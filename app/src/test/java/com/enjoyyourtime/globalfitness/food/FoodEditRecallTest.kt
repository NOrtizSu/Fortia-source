package com.enjoyyourtime.globalfitness.food

import com.enjoyyourtime.globalfitness.food.domain.AmountStart
import com.enjoyyourtime.globalfitness.food.domain.DraftAmount
import com.enjoyyourtime.globalfitness.food.domain.DraftOption
import com.enjoyyourtime.globalfitness.food.domain.FoodDrafts
import com.enjoyyourtime.globalfitness.food.domain.FoodMath
import com.enjoyyourtime.globalfitness.food.domain.FoodRecall
import com.enjoyyourtime.globalfitness.food.domain.IngredientChoice
import com.enjoyyourtime.globalfitness.food.domain.RecipeMath
import com.enjoyyourtime.globalfitness.food.domain.ValueEdits
import com.enjoyyourtime.globalfitness.food.model.AdjustLevel
import com.enjoyyourtime.globalfitness.food.model.FoodItem
import com.enjoyyourtime.globalfitness.food.model.FoodRef
import com.enjoyyourtime.globalfitness.food.model.FoodUnit
import com.enjoyyourtime.globalfitness.food.model.IngredientAdjustment
import com.enjoyyourtime.globalfitness.food.model.Nutrient
import com.enjoyyourtime.globalfitness.food.model.Nutrients
import com.enjoyyourtime.globalfitness.food.model.Portion
import com.enjoyyourtime.globalfitness.food.model.PortionKey
import com.enjoyyourtime.globalfitness.food.model.RecipeIngredient
import com.enjoyyourtime.globalfitness.food.recognition.GramsEstimator
import com.enjoyyourtime.globalfitness.ui.food.ValueFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 1.7.0's food log: values typed by hand on the confirm screen, and a food logged before starting "as last time". */
class FoodEditRecallTest {
    private val oil = Nutrients(kcal = 900.0, fat = 100.0, satFat = 14.0)
    private val bread = Nutrients(kcal = 260.0, protein = 9.0, carbs = 50.0, fat = 3.0, salt = 1.2)
    private val ingredients = mapOf("olive_oil" to oil, "bread" to bread)
    private val dish = Nutrients(kcal = 180.0, protein = 6.0, carbs = 25.0, fat = 7.0, satFat = 1.2, salt = 0.8, calcium = 15.0)
    private val recipe = listOf(RecipeIngredient("olive_oil", 5.0, true), RecipeIngredient("bread", 10.0, true), RecipeIngredient("salt", 0.6, false))

    private val paella = FoodItem(
        FoodRef.Catalog("paella"), "Paella", null, FoodUnit.G, dish, 350.0,
        listOf(Portion(PortionKey.PLATE, 350.0), Portion(PortionKey.SMALL_PLATE, 200.0)), null,
    )

    // ---- values typed by hand -----------------------------------------------------------------

    @Test
    fun aTypedValueIsForTheAmountShownAndFollowsItAfterwards() {
        // 500 kcal typed for 250 g of the dish as it is: 200 kcal per 100 g, the other values the food's.
        val per100 = ValueEdits.per100Of(Nutrient.KCAL, 500.0, 250.0, emptyList(), ingredients::get)
        assertEquals(200.0, per100, 1e-9)
        val values = ValueEdits.apply(dish, mapOf(Nutrient.KCAL to per100))
        assertEquals(500.0, RecipeMath.apply(values, 250.0, emptyList(), ingredients::get).nutrients.kcal!!, 1e-9)
        assertEquals(6.0, values.protein!!, 0.0)
        // Twice the amount afterwards: twice the value typed.
        assertEquals(1000.0, RecipeMath.apply(values, 500.0, emptyList(), ingredients::get).nutrients.kcal!!, 1e-9)
    }

    @Test
    fun aTypedValueKeepsTheIngredientChangesOnTop() {
        // Double oil, no bread on 350 g: the value typed is the total with those changes.
        val adjustments = RecipeMath.adjustments(
            recipe, 350.0, mapOf("olive_oil" to IngredientChoice.Level(AdjustLevel.DOUBLE), "bread" to IngredientChoice.Level(AdjustLevel.NONE)),
        )
        val typed = 700.0
        val per100 = ValueEdits.per100Of(Nutrient.KCAL, typed, 350.0, adjustments, ingredients::get)
        // (700 − (17.5 × 9 − 35 × 2.6)) × 100 / 350
        assertEquals((typed - (17.5 * 9.0 - 35 * 2.6)) * 100 / 350, per100, 1e-9)
        val values = ValueEdits.apply(dish, mapOf(Nutrient.KCAL to per100))
        assertEquals(typed, RecipeMath.apply(values, 350.0, adjustments, ingredients::get).nutrients.kcal!!, 1e-9)
        // The oil back to normal afterwards: its change comes off the value typed.
        val onlyBread = RecipeMath.adjustments(recipe, 350.0, mapOf("bread" to IngredientChoice.Level(AdjustLevel.NONE)))
        assertEquals(typed - 17.5 * 9.0, RecipeMath.apply(values, 350.0, onlyBread, ingredients::get).nutrients.kcal!!, 1e-9)
        // A value under what the ingredient changes add is the dish's zero, never negative.
        assertEquals(0.0, ValueEdits.per100Of(Nutrient.KCAL, 10.0, 350.0, listOf(IngredientAdjustment("olive_oil", 17.5, 35.0, 2.0)), ingredients::get), 0.0)
    }

    @Test
    fun theKeptNoteShowsOnlyWhenTheIngredientChangesRaisedTheValue() {
        // 100 kcal typed, the double oil alone adds 157: 157 is saved, the note says so.
        assertEquals(157.5, ValueEdits.keptInstead(Nutrient.KCAL, 100.0, 157.5)!!, 0.0)
        // Rounding noise at a .5 boundary is no change (12,25 g typed saves 12.2499…).
        assertNull(ValueEdits.keptInstead(Nutrient.FAT, 12.25, 12.249999999999998))
        assertNull(ValueEdits.keptInstead(Nutrient.KCAL, 500.5, 500.49999999999994))
        assertNull(ValueEdits.keptInstead(Nutrient.FAT, 12.0, 12.0))
        // Small values have a finer step: 0.5 g typed, 0.6 g saved shows.
        assertEquals(0.6, ValueEdits.keptInstead(Nutrient.SALT, 0.5, 0.6)!!, 0.0)
    }

    @Test
    fun aClearedFieldIsUnknownNeverZero() {
        val values = ValueEdits.apply(dish, mapOf(Nutrient.SALT to null, Nutrient.FIBER to 2.0))
        assertNull(values.salt)
        assertEquals(2.0, values.fiber!!, 0.0)
        assertNull(RecipeMath.apply(values, 200.0, emptyList(), ingredients::get).nutrients.salt)
        assertEquals("nothing typed: the food's values", dish, ValueEdits.apply(dish, emptyMap()))
    }

    @Test
    fun theFlagSaysWhetherTheValuesDifferFromTheFoods() {
        val own = FoodMath.nutrientsFor(dish, 350.0)
        // Nothing typed, nothing recalled: not edited, even if the entry's values differ (a newer catalog).
        assertFalse(ValueEdits.edited(false, emptyMap(), own.copy(kcal = 1.0), own))
        // Typed, but the same as shown (300 typed where 300.4 showed): not edited.
        assertFalse(ValueEdits.edited(false, mapOf(Nutrient.KCAL to 0.0), own.copy(kcal = own.kcal!! + 0.3), own))
        assertTrue(ValueEdits.edited(false, mapOf(Nutrient.KCAL to 0.0), own.copy(kcal = own.kcal!! + 2), own))
        assertTrue("a value cleared", ValueEdits.edited(false, mapOf(Nutrient.SALT to null), own.copy(salt = null), own))
        // Recalled typed values that still differ, or a food no longer known: still edited.
        assertTrue(ValueEdits.edited(true, emptyMap(), own.copy(protein = 30.0), own))
        assertFalse("restored to the food's values", ValueEdits.edited(true, emptyMap(), own, own))
        assertTrue(ValueEdits.edited(true, emptyMap(), own, null))
        assertEquals(12.0, ValueEdits.rounded(Nutrient.KCAL, 12.4), 0.0)
        assertEquals(12.4, ValueEdits.rounded(Nutrient.PROTEIN, 12.43), 0.0)
        assertEquals(0.42, ValueEdits.rounded(Nutrient.VIT_B12, 0.4249), 0.0)
    }

    @Test
    fun theConfirmPagesValueFieldsSurviveAsText() {
        val overrides = mapOf(Nutrient.KCAL to 212.5, Nutrient.SALT to null, Nutrient.VIT_D to 0.125)
        assertEquals(overrides, ValueFields.decodeOverrides(ValueFields.encodeOverrides(overrides)))
        assertEquals(emptyMap<Nutrient, Double?>(), ValueFields.decodeOverrides(""))
        val texts = mapOf(Nutrient.KCAL to "500", Nutrient.FAT to "12,5", Nutrient.SALT to "")
        assertEquals(texts, ValueFields.decodeTexts(ValueFields.encodeTexts(texts)))
        // Broken parts are left out.
        assertEquals(mapOf(Nutrient.FAT to 3.0), ValueFields.decodeOverrides("fat=3;nope=1;kcal=x;=2"))
        assertEquals("only what a number field can hold", "12,5", ValueFields.clean("1a2,5;="))
    }

    // ---- the confirm page's values: what they start from, restore and compare with ---------------

    /** An entry logged with yesterday's catalog: today's food has other values. */
    private val logged = dish.copy(kcal = 170.0)
    private val todays = DraftOption(paella.copy(per100 = logged), recipe, own = dish)

    @Test
    fun restoreOnAnEntryNotTypedByHandKeepsItsOwnValues() {
        val base = ValueEdits.base(todays, recall = null, editingEntry = true, entryEdited = false)
        val typed = mapOf(Nutrient.KCAL to 200.0)
        assertEquals(200.0, ValueEdits.values(base, restored = false, overrides = typed).kcal!!, 0.0)
        assertTrue(ValueEdits.restorable(base, restored = false, overrides = typed))
        // "Restore" only drops what was typed now: the entry's own values, not today's catalog.
        assertNull(base.restore)
        assertEquals(logged, ValueEdits.values(base, restored = false, overrides = emptyMap()))
        assertFalse("nothing to restore", ValueEdits.restorable(base, restored = false, overrides = emptyMap()))
        // An entry whose values were typed by hand goes back to the food's own values.
        val typedBefore = ValueEdits.base(todays, recall = null, editingEntry = true, entryEdited = true)
        assertTrue(ValueEdits.restorable(typedBefore, restored = false, overrides = emptyMap()))
        assertEquals(dish, ValueEdits.values(typedBefore, restored = true, overrides = emptyMap()))
        // ...unless the food is no longer known: then only what was typed now goes, and the mark stays.
        val gone = ValueEdits.base(todays.copy(own = null), recall = null, editingEntry = true, entryEdited = true)
        assertFalse(ValueEdits.restorable(gone, restored = false, overrides = emptyMap()))
        val values = ValueEdits.values(gone, restored = false, overrides = emptyMap())
        assertTrue(ValueEdits.savedEdited(gone, false, emptyMap(), FoodMath.nutrientsFor(values, 350.0), null, hasFood = true))
    }

    @Test
    fun retypingTheShownValueOfAnOldEntryIsNoEdit() {
        val base = ValueEdits.base(todays, recall = null, editingEntry = true, entryEdited = false)
        // The kcal field shows 595 (170 × 3.5); typed again as it is.
        val retyped = mapOf(Nutrient.KCAL to ValueEdits.per100Of(Nutrient.KCAL, 595.0, 350.0, emptyList(), ingredients::get))
        val values = FoodMath.nutrientsFor(ValueEdits.values(base, false, retyped), 350.0)
        val reference = base.reference?.let { FoodMath.nutrientsFor(it, 350.0) }
        assertFalse("compared with the entry's own values, not today's food", ValueEdits.savedEdited(base, false, retyped, values, reference, hasFood = true))
        // Another value typed is an edit.
        val other = mapOf(Nutrient.KCAL to ValueEdits.per100Of(Nutrient.KCAL, 500.0, 350.0, emptyList(), ingredients::get))
        assertTrue(ValueEdits.savedEdited(base, false, other, FoodMath.nutrientsFor(ValueEdits.values(base, false, other), 350.0), reference, hasFood = true))
        // A new entry is compared with the food's own values and starts from those typed last time.
        val last = FoodRecall.recall(paella, entry("e1", 1_000).copy(amount = 350.0, nutrients = FoodMath.nutrientsFor(logged, 350.0), valuesEdited = true), recipe, ingredients::get)
        val fresh = ValueEdits.base(DraftOption(paella, recipe, last), last, editingEntry = false, entryEdited = false)
        assertEquals(170.0, fresh.start.kcal!!, 1e-9)
        assertEquals(dish, fresh.reference)
        assertTrue(fresh.startedEdited)
        assertTrue(ValueEdits.savedEdited(fresh, false, emptyMap(), FoodMath.nutrientsFor(fresh.start, 350.0), FoodMath.nutrientsFor(dish, 350.0), hasFood = true))
        assertFalse("restored", ValueEdits.savedEdited(fresh, true, emptyMap(), FoodMath.nutrientsFor(dish, 350.0), FoodMath.nutrientsFor(dish, 350.0), hasFood = true))
    }

    @Test
    fun aQuickEntryNeverHasTheMark() {
        val quick = FoodItem(null, "Bocadillo", null, FoodUnit.G, Nutrients(kcal = 250.0), 200.0, emptyList(), null)
        val base = ValueEdits.base(DraftOption(quick, emptyList()), recall = null, editingEntry = true, entryEdited = false)
        val typed = mapOf(Nutrient.KCAL to 300.0)
        val values = FoodMath.nutrientsFor(ValueEdits.values(base, false, typed), 200.0)
        assertFalse(ValueEdits.savedEdited(base, false, typed, values, FoodMath.nutrientsFor(quick.per100, 200.0), hasFood = false))
        assertTrue("with a food it would", ValueEdits.savedEdited(base, false, typed, values, FoodMath.nutrientsFor(quick.per100, 200.0), hasFood = true))
    }

    @Test
    fun switchingVariantFromLastTimesAmountStartsWithItsDefaults() {
        val seafood = paella.copy(ref = FoodRef.Catalog("paella_seafood"), portion = 400.0, portions = listOf(Portion(PortionKey.PLATE, 400.0)))
        val last = FoodRecall.recall(paella, entry("e1", 1_000).copy(amount = 400.0, portionKey = PortionKey.SMALL_PLATE, portionCount = 2.0), recipe, ingredients::get)
        // Two small plates last time: the variant without a last entry starts with its own default portion, not "2 × small plate".
        assertEquals(DraftAmount(400.0, Portion(PortionKey.PLATE, 400.0), 1.0), FoodDrafts.switchAmount(seafood, null, fromRecall = true, suggested = null, portionKey = "small_plate", count = 2.0))
        assertEquals("or the photo's suggestion for it", DraftAmount(320.0, null, null), FoodDrafts.switchAmount(seafood, null, fromRecall = true, suggested = 320.0, portionKey = "small_plate", count = 2.0))
        // A variant with its own last entry starts as that one.
        assertEquals(DraftAmount(400.0, Portion(PortionKey.SMALL_PLATE, 200.0), 2.0), FoodDrafts.switchAmount(paella, last, fromRecall = false, suggested = 320.0, portionKey = "plate", count = 1.0))
        // As before otherwise: the photo's suggestion, else the same named portion with its count, else the amount typed stays.
        assertEquals(DraftAmount(320.0, null, null), FoodDrafts.switchAmount(seafood, null, fromRecall = false, suggested = 320.0, portionKey = "plate", count = 2.0))
        assertEquals(DraftAmount(800.0, Portion(PortionKey.PLATE, 400.0), 2.0), FoodDrafts.switchAmount(seafood, null, fromRecall = false, suggested = null, portionKey = "plate", count = 2.0))
        assertNull(FoodDrafts.switchAmount(seafood, null, fromRecall = false, suggested = null, portionKey = null, count = 1.0))
    }

    // ---- the last entry -------------------------------------------------------------------------

    private fun entry(id: String, eatenAt: Long, updatedAt: Long = eatenAt, foodId: String? = "paella", customFoodId: String? = null) =
        testEntry(id = id, eatenAt = eatenAt, updatedAt = updatedAt).copy(foodId = foodId, customFoodId = customFoodId)

    @Test
    fun theLastEntryIsTheLatestEatenOfThatVeryFood() {
        val entries = listOf(
            entry("old", 1_000),
            entry("late", 5_000),
            entry("tie", 5_000, updatedAt = 9_000),
            entry("seafood", 8_000, foodId = "paella_seafood"),
            entry("custom", 7_000, foodId = null, customFoodId = "c1"),
            entry("quick", 9_000, foodId = null),
        )
        assertEquals("same time: the latest changed", "tie", FoodRecall.lastEntry(entries, FoodRef.Catalog("paella"))!!.id)
        assertEquals("a variant is its own food", "seafood", FoodRecall.lastEntry(entries, FoodRef.Catalog("paella_seafood"))!!.id)
        assertEquals("custom", FoodRecall.lastEntry(entries, FoodRef.Custom("c1"))!!.id)
        assertNull("a quick entry has no food", FoodRecall.lastEntry(entries, null))
        assertNull(FoodRecall.lastEntry(entries, FoodRef.Catalog("tortilla")))
    }

    @Test
    fun aDraftStartsAsLastTime() {
        // Last time: two small plates (400 g) with double oil and its kcal typed by hand.
        val adjustments = listOf(IngredientAdjustment("olive_oil", 20.0, 40.0, 2.0))
        val logged = RecipeMath.apply(dish.copy(kcal = 150.0), 400.0, adjustments, ingredients::get)
        val last = entry("e1", 1_762_000_000_000).copy(
            amount = logged.amount, nutrients = logged.nutrients, adjustments = adjustments,
            portionKey = PortionKey.SMALL_PLATE, portionCount = 2.0, valuesEdited = true,
        )
        val r = FoodRecall.recall(paella, last, recipe, ingredients::get)
        assertEquals("e1", r.entryId)
        assertEquals(last.localDate, r.date)
        assertEquals("the dish's own amount", 400.0, r.amount, 1e-9)
        assertEquals(Portion(PortionKey.SMALL_PLATE, 200.0), r.portion)
        assertEquals(2.0, r.portionCount!!, 0.0)
        assertEquals(mapOf("olive_oil" to IngredientChoice.Level(AdjustLevel.DOUBLE)), r.choices)
        assertEquals("the values typed, the oil's change taken out", 150.0, r.per100!!.kcal!!, 1e-9)
        assertEquals(dish.protein!!, r.per100!!.protein!!, 1e-9)
        assertEquals(DraftAmount(400.0, Portion(PortionKey.SMALL_PLATE, 200.0), 2.0), FoodDrafts.startAmount(paella, r, estimate = 320.0))
        // Logged again for 400 g, a level follows the amount: the same values as last time.
        val again = RecipeMath.apply(r.per100!!, r.amount, RecipeMath.adjustments(recipe, r.amount, r.choices), ingredients::get)
        assertEquals(logged.nutrients.kcal!!, again.nutrients.kcal!!, 1e-9)
        assertEquals(logged.amount, again.amount, 1e-9)
        // Without the recall: the photo's suggestion, else the default portion.
        assertEquals(DraftAmount(320.0, null, null), FoodDrafts.startAmount(paella, null, estimate = 320.0))
        assertEquals(DraftAmount(350.0, Portion(PortionKey.PLATE, 350.0), 1.0), FoodDrafts.startAmount(paella, null, estimate = null))
    }

    @Test
    fun onlyWhatStillAppliesIsRecalled() {
        val last = entry("e1", 1_000, updatedAt = 50).copy(
            amount = 330.0,
            adjustments = listOf(IngredientAdjustment("bread", 35.0, 0.0, 0.0), IngredientAdjustment("cheese", 10.0, 25.0, null), IngredientAdjustment("salt", 2.1, 0.0, 0.0)),
            portionKey = PortionKey.PLATE, portionCount = 1.0,
        )
        val r = FoodRecall.recall(paella, last, recipe, ingredients::get)
        // dish 330 − (−35 + 15 − 2.1) = 352.1 g: no longer the plate's 350 g, so grams.
        assertEquals(352.1, r.amount, 1e-9)
        assertNull(r.portion)
        assertNull(r.portionCount)
        assertEquals("only adjustable ingredients the recipe still has", mapOf("bread" to IngredientChoice.Level(AdjustLevel.NONE)), r.choices)
        assertNull("values not typed: the food's own", r.per100)
        // A portion the food no longer has: grams.
        assertNull(FoodRecall.recall(paella.copy(portions = emptyList()), last.copy(adjustments = emptyList()), recipe, ingredients::get).portion)
        // A custom food changed after the entry: its own values, not the ones typed then.
        val typed = last.copy(adjustments = emptyList(), valuesEdited = true)
        val mine = paella.copy(ref = FoodRef.Custom("c1"))
        assertNull(FoodRecall.recall(mine, typed, emptyList(), ingredients::get, foodUpdatedAt = 60).per100)
        assertEquals(FoodMath.per100Of(typed.nutrients, 330.0), FoodRecall.recall(mine, typed, emptyList(), ingredients::get, foodUpdatedAt = 40).per100)
    }

    // ---- a plate's items ------------------------------------------------------------------------

    @Test
    fun aPlateItemStartsAsLastTimeAndAddAllSavesItAllThroughTheSameMaths() {
        val grams = GramsEstimator { portion, share -> portion?.let { it * share * 2 } }
        val adjustments = listOf(IngredientAdjustment("bread", 20.0, 0.0, 0.0))
        val logged = RecipeMath.apply(dish.copy(protein = 10.0), 200.0, adjustments, ingredients::get)
        val last = entry("e1", 1_000).copy(
            amount = logged.amount, nutrients = logged.nutrients, adjustments = adjustments,
            portionKey = PortionKey.SMALL_PLATE, portionCount = 1.0, valuesEdited = true,
        )
        val r = FoodRecall.recall(paella, last, recipe, ingredients::get)
        // Last time's amount over the photo's suggestion, which still shows.
        assertEquals(AmountStart(200.0, 280.0), FoodDrafts.plateAmount(paella, grams, 0.4, typed = null, recall = r))
        assertEquals("typed wins", AmountStart(90.0, null), FoodDrafts.plateAmount(paella, grams, 0.4, typed = 90.0, recall = r))
        val add = FoodDrafts.plateDish(paella, 200.0, r, recipe, ingredients::get)
        assertEquals(logged.nutrients.protein!!, add.nutrients.protein!!, 1e-9)
        assertEquals(logged.nutrients.kcal!!, add.nutrients.kcal!!, 1e-9)
        assertEquals(logged.amount, add.amount, 1e-9)
        assertEquals(adjustments, add.adjustments)
        assertEquals(Portion(PortionKey.SMALL_PLATE, 200.0), add.portion)
        assertEquals(1.0, add.portionCount!!, 0.0)
        assertTrue(add.valuesEdited)
        // Another amount: the changes and values still apply (a level of the new amount), the portion goes.
        val more = FoodDrafts.plateDish(paella, 300.0, r, recipe, ingredients::get)
        assertEquals(listOf(IngredientAdjustment("bread", 30.0, 0.0, 0.0)), more.adjustments)
        assertNull(more.portion)
        // Without a last entry: the food's values for the amount, its default portion when it is that.
        val plain = FoodDrafts.plateDish(paella, 350.0, null, recipe, ingredients::get)
        assertEquals(FoodMath.nutrientsFor(dish, 350.0), plain.nutrients)
        assertEquals(Portion(PortionKey.PLATE, 350.0), plain.portion)
        assertTrue(plain.adjustments.isEmpty())
        assertFalse(plain.valuesEdited)
    }

    @Test
    fun theRecallsDayIsWhereItWasEaten() {
        // 23:30 UTC on 1 Nov is already 2 Nov in Madrid (+1).
        val e = testEntry(id = "e", eatenAt = 1_762_039_800_000L, zoneOffsetSeconds = 3600)
        assertEquals(LocalDate.of(2025, 11, 2), FoodRecall.recall(paella, e, emptyList(), ingredients::get).date)
    }
}
