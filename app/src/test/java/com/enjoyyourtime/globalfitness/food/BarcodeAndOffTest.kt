package com.enjoyyourtime.globalfitness.food

import com.enjoyyourtime.globalfitness.food.barcode.BarcodeReader
import com.enjoyyourtime.globalfitness.food.model.CustomFood
import com.enjoyyourtime.globalfitness.food.model.CustomFoodSource
import com.enjoyyourtime.globalfitness.food.model.FoodUnit
import com.enjoyyourtime.globalfitness.food.model.Nutrient
import com.enjoyyourtime.globalfitness.food.model.Nutrients
import com.enjoyyourtime.globalfitness.food.off.OffCache
import com.enjoyyourtime.globalfitness.food.off.OffProduct
import com.enjoyyourtime.globalfitness.food.off.OffResult
import com.enjoyyourtime.globalfitness.food.off.OpenFoodFacts
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

class BarcodeAndOffTest {
    // ---- barcodes ---------------------------------------------------------------------------------

    /** A grey "photo" of a barcode: ZXing draws it, with a white margin and some grey noise. */
    private fun photoOf(code: String, format: BarcodeFormat, width: Int = 600, height: Int = 300): Triple<ByteArray, Int, Int> {
        val matrix = MultiFormatWriter().encode(code, format, width - 80, height - 120)
        val out = ByteArray(width * height) { 230.toByte() }
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix.get(x, y)) out[(y + 60) * width + x + 40] = (20 + (x * 7 + y * 3) % 30).toByte()
        }
        return Triple(out, width, height)
    }

    private fun rotate(src: ByteArray, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) out[x * h + (h - 1 - y)] = src[y * w + x]
        return out
    }

    @Test
    fun readsEan13AndEan8() {
        val (ean13, w, h) = photoOf("8410000810004", BarcodeFormat.EAN_13)
        assertEquals("8410000810004", BarcodeReader.decode(ean13, w, h))
        val (ean8, w8, h8) = photoOf("96385074", BarcodeFormat.EAN_8)
        assertEquals("96385074", BarcodeReader.decode(ean8, w8, h8))
    }

    @Test
    fun readsABarcodeTurned90Degrees() {
        val (img, w, h) = photoOf("4006381333931", BarcodeFormat.EAN_13)
        // The photo was taken with the phone turned: the bars run horizontally.
        assertEquals("4006381333931", BarcodeReader.decode(rotate(img, w, h), h, w))
    }

    @Test
    fun upcEIsLookedUpAsUpcA() {
        val (img, w, h) = photoOf("01234565", BarcodeFormat.UPC_E)
        assertEquals("012345000065", BarcodeReader.decode(img, w, h))
        assertEquals("012345000065", BarcodeReader.normalize("01234565", BarcodeFormat.UPC_E))
        assertNull(BarcodeReader.normalize("12AB", BarcodeFormat.EAN_8))
    }

    @Test
    fun noBarcodeInThePhoto() {
        val blank = ByteArray(400 * 300) { ((it * 31) % 256).toByte() }
        assertNull(BarcodeReader.decode(blank, 400, 300))
    }

    @Test
    fun oneFormPerProduct() {
        assertEquals("0036000291452", BarcodeReader.canonical("036000291452"))
        assertEquals("8410000810004", BarcodeReader.canonical("08410000810004"))
        assertEquals("8410000810004", BarcodeReader.canonical("8410000810004"))
        assertEquals("96385074", BarcodeReader.canonical("96385074"))
    }

    @Test
    fun luminanceAndCheckDigits() {
        val grey = BarcodeReader.luminanceOf(intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFF0000.toInt()), 3, 1)
        assertEquals(255, grey[0].toInt() and 0xFF)
        assertEquals(0, grey[1].toInt() and 0xFF)
        assertEquals(76, grey[2].toInt() and 0xFF)
        assertTrue(BarcodeReader.isValidCode("8410000810004"))
        assertTrue(BarcodeReader.isValidCode("96385074"))
        assertTrue(BarcodeReader.isValidCode("036000291452"))
        assertFalse(BarcodeReader.isValidCode("8410000810005"))
        assertFalse(BarcodeReader.isValidCode("12345"))
    }

    // ---- Open Food Facts ----------------------------------------------------------------------------

    private val complete = """
        {"code":"8410000810004","status":1,"status_verbose":"product found","product":{
          "code":"8410000810004","product_name":"Galletas María","product_name_es":"Galletas María","product_name_en":"Marie biscuits",
          "brands":"Marca A, Marca B","quantity":"800 g","serving_quantity":"25",
          "nutriments":{"energy-kcal_100g":436,"proteins_100g":7,"carbohydrates_100g":71,"sugars_100g":20,
            "fat_100g":12,"saturated-fat_100g":1.4,"fiber_100g":"3,2","salt_100g":0.75,"energy_100g":1830}}}
    """.trimIndent()

    @Test
    fun parsesACompleteProductInTheUsersLanguage() {
        val r = OpenFoodFacts.parse(complete, "8410000810004", "es") as OffResult.Found
        val p = r.product
        assertEquals("Galletas María", p.name)
        assertEquals("Marca A", p.brand)
        assertEquals(FoodUnit.G, p.unit)
        assertEquals(25.0, p.serving)
        assertEquals(436.0, p.per100.kcal)
        assertEquals(1.4, p.per100.satFat)
        assertEquals("numbers given as text with a comma", 3.2, p.per100.fiber)
        assertEquals(0.75, p.per100.salt)
        assertTrue(p.missing.isEmpty())
        assertEquals("the name in the user's language", "Marie biscuits", (OpenFoodFacts.parse(complete, "x", "en") as OffResult.Found).product.name)
        assertEquals("else the main name", "Galletas María", (OpenFoodFacts.parse(complete, "x", "fr") as OffResult.Found).product.name)
    }

    @Test
    fun missingNutrientsAreUnknownAndDerivedWhenPossible() {
        val json = """{"status":1,"product":{"product_name":"Zumo","quantity":"1 l",
            "nutriments":{"energy_100g":180,"carbohydrates_100g":10.4,"sodium_100g":0.004,"fat_100g":-1}}}"""
        val p = (OpenFoodFacts.parse(json, "123", "es") as OffResult.Found).product
        assertEquals(FoodUnit.ML, p.unit)
        assertEquals("kJ only → kcal", 43.0, p.per100.kcal)
        val kjAsEntered = """{"status":1,"product":{"nutriments":{"energy_100g":418.4,"energy_unit":"kcal","energy_value":100}}}"""
        assertEquals("energy_100g is kJ whatever unit was entered", 100.0, (OpenFoodFacts.parse(kjAsEntered, "1", "es") as OffResult.Found).product.per100.kcal)
        assertEquals("sodium → salt", 0.01, p.per100.salt)
        assertNull("negative is unknown", p.per100.fat)
        assertEquals("123", p.barcode)
        assertEquals(listOf(Nutrient.PROTEIN, Nutrient.SUGARS, Nutrient.FAT, Nutrient.SAT_FAT, Nutrient.FIBER), p.missing)
        val noName = """{"status":1,"product":{"nutriments":{}}}"""
        assertNull((OpenFoodFacts.parse(noName, "1", "es") as OffResult.Found).product.name)
    }

    @Test
    fun notFoundAndBadAnswers() {
        assertEquals(OffResult.NotFound, OpenFoodFacts.parse("""{"code":"1","status":0,"status_verbose":"product not found"}""", "1", "es"))
        assertTrue(OpenFoodFacts.parse("<html>", "1", "es") is OffResult.Failed)
    }

    @Test
    fun theRequestSendsOnlyTheBarcodeAndAsksForFewFields() {
        val url = OpenFoodFacts.url("8410000810004", "es")
        assertTrue(url.startsWith("https://world.openfoodfacts.org/api/v2/product/8410000810004?fields="))
        assertTrue(url.contains("product_name_es") && url.contains("nutriments") && url.endsWith("&lc=es"))
        assertTrue("a bad language code falls back to English", OpenFoodFacts.url("1", "x-y").endsWith("&lc=en"))
    }

    @Test
    fun httpOutcomes() = runTest {
        var sentAgent: String? = null
        fun client(answer: () -> OpenFoodFacts.HttpAnswer) = OpenFoodFacts("Fortia/1.5.0 (enjoyyourtime.online@gmail.com)", http = { _, ua ->
            sentAgent = ua
            answer()
        }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        assertTrue(client { OpenFoodFacts.HttpAnswer(200, complete) }.product("8410000810004", "es") is OffResult.Found)
        assertEquals("Fortia/1.5.0 (enjoyyourtime.online@gmail.com)", sentAgent)
        assertEquals(OffResult.NotFound, client { OpenFoodFacts.HttpAnswer(404, """{"status":0}""") }.product("1", "es"))
        assertEquals(OffResult.Offline, client { throw UnknownHostException() }.product("1", "es"))
        assertEquals(OffResult.Offline, client { throw IOException("reset") }.product("1", "es"))
        assertTrue(client { OpenFoodFacts.HttpAnswer(503, null) }.product("1", "es") is OffResult.Failed)
        assertEquals("not a barcode: nothing is sent", OffResult.NotFound, client { error("no request expected") }.product("12a", "es"))
    }

    // ---- products kept from a barcode ------------------------------------------------------------------

    private val day = 24L * 60 * 60_000

    private fun kept(name: String = "Galletas", kcal: Double? = 436.0, source: CustomFoodSource = CustomFoodSource.OFF, updatedAt: Long = 0, unit: FoodUnit = FoodUnit.G) =
        CustomFood("c1", name, null, "8410000000000", unit, Nutrients(kcal = kcal, fiber = 3.0), 25.0, source, 0, updatedAt)

    @Test
    fun aKeptProductIsAskedAgainWhenIncompleteOrOld() {
        val now = 200 * day
        assertFalse(OffCache.needsRefresh(kept(updatedAt = now - 10 * day), now))
        assertTrue("old", OffCache.needsRefresh(kept(updatedAt = now - 91 * day), now))
        assertTrue("no name (the code stands for it)", OffCache.needsRefresh(kept(name = "8410000000000", updatedAt = now), now))
        assertTrue("no kcal", OffCache.needsRefresh(kept(kcal = null, updatedAt = now), now))
        assertFalse("the user's own values are not replaced for their age", OffCache.needsRefresh(kept(source = CustomFoodSource.USER, updatedAt = now - 300 * day), now))
        assertFalse("a food without barcode", OffCache.needsRefresh(kept(updatedAt = 0).copy(barcode = null), now))
    }

    @Test
    fun aRefreshTakesTheNewValuesAndKeepsTheUsersOnes() {
        val cached = kept(name = "8410000000000", kcal = null)
        val p = OffProduct("8410000000000", "Galletas María", "Marca", FoodUnit.G, Nutrients(kcal = 440.0, sugars = 22.0), serving = 30.0)
        val r = OffCache.refreshed(cached, p, now = 99)
        assertEquals("Galletas María", r.name)
        assertEquals("Marca", r.brand)
        assertEquals(Nutrients(kcal = 440.0, sugars = 22.0, fiber = 3.0), r.per100)
        assertEquals(30.0, r.portion!!, 0.0)
        assertEquals(CustomFoodSource.OFF, r.source)
        assertEquals("marked checked even when nothing changed", 120L, OffCache.refreshed(r, p, now = 120).updatedAt)
        // Values per 100 ml do not mix with values per 100 g.
        val drink = OffCache.refreshed(cached, p.copy(unit = FoodUnit.ML, serving = null), now = 99)
        assertEquals(Nutrients(kcal = 440.0, sugars = 22.0), drink.per100)
        assertNull(drink.portion)
        // Nothing new (a product page without values): the kept values stay as they are.
        val empty = OffCache.refreshed(kept(), OffProduct("8410000000000", null, null, FoodUnit.ML, Nutrients.NONE, null), now = 5)
        assertEquals(kept().per100, empty.per100)
        assertEquals(FoodUnit.G, empty.unit)
        assertEquals("Galletas", empty.name)
    }
}
