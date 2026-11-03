package com.enjoyyourtime.globalfitness.training.catalog

import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.training.model.CatalogExercise
import com.enjoyyourtime.globalfitness.training.model.Equipment.SPORT
import com.enjoyyourtime.globalfitness.training.model.Exercise
import com.enjoyyourtime.globalfitness.training.model.ExerciseKind.CARDIO
import com.enjoyyourtime.globalfitness.training.model.Muscle.ABS
import com.enjoyyourtime.globalfitness.training.model.Muscle.ADDUCTORS
import com.enjoyyourtime.globalfitness.training.model.Muscle.BICEPS
import com.enjoyyourtime.globalfitness.training.model.Muscle.CALVES
import com.enjoyyourtime.globalfitness.training.model.Muscle.CHEST
import com.enjoyyourtime.globalfitness.training.model.Muscle.FOREARMS
import com.enjoyyourtime.globalfitness.training.model.Muscle.GLUTES
import com.enjoyyourtime.globalfitness.training.model.Muscle.HAMSTRINGS
import com.enjoyyourtime.globalfitness.training.model.Muscle.LATS
import com.enjoyyourtime.globalfitness.training.model.Muscle.LOWER_BACK
import com.enjoyyourtime.globalfitness.training.model.Muscle.OBLIQUES
import com.enjoyyourtime.globalfitness.training.model.Muscle.QUADS
import com.enjoyyourtime.globalfitness.training.model.Muscle.SHOULDERS
import com.enjoyyourtime.globalfitness.training.model.Muscle.TRICEPS

/**
 * Sports and outdoor activities ("Sports and activities" in the catalog), part of
 * [ExerciseCatalog.all]. Ids are stored like every catalog id: never rename or remove one. Names
 * are `ex_<id>` and guides `exg_<id>_steps` / `exg_<id>_tips` (tools/i18n/src/<locale>/97_sports.txt
 * and 97_sport_guides.txt).
 *
 * A sport is usually the whole session ("football, 90 min"): it is logged as [CARDIO] (time,
 * calories, and the distance where the activity covers one, see [coversDistance]), with no machine
 * level. Like the cardio machines it never counts as training volume (weekly hard sets, recovery,
 * strength trends, records: see TrainingMath.countsForMuscles); its muscles, the ones the activity
 * mostly loads, are only shown in the info sheet and the animation.
 *
 * Health Connect: [CatalogExercise.hcSession] is the session type a workout with only this activity
 * is saved as. Football and futsal are "Soccer", padel (which Health Connect does not have) is
 * "Tennis", its closest racket sport, and swimming is "Swimming (pool)", where most people log their
 * lengths; such a session takes the activity's own name as its title (see WorkoutExerciseInfo.title).
 * [CatalogExercise.hcSegment] is set only where a segment type names the activity and its session
 * type accepts it (walking, Nordic walking, stairs, running, cycling, pool swimming, yoga,
 * pilates); the others are written as "other workout" segments, like the sets of a mixed workout.
 */
object SportsCatalog {
    val all: List<CatalogExercise> = listOf(
        // Ball games
        CatalogExercise(
            "football", R.string.ex_football, CARDIO, SPORT,
            primary = setOf(QUADS, HAMSTRINGS, GLUTES, CALVES), secondary = setOf(ADDUCTORS, OBLIQUES),
            hcSession = ExerciseSessionRecord.EXERCISE_TYPE_SOCCER,
        ),
        CatalogExercise(
            "futsal", R.string.ex_futsal, CARDIO, SPORT,
            primary = setOf(QUADS, HAMSTRINGS, GLUTES, CALVES), secondary = setOf(ADDUCTORS, OBLIQUES),
            hcSession = ExerciseSessionRecord.EXERCISE_TYPE_SOCCER,
        ),
        CatalogExercise(
            "basketball", R.string.ex_basketball, CARDIO, SPORT,
            primary = setOf(QUADS, GLUTES, CALVES), secondary = setOf(HAMSTRINGS, SHOULDERS, TRICEPS),
            hcSession = ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL,
        ),
        CatalogExercise(
            "volleyball", R.string.ex_volleyball, CARDIO, SPORT,
            primary = setOf(QUADS, CALVES, SHOULDERS), secondary = setOf(GLUTES, ABS, TRICEPS),
            hcSession = ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL,
        ),
        CatalogExercise(
            "handball", R.string.ex_handball, CARDIO, SPORT,
            primary = setOf(QUADS, SHOULDERS, CALVES), secondary = setOf(GLUTES, OBLIQUES, TRICEPS),
            hcSession = ExerciseSessionRecord.EXERCISE_TYPE_HANDBALL,
        ),
        // Racket sports
        CatalogExercise(
            "tennis", R.string.ex_tennis, CARDIO, SPORT,
            primary = setOf(SHOULDERS, OBLIQUES, QUADS), secondary = setOf(FOREARMS, CALVES, GLUTES),
            hcSession = ExerciseSessionRecord.EXERCISE_TYPE_TENNIS,
        ),
        CatalogExercise(
            "padel", R.string.ex_padel, CARDIO, SPORT,
            primary = setOf(SHOULDERS, OBLIQUES, QUADS), secondary = setOf(FOREARMS, CALVES, GLUTES),
            hcSession = ExerciseSessionRecord.EXERCISE_TYPE_TENNIS,
        ),
        CatalogExercise(
            "badminton", R.string.ex_badminton, CARDIO, SPORT,
            primary = setOf(SHOULDERS, QUADS, CALVES), secondary = setOf(FOREARMS, OBLIQUES, GLUTES),
            hcSession = ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON,
        ),
        // On foot, on wheels, in the water: they cover a distance
        CatalogExercise(
            "outdoor_walk", R.string.ex_outdoor_walk, CARDIO, SPORT,
            primary = setOf(QUADS, CALVES), secondary = setOf(GLUTES, HAMSTRINGS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_WALKING,
            hcSession = ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
        ),
        CatalogExercise(
            "hiking", R.string.ex_hiking, CARDIO, SPORT,
            primary = setOf(QUADS, GLUTES, CALVES), secondary = setOf(HAMSTRINGS, LOWER_BACK),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_WALKING,
            hcSession = ExerciseSessionRecord.EXERCISE_TYPE_HIKING,
        ),
        CatalogExercise(
            "nordic_walking", R.string.ex_nordic_walking, CARDIO, SPORT,
            primary = setOf(QUADS, CALVES), secondary = setOf(GLUTES, LATS, TRICEPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_WALKING,
            hcSession = ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
        ),
        CatalogExercise(
            "stair_climbing", R.string.ex_stair_climbing, CARDIO, SPORT,
            primary = setOf(QUADS, GLUTES, CALVES), secondary = setOf(HAMSTRINGS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_STAIR_CLIMBING,
            hcSession = ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING,
        ),
        CatalogExercise(
            "outdoor_run", R.string.ex_outdoor_run, CARDIO, SPORT,
            primary = setOf(QUADS, HAMSTRINGS, GLUTES, CALVES), secondary = setOf(ADDUCTORS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_RUNNING,
            hcSession = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        ),
        CatalogExercise(
            "outdoor_cycling", R.string.ex_outdoor_cycling, CARDIO, SPORT,
            primary = setOf(QUADS, GLUTES), secondary = setOf(HAMSTRINGS, CALVES),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_BIKING,
            hcSession = ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
        ),
        CatalogExercise(
            "swimming", R.string.ex_swimming, CARDIO, SPORT,
            primary = setOf(LATS, SHOULDERS), secondary = setOf(TRICEPS, CHEST, ABS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_SWIMMING_POOL,
            hcSession = ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,
        ),
        // Mind and body, dance
        CatalogExercise(
            "yoga", R.string.ex_yoga, CARDIO, SPORT,
            primary = setOf(ABS, SHOULDERS, HAMSTRINGS), secondary = setOf(GLUTES, LOWER_BACK, QUADS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_YOGA,
            hcSession = ExerciseSessionRecord.EXERCISE_TYPE_YOGA,
        ),
        CatalogExercise(
            "pilates", R.string.ex_pilates, CARDIO, SPORT,
            primary = setOf(ABS, OBLIQUES), secondary = setOf(LOWER_BACK, GLUTES, ADDUCTORS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_PILATES,
            hcSession = ExerciseSessionRecord.EXERCISE_TYPE_PILATES,
        ),
        CatalogExercise(
            "dance", R.string.ex_dance, CARDIO, SPORT,
            primary = setOf(QUADS, GLUTES, CALVES), secondary = setOf(HAMSTRINGS, ABS, OBLIQUES),
            hcSession = ExerciseSessionRecord.EXERCISE_TYPE_DANCING,
        ),
        // Combat sports
        CatalogExercise(
            "boxing", R.string.ex_boxing, CARDIO, SPORT,
            primary = setOf(SHOULDERS, TRICEPS, OBLIQUES), secondary = setOf(CHEST, CALVES, ABS),
            hcSession = ExerciseSessionRecord.EXERCISE_TYPE_BOXING,
        ),
        CatalogExercise(
            "martial_arts", R.string.ex_martial_arts, CARDIO, SPORT,
            primary = setOf(QUADS, GLUTES, OBLIQUES), secondary = setOf(SHOULDERS, ADDUCTORS, HAMSTRINGS),
            hcSession = ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS,
        ),
        // Others
        CatalogExercise(
            "climbing", R.string.ex_climbing, CARDIO, SPORT,
            primary = setOf(LATS, FOREARMS, BICEPS), secondary = setOf(SHOULDERS, ABS, CALVES),
            hcSession = ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING,
        ),
        CatalogExercise(
            "golf", R.string.ex_golf, CARDIO, SPORT,
            primary = setOf(OBLIQUES, SHOULDERS), secondary = setOf(FOREARMS, GLUTES, LOWER_BACK),
            hcSession = ExerciseSessionRecord.EXERCISE_TYPE_GOLF,
        ),
    )

    /** The built-in activities that cover a distance worth logging (km): walks, hikes, runs, rides, swims. */
    private val distance = setOf("outdoor_walk", "hiking", "nordic_walking", "outdoor_run", "outdoor_cycling", "swimming")

    /**
     * Whether a sport's sets have a distance field: the built-in activities in [distance], and the
     * user's own sports (they may cover one; the field stays optional).
     */
    fun coversDistance(exercise: Exercise): Boolean = !exercise.builtIn || exercise.id in distance

    /** The guide of each activity, joined into [ExerciseGuides] (same order as [all]). */
    internal val guides: Map<String, ExerciseGuide> = mapOf(
        "football" to ExerciseGuide(R.string.exg_football_steps, R.string.exg_football_tips),
        "futsal" to ExerciseGuide(R.string.exg_futsal_steps, R.string.exg_futsal_tips),
        "basketball" to ExerciseGuide(R.string.exg_basketball_steps, R.string.exg_basketball_tips),
        "volleyball" to ExerciseGuide(R.string.exg_volleyball_steps, R.string.exg_volleyball_tips),
        "handball" to ExerciseGuide(R.string.exg_handball_steps, R.string.exg_handball_tips),
        "tennis" to ExerciseGuide(R.string.exg_tennis_steps, R.string.exg_tennis_tips),
        "padel" to ExerciseGuide(R.string.exg_padel_steps, R.string.exg_padel_tips),
        "badminton" to ExerciseGuide(R.string.exg_badminton_steps, R.string.exg_badminton_tips),
        "outdoor_walk" to ExerciseGuide(R.string.exg_outdoor_walk_steps, R.string.exg_outdoor_walk_tips),
        "hiking" to ExerciseGuide(R.string.exg_hiking_steps, R.string.exg_hiking_tips),
        "nordic_walking" to ExerciseGuide(R.string.exg_nordic_walking_steps, R.string.exg_nordic_walking_tips),
        "stair_climbing" to ExerciseGuide(R.string.exg_stair_climbing_steps, R.string.exg_stair_climbing_tips),
        "outdoor_run" to ExerciseGuide(R.string.exg_outdoor_run_steps, R.string.exg_outdoor_run_tips),
        "outdoor_cycling" to ExerciseGuide(R.string.exg_outdoor_cycling_steps, R.string.exg_outdoor_cycling_tips),
        "swimming" to ExerciseGuide(R.string.exg_swimming_steps, R.string.exg_swimming_tips),
        "yoga" to ExerciseGuide(R.string.exg_yoga_steps, R.string.exg_yoga_tips),
        "pilates" to ExerciseGuide(R.string.exg_pilates_steps, R.string.exg_pilates_tips),
        "dance" to ExerciseGuide(R.string.exg_dance_steps, R.string.exg_dance_tips),
        "boxing" to ExerciseGuide(R.string.exg_boxing_steps, R.string.exg_boxing_tips),
        "martial_arts" to ExerciseGuide(R.string.exg_martial_arts_steps, R.string.exg_martial_arts_tips),
        "climbing" to ExerciseGuide(R.string.exg_climbing_steps, R.string.exg_climbing_tips),
        "golf" to ExerciseGuide(R.string.exg_golf_steps, R.string.exg_golf_tips),
    )
}
