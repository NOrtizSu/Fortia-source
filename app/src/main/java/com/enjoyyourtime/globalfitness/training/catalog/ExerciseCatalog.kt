package com.enjoyyourtime.globalfitness.training.catalog

import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.training.model.CatalogExercise
import com.enjoyyourtime.globalfitness.training.model.Equipment
import com.enjoyyourtime.globalfitness.training.model.Equipment.BAND
import com.enjoyyourtime.globalfitness.training.model.Equipment.BARBELL
import com.enjoyyourtime.globalfitness.training.model.Equipment.BODYWEIGHT
import com.enjoyyourtime.globalfitness.training.model.Equipment.CABLE
import com.enjoyyourtime.globalfitness.training.model.Equipment.CARDIO_MACHINE
import com.enjoyyourtime.globalfitness.training.model.Equipment.DUMBBELL
import com.enjoyyourtime.globalfitness.training.model.Equipment.KETTLEBELL
import com.enjoyyourtime.globalfitness.training.model.Equipment.MACHINE
import com.enjoyyourtime.globalfitness.training.model.Equipment.OTHER
import com.enjoyyourtime.globalfitness.training.model.Equipment.SMITH_MACHINE
import com.enjoyyourtime.globalfitness.training.model.ExerciseKind.ASSISTED_REPS
import com.enjoyyourtime.globalfitness.training.model.ExerciseKind.BODYWEIGHT_REPS
import com.enjoyyourtime.globalfitness.training.model.ExerciseKind.CARDIO
import com.enjoyyourtime.globalfitness.training.model.ExerciseKind.DURATION
import com.enjoyyourtime.globalfitness.training.model.ExerciseKind.WEIGHT_REPS
import com.enjoyyourtime.globalfitness.training.model.Muscle
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
import com.enjoyyourtime.globalfitness.training.model.Muscle.NECK
import com.enjoyyourtime.globalfitness.training.model.Muscle.OBLIQUES
import com.enjoyyourtime.globalfitness.training.model.Muscle.QUADS
import com.enjoyyourtime.globalfitness.training.model.Muscle.SHOULDERS
import com.enjoyyourtime.globalfitness.training.model.Muscle.TRAPS
import com.enjoyyourtime.globalfitness.training.model.Muscle.TRICEPS

/**
 * Built-in exercises and machines. Ids are stored in workouts and in the cloud backup: never
 * rename or remove one (mark it hidden in a later version if needed). Names are string resources
 * `ex_<id>` (tools/i18n/src/<locale>/97_exercises.txt).
 *
 * Muscles follow the usual hard-set counting: [CatalogExercise.primary] are the prime movers (one
 * set each), [CatalogExercise.secondary] the synergists that get real work (half a set); stabilizers
 * are left out so the weekly volume is not inflated. Every muscle of the body map is the primary of
 * at least two entries, so each one can be trained from the catalog alone.
 *
 * Stretches and mobility drills ([CatalogExercise.mobility]) are logged by time and exported as
 * stretching segments, but they are not training load: their primary muscles are the ones they
 * stretch, and they add no hard sets, recovery, strength trend or records. The rule above (two
 * primaries per muscle) holds without them.
 *
 * Health Connect: [CatalogExercise.hcSegment] is set only when a segment type really names the
 * movement (null exports as generic weightlifting). Every strength segment used here is accepted by
 * the STRENGTH_TRAINING, WEIGHTLIFTING and CALISTHENICS session types; a cardio segment is accepted
 * only by its own session type ([CatalogExercise.hcSession]).
 */
object ExerciseCatalog {
    val all: List<CatalogExercise> = listOf(
        // Selectorized and plate-loaded machines.
        CatalogExercise(
            "machine_chest_press", R.string.ex_machine_chest_press, WEIGHT_REPS, MACHINE,
            primary = setOf(CHEST), secondary = setOf(TRICEPS, SHOULDERS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_BENCH_PRESS,
        ),
        CatalogExercise(
            "machine_incline_chest_press", R.string.ex_machine_incline_chest_press, WEIGHT_REPS, MACHINE,
            primary = setOf(CHEST), secondary = setOf(SHOULDERS, TRICEPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_BENCH_PRESS,
        ),
        CatalogExercise(
            "pec_deck", R.string.ex_pec_deck, WEIGHT_REPS, MACHINE,
            primary = setOf(CHEST), secondary = setOf(SHOULDERS),
        ),
        CatalogExercise(
            "machine_shoulder_press", R.string.ex_machine_shoulder_press, WEIGHT_REPS, MACHINE,
            primary = setOf(SHOULDERS), secondary = setOf(TRICEPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_SHOULDER_PRESS,
        ),
        CatalogExercise(
            "machine_lateral_raise", R.string.ex_machine_lateral_raise, WEIGHT_REPS, MACHINE,
            primary = setOf(SHOULDERS), secondary = setOf(TRAPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_LATERAL_RAISE,
        ),
        CatalogExercise(
            "reverse_pec_deck", R.string.ex_reverse_pec_deck, WEIGHT_REPS, MACHINE,
            primary = setOf(SHOULDERS), secondary = setOf(TRAPS),
        ),
        CatalogExercise(
            "machine_row", R.string.ex_machine_row, WEIGHT_REPS, MACHINE,
            primary = setOf(LATS, TRAPS), secondary = setOf(BICEPS, SHOULDERS),
        ),
        CatalogExercise(
            "pullover_machine", R.string.ex_pullover_machine, WEIGHT_REPS, MACHINE,
            primary = setOf(LATS), secondary = setOf(CHEST, TRICEPS),
        ),
        CatalogExercise(
            "leg_press_machine", R.string.ex_leg_press_machine, WEIGHT_REPS, MACHINE,
            primary = setOf(QUADS), secondary = setOf(GLUTES, ADDUCTORS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_PRESS,
        ),
        CatalogExercise(
            "horizontal_leg_press", R.string.ex_horizontal_leg_press, WEIGHT_REPS, MACHINE,
            primary = setOf(QUADS), secondary = setOf(GLUTES, ADDUCTORS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_PRESS,
        ),
        CatalogExercise(
            "hack_squat_machine", R.string.ex_hack_squat_machine, WEIGHT_REPS, MACHINE,
            primary = setOf(QUADS), secondary = setOf(GLUTES, ADDUCTORS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT,
        ),
        CatalogExercise(
            "pendulum_squat_machine", R.string.ex_pendulum_squat_machine, WEIGHT_REPS, MACHINE,
            primary = setOf(QUADS), secondary = setOf(GLUTES, ADDUCTORS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT,
        ),
        CatalogExercise(
            "belt_squat_machine", R.string.ex_belt_squat_machine, WEIGHT_REPS, MACHINE,
            primary = setOf(QUADS, GLUTES), secondary = setOf(ADDUCTORS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT,
        ),
        CatalogExercise(
            "leg_extension_machine", R.string.ex_leg_extension_machine, WEIGHT_REPS, MACHINE,
            primary = setOf(QUADS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_EXTENSION,
        ),
        CatalogExercise(
            "lying_leg_curl_machine", R.string.ex_lying_leg_curl_machine, WEIGHT_REPS, MACHINE,
            primary = setOf(HAMSTRINGS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_CURL,
        ),
        CatalogExercise(
            "seated_leg_curl_machine", R.string.ex_seated_leg_curl_machine, WEIGHT_REPS, MACHINE,
            primary = setOf(HAMSTRINGS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_CURL,
        ),
        CatalogExercise(
            "standing_calf_raise_machine", R.string.ex_standing_calf_raise_machine, WEIGHT_REPS, MACHINE,
            primary = setOf(CALVES),
        ),
        CatalogExercise(
            "seated_calf_raise_machine", R.string.ex_seated_calf_raise_machine, WEIGHT_REPS, MACHINE,
            primary = setOf(CALVES),
        ),
        CatalogExercise(
            "leg_press_calf_raise", R.string.ex_leg_press_calf_raise, WEIGHT_REPS, MACHINE,
            primary = setOf(CALVES),
        ),
        CatalogExercise(
            "donkey_calf_raise", R.string.ex_donkey_calf_raise, WEIGHT_REPS, MACHINE,
            primary = setOf(CALVES),
        ),
        CatalogExercise(
            "hip_abductor_machine", R.string.ex_hip_abductor_machine, WEIGHT_REPS, MACHINE,
            primary = setOf(GLUTES),
        ),
        CatalogExercise(
            "hip_adductor_machine", R.string.ex_hip_adductor_machine, WEIGHT_REPS, MACHINE,
            primary = setOf(ADDUCTORS),
        ),
        CatalogExercise(
            "glute_kickback_machine", R.string.ex_glute_kickback_machine, WEIGHT_REPS, MACHINE,
            primary = setOf(GLUTES), secondary = setOf(HAMSTRINGS),
        ),
        CatalogExercise(
            "hip_thrust_machine", R.string.ex_hip_thrust_machine, WEIGHT_REPS, MACHINE,
            primary = setOf(GLUTES), secondary = setOf(HAMSTRINGS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_HIP_THRUST,
        ),
        CatalogExercise(
            "back_extension_machine", R.string.ex_back_extension_machine, WEIGHT_REPS, MACHINE,
            primary = setOf(LOWER_BACK), secondary = setOf(GLUTES),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_BACK_EXTENSION,
        ),
        CatalogExercise(
            "reverse_hyperextension", R.string.ex_reverse_hyperextension, WEIGHT_REPS, MACHINE,
            primary = setOf(GLUTES, LOWER_BACK), secondary = setOf(HAMSTRINGS),
        ),
        CatalogExercise(
            "ab_crunch_machine", R.string.ex_ab_crunch_machine, WEIGHT_REPS, MACHINE,
            primary = setOf(ABS), secondary = setOf(OBLIQUES),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_CRUNCH,
        ),
        CatalogExercise(
            "rotary_torso_machine", R.string.ex_rotary_torso_machine, WEIGHT_REPS, MACHINE,
            primary = setOf(OBLIQUES), secondary = setOf(ABS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_UPPER_TWIST,
        ),
        CatalogExercise(
            "machine_biceps_curl", R.string.ex_machine_biceps_curl, WEIGHT_REPS, MACHINE,
            primary = setOf(BICEPS), secondary = setOf(FOREARMS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_ARM_CURL,
        ),
        CatalogExercise(
            "machine_triceps_extension", R.string.ex_machine_triceps_extension, WEIGHT_REPS, MACHINE,
            primary = setOf(TRICEPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_DOUBLE_ARM_TRICEPS_EXTENSION,
        ),
        CatalogExercise(
            "seated_dip_machine", R.string.ex_seated_dip_machine, WEIGHT_REPS, MACHINE,
            primary = setOf(TRICEPS), secondary = setOf(CHEST, SHOULDERS),
        ),
        // Assisted machines: the logged weight is the ASSISTANCE (counterweight). The load model turns it into
        // body weight minus assistance, with the same body-weight share as the free exercise (pull-up / dip
        // 1.0), so moving from the machine to the bar continues the same strength curve.
        CatalogExercise(
            "assisted_pull_up_machine", R.string.ex_assisted_pull_up_machine, ASSISTED_REPS, MACHINE,
            primary = setOf(LATS), secondary = setOf(BICEPS, FOREARMS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_PULL_UP,
            bodyweightFactor = 1.0,
        ),
        CatalogExercise(
            "assisted_chin_up_machine", R.string.ex_assisted_chin_up_machine, ASSISTED_REPS, MACHINE,
            primary = setOf(LATS, BICEPS), secondary = setOf(FOREARMS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_PULL_UP,
            bodyweightFactor = 1.0,
        ),
        CatalogExercise(
            "assisted_dip_machine", R.string.ex_assisted_dip_machine, ASSISTED_REPS, MACHINE,
            primary = setOf(CHEST, TRICEPS), secondary = setOf(SHOULDERS),
            bodyweightFactor = 1.0,
        ),
        CatalogExercise(
            "neck_machine", R.string.ex_neck_machine, WEIGHT_REPS, MACHINE,
            primary = setOf(NECK), secondary = setOf(TRAPS),
        ),
        // Plate-loaded and other machines added in 1.6.
        CatalogExercise(
            "iso_lateral_chest_press", R.string.ex_iso_lateral_chest_press, WEIGHT_REPS, MACHINE,
            primary = setOf(CHEST), secondary = setOf(TRICEPS, SHOULDERS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_BENCH_PRESS,
        ),
        CatalogExercise(
            "high_row_machine", R.string.ex_high_row_machine, WEIGHT_REPS, MACHINE,
            primary = setOf(LATS), secondary = setOf(TRAPS, BICEPS, SHOULDERS),
        ),
        CatalogExercise(
            "t_bar_row_machine", R.string.ex_t_bar_row_machine, WEIGHT_REPS, MACHINE,
            primary = setOf(LATS, TRAPS), secondary = setOf(BICEPS, SHOULDERS),
        ),
        CatalogExercise(
            "standing_leg_curl_machine", R.string.ex_standing_leg_curl_machine, WEIGHT_REPS, MACHINE,
            primary = setOf(HAMSTRINGS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_CURL,
        ),

        // Cable stations
        CatalogExercise(
            "lat_pulldown_machine", R.string.ex_lat_pulldown_machine, WEIGHT_REPS, CABLE,
            primary = setOf(LATS), secondary = setOf(BICEPS, FOREARMS, TRAPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_LAT_PULL_DOWN,
        ),
        CatalogExercise(
            "close_grip_lat_pulldown", R.string.ex_close_grip_lat_pulldown, WEIGHT_REPS, CABLE,
            primary = setOf(LATS), secondary = setOf(BICEPS, FOREARMS, TRAPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_LAT_PULL_DOWN,
        ),
        CatalogExercise(
            "seated_cable_row", R.string.ex_seated_cable_row, WEIGHT_REPS, CABLE,
            primary = setOf(LATS, TRAPS), secondary = setOf(BICEPS, SHOULDERS),
        ),
        CatalogExercise(
            "single_arm_cable_row", R.string.ex_single_arm_cable_row, WEIGHT_REPS, CABLE,
            primary = setOf(LATS, TRAPS), secondary = setOf(BICEPS, SHOULDERS),
        ),
        CatalogExercise(
            "straight_arm_pulldown", R.string.ex_straight_arm_pulldown, WEIGHT_REPS, CABLE,
            primary = setOf(LATS), secondary = setOf(TRICEPS),
        ),
        CatalogExercise(
            "cable_crossover", R.string.ex_cable_crossover, WEIGHT_REPS, CABLE,
            primary = setOf(CHEST), secondary = setOf(SHOULDERS),
        ),
        CatalogExercise(
            "low_cable_crossover", R.string.ex_low_cable_crossover, WEIGHT_REPS, CABLE,
            primary = setOf(CHEST), secondary = setOf(SHOULDERS),
        ),
        CatalogExercise(
            "cable_fly", R.string.ex_cable_fly, WEIGHT_REPS, CABLE,
            primary = setOf(CHEST), secondary = setOf(SHOULDERS),
        ),
        CatalogExercise(
            "face_pull", R.string.ex_face_pull, WEIGHT_REPS, CABLE,
            primary = setOf(SHOULDERS, TRAPS),
        ),
        CatalogExercise(
            "cable_lateral_raise", R.string.ex_cable_lateral_raise, WEIGHT_REPS, CABLE,
            primary = setOf(SHOULDERS), secondary = setOf(TRAPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_LATERAL_RAISE,
        ),
        CatalogExercise(
            "cable_triceps_pushdown", R.string.ex_cable_triceps_pushdown, WEIGHT_REPS, CABLE,
            primary = setOf(TRICEPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_DOUBLE_ARM_TRICEPS_EXTENSION,
        ),
        CatalogExercise(
            "rope_triceps_pushdown", R.string.ex_rope_triceps_pushdown, WEIGHT_REPS, CABLE,
            primary = setOf(TRICEPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_DOUBLE_ARM_TRICEPS_EXTENSION,
        ),
        CatalogExercise(
            "cable_overhead_triceps_extension", R.string.ex_cable_overhead_triceps_extension, WEIGHT_REPS, CABLE,
            primary = setOf(TRICEPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_DOUBLE_ARM_TRICEPS_EXTENSION,
        ),
        CatalogExercise(
            "cable_curl", R.string.ex_cable_curl, WEIGHT_REPS, CABLE,
            primary = setOf(BICEPS), secondary = setOf(FOREARMS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_ARM_CURL,
        ),
        CatalogExercise(
            "cable_rope_hammer_curl", R.string.ex_cable_rope_hammer_curl, WEIGHT_REPS, CABLE,
            primary = setOf(BICEPS, FOREARMS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_ARM_CURL,
        ),
        CatalogExercise(
            "cable_crunch", R.string.ex_cable_crunch, WEIGHT_REPS, CABLE,
            primary = setOf(ABS), secondary = setOf(OBLIQUES),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_CRUNCH,
        ),
        CatalogExercise(
            "cable_woodchop", R.string.ex_cable_woodchop, WEIGHT_REPS, CABLE,
            primary = setOf(OBLIQUES), secondary = setOf(ABS, SHOULDERS),
        ),
        CatalogExercise(
            "cable_reverse_woodchop", R.string.ex_cable_reverse_woodchop, WEIGHT_REPS, CABLE,
            primary = setOf(OBLIQUES), secondary = setOf(ABS, SHOULDERS),
        ),
        CatalogExercise(
            "pallof_press", R.string.ex_pallof_press, WEIGHT_REPS, CABLE,
            primary = setOf(OBLIQUES), secondary = setOf(ABS),
        ),
        CatalogExercise(
            "cable_pull_through", R.string.ex_cable_pull_through, WEIGHT_REPS, CABLE,
            primary = setOf(GLUTES, HAMSTRINGS), secondary = setOf(LOWER_BACK),
        ),
        CatalogExercise(
            "cable_glute_kickback", R.string.ex_cable_glute_kickback, WEIGHT_REPS, CABLE,
            primary = setOf(GLUTES), secondary = setOf(HAMSTRINGS),
        ),
        CatalogExercise(
            "cable_hip_adduction", R.string.ex_cable_hip_adduction, WEIGHT_REPS, CABLE,
            primary = setOf(ADDUCTORS),
        ),
        CatalogExercise(
            "cable_hip_abduction", R.string.ex_cable_hip_abduction, WEIGHT_REPS, CABLE,
            primary = setOf(GLUTES),
        ),
        CatalogExercise(
            "reverse_grip_lat_pulldown", R.string.ex_reverse_grip_lat_pulldown, WEIGHT_REPS, CABLE,
            primary = setOf(LATS, BICEPS), secondary = setOf(FOREARMS, TRAPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_LAT_PULL_DOWN,
        ),
        CatalogExercise(
            "cable_chest_press", R.string.ex_cable_chest_press, WEIGHT_REPS, CABLE,
            primary = setOf(CHEST), secondary = setOf(TRICEPS, SHOULDERS),
        ),
        CatalogExercise(
            "cable_reverse_fly", R.string.ex_cable_reverse_fly, WEIGHT_REPS, CABLE,
            primary = setOf(SHOULDERS), secondary = setOf(TRAPS),
        ),
        CatalogExercise(
            "cable_y_raise", R.string.ex_cable_y_raise, WEIGHT_REPS, CABLE,
            primary = setOf(SHOULDERS), secondary = setOf(TRAPS),
        ),
        CatalogExercise(
            "cable_upright_row", R.string.ex_cable_upright_row, WEIGHT_REPS, CABLE,
            primary = setOf(SHOULDERS, TRAPS), secondary = setOf(BICEPS),
        ),

        // Smith machine
        CatalogExercise(
            "smith_squat", R.string.ex_smith_squat, WEIGHT_REPS, SMITH_MACHINE,
            primary = setOf(QUADS, GLUTES), secondary = setOf(ADDUCTORS, HAMSTRINGS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT,
        ),
        CatalogExercise(
            "smith_bench_press", R.string.ex_smith_bench_press, WEIGHT_REPS, SMITH_MACHINE,
            primary = setOf(CHEST), secondary = setOf(TRICEPS, SHOULDERS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_BENCH_PRESS,
        ),
        CatalogExercise(
            "smith_incline_bench_press", R.string.ex_smith_incline_bench_press, WEIGHT_REPS, SMITH_MACHINE,
            primary = setOf(CHEST), secondary = setOf(SHOULDERS, TRICEPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_BENCH_PRESS,
        ),
        CatalogExercise(
            "smith_shoulder_press", R.string.ex_smith_shoulder_press, WEIGHT_REPS, SMITH_MACHINE,
            primary = setOf(SHOULDERS), secondary = setOf(TRICEPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_SHOULDER_PRESS,
        ),
        CatalogExercise(
            "smith_split_squat", R.string.ex_smith_split_squat, WEIGHT_REPS, SMITH_MACHINE,
            primary = setOf(QUADS, GLUTES), secondary = setOf(ADDUCTORS, HAMSTRINGS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_LUNGE,
        ),
        CatalogExercise(
            "smith_bulgarian_split_squat", R.string.ex_smith_bulgarian_split_squat, WEIGHT_REPS, SMITH_MACHINE,
            primary = setOf(QUADS, GLUTES), secondary = setOf(ADDUCTORS, HAMSTRINGS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_LUNGE,
        ),
        CatalogExercise(
            "smith_hip_thrust", R.string.ex_smith_hip_thrust, WEIGHT_REPS, SMITH_MACHINE,
            primary = setOf(GLUTES), secondary = setOf(HAMSTRINGS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_HIP_THRUST,
        ),
        CatalogExercise(
            "smith_romanian_deadlift", R.string.ex_smith_romanian_deadlift, WEIGHT_REPS, SMITH_MACHINE,
            primary = setOf(HAMSTRINGS, GLUTES), secondary = setOf(LOWER_BACK, ADDUCTORS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_DEADLIFT,
        ),
        CatalogExercise(
            "smith_bent_over_row", R.string.ex_smith_bent_over_row, WEIGHT_REPS, SMITH_MACHINE,
            primary = setOf(LATS, TRAPS), secondary = setOf(BICEPS, SHOULDERS, LOWER_BACK),
        ),
        CatalogExercise(
            "smith_shrug", R.string.ex_smith_shrug, WEIGHT_REPS, SMITH_MACHINE,
            primary = setOf(TRAPS), secondary = setOf(FOREARMS),
        ),
        CatalogExercise(
            "smith_calf_raise", R.string.ex_smith_calf_raise, WEIGHT_REPS, SMITH_MACHINE,
            primary = setOf(CALVES),
        ),
        CatalogExercise(
            "smith_reverse_lunge", R.string.ex_smith_reverse_lunge, WEIGHT_REPS, SMITH_MACHINE,
            primary = setOf(QUADS, GLUTES), secondary = setOf(ADDUCTORS, HAMSTRINGS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_LUNGE,
        ),

        // Barbell and EZ bar
        CatalogExercise(
            "barbell_bench_press", R.string.ex_barbell_bench_press, WEIGHT_REPS, BARBELL,
            primary = setOf(CHEST), secondary = setOf(TRICEPS, SHOULDERS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_BENCH_PRESS,
        ),
        CatalogExercise(
            "barbell_incline_bench_press", R.string.ex_barbell_incline_bench_press, WEIGHT_REPS, BARBELL,
            primary = setOf(CHEST), secondary = setOf(SHOULDERS, TRICEPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_BENCH_PRESS,
        ),
        CatalogExercise(
            "barbell_decline_bench_press", R.string.ex_barbell_decline_bench_press, WEIGHT_REPS, BARBELL,
            primary = setOf(CHEST), secondary = setOf(TRICEPS, SHOULDERS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_BENCH_PRESS,
        ),
        CatalogExercise(
            "close_grip_bench_press", R.string.ex_close_grip_bench_press, WEIGHT_REPS, BARBELL,
            primary = setOf(TRICEPS, CHEST), secondary = setOf(SHOULDERS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_BENCH_PRESS,
        ),
        CatalogExercise(
            "jm_press", R.string.ex_jm_press, WEIGHT_REPS, BARBELL,
            primary = setOf(TRICEPS), secondary = setOf(CHEST, SHOULDERS),
        ),
        CatalogExercise(
            "barbell_overhead_press", R.string.ex_barbell_overhead_press, WEIGHT_REPS, BARBELL,
            primary = setOf(SHOULDERS), secondary = setOf(TRICEPS, TRAPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_BARBELL_SHOULDER_PRESS,
        ),
        CatalogExercise(
            "landmine_press", R.string.ex_landmine_press, WEIGHT_REPS, BARBELL,
            primary = setOf(SHOULDERS), secondary = setOf(TRICEPS, CHEST),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_SHOULDER_PRESS,
        ),
        CatalogExercise(
            "barbell_back_squat", R.string.ex_barbell_back_squat, WEIGHT_REPS, BARBELL,
            primary = setOf(QUADS, GLUTES), secondary = setOf(ADDUCTORS, HAMSTRINGS, LOWER_BACK),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT,
        ),
        CatalogExercise(
            "barbell_front_squat", R.string.ex_barbell_front_squat, WEIGHT_REPS, BARBELL,
            primary = setOf(QUADS), secondary = setOf(GLUTES, ADDUCTORS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT,
        ),
        CatalogExercise(
            "zercher_squat", R.string.ex_zercher_squat, WEIGHT_REPS, BARBELL,
            primary = setOf(QUADS, GLUTES), secondary = setOf(ADDUCTORS, LOWER_BACK),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT,
        ),
        CatalogExercise(
            "barbell_deadlift", R.string.ex_barbell_deadlift, WEIGHT_REPS, BARBELL,
            primary = setOf(GLUTES, HAMSTRINGS, LOWER_BACK), secondary = setOf(QUADS, TRAPS, FOREARMS, ADDUCTORS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_DEADLIFT,
        ),
        CatalogExercise(
            "sumo_deadlift", R.string.ex_sumo_deadlift, WEIGHT_REPS, BARBELL,
            primary = setOf(GLUTES, QUADS, ADDUCTORS), secondary = setOf(HAMSTRINGS, LOWER_BACK, TRAPS, FOREARMS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_DEADLIFT,
        ),
        CatalogExercise(
            "rack_pull", R.string.ex_rack_pull, WEIGHT_REPS, BARBELL,
            primary = setOf(LOWER_BACK, GLUTES, TRAPS), secondary = setOf(HAMSTRINGS, FOREARMS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_DEADLIFT,
        ),
        CatalogExercise(
            "barbell_romanian_deadlift", R.string.ex_barbell_romanian_deadlift, WEIGHT_REPS, BARBELL,
            primary = setOf(HAMSTRINGS, GLUTES), secondary = setOf(LOWER_BACK, ADDUCTORS, FOREARMS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_DEADLIFT,
        ),
        CatalogExercise(
            "good_morning", R.string.ex_good_morning, WEIGHT_REPS, BARBELL,
            primary = setOf(HAMSTRINGS, LOWER_BACK), secondary = setOf(GLUTES),
        ),
        CatalogExercise(
            "seated_good_morning", R.string.ex_seated_good_morning, WEIGHT_REPS, BARBELL,
            primary = setOf(LOWER_BACK), secondary = setOf(GLUTES, HAMSTRINGS),
        ),
        CatalogExercise(
            "barbell_lunge", R.string.ex_barbell_lunge, WEIGHT_REPS, BARBELL,
            primary = setOf(QUADS, GLUTES), secondary = setOf(ADDUCTORS, HAMSTRINGS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_LUNGE,
        ),
        CatalogExercise(
            "barbell_hip_thrust", R.string.ex_barbell_hip_thrust, WEIGHT_REPS, BARBELL,
            primary = setOf(GLUTES), secondary = setOf(HAMSTRINGS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_HIP_THRUST,
        ),
        CatalogExercise(
            "barbell_glute_bridge", R.string.ex_barbell_glute_bridge, WEIGHT_REPS, BARBELL,
            primary = setOf(GLUTES), secondary = setOf(HAMSTRINGS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_HIP_THRUST,
        ),
        CatalogExercise(
            "barbell_row", R.string.ex_barbell_row, WEIGHT_REPS, BARBELL,
            primary = setOf(LATS, TRAPS), secondary = setOf(BICEPS, SHOULDERS, LOWER_BACK),
        ),
        CatalogExercise(
            "pendlay_row", R.string.ex_pendlay_row, WEIGHT_REPS, BARBELL,
            primary = setOf(LATS, TRAPS), secondary = setOf(BICEPS, SHOULDERS, LOWER_BACK),
        ),
        CatalogExercise(
            "t_bar_row", R.string.ex_t_bar_row, WEIGHT_REPS, BARBELL,
            primary = setOf(LATS, TRAPS), secondary = setOf(BICEPS, SHOULDERS, LOWER_BACK),
        ),
        CatalogExercise(
            "barbell_upright_row", R.string.ex_barbell_upright_row, WEIGHT_REPS, BARBELL,
            primary = setOf(SHOULDERS, TRAPS), secondary = setOf(BICEPS),
        ),
        CatalogExercise(
            "barbell_shrug", R.string.ex_barbell_shrug, WEIGHT_REPS, BARBELL,
            primary = setOf(TRAPS), secondary = setOf(FOREARMS),
        ),
        CatalogExercise(
            "barbell_curl", R.string.ex_barbell_curl, WEIGHT_REPS, BARBELL,
            primary = setOf(BICEPS), secondary = setOf(FOREARMS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_ARM_CURL,
        ),
        CatalogExercise(
            "ez_bar_curl", R.string.ex_ez_bar_curl, WEIGHT_REPS, BARBELL,
            primary = setOf(BICEPS), secondary = setOf(FOREARMS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_ARM_CURL,
        ),
        CatalogExercise(
            "ez_bar_preacher_curl", R.string.ex_ez_bar_preacher_curl, WEIGHT_REPS, BARBELL,
            primary = setOf(BICEPS), secondary = setOf(FOREARMS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_ARM_CURL,
        ),
        CatalogExercise(
            "barbell_reverse_curl", R.string.ex_barbell_reverse_curl, WEIGHT_REPS, BARBELL,
            primary = setOf(FOREARMS, BICEPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_ARM_CURL,
        ),
        CatalogExercise(
            "skull_crusher", R.string.ex_skull_crusher, WEIGHT_REPS, BARBELL,
            primary = setOf(TRICEPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_DOUBLE_ARM_TRICEPS_EXTENSION,
        ),
        CatalogExercise(
            "barbell_wrist_curl", R.string.ex_barbell_wrist_curl, WEIGHT_REPS, BARBELL,
            primary = setOf(FOREARMS),
        ),
        CatalogExercise(
            "barbell_reverse_wrist_curl", R.string.ex_barbell_reverse_wrist_curl, WEIGHT_REPS, BARBELL,
            primary = setOf(FOREARMS),
        ),
        CatalogExercise(
            "landmine_rotation", R.string.ex_landmine_rotation, WEIGHT_REPS, BARBELL,
            primary = setOf(OBLIQUES), secondary = setOf(ABS, SHOULDERS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_UPPER_TWIST,
        ),
        CatalogExercise(
            "landmine_squat", R.string.ex_landmine_squat, WEIGHT_REPS, BARBELL,
            primary = setOf(QUADS, GLUTES), secondary = setOf(ADDUCTORS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT,
        ),
        // Functional and Olympic-style lifts (CrossFit boxes, functional gyms). The whole-body lifts
        // have no Health Connect segment of their own: they export as weightlifting.
        CatalogExercise(
            "overhead_squat", R.string.ex_overhead_squat, WEIGHT_REPS, BARBELL,
            primary = setOf(QUADS, GLUTES), secondary = setOf(SHOULDERS, TRAPS, ADDUCTORS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT,
        ),
        CatalogExercise(
            "thruster", R.string.ex_thruster, WEIGHT_REPS, BARBELL,
            primary = setOf(QUADS, SHOULDERS), secondary = setOf(GLUTES, TRICEPS),
        ),
        CatalogExercise(
            "push_press", R.string.ex_push_press, WEIGHT_REPS, BARBELL,
            primary = setOf(SHOULDERS), secondary = setOf(TRICEPS, QUADS, GLUTES),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_BARBELL_SHOULDER_PRESS,
        ),
        CatalogExercise(
            "power_clean", R.string.ex_power_clean, WEIGHT_REPS, BARBELL,
            primary = setOf(GLUTES, HAMSTRINGS, TRAPS), secondary = setOf(QUADS, LOWER_BACK, FOREARMS),
        ),
        CatalogExercise(
            "hang_clean", R.string.ex_hang_clean, WEIGHT_REPS, BARBELL,
            primary = setOf(GLUTES, HAMSTRINGS, TRAPS), secondary = setOf(QUADS, LOWER_BACK, FOREARMS),
        ),
        CatalogExercise(
            "power_snatch", R.string.ex_power_snatch, WEIGHT_REPS, BARBELL,
            primary = setOf(GLUTES, HAMSTRINGS, SHOULDERS), secondary = setOf(TRAPS, QUADS, LOWER_BACK, FOREARMS),
        ),

        // Dumbbells
        CatalogExercise(
            "dumbbell_bench_press", R.string.ex_dumbbell_bench_press, WEIGHT_REPS, DUMBBELL,
            primary = setOf(CHEST), secondary = setOf(TRICEPS, SHOULDERS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_BENCH_PRESS,
        ),
        CatalogExercise(
            "dumbbell_incline_bench_press", R.string.ex_dumbbell_incline_bench_press, WEIGHT_REPS, DUMBBELL,
            primary = setOf(CHEST), secondary = setOf(SHOULDERS, TRICEPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_BENCH_PRESS,
        ),
        CatalogExercise(
            "dumbbell_fly", R.string.ex_dumbbell_fly, WEIGHT_REPS, DUMBBELL,
            primary = setOf(CHEST), secondary = setOf(SHOULDERS),
        ),
        CatalogExercise(
            "dumbbell_pullover", R.string.ex_dumbbell_pullover, WEIGHT_REPS, DUMBBELL,
            primary = setOf(CHEST, LATS), secondary = setOf(TRICEPS),
        ),
        CatalogExercise(
            "dumbbell_shoulder_press", R.string.ex_dumbbell_shoulder_press, WEIGHT_REPS, DUMBBELL,
            primary = setOf(SHOULDERS), secondary = setOf(TRICEPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_SHOULDER_PRESS,
        ),
        CatalogExercise(
            "arnold_press", R.string.ex_arnold_press, WEIGHT_REPS, DUMBBELL,
            primary = setOf(SHOULDERS), secondary = setOf(TRICEPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_SHOULDER_PRESS,
        ),
        CatalogExercise(
            "dumbbell_lateral_raise", R.string.ex_dumbbell_lateral_raise, WEIGHT_REPS, DUMBBELL,
            primary = setOf(SHOULDERS), secondary = setOf(TRAPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_DUMBBELL_LATERAL_RAISE,
        ),
        CatalogExercise(
            "dumbbell_front_raise", R.string.ex_dumbbell_front_raise, WEIGHT_REPS, DUMBBELL,
            primary = setOf(SHOULDERS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_DUMBBELL_FRONT_RAISE,
        ),
        CatalogExercise(
            "dumbbell_rear_delt_fly", R.string.ex_dumbbell_rear_delt_fly, WEIGHT_REPS, DUMBBELL,
            primary = setOf(SHOULDERS), secondary = setOf(TRAPS),
        ),
        CatalogExercise(
            "dumbbell_row", R.string.ex_dumbbell_row, WEIGHT_REPS, DUMBBELL,
            primary = setOf(LATS, TRAPS), secondary = setOf(BICEPS, SHOULDERS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_DUMBBELL_ROW,
        ),
        CatalogExercise(
            "chest_supported_dumbbell_row", R.string.ex_chest_supported_dumbbell_row, WEIGHT_REPS, DUMBBELL,
            primary = setOf(LATS, TRAPS), secondary = setOf(BICEPS, SHOULDERS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_DUMBBELL_ROW,
        ),
        CatalogExercise(
            "dumbbell_shrug", R.string.ex_dumbbell_shrug, WEIGHT_REPS, DUMBBELL,
            primary = setOf(TRAPS), secondary = setOf(FOREARMS),
        ),
        CatalogExercise(
            "dumbbell_curl", R.string.ex_dumbbell_curl, WEIGHT_REPS, DUMBBELL,
            primary = setOf(BICEPS), secondary = setOf(FOREARMS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_ARM_CURL,
        ),
        CatalogExercise(
            "hammer_curl", R.string.ex_hammer_curl, WEIGHT_REPS, DUMBBELL,
            primary = setOf(BICEPS, FOREARMS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_ARM_CURL,
        ),
        CatalogExercise(
            "incline_dumbbell_curl", R.string.ex_incline_dumbbell_curl, WEIGHT_REPS, DUMBBELL,
            primary = setOf(BICEPS), secondary = setOf(FOREARMS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_ARM_CURL,
        ),
        CatalogExercise(
            "concentration_curl", R.string.ex_concentration_curl, WEIGHT_REPS, DUMBBELL,
            primary = setOf(BICEPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_ARM_CURL,
        ),
        CatalogExercise(
            "zottman_curl", R.string.ex_zottman_curl, WEIGHT_REPS, DUMBBELL,
            primary = setOf(BICEPS, FOREARMS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_ARM_CURL,
        ),
        CatalogExercise(
            "dumbbell_wrist_curl", R.string.ex_dumbbell_wrist_curl, WEIGHT_REPS, DUMBBELL,
            primary = setOf(FOREARMS),
        ),
        CatalogExercise(
            "dumbbell_reverse_wrist_curl", R.string.ex_dumbbell_reverse_wrist_curl, WEIGHT_REPS, DUMBBELL,
            primary = setOf(FOREARMS),
        ),
        CatalogExercise(
            "dumbbell_overhead_extension", R.string.ex_dumbbell_overhead_extension, WEIGHT_REPS, DUMBBELL,
            primary = setOf(TRICEPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_DUMBBELL_TRICEPS_EXTENSION_TWO_ARM,
        ),
        CatalogExercise(
            "dumbbell_triceps_kickback", R.string.ex_dumbbell_triceps_kickback, WEIGHT_REPS, DUMBBELL,
            primary = setOf(TRICEPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_SINGLE_ARM_TRICEPS_EXTENSION,
        ),
        CatalogExercise(
            "dumbbell_goblet_squat", R.string.ex_dumbbell_goblet_squat, WEIGHT_REPS, DUMBBELL,
            primary = setOf(QUADS, GLUTES), secondary = setOf(ADDUCTORS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT,
        ),
        CatalogExercise(
            "dumbbell_sumo_squat", R.string.ex_dumbbell_sumo_squat, WEIGHT_REPS, DUMBBELL,
            primary = setOf(QUADS, GLUTES, ADDUCTORS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT,
        ),
        CatalogExercise(
            "bulgarian_split_squat", R.string.ex_bulgarian_split_squat, WEIGHT_REPS, DUMBBELL,
            primary = setOf(QUADS, GLUTES), secondary = setOf(ADDUCTORS, HAMSTRINGS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_LUNGE,
        ),
        CatalogExercise(
            "dumbbell_lunge", R.string.ex_dumbbell_lunge, WEIGHT_REPS, DUMBBELL,
            primary = setOf(QUADS, GLUTES), secondary = setOf(ADDUCTORS, HAMSTRINGS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_LUNGE,
        ),
        CatalogExercise(
            "dumbbell_reverse_lunge", R.string.ex_dumbbell_reverse_lunge, WEIGHT_REPS, DUMBBELL,
            primary = setOf(QUADS, GLUTES), secondary = setOf(ADDUCTORS, HAMSTRINGS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_LUNGE,
        ),
        CatalogExercise(
            "dumbbell_lateral_lunge", R.string.ex_dumbbell_lateral_lunge, WEIGHT_REPS, DUMBBELL,
            primary = setOf(QUADS, GLUTES, ADDUCTORS), secondary = setOf(HAMSTRINGS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_LUNGE,
        ),
        CatalogExercise(
            "dumbbell_step_up", R.string.ex_dumbbell_step_up, WEIGHT_REPS, DUMBBELL,
            primary = setOf(QUADS, GLUTES), secondary = setOf(HAMSTRINGS),
        ),
        CatalogExercise(
            "dumbbell_romanian_deadlift", R.string.ex_dumbbell_romanian_deadlift, WEIGHT_REPS, DUMBBELL,
            primary = setOf(HAMSTRINGS, GLUTES), secondary = setOf(LOWER_BACK, ADDUCTORS, FOREARMS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_DEADLIFT,
        ),
        CatalogExercise(
            "single_leg_romanian_deadlift", R.string.ex_single_leg_romanian_deadlift, WEIGHT_REPS, DUMBBELL,
            primary = setOf(HAMSTRINGS, GLUTES), secondary = setOf(LOWER_BACK, ADDUCTORS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_DEADLIFT,
        ),
        CatalogExercise(
            "dumbbell_calf_raise", R.string.ex_dumbbell_calf_raise, WEIGHT_REPS, DUMBBELL,
            primary = setOf(CALVES),
        ),
        CatalogExercise(
            "seated_dumbbell_calf_raise", R.string.ex_seated_dumbbell_calf_raise, WEIGHT_REPS, DUMBBELL,
            primary = setOf(CALVES),
        ),
        CatalogExercise(
            "dumbbell_side_bend", R.string.ex_dumbbell_side_bend, WEIGHT_REPS, DUMBBELL,
            primary = setOf(OBLIQUES), secondary = setOf(LOWER_BACK),
        ),
        CatalogExercise(
            "dumbbell_floor_press", R.string.ex_dumbbell_floor_press, WEIGHT_REPS, DUMBBELL,
            primary = setOf(CHEST, TRICEPS), secondary = setOf(SHOULDERS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_BENCH_PRESS,
        ),
        CatalogExercise(
            "incline_dumbbell_fly", R.string.ex_incline_dumbbell_fly, WEIGHT_REPS, DUMBBELL,
            primary = setOf(CHEST), secondary = setOf(SHOULDERS),
        ),
        CatalogExercise(
            "walking_lunge", R.string.ex_walking_lunge, WEIGHT_REPS, DUMBBELL,
            primary = setOf(QUADS, GLUTES), secondary = setOf(ADDUCTORS, HAMSTRINGS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_LUNGE,
        ),
        CatalogExercise(
            "dumbbell_snatch", R.string.ex_dumbbell_snatch, WEIGHT_REPS, DUMBBELL,
            primary = setOf(GLUTES, HAMSTRINGS, SHOULDERS), secondary = setOf(LOWER_BACK, TRAPS, FOREARMS),
        ),

        // Kettlebells
        CatalogExercise(
            "kettlebell_swing", R.string.ex_kettlebell_swing, WEIGHT_REPS, KETTLEBELL,
            primary = setOf(GLUTES, HAMSTRINGS), secondary = setOf(LOWER_BACK, FOREARMS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_KETTLEBELL_SWING,
        ),
        CatalogExercise(
            "kettlebell_goblet_squat", R.string.ex_kettlebell_goblet_squat, WEIGHT_REPS, KETTLEBELL,
            primary = setOf(QUADS, GLUTES), secondary = setOf(ADDUCTORS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT,
        ),
        CatalogExercise(
            "kettlebell_clean_and_press", R.string.ex_kettlebell_clean_and_press, WEIGHT_REPS, KETTLEBELL,
            primary = setOf(SHOULDERS), secondary = setOf(TRICEPS, GLUTES, HAMSTRINGS, TRAPS),
        ),
        CatalogExercise(
            "turkish_get_up", R.string.ex_turkish_get_up, WEIGHT_REPS, KETTLEBELL,
            primary = setOf(SHOULDERS, ABS), secondary = setOf(OBLIQUES, GLUTES, TRICEPS),
        ),
        CatalogExercise(
            "kettlebell_deadlift", R.string.ex_kettlebell_deadlift, WEIGHT_REPS, KETTLEBELL,
            primary = setOf(GLUTES, HAMSTRINGS), secondary = setOf(QUADS, LOWER_BACK, FOREARMS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_DEADLIFT,
        ),
        CatalogExercise(
            "kettlebell_clean", R.string.ex_kettlebell_clean, WEIGHT_REPS, KETTLEBELL,
            primary = setOf(GLUTES, HAMSTRINGS), secondary = setOf(LOWER_BACK, TRAPS, FOREARMS),
        ),
        CatalogExercise(
            "kettlebell_press", R.string.ex_kettlebell_press, WEIGHT_REPS, KETTLEBELL,
            primary = setOf(SHOULDERS), secondary = setOf(TRICEPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_SHOULDER_PRESS,
        ),
        CatalogExercise(
            "kettlebell_snatch", R.string.ex_kettlebell_snatch, WEIGHT_REPS, KETTLEBELL,
            primary = setOf(GLUTES, HAMSTRINGS, SHOULDERS), secondary = setOf(LOWER_BACK, TRAPS, FOREARMS),
        ),
        CatalogExercise(
            "kettlebell_row", R.string.ex_kettlebell_row, WEIGHT_REPS, KETTLEBELL,
            primary = setOf(LATS, TRAPS), secondary = setOf(BICEPS, SHOULDERS),
        ),
        CatalogExercise(
            "kettlebell_windmill", R.string.ex_kettlebell_windmill, WEIGHT_REPS, KETTLEBELL,
            primary = setOf(OBLIQUES), secondary = setOf(SHOULDERS, GLUTES, HAMSTRINGS),
        ),
        CatalogExercise(
            "kettlebell_high_pull", R.string.ex_kettlebell_high_pull, WEIGHT_REPS, KETTLEBELL,
            primary = setOf(TRAPS, SHOULDERS), secondary = setOf(GLUTES, HAMSTRINGS, BICEPS),
        ),

        // Bodyweight: repetitions, optionally with extra load (vest, belt, dumbbell). bodyweightFactor is the share
        // of the body weight lifted where it is well established (pull-up, dip ~ all of it; push-up ~ 65 %); the
        // others (core, jumps, lunges, bench dips...) only count their extra load.
        CatalogExercise(
            "push_up", R.string.ex_push_up, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(CHEST), secondary = setOf(TRICEPS, SHOULDERS),
            bodyweightFactor = 0.65,
        ),
        // Shares of the body weight on the hands (Ebben et al. 2011): push-up 64 %, on the knees 49 %,
        // hands or feet on a bench (about 45 cm) about 48 % and 72 %; a pike push-up's share is not established.
        CatalogExercise(
            "knee_push_up", R.string.ex_knee_push_up, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(CHEST), secondary = setOf(TRICEPS, SHOULDERS),
            bodyweightFactor = 0.49,
        ),
        CatalogExercise(
            "incline_push_up", R.string.ex_incline_push_up, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(CHEST), secondary = setOf(TRICEPS, SHOULDERS),
            bodyweightFactor = 0.48,
        ),
        CatalogExercise(
            "decline_push_up", R.string.ex_decline_push_up, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(CHEST), secondary = setOf(SHOULDERS, TRICEPS),
            bodyweightFactor = 0.72,
        ),
        CatalogExercise(
            "diamond_push_up", R.string.ex_diamond_push_up, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(TRICEPS, CHEST), secondary = setOf(SHOULDERS),
            bodyweightFactor = 0.65,
        ),
        CatalogExercise(
            "pike_push_up", R.string.ex_pike_push_up, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(SHOULDERS), secondary = setOf(TRICEPS),
        ),
        CatalogExercise(
            "parallel_bar_dip", R.string.ex_parallel_bar_dip, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(CHEST, TRICEPS), secondary = setOf(SHOULDERS),
            bodyweightFactor = 1.0,
        ),
        CatalogExercise(
            "bench_dip", R.string.ex_bench_dip, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(TRICEPS), secondary = setOf(CHEST, SHOULDERS),
        ),
        CatalogExercise(
            "pull_up", R.string.ex_pull_up, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(LATS), secondary = setOf(BICEPS, FOREARMS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_PULL_UP,
            bodyweightFactor = 1.0,
        ),
        CatalogExercise(
            "chin_up", R.string.ex_chin_up, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(LATS, BICEPS), secondary = setOf(FOREARMS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_PULL_UP,
            bodyweightFactor = 1.0,
        ),
        CatalogExercise(
            "inverted_row", R.string.ex_inverted_row, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(LATS, TRAPS), secondary = setOf(BICEPS, SHOULDERS),
        ),
        CatalogExercise(
            "bodyweight_squat", R.string.ex_bodyweight_squat, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(QUADS, GLUTES), secondary = setOf(ADDUCTORS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT,
        ),
        CatalogExercise(
            "bodyweight_lunge", R.string.ex_bodyweight_lunge, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(QUADS, GLUTES), secondary = setOf(ADDUCTORS, HAMSTRINGS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_LUNGE,
        ),
        CatalogExercise(
            "pistol_squat", R.string.ex_pistol_squat, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(QUADS, GLUTES), secondary = setOf(ADDUCTORS, HAMSTRINGS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT,
        ),
        CatalogExercise(
            "jump_squat", R.string.ex_jump_squat, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(QUADS, GLUTES), secondary = setOf(CALVES),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT,
        ),
        CatalogExercise(
            "cossack_squat", R.string.ex_cossack_squat, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(ADDUCTORS, QUADS, GLUTES), secondary = setOf(HAMSTRINGS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT,
        ),
        // The calves lift the whole body but the feet.
        CatalogExercise(
            "single_leg_calf_raise", R.string.ex_single_leg_calf_raise, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(CALVES),
            bodyweightFactor = 1.0,
        ),
        CatalogExercise(
            "glute_bridge", R.string.ex_glute_bridge, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(GLUTES), secondary = setOf(HAMSTRINGS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_HIP_THRUST,
        ),
        CatalogExercise(
            "single_leg_glute_bridge", R.string.ex_single_leg_glute_bridge, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(GLUTES), secondary = setOf(HAMSTRINGS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_HIP_THRUST,
        ),
        CatalogExercise(
            "donkey_kick", R.string.ex_donkey_kick, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(GLUTES), secondary = setOf(HAMSTRINGS),
        ),
        CatalogExercise(
            "back_extension", R.string.ex_back_extension, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(LOWER_BACK), secondary = setOf(GLUTES, HAMSTRINGS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_BACK_EXTENSION,
        ),
        CatalogExercise(
            "superman", R.string.ex_superman, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(LOWER_BACK), secondary = setOf(GLUTES, HAMSTRINGS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_BACK_EXTENSION,
        ),
        CatalogExercise(
            "bird_dog", R.string.ex_bird_dog, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(LOWER_BACK), secondary = setOf(GLUTES, ABS),
        ),
        CatalogExercise(
            "nordic_curl", R.string.ex_nordic_curl, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(HAMSTRINGS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_CURL,
        ),
        CatalogExercise(
            "crunch", R.string.ex_crunch, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(ABS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_CRUNCH,
        ),
        CatalogExercise(
            "sit_up", R.string.ex_sit_up, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(ABS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_SIT_UP,
        ),
        CatalogExercise(
            "hanging_leg_raise", R.string.ex_hanging_leg_raise, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(ABS), secondary = setOf(OBLIQUES, FOREARMS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_RAISE,
        ),
        CatalogExercise(
            "hanging_knee_raise", R.string.ex_hanging_knee_raise, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(ABS), secondary = setOf(OBLIQUES, FOREARMS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_RAISE,
        ),
        CatalogExercise(
            "lying_leg_raise", R.string.ex_lying_leg_raise, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(ABS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_RAISE,
        ),
        CatalogExercise(
            "reverse_crunch", R.string.ex_reverse_crunch, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(ABS), secondary = setOf(OBLIQUES),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_CRUNCH,
        ),
        CatalogExercise(
            "bicycle_crunch", R.string.ex_bicycle_crunch, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(OBLIQUES, ABS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_CRUNCH,
        ),
        CatalogExercise(
            "dead_bug", R.string.ex_dead_bug, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(ABS), secondary = setOf(OBLIQUES),
        ),
        CatalogExercise(
            "dragon_flag", R.string.ex_dragon_flag, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(ABS), secondary = setOf(OBLIQUES),
        ),
        CatalogExercise(
            "russian_twist", R.string.ex_russian_twist, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(OBLIQUES), secondary = setOf(ABS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_UPPER_TWIST,
        ),
        CatalogExercise(
            "mountain_climber", R.string.ex_mountain_climber, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(ABS), secondary = setOf(SHOULDERS, OBLIQUES),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_MOUNTAIN_CLIMBER,
        ),
        CatalogExercise(
            "burpee", R.string.ex_burpee, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(QUADS, CHEST), secondary = setOf(GLUTES, SHOULDERS, TRICEPS, ABS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_BURPEE,
        ),
        CatalogExercise(
            "lying_neck_flexion", R.string.ex_lying_neck_flexion, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(NECK),
        ),
        CatalogExercise(
            "lying_neck_extension", R.string.ex_lying_neck_extension, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(NECK), secondary = setOf(TRAPS),
        ),
        CatalogExercise(
            "side_lying_neck_flexion", R.string.ex_side_lying_neck_flexion, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(NECK), secondary = setOf(TRAPS),
        ),
        // Calisthenics and gym bodyweight variations added in 1.6. An archer push-up's share of the
        // body weight is not established; rings and parallel handles move all of it, like the bar.
        CatalogExercise(
            "archer_push_up", R.string.ex_archer_push_up, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(CHEST), secondary = setOf(TRICEPS, SHOULDERS),
        ),
        CatalogExercise(
            "ring_dip", R.string.ex_ring_dip, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(CHEST, TRICEPS), secondary = setOf(SHOULDERS),
            bodyweightFactor = 1.0,
        ),
        CatalogExercise(
            "neutral_grip_pull_up", R.string.ex_neutral_grip_pull_up, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(LATS), secondary = setOf(BICEPS, FOREARMS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_PULL_UP,
            bodyweightFactor = 1.0,
        ),
        CatalogExercise(
            "suspension_row", R.string.ex_suspension_row, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(LATS, TRAPS), secondary = setOf(BICEPS, SHOULDERS),
        ),
        CatalogExercise(
            "sissy_squat", R.string.ex_sissy_squat, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(QUADS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT,
        ),
        CatalogExercise(
            "toes_to_bar", R.string.ex_toes_to_bar, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(ABS), secondary = setOf(OBLIQUES, FOREARMS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_RAISE,
        ),
        CatalogExercise(
            "ghd_sit_up", R.string.ex_ghd_sit_up, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(ABS), secondary = setOf(QUADS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_SIT_UP,
        ),

        // Conditioning and HIIT: jumps by repetitions, the rest (circuits, intervals) by time,
        // optionally with load (sled). They count as training like burpees and mountain climbers;
        // their primary muscles are the ones that do the work. Ropes and sleds are "other" equipment.
        CatalogExercise(
            "jump_rope", R.string.ex_jump_rope, DURATION, OTHER,
            primary = setOf(CALVES), secondary = setOf(FOREARMS, SHOULDERS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_JUMP_ROPE,
        ),
        CatalogExercise(
            "jumping_jack", R.string.ex_jumping_jack, DURATION, BODYWEIGHT,
            primary = setOf(CALVES), secondary = setOf(SHOULDERS, GLUTES),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_JUMPING_JACK,
        ),
        CatalogExercise(
            "high_knees", R.string.ex_high_knees, DURATION, BODYWEIGHT,
            primary = setOf(QUADS), secondary = setOf(CALVES, ABS),
        ),
        CatalogExercise(
            "butt_kicks", R.string.ex_butt_kicks, DURATION, BODYWEIGHT,
            primary = setOf(HAMSTRINGS), secondary = setOf(CALVES),
        ),
        CatalogExercise(
            "box_jump", R.string.ex_box_jump, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(QUADS, GLUTES), secondary = setOf(CALVES, HAMSTRINGS),
        ),
        CatalogExercise(
            "tuck_jump", R.string.ex_tuck_jump, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(QUADS), secondary = setOf(GLUTES, CALVES, ABS),
        ),
        CatalogExercise(
            "skater_jump", R.string.ex_skater_jump, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(GLUTES, QUADS), secondary = setOf(ADDUCTORS, CALVES),
        ),
        CatalogExercise(
            "bear_crawl", R.string.ex_bear_crawl, DURATION, BODYWEIGHT,
            primary = setOf(SHOULDERS, ABS), secondary = setOf(QUADS, TRICEPS),
        ),
        CatalogExercise(
            "shadow_boxing", R.string.ex_shadow_boxing, DURATION, BODYWEIGHT,
            primary = setOf(SHOULDERS), secondary = setOf(TRICEPS, OBLIQUES, CALVES),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_PUNCH,
        ),
        CatalogExercise(
            "step_aerobics", R.string.ex_step_aerobics, DURATION, BODYWEIGHT,
            primary = setOf(QUADS), secondary = setOf(GLUTES, CALVES),
        ),
        CatalogExercise(
            "battle_ropes", R.string.ex_battle_ropes, DURATION, OTHER,
            primary = setOf(SHOULDERS), secondary = setOf(FOREARMS, ABS),
        ),
        CatalogExercise(
            "sled_push", R.string.ex_sled_push, DURATION, OTHER,
            primary = setOf(QUADS, GLUTES), secondary = setOf(CALVES, HAMSTRINGS),
        ),
        CatalogExercise(
            "sled_drag", R.string.ex_sled_drag, DURATION, OTHER,
            primary = setOf(QUADS), secondary = setOf(CALVES, FOREARMS),
        ),

        // Medicine ball and sandbag ("other" equipment): the ball's or bag's weight is the load.
        CatalogExercise(
            "wall_ball", R.string.ex_wall_ball, WEIGHT_REPS, OTHER,
            primary = setOf(QUADS, SHOULDERS), secondary = setOf(GLUTES, TRICEPS),
        ),
        CatalogExercise(
            "medicine_ball_slam", R.string.ex_medicine_ball_slam, WEIGHT_REPS, OTHER,
            primary = setOf(LATS, ABS), secondary = setOf(SHOULDERS, TRICEPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_BALL_SLAM,
        ),
        CatalogExercise(
            "medicine_ball_rotational_throw", R.string.ex_medicine_ball_rotational_throw, WEIGHT_REPS, OTHER,
            primary = setOf(OBLIQUES), secondary = setOf(ABS, SHOULDERS, GLUTES),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_UPPER_TWIST,
        ),
        CatalogExercise(
            "sandbag_carry", R.string.ex_sandbag_carry, DURATION, OTHER,
            primary = setOf(TRAPS, ABS), secondary = setOf(BICEPS, QUADS),
        ),

        // Timed holds and carries: seconds, optionally with load
        CatalogExercise(
            "plank", R.string.ex_plank, DURATION, BODYWEIGHT,
            primary = setOf(ABS), secondary = setOf(OBLIQUES, SHOULDERS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_PLANK,
        ),
        CatalogExercise(
            "side_plank", R.string.ex_side_plank, DURATION, BODYWEIGHT,
            primary = setOf(OBLIQUES), secondary = setOf(ABS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_PLANK,
        ),
        CatalogExercise(
            "copenhagen_plank", R.string.ex_copenhagen_plank, DURATION, BODYWEIGHT,
            primary = setOf(ADDUCTORS), secondary = setOf(OBLIQUES),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_PLANK,
        ),
        CatalogExercise(
            "wall_sit", R.string.ex_wall_sit, DURATION, BODYWEIGHT,
            primary = setOf(QUADS), secondary = setOf(GLUTES),
        ),
        CatalogExercise(
            "dead_hang", R.string.ex_dead_hang, DURATION, BODYWEIGHT,
            primary = setOf(FOREARMS),
        ),
        CatalogExercise(
            "hollow_hold", R.string.ex_hollow_hold, DURATION, BODYWEIGHT,
            primary = setOf(ABS), secondary = setOf(OBLIQUES),
        ),
        CatalogExercise(
            "l_sit", R.string.ex_l_sit, DURATION, BODYWEIGHT,
            primary = setOf(ABS), secondary = setOf(QUADS, TRICEPS),
        ),
        CatalogExercise(
            "neck_isometric_hold", R.string.ex_neck_isometric_hold, DURATION, BODYWEIGHT,
            primary = setOf(NECK),
        ),
        CatalogExercise(
            "farmers_carry", R.string.ex_farmers_carry, DURATION, DUMBBELL,
            primary = setOf(FOREARMS, TRAPS), secondary = setOf(ABS, OBLIQUES),
        ),
        CatalogExercise(
            "suitcase_carry", R.string.ex_suitcase_carry, DURATION, DUMBBELL,
            primary = setOf(OBLIQUES, FOREARMS), secondary = setOf(TRAPS, ABS),
        ),

        // Resistance bands: the band's pull cannot be weighed, so they log repetitions only
        CatalogExercise(
            "band_pull_apart", R.string.ex_band_pull_apart, BODYWEIGHT_REPS, BAND,
            primary = setOf(SHOULDERS, TRAPS),
        ),
        CatalogExercise(
            "banded_lateral_walk", R.string.ex_banded_lateral_walk, BODYWEIGHT_REPS, BAND,
            primary = setOf(GLUTES),
        ),
        CatalogExercise(
            "band_chest_press", R.string.ex_band_chest_press, BODYWEIGHT_REPS, BAND,
            primary = setOf(CHEST), secondary = setOf(TRICEPS, SHOULDERS),
        ),
        CatalogExercise(
            "band_row", R.string.ex_band_row, BODYWEIGHT_REPS, BAND,
            primary = setOf(LATS, TRAPS), secondary = setOf(BICEPS, SHOULDERS),
        ),
        CatalogExercise(
            "band_face_pull", R.string.ex_band_face_pull, BODYWEIGHT_REPS, BAND,
            primary = setOf(SHOULDERS, TRAPS),
        ),
        CatalogExercise(
            "band_lateral_raise", R.string.ex_band_lateral_raise, BODYWEIGHT_REPS, BAND,
            primary = setOf(SHOULDERS), secondary = setOf(TRAPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_LATERAL_RAISE,
        ),
        CatalogExercise(
            "band_external_rotation", R.string.ex_band_external_rotation, BODYWEIGHT_REPS, BAND,
            primary = setOf(SHOULDERS),
        ),
        CatalogExercise(
            "band_curl", R.string.ex_band_curl, BODYWEIGHT_REPS, BAND,
            primary = setOf(BICEPS), secondary = setOf(FOREARMS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_ARM_CURL,
        ),
        CatalogExercise(
            "band_triceps_pushdown", R.string.ex_band_triceps_pushdown, BODYWEIGHT_REPS, BAND,
            primary = setOf(TRICEPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_DOUBLE_ARM_TRICEPS_EXTENSION,
        ),
        CatalogExercise(
            "band_good_morning", R.string.ex_band_good_morning, BODYWEIGHT_REPS, BAND,
            primary = setOf(HAMSTRINGS, LOWER_BACK), secondary = setOf(GLUTES),
        ),

        // Stretching and mobility: held for time; logged and exported (stretching segments) but not
        // training load, so they add no hard sets, recovery, strength trend or records (see
        // CatalogExercise.mobility). Primary muscles are the ones stretched.
        CatalogExercise(
            "hamstring_stretch", R.string.ex_hamstring_stretch, DURATION, BODYWEIGHT,
            primary = setOf(HAMSTRINGS), secondary = setOf(CALVES),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_STRETCHING,
            mobility = true,
        ),
        CatalogExercise(
            "hip_flexor_stretch", R.string.ex_hip_flexor_stretch, DURATION, BODYWEIGHT,
            primary = setOf(QUADS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_STRETCHING,
            mobility = true,
        ),
        CatalogExercise(
            "couch_stretch", R.string.ex_couch_stretch, DURATION, BODYWEIGHT,
            primary = setOf(QUADS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_STRETCHING,
            mobility = true,
        ),
        CatalogExercise(
            "pigeon_stretch", R.string.ex_pigeon_stretch, DURATION, BODYWEIGHT,
            primary = setOf(GLUTES),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_STRETCHING,
            mobility = true,
        ),
        CatalogExercise(
            "butterfly_stretch", R.string.ex_butterfly_stretch, DURATION, BODYWEIGHT,
            primary = setOf(ADDUCTORS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_STRETCHING,
            mobility = true,
        ),
        CatalogExercise(
            "worlds_greatest_stretch", R.string.ex_worlds_greatest_stretch, DURATION, BODYWEIGHT,
            primary = setOf(QUADS, ADDUCTORS), secondary = setOf(HAMSTRINGS, OBLIQUES),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_STRETCHING,
            mobility = true,
        ),
        CatalogExercise(
            "calf_stretch", R.string.ex_calf_stretch, DURATION, BODYWEIGHT,
            primary = setOf(CALVES),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_STRETCHING,
            mobility = true,
        ),
        CatalogExercise(
            "cat_cow", R.string.ex_cat_cow, DURATION, BODYWEIGHT,
            primary = setOf(LOWER_BACK), secondary = setOf(ABS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_STRETCHING,
            mobility = true,
        ),
        CatalogExercise(
            "childs_pose", R.string.ex_childs_pose, DURATION, BODYWEIGHT,
            primary = setOf(LATS, LOWER_BACK),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_STRETCHING,
            mobility = true,
        ),
        CatalogExercise(
            "thoracic_rotation", R.string.ex_thoracic_rotation, DURATION, BODYWEIGHT,
            primary = setOf(OBLIQUES), secondary = setOf(CHEST),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_STRETCHING,
            mobility = true,
        ),
        CatalogExercise(
            "doorway_chest_stretch", R.string.ex_doorway_chest_stretch, DURATION, BODYWEIGHT,
            primary = setOf(CHEST), secondary = setOf(SHOULDERS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_STRETCHING,
            mobility = true,
        ),
        CatalogExercise(
            "cross_body_shoulder_stretch", R.string.ex_cross_body_shoulder_stretch, DURATION, BODYWEIGHT,
            primary = setOf(SHOULDERS), secondary = setOf(TRAPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_STRETCHING,
            mobility = true,
        ),
        CatalogExercise(
            "triceps_stretch", R.string.ex_triceps_stretch, DURATION, BODYWEIGHT,
            primary = setOf(TRICEPS), secondary = setOf(LATS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_STRETCHING,
            mobility = true,
        ),
        CatalogExercise(
            "shoulder_dislocate", R.string.ex_shoulder_dislocate, DURATION, BAND,
            primary = setOf(SHOULDERS), secondary = setOf(CHEST),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_STRETCHING,
            mobility = true,
        ),
        CatalogExercise(
            "neck_stretch", R.string.ex_neck_stretch, DURATION, BODYWEIGHT,
            primary = setOf(NECK), secondary = setOf(TRAPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_STRETCHING,
            mobility = true,
        ),
        mobility("ankle_dorsiflexion_mobilization", R.string.ex_ankle_dorsiflexion_mobilization, setOf(CALVES)),
        mobility("hip_90_90", R.string.ex_hip_90_90, setOf(GLUTES), setOf(ADDUCTORS)),
        mobility("deep_squat_hold", R.string.ex_deep_squat_hold, setOf(ADDUCTORS, GLUTES), setOf(CALVES)),
        mobility("hip_cars", R.string.ex_hip_cars, setOf(GLUTES), setOf(ADDUCTORS)),
        mobility("shoulder_cars", R.string.ex_shoulder_cars, setOf(SHOULDERS), setOf(CHEST, LATS)),
        mobility("thread_the_needle", R.string.ex_thread_the_needle, setOf(SHOULDERS, TRAPS), setOf(OBLIQUES)),
        mobility("cobra_stretch", R.string.ex_cobra_stretch, setOf(ABS), setOf(CHEST)),
        mobility("downward_dog", R.string.ex_downward_dog, setOf(HAMSTRINGS, CALVES), setOf(LATS, SHOULDERS)),
        mobility("standing_quad_stretch", R.string.ex_standing_quad_stretch, setOf(QUADS)),
        mobility("lying_spinal_twist", R.string.ex_lying_spinal_twist, setOf(OBLIQUES, LOWER_BACK), setOf(GLUTES)),
        mobility("figure_four_stretch", R.string.ex_figure_four_stretch, setOf(GLUTES)),
        mobility("wrist_mobility", R.string.ex_wrist_mobility, setOf(FOREARMS)),
        // Foam rolling (self-massage on a foam roller, "other" equipment): logged and exported like the
        // stretches, never training load; the primary muscles are the ones rolled.
        mobility("foam_roll_quads", R.string.ex_foam_roll_quads, setOf(QUADS), equipment = OTHER),
        mobility("foam_roll_hamstrings", R.string.ex_foam_roll_hamstrings, setOf(HAMSTRINGS), equipment = OTHER),
        mobility("foam_roll_calves", R.string.ex_foam_roll_calves, setOf(CALVES), equipment = OTHER),
        mobility("foam_roll_glutes", R.string.ex_foam_roll_glutes, setOf(GLUTES), equipment = OTHER),
        mobility("foam_roll_it_band", R.string.ex_foam_roll_it_band, setOf(QUADS), setOf(GLUTES), equipment = OTHER),
        mobility("foam_roll_upper_back", R.string.ex_foam_roll_upper_back, setOf(TRAPS), setOf(LATS), equipment = OTHER),
        mobility("foam_roll_lats", R.string.ex_foam_roll_lats, setOf(LATS), equipment = OTHER),

        // Cardio machines: no muscles, they train the heart (see TrainingMath.countsForMuscles)
        CatalogExercise(
            "treadmill", R.string.ex_treadmill, CARDIO, CARDIO_MACHINE,
            primary = emptySet(),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_RUNNING_TREADMILL,
            hcSession = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL,
        ),
        CatalogExercise(
            "stationary_bike", R.string.ex_stationary_bike, CARDIO, CARDIO_MACHINE,
            primary = emptySet(),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_BIKING_STATIONARY,
            hcSession = ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY,
        ),
        CatalogExercise(
            "spin_bike", R.string.ex_spin_bike, CARDIO, CARDIO_MACHINE,
            primary = emptySet(),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_BIKING_STATIONARY,
            hcSession = ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY,
        ),
        CatalogExercise(
            "recumbent_bike", R.string.ex_recumbent_bike, CARDIO, CARDIO_MACHINE,
            primary = emptySet(),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_BIKING_STATIONARY,
            hcSession = ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY,
        ),
        CatalogExercise(
            "air_bike", R.string.ex_air_bike, CARDIO, CARDIO_MACHINE,
            primary = emptySet(),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_BIKING_STATIONARY,
            hcSession = ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY,
        ),
        CatalogExercise(
            "elliptical", R.string.ex_elliptical, CARDIO, CARDIO_MACHINE,
            primary = emptySet(),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_ELLIPTICAL,
            hcSession = ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL,
        ),
        CatalogExercise(
            "rowing_machine", R.string.ex_rowing_machine, CARDIO, CARDIO_MACHINE,
            primary = emptySet(),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_ROWING_MACHINE,
            hcSession = ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE,
        ),
        CatalogExercise(
            "stair_climber", R.string.ex_stair_climber, CARDIO, CARDIO_MACHINE,
            primary = emptySet(),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_STAIR_CLIMBING_MACHINE,
            hcSession = ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE,
        ),
        CatalogExercise(
            "ski_erg", R.string.ex_ski_erg, CARDIO, CARDIO_MACHINE,
            primary = emptySet(),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_OTHER_WORKOUT,
            hcSession = ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT,
        ),
        // Walking on a treadmill is walking for Health Connect (it has no treadmill-walking type); the
        // incline goes in the set's level field. The curved treadmill is run on, like the motorised one.
        CatalogExercise(
            "treadmill_walk", R.string.ex_treadmill_walk, CARDIO, CARDIO_MACHINE,
            primary = emptySet(),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_WALKING,
            hcSession = ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
        ),
        CatalogExercise(
            "incline_treadmill_walk", R.string.ex_incline_treadmill_walk, CARDIO, CARDIO_MACHINE,
            primary = emptySet(),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_WALKING,
            hcSession = ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
        ),
        CatalogExercise(
            "curved_treadmill", R.string.ex_curved_treadmill, CARDIO, CARDIO_MACHINE,
            primary = emptySet(),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_RUNNING_TREADMILL,
            hcSession = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL,
        ),

        // Advanced calisthenics (1.8): bar and ring skills, handstands, levers and planches. Rings and
        // bars are bodyweight equipment, as for the ring dip. The bar and ring muscle-ups and the
        // harder pull-ups move the whole body, like the pull-up; the ring and clap push-ups keep the
        // push-up's position (65 %); the other shares are not established. Holds are timed.
        CatalogExercise(
            "bar_muscle_up", R.string.ex_bar_muscle_up, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(LATS, TRICEPS), secondary = setOf(CHEST, BICEPS, FOREARMS),
            bodyweightFactor = 1.0,
        ),
        CatalogExercise(
            "ring_muscle_up", R.string.ex_ring_muscle_up, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(LATS, TRICEPS), secondary = setOf(CHEST, BICEPS, FOREARMS),
            bodyweightFactor = 1.0,
        ),
        CatalogExercise(
            "wall_handstand", R.string.ex_wall_handstand, DURATION, BODYWEIGHT,
            primary = setOf(SHOULDERS), secondary = setOf(TRICEPS, TRAPS, ABS),
        ),
        CatalogExercise(
            "handstand", R.string.ex_handstand, DURATION, BODYWEIGHT,
            primary = setOf(SHOULDERS), secondary = setOf(TRICEPS, TRAPS, FOREARMS),
        ),
        CatalogExercise(
            "wall_handstand_push_up", R.string.ex_wall_handstand_push_up, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(SHOULDERS, TRICEPS), secondary = setOf(TRAPS),
        ),
        CatalogExercise(
            "tuck_front_lever", R.string.ex_tuck_front_lever, DURATION, BODYWEIGHT,
            primary = setOf(LATS, ABS), secondary = setOf(SHOULDERS, TRAPS),
        ),
        CatalogExercise(
            "front_lever", R.string.ex_front_lever, DURATION, BODYWEIGHT,
            primary = setOf(LATS, ABS), secondary = setOf(SHOULDERS, TRAPS),
        ),
        CatalogExercise(
            "tuck_back_lever", R.string.ex_tuck_back_lever, DURATION, BODYWEIGHT,
            primary = setOf(SHOULDERS, CHEST), secondary = setOf(BICEPS, LOWER_BACK),
        ),
        CatalogExercise(
            "back_lever", R.string.ex_back_lever, DURATION, BODYWEIGHT,
            primary = setOf(SHOULDERS, CHEST), secondary = setOf(BICEPS, LOWER_BACK),
        ),
        CatalogExercise(
            "tuck_planche", R.string.ex_tuck_planche, DURATION, BODYWEIGHT,
            primary = setOf(SHOULDERS), secondary = setOf(CHEST, TRICEPS, ABS),
        ),
        CatalogExercise(
            "straddle_planche", R.string.ex_straddle_planche, DURATION, BODYWEIGHT,
            primary = setOf(SHOULDERS), secondary = setOf(CHEST, TRICEPS, ABS),
        ),
        CatalogExercise(
            "human_flag", R.string.ex_human_flag, DURATION, BODYWEIGHT,
            primary = setOf(OBLIQUES, LATS), secondary = setOf(SHOULDERS, ABS),
        ),
        CatalogExercise(
            "tuck_l_sit", R.string.ex_tuck_l_sit, DURATION, BODYWEIGHT,
            primary = setOf(ABS), secondary = setOf(TRICEPS),
        ),
        CatalogExercise(
            "archer_pull_up", R.string.ex_archer_pull_up, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(LATS), secondary = setOf(BICEPS, FOREARMS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_PULL_UP,
            bodyweightFactor = 1.0,
        ),
        CatalogExercise(
            "typewriter_pull_up", R.string.ex_typewriter_pull_up, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(LATS), secondary = setOf(BICEPS, FOREARMS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_PULL_UP,
            bodyweightFactor = 1.0,
        ),
        CatalogExercise(
            "one_arm_push_up", R.string.ex_one_arm_push_up, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(CHEST, TRICEPS), secondary = setOf(SHOULDERS, OBLIQUES),
        ),
        CatalogExercise(
            "shrimp_squat", R.string.ex_shrimp_squat, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(QUADS, GLUTES), secondary = setOf(ADDUCTORS, HAMSTRINGS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT,
        ),
        CatalogExercise(
            "skin_the_cat", R.string.ex_skin_the_cat, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(SHOULDERS, LATS), secondary = setOf(ABS, BICEPS),
        ),
        CatalogExercise(
            "ring_support_hold", R.string.ex_ring_support_hold, DURATION, BODYWEIGHT,
            primary = setOf(TRICEPS, SHOULDERS), secondary = setOf(CHEST, ABS),
        ),
        CatalogExercise(
            "ring_push_up", R.string.ex_ring_push_up, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(CHEST), secondary = setOf(TRICEPS, SHOULDERS),
            bodyweightFactor = 0.65,
        ),
        CatalogExercise(
            "hollow_body_rock", R.string.ex_hollow_body_rock, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(ABS), secondary = setOf(OBLIQUES),
        ),
        CatalogExercise(
            "pseudo_planche_push_up", R.string.ex_pseudo_planche_push_up, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(CHEST, SHOULDERS), secondary = setOf(TRICEPS, ABS),
        ),
        CatalogExercise(
            "clap_push_up", R.string.ex_clap_push_up, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(CHEST), secondary = setOf(TRICEPS, SHOULDERS),
            bodyweightFactor = 0.65,
        ),
        CatalogExercise(
            "hanging_windshield_wiper", R.string.ex_hanging_windshield_wiper, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(OBLIQUES, ABS), secondary = setOf(FOREARMS),
        ),

        // Olympic lifts, kettlebells, strongman and CrossFit-style work (1.8). The whole-body lifts
        // export as weightlifting, as the power clean does; strongman implements (log, yoke, stone,
        // tyre, sandbag) are "other" equipment, whose weight is the load. Double-unders are counted.
        CatalogExercise(
            "push_jerk", R.string.ex_push_jerk, WEIGHT_REPS, BARBELL,
            primary = setOf(SHOULDERS), secondary = setOf(TRICEPS, QUADS, GLUTES),
        ),
        CatalogExercise(
            "split_jerk", R.string.ex_split_jerk, WEIGHT_REPS, BARBELL,
            primary = setOf(SHOULDERS), secondary = setOf(TRICEPS, QUADS, GLUTES),
        ),
        CatalogExercise(
            "clean_and_jerk", R.string.ex_clean_and_jerk, WEIGHT_REPS, BARBELL,
            primary = setOf(GLUTES, QUADS, SHOULDERS), secondary = setOf(HAMSTRINGS, TRAPS, TRICEPS, LOWER_BACK),
        ),
        CatalogExercise(
            "squat_clean", R.string.ex_squat_clean, WEIGHT_REPS, BARBELL,
            primary = setOf(QUADS, GLUTES, TRAPS), secondary = setOf(HAMSTRINGS, LOWER_BACK, FOREARMS),
        ),
        CatalogExercise(
            "hang_power_snatch", R.string.ex_hang_power_snatch, WEIGHT_REPS, BARBELL,
            primary = setOf(GLUTES, HAMSTRINGS, SHOULDERS), secondary = setOf(TRAPS, QUADS, LOWER_BACK, FOREARMS),
        ),
        CatalogExercise(
            "squat_snatch", R.string.ex_squat_snatch, WEIGHT_REPS, BARBELL,
            primary = setOf(QUADS, GLUTES, SHOULDERS), secondary = setOf(HAMSTRINGS, TRAPS, LOWER_BACK),
        ),
        CatalogExercise(
            "snatch_balance", R.string.ex_snatch_balance, WEIGHT_REPS, BARBELL,
            primary = setOf(QUADS, SHOULDERS), secondary = setOf(GLUTES, TRICEPS, TRAPS),
        ),
        CatalogExercise(
            "clean_pull", R.string.ex_clean_pull, WEIGHT_REPS, BARBELL,
            primary = setOf(GLUTES, HAMSTRINGS, TRAPS), secondary = setOf(QUADS, LOWER_BACK, FOREARMS),
        ),
        CatalogExercise(
            "snatch_pull", R.string.ex_snatch_pull, WEIGHT_REPS, BARBELL,
            primary = setOf(GLUTES, HAMSTRINGS, TRAPS), secondary = setOf(QUADS, LOWER_BACK, FOREARMS),
        ),
        CatalogExercise(
            "trap_bar_deadlift", R.string.ex_trap_bar_deadlift, WEIGHT_REPS, BARBELL,
            primary = setOf(GLUTES, QUADS, HAMSTRINGS), secondary = setOf(LOWER_BACK, TRAPS, FOREARMS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_DEADLIFT,
        ),
        // A shoulder mobility drill with a light bell: timed, not training load.
        CatalogExercise(
            "kettlebell_halo", R.string.ex_kettlebell_halo, DURATION, KETTLEBELL,
            primary = setOf(SHOULDERS), secondary = setOf(TRAPS, TRICEPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_STRETCHING,
            mobility = true,
        ),
        CatalogExercise(
            "kettlebell_bottoms_up_press", R.string.ex_kettlebell_bottoms_up_press, WEIGHT_REPS, KETTLEBELL,
            primary = setOf(SHOULDERS), secondary = setOf(FOREARMS, TRICEPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_SHOULDER_PRESS,
        ),
        CatalogExercise(
            "double_kettlebell_front_squat", R.string.ex_double_kettlebell_front_squat, WEIGHT_REPS, KETTLEBELL,
            primary = setOf(QUADS, GLUTES), secondary = setOf(ADDUCTORS, ABS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT,
        ),
        CatalogExercise(
            "single_arm_kettlebell_swing", R.string.ex_single_arm_kettlebell_swing, WEIGHT_REPS, KETTLEBELL,
            primary = setOf(GLUTES, HAMSTRINGS), secondary = setOf(LOWER_BACK, FOREARMS, OBLIQUES),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_KETTLEBELL_SWING,
        ),
        CatalogExercise(
            "american_kettlebell_swing", R.string.ex_american_kettlebell_swing, WEIGHT_REPS, KETTLEBELL,
            primary = setOf(GLUTES, HAMSTRINGS, SHOULDERS), secondary = setOf(LOWER_BACK, FOREARMS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_KETTLEBELL_SWING,
        ),
        CatalogExercise(
            "kettlebell_overhead_carry", R.string.ex_kettlebell_overhead_carry, DURATION, KETTLEBELL,
            primary = setOf(SHOULDERS, OBLIQUES), secondary = setOf(TRAPS, TRICEPS, ABS),
        ),
        CatalogExercise(
            "renegade_row", R.string.ex_renegade_row, WEIGHT_REPS, DUMBBELL,
            primary = setOf(LATS, ABS), secondary = setOf(TRAPS, OBLIQUES, BICEPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_DUMBBELL_ROW,
        ),
        CatalogExercise(
            "devil_press", R.string.ex_devil_press, WEIGHT_REPS, DUMBBELL,
            primary = setOf(SHOULDERS, GLUTES, HAMSTRINGS), secondary = setOf(CHEST, TRICEPS, QUADS),
        ),
        CatalogExercise(
            "man_maker", R.string.ex_man_maker, WEIGHT_REPS, DUMBBELL,
            primary = setOf(SHOULDERS, CHEST, QUADS), secondary = setOf(LATS, TRICEPS, GLUTES),
        ),
        CatalogExercise(
            "dumbbell_thruster", R.string.ex_dumbbell_thruster, WEIGHT_REPS, DUMBBELL,
            primary = setOf(QUADS, SHOULDERS), secondary = setOf(GLUTES, TRICEPS),
        ),
        CatalogExercise(
            "double_unders", R.string.ex_double_unders, BODYWEIGHT_REPS, OTHER,
            primary = setOf(CALVES), secondary = setOf(FOREARMS, SHOULDERS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_JUMP_ROPE,
        ),
        CatalogExercise(
            "burpee_box_jump_over", R.string.ex_burpee_box_jump_over, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(QUADS, CHEST), secondary = setOf(GLUTES, SHOULDERS, TRICEPS, CALVES),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_BURPEE,
        ),
        CatalogExercise(
            "yoke_carry", R.string.ex_yoke_carry, DURATION, OTHER,
            primary = setOf(QUADS, TRAPS), secondary = setOf(GLUTES, ABS, LOWER_BACK),
        ),
        CatalogExercise(
            "log_press", R.string.ex_log_press, WEIGHT_REPS, OTHER,
            primary = setOf(SHOULDERS, TRICEPS), secondary = setOf(CHEST, QUADS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_SHOULDER_PRESS,
        ),
        CatalogExercise(
            "atlas_stone", R.string.ex_atlas_stone, WEIGHT_REPS, OTHER,
            primary = setOf(GLUTES, HAMSTRINGS, LOWER_BACK), secondary = setOf(BICEPS, TRAPS, QUADS),
        ),
        CatalogExercise(
            "tyre_flip", R.string.ex_tyre_flip, WEIGHT_REPS, OTHER,
            primary = setOf(GLUTES, QUADS, HAMSTRINGS), secondary = setOf(LOWER_BACK, SHOULDERS, TRICEPS),
        ),
        CatalogExercise(
            "sandbag_to_shoulder", R.string.ex_sandbag_to_shoulder, WEIGHT_REPS, OTHER,
            primary = setOf(GLUTES, HAMSTRINGS), secondary = setOf(LOWER_BACK, TRAPS, BICEPS),
        ),

        // Suspension trainer (TRX), bosu, Swiss ball, balance and floor Pilates (1.8). The straps count
        // as bodyweight equipment, as for the suspension row; the bosu and the ball are "other" (their
        // body-weight share changes with the angle, so only extra load counts). Pilates mat exercises
        // are strength exercises here (the class itself is the pilates activity): a Pilates segment
        // would not be accepted in a strength session, so they export as the closest gym segment or
        // as weightlifting.
        CatalogExercise(
            "suspension_chest_press", R.string.ex_suspension_chest_press, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(CHEST), secondary = setOf(TRICEPS, SHOULDERS),
        ),
        CatalogExercise(
            "suspension_y_raise", R.string.ex_suspension_y_raise, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(SHOULDERS, TRAPS),
        ),
        CatalogExercise(
            "suspension_curl", R.string.ex_suspension_curl, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(BICEPS), secondary = setOf(FOREARMS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_ARM_CURL,
        ),
        CatalogExercise(
            "suspension_triceps_extension", R.string.ex_suspension_triceps_extension, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(TRICEPS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_DOUBLE_ARM_TRICEPS_EXTENSION,
        ),
        CatalogExercise(
            "suspension_pistol_squat", R.string.ex_suspension_pistol_squat, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(QUADS, GLUTES), secondary = setOf(ADDUCTORS, HAMSTRINGS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT,
        ),
        CatalogExercise(
            "suspension_atomic_push_up", R.string.ex_suspension_atomic_push_up, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(CHEST, ABS), secondary = setOf(TRICEPS, SHOULDERS),
        ),
        CatalogExercise(
            "suspension_pike", R.string.ex_suspension_pike, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(ABS), secondary = setOf(SHOULDERS, OBLIQUES),
        ),
        CatalogExercise(
            "suspension_hamstring_curl", R.string.ex_suspension_hamstring_curl, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(HAMSTRINGS, GLUTES),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_CURL,
        ),
        CatalogExercise(
            "suspension_fallout", R.string.ex_suspension_fallout, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(ABS), secondary = setOf(LATS, SHOULDERS),
        ),
        CatalogExercise(
            "bosu_squat", R.string.ex_bosu_squat, BODYWEIGHT_REPS, OTHER,
            primary = setOf(QUADS, GLUTES), secondary = setOf(ADDUCTORS, CALVES),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT,
        ),
        CatalogExercise(
            "bosu_push_up", R.string.ex_bosu_push_up, BODYWEIGHT_REPS, OTHER,
            primary = setOf(CHEST), secondary = setOf(TRICEPS, SHOULDERS, ABS),
        ),
        // Balance drills (here and the single-leg balance below) are control work, not training load:
        // timed and exported as stretching, like the mobility drills.
        CatalogExercise(
            "bosu_balance", R.string.ex_bosu_balance, DURATION, OTHER,
            primary = setOf(CALVES, GLUTES), secondary = setOf(QUADS, ABS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_STRETCHING,
            mobility = true,
        ),
        CatalogExercise(
            "swiss_ball_crunch", R.string.ex_swiss_ball_crunch, BODYWEIGHT_REPS, OTHER,
            primary = setOf(ABS), secondary = setOf(OBLIQUES),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_CRUNCH,
        ),
        CatalogExercise(
            "swiss_ball_pike", R.string.ex_swiss_ball_pike, BODYWEIGHT_REPS, OTHER,
            primary = setOf(ABS), secondary = setOf(SHOULDERS),
        ),
        CatalogExercise(
            "swiss_ball_hamstring_curl", R.string.ex_swiss_ball_hamstring_curl, BODYWEIGHT_REPS, OTHER,
            primary = setOf(HAMSTRINGS, GLUTES),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_CURL,
        ),
        CatalogExercise(
            "swiss_ball_pass", R.string.ex_swiss_ball_pass, BODYWEIGHT_REPS, OTHER,
            primary = setOf(ABS), secondary = setOf(ADDUCTORS),
        ),
        CatalogExercise(
            "stir_the_pot", R.string.ex_stir_the_pot, DURATION, OTHER,
            primary = setOf(ABS, OBLIQUES), secondary = setOf(SHOULDERS),
        ),
        CatalogExercise(
            "single_leg_balance", R.string.ex_single_leg_balance, DURATION, BODYWEIGHT,
            primary = setOf(CALVES, GLUTES), secondary = setOf(ABS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_STRETCHING,
            mobility = true,
        ),
        CatalogExercise(
            "crow_pose", R.string.ex_crow_pose, DURATION, BODYWEIGHT,
            primary = setOf(SHOULDERS, ABS), secondary = setOf(TRICEPS, FOREARMS),
        ),
        CatalogExercise(
            "v_up", R.string.ex_v_up, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(ABS), secondary = setOf(OBLIQUES),
        ),
        CatalogExercise(
            "pilates_hundred", R.string.ex_pilates_hundred, DURATION, BODYWEIGHT,
            primary = setOf(ABS), secondary = setOf(OBLIQUES),
        ),
        CatalogExercise(
            "pilates_roll_up", R.string.ex_pilates_roll_up, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(ABS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_SIT_UP,
        ),
        CatalogExercise(
            "pilates_single_leg_stretch", R.string.ex_pilates_single_leg_stretch, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(ABS), secondary = setOf(OBLIQUES),
        ),
        CatalogExercise(
            "pilates_double_leg_stretch", R.string.ex_pilates_double_leg_stretch, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(ABS),
        ),
        CatalogExercise(
            "pilates_swimming", R.string.ex_pilates_swimming, DURATION, BODYWEIGHT,
            primary = setOf(LOWER_BACK, GLUTES), secondary = setOf(SHOULDERS, HAMSTRINGS),
        ),
        CatalogExercise(
            "pilates_teaser", R.string.ex_pilates_teaser, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(ABS), secondary = setOf(OBLIQUES),
        ),
        CatalogExercise(
            "pilates_side_kick", R.string.ex_pilates_side_kick, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(GLUTES), secondary = setOf(ADDUCTORS, OBLIQUES),
        ),
        CatalogExercise(
            "pilates_shoulder_bridge", R.string.ex_pilates_shoulder_bridge, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(GLUTES, HAMSTRINGS), secondary = setOf(LOWER_BACK),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_HIP_THRUST,
        ),

        // Combat drills (1.8): punches and kicks on bags and pads, shadow drills and the sprawl, done in
        // timed rounds like shadow boxing (punching segments). Bags and pads are "other" equipment.
        CatalogExercise(
            "heavy_bag", R.string.ex_heavy_bag, DURATION, OTHER,
            primary = setOf(SHOULDERS), secondary = setOf(TRICEPS, OBLIQUES, CHEST),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_PUNCH,
        ),
        CatalogExercise(
            "speed_bag", R.string.ex_speed_bag, DURATION, OTHER,
            primary = setOf(SHOULDERS), secondary = setOf(FOREARMS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_PUNCH,
        ),
        CatalogExercise(
            "double_end_bag", R.string.ex_double_end_bag, DURATION, OTHER,
            primary = setOf(SHOULDERS), secondary = setOf(TRICEPS, OBLIQUES),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_PUNCH,
        ),
        CatalogExercise(
            "pad_work", R.string.ex_pad_work, DURATION, OTHER,
            primary = setOf(SHOULDERS), secondary = setOf(TRICEPS, OBLIQUES, CALVES),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_PUNCH,
        ),
        CatalogExercise(
            "shadow_hooks", R.string.ex_shadow_hooks, DURATION, BODYWEIGHT,
            primary = setOf(OBLIQUES, SHOULDERS), secondary = setOf(CHEST),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_PUNCH,
        ),
        CatalogExercise(
            "shadow_uppercuts", R.string.ex_shadow_uppercuts, DURATION, BODYWEIGHT,
            primary = setOf(SHOULDERS), secondary = setOf(BICEPS, OBLIQUES, QUADS),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_PUNCH,
        ),
        CatalogExercise(
            "dumbbell_punches", R.string.ex_dumbbell_punches, DURATION, DUMBBELL,
            primary = setOf(SHOULDERS), secondary = setOf(TRICEPS, CHEST),
            hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_PUNCH,
        ),
        CatalogExercise(
            "bag_kicks", R.string.ex_bag_kicks, DURATION, OTHER,
            primary = setOf(GLUTES, QUADS), secondary = setOf(OBLIQUES, ADDUCTORS, CALVES),
        ),
        CatalogExercise(
            "knee_strikes", R.string.ex_knee_strikes, DURATION, OTHER,
            primary = setOf(ABS, GLUTES), secondary = setOf(QUADS, CALVES),
        ),
        CatalogExercise(
            "sprawl", R.string.ex_sprawl, BODYWEIGHT_REPS, BODYWEIGHT,
            primary = setOf(ABS, QUADS), secondary = setOf(SHOULDERS, CHEST, GLUTES),
        ),
    ) + SportsCatalog.all

    private val byId: Map<String, CatalogExercise> = all.associateBy { it.id }

    operator fun get(id: String): CatalogExercise? = byId[id]

    /** A stretch, mobility drill or foam-rolling entry: timed, a stretching segment, not training load. */
    private fun mobility(
        id: String,
        nameRes: Int,
        primary: Set<Muscle>,
        secondary: Set<Muscle> = emptySet(),
        equipment: Equipment = BODYWEIGHT,
    ) = CatalogExercise(
        id, nameRes, DURATION, equipment,
        primary = primary, secondary = secondary,
        hcSegment = ExerciseSegment.EXERCISE_SEGMENT_TYPE_STRETCHING,
        mobility = true,
    )
}
