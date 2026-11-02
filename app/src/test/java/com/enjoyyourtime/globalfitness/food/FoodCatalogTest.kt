package com.enjoyyourtime.globalfitness.food

import com.enjoyyourtime.globalfitness.food.catalog.FoodCatalogParser
import com.enjoyyourtime.globalfitness.food.catalog.FoodSearch
import com.enjoyyourtime.globalfitness.food.catalog.FoodUsage
import com.enjoyyourtime.globalfitness.food.model.FoodGroup
import com.enjoyyourtime.globalfitness.food.model.FoodItem
import com.enjoyyourtime.globalfitness.food.model.FoodRef
import com.enjoyyourtime.globalfitness.food.model.FoodUnit
import com.enjoyyourtime.globalfitness.food.model.Nutrients
import com.enjoyyourtime.globalfitness.food.model.Portion
import com.enjoyyourtime.globalfitness.food.model.PortionKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FoodCatalogTest {
    private val header = "id\tgroup\tunit\tkcal\tprotein\tcarbs\tsugars\tfat\tsat_fat\tfiber\tsalt\tportion\tportions\tvisual\tsource"

    @Test
    fun parsesTheContractFormat() {
        val text = "$header\n" +
            "paella\tdishes\tg\t158\t7.5\t20\t0.8\t5\t1.1\t0.8\t0.6\t350\tplate:350|small_plate:200\t1\tfdc:1\n" +
            "beer\tdrinks\tml\t43\t0.5\t3.6\t\t0\t0\t0\t0\t330\tcan:330|glass:200\t0\tciqual:5000\n"
        val foods = FoodCatalogParser.parse(text)
        assertEquals(listOf("paella", "beer"), foods.map { it.id })
        val paella = foods[0]
        assertEquals(FoodGroup.DISHES, paella.group)
        assertEquals(FoodUnit.G, paella.unit)
        assertEquals(Nutrients(158.0, 7.5, 20.0, 0.8, 5.0, 1.1, 0.8, 0.6), paella.per100)
        assertEquals(350.0, paella.portion)
        assertEquals(listOf(Portion(PortionKey.PLATE, 350.0), Portion(PortionKey.SMALL_PLATE, 200.0)), paella.portions)
        assertTrue(paella.visual)
        val beer = foods[1]
        assertEquals(FoodUnit.ML, beer.unit)
        assertNull("an empty cell is unknown, never 0", beer.per100.sugars)
        assertFalse(beer.visual)
        assertEquals("ciqual:5000", beer.source)
    }

    @Test
    fun findsColumnsByNameAndSkipsBadRows() {
        val text = "﻿unit\tid\tkcal\r\n" +
            "g\tok_food\t100\r\n" +
            "g\tBad Id\t100\n" +
            "g\tok_food\t200\n" +
            "# a comment\n" +
            "\n" +
            "g\tnegative\t-5\n"
        val foods = FoodCatalogParser.parse(text)
        assertEquals(listOf("ok_food", "negative"), foods.map { it.id })
        assertEquals("the first of two duplicates wins", 100.0, foods[0].per100.kcal)
        assertNull("a negative value is unknown", foods[1].per100.kcal)
        assertNull(foods[1].group)
    }

    @Test
    fun portionsDropUnknownKeysAndBadAmounts() {
        assertEquals(
            listOf(Portion(PortionKey.UNIT, 120.0), Portion(PortionKey.SLICE, 30.0)),
            FoodCatalogParser.portions("unit:120|spoonful:5|slice:30|bowl:x|cup:0|unit:99"),
        )
        assertEquals(emptyList<Portion>(), FoodCatalogParser.portions(null))
    }

    /** The asset shipped in the app (placeholder until the food-db agent's file lands) follows the contract. */
    @Test
    fun theBundledCatalogParsesWithNamesForEveryFood() {
        val file = File("src/main/assets/food/foods.tsv")
        val text = file.readText(Charsets.UTF_8)
        assertFalse("UTF-8 with LF line ends", text.contains('\r'))
        val rows = text.lines().filter { it.isNotBlank() }.size - 1
        val foods = FoodCatalogParser.parse(text)
        assertEquals("every row parses", rows, foods.size)
        val names = File("../tools/i18n/src/master/97_foods.txt").readLines().mapNotNull { it.substringBefore(" = ", "").takeIf { k -> k.startsWith("food_") } }.toSet()
        for (f in foods) {
            assertTrue("food_${f.id} has a name", "food_${f.id}" in names)
            assertTrue("${f.id} has a group", f.group != null)
            assertTrue("${f.id} has kcal", f.per100.kcal != null)
        }
        for (g in FoodGroup.entries) assertTrue("food_group_${g.code} has a name", "food_group_${g.code}" in names)
    }

    /** Contract 1: every visual class of the bundled model has a catalog food (checked once the real catalog is in). */
    @Test
    fun everyModelLabelHasACatalogFood() {
        val spec = File("src/main/assets/food/food_model.json").takeIf { it.isFile } ?: return
        val foods = FoodCatalogParser.parse(File("src/main/assets/food/foods.tsv").readText(Charsets.UTF_8))
        if (foods.all { it.source == "placeholder" }) return // the food-db catalog replaces the placeholder at merge
        val labels = com.enjoyyourtime.globalfitness.food.recognition.ModelSpec.parse(spec.readText())
        val ids = foods.mapTo(HashSet()) { it.id }
        val missing = labels.labels.filter { it != labels.notFoodLabel && it !in ids }
        assertTrue("model labels without a catalog food: $missing", missing.isEmpty())
    }

    // ---- search ---------------------------------------------------------------------------------

    private fun item(id: String, name: String, brand: String? = null) =
        FoodItem(FoodRef.Catalog(id), name, brand, FoodUnit.G, Nutrients(kcal = 100.0), 100.0, emptyList(), null)

    @Test
    fun normalizeIgnoresCaseAccentsAndPunctuation() {
        assertEquals("platano de canarias", FoodSearch.normalize("  Plátano de Canarias! "))
        assertEquals("cafe con leche", FoodSearch.normalize("CAFÉ  con-leche"))
        assertEquals("ñ is searched as n, like the other accents", "pinones", FoodSearch.normalize("Piñones"))
        assertEquals("яблоко", FoodSearch.normalize("Яблоко"))
    }

    /** Japanese names are written in katakana or hiragana: either finds them, the voicing marks still count. */
    @Test
    fun hiraganaFindsKatakanaNames() {
        assertEquals(FoodSearch.normalize("ピザ"), FoodSearch.normalize("ぴざ"))
        assertEquals(FoodSearch.normalize("ハンバーガー"), FoodSearch.normalize("はんばーがー"))
        assertTrue("パン (bread) is not ハン", FoodSearch.normalize("パン") != FoodSearch.normalize("ハン"))
        val items = listOf(item("pizza", "ピザ"), item("bread", "パン"), item("hamburger", "ハンバーガー"), item("rice", "ご飯"))
        assertEquals(listOf("pizza"), FoodSearch.search(items, emptyMap(), "ぴざ").map { it.foodId })
        assertEquals(listOf("bread"), FoodSearch.search(items, emptyMap(), "ぱん").map { it.foodId })
        assertEquals(listOf("hamburger"), FoodSearch.search(items, emptyMap(), "はんば").map { it.foodId })
        assertEquals("kanji are left as they are", listOf("rice"), FoodSearch.search(items, emptyMap(), "ご飯").map { it.foodId })
        // Hangul is still compared letter by letter: a syllable being typed (기 on the way to 김) matches.
        assertTrue(FoodSearch.normalize("김치").startsWith(FoodSearch.normalize("기")))
    }

    @Test
    fun tiersArePrefixThenWordPrefixThenContains() {
        assertEquals(3, FoodSearch.tier("pollo asado", "pol"))
        assertEquals(2, FoodSearch.tier("pechuga de pollo", "pol"))
        assertEquals(2, FoodSearch.tier("pechuga de pollo", "poll pech"))
        assertEquals(1, FoodSearch.tier("empollon", "poll"))
        assertEquals(0, FoodSearch.tier("arroz", "pollo"))
        assertEquals(0, FoodSearch.tier("arroz", ""))
    }

    @Test
    fun searchRanksByMatchThenUsage() {
        val items = listOf(
            item("chicken_breast", "Pechuga de pollo a la plancha"),
            item("roast_chicken", "Pollo asado"),
            item("chicken_soup", "Sopa de pollo"),
            item("cabbage", "Repollo"),
            item("rice", "Arroz"),
        )
        val noUsage = FoodSearch.search(items, emptyMap(), "pollo")
        assertEquals(listOf("roast_chicken", "chicken_soup", "chicken_breast", "cabbage"), noUsage.map { (it.ref as FoodRef.Catalog).id })
        // A food the user logs comes first among prefix and word-prefix matches.
        val usage = mapOf<FoodRef, FoodUsage>(FoodRef.Catalog("chicken_breast") to FoodUsage(5, 100L), FoodRef.Catalog("cabbage") to FoodUsage(9, 200L))
        val used = FoodSearch.search(items, usage, "POLLO")
        assertEquals("chicken_breast", (used.first().ref as FoodRef.Catalog).id)
        assertEquals("a used food that only contains the query does not jump ahead", "cabbage", (used.last().ref as FoodRef.Catalog).id)
        assertEquals("accents ignored", 1, FoodSearch.search(listOf(item("banana", "Plátano")), emptyMap(), "platano").size)
    }

    @Test
    fun emptyQueryListsRecentFoodsAndBrandsAreSearched() {
        val items = listOf(item("a", "Apple"), item("b", "Banana"), item("c", "Cola", brand = "Fizzy"))
        val usage = mapOf<FoodRef, FoodUsage>(FoodRef.Catalog("a") to FoodUsage(10, 100L), FoodRef.Catalog("b") to FoodUsage(1, 500L))
        assertEquals(listOf("b", "a"), FoodSearch.search(items, usage, "  ").map { (it.ref as FoodRef.Catalog).id })
        assertEquals(listOf("c"), FoodSearch.search(items, emptyMap(), "fizzy").map { (it.ref as FoodRef.Catalog).id })
    }

    @Test
    fun usageCountsEntriesByFood() {
        val entries = listOf(testEntry(id = "1", eatenAt = 10), testEntry(id = "2", eatenAt = 30), testEntry(id = "3", eatenAt = 20).copy(foodId = null))
        assertEquals(mapOf<FoodRef, FoodUsage>(FoodRef.Catalog("paella") to FoodUsage(2, 30)), FoodSearch.usage(entries))
    }
}
