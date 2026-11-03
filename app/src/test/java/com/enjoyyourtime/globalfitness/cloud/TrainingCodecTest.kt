package com.enjoyyourtime.globalfitness.cloud

import com.enjoyyourtime.globalfitness.cloud.FirestoreCodec.Decoded
import com.enjoyyourtime.globalfitness.training.catalog.RoutineCatalog
import com.enjoyyourtime.globalfitness.training.data.WorkoutEdits
import com.enjoyyourtime.globalfitness.training.model.CustomExercise
import com.enjoyyourtime.globalfitness.training.model.Equipment
import com.enjoyyourtime.globalfitness.training.model.ExerciseKind
import com.enjoyyourtime.globalfitness.training.model.Muscle
import com.enjoyyourtime.globalfitness.training.model.Routine
import com.enjoyyourtime.globalfitness.training.model.RoutineExercise
import com.enjoyyourtime.globalfitness.training.model.Workout
import com.enjoyyourtime.globalfitness.training.model.WorkoutEntry
import com.enjoyyourtime.globalfitness.training.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainingCodecTest {
    private val hash = TrainingCodec.photoHash(byteArrayOf(1, 2, 3))
    private val routineId = "6b1f7c1e-2d3a-4b5c-8d9e-0f1a2b3c4d5e"

    // ---- Document ids ----------------------------------------------------------------------------

    @Test
    fun idsRoundTripAndNeverContainSlashes() {
        val ids = listOf(
            "barbell_bench_press",
            "u:3f2a9c1e-0b7d-4a51-9a51-1f0e2d3c4b5a",
            "6b1f7c1e-2d3a-4b5c-8d9e-0f1a2b3c4d5e",
            "a/b",
            ".",
            "..",
            "__reserved__",
            "~tilde~",
            "máquina de remo",
            "",
        )
        for (raw in ids) {
            val doc = TrainingCodec.docId(raw)
            assertFalse(raw, '/' in doc)
            assertTrue(raw, doc.all { it.isLetterOrDigit() || it in "-_~" })
            assertFalse(raw, doc == "." || doc == ".." || doc.isEmpty())
            assertFalse(raw, doc.startsWith("__") && doc.endsWith("__"))
            assertEquals(raw, TrainingCodec.rawId(doc))
        }
        assertEquals("u~3A3f2a", TrainingCodec.docId("u:3f2a"))
        assertEquals("barbell_bench_press", TrainingCodec.docId("barbell_bench_press"))
    }

    @Test
    fun foreignDocIdsAreRejected() {
        assertNull(TrainingCodec.rawId("u:raw-colon")) // not written by docId (':' is always escaped)
        assertNull(TrainingCodec.rawId("bad~4"))
        assertNull(TrainingCodec.rawId("bad~ZZ"))
        assertNull(TrainingCodec.rawId("~41")) // non-canonical spelling of "A"
    }

    // ---- Workouts --------------------------------------------------------------------------------

    @Test
    fun workoutRoundTripsWithEverySetFieldIncludingNulls() {
        val workout = Workout(
            id = "w-1",
            startedAt = 1_700_000_000_000L,
            endedAt = null,
            name = null,
            note = "Felt strong",
            updatedAt = 1_700_000_100_000L,
            entries = listOf(
                WorkoutEntry(
                    id = "e-1",
                    exerciseId = "u:machine-1",
                    note = null,
                    sets = listOf(
                        WorkoutSet(
                            id = "s-1", weightKg = 82.5, reps = 8, durationSec = 45, distanceM = 1234.5, level = 7, kcal = 120,
                            warmup = true, done = true, doneAt = 1_700_000_050_000L,
                        ),
                        WorkoutSet(id = "s-2"),
                    ),
                ),
                WorkoutEntry(id = "e-2", exerciseId = "treadmill", note = "incline 3", sets = emptyList()),
            ),
        )
        val map = TrainingCodec.workoutToMap(workout)
        assertEquals(TrainingCodec.TRAINING_SCHEMA_VERSION, map[FirestoreCodec.F_SCHEMA])
        assertEquals(false, map[FirestoreCodec.F_DELETED])
        // Top-level nulls are written explicitly (documents are merged, so a cleared name must be stored as null).
        assertTrue(map.containsKey("endedAt") && map["endedAt"] == null)
        assertTrue(map.containsKey("name") && map["name"] == null)

        val decoded = TrainingCodec.workoutFromMap("w-1", firestoreLike(map), updatedAtMillis = 5L)
        assertEquals(Decoded.Live(workout), decoded)
        assertEquals(TrainingMerge.versionOf(workout), TrainingMerge.versionOf((decoded as Decoded.Live).record))
    }

    @Test
    fun malformedEntriesAreSkippedAndAWorkoutNeedsAStart() {
        val data = mapOf<String, Any?>(
            "startedAt" to 10L,
            "entries" to listOf(
                mapOf("id" to "e1", "exerciseId" to "squat", "sets" to listOf(mapOf("id" to "s1", "reps" to 5L), mapOf("reps" to 3L), "junk")),
                mapOf("id" to "e2"),
                42,
            ),
        )
        val w = (TrainingCodec.workoutFromMap("w", data, updatedAtMillis = 99L) as Decoded.Live).record
        assertEquals(listOf("e1"), w.entries.map { it.id })
        assertEquals(listOf("s1"), w.entries[0].sets.map { it.id })
        assertEquals(5, w.entries[0].sets[0].reps)
        assertEquals(99L, w.updatedAt) // no editedAt: the server time
        assertNull(TrainingCodec.workoutFromMap("w", mapOf("name" to "no start"), 1L))
    }

    @Test
    fun repeatedEntryAndSetIdsInsideADocumentGetStableUniqueIds() {
        val set = mapOf("id" to "s1", "reps" to 5L)
        val data = mapOf<String, Any?>(
            "startedAt" to 10L,
            "entries" to listOf(
                mapOf("id" to "e1", "exerciseId" to "squat", "sets" to listOf(set, set)),
                mapOf("id" to "e1", "exerciseId" to "lunge", "sets" to listOf(set)),
            ),
        )
        val w = (TrainingCodec.workoutFromMap("w", data, 1L) as Decoded.Live).record
        val entryIds = w.entries.map { it.id }
        val setIds = w.entries.flatMap { e -> e.sets.map { it.id } }
        assertEquals(2, entryIds.toSet().size)
        assertEquals(3, setIds.toSet().size)
        assertEquals("e1", entryIds[0]) // the first keeps its id
        assertEquals("s1", setIds[0])
        // The same document always decodes to the same workout.
        assertEquals(w, (TrainingCodec.workoutFromMap("w", data, 1L) as Decoded.Live).record)
    }

    @Test
    fun collectionsDecodeTheSameForEveryCallerAndFlagNewerDocuments() {
        val mine = TrainingCodec.customExerciseToMap(
            CustomExercise("u:1", "Mine", ExerciseKind.WEIGHT_REPS, Equipment.MACHINE, emptySet(), emptySet(), false, 1L, 2L),
        )
        val newer = mine + (FirestoreCodec.F_SCHEMA to (TrainingCodec.READABLE_SCHEMA_VERSION + 1).toLong())
        val docs = listOf(
            TrainingCodec.RawDoc(TrainingCodec.docId("u:1"), mine, 30L),
            TrainingCodec.RawDoc(TrainingCodec.docId("u:2"), newer, 50L),
            TrainingCodec.RawDoc(TrainingCodec.docId("u:3"), TrainingCodec.tombstoneToMap(9L), 40L),
            TrainingCodec.RawDoc("u:raw-colon", mine, 60L), // not written by this app: ignored, but still moves the cursor
        )
        val read = TrainingCodec.collect(docs, complete = false, decode = TrainingCodec::customExerciseFromMap)
        assertEquals(listOf("u:1", "u:2"), read.live.map { it.id })
        assertEquals(listOf(Tombstone("u:3", 9L)), read.dead)
        assertEquals(setOf("u:2"), read.readOnly)
        assertEquals(60L, read.maxUpdatedAt)
        assertFalse(read.complete)
        assertFalse(TrainingCodec.isNewerSchema(mine))
        assertFalse(TrainingCodec.isNewerSchema(emptyMap())) // documents without a version are version 1
    }

    @Test
    fun tombstonesRoundTrip() {
        val map = TrainingCodec.tombstoneToMap(42L)
        assertEquals(setOf(FirestoreCodec.F_SCHEMA, FirestoreCodec.F_DELETED, FirestoreCodec.F_DELETED_AT), map.keys)
        assertEquals(Decoded.Dead(Tombstone("w", 42L)), TrainingCodec.workoutFromMap("w", map, 99L))
        assertEquals(Decoded.Dead(Tombstone("u:1", 42L)), TrainingCodec.customExerciseFromMap("u:1", map, 99L))
        assertEquals(Decoded.Dead(Tombstone("squat", 42L)), TrainingCodec.noteFromMap("squat", map, 99L))
        assertEquals(Decoded.Dead(Tombstone(routineId, 42L)), TrainingCodec.routineFromMap(routineId, map, 99L))
        // A tombstone without deletedAt falls back to the server time.
        assertEquals(Decoded.Dead(Tombstone("w", 99L)), TrainingCodec.workoutFromMap("w", mapOf(FirestoreCodec.F_DELETED to true), 99L))
    }

    // ---- Custom exercises and notes --------------------------------------------------------------

    @Test
    fun customExerciseRoundTripsAndUnknownCodesFallBack() {
        val custom = CustomExercise(
            id = "u:abc", name = "Hack squat (gym 2)", kind = ExerciseKind.WEIGHT_REPS, equipment = Equipment.MACHINE,
            primary = setOf(Muscle.QUADS, Muscle.GLUTES), secondary = setOf(Muscle.HAMSTRINGS), archived = true,
            createdAt = 100L, updatedAt = 200L,
        )
        val map = TrainingCodec.customExerciseToMap(custom)
        assertEquals(listOf("glutes", "quads"), map["primaryMuscles"])
        assertEquals(Decoded.Live(custom), TrainingCodec.customExerciseFromMap("u:abc", firestoreLike(map), 1L))

        // Written by a newer version: unknown kind / muscles are read like the database reads them.
        val newer = map + mapOf("kind" to "plyometric", "primaryMuscles" to listOf("quads", "tibialis"))
        val read = (TrainingCodec.customExerciseFromMap("u:abc", newer, 1L) as Decoded.Live).record
        assertEquals(ExerciseKind.WEIGHT_REPS, read.kind)
        assertEquals(setOf(Muscle.QUADS), read.primary)
        // Only the user's own ids are custom exercises.
        assertNull(TrainingCodec.customExerciseFromMap("barbell_bench_press", map, 1L))
        // Every kind (assisted machines included) survives the trip.
        for (kind in ExerciseKind.entries) {
            val k = custom.copy(kind = kind)
            assertEquals(Decoded.Live(k), TrainingCodec.customExerciseFromMap("u:abc", TrainingCodec.customExerciseToMap(k), 1L))
        }
    }

    @Test
    fun anOwnSportRoundTripsAndAnOlderAppKeepsItReadOnlyWithoutHidingIt() {
        val sport = CustomExercise(
            id = "u:squash", name = "Squash", kind = ExerciseKind.CARDIO, equipment = Equipment.SPORT,
            primary = setOf(Muscle.QUADS), secondary = setOf(Muscle.CALVES), archived = false, createdAt = 100L, updatedAt = 200L,
        )
        val map = TrainingCodec.customExerciseToMap(sport)
        assertEquals("sport", map["equipment"])
        assertEquals(TrainingCodec.SPORT_SCHEMA_VERSION, map[FirestoreCodec.F_SCHEMA])
        assertEquals(Decoded.Live(sport), TrainingCodec.customExerciseFromMap(sport.id, firestoreLike(map), 1L))
        // Every other own exercise stays at version 1, which 1.3.0 keeps writing.
        for (e in Equipment.entries - Equipment.SPORT) {
            val other = TrainingCodec.customExerciseToMap(sport.copy(equipment = e))
            assertEquals(e.code, TrainingCodec.TRAINING_SCHEMA_VERSION, other[FirestoreCodec.F_SCHEMA])
        }
        // This version reads and writes it in full; the database stores the same code.
        val docs = listOf(TrainingCodec.RawDoc(TrainingCodec.docId(sport.id), firestoreLike(map), 10L))
        val read = TrainingCodec.collect(docs, complete = true, decode = TrainingCodec::customExerciseFromMap)
        assertEquals(listOf(sport), read.live)
        assertTrue(read.readOnly.isEmpty())
        assertEquals(Equipment.SPORT, Equipment.fromCode(Equipment.SPORT.code))
        for (e in Equipment.entries) assertEquals(e, Equipment.fromCode(e.code))
        // 1.3.0 (it knew version 1 only and no "sport" code) lists it as "other" but never writes it back.
        assertTrue(TrainingCodec.isNewerSchema(map, readerVersion = 1))
        assertFalse(TrainingCodec.isNewerSchema(TrainingCodec.customExerciseToMap(sport.copy(equipment = Equipment.MACHINE)), readerVersion = 1))
        val asOlderApp = (TrainingCodec.customExerciseFromMap(sport.id, map + ("equipment" to "not-known-yet"), 1L) as Decoded.Live).record
        assertEquals(Equipment.OTHER, asOlderApp.equipment)
        assertEquals(ExerciseKind.CARDIO, asOlderApp.kind)
        // A deletion is a plain tombstone: it still syncs to every version.
        assertEquals(TrainingCodec.TRAINING_SCHEMA_VERSION, TrainingCodec.tombstoneToMap(5L)[FirestoreCodec.F_SCHEMA])
    }

    @Test
    fun noteRoundTripsWithAndWithoutPhoto() {
        val note = NoteRecord("u:abc", "seat 4, back 2", favorite = true, photoHash = hash, updatedAt = 300L)
        val map = TrainingCodec.noteToMap(note)
        assertTrue(map.containsKey("photoHash"))
        assertEquals(Decoded.Live(note), TrainingCodec.noteFromMap("u:abc", firestoreLike(map), 1L))

        val noPhoto = note.copy(notes = null, photoHash = null)
        val cleared = TrainingCodec.noteToMap(noPhoto)
        assertTrue(cleared.containsKey("photoHash") && cleared["photoHash"] == null) // a removed photo is written as null
        assertEquals(Decoded.Live(noPhoto), TrainingCodec.noteFromMap("u:abc", cleared, 1L))
        // A malformed hash is no photo.
        val bad = (TrainingCodec.noteFromMap("x", map + ("photoHash" to "../../etc"), 1L) as Decoded.Live).record
        assertNull(bad.photoHash)
    }

    // ---- Routines ----------------------------------------------------------------------------------

    @Test
    fun routineRoundTripsWithNullTargetsAndNote() {
        val routine = Routine(
            id = routineId,
            name = "Legs A (gym 2)",
            exercises = listOf(
                RoutineExercise("leg_press_machine", sets = 4, reps = 10),
                RoutineExercise("plank", sets = 3, durationSec = 45),
                RoutineExercise("u:3f2a9c1e-0b7d-4a51-9a51-1f0e2d3c4b5a", sets = 2), // no target: taken from last time
            ),
            note = null,
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_100_000L,
        )
        val map = TrainingCodec.routineToMap(routine)
        assertEquals(TrainingCodec.TRAINING_SCHEMA_VERSION, map[FirestoreCodec.F_SCHEMA])
        assertEquals(false, map[FirestoreCodec.F_DELETED])
        // Documents are merged: a cleared note must be stored as null.
        assertTrue(map.containsKey("note") && map["note"] == null)
        val decoded = TrainingCodec.routineFromMap(routineId, firestoreLike(map), updatedAtMillis = 5L)
        assertEquals(Decoded.Live(routine), decoded)
        assertEquals(TrainingMerge.versionOf(routine), TrainingMerge.versionOf((decoded as Decoded.Live).record))

        val withNote = routine.copy(note = "Rest 2 min", exercises = emptyList())
        assertEquals(Decoded.Live(withNote), TrainingCodec.routineFromMap(routineId, firestoreLike(TrainingCodec.routineToMap(withNote)), 5L))

        // Written by a newer app version: read, but never overwritten.
        val newer = map + (FirestoreCodec.F_SCHEMA to (TrainingCodec.READABLE_SCHEMA_VERSION + 1).toLong())
        val read = TrainingCodec.collect(listOf(TrainingCodec.RawDoc(TrainingCodec.docId(routineId), newer, 10L)), complete = true, decode = TrainingCodec::routineFromMap)
        assertEquals(listOf(routine), read.live)
        assertEquals(setOf(routineId), read.readOnly)
    }

    @Test
    fun routineDecodingDropsRepeatedExercisesAndToleratesGarbage() {
        val data = mapOf<String, Any?>(
            "name" to "Push",
            "exercises" to listOf(
                mapOf("exerciseId" to "barbell_bench_press", "sets" to 3L, "reps" to 8L),
                mapOf("exerciseId" to "barbell_bench_press", "sets" to 5L, "reps" to 5L), // repeated: the first one is kept
                mapOf("exerciseId" to "dips", "sets" to "three", "reps" to -2L), // garbage set count and target
                mapOf("exerciseId" to "push_up", "sets" to 999L, "durationSec" to 0L),
                mapOf("sets" to 3L), // no exercise
                mapOf("exerciseId" to "", "sets" to 3L),
                "junk",
                42L,
            ),
        )
        val r = (TrainingCodec.routineFromMap(routineId, data, updatedAtMillis = 77L) as Decoded.Live).record
        assertEquals(
            listOf(
                RoutineExercise("barbell_bench_press", 3, reps = 8),
                RoutineExercise("dips", 1),
                RoutineExercise("push_up", WorkoutEdits.MAX_ROUTINE_SETS),
            ),
            r.exercises,
        )
        assertEquals(77L, r.updatedAt) // no editedAt: the server time
        assertEquals(77L, r.createdAt)
        assertNull(r.note)
        // The same document always decodes to the same routine.
        assertEquals(r, (TrainingCodec.routineFromMap(routineId, data, 77L) as Decoded.Live).record)

        // Exercises that are not a list, a note that is not text: an empty routine, not an error.
        val odd = (TrainingCodec.routineFromMap(routineId, mapOf("name" to "X", "exercises" to "nope", "note" to 5L), 1L) as Decoded.Live).record
        assertTrue(odd.exercises.isEmpty())
        assertNull(odd.note)
        // A routine needs a name and an id of its own (built-in routines are never synced).
        assertNull(TrainingCodec.routineFromMap(routineId, mapOf("exercises" to emptyList<Any>()), 1L))
        assertNull(TrainingCodec.routineFromMap("", data, 1L))
        assertNull(TrainingCodec.routineFromMap(RoutineCatalog.all.first().id, data, 1L))
    }

    @Test
    fun photoHashesAreSha256Hex() {
        assertEquals(64, hash.length)
        assertTrue(TrainingCodec.isPhotoHash(hash))
        assertFalse(TrainingCodec.isPhotoHash(hash.uppercase()))
        assertFalse(TrainingCodec.isPhotoHash(null))
        assertEquals(hash, TrainingCodec.photoHash(byteArrayOf(1, 2, 3)))
    }

    // ---- Base -------------------------------------------------------------------------------------

    @Test
    fun baseRoundTrips() {
        val base = TrainingBase(
            datasetKey = "ds-1",
            pending = TrainingMode.ADOPT_REPLACE,
            pendingSince = 1_234L,
            cursors = mapOf(TrainingKind.WORKOUT to 10L, TrainingKind.NOTE to 30L, TrainingKind.ROUTINE to 40L),
            entries = mapOf(
                DocKey(TrainingKind.WORKOUT, "w-1") to "5.abc",
                DocKey(TrainingKind.WORKOUT, "w-2") to TrainingMerge.dead(99L),
                DocKey(TrainingKind.CUSTOM_EXERCISE, "u:1") to "6.def",
                DocKey(TrainingKind.NOTE, "barbell_bench_press") to "7.123",
                DocKey(TrainingKind.ROUTINE, routineId) to "8.456",
                DocKey(TrainingKind.ROUTINE, "gone") to TrainingMerge.dead(98L),
            ),
            photos = setOf(hash),
            photoFiles = mapOf("u_1_99.jpg" to hash),
            photosInFlight = mapOf(hash to 77L),
            readOnly = setOf(DocKey(TrainingKind.CUSTOM_EXERCISE, "u:2"), DocKey(TrainingKind.ROUTINE, routineId)),
        )
        assertEquals(base, TrainingBaseCodec.decode(TrainingBaseCodec.encode(base)))
        assertEquals(TrainingMode.UNION, TrainingBaseCodec.decode(TrainingBaseCodec.encode(base.copy(pending = TrainingMode.UNION)))!!.pending)
        val plain = base.copy(pending = null, pendingSince = null)
        assertEquals(plain, TrainingBaseCodec.decode(TrainingBaseCodec.encode(plain)))
        // A pending line written before the transition time existed is still read.
        assertEquals(TrainingMode.REPLACE_CLOUD, TrainingBaseCodec.decode("v\t1\ndataset\tx\npending\treplace_cloud\n")!!.pending)
        assertNull(TrainingBaseCodec.decode("v\t0\ndataset\tx\n"))
        assertNull(TrainingBaseCodec.decode(""))
    }

    @Test
    fun baseIsTrustedOnlyForItsDatasetWithoutPendingTransition() {
        val base = TrainingBase("ds-1")
        assertTrue(base.trustedFor("ds-1"))
        assertFalse(base.trustedFor("ds-2"))
        assertFalse(base.copy(pending = TrainingMode.REPLACE_CLOUD).trustedFor("ds-1"))
        assertEquals(TrainingCursors.FULL, base.copy(cursors = mapOf(TrainingKind.WORKOUT to 5L)).cursorsOrFull(trusted = false))
        assertEquals(TrainingCursors(workouts = 5L), base.copy(cursors = mapOf(TrainingKind.WORKOUT to 5L)).cursorsOrFull(trusted = true))
        assertEquals(TrainingCursors(routines = 7L), base.copy(cursors = mapOf(TrainingKind.ROUTINE to 7L)).cursorsOrFull(trusted = true))
        // A base saved before routines were synced has no cursor for them: they are read whole.
        val beforeRoutines = TrainingBaseCodec.decode("v\t1\ndataset\tds-1\ncursor\tw\t5\ncursor\tc\t6\ncursor\tn\t7\n")!!
        assertEquals(TrainingCursors(5L, 6L, 7L, routines = null), beforeRoutines.cursorsOrFull(trusted = true))
        // A base saved before the reader's version was stored (it may hold read-only marks of documents
        // this version reads in full): read whole once, the marks forgotten; one of this version stays.
        val old = TrainingBaseCodec.decode("v\t1\ndataset\tds-1\ncursor\tw\t5\nro\tc\tu:2\n")!!
        assertEquals(0, old.schema)
        val updated = old.forReader()
        assertEquals(TrainingCursors.FULL, updated.cursorsOrFull(trusted = true))
        assertTrue(updated.readOnly.isEmpty())
        assertEquals(TrainingCodec.READABLE_SCHEMA_VERSION, TrainingBaseCodec.decode(TrainingBaseCodec.encode(updated))!!.schema)
        assertEquals(base.copy(cursors = mapOf(TrainingKind.WORKOUT to 5L)), base.copy(cursors = mapOf(TrainingKind.WORKOUT to 5L)).forReader())
    }

    /** What Firestore hands back: whole numbers as Long, decimals as Double. */
    private fun firestoreLike(value: Any?): Any? = when (value) {
        is Int -> value.toLong()
        is Map<*, *> -> value.entries.associate { (k, v) -> k to firestoreLike(v) }
        is List<*> -> value.map(::firestoreLike)
        else -> value
    }

    @Suppress("UNCHECKED_CAST")
    private fun firestoreLike(map: Map<String, Any?>): Map<String, Any?> = firestoreLike(map as Any?) as Map<String, Any?>
}
