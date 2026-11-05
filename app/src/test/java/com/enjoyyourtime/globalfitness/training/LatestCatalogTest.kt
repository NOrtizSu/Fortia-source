package com.enjoyyourtime.globalfitness.training

import androidx.health.connect.client.records.ExerciseSegment
import com.enjoyyourtime.globalfitness.training.catalog.ExerciseAliases
import com.enjoyyourtime.globalfitness.training.catalog.ExerciseCatalog
import com.enjoyyourtime.globalfitness.training.catalog.ExerciseGuides
import com.enjoyyourtime.globalfitness.training.catalog.RoutineCatalog
import com.enjoyyourtime.globalfitness.training.catalog.SportsCatalog
import com.enjoyyourtime.globalfitness.training.model.Equipment
import com.enjoyyourtime.globalfitness.training.model.ExerciseKind
import com.enjoyyourtime.globalfitness.ui.training.motion.MotionCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The exercises and sports added in 1.8: complete entries, the way each is logged and exported, and the catalog's size. */
class LatestCatalogTest {
    private val all = ExerciseCatalog.all

    @Test
    fun theCatalogHasTheNumbersOfTheStoreListing() {
        assertEquals(384, all.count { it.equipment != Equipment.SPORT })
        assertEquals(34, SportsCatalog.all.size)
        assertEquals(89, GROUPS.values.sumOf { it.size })
        assertEquals(12, NEW_SPORTS.size)
        // The new groups and sports are all in the catalog, once.
        val ids = all.map { it.id }
        for (id in GROUPS.values.flatten() + NEW_SPORTS) assertEquals(id, 1, ids.count { it == id })
    }

    @Test
    fun everyNewEntryIsComplete() {
        for (id in GROUPS.values.flatten() + NEW_SPORTS) {
            val e = ExerciseCatalog[id]
            assertNotNull(id, e)
            e!!
            assertNotNull("$id has no guide", ExerciseGuides[id])
            assertNotNull("$id has no search words", ExerciseAliases[id])
            assertTrue("$id has no animation", id in MotionCatalog.mappedIds)
            assertTrue("$id has no muscles", e.primary.isNotEmpty())
        }
    }

    @Test
    fun holdsCarriesAndRoundsAreTimedTheRestCounted() {
        val timed = setOf(
            "wall_handstand", "handstand", "tuck_front_lever", "front_lever", "tuck_back_lever", "back_lever",
            "tuck_planche", "straddle_planche", "human_flag", "tuck_l_sit", "ring_support_hold",
            "kettlebell_halo", "kettlebell_overhead_carry", "yoke_carry", "bosu_balance", "stir_the_pot",
            "single_leg_balance", "crow_pose", "pilates_hundred", "pilates_swimming",
            "heavy_bag", "speed_bag", "double_end_bag", "pad_work", "shadow_hooks", "shadow_uppercuts", "dumbbell_punches",
            "bag_kicks", "knee_strikes",
        )
        for (id in GROUPS.values.flatten()) {
            val e = ExerciseCatalog[id]!!
            if (id in timed) {
                assertEquals(id, ExerciseKind.DURATION, e.kind)
            } else {
                assertTrue("$id: ${e.kind}", e.kind == ExerciseKind.WEIGHT_REPS || e.kind == ExerciseKind.BODYWEIGHT_REPS)
            }
            // Only the balance drills and the halo are control work; the rest counts as training.
            assertEquals(id, id in CONTROL, e.mobility)
        }
        // Loaded lifts log weight × reps; the counted bodyweight skills reps.
        for (id in listOf("clean_and_jerk", "trap_bar_deadlift", "kettlebell_bottoms_up_press", "devil_press", "log_press", "atlas_stone", "tyre_flip")) {
            assertEquals(id, ExerciseKind.WEIGHT_REPS, ExerciseCatalog[id]!!.kind)
        }
        for (id in listOf("bar_muscle_up", "double_unders", "burpee_box_jump_over", "suspension_pike", "pilates_teaser", "sprawl")) {
            assertEquals(id, ExerciseKind.BODYWEIGHT_REPS, ExerciseCatalog[id]!!.kind)
        }
    }

    @Test
    fun theNewGearUsesTheExistingEquipmentKinds() {
        // No new equipment codes (an older app would read them as "other" and could write them back).
        assertEquals(11, Equipment.entries.size)
        for (id in GROUPS.getValue("suspension and balance").filter { it.startsWith("suspension_") }) assertEquals(id, Equipment.BODYWEIGHT, ExerciseCatalog[id]!!.equipment)
        for (id in listOf("bosu_squat", "bosu_push_up", "bosu_balance", "swiss_ball_crunch", "swiss_ball_pike", "swiss_ball_hamstring_curl", "swiss_ball_pass", "stir_the_pot")) {
            assertEquals(id, Equipment.OTHER, ExerciseCatalog[id]!!.equipment)
        }
        for (id in listOf("bar_muscle_up", "ring_muscle_up", "skin_the_cat", "ring_push_up")) assertEquals(id, Equipment.BODYWEIGHT, ExerciseCatalog[id]!!.equipment)
        for (id in listOf("heavy_bag", "speed_bag", "double_end_bag", "pad_work", "yoke_carry", "log_press", "atlas_stone", "tyre_flip", "sandbag_to_shoulder")) {
            assertEquals(id, Equipment.OTHER, ExerciseCatalog[id]!!.equipment)
        }
        assertEquals(Equipment.BARBELL, ExerciseCatalog["trap_bar_deadlift"]!!.equipment)
        val bells = listOf(
            "kettlebell_halo", "kettlebell_bottoms_up_press", "double_kettlebell_front_squat", "single_arm_kettlebell_swing",
            "american_kettlebell_swing", "kettlebell_overhead_carry",
        )
        for (id in bells) assertEquals(id, Equipment.KETTLEBELL, ExerciseCatalog[id]!!.equipment)
    }

    @Test
    fun theBalanceDrillsAndTheHaloAreControlWorkNotHardSets() {
        // Timed and exported as stretching, like the mobility drills: no hard sets, recovery or records (MobilityTest).
        for (id in CONTROL) {
            val e = ExerciseCatalog[id]!!
            assertTrue(id, e.mobility)
            assertEquals(id, ExerciseKind.DURATION, e.kind)
            assertEquals(id, ExerciseSegment.EXERCISE_SEGMENT_TYPE_STRETCHING, e.hcSegment)
        }
        // The single-leg deadlift reach was the single-leg Romanian deadlift again: dropped before release.
        assertNull(ExerciseCatalog["single_leg_rdl_reach"])
    }

    @Test
    fun healthConnectSegmentsNameTheMovement() {
        fun segment(id: String) = ExerciseCatalog[id]!!.hcSegment
        for (id in listOf("heavy_bag", "speed_bag", "double_end_bag", "pad_work", "shadow_hooks", "shadow_uppercuts", "dumbbell_punches")) {
            assertEquals(id, ExerciseSegment.EXERCISE_SEGMENT_TYPE_PUNCH, segment(id))
        }
        for (id in listOf("single_arm_kettlebell_swing", "american_kettlebell_swing")) assertEquals(id, ExerciseSegment.EXERCISE_SEGMENT_TYPE_KETTLEBELL_SWING, segment(id))
        assertEquals(ExerciseSegment.EXERCISE_SEGMENT_TYPE_JUMP_ROPE, segment("double_unders"))
        assertEquals(ExerciseSegment.EXERCISE_SEGMENT_TYPE_PULL_UP, segment("archer_pull_up"))
        assertEquals(ExerciseSegment.EXERCISE_SEGMENT_TYPE_DEADLIFT, segment("trap_bar_deadlift"))
        // A Pilates segment would not be accepted in the strength session a mat exercise is saved in.
        for (e in all.filter { it.equipment != Equipment.SPORT }) assertTrue(e.id, e.hcSegment != ExerciseSegment.EXERCISE_SEGMENT_TYPE_PILATES)
        // Open water has its own swimming segment.
        assertEquals(ExerciseSegment.EXERCISE_SEGMENT_TYPE_SWIMMING_OPEN_WATER, ExerciseCatalog["open_water_swimming"]!!.hcSegment)
    }

    @Test
    fun bodyWeightSharesFollowTheExercisesTheyExtend() {
        fun share(id: String) = ExerciseCatalog[id]!!.bodyweightFactor
        for (id in listOf("bar_muscle_up", "ring_muscle_up", "archer_pull_up", "typewriter_pull_up")) assertEquals(id, share("pull_up"), share(id))
        for (id in listOf("ring_push_up", "clap_push_up")) assertEquals(id, share("push_up"), share(id))
        for (id in listOf("one_arm_push_up", "pseudo_planche_push_up", "wall_handstand_push_up", "suspension_chest_press", "bosu_push_up")) assertEquals(id, null, share(id))
    }

    @Test
    fun theNewRoutinesUseTheNewExercises() {
        // The kettlebell routine needs a single bell.
        val kettlebell = RoutineCatalog["kettlebell"]!!.exercises.map { it.exerciseId }
        assertTrue(kettlebell.all { ExerciseCatalog[it]!!.equipment == Equipment.KETTLEBELL })
        assertTrue(kettlebell.none { it.startsWith("double_") })
        assertTrue(kettlebell.containsAll(listOf("kettlebell_halo", "kettlebell_overhead_carry")))
        // The skills come first, while fresh, before the pull-ups and dips.
        val calisthenics = RoutineCatalog["calisthenics"]!!.exercises.map { it.exerciseId }
        assertTrue(calisthenics.containsAll(listOf("tuck_front_lever", "wall_handstand", "tuck_l_sit", "hollow_body_rock")))
        for (skill in listOf("tuck_front_lever", "wall_handstand")) {
            for (strength in listOf("pull_up", "parallel_bar_dip")) assertTrue(skill, calisthenics.indexOf(skill) < calisthenics.indexOf(strength))
        }
    }

    private companion object {
        val GROUPS = mapOf(
            "calisthenics" to listOf(
                "bar_muscle_up", "ring_muscle_up", "wall_handstand", "handstand", "wall_handstand_push_up", "tuck_front_lever",
                "front_lever", "tuck_back_lever", "back_lever", "tuck_planche", "straddle_planche", "human_flag", "tuck_l_sit",
                "archer_pull_up", "typewriter_pull_up", "one_arm_push_up", "shrimp_squat", "skin_the_cat", "ring_support_hold",
                "ring_push_up", "hollow_body_rock", "pseudo_planche_push_up", "clap_push_up", "hanging_windshield_wiper",
            ),
            "lifts and strongman" to listOf(
                "push_jerk", "split_jerk", "clean_and_jerk", "squat_clean", "hang_power_snatch", "squat_snatch", "snatch_balance",
                "clean_pull", "snatch_pull", "trap_bar_deadlift", "kettlebell_halo", "kettlebell_bottoms_up_press",
                "double_kettlebell_front_squat", "single_arm_kettlebell_swing", "american_kettlebell_swing", "kettlebell_overhead_carry",
                "renegade_row", "devil_press", "man_maker", "dumbbell_thruster", "double_unders", "burpee_box_jump_over",
                "yoke_carry", "log_press", "atlas_stone", "tyre_flip", "sandbag_to_shoulder",
            ),
            "suspension and balance" to listOf(
                "suspension_chest_press", "suspension_y_raise", "suspension_curl", "suspension_triceps_extension",
                "suspension_pistol_squat", "suspension_atomic_push_up", "suspension_pike", "suspension_hamstring_curl",
                "suspension_fallout", "bosu_squat", "bosu_push_up", "bosu_balance", "swiss_ball_crunch", "swiss_ball_pike",
                "swiss_ball_hamstring_curl", "swiss_ball_pass", "stir_the_pot", "single_leg_balance",
                "crow_pose", "v_up", "pilates_hundred", "pilates_roll_up", "pilates_single_leg_stretch",
                "pilates_double_leg_stretch", "pilates_swimming", "pilates_teaser", "pilates_side_kick", "pilates_shoulder_bridge",
            ),
            "combat" to listOf(
                "heavy_bag", "speed_bag", "double_end_bag", "pad_work", "shadow_hooks", "shadow_uppercuts", "dumbbell_punches",
                "bag_kicks", "knee_strikes", "sprawl",
            ),
        )
        val CONTROL = setOf("single_leg_balance", "bosu_balance", "kettlebell_halo")
        val NEW_SPORTS = listOf(
            "alpine_skiing", "cross_country_skiing", "snowboarding", "surfing", "rowing", "kayaking", "open_water_swimming",
            "rugby", "table_tennis", "squash", "inline_skating", "ice_skating",
        )
    }
}
