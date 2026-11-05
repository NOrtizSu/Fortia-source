package com.enjoyyourtime.globalfitness.food

import com.enjoyyourtime.globalfitness.food.barcode.BarcodeReader
import com.enjoyyourtime.globalfitness.food.model.FoodItem
import com.enjoyyourtime.globalfitness.food.model.FoodUnit
import com.enjoyyourtime.globalfitness.food.model.Nutrients
import com.enjoyyourtime.globalfitness.food.pack.PackFind
import com.enjoyyourtime.globalfitness.food.pack.PackScore
import com.enjoyyourtime.globalfitness.food.pack.PackageText
import com.enjoyyourtime.globalfitness.food.pack.TextLine
import com.enjoyyourtime.globalfitness.food.pack.TextScript
import com.enjoyyourtime.globalfitness.food.recognition.Recognition
import com.enjoyyourtime.globalfitness.food.recognition.Suggestion
import com.enjoyyourtime.globalfitness.ui.food.BarcodeState
import com.enjoyyourtime.globalfitness.ui.food.PackText
import com.enjoyyourtime.globalfitness.ui.food.PhotoPackRules
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * A packaged product on a meal photo: the barcode read on the whole photo, and the words read on the
 * package turned into an Open Food Facts search (texts as ML Kit gives them for real packages: one
 * entry per line, with its top and height as shares of the photo's height).
 */
class PackageTextTest {
    // ---- a barcode on the meal photo ------------------------------------------------------------

    /** A 1280×960 grey "meal photo" (textured, noisy) with, when given, a barcode printed small somewhere in it. */
    private fun mealPhoto(code: String?, x: Int = 820, y: Int = 610, rotated: Boolean = false, format: BarcodeFormat = BarcodeFormat.EAN_13): ByteArray {
        val w = 1280
        val h = 960
        val random = Random(7)
        val out = ByteArray(w * h) { i -> (110 + ((i % w) / 40 + (i / w) / 30) % 5 * 20 + random.nextInt(-12, 12)).toByte() }
        if (code != null) {
            val bw = 300
            val bh = 150
            val matrix = MultiFormatWriter().encode(code, format, bw, bh)
            // A white label with the bars on it.
            for (yy in -20 until bh + 20) for (xx in -20 until bw + 20) {
                val px = if (rotated) x + yy else x + xx
                val py = if (rotated) y + xx else y + yy
                if (px !in 0 until w || py !in 0 until h) continue
                val bar = xx in 0 until matrix.width && yy in 0 until matrix.height && matrix.get(xx, yy)
                out[py * w + px] = (if (bar) 25 + random.nextInt(0, 20) else 225 + random.nextInt(0, 20)).toByte()
            }
        }
        return out
    }

    private val meal = BarcodeReader.MEAL_PHOTO_FORMATS

    @Test
    fun aBarcodeAnywhereOnTheMealPhotoIsRead() {
        assertEquals("8410000807363", BarcodeReader.decode(mealPhoto("8410000807363"), 1280, 960, meal))
        // On its side (the package lies on the table the other way).
        assertEquals("8410000807363", BarcodeReader.decode(mealPhoto("8410000807363", x = 200, y = 300, rotated = true), 1280, 960, meal))
    }

    @Test
    fun aMealPhotoWithoutBarcodeReadsNone() {
        assertNull(BarcodeReader.decode(mealPhoto(null), 1280, 960, meal))
    }

    @Test
    fun upcEIsOnlyReadOnABarcodePhoto() {
        val photo = mealPhoto("01234565", format = BarcodeFormat.UPC_E)
        // A meal photo is not aimed at a barcode: UPC-E's few digits are too easily "found" in it.
        assertNull(BarcodeReader.decode(photo, 1280, 960, meal))
        // The barcode page reads it (as its UPC-A).
        assertEquals("012345000065", BarcodeReader.decode(photo, 1280, 960, BarcodeReader.FORMATS))
    }

    // ---- the words on the package -------------------------------------------------------------------

    private fun line(text: String, top: Float, height: Float) = TextLine(text, top, height)

    @Test
    fun biscuitBox() {
        // Fontaneda "La Buena María" (front of the box): brand, name, a claim, the weight, the nutrition strip.
        val lines = listOf(
            line("Fontaneda", 0.08f, 0.09f),
            line("La Buena", 0.20f, 0.10f),
            line("MARÍA", 0.31f, 0.13f),
            line("GALLETAS", 0.47f, 0.035f),
            line("Receta original desde 1881", 0.52f, 0.025f),
            line("800g", 0.86f, 0.04f),
            line("Por 100 g: Valor energético 1852 kJ / 442 kcal", 0.93f, 0.012f),
            line("Grasas 13 g · Azúcares 21 g · Sal 0,6 g", 0.95f, 0.012f),
        )
        assertEquals("fontaneda buena maría", PackageText.query(lines))
    }

    @Test
    fun drinkCan() {
        // Coca-Cola Zero: the logo, "zero", "sin azúcar", the volume and the small print.
        val lines = listOf(
            line("Coca-Cola", 0.30f, 0.16f),
            line("zero", 0.48f, 0.08f),
            line("sin azúcar", 0.58f, 0.03f),
            line("330 ml", 0.85f, 0.03f),
            line("Contiene cafeína. Consumir preferentemente antes del: ver base", 0.90f, 0.01f),
        )
        // "sin azúcar" is a claim in small print: a nutrient word off the name.
        assertEquals("coca-cola zero", PackageText.query(lines))
    }

    @Test
    fun aBrandWithNutritionInItsNameStays() {
        // Optimum Nutrition's whey: "Nutrition" is the brand, not the nutrition table.
        val lines = listOf(
            line("OPTIMUM NUTRITION", 0.08f, 0.06f),
            line("GOLD STANDARD", 0.18f, 0.10f),
            line("100% WHEY", 0.30f, 0.09f),
            line("Nutrition Facts per 30 g serving", 0.90f, 0.012f),
        )
        assertEquals("optimum nutrition gold standard whey", PackageText.query(lines))
    }

    @Test
    fun aNutrientInTheNameStaysInSmallPrintGoes() {
        // A protein yogurt: "Protein" is its name; "Proteínas 10 g" on the side is the table.
        val lines = listOf(
            line("YOPRO", 0.10f, 0.14f),
            line("High Protein", 0.28f, 0.08f),
            line("Proteínas 10 g", 0.70f, 0.02f),
        )
        assertEquals("yopro high protein", PackageText.query(lines))
    }

    @Test
    fun logosTheRecognizerIsUnsureOfAreLeftOut() {
        // What the emulator's ML Kit read on La Lechera's yogurt pack (text, top, height, confidence): the
        // script logo is the tallest text but misread, with low confidence; the printed name is sure.
        val yogurt = listOf(
            TextLine("Sechera", 0.20f, 0.072f, 0.52f),
            TextLine("Neste", 0.15f, 0.021f, 0.36f),
            TextLine("YOGUR", 0.38f, 0.0195f, 0.80f),
            TextLine("NATURAL", 0.42f, 0.035f, 0.75f),
            TextLine("O114 BC", 0.32f, 0.040f, 0.31f),
            TextLine("echera", 0.60f, 0.088f, 0.59f),
            TextLine("YOGUR", 0.78f, 0.018f, 0.77f),
            TextLine("NATURAL", 0.82f, 0.034f, 0.76f),
        )
        assertEquals("yogur natural", PackageText.query(yogurt))
        // The biscuits: "FONTANEDA" is sure, "María" in script was read as "1ARIA".
        val biscuits = listOf(TextLine("FONTANEDA", 0.35f, 0.051f, 0.73f), TextLine("7A", 0.55f, 0.020f, 0.51f), TextLine("1ARIA", 0.45f, 0.064f, 0.49f))
        assertEquals("fontaneda", PackageText.query(biscuits))
        // Only unsure lines: they are used rather than nothing.
        assertEquals("sechera", PackageText.query(listOf(TextLine("Sechera", 0.2f, 0.07f, 0.52f))))
    }

    @Test
    fun otherScriptsAreReadAsWords() {
        // Chinese: no spaces, a line is one word.
        assertEquals("康师傅 红烧牛肉面", PackageText.query(listOf(line("康师傅", 0.1f, 0.1f), line("红烧牛肉面", 0.25f, 0.12f))))
        // Hindi: the vowel signs stay inside the word ("नमकीन", not "नमक" + "न").
        assertEquals("हल्दीराम नमकीन", PackageText.query(listOf(line("हल्दीराम", 0.1f, 0.1f), line("नमकीन", 0.25f, 0.08f))))
    }

    @Test
    fun yogurtPack() {
        // Danone Activia (a pack of four): small brand above a big name; the pack size and claims.
        val lines = listOf(
            line("DANONE", 0.05f, 0.04f),
            line("Activia", 0.12f, 0.15f),
            line("Natural", 0.30f, 0.06f),
            line("4 x 120 g", 0.80f, 0.03f),
            line("Bífidus ActiRegularis", 0.40f, 0.025f),
            line("Información nutricional por 100 g", 0.92f, 0.012f),
        )
        // The brand is small but within reach of the name: kept.
        assertEquals("danone activia natural", PackageText.query(lines))
    }

    @Test
    fun crispsBagOfAStoreBrand() {
        val lines = listOf(
            line("HACENDADO", 0.06f, 0.05f),
            line("Patatas fritas", 0.16f, 0.09f),
            line("lisas", 0.27f, 0.06f),
            line("NUEVO", 0.04f, 0.03f),
            line("160 g", 0.88f, 0.03f),
            line("Consumir preferentemente antes del: ver lateral", 0.93f, 0.01f),
            line("www.mercadona.es", 0.96f, 0.01f),
        )
        assertEquals("hacendado patatas fritas lisas", PackageText.query(lines))
    }

    @Test
    fun englishLabel() {
        val lines = listOf(
            line("Heinz", 0.10f, 0.08f),
            line("Tomato Ketchup", 0.22f, 0.10f),
            line("Nutrition Information per 100g Energy 427kJ / 102kcal", 0.85f, 0.012f),
            line("Ingredients: tomatoes (148g per 100g Tomato Ketchup), spirit vinegar, sugar, salt", 0.88f, 0.012f),
        )
        assertEquals("heinz tomato ketchup", PackageText.query(lines))
    }

    @Test
    fun aBrandWithDigitsStaysAQuantityGoes() {
        val lines = listOf(line("7UP", 0.2f, 0.12f), line("Free", 0.35f, 0.08f), line("1,5L", 0.8f, 0.04f), line("L:2304B 12/2027", 0.9f, 0.01f))
        assertEquals("7up free", PackageText.query(lines))
    }

    @Test
    fun atMostFiveWordsEachOnce() {
        val lines = listOf(
            line("Gullón Gullón", 0.1f, 0.1f),
            line("Digestive avena chocolate negro sin azúcares añadidos", 0.25f, 0.09f),
        )
        val q = PackageText.query(lines)!!
        assertEquals(listOf("gullón", "digestive", "avena", "chocolate", "negro"), q.split(' '))
    }

    @Test
    fun nothingFoundSearchesFewerWordsOnce() {
        // "María" in a script font, read by the emulator's text recognition as "1aria" (seen on the emulator).
        assertEquals("fontaneda", PackageText.broader("fontaneda 1aria"))
        // All plain words: the longest one.
        assertEquals("hacendado", PackageText.broader("hacendado patatas lisas"))
        // One word: nothing broader.
        assertNull(PackageText.broader("activia"))
        assertNull(PackageText.broader("7up 1l"))
    }

    @Test
    fun noWordsNoSearch() {
        assertNull(PackageText.query(emptyList()))
        // Only numbers, units and the nutrition table.
        assertNull(PackageText.query(listOf(line("250 g", 0.5f, 0.05f), line("Valor energético 1852 kJ", 0.8f, 0.02f), line("12/2027", 0.9f, 0.02f))))
        // Two-letter words only.
        assertNull(PackageText.query(listOf(line("OK TV", 0.5f, 0.05f))))
    }

    // ---- when the photo page looks by itself (PhotoPackRules: the view model follows it) ---------------

    private val noFood = Recognition(emptyList(), notFood = true, topLabel = "_not_food", topConfidence = 0.9f)
    private val weak = Recognition(listOf(Suggestion("cookies", 0.12f)), notFood = false, topLabel = "cookies", topConfidence = 0.12f)
    private val sure = Recognition(listOf(Suggestion("cookies", 0.61f)), notFood = false, topLabel = "cookies", topConfidence = 0.61f)
    private val found = BarcodeState.Found("8410000807363", FoodItem(null, "La Buena María", null, FoodUnit.G, Nutrients.NONE, null, emptyList(), null), emptyList(), fromCache = true)

    @Test
    fun enoughTextAndAWeakGuess() {
        assertTrue(PackageText.enough("coca-cola zero"))
        assertTrue(PackageText.enough("activia"))
        assertFalse(PackageText.enough("bio"))
        assertFalse(PackageText.enough(null))
        assertFalse(PackageText.enough(""))
        assertTrue(PackageText.weakGuess(noFood))
        assertTrue(PackageText.weakGuess(weak))
        // No model at all: the words decide.
        assertTrue(PackageText.weakGuess(null))
        assertFalse(PackageText.weakGuess(sure))
    }

    @Test
    fun onlyAFoundBarcodeDecides() {
        assertTrue(PhotoPackRules.barcodeDecides(found))
        // Unknown to Open Food Facts, offline or failed: the scanned products and the words still help.
        assertFalse(PhotoPackRules.barcodeDecides(BarcodeState.NotFound("8410000807363")))
        assertFalse(PhotoPackRules.barcodeDecides(BarcodeState.Offline("8410000807363")))
        assertFalse(PhotoPackRules.barcodeDecides(BarcodeState.Failed("8410000807363")))
        assertFalse(PhotoPackRules.barcodeDecides(null))
    }

    @Test
    fun theBarcodePageOpensByItselfOnlyFoundAndBeforeTheUserLeaves() {
        // Found while the photo page is still coming in, or in front: it opens (once the page is resumed).
        assertTrue(PhotoPackRules.opensBarcodePage(found, pageLeft = false))
        // The user went on (or the page was covered): the card shows it instead.
        assertFalse(PhotoPackRules.opensBarcodePage(found, pageLeft = true))
        assertFalse(PhotoPackRules.opensBarcodePage(BarcodeState.NotFound("8410000807363"), pageLeft = false))
    }

    @Test
    fun theWordsAreReadByThemselvesOnlyForALoneWeakGuess() {
        fun reads(
            plate: Boolean = false,
            barcode: BarcodeState? = null,
            find: PackFind? = null,
            recognition: Recognition? = noFood,
            readable: Boolean = true,
            left: Boolean = false,
        ) = PhotoPackRules.readsByItself(plate, barcode, find, recognition, readable, left)
        assertTrue(reads())
        assertTrue(reads(recognition = weak))
        assertTrue(reads(barcode = BarcodeState.NotFound("8410000807363")))
        assertTrue(reads(find = PackFind.Suggestions(emptyList())))
        assertFalse(reads(recognition = sure))
        assertFalse(reads(plate = true))
        assertFalse(reads(barcode = found))
        assertFalse(reads(find = PackFind.Match(PackScore("a", 0.9f, 0))))
        // Russian or Arabic: no recognizer for their writing.
        assertFalse(reads(readable = false))
        // The user went on from the photo page.
        assertFalse(reads(left = true))
        // Searched by itself only with enough words; when asked, with any.
        assertTrue(PhotoPackRules.searches("fontaneda maría", auto = true))
        assertFalse(PhotoPackRules.searches("bar", auto = true))
        assertTrue(PhotoPackRules.searches("bar", auto = false))
        assertFalse(PhotoPackRules.searches("", auto = false))
    }

    @Test
    fun anotherScriptsRecognizerThatReadNothingOfItsOwnFallsBackToLatin() {
        val read = listOf(TextLine("nutelld", 0.3f, 0.1f, 0.7f))
        // The Japanese recognizer read Nutella's jar as "nutelld" (seen in the 1.8.0 smoke test): no Japanese in it.
        assertTrue(PackageText.needsLatin(TextScript.JAPANESE, read, "nutelld"))
        // Text was read but nothing usable.
        assertTrue(PackageText.needsLatin(TextScript.CHINESE, read, null))
        assertTrue(PackageText.needsLatin(TextScript.DEVANAGARI, read, "haldiram"))
        // A photo without any text is not read a second time (nor the Latin module downloaded for it).
        assertFalse(PackageText.needsLatin(TextScript.CHINESE, emptyList(), null))
        // Words in its own script: kept (even with a Latin brand among them).
        assertFalse(PackageText.needsLatin(TextScript.JAPANESE, read, "日清 カップヌードル"))
        assertFalse(PackageText.needsLatin(TextScript.KOREAN, read, "농심 신라면"))
        assertFalse(PackageText.needsLatin(TextScript.CHINESE, read, "康师傅 nutella"))
        assertFalse(PackageText.needsLatin(TextScript.DEVANAGARI, read, "हल्दीराम"))
        // The Latin recognizer itself never reads twice.
        assertFalse(PackageText.needsLatin(TextScript.LATIN, read, null))
        assertTrue(TextScript.JAPANESE.owns('カ'))
        assertFalse(TextScript.JAPANESE.owns('n'))
        assertTrue(TextScript.KOREAN.owns('신'))
    }

    @Test
    fun theLatinReadingCountsOnlyTheLinesItIsSureOf() {
        assertEquals("nutella", PackageText.latinQuery(listOf(TextLine("nutella", 0.3f, 0.1f, 0.8f), TextLine("Ferrero", 0.1f, 0.03f, 0.4f))))
        // Only unsure lines: no words (the other recognizer's words stay).
        assertNull(PackageText.latinQuery(listOf(TextLine("Sechera", 0.2f, 0.07f, 0.52f))))
    }

    @Test
    fun theUsersTypingIsNotWipedByANewSearch() {
        // A search retried with fewer words changes the words shown while the user is typing: theirs stay.
        assertEquals("coca-cola", PhotoPackRules.fieldAfter("coca-cola", edited = true, start = "senza"))
        // Not editing: the field follows the words searched.
        assertEquals("senza", PhotoPackRules.fieldAfter("senza aggiunta", edited = false, start = "senza"))
    }

    @Test
    fun theWordsTypedOrCorrectedAreSearchedAsGiven() {
        assertEquals("alpro natur", PackageText.typed("  alpro   natur \n"))
        assertEquals("nutella", PackageText.typed("nutella"))
        // Nothing to search.
        assertNull(PackageText.typed("   "))
        assertNull(PackageText.typed("-- ·"))
        assertEquals(PackageText.MAX_TYPED, PackageText.typed("a".repeat(200))!!.length)
    }

    @Test
    fun theSearchFieldShowsTheWordsOrStartsEmpty() {
        assertNull(PhotoPackRules.wordsField(PackText.Idle))
        assertNull(PhotoPackRules.wordsField(PackText.Reading))
        // The words searched (read or typed), to correct them.
        assertEquals("alpro natur", PhotoPackRules.wordsField(PackText.Searching("alpro natur")))
        assertEquals("alpro natur", PhotoPackRules.wordsField(PackText.Results("alpro natur", emptyList())))
        assertEquals("zero", PhotoPackRules.wordsField(PackText.NotFound("zero")))
        assertEquals("zero", PhotoPackRules.wordsField(PackText.Offline("zero")))
        assertEquals("zero", PhotoPackRules.wordsField(PackText.Busy("zero")))
        assertEquals("zero", PhotoPackRules.wordsField(PackText.Failed("zero")))
        // No usable words read (or no reading at all): empty, to type the brand and name instead of a dead end.
        assertEquals("", PhotoPackRules.wordsField(PackText.NoText))
        assertEquals("", PhotoPackRules.wordsField(PackText.ReadFailed))
        assertEquals("", PhotoPackRules.wordsField(PackText.Unavailable(preparing = false)))
    }

    @Test
    fun packagesAreReadInEveryLanguageWithARecognizer() {
        assertEquals(TextScript.LATIN, TextScript.forLanguage("es"))
        assertEquals(TextScript.LATIN, TextScript.forLanguage("vi"))
        assertEquals(TextScript.CHINESE, TextScript.forLanguage("zh"))
        assertEquals(TextScript.JAPANESE, TextScript.forLanguage("ja"))
        assertEquals(TextScript.KOREAN, TextScript.forLanguage("ko"))
        assertEquals(TextScript.DEVANAGARI, TextScript.forLanguage("hi"))
        assertNull(TextScript.forLanguage("ru"))
        assertNull(TextScript.forLanguage("ar"))
    }
}
