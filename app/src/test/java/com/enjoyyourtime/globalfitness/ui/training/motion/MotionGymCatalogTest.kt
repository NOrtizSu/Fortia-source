package com.enjoyyourtime.globalfitness.ui.training.motion

import com.enjoyyourtime.globalfitness.UiText
import com.enjoyyourtime.globalfitness.training.catalog.ExerciseCatalog
import com.enjoyyourtime.globalfitness.training.model.Equipment
import com.enjoyyourtime.globalfitness.training.model.Exercise
import com.enjoyyourtime.globalfitness.training.model.ExerciseKind
import com.enjoyyourtime.globalfitness.training.model.Muscle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The animations of the larger gym catalog and the defaults a custom exercise borrows from it. */
class MotionGymCatalogTest {
    @Test
    fun theGymMotionsAreCatalogExercisesNotAlreadyMapped() {
        val ids = ExerciseCatalog.all.map { it.id }.toSet()
        assertTrue(gymMotions.keys - ids == emptySet<String>())
        assertEquals(gymMotions.keys, gymMotions.keys intersect MotionCatalog.mappedIds)
        // Joined with "+", a repeated id would silently replace the original animation.
        assertEquals(emptySet<String>(), gymMotions.keys intersect MotionCatalog.baseIds)
        assertEquals(emptySet<String>(), sportsMotions.keys intersect MotionCatalog.baseIds)
        assertEquals(emptySet<String>(), gymMotions.keys intersect sportsMotions.keys)
    }

    @Test
    fun theNewerMotionsAreCatalogExercisesNotAlreadyMapped() {
        val ids = ExerciseCatalog.all.map { it.id }.toSet()
        assertEquals(emptySet<String>(), moreMotions.keys - ids)
        for (other in listOf(MotionCatalog.baseIds, sportsMotions.keys, gymMotions.keys)) {
            assertEquals(emptySet<String>(), moreMotions.keys intersect other)
        }
        // The 1.6 additions: conditioning, functional, mobility and foam rolling, variants and the treadmill walks.
        for (id in listOf("treadmill_walk", "incline_treadmill_walk", "jump_rope", "power_clean", "foam_roll_quads", "archer_push_up")) {
            assertTrue(id, id in moreMotions.keys)
        }
    }

    @Test
    fun theLatestMotionsAreCatalogExercisesNotAlreadyMapped() {
        val ids = ExerciseCatalog.all.map { it.id }.toSet()
        assertEquals(emptySet<String>(), latestMotions.keys - ids)
        for (other in listOf(MotionCatalog.baseIds, sportsMotions.keys, gymMotions.keys, moreMotions.keys)) {
            assertEquals(emptySet<String>(), latestMotions.keys intersect other)
        }
        // The 1.8 additions: calisthenics, lifts and strongman, suspension / balance / Pilates, combat drills, and the new sports.
        for (id in listOf("bar_muscle_up", "front_lever", "clean_and_jerk", "tyre_flip", "suspension_pike", "bosu_squat", "pilates_teaser", "heavy_bag", "sprawl")) {
            assertTrue(id, id in latestMotions.keys)
        }
        for (id in listOf("alpine_skiing", "surfing", "rowing", "table_tennis", "ice_skating", "open_water_swimming")) assertTrue(id, id in sportsMotions.keys)
    }

    @Test
    fun aDomeStandsOnItsBase() {
        val dome = Dome(v(0f, 0.03f), radius = 0.315f, height = 0.21f)
        assertEquals(0.21f, dome.surface(0f), 1e-4f)
        assertEquals(0f, dome.surface(0.315f), 1e-4f)
        assertTrue(dome.surface(0.15f) in 0f..0.21f)
        // Drawn as one closed outline, inside the picture's view of the base and the top.
        val scene = SceneBuilder(Camera(24f, 8f), emptySet()).build(Frame(FrameBuilder().apply { body.torso(v(0f, 1f)); body.standOn(0f) }.build().pose, listOf(dome), 0f))
        val fills = scene.ops.filter { it.ink == Ink.PAD && it.type == OpType.FILL }
        assertEquals(1, fills.size)
        val ys = fills.single().points.filterIndexed { i, _ -> i % 2 == 1 }
        assertTrue(ys.max() <= 0.03f + 0.21f + 3e-3f)
        // The front of the base rim, seen from a little above, sits just below the base's centre.
        assertTrue(ys.min() >= 0.03f - 0.315f * kotlin.math.sin(rad(8f)) - 1e-3f)
    }

    @Test
    fun customExercisesBorrowTheNewTypicalMovements() {
        assertEquals("dumbbell_wrist_curl", source(Equipment.DUMBBELL, Muscle.FOREARMS))
        assertEquals("dumbbell_calf_raise", source(Equipment.DUMBBELL, Muscle.CALVES))
        assertEquals("single_leg_calf_raise", source(Equipment.BODYWEIGHT, Muscle.CALVES))
        assertEquals("cable_hip_adduction", source(Equipment.CABLE, Muscle.ADDUCTORS))
        assertEquals("lying_neck_flexion", source(Equipment.BODYWEIGHT, Muscle.NECK))
        assertEquals("kettlebell_row", source(Equipment.KETTLEBELL, Muscle.LATS))
        assertEquals("band_curl", source(Equipment.BAND, Muscle.BICEPS))
        // Unchanged: no band movement for the calves.
        assertEquals(null, source(Equipment.BAND, Muscle.CALVES))
    }

    @Test
    fun aCustomExerciseNeverBorrowsAStretch() {
        val stretches = ExerciseCatalog.all.filter { it.mobility }.map { it.id }.toSet()
        for (m in Muscle.entries) {
            for (e in Equipment.entries) {
                val id = source(e, m) ?: continue
                assertFalse("$m with $e borrows the stretch $id", id in stretches)
            }
        }
    }

    private fun source(equipment: Equipment, muscle: Muscle): String? = MotionCatalog.sourceId(
        Exercise("u:test", UiText.Raw("Mine"), ExerciseKind.WEIGHT_REPS, equipment, setOf(muscle), emptySet(), builtIn = false),
    )
}
