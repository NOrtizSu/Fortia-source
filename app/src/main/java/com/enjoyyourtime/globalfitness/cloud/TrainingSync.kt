package com.enjoyyourtime.globalfitness.cloud

import android.util.Log
import com.enjoyyourtime.globalfitness.training.data.ChangeOrigin
import com.enjoyyourtime.globalfitness.training.data.ExercisePhotos
import com.enjoyyourtime.globalfitness.training.data.TrainingBatch
import com.enjoyyourtime.globalfitness.training.data.TrainingRepository
import com.enjoyyourtime.globalfitness.training.data.TrainingVersions
import com.enjoyyourtime.globalfitness.training.model.ExerciseNote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/** What the cloud backup needs from the training feature. */
class TrainingBackupSource(val repository: TrainingRepository, val photos: TrainingPhotoFiles)

/** [TrainingPhotoFiles] over the app's [ExercisePhotos] folder. */
class ExercisePhotoFiles(private val photos: ExercisePhotos) : TrainingPhotoFiles {
    override fun exists(name: String): Boolean = photos.exists(name)

    override fun bytes(name: String): ByteArray? = try {
        photos.bytes(name)
    } catch (e: IOException) {
        null
    }

    override fun store(exerciseId: String, bytes: ByteArray): String = photos.store(exerciseId, bytes)

    override fun delete(name: String) = photos.delete(name)

    override fun deleteUnused(used: Set<String>, graceMs: Long) = photos.deleteUnused(used, graceMs)
}

/**
 * The training part of a cloud sync, run by [CloudSyncEngine] after the measurements under the
 * same lock, retries and status. One call: read the phone and the cloud (incrementally when the
 * [TrainingBase] is trusted), plan with [TrainingMerge], download the photos the plan needs, apply
 * on the phone in ONE [TrainingRepository] transaction, upload, forget the local tombstones the
 * server confirmed, save the base. Every step is idempotent: a sync that fails half-way is simply
 * planned again (nothing is recorded as agreed until it is confirmed). Hashing, planning and file
 * work run on [compute] / [io], never on the caller's (main) thread.
 */
class TrainingSync(
    private val repository: TrainingRepository,
    private val photos: TrainingPhotoFiles,
    private val cloud: TrainingCloud,
    private val bases: TrainingBaseStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val loadTimeoutMs: Long = LOAD_TIMEOUT_MS,
) {
    /** Edits made on this phone. The sync's own writes arrive as [ChangeOrigin.CLOUD] and are ignored (no loops). */
    val localChanges: Flow<Unit> = repository.changes.filter { it == ChangeOrigin.LOCAL }.map { }

    /**
     * Remembers that the measurement part is switching [datasetId] to [mode] (restore / replace) and
     * when, so the training part completes the same transition even if this sync fails and is retried
     * as a plain merge, while anything the user changes after this moment is merged normally. A retry
     * of the same transition keeps the original time. Call before the measurement part changes anything.
     */
    suspend fun prepareTransition(uid: String, mode: TrainingMode, datasetId: String) {
        val previous = bases.loadTrainingBase(uid)
        val since = previous?.pendingSince?.takeIf { previous.pending == mode && previous.datasetKey == datasetId } ?: clock()
        bases.saveTrainingBase(
            uid,
            TrainingBase(
                datasetKey = datasetId,
                pending = mode,
                pendingSince = since,
                photoFiles = previous?.photoFiles.orEmpty(),
                photosInFlight = previous?.photosInFlight.orEmpty(),
            ),
        )
    }

    /** Syncs the training data of [datasetId]. Returns true when some writes are still queued offline. */
    suspend fun sync(uid: String, datasetId: String): Boolean {
        awaitLoaded()
        val stored = bases.loadTrainingBase(uid)
        val mode = TrainingMerge.modeFor(stored, datasetId)
        val trusted = mode == TrainingMode.MERGE && stored?.trustedFor(datasetId) == true
        // Saved by an older version: its read-only marks are dropped and everything is read again (TrainingBase.forReader).
        val previous = stored.takeIf { trusted }?.forReader()
        val pendingSince = stored?.pendingSince.takeIf { mode != TrainingMode.MERGE }
        val inFlight = stored?.photosInFlight.orEmpty()
        val hashes = HashMap(stored?.photoFiles.orEmpty())

        val read = readLocal(hashes)
        // While a photo file is missing (restored phone), the notes are read whole so the cloud's photo is known.
        val since = previous?.cursorsOrFull(trusted = true)
            ?.let { c -> if (read.local.notes.any { it.photoMissing }) c.copy(notes = null) else c }
            ?: TrainingCursors.FULL
        val remote = cloud.fetchTraining(uid, since)
        val now = clock()
        val plan = withContext(compute) { TrainingMerge.plan(mode, read.local, remote, previous, now, pendingSince, inFlight) }

        val applied = applyLocally(uid, plan, read, hashes, previous)
        if (plan.dropNow.isNotEmpty()) repository.dropTombstones(plan.dropNow)

        val result = if (plan.writes.isEmpty()) {
            TrainingWriteResult.NOTHING
        } else {
            cloud.writeTraining(uid, plan.writes) { put -> readPhoto(put, hashes) }
        }
        // Only now: a tombstone the server has not confirmed is uploaded again next time.
        val confirmedDeletions = plan.dropOnConfirm.filterKeys { it in result.confirmedDocs }.values
        if (confirmedDeletions.isNotEmpty()) repository.dropTombstones(confirmedDeletions)

        if (!trusted) {
            // After a restore or a first sync: photo files no note uses (e.g. of notes the cloud replaced).
            val used = repository.notes.value.values.mapNotNullTo(HashSet()) { it.photo }
            withContext(io) { photos.deleteUnused(used, CLEANUP_GRACE_MS) }
        }
        val notesNow = repository.notes.value.values
        val next = withContext(compute) {
            val usedFiles = notesNow.mapNotNullTo(HashSet()) { it.photo }
            TrainingMerge.nextBase(
                previous = previous,
                datasetKey = datasetId,
                remote = remote,
                plan = plan,
                result = result,
                skipped = applied.skipped,
                overrides = applied.overrides,
                localPhotos = usedFiles.mapNotNullTo(HashSet()) { hashes[it] },
                photoFiles = hashes.filterKeys { it in usedFiles },
                now = now,
                photosInFlight = inFlight,
            )
        }
        bases.saveTrainingBase(uid, next)
        return !result.allConfirmed
    }

    // ---- Local side ------------------------------------------------------------------------------

    private class LocalRead(
        val local: TrainingLocal,
        /** The notes as stored (local file names), by exercise id. */
        val notes: Map<String, ExerciseNote>,
        val records: Map<String, NoteRecord>,
        val seen: TrainingVersions,
    )

    private class Applied(val skipped: Set<DocKey>, val overrides: Map<DocKey, String?>)

    /** The phone's training data with photo hashes ([hashes]: file name -> hash cache, updated). */
    private suspend fun readLocal(hashes: MutableMap<String, String>): LocalRead {
        val tombstones = repository.tombstones()
        val workouts = repository.workouts.value
        val custom = repository.customExercises.value
        val notes = repository.notes.value
        val routines = repository.routines.value
        return withContext(io) {
            val records = LinkedHashMap<String, NoteRecord>()
            val files = HashMap<String, String>()
            for (n in notes.values) {
                val file = n.photo
                var hash: String? = null
                var missing = false
                if (file != null) {
                    if (!photos.exists(file)) {
                        missing = true
                    } else {
                        hash = hashes[file]
                        if (hash == null) {
                            val bytes = photos.bytes(file)
                            if (bytes == null) {
                                missing = true
                            } else if (bytes.size <= TrainingCodec.MAX_PHOTO_BYTES) {
                                // Larger files (never produced by ExercisePhotos) stay on this phone only.
                                hash = TrainingCodec.photoHash(bytes)
                                hashes[file] = hash
                            }
                        }
                    }
                    if (missing) hashes.remove(file)
                }
                if (hash != null) files.putIfAbsent(hash, file!!)
                records[n.exerciseId] = NoteRecord(n.exerciseId, n.notes, n.favorite, hash, n.updatedAt, photoMissing = missing)
            }
            LocalRead(
                local = TrainingLocal(workouts, custom, records.values.toList(), routines, tombstones, files),
                notes = notes,
                records = records,
                seen = TrainingVersions(
                    workouts = workouts.associate { it.id to it.updatedAt },
                    customExercises = custom.associate { it.id to it.updatedAt },
                    notes = notes.mapValues { it.value.updatedAt },
                    routines = routines.associate { it.id to it.updatedAt },
                ),
            )
        }
    }

    /**
     * Resolves the notes' photos (reusing the phone's file, else downloading it) and applies the plan in
     * one transaction. Items the user changed since [read] are left for the next sync. If the database
     * refuses the batch (a document it cannot store), the items are applied one by one and the refused
     * ones are skipped and logged, so one bad document never stops the training sync. Replaced photo
     * files are deleted only after the apply succeeded.
     */
    private suspend fun applyLocally(
        uid: String,
        plan: TrainingMerge.Plan,
        read: LocalRead,
        hashes: MutableMap<String, String>,
        previous: TrainingBase?,
    ): Applied {
        val changes = plan.apply
        if (changes.isEmpty) return Applied(emptySet(), emptyMap())
        val overrides = HashMap<DocKey, String?>()
        val newFiles = ArrayList<String>()
        val fetched = HashMap<String, String>()
        val refused = HashSet<DocKey>()
        val applied: TrainingBatch
        try {
            val notes = ArrayList<ExerciseNote>(changes.upsertNotes.size)
            for (n in changes.upsertNotes) {
                val before = read.notes[n.exerciseId]
                val mine = read.records[n.exerciseId]
                val hash = n.photoHash
                var file: String? = null
                if (hash != null) {
                    file = if (mine != null && !mine.photoMissing && mine.photoHash == hash) {
                        before?.photo
                    } else {
                        obtainPhoto(uid, n.exerciseId, hash, read.local.photoFiles, fetched, hashes)?.also { newFiles += it }
                    }
                    if (file == null) {
                        // The cloud does not have this photo (any more): keep the phone's own photo if it has
                        // one, and remember what is stored so the note is not uploaded back and forth.
                        val keep = before?.photo?.takeIf { mine != null && !mine.photoMissing }
                        file = keep
                        overrides[DocKey(TrainingKind.NOTE, n.exerciseId)] =
                            TrainingMerge.versionOf(n.copy(photoHash = if (keep != null) mine?.photoHash else null))
                    }
                }
                notes += ExerciseNote(n.exerciseId, n.notes, file, n.favorite, n.updatedAt)
            }
            val batch = TrainingBatch(
                upsertWorkouts = changes.upsertWorkouts,
                removeWorkouts = changes.removeWorkouts,
                upsertCustomExercises = changes.upsertCustomExercises,
                removeCustomExercises = changes.removeCustomExercises,
                upsertNotes = notes,
                removeNotes = changes.removeNotes,
                upsertRoutines = changes.upsertRoutines,
                removeRoutines = changes.removeRoutines,
            )
            applied = try {
                repository.applyRemoteIfUnchanged(batch, read.seen)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "The training database refused the cloud batch; applying it item by item", e)
                applyOneByOne(batch, read.seen, refused)
            }
        } catch (e: Throwable) {
            withContext(io) { newFiles.forEach(photos::delete) }
            throw e
        }

        val skipped = HashSet<DocKey>()
        fun leftOut(kind: TrainingKind, requested: Collection<String>, done: Collection<String>) {
            val doneSet = done.toHashSet()
            for (id in requested) {
                val key = DocKey(kind, id)
                if (id !in doneSet && key !in refused) skipped += key
            }
        }
        leftOut(TrainingKind.WORKOUT, changes.upsertWorkouts.map { it.id } + changes.removeWorkouts, applied.upsertWorkouts.map { it.id } + applied.removeWorkouts)
        leftOut(
            TrainingKind.CUSTOM_EXERCISE,
            changes.upsertCustomExercises.map { it.id } + changes.removeCustomExercises,
            applied.upsertCustomExercises.map { it.id } + applied.removeCustomExercises,
        )
        leftOut(TrainingKind.NOTE, changes.upsertNotes.map { it.exerciseId } + changes.removeNotes, applied.upsertNotes.map { it.exerciseId } + applied.removeNotes)
        leftOut(TrainingKind.ROUTINE, changes.upsertRoutines.map { it.id } + changes.removeRoutines, applied.upsertRoutines.map { it.id } + applied.removeRoutines)
        skipped.forEach { overrides.remove(it) }
        // A refused document keeps what was agreed before (the cursor moves on: it is not read again and again).
        for (key in refused) overrides[key] = previous?.entries?.get(key)

        // Old photo files of replaced or removed notes, and downloads a skipped note did not take.
        val obsolete = LinkedHashSet<String>(newFiles)
        for (n in applied.upsertNotes) read.notes[n.exerciseId]?.photo?.let { if (it != n.photo) obsolete += it }
        for (id in applied.removeNotes) read.notes[id]?.photo?.let { obsolete += it }
        val used = repository.notes.value.values.mapNotNullTo(HashSet()) { it.photo }
        val unused = obsolete.filter { it !in used }
        if (unused.isNotEmpty()) {
            withContext(io) { unused.forEach(photos::delete) }
            unused.forEach { hashes.remove(it) }
        }
        return Applied(skipped, overrides)
    }

    /** Applies [batch] one item at a time, in the batch's own order; items the database refuses go to [refused]. */
    private suspend fun applyOneByOne(batch: TrainingBatch, seen: TrainingVersions, refused: MutableSet<DocKey>): TrainingBatch {
        val done = ArrayList<TrainingBatch>()
        suspend fun one(key: DocKey, single: TrainingBatch) {
            try {
                done += repository.applyRemoteIfUnchanged(single, seen)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Skipped cloud ${key.kind.collection}/${key.id}: the training database refused it", e)
                refused += key
            }
        }
        for (c in batch.upsertCustomExercises) one(DocKey(TrainingKind.CUSTOM_EXERCISE, c.id), TrainingBatch(upsertCustomExercises = listOf(c)))
        for (n in batch.upsertNotes) one(DocKey(TrainingKind.NOTE, n.exerciseId), TrainingBatch(upsertNotes = listOf(n)))
        for (w in batch.upsertWorkouts) one(DocKey(TrainingKind.WORKOUT, w.id), TrainingBatch(upsertWorkouts = listOf(w)))
        for (r in batch.upsertRoutines) one(DocKey(TrainingKind.ROUTINE, r.id), TrainingBatch(upsertRoutines = listOf(r)))
        for (id in batch.removeWorkouts) one(DocKey(TrainingKind.WORKOUT, id), TrainingBatch(removeWorkouts = listOf(id)))
        for (id in batch.removeCustomExercises) one(DocKey(TrainingKind.CUSTOM_EXERCISE, id), TrainingBatch(removeCustomExercises = listOf(id)))
        for (id in batch.removeNotes) one(DocKey(TrainingKind.NOTE, id), TrainingBatch(removeNotes = listOf(id)))
        for (id in batch.removeRoutines) one(DocKey(TrainingKind.ROUTINE, id), TrainingBatch(removeRoutines = listOf(id)))
        return TrainingBatch(
            upsertWorkouts = done.flatMap { it.upsertWorkouts },
            removeWorkouts = done.flatMap { it.removeWorkouts },
            upsertCustomExercises = done.flatMap { it.upsertCustomExercises },
            removeCustomExercises = done.flatMap { it.removeCustomExercises },
            upsertNotes = done.flatMap { it.upsertNotes },
            removeNotes = done.flatMap { it.removeNotes },
            upsertRoutines = done.flatMap { it.upsertRoutines },
            removeRoutines = done.flatMap { it.removeRoutines },
        )
    }

    /** A photo for [exerciseId] with the bytes of [hash], copied from the phone or downloaded; null if nobody has it. */
    private suspend fun obtainPhoto(
        uid: String,
        exerciseId: String,
        hash: String,
        localFiles: Map<String, String>,
        fetched: MutableMap<String, String>,
        hashes: MutableMap<String, String>,
    ): String? {
        val source = fetched[hash] ?: localFiles[hash]
        val copied = source?.let { f -> withContext(io) { photos.bytes(f)?.takeIf { TrainingCodec.photoHash(it) == hash } } }
        val bytes = copied ?: cloud.fetchPhoto(uid, hash)?.let { b -> withContext(compute) { b.takeIf { TrainingCodec.photoHash(it) == hash } } }
            ?: return null
        val name = withContext(io) { photos.store(exerciseId, bytes) }
        hashes[name] = hash
        fetched[hash] = name
        return name
    }

    /** The bytes of an upload, re-checked against its hash (a changed file is re-hashed next time). */
    private suspend fun readPhoto(put: TrainingWrite.PutPhoto, hashes: MutableMap<String, String>): ByteArray? {
        val bytes = withContext(io) { photos.bytes(put.file)?.takeIf { TrainingCodec.photoHash(it) == put.hash } }
        if (bytes == null) hashes.remove(put.file)
        return bytes
    }

    /** Fails fast instead of waiting forever when training.db cannot be opened. */
    private suspend fun awaitLoaded() {
        val ready = withTimeoutOrNull(loadTimeoutMs) {
            combine(repository.loaded, repository.loadError) { loaded, error -> loaded || error != null }.first { it }
        }
        repository.loadError.value?.let { throw IllegalStateException("Could not open the training database: $it") }
        if (ready == null) throw IllegalStateException("The training database did not finish loading.")
    }

    private companion object {
        const val TAG = "TrainingSync"
        const val LOAD_TIMEOUT_MS = 30_000L

        /** Photo files younger than this are never cleaned up (an editor may hold an unsaved photo). */
        const val CLEANUP_GRACE_MS = 24L * 60 * 60_000
    }
}
