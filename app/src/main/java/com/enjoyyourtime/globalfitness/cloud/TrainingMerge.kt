package com.enjoyyourtime.globalfitness.cloud

import com.enjoyyourtime.globalfitness.training.data.TrainingRepository
import com.enjoyyourtime.globalfitness.training.model.CustomExercise
import com.enjoyyourtime.globalfitness.training.model.Muscle
import com.enjoyyourtime.globalfitness.training.model.Routine
import com.enjoyyourtime.globalfitness.training.model.Workout
import java.security.MessageDigest
import com.enjoyyourtime.globalfitness.training.model.Tombstone as LocalTombstone

/** Training changes the sync applies on the phone (notes carry the photo hash; the sync resolves the file). */
data class LocalTrainingChanges(
    val upsertWorkouts: List<Workout> = emptyList(),
    val removeWorkouts: List<String> = emptyList(),
    val upsertCustomExercises: List<CustomExercise> = emptyList(),
    val removeCustomExercises: List<String> = emptyList(),
    val upsertNotes: List<NoteRecord> = emptyList(),
    val removeNotes: List<String> = emptyList(),
    val upsertRoutines: List<Routine> = emptyList(),
    val removeRoutines: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = upsertWorkouts.isEmpty() && removeWorkouts.isEmpty() && upsertCustomExercises.isEmpty() &&
            removeCustomExercises.isEmpty() && upsertNotes.isEmpty() && removeNotes.isEmpty() &&
            upsertRoutines.isEmpty() && removeRoutines.isEmpty()
}

/**
 * Pure merge of the training data (workouts, custom exercises, exercise notes and their photos, the
 * user's routines) with the cloud copy. No Android, no Firebase; unit-tested.
 *
 * Unlike measurements, every training document carries a client `updatedAt` that the repository
 * bumps past the version it edited (`max(now, previous + 1)`, deletions too), and every local
 * deletion leaves a tombstone. So the rule per document is plain last-writer-wins:
 * - both sides live and different: the higher `updatedAt` wins (ties: the cloud copy). This also
 *   heals a cloud copy overwritten by an older, late write.
 * - live vs tombstone: the tombstone wins over a live copy with `updatedAt <= deletedAt`, and always
 *   over the very version this phone had agreed on (whatever the clocks say). A deletion of a known
 *   version is uploaded with `deletedAt` past that version, so no phone resurrects it.
 * - the [TrainingBase] tells which local documents changed since the last sync (uploads are decided
 *   without reading the whole collection), which version was agreed, and which documents were
 *   deleted: a live cloud copy not newer than a known deletion (an old write that landed late) gets
 *   the tombstone again instead of coming back.
 * - a local tombstone is always uploaded unless the cloud already shows one: an unconfirmed put of
 *   that document may still be waiting in Firestore's offline queue.
 * - a custom exercise used by a workout or a routine is never removed from the phone: it is kept
 *   archived (the history and the routines keep its name), newer than the tombstone, as the
 *   repository does for a local removal. An exercise the phone does not know yet (a workout or
 *   routine restored before its machine) is shown as unknown until it arrives.
 *
 * Photos are content addressed (SHA-256): uploaded only when the cloud does not have that hash yet
 * (and not again while an earlier upload waits in the offline queue), deleted when no note uses them
 * any more. A note whose photo file is missing on the phone ([NoteRecord.photoMissing]) never changes
 * the cloud's photo: it takes the cloud's and downloads it.
 */
object TrainingMerge {
    /** How long a known deletion is remembered against late writes. */
    const val DEAD_ENTRY_TTL_MS = 30L * 24 * 60 * 60_000

    /** An unconfirmed photo upload is not queued again for this long. */
    const val PHOTO_IN_FLIGHT_MS = 24L * 60 * 60_000

    class Plan internal constructor(
        val mode: TrainingMode,
        /** Apply these on the phone (one transaction). */
        val apply: LocalTrainingChanges,
        /** Upload these photos first... */
        val photoUploads: List<TrainingWrite.PutPhoto>,
        /** ...then these documents and tombstones... */
        val docWrites: List<TrainingWrite>,
        /** ...and finally delete these photos no note uses any more. */
        val photoDeletes: List<TrainingWrite.DeletePhoto>,
        /** Local tombstones that need no upload (the cloud already shows a tombstone). */
        val dropNow: List<LocalTombstone>,
        /** Local tombstones to forget once the server confirmed the tombstone write of their document. */
        val dropOnConfirm: Map<DocKey, LocalTombstone>,
        /** Base entries that do not depend on a write (null removes the entry). */
        internal val settled: Map<DocKey, String?>,
        /** Base entries recorded once the write of that document is confirmed. */
        internal val onConfirm: Map<DocKey, String?>,
        /** Photo hashes the cloud notes read in this sync use (so they exist in the cloud). */
        val remotePhotos: Set<String>,
        /** Collections to read whole next time (something the phone needs was not in this read). */
        val refetch: Set<TrainingKind>,
        /** Documents whose cloud copy is from a newer app version, after this read. */
        val readOnly: Set<DocKey>,
    ) {
        val writes: List<TrainingWrite> get() = photoUploads + docWrites + photoDeletes

        val isNoOp: Boolean get() = apply.isEmpty && writes.isEmpty() && dropNow.isEmpty()
    }

    // ---- Mode ----------------------------------------------------------------------------------

    /**
     * The training side of a dataset transition decided for the measurements, or null for a plain
     * merge. Only the user's explicit choices replace a side: an automatic restore (fresh phone, no
     * measurements) keeps what was logged on the phone, and an automatic "this phone's data to the
     * cloud" (the cloud holds none of our dataset) never deletes training in the cloud.
     */
    fun transitionFor(mode: SyncMode, choice: ConflictChoice?): TrainingMode? = when (mode) {
        SyncMode.AdoptCloud -> if (choice == ConflictChoice.RESTORE_CLOUD) TrainingMode.ADOPT_REPLACE else TrainingMode.ADOPT_MERGE
        SyncMode.ReplaceCloud -> if (choice == ConflictChoice.REPLACE_CLOUD) TrainingMode.REPLACE_CLOUD else TrainingMode.UNION
        else -> null
    }

    /** A pending transition of this very dataset is finished first; otherwise a merge. */
    fun modeFor(base: TrainingBase?, datasetId: String): TrainingMode =
        base?.pending?.takeIf { base.datasetKey == datasetId } ?: TrainingMode.MERGE

    // ---- Versions ------------------------------------------------------------------------------

    /** "<updatedAt>.<sha1 of the content>": equal versions are the same document state. */
    fun versionOf(w: Workout): String {
        val c = Canon().l(w.startedAt).l(w.endedAt).s(w.name).s(w.note).l(w.entries.size.toLong())
        for (e in w.entries) {
            c.s(e.id).s(e.exerciseId).s(e.note).l(e.sets.size.toLong())
            for (s in e.sets) {
                c.s(s.id).d(s.weightKg).l(s.reps?.toLong()).l(s.durationSec?.toLong()).d(s.distanceM)
                    .l(s.level?.toLong()).l(s.kcal?.toLong()).b(s.warmup).b(s.done).l(s.doneAt)
            }
        }
        // Only when there are any: the versions of workouts without pauses stay what they were.
        if (w.pauses.isNotEmpty()) {
            c.s("pauses").l(w.pauses.size.toLong())
            for (p in w.pauses) c.l(p.start).l(p.end)
        }
        return "${w.updatedAt}.${c.digest()}"
    }

    fun versionOf(e: CustomExercise): String {
        val c = Canon().s(e.name).s(e.kind.code).s(e.equipment.code).s(Muscle.encode(e.primary)).s(Muscle.encode(e.secondary))
            .b(e.archived).l(e.createdAt)
        return "${e.updatedAt}.${c.digest()}"
    }

    /** The photo counts by hash; [NoteRecord.photoMissing] is local bookkeeping and does not. */
    fun versionOf(n: NoteRecord): String = "${n.updatedAt}.${Canon().s(n.notes).b(n.favorite).s(n.photoHash).digest()}"

    fun versionOf(r: Routine): String {
        val c = Canon().s(r.name).s(r.note).l(r.createdAt).l(r.exercises.size.toLong())
        for (e in r.exercises) c.s(e.exerciseId).l(e.sets.toLong()).l(e.reps?.toLong()).l(e.durationSec?.toLong())
        return "${r.updatedAt}.${c.digest()}"
    }

    /** The `updatedAt` of a live version string (null for a deletion entry). */
    fun updatedAtOf(version: String): Long? = version.substringBefore('.').toLongOrNull()

    /** Base entry of a document known to be deleted at [deletedAt]. */
    fun dead(deletedAt: Long): String = "$DEAD_PREFIX$deletedAt"

    /** The deletion time of a [dead] entry, null for a live version. */
    fun deadAtOf(entry: String?): Long? = entry?.takeIf { it.startsWith(DEAD_PREFIX) }?.substring(DEAD_PREFIX.length)?.toLongOrNull()

    // ---- Plan ----------------------------------------------------------------------------------

    /**
     * [base] must be trusted (same dataset, no pending transition) or null; in the transition modes
     * and without base, [remote] must be a complete read. [pendingSince]: when the transition was
     * decided (documents changed later are merged normally). [photosInFlight]: unconfirmed photo
     * uploads of this phone, by hash, with the time they were queued.
     */
    fun plan(
        mode: TrainingMode,
        local: TrainingLocal,
        remote: TrainingCloudSnapshot,
        base: TrainingBase?,
        now: Long,
        pendingSince: Long? = null,
        photosInFlight: Map<String, Long> = emptyMap(),
    ): Plan {
        val trusted = mode == TrainingMode.MERGE
        val entries = if (trusted) base?.entries.orEmpty() else emptyMap()
        val readOnlyBefore = if (trusted) base?.readOnly.orEmpty() else emptySet()
        val since = pendingSince.takeIf { mode != TrainingMode.MERGE }
        val dead = local.tombstones.groupBy { TrainingKind.ofTombstone(it.kind) }
        val refetch = HashSet<TrainingKind>()

        fun <T> readOnlyIds(kind: TrainingKind, docs: RemoteDocs<T>, id: (T) -> String): Set<String> {
            val seen = docs.live.mapTo(HashSet(), id) + docs.dead.map { it.id }
            return readOnlyBefore.filter { it.kind == kind && it.id !in seen }.mapTo(HashSet()) { it.id } + docs.readOnly
        }
        val roWorkouts = readOnlyIds(TrainingKind.WORKOUT, remote.workouts) { it.id }
        val roCustom = readOnlyIds(TrainingKind.CUSTOM_EXERCISE, remote.customExercises) { it.id }
        val roNotes = readOnlyIds(TrainingKind.NOTE, remote.notes) { it.exerciseId }
        val roRoutines = readOnlyIds(TrainingKind.ROUTINE, remote.routines) { it.id }

        val w = mergeKind(
            mode, local.workouts, dead[TrainingKind.WORKOUT].orEmpty(), remote.workouts, entriesOf(entries, TrainingKind.WORKOUT), now,
            id = { it.id }, updatedAt = { it.updatedAt }, version = ::versionOf, since = since, readOnly = roWorkouts,
        )
        sanitizeIds(w, local.workouts)
        if (w.lost) refetch += TrainingKind.WORKOUT

        val c = mergeKind(
            mode, local.customExercises, dead[TrainingKind.CUSTOM_EXERCISE].orEmpty(),
            remote.customExercises, entriesOf(entries, TrainingKind.CUSTOM_EXERCISE), now,
            id = { it.id }, updatedAt = { it.updatedAt }, version = ::versionOf, since = since, readOnly = roCustom,
        )
        if (c.lost) refetch += TrainingKind.CUSTOM_EXERCISE

        // Routines: plain documents, last writer wins like the others.
        val r = mergeKind(
            mode, local.routines, dead[TrainingKind.ROUTINE].orEmpty(), remote.routines, entriesOf(entries, TrainingKind.ROUTINE), now,
            id = { it.id }, updatedAt = { it.updatedAt }, version = ::versionOf, since = since, readOnly = roRoutines,
        )
        if (r.lost) refetch += TrainingKind.ROUTINE

        // Exercises the workouts and routines on the phone will use after this sync.
        val finalWorkouts = LinkedHashMap<String, Workout>()
        for (x in local.workouts) finalWorkouts[x.id] = x
        for (id in w.removeLocal) finalWorkouts.remove(id)
        for (x in w.apply) finalWorkouts[x.id] = x
        val finalRoutines = LinkedHashMap<String, Routine>()
        for (x in local.routines) finalRoutines[x.id] = x
        for (id in r.removeLocal) finalRoutines.remove(id)
        for (x in r.apply) finalRoutines[x.id] = x
        val usedExercises = TrainingRepository.exercisesInUse(finalWorkouts.values, finalRoutines.values)
        if (protectUsedCustom(c, local.customExercises, remote.customExercises, usedExercises)) refetch += TrainingKind.CUSTOM_EXERCISE

        // A note whose photo file is missing takes the cloud's photo (or none if the cloud has no note).
        val remoteNotes = LinkedHashMap<String, NoteRecord>().apply { for (r in remote.notes.live) putIfAbsent(r.exerciseId, r) }
        val remoteDeadNotes = remote.notes.dead.mapTo(HashSet()) { it.id }
        val repairs = LinkedHashMap<String, NoteRecord>()
        val unresolved = HashSet<String>()
        val effectiveNotes = local.notes.mapNotNull { note ->
            if (!note.photoMissing) return@mapNotNull note
            val r = remoteNotes[note.exerciseId]
            val resolved = when {
                r != null -> note.copy(photoHash = r.photoHash, photoMissing = false)
                remote.notes.complete || note.exerciseId in remoteDeadNotes -> note.copy(photoHash = null, photoMissing = false)
                else -> null // only a complete read tells; the sync reads notes fully while any photo is missing
            }
            if (resolved == null) unresolved += note.exerciseId else repairs[note.exerciseId] = resolved
            resolved
        }
        val n = mergeKind(
            mode, effectiveNotes, dead[TrainingKind.NOTE].orEmpty(), remote.notes, entriesOf(entries, TrainingKind.NOTE), now,
            id = { it.exerciseId }, updatedAt = { it.updatedAt }, version = ::versionOf, skip = unresolved, since = since, readOnly = roNotes,
        )
        if (n.lost) refetch += TrainingKind.NOTE
        val applied = n.apply.mapTo(HashSet()) { it.exerciseId }
        for ((id, note) in repairs) {
            if (id !in applied && id !in n.removeLocal) n.apply += note
        }

        // ---- Photos ----
        val remotePhotos = remote.notes.live.mapNotNullTo(LinkedHashSet()) { it.photoHash }
        val photoUploads = LinkedHashMap<String, TrainingWrite.PutPhoto>()
        val photoDeletes = ArrayList<TrainingWrite.DeletePhoto>()
        if (mode != TrainingMode.ADOPT_REPLACE || n.put.isNotEmpty()) {
            val known = if (trusted) base?.photos.orEmpty() else emptySet()
            val queued = photosInFlight.filterValues { now - it < PHOTO_IN_FLIGHT_MS }.keys
            val original = local.notes.associateBy { it.exerciseId }
            for (put in n.put) {
                val hash = put.photoHash ?: continue
                val mine = original[put.exerciseId] ?: continue
                // A repaired note carries the cloud's own photo: nothing to upload.
                if (mine.photoMissing || mine.photoHash != hash) continue
                val theirs = remoteNotes[put.exerciseId]
                // Re-sent when this note beats a cloud version with another photo (that writer may have deleted ours).
                val needed = hash !in remotePhotos && (hash !in known || (theirs != null && theirs.photoHash != hash))
                if (needed && hash !in queued && hash !in photoUploads) {
                    local.photoFiles[hash]?.let { photoUploads[hash] = TrainingWrite.PutPhoto(hash, it) }
                }
            }
            // A photo is deleted only when no note uses it after this sync, and never while the photo of
            // some note is still unknown (missing file, notes not read whole).
            if (unresolved.isEmpty() && mode != TrainingMode.ADOPT_REPLACE) {
                val final = LinkedHashMap<String, NoteRecord>()
                for (note in effectiveNotes) final[note.exerciseId] = note
                for (id in n.removeLocal) final.remove(id)
                for (note in n.apply) final[note.exerciseId] = note
                val used = final.values.mapNotNullTo(HashSet()) { it.photoHash }
                for (hash in LinkedHashSet(known + remotePhotos)) {
                    if (hash !in used) photoDeletes += TrainingWrite.DeletePhoto(hash)
                }
            }
        }

        val docWrites = ArrayList<TrainingWrite>()
        c.put.mapTo(docWrites) { TrainingWrite.PutCustomExercise(it) }
        n.put.mapTo(docWrites) { TrainingWrite.PutNote(it) }
        w.put.mapTo(docWrites) { TrainingWrite.PutWorkout(it) }
        r.put.mapTo(docWrites) { TrainingWrite.PutRoutine(it) }
        c.delete.mapTo(docWrites) { TrainingWrite.Delete(TrainingKind.CUSTOM_EXERCISE, it.id, it.deletedAt) }
        n.delete.mapTo(docWrites) { TrainingWrite.Delete(TrainingKind.NOTE, it.id, it.deletedAt) }
        w.delete.mapTo(docWrites) { TrainingWrite.Delete(TrainingKind.WORKOUT, it.id, it.deletedAt) }
        r.delete.mapTo(docWrites) { TrainingWrite.Delete(TrainingKind.ROUTINE, it.id, it.deletedAt) }

        val settled = LinkedHashMap<DocKey, String?>()
        val onConfirm = LinkedHashMap<DocKey, String?>()
        val dropOnConfirm = LinkedHashMap<DocKey, LocalTombstone>()
        for ((kind, m) in listOf(TrainingKind.WORKOUT to w, TrainingKind.CUSTOM_EXERCISE to c, TrainingKind.NOTE to n, TrainingKind.ROUTINE to r)) {
            m.settled.forEach { (id, v) -> settled[DocKey(kind, id)] = v }
            m.onConfirm.forEach { (id, v) -> onConfirm[DocKey(kind, id)] = v }
            m.dropOnConfirm.forEach { (id, t) -> dropOnConfirm[DocKey(kind, id)] = t }
        }
        val readOnly = HashSet<DocKey>()
        roWorkouts.mapTo(readOnly) { DocKey(TrainingKind.WORKOUT, it) }
        roCustom.mapTo(readOnly) { DocKey(TrainingKind.CUSTOM_EXERCISE, it) }
        roNotes.mapTo(readOnly) { DocKey(TrainingKind.NOTE, it) }
        roRoutines.mapTo(readOnly) { DocKey(TrainingKind.ROUTINE, it) }
        return Plan(
            mode = mode,
            apply = LocalTrainingChanges(
                upsertWorkouts = w.apply,
                removeWorkouts = w.removeLocal.toList(),
                upsertCustomExercises = c.apply,
                removeCustomExercises = c.removeLocal.toList(),
                upsertNotes = n.apply,
                removeNotes = n.removeLocal.toList(),
                upsertRoutines = r.apply,
                removeRoutines = r.removeLocal.toList(),
            ),
            photoUploads = photoUploads.values.toList(),
            docWrites = docWrites,
            photoDeletes = photoDeletes,
            dropNow = w.dropNow + c.dropNow + n.dropNow + r.dropNow,
            dropOnConfirm = dropOnConfirm,
            settled = settled,
            onConfirm = onConfirm,
            remotePhotos = remotePhotos,
            refetch = refetch,
            readOnly = readOnly,
        )
    }

    // ---- Next base -----------------------------------------------------------------------------

    /**
     * The base to store after the plan ran. [previous] is the trusted base the plan was made with
     * (null otherwise). [skipped]: documents whose local apply was dropped because the user changed
     * them during the sync (decided again next time; their collection is pulled again from the old
     * cursor). [overrides]: what the phone really stored when it differs from the plan (a photo the
     * cloud could not provide, a document the database refused). [localPhotos]: photo hashes the
     * phone's notes use now. [photosInFlight]: the unconfirmed photo uploads known before this sync.
     */
    fun nextBase(
        previous: TrainingBase?,
        datasetKey: String,
        remote: TrainingCloudSnapshot,
        plan: Plan,
        result: TrainingWriteResult,
        skipped: Set<DocKey>,
        overrides: Map<DocKey, String?>,
        localPhotos: Set<String>,
        photoFiles: Map<String, String>,
        now: Long = 0L,
        photosInFlight: Map<String, Long> = emptyMap(),
    ): TrainingBase {
        val entries = LinkedHashMap(previous?.entries.orEmpty())
        fun set(key: DocKey, version: String?) {
            if (version == null) entries.remove(key) else entries[key] = version
        }
        for ((key, version) in plan.settled) if (key !in skipped) set(key, version)
        for ((key, version) in plan.onConfirm) if (key in result.confirmedDocs) set(key, version)
        for ((key, version) in overrides) set(key, version)
        entries.entries.removeAll { (_, v) -> deadAtOf(v)?.let { it < now - DEAD_ENTRY_TTL_MS } == true }

        val skippedKinds = skipped.mapTo(HashSet()) { it.kind }
        val cursors = LinkedHashMap<TrainingKind, Long>()
        for ((kind, docs) in listOf(
            TrainingKind.WORKOUT to remote.workouts,
            TrainingKind.CUSTOM_EXERCISE to remote.customExercises,
            TrainingKind.NOTE to remote.notes,
            TrainingKind.ROUTINE to remote.routines,
        )) {
            val before = previous?.cursors?.get(kind)
            val cursor = when {
                kind in plan.refetch -> null
                kind in skippedKinds -> before
                docs.complete -> docs.maxUpdatedAt
                else -> maxOfNullable(before, docs.maxUpdatedAt)
            }
            if (cursor != null) cursors[kind] = cursor
        }

        // A confirmed note means the photo queued before it reached the server too (Firestore keeps the order).
        val waiting = photosInFlight.keys + result.queuedPhotos
        val landedWithNotes = plan.docWrites.filterIsInstance<TrainingWrite.PutNote>()
            .filter { DocKey(TrainingKind.NOTE, it.note.exerciseId) in result.confirmedDocs }
            .mapNotNullTo(HashSet()) { it.note.photoHash?.takeIf { h -> h in waiting } }
        val known = previous?.photos.orEmpty() + plan.remotePhotos + result.uploadedPhotos + landedWithNotes - result.deletedPhotos
        val stillWaiting = photosInFlight.filterValues { now - it < PHOTO_IN_FLIGHT_MS }
        val inFlight = (stillWaiting + result.queuedPhotos.filter { it !in stillWaiting }.associateWith { now })
            .filterKeys { it in localPhotos && it !in known }
        return TrainingBase(
            datasetKey = datasetKey,
            pending = null,
            pendingSince = null,
            cursors = cursors,
            entries = entries,
            photos = localPhotos.filterTo(LinkedHashSet()) { it in known },
            photoFiles = photoFiles,
            photosInFlight = inFlight,
            readOnly = plan.readOnly,
        )
    }

    // ---- Internals -----------------------------------------------------------------------------

    private const val DEAD_PREFIX = "D:"

    /** A tombstone to upload for document [id]. */
    internal data class DocDeletion(val id: String, val deletedAt: Long)

    /** The base entries of one collection, by document id. */
    internal fun entriesOf(entries: Map<DocKey, String>, kind: TrainingKind): Map<String, String> =
        HashMap<String, String>().apply { for ((k, v) in entries) if (k.kind == kind) put(k.id, v) }

    /**
     * The merge of one collection (also used by the food log's sync, [FoodMerge]): what to apply on the
     * phone, upload and delete, and the base entries that follow (by document id).
     */
    internal class KindMerge<T> {
        val apply = ArrayList<T>()
        val removeLocal = LinkedHashSet<String>()
        val put = ArrayList<T>()
        val delete = ArrayList<DocDeletion>()
        val dropNow = ArrayList<LocalTombstone>()
        val dropOnConfirm = LinkedHashMap<String, LocalTombstone>()
        val settled = LinkedHashMap<String, String?>()
        val onConfirm = LinkedHashMap<String, String?>()

        /** The base lists a document the phone lost without a tombstone, and this read was incremental. */
        var lost = false
    }

    /**
     * Last-writer-wins merge of one collection in [mode] (see the class comment). [base]: the agreed
     * version or deletion of each document, by id (empty when untrusted).
     */
    internal fun <T> mergeKind(
        mode: TrainingMode,
        local: List<T>,
        localDead: List<LocalTombstone>,
        remote: RemoteDocs<T>,
        base: Map<String, String>,
        now: Long,
        id: (T) -> String,
        updatedAt: (T) -> Long,
        version: (T) -> String,
        skip: Set<String> = emptySet(),
        since: Long? = null,
        readOnly: Set<String> = emptySet(),
    ): KindMerge<T> {
        val out = KindMerge<T>()
        val mine = LinkedHashMap<String, T>().apply { for (x in local) putIfAbsent(id(x), x) }
        val myDead = LinkedHashMap<String, LocalTombstone>().apply {
            for (t in localDead) {
                val previous = get(t.id)
                if (previous == null || t.deletedAt > previous.deletedAt) put(t.id, t)
            }
        }
        val theirs = LinkedHashMap<String, T>().apply { for (x in remote.live) putIfAbsent(id(x), x) }
        val theirDead = LinkedHashMap<String, Tombstone>().apply { for (t in remote.dead) if (t.id !in theirs) putIfAbsent(t.id, t) }
        val ids = LinkedHashSet<String>().apply {
            addAll(mine.keys)
            addAll(myDead.keys)
            addAll(theirs.keys)
            addAll(theirDead.keys)
            addAll(base.keys)
            removeAll(skip)
        }

        fun apply(x: T) {
            out.apply += x
            out.settled[id(x)] = version(x)
        }

        fun put(x: T) {
            // The cloud copy is from a newer app version: kept as it is (see TrainingCodec).
            if (id(x) in readOnly) return
            out.put += x
            out.onConfirm[id(x)] = version(x)
        }

        fun removeLocal(key: String, deletedAt: Long?) {
            out.removeLocal += key
            out.settled[key] = deletedAt?.let(::dead)
        }

        fun delete(key: String, deletedAt: Long, tombstone: LocalTombstone?) {
            out.delete += DocDeletion(key, deletedAt)
            out.onConfirm[key] = dead(deletedAt)
            if (tombstone != null) out.dropOnConfirm[key] = tombstone
        }

        for (key in ids) {
            val l = mine[key]
            var lt = myDead[key]
            var r = theirs[key]
            var rt = theirDead[key]
            val b = base[key]
            // Completing a transition: what changed since it was decided is merged normally.
            val keyMode = if (since != null && (
                    (l != null && updatedAt(l) > since) || (lt != null && lt.deletedAt > since) ||
                        (r != null && updatedAt(r) > since) || (rt != null && rt.deletedAt > since)
                    )
            ) {
                TrainingMode.MERGE
            } else {
                mode
            }
            // A known deletion: a live cloud copy not newer than it is an old write that landed late.
            var lateWrite: Long? = null
            val deadAt = deadAtOf(b)
            if (deadAt != null) {
                if (r != null && updatedAt(r) <= deadAt) {
                    r = null
                    lateWrite = deadAt
                }
                if (r == null && rt == null) rt = Tombstone(key, deadAt)
            }
            if (lt != null && l != null) {
                out.dropNow += lt // re-created after the deletion
                lt = null
            }
            // Taking over another dataset: a deletion made before must not delete the cloud's copy.
            val oldDeletion = keyMode == TrainingMode.ADOPT_MERGE || keyMode == TrainingMode.ADOPT_REPLACE || keyMode == TrainingMode.UNION
            if (lt != null && oldDeletion && (r != null || rt != null)) {
                out.dropNow += lt
                lt = null
            }
            when (keyMode) {
                TrainingMode.MERGE -> when {
                    l != null && r != null -> {
                        val vl = version(l)
                        val vr = version(r)
                        when {
                            vl == vr -> out.settled[key] = vl
                            updatedAt(l) > updatedAt(r) -> put(l)
                            else -> apply(r)
                        }
                    }
                    l != null && rt != null ->
                        if (b == version(l) || updatedAt(l) <= rt.deletedAt) removeLocal(key, rt.deletedAt) else put(l)
                    l != null -> if (b != version(l)) put(l)
                    lt != null && r != null -> {
                        val vr = version(r)
                        when {
                            // Deleted the very version we had agreed on: the deletion is newer, whatever the clocks say.
                            b == vr -> delete(key, maxOf(lt.deletedAt, updatedAt(r) + 1), lt)
                            updatedAt(r) > lt.deletedAt -> {
                                apply(r) // edited elsewhere after the deletion here
                                out.dropNow += lt
                            }
                            else -> delete(key, lt.deletedAt, lt)
                        }
                    }
                    lt != null && rt != null -> if (theirDead[key] != null) {
                        out.dropNow += lt
                        out.settled[key] = dead(rt.deletedAt)
                    } else {
                        // Only remembered: the cloud may hold a newer put still in the offline queue.
                        delete(key, maxOf(lt.deletedAt, rt.deletedAt), lt)
                    }
                    // Not in this read: uploaded anyway (cheap), an unconfirmed put may still be queued.
                    lt != null -> delete(key, maxOf(lt.deletedAt, (b?.let(::updatedAtOf) ?: lt.deletedAt - 1) + 1), lt)
                    r != null -> apply(r)
                    rt != null -> out.settled[key] = dead(rt.deletedAt)
                    else -> {
                        // The base lists it but the phone lost it (e.g. training.db recreated): read it again.
                        out.settled[key] = null
                        if (!remote.complete) out.lost = true
                    }
                }
                TrainingMode.ADOPT_MERGE -> when {
                    l != null && r != null -> if (version(l) == version(r)) out.settled[key] = version(l) else apply(r)
                    l != null && rt != null -> if (updatedAt(l) > rt.deletedAt) put(l) else removeLocal(key, rt.deletedAt)
                    l != null -> put(l)
                    lt != null -> delete(key, lt.deletedAt, lt)
                    r != null -> apply(r)
                    rt != null -> out.settled[key] = dead(rt.deletedAt)
                    else -> out.settled[key] = null
                }
                TrainingMode.UNION -> when {
                    l != null && r != null -> {
                        val vl = version(l)
                        val vr = version(r)
                        when {
                            vl == vr -> out.settled[key] = vl
                            updatedAt(l) > updatedAt(r) -> put(l)
                            else -> apply(r)
                        }
                    }
                    l != null && rt != null -> if (updatedAt(l) > rt.deletedAt) put(l) else removeLocal(key, rt.deletedAt)
                    l != null -> put(l)
                    lt != null -> delete(key, lt.deletedAt, lt)
                    r != null -> apply(r)
                    rt != null -> out.settled[key] = dead(rt.deletedAt)
                    else -> out.settled[key] = null
                }
                TrainingMode.ADOPT_REPLACE -> when {
                    l != null && r != null -> if (version(l) == version(r)) out.settled[key] = version(l) else apply(r)
                    l != null -> removeLocal(key, rt?.deletedAt)
                    lt != null -> delete(key, lt.deletedAt, lt)
                    r != null -> apply(r)
                    rt != null -> out.settled[key] = dead(rt.deletedAt)
                    else -> out.settled[key] = null
                }
                TrainingMode.REPLACE_CLOUD -> when {
                    l != null && r != null -> if (version(l) == version(r)) out.settled[key] = version(l) else put(l)
                    l != null -> put(l)
                    r != null -> delete(key, maxOf(now, updatedAt(r) + 1), lt)
                    lt != null && rt != null -> {
                        out.dropNow += lt
                        out.settled[key] = dead(rt.deletedAt)
                    }
                    lt != null -> delete(key, lt.deletedAt, lt)
                    rt != null -> out.settled[key] = dead(rt.deletedAt)
                    else -> out.settled[key] = null
                }
            }
            // The late write is replaced by the tombstone again, unless a newer local copy is uploaded instead.
            if (lateWrite != null && key !in out.onConfirm) delete(key, lateWrite, null)
        }
        return out
    }

    /**
     * A custom exercise that a workout or routine on the phone uses is never removed (they would show
     * "unknown exercise" forever): it is kept archived, newer than the tombstone that removed it, and
     * uploaded so the other phones keep it too. Returns true when it is not in this read (read again).
     */
    private fun protectUsedCustom(
        c: KindMerge<CustomExercise>,
        local: List<CustomExercise>,
        remote: RemoteDocs<CustomExercise>,
        used: Set<String>,
    ): Boolean {
        var refetch = false
        val mine = local.associateBy { it.id }
        val theirs = remote.live.associateBy { it.id }
        // Tombstone uploads first (a removal below may replace one with the archived copy).
        for (d in c.delete.filter { it.id in used }) {
            c.delete.remove(d)
            c.onConfirm.remove(d.id)
            val tombstone = c.dropOnConfirm.remove(d.id)
            if (tombstone != null) c.dropNow += tombstone
            if (d.id in mine) continue
            val r = theirs[d.id]
            if (r == null) {
                refetch = true
                continue
            }
            val kept = if (tombstone != null) r.copy(archived = true, updatedAt = maxOf(r.updatedAt, tombstone.deletedAt) + 1) else r
            c.apply += kept
            if (kept != r) {
                c.put += kept
                c.onConfirm[d.id] = versionOf(kept)
            } else {
                c.settled[d.id] = versionOf(r)
            }
        }
        for (id in c.removeLocal.filter { it in used }) {
            val l = mine[id] ?: continue
            c.removeLocal.remove(id)
            val deletedAt = deadAtOf(c.settled.remove(id))
            val kept = if (deletedAt != null) l.copy(archived = true, updatedAt = maxOf(l.updatedAt, deletedAt) + 1) else l
            if (kept != l) c.apply += kept
            c.put += kept
            c.onConfirm[id] = versionOf(kept)
        }
        return refetch
    }

    /**
     * Entry and set ids are unique in the phone's database: a cloud workout reusing an id of another
     * workout (or of another workout in the same batch) gets a stable [TrainingCodec.derivedId]. The
     * phone then remembers its own version as agreed, so it is not uploaded back and forth.
     */
    private fun sanitizeIds(w: KindMerge<Workout>, local: List<Workout>) {
        if (w.apply.isEmpty()) return
        val entryOwner = HashMap<String, String>()
        val setOwner = HashMap<String, String>()
        for (x in local) {
            for (e in x.entries) {
                entryOwner.putIfAbsent(e.id, x.id)
                for (s in e.sets) setOwner.putIfAbsent(s.id, x.id)
            }
        }
        fun free(id: String, workoutId: String, owner: MutableMap<String, String>): String {
            var candidate = id
            var n = 0
            while (true) {
                val o = owner[candidate]
                if (o == null || o == workoutId) {
                    owner[candidate] = workoutId
                    return candidate
                }
                candidate = TrainingCodec.derivedId("$workoutId/$id/$n")
                n++
            }
        }
        val fixed = w.apply.map { workout ->
            val entries = workout.entries.map { e ->
                val entryId = free(e.id, workout.id, entryOwner)
                val sets = e.sets.map { s ->
                    val setId = free(s.id, workout.id, setOwner)
                    if (setId == s.id) s else s.copy(id = setId)
                }
                if (entryId == e.id && sets == e.sets) e else e.copy(id = entryId, sets = sets)
            }
            if (entries == workout.entries) {
                workout
            } else {
                workout.copy(entries = entries).also { w.settled[it.id] = versionOf(it) }
            }
        }
        w.apply.clear()
        w.apply += fixed
    }

    private fun maxOfNullable(a: Long?, b: Long?): Long? = when {
        a == null -> b
        b == null -> a
        else -> maxOf(a, b)
    }

    /** Unambiguous canonical text of a record (length-prefixed strings, explicit nulls), hashed with SHA-1. */
    internal class Canon {
        private val sb = StringBuilder()

        fun s(v: String?) = apply { if (v == null) sb.append("N;") else sb.append('S').append(v.length).append(':').append(v).append(';') }

        fun l(v: Long?) = apply { if (v == null) sb.append("N;") else sb.append('L').append(v).append(';') }

        fun d(v: Double?) = apply { if (v == null) sb.append("N;") else sb.append('D').append(v.toString()).append(';') }

        fun b(v: Boolean) = apply { sb.append(if (v) "T;" else "F;") }

        fun digest(): String =
            MessageDigest.getInstance("SHA-1").digest(sb.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
