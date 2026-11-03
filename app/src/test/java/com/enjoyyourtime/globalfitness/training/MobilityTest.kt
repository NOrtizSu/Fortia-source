package com.enjoyyourtime.globalfitness.training

import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import com.enjoyyourtime.globalfitness.data.Sex
import com.enjoyyourtime.globalfitness.health.WorkoutExerciseInfo
import com.enjoyyourtime.globalfitness.health.WorkoutExportPlanner
import com.enjoyyourtime.globalfitness.training.catalog.ExerciseCatalog
import com.enjoyyourtime.globalfitness.training.catalog.RoutineCatalog
import com.enjoyyourtime.globalfitness.training.data.TrainingRepository
import com.enjoyyourtime.globalfitness.training.domain.Achievement
import com.enjoyyourtime.globalfitness.training.domain.AchievementInput
import com.enjoyyourtime.globalfitness.training.domain.Achievements
import com.enjoyyourtime.globalfitness.training.domain.BodyShapeModel
import com.enjoyyourtime.globalfitness.training.domain.ExerciseHistory
import com.enjoyyourtime.globalfitness.training.domain.LoadModel
import com.enjoyyourtime.globalfitness.training.domain.MuscleStats
import com.enjoyyourtime.globalfitness.training.domain.RecordKind
import com.enjoyyourtime.globalfitness.training.domain.TrainingMath
import com.enjoyyourtime.globalfitness.training.model.CustomExercise
import com.enjoyyourtime.globalfitness.training.model.Equipment
import com.enjoyyourtime.globalfitness.training.model.Exercise
import com.enjoyyourtime.globalfitness.training.model.ExerciseKind
import com.enjoyyourtime.globalfitness.training.model.Muscle
import com.enjoyyourtime.globalfitness.training.model.Workout
import com.enjoyyourtime.globalfitness.training.model.WorkoutEntry
import com.enjoyyourtime.globalfitness.training.model.WorkoutSet
import com.enjoyyourtime.globalfitness.training.music.TodayTraining
import com.enjoyyourtime.globalfitness.training.music.TrainingDay
import com.enjoyyourtime.globalfitness.ui.progress.MuscleMapLogic
import com.enjoyyourtime.globalfitness.ui.training.RoutineStats
import com.enjoyyourtime.globalfitness.ui.training.SetField
import com.enjoyyourtime.globalfitness.ui.training.SetTable
import com.enjoyyourtime.globalfitness.ui.training.WorkoutRecords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Stretches and mobility drills (CatalogExercise.mobility) are logged and exported like any
 * exercise but are not training load: no hard sets, recovery, body-shape volume, strength trend,
 * personal records, record achievements or "hard day" for the music, and a stretching-only workout
 * is a Health Connect stretching session.
 */
class MobilityTest {
    private val zone = ZoneOffset.UTC
    private val day = Duration.ofDays(1).toMillis()
    private val hour = Duration.ofHours(1).toMillis()
    private val now = 200 * day
    private val exercises = TrainingRepository.buildExercises(ExerciseCatalog.all, emptyList(), emptyMap())
    private val lookup: (String) -> Exercise = { exercises[it] ?: TrainingRepository.unknownExercise(it) }
    private var ids = 0

    private fun hold(sec: Int, at: Long) = WorkoutSet("s${ids++}", durationSec = sec, done = true, doneAt = at)

    private fun lift(kg: Double, reps: Int, at: Long) = WorkoutSet("s${ids++}", weightKg = kg, reps = reps, done = true, doneAt = at)

    private fun workout(at: Long, vararg entries: Pair<String, List<WorkoutSet>>) = Workout(
        id = "w${ids++}", startedAt = at, endedAt = at + hour, name = null, note = null, updatedAt = at,
        entries = entries.map { (ex, sets) -> WorkoutEntry("e${ids++}", ex, sets) },
    )

    private val stretches = ExerciseCatalog.all.filter { it.mobility }.map { it.id }

    @Test
    fun theCatalogFlagReachesTheExercisesAndOnlyBuiltInsHaveIt() {
        assertTrue("${stretches.size} stretches", stretches.size in 30..45)
        assertTrue(lookup("foam_roll_quads").mobility)
        assertTrue(lookup("downward_dog").mobility)
        assertFalse(lookup("jump_rope").mobility)
        assertTrue(lookup("hamstring_stretch").mobility)
        assertFalse(lookup("plank").mobility)
        val own = CustomExercise("u:1", "Stretch", ExerciseKind.DURATION, Equipment.BODYWEIGHT, setOf(Muscle.HAMSTRINGS), emptySet(), false, 0, 0)
        assertFalse(TrainingRepository.buildExercises(emptyList(), listOf(own), emptyMap()).getValue("u:1").mobility)
        for (id in stretches) {
            val ex = lookup(id)
            assertFalse("$id counts for muscles", TrainingMath.countsForMuscles(ex))
            assertFalse("$id has records", TrainingMath.hasRecords(ex))
        }
        assertTrue(TrainingMath.countsForMuscles(lookup("plank")))
    }

    @Test
    fun stretchesAddNoHardSetsAndNeedNoRecovery() {
        val w = workout(
            now - hour,
            "barbell_bench_press" to listOf(lift(60.0, 8, now - hour), lift(60.0, 8, now - hour)),
            "hamstring_stretch" to List(3) { hold(60, now - hour) },
            "doorway_chest_stretch" to List(2) { hold(45, now - hour) },
        )
        val week = MuscleStats.weekly(listOf(w), lookup, now)
        assertEquals(0.0, week.getValue(Muscle.HAMSTRINGS).sets, 0.0)
        assertEquals(0.0, week.getValue(Muscle.CALVES).sets, 0.0)
        // Only the bench press counts for the chest, not the doorway stretch.
        assertEquals(2.0, week.getValue(Muscle.CHEST).sets, 1e-9)
        val recovery = MuscleStats.recovery(listOf(w), lookup, now)
        assertNull(recovery.getValue(Muscle.HAMSTRINGS).lastTrainedAt)
        assertEquals(1.0, recovery.getValue(Muscle.HAMSTRINGS).fraction, 0.0)
        assertTrue(recovery.getValue(Muscle.CHEST).fraction < 1.0)
        // A workout of stretches only trains nothing at all.
        val stretchDay = workout(now - hour, *stretches.map { it to listOf(hold(60, now - hour)) }.toTypedArray())
        assertTrue(MuscleStats.weekly(listOf(stretchDay), lookup, now).values.all { it.sets == 0.0 })
        assertTrue(ExerciseHistory.workoutMuscles(stretchDay, lookup).isEmpty())
    }

    @Test
    fun longerHoldsAreRecordsForAPlankButNotForAStretch() {
        val first = workout(now - 2 * day, "plank" to listOf(hold(30, now - 2 * day)), "pigeon_stretch" to listOf(hold(30, now - 2 * day)))
        val second = workout(now - day, "plank" to listOf(hold(60, now - day)), "pigeon_stretch" to listOf(hold(90, now - day)))
        val workouts = listOf(first, second)
        val records = WorkoutRecords.forWorkout(workouts, second, lookup, LoadModel.WITHOUT_BODYWEIGHT)
        val plank = second.entries.first { it.exerciseId == "plank" }.sets.single()
        val pigeon = second.entries.first { it.exerciseId == "pigeon_stretch" }.sets.single()
        assertEquals(setOf(RecordKind.DURATION), records[plank.id])
        assertNull(records[pigeon.id])
        // The record achievement counts the plank's only.
        val board = Achievements.evaluate(
            AchievementInput(workouts, lookup, emptyList(), Sex.MALE, 30, null, zone, DayOfWeek.MONDAY, now, null, emptyMap()),
        )
        assertEquals(1.0, board.state(Achievement.RECORDS)!!.progress!!, 0.0)
        // Neither is a strength trend (no repetitions to estimate a 1RM from).
        assertTrue(MuscleStats.exerciseTrends(workouts, lookup, 0, now).isEmpty())
    }

    @Test
    fun stretchesAreNotTrainingVolumeForTheBodyShapeOrTheMuscleMap() {
        val stretchesOnly = (0 until 6).map { k -> workout(now - k * day, "hamstring_stretch" to List(4) { hold(60, now - k * day) }) }
        val nothing = stretchesOnly.map { it.copy(entries = emptyList()) }
        assertEquals(
            BodyShapeModel.trainingScore(now, nothing, lookup, LoadModel.WITHOUT_BODYWEIGHT),
            BodyShapeModel.trainingScore(now, stretchesOnly, lookup, LoadModel.WITHOUT_BODYWEIGHT),
        )
        val sessions = ExerciseHistory.allSessions(stretchesOnly + workout(now, "lying_leg_curl_machine" to listOf(lift(40.0, 10, now))), exercises = lookup)
        assertEquals(listOf("lying_leg_curl_machine"), MuscleMapLogic.recentExercises(Muscle.HAMSTRINGS, sessions, lookup).map { it.exercise.id })
    }

    @Test
    fun aStretchIsLoggedByTimeOnlyAndStartsNoRest() {
        for (id in stretches) {
            assertEquals(id, listOf(SetField.TIME), SetTable.columns(lookup(id)).fields)
            assertFalse("$id starts the rest timer", SetTable.startsRest(lookup(id)))
        }
        // A plank keeps its optional load, and the lifts their rest.
        assertEquals(listOf(SetField.TIME, SetField.LOAD), SetTable.columns(lookup("plank")).fields)
        assertTrue(SetTable.startsRest(lookup("plank")))
        assertTrue(SetTable.startsRest(lookup("barbell_bench_press")))
    }

    @Test
    fun stretchesAreNoNewExerciseForTheVarietyMedal() {
        val w = workout(
            now - hour,
            "barbell_bench_press" to listOf(lift(60.0, 8, now - hour)),
            *stretches.take(6).map { it to listOf(hold(60, now - hour)) }.toTypedArray(),
        )
        val board = Achievements.evaluate(
            AchievementInput(listOf(w), lookup, emptyList(), Sex.MALE, 30, null, zone, DayOfWeek.MONDAY, now, null, emptyMap()),
        )
        assertEquals(1.0, board.state(Achievement.EXERCISE_VARIETY)!!.progress!!, 0.0)
    }

    @Test
    fun aLongStretchingSessionIsNoHardDayButALongWorkoutWithAStretchIs() {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val at = today.atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
        val yesterday = at - day
        // Two hours of yoga-like stretching, ticked along the way.
        val stretching = workout(yesterday, *stretches.take(8).mapIndexed { i, id -> id to listOf(hold(60, yesterday + (i + 1) * 15 * 60_000L)) }.toTypedArray())
        assertEquals(TrainingDay.TRAINING_DAY, TodayTraining.classify(listOf(stretching), today, zone, at, lookup))
        // The same two hours with one lift among the stretches: a hard day.
        val mixed = stretching.copy(entries = stretching.entries + WorkoutEntry("e-lift", "barbell_back_squat", listOf(lift(80.0, 5, yesterday + 10 * 60_000L))))
        assertEquals(TrainingDay.RECOVERY, TodayTraining.classify(listOf(mixed), today, zone, at, lookup))
    }

    @Test
    fun theMobilityRoutineTrainsNoMuscleButIsTimed() {
        val routine = RoutineCatalog["mobility"]!!
        assertTrue(routine.exercises.all { lookup(it.exerciseId).mobility && it.durationSec != null })
        assertTrue(RoutineStats.muscles(routine.exercises, lookup).isEmpty())
        // A routine mixing both counts the lifts only.
        val mixed = RoutineCatalog["core"]!!.exercises + routine.exercises
        assertEquals(RoutineStats.muscles(RoutineCatalog["core"]!!.exercises, lookup), RoutineStats.muscles(mixed, lookup))
    }

    @Test
    fun musicTreatsAStretchingSessionAsARecoveryDay() {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val at = today.atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
        val classify = { workouts: List<Workout> -> TodayTraining.classify(workouts, today, zone, at + 2 * hour, lookup) }
        assertEquals(TrainingDay.RECOVERY, classify(listOf(workout(at, "cat_cow" to listOf(hold(60, at)), "childs_pose" to listOf(hold(60, at))))))
        // Leg stretches after an upper-body workout don't make it a leg day.
        val upper = listOf("barbell_bench_press", "lat_pulldown_machine").map { it to listOf(lift(50.0, 10, at)) }
        val legStretches = listOf("hamstring_stretch", "hip_flexor_stretch", "pigeon_stretch", "calf_stretch").map { it to listOf(hold(45, at)) }
        assertEquals(TrainingDay.STRENGTH, classify(listOf(workout(at, *(upper + legStretches).toTypedArray()))))
        // Thirty stretching sets yesterday are no hard day.
        val yesterday = at - day
        val longStretch = workout(yesterday, *stretches.take(10).map { it to List(3) { hold(30, yesterday) } }.toTypedArray())
        assertEquals(TrainingDay.TRAINING_DAY, TodayTraining.classify(listOf(longStretch), today, zone, at, lookup))
    }

    @Test
    fun healthConnectWritesStretchingSegmentsAndAStretchingSessionWhenOnlyStretching() {
        val stretching = ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING
        val stretch = WorkoutExerciseInfo(cardio = false, hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_STRETCHING, mobility = true)
        val bench = WorkoutExerciseInfo(cardio = false, hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_BENCH_PRESS)
        val treadmill = WorkoutExerciseInfo(
            cardio = true,
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_RUNNING_TREADMILL,
            hcSession = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL,
        )
        assertEquals(stretching, WorkoutExportPlanner.sessionType(listOf(stretch, stretch)))
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, WorkoutExportPlanner.sessionType(listOf(bench, stretch)))
        // A cool-down does not turn a run into strength training.
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL, WorkoutExportPlanner.sessionType(listOf(treadmill, stretch)))
        for (session in listOf(stretching, ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL)) {
            assertEquals(ExerciseSegment.EXERCISE_SEGMENT_TYPE_STRETCHING, WorkoutExportPlanner.segmentType(stretch, session))
        }
        // From the catalog: every stretch is a stretching segment, and the exporter's info carries the flag.
        for (id in stretches) assertEquals(ExerciseSegment.EXERCISE_SEGMENT_TYPE_STRETCHING, lookup(id).hcSegment)
        val w = workout(now, "cat_cow" to listOf(hold(60, now)), "childs_pose" to listOf(hold(60, now + 90_000)))
        val info = { id: String -> WorkoutExerciseInfo.of(lookup(id)) { null } }
        val session = WorkoutExportPlanner.session(w, info)
        assertEquals(stretching, session.exerciseType)
        assertTrue(session.segments.isNotEmpty() && session.segments.all { it.type == ExerciseSegment.EXERCISE_SEGMENT_TYPE_STRETCHING })
    }
}
