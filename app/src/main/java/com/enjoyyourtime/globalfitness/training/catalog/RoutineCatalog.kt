package com.enjoyyourtime.globalfitness.training.catalog

import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.training.model.CatalogRoutine
import com.enjoyyourtime.globalfitness.training.model.RoutineExercise

/**
 * Built-in routines: ready-made sets of catalog exercises to start a workout with or add to one in
 * a single step. Targets are a sensible default for most gym-goers (3–4 sets of 8–15 reps); loads
 * come from the user's last session of each exercise. Keep ids stable (the screens may remember
 * the last routine used). Names are string resources `routine_<id>`
 * (tools/i18n/src/<locale>/97_routines.txt).
 */
object RoutineCatalog {
    private fun e(id: String, sets: Int, reps: Int) = RoutineExercise(id, sets, reps = reps)

    private fun timed(id: String, sets: Int, seconds: Int) = RoutineExercise(id, sets, durationSec = seconds)

    val all: List<CatalogRoutine> = listOf(
        // Full body: two or three times a week, the easiest start.
        CatalogRoutine(
            "full_body_machines", R.string.routine_full_body_machines,
            listOf(
                e("leg_press_machine", 3, 12),
                e("machine_chest_press", 3, 12),
                e("lat_pulldown_machine", 3, 12),
                e("machine_row", 3, 12),
                e("machine_shoulder_press", 3, 12),
                e("lying_leg_curl_machine", 2, 15),
                timed("plank", 3, 30),
            ),
        ),
        CatalogRoutine(
            "full_body_free_weights", R.string.routine_full_body_free_weights,
            listOf(
                e("barbell_back_squat", 3, 8),
                e("barbell_bench_press", 3, 8),
                e("barbell_row", 3, 8),
                e("dumbbell_shoulder_press", 3, 10),
                e("barbell_romanian_deadlift", 3, 10),
                timed("plank", 3, 45),
            ),
        ),
        // Push / pull / legs.
        CatalogRoutine(
            "push", R.string.routine_push,
            listOf(
                e("barbell_bench_press", 4, 8),
                e("dumbbell_incline_bench_press", 3, 10),
                e("machine_shoulder_press", 3, 10),
                e("dumbbell_lateral_raise", 3, 15),
                e("rope_triceps_pushdown", 3, 12),
                e("cable_overhead_triceps_extension", 3, 12),
            ),
        ),
        CatalogRoutine(
            "pull", R.string.routine_pull,
            listOf(
                e("lat_pulldown_machine", 4, 10),
                e("seated_cable_row", 3, 10),
                e("dumbbell_row", 3, 10),
                e("face_pull", 3, 15),
                e("barbell_curl", 3, 10),
                e("hammer_curl", 3, 12),
            ),
        ),
        CatalogRoutine(
            "legs", R.string.routine_legs,
            listOf(
                e("barbell_back_squat", 4, 8),
                e("barbell_romanian_deadlift", 3, 10),
                e("leg_press_machine", 3, 12),
                e("leg_extension_machine", 3, 15),
                e("lying_leg_curl_machine", 3, 12),
                e("standing_calf_raise_machine", 4, 15),
            ),
        ),
        // Upper / lower.
        CatalogRoutine(
            "upper_body", R.string.routine_upper_body,
            listOf(
                e("barbell_bench_press", 4, 8),
                e("barbell_row", 4, 8),
                e("dumbbell_shoulder_press", 3, 10),
                e("lat_pulldown_machine", 3, 10),
                e("dumbbell_curl", 3, 12),
                e("cable_triceps_pushdown", 3, 12),
            ),
        ),
        CatalogRoutine(
            "lower_body", R.string.routine_lower_body,
            listOf(
                e("barbell_back_squat", 4, 6),
                e("barbell_romanian_deadlift", 3, 8),
                e("bulgarian_split_squat", 3, 10),
                e("lying_leg_curl_machine", 3, 12),
                e("standing_calf_raise_machine", 4, 12),
                e("hanging_leg_raise", 3, 12),
            ),
        ),
        // One or two muscle groups a day.
        CatalogRoutine(
            "chest_triceps", R.string.routine_chest_triceps,
            listOf(
                e("barbell_bench_press", 4, 8),
                e("dumbbell_incline_bench_press", 3, 10),
                e("pec_deck", 3, 12),
                e("parallel_bar_dip", 3, 10),
                e("rope_triceps_pushdown", 3, 12),
                e("skull_crusher", 3, 10),
            ),
        ),
        CatalogRoutine(
            "back_biceps", R.string.routine_back_biceps,
            listOf(
                e("pull_up", 4, 8),
                e("barbell_row", 3, 8),
                e("seated_cable_row", 3, 10),
                e("straight_arm_pulldown", 3, 12),
                e("ez_bar_curl", 3, 10),
                e("incline_dumbbell_curl", 3, 12),
            ),
        ),
        CatalogRoutine(
            "shoulders", R.string.routine_shoulders,
            listOf(
                e("barbell_overhead_press", 4, 8),
                e("dumbbell_lateral_raise", 4, 15),
                e("reverse_pec_deck", 3, 15),
                e("cable_lateral_raise", 3, 12),
                e("face_pull", 3, 15),
                e("dumbbell_shrug", 3, 12),
            ),
        ),
        CatalogRoutine(
            "arms", R.string.routine_arms,
            listOf(
                e("barbell_curl", 3, 10),
                e("close_grip_bench_press", 3, 8),
                e("hammer_curl", 3, 12),
                e("rope_triceps_pushdown", 3, 12),
                e("concentration_curl", 3, 12),
                e("dumbbell_overhead_extension", 3, 12),
            ),
        ),
        CatalogRoutine(
            "glutes", R.string.routine_glutes,
            listOf(
                e("barbell_hip_thrust", 4, 10),
                e("bulgarian_split_squat", 3, 10),
                e("cable_glute_kickback", 3, 15),
                e("hip_abductor_machine", 3, 15),
                e("barbell_romanian_deadlift", 3, 10),
            ),
        ),
        CatalogRoutine(
            "core", R.string.routine_core,
            listOf(
                timed("plank", 3, 45),
                timed("side_plank", 2, 30),
                e("hanging_leg_raise", 3, 12),
                e("cable_crunch", 3, 15),
                e("russian_twist", 3, 20),
                e("back_extension", 3, 12),
            ),
        ),
        // Stretching and mobility: a cool-down or a recovery day; held for time (sets per side).
        CatalogRoutine(
            "mobility", R.string.routine_mobility,
            listOf(
                timed("cat_cow", 1, 60),
                timed("worlds_greatest_stretch", 2, 45),
                timed("hip_flexor_stretch", 2, 45),
                timed("hamstring_stretch", 2, 45),
                timed("pigeon_stretch", 2, 45),
                timed("thoracic_rotation", 2, 30),
                timed("doorway_chest_stretch", 2, 30),
                timed("childs_pose", 1, 60),
            ),
        ),
        // Foam rolling: a warm-up or a recovery day, calves to upper body (sets per side where one-sided).
        CatalogRoutine(
            "foam_rolling", R.string.routine_foam_rolling,
            listOf(
                timed("foam_roll_calves", 1, 60),
                timed("foam_roll_hamstrings", 1, 60),
                timed("foam_roll_quads", 1, 60),
                timed("foam_roll_it_band", 2, 45),
                timed("foam_roll_glutes", 2, 45),
                timed("foam_roll_upper_back", 1, 60),
                timed("foam_roll_lats", 2, 45),
            ),
        ),
        // HIIT without equipment: work intervals of 30-40 s, or a few fast reps, with short rests.
        CatalogRoutine(
            "hiit", R.string.routine_hiit,
            listOf(
                timed("jumping_jack", 3, 40),
                timed("high_knees", 3, 30),
                e("burpee", 3, 10),
                e("mountain_climber", 3, 20),
                e("jump_squat", 3, 12),
                e("skater_jump", 3, 16),
                timed("plank", 3, 30),
            ),
        ),
        CatalogRoutine(
            "cardio_mix", R.string.routine_cardio_mix,
            listOf(
                timed("treadmill", 1, 600),
                timed("stationary_bike", 1, 600),
                timed("rowing_machine", 1, 600),
            ),
        ),
        // Kettlebell full body (1.8): everything with a single bell. The halo warms the shoulders up,
        // swings and get-ups train the posterior chain and the core.
        CatalogRoutine(
            "kettlebell", R.string.routine_kettlebell,
            listOf(
                timed("kettlebell_halo", 2, 30),
                e("kettlebell_swing", 4, 15),
                e("kettlebell_goblet_squat", 3, 10),
                e("kettlebell_clean_and_press", 3, 8),
                e("kettlebell_row", 3, 10),
                e("turkish_get_up", 2, 3),
                timed("kettlebell_overhead_carry", 2, 40),
            ),
        ),
        // Calisthenics skills (1.8): the skills first, while fresh (short holds an intermediate can
        // manage), then pulling and pushing strength and the core.
        CatalogRoutine(
            "calisthenics", R.string.routine_calisthenics,
            listOf(
                timed("wall_handstand", 3, 20),
                timed("tuck_front_lever", 4, 8),
                timed("tuck_l_sit", 3, 10),
                e("pull_up", 4, 6),
                e("parallel_bar_dip", 3, 8),
                e("pike_push_up", 3, 8),
                e("hollow_body_rock", 3, 12),
            ),
        ),
    )

    private val byId: Map<String, CatalogRoutine> = all.associateBy { it.id }

    operator fun get(id: String): CatalogRoutine? = byId[id]
}
