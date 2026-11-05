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
import com.enjoyyourtime.globalfitness.food.off.PhotoResult
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
        // Android's legacy code for Indonesian is "in"; Open Food Facts uses "id".
        val indonesian = OpenFoodFacts.url("1", "in")
        assertTrue(indonesian.contains("product_name_id") && indonesian.endsWith("&lc=id"))
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
        // The last check counts, not the last change: a product asked again that was still the same.
        assertFalse("checked lately", OffCache.needsRefresh(kept(updatedAt = now - 300 * day).copy(checkedAt = now - 10 * day), now))
        assertTrue("checked long ago", OffCache.needsRefresh(kept(updatedAt = now - 300 * day).copy(checkedAt = now - 91 * day), now))
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
        assertEquals(99L, r.checkedAt)
        // Asked again, nothing new: only the check time moves (no new version, see FoodRepository.updateCustomFood).
        val again = OffCache.refreshed(r, p, now = 120)
        assertEquals(120L, again.checkedAt)
        assertEquals(r.copy(checkedAt = 120L), again)
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

    // ---- the product's photo ---------------------------------------------------------------------------

    private val front = "https://images.openfoodfacts.org/images/products/301/762/042/2003/front_es.621.400.jpg"
    private val frontSmall = "https://images.openfoodfacts.org/images/products/301/762/042/2003/front_es.621.200.jpg"

    private fun withImages(fields: String) = """{"status":1,"product":{"product_name":"Nutella","nutriments":{"energy-kcal_100g":539}$fields}}"""

    @Test
    fun theRequestAsksForTheFrontPhotoInTheUsersLanguage() {
        val url = OpenFoodFacts.url("3017620422003", "es")
        assertTrue(url.contains("image_front_url") && url.contains("image_front_small_url"))
        assertTrue("lc picks the photo's language", url.endsWith("&lc=es"))
    }

    @Test
    fun parsesTheFrontPhotoAddress() {
        fun image(fields: String) = (OpenFoodFacts.parse(withImages(fields), "3017620422003", "es") as OffResult.Found).product.imageUrl
        assertEquals("the 400 px one", front, image(""","image_front_url":"$front","image_front_small_url":"$frontSmall""""))
        assertEquals("else the 200 px one", frontSmall, image(""","image_front_small_url":"$frontSmall""""))
        assertNull("no photo", image(""))
        assertNull("blank", image(""","image_front_url":" """"))
        assertEquals("not over HTTPS: the other one", frontSmall, image(""","image_front_url":"http://images.openfoodfacts.org/a.jpg","image_front_small_url":"$frontSmall""""))
        assertNull("another server", image(""","image_front_url":"https://example.com/front.jpg""""))
        assertNull("a look-alike server", image(""","image_front_url":"https://openfoodfacts.org.example.com/front.jpg""""))
        assertNull((OpenFoodFacts.parse(complete, "8410000810004", "es") as OffResult.Found).product.imageUrl)
    }

    @Test
    fun onlyOpenFoodFactsServersOverHttpsServePhotos() {
        assertTrue(OpenFoodFacts.isImageUrl(front))
        assertTrue(OpenFoodFacts.isImageUrl("https://static.openfoodfacts.org/images/x.jpg"))
        assertTrue(OpenFoodFacts.isImageUrl("https://openfoodfacts.org/x.jpg"))
        assertFalse(OpenFoodFacts.isImageUrl("http://images.openfoodfacts.org/x.jpg"))
        assertFalse(OpenFoodFacts.isImageUrl("https://images.openfoodfacts.org:8443/x.jpg"))
        assertFalse(OpenFoodFacts.isImageUrl("https://user@images.openfoodfacts.org/x.jpg"))
        assertFalse(OpenFoodFacts.isImageUrl("https://evilopenfoodfacts.org/x.jpg"))
        assertFalse(OpenFoodFacts.isImageUrl("file:///data/x.jpg"))
        assertFalse(OpenFoodFacts.isImageUrl("not a url"))
    }

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A, 0, 0)
    private val webp = "RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)

    @Test
    fun aPhotoIsKeptOnlyWhenItIsAnImageOfAllowedSize() {
        fun answer(body: ByteArray?, type: String? = "image/jpeg", code: Int = 200) = OpenFoodFacts.imageOf(OpenFoodFacts.ImageAnswer(code, type, body))
        assertTrue(jpeg.contentEquals(answer(jpeg)))
        assertTrue("a type with parameters", jpeg.contentEquals(answer(jpeg, "Image/JPEG; charset=binary")))
        assertTrue(png.contentEquals(answer(png, "image/png")))
        assertTrue(webp.contentEquals(answer(webp, "image/webp")))
        assertNull("not an image type", answer(jpeg, "text/html"))
        assertNull("no type", answer(jpeg, null))
        assertNull("an image type, but not image bytes", answer("<html>".toByteArray(), "image/jpeg"))
        assertNull("an SVG is not kept", answer("<svg/>".toByteArray(), "image/svg+xml"))
        assertNull("empty", answer(ByteArray(0)))
        assertNull("not read (too large while reading)", answer(null))
        assertNull("not 200", answer(jpeg, code = 404))
        val large = ByteArray(OpenFoodFacts.MAX_IMAGE_BYTES + 1).also { jpeg.copyInto(it) }
        assertNull("over 2 MB", answer(large))
        val largest = ByteArray(OpenFoodFacts.MAX_IMAGE_BYTES).also { jpeg.copyInto(it) }
        assertTrue("2 MB exactly", largest.contentEquals(answer(largest)))
    }

    /** A client whose image server answers [answers] by address (anything else: a test failure). */
    private fun imageClient(asked: MutableList<String>, sentAgent: (String) -> Unit = {}, answers: (String) -> OpenFoodFacts.ImageAnswer) = OpenFoodFacts(
        "Fortia/1.7.0 (enjoyyourtime.online@gmail.com)",
        http = { _, _ -> error("no product request expected") },
        io = kotlinx.coroutines.Dispatchers.Unconfined,
        imageHttp = { url, ua ->
            asked += url
            sentAgent(ua)
            answers(url)
        },
    )

    @Test
    fun photoDownloadOutcomes() = runTest {
        val asked = mutableListOf<String>()
        var agent: String? = null
        fun client(answer: () -> OpenFoodFacts.ImageAnswer) = imageClient(asked, { agent = it }) { answer() }
        val photo = client { OpenFoodFacts.ImageAnswer(200, "image/jpeg", jpeg) }.image(front)
        assertTrue(jpeg.contentEquals((photo as PhotoResult.Photo).bytes))
        assertEquals("only the photo's address is asked", listOf(front), asked)
        assertEquals("Fortia/1.7.0 (enjoyyourtime.online@gmail.com)", agent)
        // Failures say why, for the log (never the address).
        assertEquals("offline", PhotoResult.Failed("UnknownHostException"), client { throw UnknownHostException() }.image(front))
        assertEquals("cut", PhotoResult.Failed("IOException"), client { throw IOException("reset") }.image(front))
        assertEquals(PhotoResult.Failed("not an image"), client { OpenFoodFacts.ImageAnswer(200, "text/html", "<html>".toByteArray()) }.image(front))
        assertEquals(PhotoResult.Failed("too large or unreadable"), client { OpenFoodFacts.ImageAnswer(200, "image/jpeg", null) }.image(front))
        assertEquals("server error: tried again later", PhotoResult.Failed("HTTP 503"), client { OpenFoodFacts.ImageAnswer(503, null, null) }.image(front))
        assertEquals("no longer there", PhotoResult.Gone, client { OpenFoodFacts.ImageAnswer(404, "text/html", null) }.image(front))
        assertEquals("gone", PhotoResult.Gone, client { OpenFoodFacts.ImageAnswer(410, null, null) }.image(front))
        asked.clear()
        val outside = PhotoResult.Failed("not an Open Food Facts address")
        assertEquals("another server: nothing is asked", outside, client { error("no request expected") }.image("https://example.com/a.jpg"))
        assertEquals("not HTTPS: nothing is asked", outside, client { error("no request expected") }.image("http://images.openfoodfacts.org/a.jpg"))
        assertTrue(asked.isEmpty())
    }

    @Test
    fun redirectsAreFollowedOnlyToOpenFoodFactsAndOnlyTwice() = runTest {
        val moved = "https://static.openfoodfacts.org/images/products/301/762/042/2003/front_es.621.400.jpg"
        val asked = mutableListOf<String>()
        fun redirect(to: String) = OpenFoodFacts.ImageAnswer(301, null, null, location = to)
        val ok = OpenFoodFacts.ImageAnswer(200, "image/jpeg", jpeg)

        val once = imageClient(asked) { url -> if (url == front) redirect(moved) else ok }.image(front)
        assertTrue("to another Open Food Facts server", once is PhotoResult.Photo)
        assertEquals(listOf(front, moved), asked)

        asked.clear()
        val relative = imageClient(asked) { url -> if (url == front) redirect("/images/x.jpg") else ok }.image(front)
        assertTrue("a relative address stays on that server", relative is PhotoResult.Photo)
        assertEquals("https://images.openfoodfacts.org/images/x.jpg", asked.last())

        asked.clear()
        val outside = imageClient(asked) { url -> if (url == front) redirect("https://example.com/x.jpg") else error("never asked: $url") }.image(front)
        assertEquals("never to another server", PhotoResult.Failed("redirect to another server"), outside)
        assertEquals(listOf(front), asked)

        asked.clear()
        val downgraded = imageClient(asked) { url -> if (url == front) redirect("http://images.openfoodfacts.org/x.jpg") else error("never asked: $url") }.image(front)
        assertEquals("never to plain HTTP", PhotoResult.Failed("redirect to another server"), downgraded)

        asked.clear()
        val hops = listOf(front, "https://images.openfoodfacts.org/2.jpg", "https://images.openfoodfacts.org/3.jpg", "https://images.openfoodfacts.org/4.jpg")
        val loop = imageClient(asked) { url -> redirect(hops[hops.indexOf(url) + 1]) }.image(front)
        assertEquals("at most two redirects", PhotoResult.Failed("too many redirects"), loop)
        assertEquals(hops.take(3), asked)

        asked.clear()
        val noLocation = imageClient(asked) { OpenFoodFacts.ImageAnswer(302, null, null) }.image(front)
        assertEquals("a redirect to nowhere", PhotoResult.Failed("HTTP 302"), noLocation)

        asked.clear()
        val broken = imageClient(asked) { redirect("https://images.openfoodfacts.org/a b|c") }.image(front)
        assertEquals("a redirect that is not an address", PhotoResult.Failed("bad redirect"), broken)
    }

    @Test
    fun aProductKeepsThePhotoAddressOpenFoodFactsGives() {
        val p = OffProduct("3017620422003", "Nutella", "Ferrero", FoodUnit.G, Nutrients(kcal = 539.0), 15.0, imageUrl = front)
        val stored = OffCache.customFoodOf(p, "3017620422003")
        assertEquals(front, stored.imageUrl)
        assertNull("downloaded afterwards", stored.image)
        assertEquals("asked: none", "", OffCache.customFoodOf(p.copy(imageUrl = null), "3017620422003").imageUrl)

        val withFile = stored.copy(image = "off_1.jpg")
        assertEquals("the same address keeps the photo", withFile, OffCache.withImage(withFile, front))
        val moved = OffCache.withImage(withFile, frontSmall)
        assertEquals(frontSmall, moved.imageUrl)
        assertNull("a new address: the old photo goes", moved.image)
        val gone = OffCache.withImage(withFile, null)
        assertEquals("", gone.imageUrl)
        assertNull(gone.image)
        val own = withFile.copy(source = CustomFoodSource.USER, imageUrl = null, image = null)
        assertEquals("the user's own food never has one", own, OffCache.withImage(own, front))

        // A refresh: the photo follows Open Food Facts; asked again with nothing new, it stays.
        val refreshed = OffCache.refreshed(withFile, p, now = 50)
        assertEquals("off_1.jpg", refreshed.image)
        assertEquals(front, refreshed.imageUrl)
        val newPhoto = OffCache.refreshed(withFile, p.copy(imageUrl = frontSmall), now = 50)
        assertEquals(frontSmall, newPhoto.imageUrl)
        assertNull(newPhoto.image)
        val userFood = kept(name = "8410000000000", kcal = null, source = CustomFoodSource.USER)
        assertEquals("a food of the user's that Open Food Facts now knows becomes its product, photo included",
            front, OffCache.refreshed(userFood, p, now = 50).imageUrl)
    }

    @Test
    fun whenAPhotoIsAskedForOrDownloaded() {
        val stored = kept().copy(imageUrl = front)
        val none = setOf<String>()
        assertTrue("kept by an older version: asked once", OffCache.needsImageCheck(kept()))
        assertFalse("asked already", OffCache.needsImageCheck(stored))
        assertFalse("asked already: none", OffCache.needsImageCheck(kept().copy(imageUrl = "")))
        assertFalse("the user's own food", OffCache.needsImageCheck(kept(source = CustomFoodSource.USER)))
        assertFalse("no barcode", OffCache.needsImageCheck(kept().copy(barcode = null)))

        assertEquals("not downloaded yet", front, OffCache.imageToFetch(stored, none::contains))
        assertEquals("its file is gone (a restored database)", front, OffCache.imageToFetch(stored.copy(image = "off_1.jpg"), none::contains))
        assertNull("its file is there", OffCache.imageToFetch(stored.copy(image = "off_1.jpg"), setOf("off_1.jpg")::contains))
        assertNull("none on Open Food Facts", OffCache.imageToFetch(kept().copy(imageUrl = ""), none::contains))
        assertNull("not asked yet", OffCache.imageToFetch(kept(), none::contains))
        assertNull("the user's own food", OffCache.imageToFetch(stored.copy(source = CustomFoodSource.USER), none::contains))
    }
}
