package com.enjoyyourtime.globalfitness.ui.navigation

import com.enjoyyourtime.globalfitness.ui.food.FoodRoutes
import com.enjoyyourtime.globalfitness.ui.training.TrainingRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationsTest {
    @Test
    fun fiveSectionsInBarOrder() {
        // Comida sits between Entreno and Progreso.
        assertEquals(listOf(TopLevel.TODAY, TopLevel.TRAINING, TopLevel.FOOD, TopLevel.PROGRESS, TopLevel.PROFILE), TopLevel.entries)
        assertEquals(listOf("hoy", "entreno", "comida", "progreso", "perfil"), TopLevel.entries.map { it.route })
    }

    @Test
    fun routeLookupFindsSectionsOnly() {
        TopLevel.entries.forEach { assertEquals(it, TopLevel.of(it.route)) }
        assertNull(TopLevel.of(null))
        // Sub-pages hide the bar: they are not sections.
        listOf(Routes.DEVICES, Routes.ACCOUNT, Routes.REMOVE_ADS, Routes.PRIVACY_POLICY, Routes.LICENSES).forEach { assertNull(TopLevel.of(it)) }
        listOf(TrainingRoutes.EXERCISE, TrainingRoutes.WORKOUT, TrainingRoutes.CATALOG, TrainingRoutes.HISTORY, TrainingRoutes.SETTINGS)
            .forEach { assertNull(it, TopLevel.of(it)) }
        FOOD_ROUTES.forEach { assertNull(it, TopLevel.of(it)) }
        // The former sections are gone (Body and Activity are Progreso's tabs, Devices a sub-page).
        assertNull(TopLevel.of("cuerpo"))
        assertNull(TopLevel.of("actividad"))
    }

    @Test
    fun routesAreUnique() {
        val all = TopLevel.entries.map { it.route } +
            listOf(Routes.DEVICES, Routes.ACCOUNT, Routes.REMOVE_ADS, Routes.PRIVACY_POLICY, Routes.LICENSES) +
            listOf(TrainingRoutes.EXERCISE, TrainingRoutes.WORKOUT, TrainingRoutes.CATALOG, TrainingRoutes.HISTORY, TrainingRoutes.SETTINGS) +
            FOOD_ROUTES
        assertEquals(all.size, all.toSet().size)
    }

    private val FOOD_ROUTES = listOf(FoodRoutes.PHOTO, FoodRoutes.SEARCH, FoodRoutes.BARCODE, FoodRoutes.CONFIRM, FoodRoutes.MANUAL, FoodRoutes.GOAL, FoodRoutes.EDIT)

    @Test
    fun barIconsBuild() {
        // The dumbbell and insights glyphs are parsed path strings: a typo would only fail at runtime.
        TopLevel.entries.forEach { section ->
            assertTrue(section.name, section.icon.root.size > 0)
            assertTrue(section.name, section.selectedIcon.root.size > 0)
        }
    }
}
