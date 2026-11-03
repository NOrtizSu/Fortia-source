package com.enjoyyourtime.globalfitness.ui.training

import androidx.compose.ui.graphics.vector.addPathNodes
import com.enjoyyourtime.globalfitness.training.catalog.ExerciseCatalog
import com.enjoyyourtime.globalfitness.training.data.TrainingRepository
import com.enjoyyourtime.globalfitness.training.model.Equipment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseIconsTest {
    @Test
    fun everyGlyphPathParses() {
        for ((name, path) in ExerciseIcons.paths) {
            assertTrue("$name has no path commands", addPathNodes(path).size > 2)
        }
    }

    @Test
    fun everyEquipmentHasItsOwnGlyph() {
        val names = Equipment.entries.map { it.icon.name }
        assertEquals("repeated glyph: $names", Equipment.entries.size, names.toSet().size)
        val paths = names.map { ExerciseIcons.paths.getValue(it) }
        assertEquals("repeated path data", paths.size, paths.toSet().size)
        for (e in Equipment.entries) assertTrue("$e glyph is empty", e.icon.root.size > 0)
    }

    @Test
    fun equipmentDoesNotReuseTheTrainingTabDumbbell() {
        val tab = TrainingIcons.paths.getValue("FitnessCenter")
        for (e in Equipment.entries) assertFalse("$e reuses the tab glyph", ExerciseIcons.paths.getValue(e.icon.name) == tab)
    }

    @Test
    fun walkingActivitiesShowAWalker() {
        val exercises = TrainingRepository.buildExercises(ExerciseCatalog.all, emptyList(), emptyMap())
        for (id in listOf("outdoor_walk", "treadmill_walk", "incline_treadmill_walk", "nordic_walking")) {
            assertSame(id, ExerciseIcons.Walk, exercises.getValue(id).activityIcon)
        }
        assertSame(ExerciseIcons.Run, exercises.getValue("treadmill").activityIcon)
        assertSame(ExerciseIcons.Run, exercises.getValue("curved_treadmill").activityIcon)
        assertSame(ExerciseIcons.Stairs, exercises.getValue("stair_climbing").activityIcon)
    }

    @Test
    fun glyphsStayOnTheGrid() {
        // Every absolute coordinate pair of the glyphs lies on the 24-unit grid (a badly scaled
        // Material Symbols path would land around 960).
        val number = Regex("-?\\d+(\\.\\d+)?")
        for ((name, path) in ExerciseIcons.paths) {
            for (n in number.findAll(path)) {
                assertTrue("$name: ${n.value}", n.value.toDouble() in -24.0..24.0)
            }
        }
    }
}
