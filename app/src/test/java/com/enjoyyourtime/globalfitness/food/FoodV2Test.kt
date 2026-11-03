package com.enjoyyourtime.globalfitness.food

import com.enjoyyourtime.globalfitness.food.catalog.FoodCatalog
import com.enjoyyourtime.globalfitness.food.catalog.FoodCatalogParser
import com.enjoyyourtime.globalfitness.food.catalog.FoodSearch
import com.enjoyyourtime.globalfitness.food.catalog.RecipeParser
import com.enjoyyourtime.globalfitness.food.domain.AmountStart
import com.enjoyyourtime.globalfitness.food.domain.FoodDrafts
import com.enjoyyourtime.globalfitness.food.domain.FoodMath
import com.enjoyyourtime.globalfitness.food.domain.IngredientChoice
import com.enjoyyourtime.globalfitness.food.domain.RecipeMath
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
import com.enjoyyourtime.globalfitness.ui.food.IngredientChoices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 1.6.0's food data: foods.tsv v2 (more nutrients, variants), recipes.tsv and the ingredient maths. */
class FoodV2Test {
    private val v1 = "id\tgroup\tunit\tkcal\tprotein\tcarbs\tsugars\tfat\tsat_fat\tfiber\tsalt\tportion\tportions\tvisual\tsource"
    private val v2 = "$v1\tmono_fat\tpoly_fat\tcholesterol\tpotassium\tcalcium\tiron\tmagnesium\tzinc\tvit_a\tvit_c\tvit_d\tvit_e\tvit_b12\tfolate\tvariant_of"

    /** A v2 row: the v1 cells, then the 14 values and variant_of. */
    private fun row(
        id: String,
        unit: String = "g",
        kcal: Int = 100,
        extra: String = "\t\t\t\t\t\t\t\t\t\t\t\t\t",
        variantOf: String = "",
        visual: Int = 0,
        portions: String = "",
        portion: Int = 300,
    ) = "$id\tdishes\t$unit\t$kcal\t5\t10\t1\t4\t1\t1\t0.5\t$portion\t$portions\t$visual\trecipe\t$extra\t$variantOf"

    @Test
    fun readsTheV2ColumnsAndStillTheV1File() {
        val text = "$v2\n" +
            "paella\tdishes\tg\t158\t7.5\t20\t0.8\t5\t1.1\t0.8\t0.6\t350\tplate:350\t1\trecipe\t3.1\t0.7\t21\t180\t20.5\t0.95\t25\t0.85\t35\t2.3\t0.1\t0.65\t0.42\t18\t\n" +
            row("paella_seafood", variantOf = "paella", portions = "plate:350") + "\n"
        val foods = FoodCatalogParser.parse(text).associateBy { it.id }
        val p = foods.getValue("paella").per100
        assertEquals(3.1, p.monoFat!!, 0.0)
        assertEquals(0.7, p.polyFat!!, 0.0)
        assertEquals(21.0, p.cholesterol!!, 0.0)
        assertEquals(180.0, p.potassium!!, 0.0)
        assertEquals(20.5, p.calcium!!, 0.0)
        assertEquals(0.95, p.iron!!, 0.0)
        assertEquals(25.0, p.magnesium!!, 0.0)
        assertEquals(0.85, p.zinc!!, 0.0)
        assertEquals(35.0, p.vitA!!, 0.0)
        assertEquals(2.3, p.vitC!!, 0.0)
        assertEquals(0.1, p.vitD!!, 0.0)
        assertEquals(0.65, p.vitE!!, 0.0)
        assertEquals(0.42, p.vitB12!!, 0.0)
        assertEquals(18.0, p.folate!!, 0.0)
        assertNull(foods.getValue("paella").variantOf)
        assertEquals("paella", foods.getValue("paella_seafood").variantOf)
        assertNull("an empty value is unknown", foods.getValue("paella_seafood").per100.calcium)
        // A v1 file (1.5.0's, tests, old data) reads as before, without the new values.
        val old = FoodCatalogParser.parse("$v1\nrice\tgrains\tg\t130\t2.7\t28\t0.1\t0.3\t0.1\t0.4\t0\t150\t\t1\tfdc:1\n").single()
        assertEquals(130.0, old.per100.kcal!!, 0.0)
        assertFalse(old.per100.hasExtra)
        assertNull(old.variantOf)
    }

    @Test
    fun aVariantNeedsABaseThatIsNotAVariant() {
        val text = "$v2\n" +
            row("coffee") + "\n" +
            row("cortado", variantOf = "coffee") + "\n" +
            row("cortado_oat", variantOf = "cortado") + "\n" +
            row("ghost", variantOf = "missing") + "\n" +
            row("selfish", variantOf = "selfish") + "\n" +
            row("bad", variantOf = "Bad Id") + "\n"
        val foods = FoodCatalogParser.parse(text).associateBy { it.id }
        assertEquals("coffee", foods.getValue("cortado").variantOf)
        assertNull("a variant of a variant is a plain food", foods.getValue("cortado_oat").variantOf)
        assertNull(foods.getValue("ghost").variantOf)
        assertNull(foods.getValue("selfish").variantOf)
        assertNull(foods.getValue("bad").variantOf)
        assertEquals(6, foods.size)
        // Offered base first, then its variants; a plain food has none.
        assertEquals(listOf("coffee", "cortado"), FoodCatalog.variantsOf("cortado", foods).map { it.id })
        assertEquals(listOf("coffee", "cortado"), FoodCatalog.variantsOf("coffee", foods).map { it.id })
        assertTrue(FoodCatalog.variantsOf("ghost", foods).isEmpty())
        assertTrue(FoodCatalog.variantsOf("nothing", foods).isEmpty())
    }

    @Test
    fun recipesParse() {
        val known = setOf("tortilla", "potato_raw", "egg", "olive_oil", "onion", "salt")
        val text = "dish\tingredient\tgrams\tadjustable\n" +
            "tortilla\tpotato_raw\t55.2\t1\n" +
            "tortilla\tegg\t30\t1\n" +
            "tortilla\tolive_oil\t8.5\t1\n" +
            "tortilla\tsalt\t0.6\t0\n" +
            "tortilla\tunknown_thing\t5\t1\n" +
            "tortilla\tonion\t0\t1\n" +
            "tortilla\tonion\t-3\t1\n" +
            "tortilla\ttortilla\t10\t1\n" +
            "tortilla\tolive_oil\t1.5\t0\n" +
            "nothing\tegg\t10\t1\n"
        val recipes = RecipeParser.parse(text) { it in known }
        assertEquals(setOf("tortilla"), recipes.keys)
        val t = recipes.getValue("tortilla")
        assertEquals(listOf("potato_raw", "egg", "olive_oil", "salt"), t.map { it.ingredient })
        assertEquals("listed twice: the grams add up", 10.0, t[2].grams, 1e-9)
        assertTrue(t[2].adjustable)
        assertFalse(t[3].adjustable)
        assertEquals(emptyMap<String, List<RecipeIngredient>>(), RecipeParser.parse("dish\tgrams\nx\t1\n"))
        // Columns by name, in any order; without "adjustable" nothing is adjustable.
        assertEquals(listOf(RecipeIngredient("egg", 2.0, false)), RecipeParser.parse("grams\tingredient\tdish\n2\tegg\ttortilla\n").getValue("tortilla"))
    }

    // ---- the delta of contract 3 ----------------------------------------------------------------

    private val oil = Nutrients(kcal = 900.0, fat = 100.0, satFat = 14.0, monoFat = 73.0, vitE = 14.0)
    private val bread = Nutrients(kcal = 260.0, protein = 9.0, carbs = 50.0, fat = 3.0, salt = 1.2, calcium = 20.0)
    private val ingredients = mapOf("olive_oil" to oil, "bread" to bread)
    private val recipe = listOf(
        RecipeIngredient("rice_raw", 25.0, true),
        RecipeIngredient("olive_oil", 5.0, true),
        RecipeIngredient("bread", 10.0, true),
        RecipeIngredient("salt", 0.6, false),
    )
    private val dish = Nutrients(kcal = 180.0, protein = 6.0, carbs = 25.0, fat = 7.0, satFat = 1.2, salt = 0.8, calcium = 15.0, vitE = 1.0)

    @Test
    fun adjustmentsFollowTheChoices() {
        val a = RecipeMath.adjustments(
            recipe,
            350.0,
            mapOf(
                "olive_oil" to IngredientChoice.Level(AdjustLevel.DOUBLE),
                "bread" to IngredientChoice.Level(AdjustLevel.NONE),
                "rice_raw" to IngredientChoice.Level(AdjustLevel.NORMAL),
                "salt" to IngredientChoice.Level(AdjustLevel.NONE),
            ),
        )
        // old_g = grams × total / 100; only what changes, only adjustable ingredients.
        assertEquals(listOf(IngredientAdjustment("olive_oil", 17.5, 35.0, 2.0), IngredientAdjustment("bread", 35.0, 0.0, 0.0)), a)
        val typed = RecipeMath.adjustments(recipe, 200.0, mapOf("olive_oil" to IngredientChoice.Grams(4.0), "bread" to IngredientChoice.Grams(20.02)))
        assertEquals("grams equal to the usual change nothing", listOf(IngredientAdjustment("olive_oil", 10.0, 4.0, null)), typed)
        assertEquals(IngredientChoice.Level(AdjustLevel.DOUBLE), IngredientChoice.of(a[0]))
        assertEquals(IngredientChoice.Grams(4.0), IngredientChoice.of(typed[0]))
    }

    @Test
    fun theDeltaFormula() {
        val adjustments = listOf(IngredientAdjustment("olive_oil", 17.5, 35.0, 2.0), IngredientAdjustment("bread", 35.0, 0.0, 0.0))
        val r = RecipeMath.apply(dish, 350.0, adjustments, ingredients::get)
        // dish × 350 / 100 + (35 − 17.5) × oil / 100 + (0 − 35) × bread / 100
        assertEquals(180 * 3.5 + 17.5 * 9.0 - 35 * 2.6, r.nutrients.kcal!!, 1e-9)
        assertEquals(7 * 3.5 + 17.5 * 1.0 - 35 * 0.03, r.nutrients.fat!!, 1e-9)
        assertEquals("the oil has no protein value: only the bread's counts", 6 * 3.5 - 35 * 0.09, r.nutrients.protein!!, 1e-9)
        assertEquals(15 * 3.5 - 35 * 0.2, r.nutrients.calcium!!, 1e-9)
        assertNull("unknown for the dish stays unknown", r.nutrients.monoFat)
        assertEquals("the weight changes by Σ (new − old)", 350 + 17.5 - 35, r.amount, 1e-9)
        // Nothing changed: the dish's own values for the amount.
        assertEquals(RecipeMath.apply(dish, 200.0, emptyList(), ingredients::get).nutrients, FoodMath.nutrientsFor(dish, 200.0))
        // Removing more than there is never goes below zero.
        val none = RecipeMath.apply(Nutrients(kcal = 10.0, salt = 0.1), 100.0, listOf(IngredientAdjustment("bread", 50.0, 0.0, 0.0)), ingredients::get)
        assertEquals(0.0, none.nutrients.kcal!!, 0.0)
        assertEquals(0.0, none.nutrients.salt!!, 0.0)
        assertEquals(50.0, none.amount, 1e-9)
        // An ingredient gone from the catalog counts for the weight only.
        val gone = RecipeMath.apply(dish, 100.0, listOf(IngredientAdjustment("missing", 10.0, 20.0, 2.0)), ingredients::get)
        assertEquals(180.0, gone.nutrients.kcal!!, 1e-9)
        assertEquals(110.0, gone.amount, 1e-9)
    }

    @Test
    fun anEntryOpenedAgainKeepsItsValues() {
        val adjustments = listOf(IngredientAdjustment("olive_oil", 17.5, 35.0, 2.0), IngredientAdjustment("bread", 35.0, 0.0, 0.0))
        val logged = RecipeMath.apply(dish, 350.0, adjustments, ingredients::get)
        val back = RecipeMath.unadjustedPer100(logged.nutrients, logged.amount, adjustments, ingredients::get)
        for (n in Nutrient.entries) {
            val expected = dish[n]
            if (expected == null) assertNull(n.code, back[n]) else assertEquals(n.code, expected, back[n]!!, 1e-9)
        }
        val again = RecipeMath.apply(back, logged.amount - adjustments.sumOf { it.delta }, adjustments, ingredients::get)
        assertEquals(logged.nutrients.kcal!!, again.nutrients.kcal!!, 1e-9)
        assertEquals(logged.amount, again.amount, 1e-9)
        // Without adjustments: per 100 of what was logged.
        assertEquals(FoodMath.per100Of(logged.nutrients, 200.0), RecipeMath.unadjustedPer100(logged.nutrients, 200.0, emptyList(), ingredients::get))
    }

    @Test
    fun levelsFollowTheDishTypedGramsStay() {
        // The same choices for half the dish: a level keeps its proportion, grams typed stay as typed.
        val choices = mapOf("olive_oil" to IngredientChoice.Level(AdjustLevel.DOUBLE), "bread" to IngredientChoice.Grams(10.0), "rice_raw" to IngredientChoice.Grams(43.75))
        val full = RecipeMath.adjustments(recipe, 350.0, choices)
        assertEquals(listOf(IngredientAdjustment("rice_raw", 87.5, 43.75, null), IngredientAdjustment("olive_oil", 17.5, 35.0, 2.0), IngredientAdjustment("bread", 35.0, 10.0, null)), full)
        val half = RecipeMath.adjustments(recipe, 175.0, choices)
        assertEquals(IngredientAdjustment("olive_oil", 8.75, 17.5, 2.0), half.single { it.ingredient == "olive_oil" })
        assertEquals(IngredientAdjustment("bread", 17.5, 10.0, null), half.single { it.ingredient == "bread" })
        assertTrue("typed grams that became the usual ones change nothing", half.none { it.ingredient == "rice_raw" })
    }

    @Test
    fun theConfirmPagesChoicesSurviveAsText() {
        val choices = mapOf(
            "olive_oil" to IngredientChoice.Level(AdjustLevel.DOUBLE),
            "bread" to IngredientChoice.Level(AdjustLevel.NONE),
            "rice_raw" to IngredientChoice.Grams(42.5),
        )
        assertEquals(choices, IngredientChoices.decode(IngredientChoices.encode(choices)))
        assertEquals(emptyMap<String, IngredientChoice>(), IngredientChoices.decode(""))
        // Broken parts are left out.
        assertEquals(mapOf("egg" to IngredientChoice.Level(AdjustLevel.HALF)), IngredientChoices.decode("egg=half;oil=g:x;=double;milk=lots"))
    }

    @Test
    fun anEditedEntryKeepsAnIngredientItsRecipeNoLongerHas() {
        val now = listOf(RecipeIngredient("olive_oil", 5.0, false), RecipeIngredient("salt", 0.6, false))
        val list = RecipeMath.ingredientsFor(now, listOf(IngredientAdjustment("olive_oil", 17.5, 35.0, 2.0), IngredientAdjustment("bread", 35.0, 0.0, 0.0)), 350.0)
        assertEquals(listOf("olive_oil", "salt", "bread"), list.map { it.ingredient })
        assertTrue("changed: still adjustable", list[0].adjustable)
        assertFalse(list[1].adjustable)
        assertEquals(10.0, list[2].grams, 1e-9)
    }

    // ---- drafts, variants, search -------------------------------------------------------------

    @Test
    fun aDraftOffersTheVariantsAndTheIngredients() {
        val text = "$v2\n" +
            row("paella", portions = "plate:350", portion = 350) + "\n" +
            row("paella_seafood", kcal = 140, variantOf = "paella", portions = "plate:400|small_plate:200", portion = 400) + "\n" +
            row("olive_oil", kcal = 900) + "\n" +
            row("prawns", kcal = 90) + "\n"
        val foods = FoodCatalogParser.parse(text).associateBy { it.id }
        val recipes = mapOf(
            "paella" to listOf(RecipeIngredient("olive_oil", 5.0, true)),
            "paella_seafood" to listOf(RecipeIngredient("prawns", 15.0, true), RecipeIngredient("olive_oil", 4.0, true)),
        )
        val item = FoodItem.of(foods.getValue("paella"), "Paella")
        val shape = FoodDrafts.shape(item, foods, recipes, { it.uppercase() })
        assertEquals(listOf("paella", "paella_seafood"), shape.options.map { it.item.foodId })
        assertEquals("the draft's own item first", item, shape.options[0].item)
        assertEquals("PAELLA_SEAFOOD", shape.options[1].item.name)
        assertEquals(setOf("olive_oil", "prawns"), shape.ingredients.keys)
        assertEquals(listOf("olive_oil"), shape.recipe.map { it.ingredient })
        // Switching: the same named portion if the variant has it, else its default one; grams stay grams.
        val seafood = shape.options[1].item
        assertEquals(Portion(PortionKey.PLATE, 400.0), FoodDrafts.portionAfterSwitch(seafood, "plate"))
        assertEquals(Portion(PortionKey.PLATE, 400.0), FoodDrafts.portionAfterSwitch(seafood, "glass"))
        assertNull(FoodDrafts.portionAfterSwitch(seafood, null))
        // A custom food has none of that.
        val custom = FoodItem(FoodRef.Custom("c1"), "Mine", null, FoodUnit.G, Nutrients(kcal = 1.0), null, emptyList(), null)
        assertTrue(FoodDrafts.shape(custom, foods, recipes, { it }).options.isEmpty())
    }

    @Test
    fun aPlateItemStartsWithThePhotosSuggestionElseItsPortion() {
        val apple = FoodItem(FoodRef.Catalog("apple"), "Apple", null, FoodUnit.G, Nutrients(kcal = 54.0), 150.0, listOf(Portion(PortionKey.UNIT, 150.0)), null)
        val noPortion = apple.copy(portion = null, portions = emptyList())
        // The estimate behind its interface: here the portion times twice the item's share.
        val grams = GramsEstimator { portion, share -> portion?.let { it * share * 2 } }
        assertEquals(AmountStart(120.0, 120.0), FoodDrafts.plateAmount(apple, grams, 0.4, typed = null))
        // No estimator (or no estimate): the food's default portion, not marked as estimated.
        assertEquals(AmountStart(150.0, null), FoodDrafts.plateAmount(apple, null, 0.4, typed = null))
        assertEquals(AmountStart(100.0, null), FoodDrafts.plateAmount(noPortion, grams, 0.4, typed = null))
        // What the user typed always wins.
        assertEquals(AmountStart(90.0, null), FoodDrafts.plateAmount(apple, grams, 0.4, typed = 90.0))
    }

    @Test
    fun searchListsTheVariantsAfterTheirBase() {
        fun item(id: String, name: String, variantOf: String? = null) =
            FoodItem(FoodRef.Catalog(id), name, null, FoodUnit.G, Nutrients(kcal = 100.0), 100.0, emptyList(), null, variantOf = variantOf)
        val items = listOf(item("coffee", "Café"), item("cortado", "Cortado", "coffee"), item("latte", "Café con leche", "coffee"), item("cake", "Pastel de café"))
        val found = FoodSearch.search(items, emptyMap(), "café")
        assertEquals(listOf("coffee", "cortado", "latte", "cake"), found.map { it.foodId })
        assertEquals("a variant found on its own stays where it ranks", listOf("cortado"), FoodSearch.search(items, emptyMap(), "cortado").map { it.foodId })
    }

    // ---- totals --------------------------------------------------------------------------------

    @Test
    fun nutrientsBeyondTheLabelAddUpAndMarkPartialTotals() {
        val a = Nutrients(kcal = 100.0, calcium = 50.0, vitD = 1.5)
        val b = Nutrients(kcal = 50.0, calcium = 25.0)
        assertEquals(Nutrients(kcal = 150.0, calcium = 75.0, vitD = 1.5), a + b)
        assertEquals(Nutrients(kcal = 200.0, calcium = 100.0, vitD = 3.0), a.scaled(2.0))
        assertTrue(a.hasExtra)
        assertFalse(Nutrients(kcal = 1.0, salt = 1.0).hasExtra)
        assertEquals(setOf(Nutrient.VIT_D), FoodMath.partial(listOf(a, b)))
        val day = LocalDate.of(2023, 11, 14)
        val log = FoodMath.dayLog(listOf(testEntry(id = "1", nutrients = a), testEntry(id = "2", nutrients = b)), day)
        assertEquals(1.5, log.total.vitD!!, 0.0)
        assertTrue(Nutrient.VIT_D in log.partial)
        assertFalse(Nutrient.CALCIUM in log.partial)
        assertFalse("unknown everywhere is not partial", Nutrient.ZINC in log.partial)
    }

    @Test
    fun nutrientCodesAreTheColumnsAndStayStable() {
        assertEquals(
            listOf(
                "kcal", "protein", "carbs", "sugars", "fat", "sat_fat", "fiber", "salt", "mono_fat", "poly_fat", "cholesterol", "potassium",
                "calcium", "iron", "magnesium", "zinc", "vit_a", "vit_c", "vit_d", "vit_e", "vit_b12", "folate",
            ),
            Nutrient.entries.map { it.code },
        )
        assertEquals(Nutrient.entries, Nutrient.LABEL + Nutrient.EXTRA)
        assertEquals(listOf("kcal", "protein", "carbs", "sugars", "fat", "satFat", "fiber", "salt"), Nutrient.LABEL.map { it.field })
    }
}
