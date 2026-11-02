package com.enjoyyourtime.globalfitness.food

import android.content.ComponentCallbacks2
import com.enjoyyourtime.globalfitness.food.recognition.ConfidenceHint
import com.enjoyyourtime.globalfitness.food.recognition.FoodRecognizer
import com.enjoyyourtime.globalfitness.food.recognition.ModelSpec
import com.enjoyyourtime.globalfitness.food.recognition.RecognitionMath
import com.enjoyyourtime.globalfitness.food.recognition.TensorType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp

class RecognitionTest {
    /** The example of the contract (common.md, contract 3). */
    private val contractJson = """
        {"version": 1, "labels": ["paella", "fried_egg", "banana", "_not_food"],
         "input": {"width": 224, "height": 224, "layout": "NHWC", "dtype": "float32",
                   "mean": [0.485, 0.456, 0.406], "std": [0.229, 0.224, 0.225], "range": "0..1 before mean/std",
                   "resize": "short_side_then_center_crop", "crop_fraction": 0.875},
         "output": {"dtype": "uint8", "scale": 0.00390625, "zero_point": 0, "kind": "probabilities"},
         "temperature": 1.5, "min_confidence": 0.05, "extra": {"ignored": true}}
    """.trimIndent()

    @Test
    fun parsesTheContract() {
        val s = ModelSpec.parse(contractJson)
        assertEquals(1, s.version)
        assertEquals(listOf("paella", "fried_egg", "banana", "_not_food"), s.labels)
        assertEquals(224, s.width)
        assertEquals(224, s.height)
        assertFalse(s.channelsFirst)
        assertEquals(TensorType.FLOAT32, s.inputType)
        assertEquals(listOf(0.485f, 0.456f, 0.406f), s.mean)
        assertEquals(ModelSpec.NOT_FOOD, s.notFoodLabel)
        assertFalse(s.bgr)
        assertTrue(s.unitRange)
        assertTrue(s.centerCrop)
        assertEquals(0.875f, s.cropFraction, 1e-6f)
        assertEquals(TensorType.UINT8, s.outputType)
        assertEquals(0.00390625f, s.outputScale, 1e-9f)
        assertFalse(s.logits)
        assertEquals(1.5f, s.temperature, 1e-6f)
        assertEquals(0.05f, s.minConfidence, 1e-6f)
    }

    /** The fields model_v0 added to the contract: the short side's size, the channel order, the "not food" label. */
    @Test
    fun shortSideChannelsAndNotFoodLabel() {
        val s = ModelSpec.parse(
            """{"labels": ["apple", "nothing"], "not_food_label": "nothing",
               "input": {"width": 224, "height": 224, "resize": "short_side_then_center_crop", "resize_short_side": 256,
                         "crop_fraction": 0.5, "channels": "BGR", "mean": [0.5, 0.5, 0.5], "std": [0.5, 0.5, 0.5]},
               "output": {"kind": "logits"}, "temperature": 0.88, "min_confidence": 0.1}""",
        )
        assertEquals("224 of a short side resized to 256", 0.875f, s.cropFraction, 1e-6f)
        assertTrue(s.bgr)
        assertEquals("nothing", s.notFoodLabel)
        assertEquals(0.88f, s.temperature, 1e-6f)
        val r = RecognitionMath.recognize(floatArrayOf(0.2f, 0.8f), s.labels, s.minConfidence, notFood = s.notFoodLabel)
        assertTrue(r.notFood)
        assertEquals(listOf("apple"), r.suggestions.map { it.label })
        assertEquals(256, s.resizeShortSide)
        assertFalse("no filter named: area-averaged", s.bilinear)
        // BGR: a red pixel lands in the last channel (both filters).
        val red = IntArray(4) { 0xFFFF0000.toInt() }
        val out = RecognitionMath.preprocess(red, 2, 2, ModelSpec.parse("""{"labels": ["a"], "input": {"width": 1, "height": 1, "channels": "BGR", "range": "0..255"}}"""))
        assertArrayEquals(floatArrayOf(0f, 0f, 255f), out, 1e-3f)
        val bilinear = ModelSpec.parse("""{"labels": ["a"], "input": {"width": 1, "height": 1, "channels": "BGR", "range": "0..255", "interpolation": "bilinear", "layout": "NCHW"}}""")
        assertTrue(bilinear.bilinear)
        assertArrayEquals(floatArrayOf(0f, 0f, 255f), RecognitionMath.preprocess(red, 2, 2, bilinear), 1e-3f)
    }

    @Test
    fun theModelIsFreedWhenAndroidRunsShortOrTheAppIsInTheBackground() {
        @Suppress("DEPRECATION")
        run {
            assertTrue(FoodRecognizer.releaseOnTrim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
            assertTrue(FoodRecognizer.releaseOnTrim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL))
            assertTrue(FoodRecognizer.releaseOnTrim(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND))
            assertTrue(FoodRecognizer.releaseOnTrim(ComponentCallbacks2.TRIM_MEMORY_COMPLETE))
            assertFalse("the camera app in front", FoodRecognizer.releaseOnTrim(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
            assertFalse(FoodRecognizer.releaseOnTrim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
        }
    }

    @Test
    fun theBundledModelSpecParses() {
        val file = java.io.File("src/main/assets/food/food_model.json")
        if (!file.isFile) return // the model ships with the food-model agent's branch
        val s = ModelSpec.parse(file.readText())
        assertTrue(s.labels.size > 1)
        assertTrue(s.notFoodLabel in s.labels)
        assertEquals(s.labels.size, s.labels.toSet().size)
        assertTrue("the training pipeline's filter", s.bilinear)
    }

    @Test
    fun defaultsAndOtherLayouts() {
        val s = ModelSpec.parse("""{"labels": ["a", "b"], "input": {"layout": "NCHW", "dtype": "uint8", "range": "0..255", "resize": "stretch", "width": 192}}""")
        assertTrue(s.channelsFirst)
        assertEquals(TensorType.UINT8, s.inputType)
        assertFalse(s.unitRange)
        assertFalse(s.centerCrop)
        assertEquals(192, s.height)
        assertTrue("logits unless said otherwise", s.logits)
        assertEquals(1f, s.temperature, 0f)
        for (bad in listOf("""{"labels": []}""", """[1, 2]""", """{"labels": ["a"], "input": {"std": [0, 1, 1]}}""", "not json")) {
            try {
                ModelSpec.parse(bad)
                fail("accepted $bad")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun softmaxTemperatureAndDequantize() {
        val p = RecognitionMath.softmax(floatArrayOf(1f, 2f, 3f))
        assertEquals(1f, p.sum(), 1e-6f)
        val e = doubleArrayOf(exp(1.0), exp(2.0), exp(3.0))
        assertEquals((e[2] / e.sum()).toFloat(), p[2], 1e-6f)
        val soft = RecognitionMath.softmax(floatArrayOf(1f, 2f, 3f), temperature = 2f)
        assertTrue("a higher temperature softens", soft[2] < p[2])
        assertEquals("large logits stay finite", 1f, RecognitionMath.softmax(floatArrayOf(1000f, 0f))[0], 1e-6f)
        assertArrayEquals(floatArrayOf(-0.5f, 0f, 0.5f), RecognitionMath.dequantize(intArrayOf(126, 128, 130), 0.25f, 128), 1e-6f)
        val tempered = RecognitionMath.temper(floatArrayOf(0.6f, 0.4f), 1f)
        assertArrayEquals(floatArrayOf(0.6f, 0.4f), tempered, 1e-6f)
        val sharper = RecognitionMath.temper(floatArrayOf(0.6f, 0.4f), 0.5f)
        assertTrue(sharper[0] > 0.6f)
        assertEquals(1f, sharper.sum(), 1e-5f)
        assertEquals(3, RecognitionMath.quantize(1f, 0.5f, 1, TensorType.UINT8))
        assertEquals(255, RecognitionMath.quantize(1000f, 0.5f, 0, TensorType.UINT8))
        assertEquals(-128, RecognitionMath.quantize(-1000f, 0.5f, 0, TensorType.INT8))
    }

    @Test
    fun topFiveFoodsWithoutNotFood() {
        val labels = listOf("a", "b", ModelSpec.NOT_FOOD, "c", "d", "e", "f", "g")
        val probs = floatArrayOf(0.30f, 0.05f, 0.10f, 0.20f, 0.15f, 0.08f, 0.07f, 0.05f)
        assertEquals(listOf(0, 3, 4), RecognitionMath.topK(probs, 3))
        val r = RecognitionMath.recognize(probs, labels, minConfidence = 0.05f)
        assertEquals(listOf("a", "c", "d", "e", "f"), r.suggestions.map { it.label })
        assertFalse(r.notFood)
        assertEquals("a", r.topLabel)
        assertEquals(0.30f, r.topConfidence!!, 1e-6f)
        // Ties keep the model's order.
        assertEquals(listOf("b", "g"), RecognitionMath.recognize(floatArrayOf(0.5f, 0.5f), listOf("b", "g"), 0.05f).suggestions.map { it.label })
    }

    @Test
    fun notFoodWhenItWinsOrEverythingIsUnsure() {
        val labels = listOf("a", ModelSpec.NOT_FOOD, "b")
        val notFood = RecognitionMath.recognize(floatArrayOf(0.2f, 0.7f, 0.1f), labels, 0.05f)
        assertTrue(notFood.notFood)
        assertEquals(ModelSpec.NOT_FOOD, notFood.topLabel)
        assertEquals("the foods are still ranked (for the entry's stats)", listOf("a", "b"), notFood.suggestions.map { it.label })
        val unsure = RecognitionMath.recognize(floatArrayOf(0.04f, 0.03f, 0.03f), labels, 0.05f)
        assertTrue(unsure.notFood)
        val unknownOnly = RecognitionMath.recognize(floatArrayOf(0.9f, 0.05f, 0.05f), labels, 0.05f, known = { it != "a" })
        assertEquals("foods missing from the catalog are never offered", listOf("b"), unknownOnly.suggestions.map { it.label })
        assertFalse("the known foods at or above the threshold are still offered", unknownOnly.notFood)
        val empty = RecognitionMath.recognize(FloatArray(0), emptyList(), 0.05f)
        assertTrue(empty.notFood)
        assertNull(empty.topLabel)
    }

    @Test
    fun probabilitiesFollowTheSpec() {
        val logits = ModelSpec.parse("""{"labels": ["a", "b"], "output": {"kind": "logits"}, "temperature": 2.0}""")
        assertArrayEquals(RecognitionMath.softmax(floatArrayOf(2f, 0f), 2f), RecognitionMath.probabilities(floatArrayOf(2f, 0f), logits), 1e-6f)
        val probs = ModelSpec.parse("""{"labels": ["a", "b"], "output": {"kind": "probabilities"}}""")
        assertArrayEquals(floatArrayOf(0.75f, 0.25f), RecognitionMath.probabilities(floatArrayOf(0.75f, 0.25f), probs), 1e-6f)
    }

    @Test
    fun hintsAreWordsNotPercentages() {
        assertEquals(ConfidenceHint.VERY_LIKELY, RecognitionMath.hint(0.8f))
        assertEquals(ConfidenceHint.VERY_LIKELY, RecognitionMath.hint(0.5f))
        assertEquals(ConfidenceHint.LIKELY, RecognitionMath.hint(0.3f))
        assertEquals(ConfidenceHint.POSSIBLE, RecognitionMath.hint(0.1f))
    }

    // ---- preprocessing ------------------------------------------------------------------------

    private fun spec(json: String) = ModelSpec.parse(json)

    @Test
    fun centreCropAndNormalizeInNhwc() {
        // 8×4 image: left half red, right half blue; a 4×4 model with crop 1.0 takes the centre square (x 2..5).
        val w = 8
        val h = 4
        val px = IntArray(w * h) { i -> if (i % w < 4) 0xFFFF0000.toInt() else 0xFF0000FF.toInt() }
        val s = spec("""{"labels": ["a"], "input": {"width": 4, "height": 4, "mean": [0.5, 0.5, 0.5], "std": [0.5, 0.5, 0.5], "crop_fraction": 1.0}}""")
        val out = RecognitionMath.preprocess(px, w, h, s)
        assertEquals(4 * 4 * 3, out.size)
        // Pixel (0, 0) comes from x = 2 (red): R = (1 − 0.5) / 0.5 = 1, B = −1.
        assertEquals(1f, out[0], 1e-5f)
        assertEquals(-1f, out[2], 1e-5f)
        // Pixel (3, 0) comes from x = 5 (blue).
        assertEquals(-1f, out[3 * 3], 1e-5f)
        assertEquals(1f, out[3 * 3 + 2], 1e-5f)
    }

    @Test
    fun downscalingAveragesAndNchwIsChannelPlanes() {
        // 4×4 checkerboard of black and white → a 2×2 model sees mid grey everywhere (anti-aliased).
        val px = IntArray(16) { i -> if ((i % 4 + i / 4) % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        val s = spec("""{"labels": ["a"], "input": {"width": 2, "height": 2, "layout": "NCHW", "range": "0..255", "resize": "stretch"}}""")
        val out = RecognitionMath.preprocess(px, 4, 4, s)
        assertEquals(12, out.size)
        for (v in out) assertTrue("grey: $v", abs(v - 127.5f) < 1e-3f)
        // Channel planes: a red image has R in the first plane only.
        val red = IntArray(16) { 0xFFFF0000.toInt() }
        val planes = RecognitionMath.preprocess(red, 4, 4, s)
        assertArrayEquals(floatArrayOf(255f, 255f, 255f, 255f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f), planes, 1e-3f)
    }

    @Test
    fun cropFractionTakesTheCentre() {
        // 10×10 with a 2×2 white centre; crop 0.2 of the short side = the centre 2×2 only.
        val px = IntArray(100) { i -> if (i % 10 in 4..5 && i / 10 in 4..5) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        val s = spec("""{"labels": ["a"], "input": {"width": 2, "height": 2, "range": "0..255", "crop_fraction": 0.2}}""")
        val out = RecognitionMath.preprocess(px, 10, 10, s)
        for (v in out) assertEquals(255f, v, 1e-3f)
    }
}
