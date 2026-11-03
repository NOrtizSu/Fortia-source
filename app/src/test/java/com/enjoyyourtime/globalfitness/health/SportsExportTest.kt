package com.enjoyyourtime.globalfitness.health

import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import com.enjoyyourtime.globalfitness.UiText
import com.enjoyyourtime.globalfitness.training.catalog.ExerciseCatalog
import com.enjoyyourtime.globalfitness.training.catalog.SportsCatalog
import com.enjoyyourtime.globalfitness.training.data.TrainingRepository
import com.enjoyyourtime.globalfitness.training.domain.TrainingMath
import com.enjoyyourtime.globalfitness.training.model.CustomExercise
import com.enjoyyourtime.globalfitness.training.model.Equipment
import com.enjoyyourtime.globalfitness.training.model.ExerciseKind
import com.enjoyyourtime.globalfitness.training.model.Muscle
import com.enjoyyourtime.globalfitness.training.model.Workout
import com.enjoyyourtime.globalfitness.training.model.WorkoutEntry
import com.enjoyyourtime.globalfitness.training.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** How workouts with sports and activities are written to Health Connect (session type, title, segments). */
class SportsExportTest {
    private val own = CustomExercise("u:squash", "Squash", ExerciseKind.CARDIO, Equipment.SPORT, setOf(Muscle.QUADS), emptySet(), archived = false, createdAt = 1, updatedAt = 1)
    private val exercises = TrainingRepository.buildExercises(ExerciseCatalog.all, listOf(own), emptyMap())

    /** As the exporter builds it; names resolve to "name of <id>". */
    private val info: (String) -> WorkoutExerciseInfo = { id ->
        val e = exercises[id] ?: TrainingRepository.unknownExercise(id)
        WorkoutExerciseInfo.of(e) { text -> if (text is UiText.Raw) text.text else "name of $id" }
    }

    private val start = 1_700_000_000_000L
    private val minute = 60_000L

    private fun workout(vararg ids: String, name: String? = null, minutes: Int = 90): Workout = Workout(
        id = "w1",
        startedAt = start,
        endedAt = start + minutes * minute,
        name = name,
        note = null,
        updatedAt = 10,
        entries = ids.mapIndexed { i, id ->
            WorkoutEntry("e$i", id, listOf(WorkoutSet("s$i", durationSec = 20 * 60, reps = if (id.startsWith("barbell")) 8 else null, done = true, doneAt = start + (i + 1) * 25 * minute)))
        },
    )

    private fun record(w: Workout): ExerciseSessionRecord =
        WorkoutExporter.buildRecord(WorkoutExportPlanner.session(w, info), ZoneId.of("Europe/Madrid")) { "generic $it" }

    @Test
    fun aWorkoutOfOneSportIsThatSportTitledWithItsName() {
        for (sport in SportsCatalog.all) {
            val planned = WorkoutExportPlanner.session(workout(sport.id), info)
            assertEquals(sport.id, sport.hcSession, planned.exerciseType)
            assertEquals(sport.id, "name of ${sport.id}", planned.title)
            // The library accepts every segment: the activity's own type where it has one, else "other workout".
            val record = record(workout(sport.id))
            assertEquals(sport.id, 1, record.segments.size)
            assertEquals(sport.id, sport.hcSegment ?: ExerciseSegment.EXERCISE_SEGMENT_TYPE_OTHER_WORKOUT, record.segments.single().segmentType)
            assertEquals("name of ${sport.id}", record.title)
        }
        // Padel and futsal keep their own names on the closest Health Connect sport.
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_TENNIS, record(workout("padel")).exerciseType)
        assertEquals("name of padel", record(workout("padel")).title)
        // The same sport twice in one workout is still that sport.
        assertEquals("name of football", WorkoutExportPlanner.session(workout("football", "football"), info).title)
    }

    @Test
    fun everyCatalogExerciseAloneKeepsItsOwnSegment() {
        // Logged on its own, each exercise is written with its own Health Connect segment: the pair of
        // its segment and the session type the planner picks for it is one the library accepts (an
        // incompatible pair would fall back to "other workout" or to no segments at all).
        for (e in ExerciseCatalog.all) {
            val planned = WorkoutExportPlanner.session(workout(e.id), info)
            val own = e.hcSegment ?: if (e.kind == ExerciseKind.CARDIO) ExerciseSegment.EXERCISE_SEGMENT_TYPE_OTHER_WORKOUT else ExerciseSegment.EXERCISE_SEGMENT_TYPE_WEIGHTLIFTING
            assertEquals(e.id, own, planned.segments.single().type)
            assertEquals(e.id, own, record(workout(e.id)).segments.single().segmentType)
        }
        // Walking on a treadmill is a walk, as walking outdoors is; the curved treadmill a treadmill run.
        for (id in listOf("treadmill_walk", "incline_treadmill_walk", "outdoor_walk", "nordic_walking")) {
            assertEquals(id, ExerciseSessionRecord.EXERCISE_TYPE_WALKING, record(workout(id)).exerciseType)
            assertEquals(id, ExerciseSegment.EXERCISE_SEGMENT_TYPE_WALKING, record(workout(id)).segments.single().segmentType)
        }
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL, record(workout("curved_treadmill")).exerciseType)
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING, record(workout("stair_climbing")).exerciseType)
        // Conditioning drills keep their own segment inside a strength session.
        val hiit = record(workout("jump_rope", "jumping_jack", "medicine_ball_slam", "barbell_back_squat"))
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, hiit.exerciseType)
        assertEquals(
            listOf(
                ExerciseSegment.EXERCISE_SEGMENT_TYPE_JUMP_ROPE,
                ExerciseSegment.EXERCISE_SEGMENT_TYPE_JUMPING_JACK,
                ExerciseSegment.EXERCISE_SEGMENT_TYPE_BALL_SLAM,
                ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT,
            ),
            hiit.segments.map { it.segmentType },
        )
    }

    @Test
    fun theWorkoutsOwnNameWins() {
        assertEquals("Sunday league", record(workout("football", name = " Sunday league ")).title)
    }

    @Test
    fun anOwnSportIsAnOtherWorkoutWithItsName() {
        val r = record(workout(own.id))
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT, r.exerciseType)
        assertEquals("Squash", r.title)
        assertEquals(ExerciseSegment.EXERCISE_SEGMENT_TYPE_OTHER_WORKOUT, r.segments.single().segmentType)
    }

    @Test
    fun aMixedWorkoutStaysOneValidStrengthSession() {
        val planned = WorkoutExportPlanner.session(workout("barbell_back_squat", "football", "outdoor_run"), info)
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, planned.exerciseType)
        assertNull(planned.title)
        assertEquals(
            listOf(ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT, ExerciseSegment.EXERCISE_SEGMENT_TYPE_OTHER_WORKOUT, ExerciseSegment.EXERCISE_SEGMENT_TYPE_OTHER_WORKOUT),
            planned.segments.map { it.type },
        )
        val r = record(workout("barbell_back_squat", "football", "outdoor_run"))
        assertEquals(3, r.segments.size)
        assertEquals("generic ${ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING}", r.title)
    }

    @Test
    fun differentActivitiesTogetherAreAnOtherWorkoutKeepingTheirSegmentsTitledWithTheirNames() {
        val r = record(workout("outdoor_walk", "outdoor_run"))
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT, r.exerciseType)
        assertEquals("name of outdoor_walk + name of outdoor_run", r.title)
        assertEquals(listOf(ExerciseSegment.EXERCISE_SEGMENT_TYPE_WALKING, ExerciseSegment.EXERCISE_SEGMENT_TYPE_RUNNING), r.segments.map { it.segmentType })
        // Each name once, in the order logged; an own sport with its own name.
        assertEquals("name of football + Squash", WorkoutExportPlanner.session(workout("football", own.id, "football"), info).title)
        // A cool-down stretch does not count, as for the session type.
        assertEquals("name of padel", WorkoutExportPlanner.session(workout("padel", "hamstring_stretch"), info).title)
        // A treadmill and a sport: no strength training either, but no sport's name (the treadmill is none).
        val mixed = record(workout("treadmill", "football"))
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT, mixed.exerciseType)
        assertEquals("generic ${ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT}", mixed.title)
    }

    @Test
    fun onlySportsBringATitle() {
        assertNull(info("treadmill").title)
        assertNull(info("barbell_bench_press").title)
        assertEquals("name of yoga", info("yoga").title)
        // One rule for the exporter and the screens: an own "sport" logged by reps is not one.
        val reps = own.copy(id = "u:reps", kind = ExerciseKind.BODYWEIGHT_REPS)
        val byReps = TrainingRepository.buildExercises(emptyList(), listOf(reps), emptyMap()).getValue("u:reps")
        assertFalse(TrainingMath.isSport(byReps))
        assertNull(WorkoutExerciseInfo.of(byReps) { "x" }.title)
        assertTrue(TrainingMath.isSport(exercises.getValue("football")))
    }
}
