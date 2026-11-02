package com.enjoyyourtime.globalfitness.food

import com.enjoyyourtime.globalfitness.data.Sex
import com.enjoyyourtime.globalfitness.food.domain.BmrSource
import com.enjoyyourtime.globalfitness.food.domain.FoodMath
import com.enjoyyourtime.globalfitness.food.domain.GoalAim
import com.enjoyyourtime.globalfitness.food.domain.GoalInputs
import com.enjoyyourtime.globalfitness.food.domain.ManualGoal
import com.enjoyyourtime.globalfitness.food.domain.NutritionGoals
import com.enjoyyourtime.globalfitness.food.model.Meal
import com.enjoyyourtime.globalfitness.food.model.Nutrients
import com.enjoyyourtime.globalfitness.food.model.Portion
import com.enjoyyourtime.globalfitness.food.model.PortionKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

class FoodMathTest {
    @Test
    fun mealByTimeOfDay() {
        assertEquals(Meal.BREAKFAST, FoodMath.mealFor(LocalTime.of(7, 30)))
        assertEquals(Meal.BREAKFAST, FoodMath.mealFor(LocalTime.of(10, 59)))
        assertEquals(Meal.LUNCH, FoodMath.mealFor(LocalTime.of(11, 0)))
        assertEquals(Meal.LUNCH, FoodMath.mealFor(LocalTime.of(15, 59)))
        assertEquals(Meal.SNACK, FoodMath.mealFor(LocalTime.of(16, 0)))
        assertEquals(Meal.SNACK, FoodMath.mealFor(LocalTime.of(19, 29)))
        assertEquals(Meal.DINNER, FoodMath.mealFor(LocalTime.of(19, 30)))
        assertEquals(Meal.DINNER, FoodMath.mealFor(LocalTime.of(23, 59)))
    }

    @Test
    fun portionsAndNutrientsScaleWithTheAmount() {
        assertEquals(525.0, FoodMath.amountOf(Portion(PortionKey.PLATE, 350.0), 1.5), 1e-9)
        assertEquals(33.3, FoodMath.amountOf(Portion(PortionKey.SLICE, 33.333), 1.0), 1e-9)
        val per100 = Nutrients(kcal = 158.0, protein = 7.5, sugars = null, salt = 0.6)
        val n = FoodMath.nutrientsFor(per100, 350.0)
        assertEquals(553.0, n.kcal!!, 1e-9)
        assertEquals(26.25, n.protein!!, 1e-9)
        assertNull("unknown stays unknown", n.sugars)
        assertEquals(2.1, n.salt!!, 1e-9)
        val back = FoodMath.per100Of(n, 350.0)
        assertEquals(158.0, back.kcal!!, 1e-9)
        assertEquals(Nutrients.NONE, FoodMath.per100Of(n, 0.0))
    }

    @Test
    fun portionCountsStepByWholesAndHalvesBelowOne() {
        assertEquals(1.0, FoodMath.morePortions(0.5), 0.0)
        assertEquals(2.0, FoodMath.morePortions(1.0), 0.0)
        assertEquals(2.0, FoodMath.morePortions(1.5), 0.0)
        assertEquals(1.0, FoodMath.lessPortions(2.0), 0.0)
        assertEquals(1.0, FoodMath.lessPortions(1.5), 0.0)
        assertEquals(0.5, FoodMath.lessPortions(1.0), 0.0)
        assertEquals(FoodMath.MIN_PORTIONS, FoodMath.lessPortions(0.5), 0.0)
    }

    @Test
    fun nutrientSumsKeepUnknownsApart() {
        val a = Nutrients(kcal = 100.0, sugars = null, fat = 2.0)
        val b = Nutrients(kcal = 50.0, sugars = 3.0, fat = null)
        assertEquals(Nutrients(kcal = 150.0, sugars = 3.0, fat = 2.0), a + b)
        assertEquals(Nutrients.NONE, Nutrients.sum(emptyList()))
    }

    @Test
    fun theDayGroupsEntriesByMealInTheZoneTheyWereEatenIn() {
        val date = LocalDate.of(2026, 10, 31)
        fun at(h: Int, m: Int = 0, offsetH: Int = 1) = date.atTime(h, m).toInstant(ZoneOffset.ofHours(offsetH)).toEpochMilli()
        val entries = listOf(
            testEntry(id = "b1", eatenAt = at(8), meal = Meal.BREAKFAST, nutrients = Nutrients(kcal = 300.0, protein = 10.0)),
            testEntry(id = "l2", eatenAt = at(14, 10), meal = Meal.LUNCH, nutrients = Nutrients(kcal = 200.0)),
            testEntry(id = "l1", eatenAt = at(14), meal = Meal.LUNCH, nutrients = Nutrients(kcal = 500.0, protein = 30.0)),
            // 23:30 in New York is already the next day in Madrid; it belongs to the day it was eaten.
            testEntry(id = "ny", eatenAt = at(23, 30, offsetH = -4), meal = Meal.DINNER, zoneOffsetSeconds = -4 * 3600, nutrients = Nutrients(kcal = 700.0)),
            testEntry(id = "next", eatenAt = at(1) + 86_400_000L, meal = Meal.BREAKFAST, nutrients = Nutrients(kcal = 999.0)),
        )
        val day = FoodMath.dayLog(entries, date)
        assertEquals(listOf(Meal.BREAKFAST, Meal.LUNCH, Meal.DINNER), day.meals.keys.toList())
        assertEquals(listOf("l1", "l2"), day.meals.getValue(Meal.LUNCH).map { it.id })
        assertEquals(700.0, day.mealTotals.getValue(Meal.LUNCH).kcal!!, 1e-9)
        assertEquals(1700.0, day.total.kcal!!, 1e-9)
        assertEquals(40.0, day.total.protein!!, 1e-9)
        assertEquals(mapOf(date to 1700.0, date.plusDays(1) to 999.0), FoodMath.kcalByDay(entries))
        assertTrue(FoodMath.dayLog(entries, date.minusDays(3)).isEmpty)
    }

    // ---- goals ------------------------------------------------------------------------------------

    @Test
    fun mifflinStJeor() {
        // 80 kg, 180 cm, 30 years: 800 + 1125 - 150 + 5 = 1780 (men) / 1614 (women).
        assertEquals(1780.0, NutritionGoals.mifflinStJeor(Sex.MALE, 80.0, 180.0, 30), 1e-9)
        assertEquals(1614.0, NutritionGoals.mifflinStJeor(Sex.FEMALE, 80.0, 180.0, 30), 1e-9)
    }

    private fun inputs(weight: Double? = 80.0, scaleBmr: Double? = null, active: Double? = null, aim: GoalAim = GoalAim.MAINTAIN) =
        GoalInputs(Sex.MALE, 30, 180.0, weight, scaleBmr, active, aim)

    @Test
    fun autoGoalUsesTheDaysActivityWhenKnown() {
        val g = NutritionGoals.auto(inputs(active = 400.0))!!
        assertEquals(BmrSource.FORMULA, g.bmrSource)
        assertEquals(1780.0 * 1.2, g.base, 1e-9)
        assertEquals(400.0, g.active!!, 1e-9)
        assertEquals(1780.0 * 1.2 + 400, g.maintenance, 1e-9)
        assertEquals(2540.0, g.kcal, 1e-9) // 2536 → nearest 10
    }

    @Test
    fun autoGoalAssumesLightActivityWithoutHealthConnect() {
        val g = NutritionGoals.auto(inputs())!!
        assertNull(g.active)
        assertEquals(1780.0 * 1.375, g.maintenance, 1e-9)
        assertEquals(2450.0, g.kcal, 1e-9) // 2447.5
    }

    @Test
    fun theScalesBmrWinsAndTheAimAdjusts() {
        val lose = NutritionGoals.auto(inputs(scaleBmr = 1700.0, active = 0.0, aim = GoalAim.LOSE))!!
        assertEquals(BmrSource.SCALE, lose.bmrSource)
        assertEquals(1730.0, lose.kcal, 1e-9) // 1700 × 1.2 × 0.85 = 1734
        val gain = NutritionGoals.auto(inputs(scaleBmr = 1700.0, active = 0.0, aim = GoalAim.GAIN))!!
        assertEquals(2240.0, gain.kcal, 1e-9) // 2244
        assertNull("no scale BMR and no weight: the sheet asks for the weight", NutritionGoals.auto(inputs(weight = null)))
        assertEquals("a scale BMR needs no weight", BmrSource.SCALE, NutritionGoals.auto(inputs(weight = null, scaleBmr = 1600.0))!!.bmrSource)
    }

    @Test
    fun targetsAndLimits() {
        val t = NutritionGoals.targets(2000.0, 80.0, GoalAim.MAINTAIN)
        assertEquals(128.0, t.protein, 1e-9) // 1.6 g/kg
        assertEquals(66.7, t.fat, 1e-9) // 30 % of 2000 kcal / 9
        assertEquals(222.0, t.carbs, 0.2) // (2000 − 512 − 600) / 4
        assertEquals(50.0, t.sugarsMax, 1e-9)
        assertEquals(22.2, t.satFatMax, 1e-9)
        assertEquals(5.0, t.saltMax, 1e-9)
        assertEquals(25.0, t.fiberMin, 1e-9)
        assertEquals(144.0, NutritionGoals.targets(2000.0, 80.0, GoalAim.GAIN).protein, 1e-9) // 1.8 g/kg
        assertEquals("without a weight: 20 % of the calories", 100.0, NutritionGoals.targets(2000.0, null, GoalAim.MAINTAIN).protein, 1e-9)
        assertEquals("carbs never negative", 0.0, NutritionGoals.targets(600.0, 120.0, GoalAim.GAIN).carbs, 1e-9)
    }

    @Test
    fun manualGoalKeepsTheUsersNumbers() {
        val t = NutritionGoals.manual(ManualGoal(1800.0, protein = 150.0), weightKg = 70.0)
        assertEquals(1800.0, t.kcal, 1e-9)
        assertEquals(150.0, t.protein, 1e-9)
        assertEquals(60.0, t.fat, 1e-9)
        assertEquals((1800 - 600 - 540) / 4.0, t.carbs, 0.1)
        assertEquals(200.0, NutritionGoals.manual(ManualGoal(1800.0, carbs = 200.0), 70.0).carbs, 1e-9)
    }
}
