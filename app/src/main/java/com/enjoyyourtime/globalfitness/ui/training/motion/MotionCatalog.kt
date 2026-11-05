package com.enjoyyourtime.globalfitness.ui.training.motion

import com.enjoyyourtime.globalfitness.training.model.Equipment
import com.enjoyyourtime.globalfitness.training.model.Exercise
import com.enjoyyourtime.globalfitness.training.model.ExerciseKind
import com.enjoyyourtime.globalfitness.training.model.Muscle

/**
 * Which animation each exercise gets. Every built-in exercise maps to a movement pattern with its
 * parameters (equipment, angle, grip). A custom exercise borrows the animation of the most typical
 * built-in exercise for its main muscle and equipment (a curl for biceps with dumbbells, a leg
 * press for quads on a machine...); combinations without an obvious movement (cardio machines,
 * "other" equipment) get none. Motions are built on first use and kept.
 */
internal object MotionCatalog {
    /**
     * The original catalog's exercises; the sports, the larger gym catalog and the 1.6 additions
     * (conditioning, functional lifts, mobility, foam rolling, treadmill walks: [moreMotions]) have
     * their own maps.
     */
    private val base: Map<String, () -> Motion> = mapOf(
        // Selectorized and plate-loaded machines
        "machine_chest_press" to { machineChestPress(incline = false) },
        "machine_incline_chest_press" to { machineChestPress(incline = true) },
        "pec_deck" to { pecDeck() },
        "machine_shoulder_press" to { seatedPress(Grip.MACHINE) },
        "machine_lateral_raise" to { lateralRaise(Grip.MACHINE) },
        "reverse_pec_deck" to { rearDeltFly(RearDelt.MACHINE) },
        "machine_row" to { machineRow() },
        "pullover_machine" to { pullover(Pullover.MACHINE) },
        "leg_press_machine" to { legPress(LegPress.ANGLED) },
        "horizontal_leg_press" to { legPress(LegPress.HORIZONTAL) },
        "hack_squat_machine" to { hackSquat(pendulum = false) },
        "pendulum_squat_machine" to { hackSquat(pendulum = true) },
        "leg_extension_machine" to { legExtension() },
        "lying_leg_curl_machine" to { legCurl(LegCurl.LYING) },
        "seated_leg_curl_machine" to { legCurl(LegCurl.SEATED) },
        "standing_calf_raise_machine" to { calfRaise(CalfRaise.STANDING_MACHINE) },
        "seated_calf_raise_machine" to { calfRaise(CalfRaise.SEATED) },
        "leg_press_calf_raise" to { legPress(LegPress.CALF) },
        "hip_abductor_machine" to { hipAbduction(outward = true) },
        "hip_adductor_machine" to { hipAbduction(outward = false) },
        "glute_kickback_machine" to { gluteKickback(machine = true) },
        "hip_thrust_machine" to { hipThrust(Thrust.MACHINE) },
        "back_extension_machine" to { backExtension(machine = true) },
        "ab_crunch_machine" to { crunch(Crunch.MACHINE) },
        "rotary_torso_machine" to { rotaryTorso() },
        "machine_biceps_curl" to { preacherCurl(Grip.MACHINE) },
        "machine_triceps_extension" to { machineTricepsExtension() },
        "seated_dip_machine" to { seatedDipMachine() },
        "assisted_pull_up_machine" to { pullUp(chin = false, assisted = true) },
        "assisted_chin_up_machine" to { pullUp(chin = true, assisted = true) },
        "assisted_dip_machine" to { dip(assisted = true) },
        "neck_machine" to { neckMachine() },
        // Cable stations
        "lat_pulldown_machine" to { pulldown(close = false) },
        "close_grip_lat_pulldown" to { pulldown(close = true) },
        "seated_cable_row" to { seatedCableRow(single = false) },
        "single_arm_cable_row" to { seatedCableRow(single = true) },
        "straight_arm_pulldown" to { pullover(Pullover.CABLE_STANDING) },
        "cable_crossover" to { cableFly(Pulley.HIGH) },
        "low_cable_crossover" to { cableFly(Pulley.LOW) },
        "cable_fly" to { cableFly(Pulley.MID) },
        "face_pull" to { facePull() },
        "cable_lateral_raise" to { lateralRaise(Grip.CABLE) },
        "cable_triceps_pushdown" to { pushdown(rope = false) },
        "rope_triceps_pushdown" to { pushdown(rope = true) },
        "cable_overhead_triceps_extension" to { overheadExtension(Grip.CABLE) },
        "cable_curl" to { standingCurl(Grip.CABLE) },
        "cable_crunch" to { crunch(Crunch.CABLE) },
        "cable_woodchop" to { woodchop() },
        "cable_pull_through" to { pullThrough() },
        "cable_glute_kickback" to { gluteKickback(machine = false) },
        // Smith machine
        "smith_squat" to { squat(SquatLoad.SMITH) },
        "smith_bench_press" to { benchPress(Grip.SMITH) },
        "smith_incline_bench_press" to { benchPress(Grip.SMITH, incline = 30f) },
        "smith_shoulder_press" to { seatedPress(Grip.SMITH) },
        "smith_split_squat" to { lunge(Lunge.SPLIT, Grip.SMITH) },
        "smith_hip_thrust" to { hipThrust(Thrust.SMITH) },
        "smith_calf_raise" to { calfRaise(CalfRaise.SMITH) },
        // Barbell and EZ bar
        "barbell_bench_press" to { benchPress(Grip.BARBELL) },
        "barbell_incline_bench_press" to { benchPress(Grip.BARBELL, incline = 30f) },
        "barbell_decline_bench_press" to { benchPress(Grip.BARBELL, incline = -15f) },
        "close_grip_bench_press" to { benchPress(Grip.BARBELL, width = 0.2f) },
        "barbell_overhead_press" to { barbellOverheadPress() },
        "barbell_back_squat" to { squat(SquatLoad.BACK_BAR) },
        "barbell_front_squat" to { squat(SquatLoad.FRONT_BAR) },
        "barbell_deadlift" to { deadlift(sumo = false) },
        "sumo_deadlift" to { deadlift(sumo = true) },
        "barbell_romanian_deadlift" to { romanianDeadlift(Grip.BARBELL) },
        "good_morning" to { goodMorning() },
        "barbell_lunge" to { lunge(Lunge.FORWARD, Grip.BARBELL) },
        "barbell_hip_thrust" to { hipThrust(Thrust.BARBELL) },
        "barbell_glute_bridge" to { hipThrust(Thrust.BARBELL_BRIDGE) },
        "barbell_row" to { barbellRow(Row.BENT_OVER) },
        "pendlay_row" to { barbellRow(Row.PENDLAY) },
        "t_bar_row" to { barbellRow(Row.T_BAR) },
        "barbell_upright_row" to { uprightRow() },
        "barbell_shrug" to { shrug(Grip.BARBELL) },
        "barbell_curl" to { standingCurl(Grip.BARBELL) },
        "ez_bar_curl" to { standingCurl(Grip.EZ_BAR) },
        "ez_bar_preacher_curl" to { preacherCurl(Grip.EZ_BAR) },
        "skull_crusher" to { skullCrusher() },
        "barbell_wrist_curl" to { wristCurl(reverse = false) },
        "barbell_reverse_wrist_curl" to { wristCurl(reverse = true) },
        // Dumbbells
        "dumbbell_bench_press" to { benchPress(Grip.DUMBBELLS) },
        "dumbbell_incline_bench_press" to { benchPress(Grip.DUMBBELLS, incline = 30f) },
        "dumbbell_fly" to { dumbbellFly() },
        "dumbbell_pullover" to { pullover(Pullover.DUMBBELL) },
        "dumbbell_shoulder_press" to { seatedPress(Grip.DUMBBELLS) },
        "arnold_press" to { arnoldPress() },
        "dumbbell_lateral_raise" to { lateralRaise(Grip.DUMBBELLS) },
        "dumbbell_front_raise" to { frontRaise() },
        "dumbbell_rear_delt_fly" to { rearDeltFly(RearDelt.DUMBBELLS_BENT_OVER) },
        "dumbbell_row" to { dumbbellRow() },
        "dumbbell_shrug" to { shrug(Grip.DUMBBELLS) },
        "dumbbell_curl" to { standingCurl(Grip.DUMBBELLS) },
        "hammer_curl" to { standingCurl(Grip.DUMBBELLS, hammer = true) },
        "incline_dumbbell_curl" to { inclineCurl() },
        "concentration_curl" to { concentrationCurl() },
        "dumbbell_overhead_extension" to { overheadExtension(Grip.DUMBBELLS) },
        "dumbbell_triceps_kickback" to { kickback() },
        "dumbbell_goblet_squat" to { squat(SquatLoad.GOBLET_DUMBBELL) },
        "bulgarian_split_squat" to { lunge(Lunge.BULGARIAN, Grip.DUMBBELLS) },
        "dumbbell_lunge" to { lunge(Lunge.FORWARD, Grip.DUMBBELLS) },
        "dumbbell_step_up" to { stepUp() },
        "dumbbell_romanian_deadlift" to { romanianDeadlift(Grip.DUMBBELLS) },
        "dumbbell_side_bend" to { sideBend() },
        // Kettlebells
        "kettlebell_swing" to { kettlebellSwing() },
        "kettlebell_goblet_squat" to { squat(SquatLoad.GOBLET_KETTLEBELL) },
        "kettlebell_clean_and_press" to { cleanAndPress() },
        "turkish_get_up" to { turkishGetUp() },
        // Bodyweight
        "push_up" to { pushUp() },
        "parallel_bar_dip" to { dip(assisted = false) },
        "bench_dip" to { benchDip() },
        "pull_up" to { pullUp(chin = false, assisted = false) },
        "chin_up" to { pullUp(chin = true, assisted = false) },
        "inverted_row" to { invertedRow() },
        "bodyweight_squat" to { squat(SquatLoad.BODYWEIGHT) },
        "bodyweight_lunge" to { lunge(Lunge.FORWARD, Grip.NONE) },
        "glute_bridge" to { hipThrust(Thrust.BRIDGE) },
        "back_extension" to { backExtension(machine = false) },
        "nordic_curl" to { legCurl(LegCurl.NORDIC) },
        "crunch" to { crunch(Crunch.FLOOR) },
        "sit_up" to { crunch(Crunch.SIT_UP) },
        "hanging_leg_raise" to { legRaise(hanging = true) },
        "lying_leg_raise" to { legRaise(hanging = false) },
        "russian_twist" to { russianTwist() },
        "mountain_climber" to { mountainClimber() },
        "burpee" to { burpee() },
        // Timed holds and carries
        "plank" to { plank(Plank.FRONT) },
        "side_plank" to { plank(Plank.SIDE) },
        "copenhagen_plank" to { plank(Plank.COPENHAGEN) },
        "wall_sit" to { wallSit() },
        "dead_hang" to { deadHang() },
        "farmers_carry" to { farmersCarry() },
        // Resistance bands
        "band_pull_apart" to { rearDeltFly(RearDelt.BAND) },
        "banded_lateral_walk" to { lateralWalk() },
        // Cardio machines
        "treadmill" to { treadmillRun() },
        "stationary_bike" to { bike(Bike.UPRIGHT) },
        "spin_bike" to { bike(Bike.SPIN) },
        "recumbent_bike" to { bike(Bike.RECUMBENT) },
        "air_bike" to { bike(Bike.AIR) },
        "elliptical" to { elliptical() },
        "rowing_machine" to { rowingMachine() },
        "stair_climber" to { stairClimber() },
        "ski_erg" to { skiErg() },
    )

    private val builtIn: Map<String, () -> Motion> = base + sportsMotions + gymMotions + moreMotions + latestMotions

    /**
     * The built-in exercise whose animation a custom one borrows, by main muscle and equipment; a
     * muscle missing from the table (or equipment without a typical movement) has none.
     */
    private val typical: Map<Muscle, Map<Equipment, String>> = mapOf(
        Muscle.CHEST to mapOf(
            Equipment.BARBELL to "barbell_bench_press",
            Equipment.DUMBBELL to "dumbbell_bench_press",
            Equipment.MACHINE to "machine_chest_press",
            Equipment.SMITH_MACHINE to "smith_bench_press",
            Equipment.CABLE to "cable_fly",
            Equipment.BODYWEIGHT to "push_up",
            Equipment.BAND to "band_chest_press",
        ),
        Muscle.SHOULDERS to mapOf(
            Equipment.BARBELL to "barbell_overhead_press",
            Equipment.DUMBBELL to "dumbbell_shoulder_press",
            Equipment.MACHINE to "machine_shoulder_press",
            Equipment.SMITH_MACHINE to "smith_shoulder_press",
            Equipment.CABLE to "cable_lateral_raise",
            Equipment.BAND to "band_pull_apart",
            Equipment.KETTLEBELL to "kettlebell_clean_and_press",
            Equipment.BODYWEIGHT to "pike_push_up",
        ),
        Muscle.BICEPS to mapOf(
            Equipment.BARBELL to "barbell_curl",
            Equipment.DUMBBELL to "dumbbell_curl",
            Equipment.MACHINE to "machine_biceps_curl",
            Equipment.CABLE to "cable_curl",
            Equipment.BAND to "band_curl",
        ),
        Muscle.TRICEPS to mapOf(
            Equipment.BARBELL to "skull_crusher",
            Equipment.DUMBBELL to "dumbbell_overhead_extension",
            Equipment.MACHINE to "machine_triceps_extension",
            Equipment.CABLE to "cable_triceps_pushdown",
            Equipment.BODYWEIGHT to "bench_dip",
            Equipment.BAND to "band_triceps_pushdown",
        ),
        Muscle.FOREARMS to mapOf(Equipment.BARBELL to "barbell_wrist_curl", Equipment.DUMBBELL to "dumbbell_wrist_curl"),
        Muscle.ABS to mapOf(
            Equipment.BODYWEIGHT to "crunch",
            Equipment.CABLE to "cable_crunch",
            Equipment.MACHINE to "ab_crunch_machine",
        ),
        Muscle.OBLIQUES to mapOf(
            Equipment.BODYWEIGHT to "russian_twist",
            Equipment.CABLE to "cable_woodchop",
            Equipment.MACHINE to "rotary_torso_machine",
            Equipment.DUMBBELL to "dumbbell_side_bend",
            Equipment.KETTLEBELL to "kettlebell_windmill",
            Equipment.BARBELL to "landmine_rotation",
        ),
        Muscle.TRAPS to mapOf(Equipment.BARBELL to "barbell_shrug", Equipment.DUMBBELL to "dumbbell_shrug", Equipment.SMITH_MACHINE to "smith_shrug"),
        Muscle.LATS to mapOf(
            Equipment.BARBELL to "barbell_row",
            Equipment.DUMBBELL to "dumbbell_row",
            Equipment.MACHINE to "machine_row",
            Equipment.CABLE to "lat_pulldown_machine",
            Equipment.BODYWEIGHT to "pull_up",
            Equipment.KETTLEBELL to "kettlebell_row",
            Equipment.SMITH_MACHINE to "smith_bent_over_row",
            Equipment.BAND to "band_row",
        ),
        Muscle.LOWER_BACK to mapOf(
            Equipment.BODYWEIGHT to "back_extension",
            Equipment.MACHINE to "back_extension_machine",
            Equipment.BARBELL to "good_morning",
            Equipment.BAND to "band_good_morning",
        ),
        Muscle.GLUTES to mapOf(
            Equipment.BARBELL to "barbell_hip_thrust",
            Equipment.MACHINE to "hip_thrust_machine",
            Equipment.SMITH_MACHINE to "smith_hip_thrust",
            Equipment.CABLE to "cable_glute_kickback",
            Equipment.BODYWEIGHT to "glute_bridge",
            Equipment.BAND to "banded_lateral_walk",
            Equipment.KETTLEBELL to "kettlebell_swing",
        ),
        Muscle.QUADS to mapOf(
            Equipment.BARBELL to "barbell_back_squat",
            Equipment.DUMBBELL to "dumbbell_goblet_squat",
            Equipment.KETTLEBELL to "kettlebell_goblet_squat",
            Equipment.MACHINE to "leg_press_machine",
            Equipment.SMITH_MACHINE to "smith_squat",
            Equipment.BODYWEIGHT to "bodyweight_squat",
        ),
        Muscle.HAMSTRINGS to mapOf(
            Equipment.BARBELL to "barbell_romanian_deadlift",
            Equipment.DUMBBELL to "dumbbell_romanian_deadlift",
            Equipment.MACHINE to "lying_leg_curl_machine",
            Equipment.BODYWEIGHT to "nordic_curl",
            Equipment.KETTLEBELL to "kettlebell_swing",
            Equipment.SMITH_MACHINE to "smith_romanian_deadlift",
            Equipment.BAND to "band_good_morning",
        ),
        Muscle.ADDUCTORS to mapOf(
            Equipment.MACHINE to "hip_adductor_machine",
            Equipment.CABLE to "cable_hip_adduction",
            Equipment.DUMBBELL to "dumbbell_sumo_squat",
            Equipment.BODYWEIGHT to "cossack_squat",
        ),
        Muscle.CALVES to mapOf(
            Equipment.MACHINE to "standing_calf_raise_machine",
            Equipment.SMITH_MACHINE to "smith_calf_raise",
            Equipment.DUMBBELL to "dumbbell_calf_raise",
            Equipment.BODYWEIGHT to "single_leg_calf_raise",
        ),
        Muscle.NECK to mapOf(Equipment.MACHINE to "neck_machine", Equipment.BODYWEIGHT to "lying_neck_flexion"),
    )

    private val cache = HashMap<String, Motion>()

    /** Ids of the built-in exercises with their own animation. */
    val mappedIds: Set<String> get() = builtIn.keys

    /** Ids of [base] (the maps joined to it must not repeat one: the later one would silently win). */
    val baseIds: Set<String> get() = base.keys

    /** The animation of the built-in exercise [id], or null when it has none. */
    fun forId(id: String): Motion? = synchronized(cache) {
        cache[id] ?: builtIn[id]?.invoke()?.also { cache[id] = it }
    }

    /** The animation [exercise] shows: its own for a built-in, the typical one of its kind for a custom exercise. */
    fun forExercise(exercise: Exercise): Motion? = sourceId(exercise)?.let(::forId)

    /** The built-in exercise whose animation [exercise] uses, or null when it has none. */
    fun sourceId(exercise: Exercise): String? {
        if (exercise.builtIn) return exercise.id.takeIf { it in builtIn }
        // A timed or cardio custom exercise could be anything: no guess.
        if (exercise.kind == ExerciseKind.CARDIO || exercise.kind == ExerciseKind.DURATION) return null
        val muscles = exercise.primary.ifEmpty { exercise.secondary }
        return Muscle.entries.firstNotNullOfOrNull { m -> if (m in muscles) typical[m]?.get(exercise.equipment) else null }
    }
}
