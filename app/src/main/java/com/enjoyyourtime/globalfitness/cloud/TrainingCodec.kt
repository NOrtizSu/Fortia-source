package com.enjoyyourtime.globalfitness.cloud

import com.enjoyyourtime.globalfitness.cloud.FirestoreCodec.Decoded
import com.enjoyyourtime.globalfitness.cloud.FirestoreCodec.F_DELETED
import com.enjoyyourtime.globalfitness.cloud.FirestoreCodec.F_DELETED_AT
import com.enjoyyourtime.globalfitness.cloud.FirestoreCodec.F_EDITED_AT
import com.enjoyyourtime.globalfitness.cloud.FirestoreCodec.F_SCHEMA
import com.enjoyyourtime.globalfitness.training.catalog.RoutineCatalog
import com.enjoyyourtime.globalfitness.training.data.TrainingRepository
import com.enjoyyourtime.globalfitness.training.domain.WorkoutTime
import com.enjoyyourtime.globalfitness.training.model.CustomExercise
import com.enjoyyourtime.globalfitness.training.model.Equipment
import com.enjoyyourtime.globalfitness.training.model.ExerciseKind
import com.enjoyyourtime.globalfitness.training.model.Muscle
import com.enjoyyourtime.globalfitness.training.model.Routine
import com.enjoyyourtime.globalfitness.training.model.RoutineExercise
import com.enjoyyourtime.globalfitness.training.model.Workout
import com.enjoyyourtime.globalfitness.training.model.WorkoutEntry
import com.enjoyyourtime.globalfitness.training.model.WorkoutPause
import com.enjoyyourtime.globalfitness.training.model.WorkoutSet
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Converts training records to and from Firestore field maps. Pure Kotlin: the Firestore layer adds
 * the server `updatedAt` timestamp on write (used by incremental pulls, like every other document of
 * the backup) and passes it back as epoch millis on read. The client time of the last change is
 * `editedAt`, as in `meta/profile`.
 *
 * Layout under `users/{uid}/` (document ids encoded with [docId]):
 * - `workouts/{workoutId}`: one document per workout with its entries, sets and pauses embedded
 *   (a few KB; far below the 1 MiB document limit).
 * - `customExercises/{exerciseId}`: the user's own exercises and machines.
 * - `exerciseNotes/{exerciseId}`: machine settings, favorite star and `photoHash` (or null).
 * - `routines/{routineId}`: the user's own routines with their exercises and targets embedded (the
 *   built-in routines ship with the app and are never synced).
 * - `exercisePhotos/{sha256}`: the photo JPEG as a Blob (at most [MAX_PHOTO_BYTES]); content
 *   addressed, so an unchanged photo is never uploaded twice. Never listed by the sync, only read by id.
 * - Tombstones (every document collection): `{schemaVersion, deleted: true, deletedAt}`.
 *
 * Forward compatibility: a future version that adds fields or codes an older app cannot keep (a new
 * exercise kind, a per-set or per-routine-exercise field...) must raise the version of the documents
 * that use them (per document: [SPORT_SCHEMA_VERSION] is only on custom sports) and
 * [READABLE_SCHEMA_VERSION].
 * Older apps then read such documents but never overwrite them ([RemoteDocs.readOnly]), so nothing is
 * downgraded; their own edits of those documents stay on the phone until the app is updated.
 * Deletions still sync. A new collection needs no raise: older apps never read it, so they cannot
 * downgrade it (that is how `routines` was added, at schema version 1), and raising the version would
 * make every document of the existing collections read-only for them. Neither does a new top-level
 * field that an older app can leave as it is: live documents are written merged (see
 * FirestoreTrainingRemote), so an older app's edit of a workout keeps its `pauses` (that is how they
 * were added, at schema version 1). That app restores the workout without them (its duration then
 * includes the pauses) but never removes them; a pause left open by a workout it finished ends with
 * the workout here ([WorkoutTime.normalize]).
 */
object TrainingCodec {
    const val PHOTOS = "exercisePhotos"

    /** Training documents have their own schema version (independent of [CLOUD_SCHEMA_VERSION]). */
    const val TRAINING_SCHEMA_VERSION = 1

    /**
     * Version of a custom exercise whose equipment is [Equipment.SPORT] (1.4.0): an app older than
     * that code would store it as [Equipment.OTHER] and write it back so. With this version 1.3.0
     * keeps the document read-only ([RemoteDocs.readOnly]): it still lists the exercise (as "Other",
     * its workouts and routines keep it), but never overwrites the cloud copy; its own edits of that
     * exercise stay on that phone, a deletion still syncs. Every other document stays at
     * [TRAINING_SCHEMA_VERSION], so 1.3.0 keeps writing them. A phone updated from an older version
     * reads everything again once ([TrainingBase.forReader]), so it gets the full copy and its marks go.
     */
    const val SPORT_SCHEMA_VERSION = 2

    /** The highest version this app reads and writes in full: documents above it are newer ([isNewerSchema]). */
    const val READABLE_SCHEMA_VERSION = SPORT_SCHEMA_VERSION

    const val F_BYTES = "bytes"
    const val F_SIZE = "size"

    /** Largest photo blob uploaded: the document limit is 1 MiB including field names. */
    const val MAX_PHOTO_BYTES = 1_000_000

    // ---- Document ids ------------------------------------------------------------------------

    /**
     * Reversible Firestore document id for an app id: letters, digits, '-' and '_' stay, every other
     * UTF-8 byte becomes `~XX` (so '/' never appears and "." / ".." are impossible). A leading "__"
     * is escaped too (ids of the form `__x__` are reserved). Custom ids "u:<uuid>" become "u~3A<uuid>".
     */
    fun docId(raw: String): String {
        if (raw.isEmpty()) return "~"
        val reservedStart = raw.startsWith("__")
        val out = StringBuilder(raw.length + 8)
        raw.toByteArray(Charsets.UTF_8).forEachIndexed { i, b ->
            val c = b.toInt() and 0xFF
            val plain = c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code ||
                c == '-'.code || (c == '_'.code && !(i == 0 && reservedStart))
            if (plain) out.append(c.toChar()) else out.append('~').append(HEX[c shr 4]).append(HEX[c and 0x0F])
        }
        return out.toString()
    }

    /** Inverse of [docId]; null for an id this app did not write (or not in canonical form). */
    fun rawId(docId: String): String? {
        if (docId == "~") return ""
        val bytes = ByteArrayOutputStream(docId.length)
        var i = 0
        while (i < docId.length) {
            val c = docId[i]
            if (c == '~') {
                if (i + 2 >= docId.length) return null
                val hi = HEX.indexOf(docId[i + 1])
                val lo = HEX.indexOf(docId[i + 2])
                if (hi < 0 || lo < 0) return null
                bytes.write(hi * 16 + lo)
                i += 3
            } else {
                if (c.code > 0x7F) return null
                bytes.write(c.code)
                i++
            }
        }
        val raw = String(bytes.toByteArray(), Charsets.UTF_8)
        return raw.takeIf { docId(it) == docId }
    }

    /**
     * A stable id derived from [seed], for entry / set ids that would collide (duplicated inside a
     * cloud document, or used by another workout on the phone). The same input always gives the same
     * id, so decoding the same document twice gives the same workout.
     */
    fun derivedId(seed: String): String =
        "r-" + MessageDigest.getInstance("SHA-1").digest(seed.toByteArray(Charsets.UTF_8)).take(12).joinToString("") { "%02x".format(it) }

    // ---- Collections -------------------------------------------------------------------------

    /** One Firestore document as read: encoded id, fields, server updatedAt. */
    class RawDoc(val docId: String, val data: Map<String, Any?>, val updatedAtMillis: Long?)

    /** Decodes a collection read ([complete]: the whole collection) the same way for every caller. */
    fun <T> collect(docs: Iterable<RawDoc>, complete: Boolean, decode: (String, Map<String, Any?>, Long?) -> Decoded<T>?): RemoteDocs<T> {
        val live = ArrayList<T>()
        val dead = ArrayList<Tombstone>()
        val readOnly = HashSet<String>()
        var maxUpdatedAt: Long? = null
        for (doc in docs) {
            doc.updatedAtMillis?.let { maxUpdatedAt = maxOf(maxUpdatedAt ?: it, it) }
            // Documents this app did not write (ids not in canonical form) are ignored.
            val id = rawId(doc.docId) ?: continue
            when (val decoded = decode(id, doc.data, doc.updatedAtMillis)) {
                is Decoded.Live -> {
                    live += decoded.record
                    if (isNewerSchema(doc.data)) readOnly += id
                }
                is Decoded.Dead -> dead += decoded.tombstone
                null -> Unit
            }
        }
        return RemoteDocs(live, dead, complete, maxUpdatedAt, readOnly)
    }

    /**
     * Written by a newer app version that may store what this one cannot (see the class comment).
     * [readerVersion]: the highest version the reading app knows (1.3.0 knew only 1).
     */
    fun isNewerSchema(data: Map<String, Any?>, readerVersion: Int = READABLE_SCHEMA_VERSION): Boolean =
        ((data[F_SCHEMA] as? Number)?.toInt() ?: 1) > readerVersion

    // ---- Photos ------------------------------------------------------------------------------

    fun photoHash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun isPhotoHash(s: String?): Boolean = s != null && s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' }

    /** Photo document fields except the bytes (the Firestore layer adds them as a Blob). */
    fun photoToMap(size: Int): Map<String, Any?> = mapOf(F_SCHEMA to TRAINING_SCHEMA_VERSION, F_SIZE to size.toLong())

    // ---- Workouts ----------------------------------------------------------------------------

    fun workoutToMap(w: Workout): Map<String, Any?> = mapOf(
        F_SCHEMA to TRAINING_SCHEMA_VERSION,
        F_DELETED to false,
        "startedAt" to w.startedAt,
        "endedAt" to w.endedAt,
        "name" to w.name,
        "note" to w.note,
        F_EDITED_AT to w.updatedAt,
        // Always written, even empty: the merged write must replace the pauses of an earlier version.
        "pauses" to w.pauses.map { p -> buildMap { put("start", p.start); p.end?.let { put("end", it) } } },
        "entries" to w.entries.map { e ->
            // Null fields are left out inside the arrays: they are always rewritten whole.
            buildMap {
                put("id", e.id)
                put("exerciseId", e.exerciseId)
                e.note?.let { put("note", it) }
                put("sets", e.sets.map(::setToMap))
            }
        },
    )

    private fun setToMap(s: WorkoutSet): Map<String, Any?> = buildMap {
        put("id", s.id)
        s.weightKg?.let { put("weightKg", it) }
        s.reps?.let { put("reps", it.toLong()) }
        s.durationSec?.let { put("durationSec", it.toLong()) }
        s.distanceM?.let { put("distanceM", it) }
        s.level?.let { put("level", it.toLong()) }
        s.kcal?.let { put("kcal", it.toLong()) }
        put("warmup", s.warmup)
        put("done", s.done)
        s.doneAt?.let { put("doneAt", it) }
    }

    /**
     * Malformed entries, sets and pauses are skipped; a workout without a start time is ignored. Entry
     * and set ids repeated inside the document get a [derivedId] (the database needs them unique), and
     * the pauses are [WorkoutTime.normalize]d (sorted, inside the workout, not overlapping).
     */
    fun workoutFromMap(id: String, data: Map<String, Any?>, updatedAtMillis: Long?): Decoded<Workout>? {
        tombstoneOf(id, data, updatedAtMillis)?.let { return it }
        if (id.isEmpty()) return null
        val startedAt = long(data["startedAt"]) ?: return null
        val entryIds = HashSet<String>()
        val setIds = HashSet<String>()
        fun unique(seen: MutableSet<String>, raw: String, seed: String): String {
            if (seen.add(raw)) return raw
            var n = 0
            while (true) {
                val candidate = derivedId("$seed#$n")
                if (seen.add(candidate)) return candidate
                n++
            }
        }
        val entries = (data["entries"] as? List<*>).orEmpty().mapIndexedNotNull { i, raw ->
            val e = raw as? Map<*, *> ?: return@mapIndexedNotNull null
            val rawEntryId = (e["id"] as? String)?.takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
            val exerciseId = (e["exerciseId"] as? String)?.takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
            val entryId = unique(entryIds, rawEntryId, "$id/e$i/$rawEntryId")
            WorkoutEntry(
                id = entryId,
                exerciseId = exerciseId,
                sets = (e["sets"] as? List<*>).orEmpty().mapIndexedNotNull { j, rawSet ->
                    setFromMap(rawSet as? Map<*, *>)?.let { s -> s.copy(id = unique(setIds, s.id, "$id/e$i/s$j/${s.id}")) }
                },
                note = e["note"] as? String,
            )
        }
        val endedAt = long(data["endedAt"])
        val pauses = (data["pauses"] as? List<*>).orEmpty().mapNotNull { raw ->
            val p = raw as? Map<*, *> ?: return@mapNotNull null
            WorkoutPause(long(p["start"]) ?: return@mapNotNull null, long(p["end"]))
        }
        return Decoded.Live(
            Workout(
                id = id,
                startedAt = startedAt,
                endedAt = endedAt,
                name = data["name"] as? String,
                note = data["note"] as? String,
                updatedAt = long(data[F_EDITED_AT]) ?: updatedAtMillis ?: startedAt,
                entries = entries,
                pauses = WorkoutTime.normalize(pauses, startedAt, endedAt),
            ),
        )
    }

    private fun setFromMap(s: Map<*, *>?): WorkoutSet? {
        if (s == null) return null
        val id = (s["id"] as? String)?.takeIf { it.isNotEmpty() } ?: return null
        return WorkoutSet(
            id = id,
            weightKg = double(s["weightKg"]),
            reps = int(s["reps"]),
            durationSec = int(s["durationSec"]),
            distanceM = double(s["distanceM"]),
            level = int(s["level"]),
            kcal = int(s["kcal"]),
            warmup = s["warmup"] == true,
            done = s["done"] == true,
            doneAt = long(s["doneAt"]),
        )
    }

    // ---- Custom exercises --------------------------------------------------------------------

    /** A sport's document is written at [SPORT_SCHEMA_VERSION], every other one at [TRAINING_SCHEMA_VERSION]. */
    fun customExerciseToMap(c: CustomExercise): Map<String, Any?> = mapOf(
        F_SCHEMA to if (c.equipment == Equipment.SPORT) SPORT_SCHEMA_VERSION else TRAINING_SCHEMA_VERSION,
        F_DELETED to false,
        "name" to c.name,
        "kind" to c.kind.code,
        "equipment" to c.equipment.code,
        "primaryMuscles" to c.primary.sortedBy { it.ordinal }.map { it.code },
        "secondaryMuscles" to c.secondary.sortedBy { it.ordinal }.map { it.code },
        "archived" to c.archived,
        "createdAt" to c.createdAt,
        F_EDITED_AT to c.updatedAt,
    )

    /** Only ids of the user's own exercises ("u:...") are accepted; unknown codes fall back like the database does. */
    fun customExerciseFromMap(id: String, data: Map<String, Any?>, updatedAtMillis: Long?): Decoded<CustomExercise>? {
        tombstoneOf(id, data, updatedAtMillis)?.let { return it }
        if (!CustomExercise.isCustomId(id)) return null
        val name = data["name"] as? String ?: return null
        val updatedAt = long(data[F_EDITED_AT]) ?: updatedAtMillis ?: 0L
        return Decoded.Live(
            CustomExercise(
                id = id,
                name = name,
                kind = ExerciseKind.fromCode(data["kind"] as? String),
                equipment = Equipment.fromCode(data["equipment"] as? String),
                primary = muscles(data["primaryMuscles"]),
                secondary = muscles(data["secondaryMuscles"]),
                archived = data["archived"] == true,
                createdAt = long(data["createdAt"]) ?: updatedAt,
                updatedAt = updatedAt,
            ),
        )
    }

    private fun muscles(value: Any?): Set<Muscle> =
        (value as? List<*>).orEmpty().mapNotNull { (it as? String)?.let(Muscle::fromCode) }.toSet()

    // ---- Exercise notes ----------------------------------------------------------------------

    fun noteToMap(n: NoteRecord): Map<String, Any?> = mapOf(
        F_SCHEMA to TRAINING_SCHEMA_VERSION,
        F_DELETED to false,
        "notes" to n.notes,
        "favorite" to n.favorite,
        "photoHash" to n.photoHash,
        F_EDITED_AT to n.updatedAt,
    )

    /** A `photoHash` that is not a SHA-256 hex string is treated as no photo. */
    fun noteFromMap(id: String, data: Map<String, Any?>, updatedAtMillis: Long?): Decoded<NoteRecord>? {
        tombstoneOf(id, data, updatedAtMillis)?.let { return it }
        if (id.isEmpty()) return null
        return Decoded.Live(
            NoteRecord(
                exerciseId = id,
                notes = data["notes"] as? String,
                favorite = data["favorite"] == true,
                photoHash = (data["photoHash"] as? String)?.takeIf(::isPhotoHash),
                updatedAt = long(data[F_EDITED_AT]) ?: updatedAtMillis ?: 0L,
            ),
        )
    }

    // ---- Routines ----------------------------------------------------------------------------

    fun routineToMap(r: Routine): Map<String, Any?> = mapOf(
        F_SCHEMA to TRAINING_SCHEMA_VERSION,
        F_DELETED to false,
        "name" to r.name,
        "note" to r.note,
        "createdAt" to r.createdAt,
        F_EDITED_AT to r.updatedAt,
        "exercises" to r.exercises.map { e ->
            // Null targets are left out inside the array: it is always rewritten whole.
            buildMap {
                put("exerciseId", e.exerciseId)
                put("sets", e.sets.toLong())
                e.reps?.let { put("reps", it.toLong()) }
                e.durationSec?.let { put("durationSec", it.toLong()) }
            }
        },
    )

    /**
     * Malformed exercises (no exercise id) are skipped and the targets are cleaned like the repository
     * stores them ([TrainingRepository.cleanRoutine]: a missing set count is one set, an exercise
     * repeated in the document keeps its first target), so the same document always decodes to the same
     * routine. A routine without name is ignored, and so is one with the id of a built-in routine
     * (built-ins are never synced: it would be listed twice). An exercise unknown on this phone is kept;
     * the screens show it as unknown.
     */
    fun routineFromMap(id: String, data: Map<String, Any?>, updatedAtMillis: Long?): Decoded<Routine>? {
        tombstoneOf(id, data, updatedAtMillis)?.let { return it }
        if (id.isEmpty() || RoutineCatalog[id] != null) return null
        val name = data["name"] as? String ?: return null
        val updatedAt = long(data[F_EDITED_AT]) ?: updatedAtMillis ?: 0L
        val exercises = (data["exercises"] as? List<*>).orEmpty().mapNotNull { raw ->
            val e = raw as? Map<*, *> ?: return@mapNotNull null
            val exerciseId = (e["exerciseId"] as? String)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            RoutineExercise(exerciseId, sets = int(e["sets"]) ?: 1, reps = int(e["reps"]), durationSec = int(e["durationSec"]))
        }
        return Decoded.Live(
            Routine(
                id = id,
                name = name,
                exercises = TrainingRepository.cleanRoutine(exercises),
                note = data["note"] as? String,
                createdAt = long(data["createdAt"]) ?: updatedAt,
                updatedAt = updatedAt,
            ),
        )
    }

    // ---- Tombstones --------------------------------------------------------------------------

    fun tombstoneToMap(deletedAt: Long): Map<String, Any?> = mapOf(
        F_SCHEMA to TRAINING_SCHEMA_VERSION,
        F_DELETED to true,
        F_DELETED_AT to deletedAt,
    )

    private fun tombstoneOf(id: String, data: Map<String, Any?>, updatedAtMillis: Long?): Decoded.Dead? {
        if (data[F_DELETED] != true) return null
        return Decoded.Dead(Tombstone(id, long(data[F_DELETED_AT]) ?: updatedAtMillis ?: 0L))
    }

    // ---- Sizes -------------------------------------------------------------------------------

    /** Rough encoded size of a document map, to keep each batch far below the request limit. */
    fun estimateBytes(value: Any?): Int = when (value) {
        null, is Boolean -> 1
        is Number -> 8
        is String -> value.length * 3 + 1
        is Map<*, *> -> value.entries.sumOf { (k, v) -> ((k as? String)?.length ?: 0) + 1 + estimateBytes(v) }
        is List<*> -> value.sumOf { estimateBytes(it) } + 1
        is ByteArray -> value.size
        else -> 16
    }

    private fun long(v: Any?): Long? = (v as? Number)?.toLong()

    private fun int(v: Any?): Int? = (v as? Number)?.toInt()

    private fun double(v: Any?): Double? = (v as? Number)?.toDouble()

    private const val HEX = "0123456789ABCDEF"
}
