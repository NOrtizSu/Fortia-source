package com.enjoyyourtime.globalfitness.training

import androidx.health.connect.client.records.ExerciseSessionRecord
import com.enjoyyourtime.globalfitness.UiText
import com.enjoyyourtime.globalfitness.health.HealthMappings
import com.enjoyyourtime.globalfitness.health.WorkoutExportPlanner
import com.enjoyyourtime.globalfitness.training.catalog.ExerciseCatalog
import com.enjoyyourtime.globalfitness.training.catalog.ExerciseGuides
import com.enjoyyourtime.globalfitness.training.catalog.SportsCatalog
import com.enjoyyourtime.globalfitness.data.Sex
import com.enjoyyourtime.globalfitness.training.data.TrainingRepository
import com.enjoyyourtime.globalfitness.training.domain.Achievement
import com.enjoyyourtime.globalfitness.training.domain.AchievementInput
import com.enjoyyourtime.globalfitness.training.domain.Achievements
import com.enjoyyourtime.globalfitness.training.domain.BodyShapeModel
import com.enjoyyourtime.globalfitness.training.domain.ExerciseHistory
import com.enjoyyourtime.globalfitness.training.domain.LoadModel
import com.enjoyyourtime.globalfitness.training.domain.MuscleStats
import com.enjoyyourtime.globalfitness.training.domain.TrainingMath
import com.enjoyyourtime.globalfitness.training.model.Equipment
import com.enjoyyourtime.globalfitness.training.model.Exercise
import com.enjoyyourtime.globalfitness.training.model.ExerciseKind
import com.enjoyyourtime.globalfitness.training.model.Muscle
import com.enjoyyourtime.globalfitness.training.model.RoutineExercise
import com.enjoyyourtime.globalfitness.training.model.Workout
import com.enjoyyourtime.globalfitness.training.model.WorkoutEntry
import com.enjoyyourtime.globalfitness.training.model.WorkoutSet
import com.enjoyyourtime.globalfitness.training.music.TodayTraining
import com.enjoyyourtime.globalfitness.training.music.TrainingDay
import com.enjoyyourtime.globalfitness.ui.progress.MuscleMapLogic
import com.enjoyyourtime.globalfitness.ui.training.ProgressMetric
import com.enjoyyourtime.globalfitness.ui.training.RoutineStats
import com.enjoyyourtime.globalfitness.ui.training.SetField
import com.enjoyyourtime.globalfitness.ui.training.SetTable
import com.enjoyyourtime.globalfitness.ui.training.TrainingFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset

/** The sports and activities of the catalog: their entries, how they are logged and what they never count for. */
class SportsCatalogTest {
    private val sports = SportsCatalog.all
    private val exercises = TrainingRepository.buildExercises(ExerciseCatalog.all, emptyList(), emptyMap())
    private val lookup = { id: String -> exercises[id] ?: TrainingRepository.unknownExercise(id) }

    private val day = LocalDate.of(2026, 10, 26)
    private val start = day.atTime(18, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val minute = 60_000L

    private fun workout(vararg entries: Pair<String, WorkoutSet>, at: Long = start, minutes: Long = 90) = Workout(
        id = "w$at",
        startedAt = at,
        endedAt = at + minutes * minute,
        name = null,
        note = null,
        updatedAt = at,
        entries = entries.mapIndexed { i, (id, set) -> WorkoutEntry("e$i", id, listOf(set)) },
    )

    private fun timed(seconds: Int, km: Double? = null, at: Long = start + 90 * minute) =
        WorkoutSet("s$seconds-$at", durationSec = seconds, distanceM = km?.let { it * 1000 }, done = true, doneAt = at)

    private fun lifted(at: Long = start + 30 * minute) = WorkoutSet("lift$at", weightKg = 80.0, reps = 8, done = true, doneAt = at)

    @Test
    fun theApprovedActivitiesAreInTheCatalogAsSportsLoggedByTime() {
        assertEquals(EXPECTED_SESSION.keys, sports.map { it.id }.toSet())
        assertEquals(sports.size, sports.map { it.id }.toSet().size)
        for (e in sports) {
            assertTrue("${e.id} is not in the catalog", ExerciseCatalog[e.id] === e)
            assertEquals(e.id, ExerciseKind.CARDIO, e.kind)
            assertEquals(e.id, Equipment.SPORT, e.equipment)
            assertTrue("${e.id} loads no muscle", e.primary.isNotEmpty())
            assertTrue("${e.id} repeats a primary muscle as secondary", (e.primary intersect e.secondary).isEmpty())
            assertNotNull("${e.id} has no guide", ExerciseGuides[e.id])
        }
        // Only sports and activities are SPORT: the gym exercises keep their equipment.
        assertEquals(sports.toSet(), ExerciseCatalog.all.filter { it.equipment == Equipment.SPORT }.toSet())
    }

    @Test
    fun eachActivityIsSavedToHealthConnectAsItsOwnSport() {
        for (e in sports) {
            assertEquals(e.id, EXPECTED_SESSION.getValue(e.id), e.hcSession)
            assertTrue("${e.id}: no name for its session type", e.hcSession in HealthMappings.knownExerciseTypes)
            // A segment type is only set where the activity's own session accepts it.
            e.hcSegment?.let { assertTrue("${e.id}: segment $it not allowed in its session", WorkoutExportPlanner.compatible(it, e.hcSession!!)) }
        }
    }

    @Test
    fun onlyTheActivitiesThatCoverADistanceLogOne() {
        val withDistance = sports.filter { SportsCatalog.coversDistance(lookup(it.id)) }.map { it.id }.toSet()
        assertEquals(
            setOf(
                "outdoor_walk", "hiking", "nordic_walking", "outdoor_run", "outdoor_cycling", "swimming",
                "cross_country_skiing", "rowing", "kayaking", "open_water_swimming", "inline_skating",
            ),
            withDistance,
        )
        // The user's own sport may cover one: the field is there, optional.
        assertTrue(SportsCatalog.coversDistance(ownSport()))
    }

    @Test
    fun aSportHasTimeDistanceWhereItAppliesAndCaloriesButNoMachineLevel() {
        assertEquals(listOf(SetField.TIME, SetField.KCAL), SetTable.columns(lookup("football")).fields)
        assertEquals(listOf(SetField.TIME, SetField.DISTANCE, SetField.KCAL), SetTable.columns(lookup("outdoor_walk")).fields)
        assertEquals(listOf(SetField.TIME, SetField.DISTANCE, SetField.KCAL), SetTable.columns(ownSport()).fields)
        assertFalse(SetTable.columns(lookup("swimming")).previous)
        // Cardio machines keep their level; an own sport logged another way keeps its kind's columns.
        assertEquals(SetTable.columns(ExerciseKind.CARDIO), SetTable.columns(lookup("treadmill")))
        assertEquals(SetTable.columns(ExerciseKind.WEIGHT_REPS), SetTable.columns(ownSport(ExerciseKind.WEIGHT_REPS)))
        assertTrue(TrainingMath.isSport(lookup("yoga")))
        assertFalse(TrainingMath.isSport(lookup("treadmill")))
        // The progress charts: no distance chart for a sport that logs none.
        assertEquals(listOf(ProgressMetric.TOTAL_TIME), ProgressMetric.forExercise(lookup("tennis")))
        assertEquals(listOf(ProgressMetric.TOTAL_TIME, ProgressMetric.DISTANCE), ProgressMetric.forExercise(lookup("outdoor_cycling")))
        assertEquals(ProgressMetric.forKind(ExerciseKind.WEIGHT_REPS, false), ProgressMetric.forExercise(lookup("barbell_bench_press")))
    }

    @Test
    fun aWholeMatchFitsTheTimeField() {
        // Typed from the right: 1 h 30 min of football, a 4-hour round of golf.
        assertEquals(5_400, TrainingFormat.digitsToSeconds("13000"))
        assertEquals(5_400, TrainingFormat.digitsToSeconds("9000"))
        assertEquals("1:30:00", TrainingFormat.duration(5_400, java.util.Locale.ROOT))
        assertEquals(14_400, TrainingFormat.digitsToSeconds("40000"))
    }

    @Test
    fun sportsNeverCountAsTrainingVolume() {
        val match = workout("football" to timed(5_400))
        val workouts = listOf(match)
        val now = start + 100 * minute
        // Weekly hard sets, recovery and the strength trend ignore them, like the cardio machines.
        assertTrue(MuscleStats.weekly(workouts, lookup, now).values.all { it.sets == 0.0 })
        assertTrue(MuscleStats.recovery(workouts, lookup, now).values.all { it.lastTrainedAt == null && it.fraction == 1.0 })
        assertTrue(MuscleStats.exerciseTrends(workouts, lookup, Long.MIN_VALUE, Long.MAX_VALUE).isEmpty())
        assertTrue(ExerciseHistory.workoutMuscles(match, lookup).isEmpty())
        assertEquals(0.0, ExerciseHistory.workoutVolumeKg(match, lookup), 0.0)
        // No records: a longer match is not a personal record of a lift.
        val longer = timed(6_000, at = start + 2 * 24 * 60 * minute)
        assertTrue(ExerciseHistory.newRecords(longer, ExerciseKind.CARDIO, match.entries.single().sets).isEmpty())
        // The muscle page does not list them as having trained the quads, and a routine plans no sets for them.
        val sessions = ExerciseHistory.allSessions(workouts, exercises = lookup)
        assertTrue(MuscleMapLogic.recentExercises(Muscle.QUADS, sessions, lookup).isEmpty())
        assertTrue(RoutineStats.muscles(listOf(RoutineExercise("football", 1, durationSec = 5_400)), lookup).isEmpty())
        // A gym exercise next to it still counts as before.
        val mixed = workout("football" to timed(3_600), "barbell_back_squat" to lifted())
        assertEquals(1.0, MuscleStats.weekly(listOf(mixed), lookup, now).getValue(Muscle.QUADS).sets, 1e-9)
        assertEquals(listOf("barbell_back_squat"), MuscleMapLogic.recentExercises(Muscle.QUADS, ExerciseHistory.allSessions(listOf(mixed), exercises = lookup), lookup).map { it.exercise.id })
    }

    @Test
    fun aDayOfSportGetsTheCardioMusic() {
        fun classify(vararg w: Workout) = TodayTraining.classify(w.toList(), day, ZoneOffset.UTC, start + 4 * 60 * minute, lookup)
        assertEquals(TrainingDay.CARDIO, classify(workout("football" to timed(5_400))))
        assertEquals(TrainingDay.CARDIO, classify(workout("yoga" to timed(3_600))))
        assertEquals(TrainingDay.CARDIO, classify(workout("outdoor_run" to timed(1_800, km = 5.0), "outdoor_walk" to timed(600, km = 1.0), "plank" to timed(60))))
        // As with the cardio machines, a tie keeps the strength music.
        assertEquals(TrainingDay.STRENGTH, classify(workout("tennis" to timed(3_600), "barbell_bench_press" to lifted())))
    }

    @Test
    fun aSportStartsNoRestTimerAndARoutinePlansAnHourOfIt() {
        assertFalse(SetTable.startsRest(lookup("football")))
        assertFalse(SetTable.startsRest(ownSport()))
        // Cardio machines keep their rest.
        assertTrue(SetTable.startsRest(lookup("treadmill")))
        // Never done: one hour (a cardio machine ten minutes); done before: as last time.
        assertEquals(RoutineExercise("football", 1, durationSec = 3_600), RoutineStats.defaultTarget("football", ExerciseKind.CARDIO, emptyList(), sport = true))
        assertEquals(RoutineExercise("treadmill", 1, durationSec = 600), RoutineStats.defaultTarget("treadmill", ExerciseKind.CARDIO, emptyList()))
        assertEquals(RoutineExercise("football", 1, durationSec = 5_400), RoutineStats.defaultTarget("football", ExerciseKind.CARDIO, listOf(timed(5_400)), sport = true))
    }

    @Test
    fun sportsBringNoVolumeRecordsOrBodyShapeButCountForWorkoutsVarietyAndDistance() {
        val matches = (0 until 4).map { k ->
            val at = start - k * 24 * 60 * minute
            workout("football" to timed(5_400, at = at + 90 * minute), "outdoor_run" to timed(1_800, km = 5.0, at = at + 120 * minute), at = at, minutes = 120)
        }
        val board = Achievements.evaluate(
            AchievementInput(matches, lookup, emptyList(), Sex.MALE, 30, null, ZoneOffset.UTC, DayOfWeek.MONDAY, start + 200 * minute, null, emptyMap()),
        )
        assertEquals(4.0, board.state(Achievement.WORKOUTS)!!.progress!!, 0.0)
        assertEquals(0.0, board.state(Achievement.VOLUME)!!.progress!!, 0.0)
        // A longer match is not a personal record.
        assertEquals(0.0, board.state(Achievement.RECORDS)!!.progress!!, 0.0)
        assertEquals(2.0, board.state(Achievement.EXERCISE_VARIETY)!!.progress!!, 0.0)
        assertEquals(20.0, board.state(Achievement.CARDIO_DISTANCE)!!.progress!!, 1e-9)
        // The body figure: sports are no training volume.
        val none = matches.map { it.copy(entries = emptyList()) }
        assertEquals(
            BodyShapeModel.trainingScore(start + 200 * minute, none, lookup, LoadModel.WITHOUT_BODYWEIGHT),
            BodyShapeModel.trainingScore(start + 200 * minute, matches, lookup, LoadModel.WITHOUT_BODYWEIGHT),
        )
    }

    private fun ownSport(kind: ExerciseKind = ExerciseKind.CARDIO) =
        Exercise("u:squash", UiText.raw("Squash"), kind, Equipment.SPORT, setOf(Muscle.QUADS), emptySet(), builtIn = false)

    private companion object {
        val EXPECTED_SESSION: Map<String, Int> = with(ExerciseSessionRecord) {
            mapOf(
                "football" to EXERCISE_TYPE_SOCCER,
                "futsal" to EXERCISE_TYPE_SOCCER,
                "basketball" to EXERCISE_TYPE_BASKETBALL,
                "volleyball" to EXERCISE_TYPE_VOLLEYBALL,
                "handball" to EXERCISE_TYPE_HANDBALL,
                "tennis" to EXERCISE_TYPE_TENNIS,
                "padel" to EXERCISE_TYPE_TENNIS,
                "badminton" to EXERCISE_TYPE_BADMINTON,
                "outdoor_walk" to EXERCISE_TYPE_WALKING,
                "hiking" to EXERCISE_TYPE_HIKING,
                "nordic_walking" to EXERCISE_TYPE_WALKING,
                "stair_climbing" to EXERCISE_TYPE_STAIR_CLIMBING,
                "outdoor_run" to EXERCISE_TYPE_RUNNING,
                "outdoor_cycling" to EXERCISE_TYPE_BIKING,
                "swimming" to EXERCISE_TYPE_SWIMMING_POOL,
                "yoga" to EXERCISE_TYPE_YOGA,
                "pilates" to EXERCISE_TYPE_PILATES,
                "dance" to EXERCISE_TYPE_DANCING,
                "boxing" to EXERCISE_TYPE_BOXING,
                "martial_arts" to EXERCISE_TYPE_MARTIAL_ARTS,
                "climbing" to EXERCISE_TYPE_ROCK_CLIMBING,
                "golf" to EXERCISE_TYPE_GOLF,
                "alpine_skiing" to EXERCISE_TYPE_SKIING,
                "cross_country_skiing" to EXERCISE_TYPE_SKIING,
                "snowboarding" to EXERCISE_TYPE_SNOWBOARDING,
                "surfing" to EXERCISE_TYPE_SURFING,
                "rowing" to EXERCISE_TYPE_ROWING,
                "kayaking" to EXERCISE_TYPE_PADDLING,
                "open_water_swimming" to EXERCISE_TYPE_SWIMMING_OPEN_WATER,
                "rugby" to EXERCISE_TYPE_RUGBY,
                "table_tennis" to EXERCISE_TYPE_TABLE_TENNIS,
                "squash" to EXERCISE_TYPE_SQUASH,
                "inline_skating" to EXERCISE_TYPE_SKATING,
                "ice_skating" to EXERCISE_TYPE_ICE_SKATING,
            )
        }
    }
}
