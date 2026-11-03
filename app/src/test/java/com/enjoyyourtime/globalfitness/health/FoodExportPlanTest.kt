package com.enjoyyourtime.globalfitness.health

import androidx.health.connect.client.records.MealType
import com.enjoyyourtime.globalfitness.food.data.ExportLedgerEntry
import com.enjoyyourtime.globalfitness.food.model.Meal
import com.enjoyyourtime.globalfitness.food.model.Nutrients
import com.enjoyyourtime.globalfitness.food.testEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodExportPlanTest {
    private fun ledger(id: String, version: Long) = id to ExportLedgerEntry(id, version, "hc-$id", 0, "install")

    @Test
    fun newAndEditedEntriesAreWrittenDeletedOnesRemoved() {
        val entries = listOf(testEntry(id = "new", updatedAt = 5), testEntry(id = "same", updatedAt = 7), testEntry(id = "edited", updatedAt = 9))
        val plan = FoodExportPlanner.plan(entries, mapOf(ledger("same", 7), ledger("edited", 8), ledger("gone", 3)), writeEnabled = true)
        assertEquals(listOf("new", "edited"), plan.writes.map { it.entryId })
        assertEquals(listOf("gone"), plan.deletes)
        assertEquals(3, plan.savedCount)
    }

    @Test
    fun switchOffStopsWritingButDeletionsStillGo() {
        val plan = FoodExportPlanner.plan(listOf(testEntry(id = "a", updatedAt = 1)), mapOf(ledger("b", 1)), writeEnabled = false)
        assertTrue(plan.writes.isEmpty())
        assertEquals(listOf("b"), plan.deletes)
        assertEquals(0, plan.savedCount)
        assertTrue(FoodExportPlanner.plan(emptyList(), emptyMap(), writeEnabled = true).isEmpty)
    }

    @Test
    fun anEntryBecomesANutritionRecord() {
        val e = testEntry(id = "e1", eatenAt = 1_700_000_000_000L, meal = Meal.DINNER, updatedAt = 42)
            .copy(nutrients = Nutrients(kcal = 300.0, protein = 12.0, carbs = 30.0, sugars = null, fat = 9.0, satFat = 2.0, fiber = 1.5, salt = 1.0))
        val p = FoodExportPlanner.planned(e)
        assertEquals("fortia-meal-e1", p.clientRecordId)
        assertEquals(42L, p.version)
        assertEquals(MealType.MEAL_TYPE_DINNER, p.mealType)
        assertEquals(e.eatenAt + FoodExportPlanner.RECORD_SPAN_MS, p.end)
        assertEquals("sodium = salt / 2.5", 0.4, p.sodiumG!!, 1e-9)
        assertNull("unknown values are left out", p.sugarG)
        val r = FoodExportPlanner.buildRecord(p)
        assertEquals(300.0, r.energy!!.inKilocalories, 1e-9)
        assertEquals(12.0, r.protein!!.inGrams, 1e-9)
        assertEquals(30.0, r.totalCarbohydrate!!.inGrams, 1e-9)
        assertEquals(9.0, r.totalFat!!.inGrams, 1e-9)
        assertEquals(2.0, r.saturatedFat!!.inGrams, 1e-9)
        assertEquals(1.5, r.dietaryFiber!!.inGrams, 1e-9)
        assertEquals(0.4, r.sodium!!.inGrams, 1e-9)
        assertNull(r.sugar)
        assertEquals("Paella", r.name)
        assertEquals(MealType.MEAL_TYPE_DINNER, r.mealType)
        assertEquals(3600, r.startZoneOffset!!.totalSeconds)
        assertEquals("fortia-meal-e1", r.metadata.clientRecordId)
        assertEquals(42L, r.metadata.clientRecordVersion)
    }

    @Test
    fun theValuesBeyondTheLabelGoToTheirFieldsInGrams() {
        val e = testEntry(id = "e2").copy(
            nutrients = Nutrients(
                kcal = 500.0, monoFat = 12.0, polyFat = 3.5, cholesterol = 80.0, potassium = 900.0, calcium = 250.0, iron = 4.2,
                magnesium = 110.0, zinc = 3.1, vitA = 450.0, vitC = 35.0, vitD = 2.5, vitE = 6.0, vitB12 = 1.8, folate = 120.0,
            ),
        )
        val r = FoodExportPlanner.buildRecord(FoodExportPlanner.planned(e))
        assertEquals(12.0, r.monounsaturatedFat!!.inGrams, 1e-9)
        assertEquals(3.5, r.polyunsaturatedFat!!.inGrams, 1e-9)
        // mg and µg become masses: 80 mg = 0.08 g, 450 µg = 0.00045 g.
        assertEquals(80.0, r.cholesterol!!.inMilligrams, 1e-6)
        assertEquals(900.0, r.potassium!!.inMilligrams, 1e-6)
        assertEquals(250.0, r.calcium!!.inMilligrams, 1e-6)
        assertEquals(4.2, r.iron!!.inMilligrams, 1e-6)
        assertEquals(110.0, r.magnesium!!.inMilligrams, 1e-6)
        assertEquals(3.1, r.zinc!!.inMilligrams, 1e-6)
        assertEquals(450.0, r.vitaminA!!.inMicrograms, 1e-3)
        assertEquals(35.0, r.vitaminC!!.inMilligrams, 1e-6)
        assertEquals(2.5, r.vitaminD!!.inMicrograms, 1e-3)
        assertEquals(6.0, r.vitaminE!!.inMilligrams, 1e-6)
        assertEquals(1.8, r.vitaminB12!!.inMicrograms, 1e-3)
        assertEquals(120.0, r.folate!!.inMicrograms, 1e-3)
        // Unknown values are left out; one Health Connect would refuse drops only that field.
        val plain = FoodExportPlanner.buildRecord(FoodExportPlanner.planned(testEntry(id = "e3")))
        assertNull(plain.calcium)
        assertNull(plain.vitaminD)
        val absurd = FoodExportPlanner.planned(testEntry(id = "e4").copy(nutrients = Nutrients(kcal = 1.0, potassium = 200_000.0, iron = -1.0, zinc = Double.NaN)))
        assertTrue(absurd.extraG.isEmpty())
        assertEquals(1.0, FoodExportPlanner.buildRecord(absurd).energy!!.inKilocalories, 1e-9)
    }

    @Test
    fun mealTypes() {
        assertEquals(MealType.MEAL_TYPE_BREAKFAST, FoodExportPlanner.mealType(Meal.BREAKFAST))
        assertEquals(MealType.MEAL_TYPE_LUNCH, FoodExportPlanner.mealType(Meal.LUNCH))
        assertEquals(MealType.MEAL_TYPE_SNACK, FoodExportPlanner.mealType(Meal.SNACK))
        assertEquals(MealType.MEAL_TYPE_UNKNOWN, FoodExportPlanner.mealType(Meal.OTHER))
        val negative = FoodExportPlanner.planned(testEntry(id = "x").copy(nutrients = Nutrients(kcal = -5.0, fat = Double.NaN)))
        assertNull(negative.energyKcal)
        assertNull(negative.fatG)
        // Sodium over Health Connect's 100 g drops that field only; the record still builds.
        val salty = FoodExportPlanner.planned(testEntry(id = "y").copy(nutrients = Nutrients(kcal = 50.0, salt = 300.0)))
        assertNull(salty.sodiumG)
        assertEquals(50.0, FoodExportPlanner.buildRecord(salty).energy!!.inKilocalories, 1e-9)
    }
}
