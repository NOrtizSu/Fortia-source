package com.enjoyyourtime.globalfitness.ui.training.motion

/**
 * Animations of the exercises added in 1.6, in catalog order (joined into [MotionCatalog]): the
 * patterns of MotionVariants, MotionFunctional, MotionHiit and MotionMobility. The two new sports
 * are in [sportsMotions].
 */
internal val moreMotions: Map<String, () -> Motion> = mapOf(
    // Machines
    "iso_lateral_chest_press" to { isoLateralChestPress() },
    "high_row_machine" to { highRowMachine() },
    "t_bar_row_machine" to { tBarRowMachine() },
    "standing_leg_curl_machine" to { standingLegCurl() },
    // Cable stations
    "reverse_grip_lat_pulldown" to { reverseGripPulldown() },
    "cable_chest_press" to { cableChestPress() },
    "cable_reverse_fly" to { cableReverseFly() },
    "cable_y_raise" to { cableYRaise() },
    "cable_upright_row" to { cableUprightRow() },
    // Smith machine
    "smith_reverse_lunge" to { smithReverseLunge() },
    // Barbell
    "landmine_squat" to { landmineSquat() },
    "overhead_squat" to { overheadSquat() },
    "thruster" to { thruster() },
    "push_press" to { pushPress() },
    "power_clean" to { powerClean() },
    "hang_clean" to { hangClean() },
    "power_snatch" to { powerSnatch() },
    // Dumbbells
    "dumbbell_floor_press" to { dumbbellFloorPress() },
    "incline_dumbbell_fly" to { inclineDumbbellFly() },
    "walking_lunge" to { walkingLunge() },
    "dumbbell_snatch" to { dumbbellSnatch() },
    // Kettlebells
    "kettlebell_high_pull" to { kettlebellHighPull() },
    // Bodyweight
    "archer_push_up" to { archerPushUp() },
    "ring_dip" to { ringDip() },
    "neutral_grip_pull_up" to { neutralGripPullUp() },
    "suspension_row" to { suspensionRow() },
    "sissy_squat" to { sissySquat() },
    "toes_to_bar" to { toesToBar() },
    "ghd_sit_up" to { ghdSitUp() },
    // Conditioning and HIIT
    "jump_rope" to { jumpRope() },
    "jumping_jack" to { jumpingJack() },
    "high_knees" to { runOnTheSpot(high = true) },
    "butt_kicks" to { runOnTheSpot(high = false) },
    "box_jump" to { boxJump() },
    "tuck_jump" to { tuckJump() },
    "skater_jump" to { skaterJump() },
    "bear_crawl" to { bearCrawl() },
    "shadow_boxing" to { shadowBoxing() },
    "step_aerobics" to { stepAerobics() },
    "battle_ropes" to { battleRopes() },
    "sled_push" to { sledPush() },
    "sled_drag" to { sledDrag() },
    // Medicine ball and sandbag
    "wall_ball" to { wallBall() },
    "medicine_ball_slam" to { medicineBallSlam() },
    "medicine_ball_rotational_throw" to { medicineBallRotationalThrow() },
    "sandbag_carry" to { sandbagCarry() },
    // Stretching, mobility and foam rolling
    "ankle_dorsiflexion_mobilization" to { ankleMobilization() },
    "hip_90_90" to { hip9090() },
    "deep_squat_hold" to { deepSquatHold() },
    "hip_cars" to { hipCars() },
    "shoulder_cars" to { shoulderCars() },
    "thread_the_needle" to { threadTheNeedle() },
    "cobra_stretch" to { cobraStretch() },
    "downward_dog" to { downwardDog() },
    "standing_quad_stretch" to { standingQuadStretch() },
    "lying_spinal_twist" to { lyingSpinalTwist() },
    "figure_four_stretch" to { figureFourStretch() },
    "wrist_mobility" to { wristMobility() },
    "foam_roll_quads" to { foamRollQuads() },
    "foam_roll_hamstrings" to { foamRollLegBack(calves = false) },
    "foam_roll_calves" to { foamRollLegBack(calves = true) },
    "foam_roll_glutes" to { foamRollGlutes() },
    "foam_roll_it_band" to { foamRollSide(lats = false) },
    "foam_roll_upper_back" to { foamRollUpperBack() },
    "foam_roll_lats" to { foamRollSide(lats = true) },
    // Cardio machines
    "treadmill_walk" to { treadmillWalk(incline = false) },
    "incline_treadmill_walk" to { treadmillWalk(incline = true) },
    "curved_treadmill" to { curvedTreadmill() },
)
