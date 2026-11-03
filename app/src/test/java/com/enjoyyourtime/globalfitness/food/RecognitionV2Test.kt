package com.enjoyyourtime.globalfitness.food

import com.enjoyyourtime.globalfitness.food.recognition.CropSquare
import com.enjoyyourtime.globalfitness.food.recognition.DetectedBox
import com.enjoyyourtime.globalfitness.food.recognition.DetectionMath
import com.enjoyyourtime.globalfitness.food.recognition.DetectorSpec
import com.enjoyyourtime.globalfitness.food.recognition.GramsSpec
import com.enjoyyourtime.globalfitness.food.recognition.ModelGrams
import com.enjoyyourtime.globalfitness.food.recognition.ModelSpec
import com.enjoyyourtime.globalfitness.food.recognition.RecognitionMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.exp
import kotlin.math.ln

/** The detector (detector_contract.md), the grams estimate (grams_contract.md) and classifier v2's json. */
class RecognitionV2Test {
    private val dummyDetectorJson = """
        {"version": 1, "kind": "food_item_detector",
         "input": {"width": 320, "height": 320, "layout": "NHWC", "dtype": "float32", "channels": "RGB", "range": "0..1",
                   "mean": [0.0, 0.0, 0.0], "std": [1.0, 1.0, 1.0], "resize": "stretch", "interpolation": "bilinear"},
         "outputs": {"boxes": {"index": 1, "shape": [1, 10, 4], "dtype": "float32"},
                     "scores": {"index": 0, "shape": [1, 10], "dtype": "float32", "order": "descending"},
                     "count": {"index": 2, "shape": [1], "dtype": "float32"}},
         "max_detections": 10, "nms_iou": 0.5, "graph_score_floor": 0.05,
         "score_threshold": 0.25, "max_items": 6, "min_box_side": 0.06,
         "crop": {"margin": 0.1, "square": true}, "model": {"name": "dummy", "future": [1, 2]}}
    """.trimIndent()

    @Test
    fun detectorSpecFollowsTheJson() {
        val s = DetectorSpec.parse(dummyDetectorJson)
        assertEquals(320, s.width)
        assertEquals(320, s.height)
        assertEquals(1, s.boxesIndex)
        assertEquals(0, s.scoresIndex)
        assertEquals(2, s.countIndex)
        assertEquals(0.25f, s.scoreThreshold, 0f)
        assertEquals(6, s.maxItems)
        assertEquals(0.06f, s.minBoxSide, 0f)
        assertEquals(0.1f, s.cropMargin, 0f)
        assertNull("no group_suppression block: no suppression", s.containerInside)
        // Missing keys take the contract's values.
        val bare = DetectorSpec.parse("""{"input": {"width": 384}}""")
        assertEquals(384, bare.height)
        assertNull(bare.boxesIndex)
        assertEquals(0.25f, bare.scoreThreshold, 0f)
        assertEquals(6, bare.maxItems)
        assertEquals(0.1f, bare.cropMargin, 0f)
        // Its input is the photo stretched, 0..1, no mean/std.
        val input = s.inputSpec()
        assertFalse(input.centerCrop)
        assertTrue(input.unitRange)
        assertEquals(listOf(0f, 0f, 0f), input.mean)
        val out = RecognitionMath.preprocess(IntArray(4 * 2) { 0xFF_FF_80_00.toInt() }, 4, 2, input)
        assertEquals(320 * 320 * 3, out.size)
        assertEquals(1f, out[0], 1e-6f)
        assertEquals(128 / 255f, out[1], 1e-6f)
        assertEquals(0f, out[2], 1e-6f)
    }

    private fun boxes(vararg b: FloatArray) = FloatArray(40).also { a -> b.forEachIndexed { i, v -> v.copyInto(a, i * 4) } }

    @Test
    fun detectionsAreKeptByCountScoreSizeAndNumber() {
        val spec = DetectorSpec.parse(dummyDetectorJson)
        val b = boxes(
            floatArrayOf(0.1f, 0.1f, 0.5f, 0.4f), // kept
            floatArrayOf(0.5f, 0.5f, 0.9f, 0.9f), // kept
            floatArrayOf(0.0f, 0.0f, 0.03f, 0.5f), // too thin: 3 % of the short side
            floatArrayOf(0.2f, 0.6f, 0.4f, 0.8f), // under the threshold
            floatArrayOf(0.2f, 0.2f, 0.6f, 0.6f), // beyond count
        )
        val scores = floatArrayOf(0.9f, 0.6f, 0.5f, 0.2f, 0.9f, 0f, 0f, 0f, 0f, 0f)
        val kept = DetectionMath.select(b, scores, 4f, 1000, 800, spec)
        assertEquals(listOf(0.9f, 0.6f), kept.map { it.score })
        assertEquals(DetectedBox(0.1f, 0.1f, 0.5f, 0.4f, 0.9f), kept[0])
        assertTrue(DetectionMath.select(b, scores, Float.NaN, 1000, 800, spec).isEmpty())
        // At most max_items, best first.
        val many = FloatArray(40) { i -> if (i % 4 < 2) 0.1f else 0.6f }
        val manyScores = FloatArray(10) { 0.9f - it * 0.05f }
        assertEquals(6, DetectionMath.select(many, manyScores, 10f, 100, 100, spec).size)
        // A count above K reads K rows.
        assertEquals(6, DetectionMath.select(many, manyScores, 99f, 100, 100, spec).size)
    }

    // ---- containers (a box around items that are already there) -------------------------------

    private val groupBlock = """"group_suppression": {"rule": "drop_container", "inside": 0.8, "min_inside": 2}"""
    private val containers = DetectorSpec.parse(dummyDetectorJson.replace(""""score_threshold"""", """$groupBlock, "score_threshold""""))

    @Test
    fun theGroupSuppressionBlock() {
        assertEquals(0.8f, containers.containerInside)
        assertEquals(2, containers.containerMinInside)
        fun parse(block: String) = DetectorSpec.parse("""{"input": {"width": 320}, "group_suppression": $block}""")
        assertNull("an unknown rule: no suppression", parse("""{"rule": "keep_container", "inside": 0.8, "min_inside": 2}""").containerInside)
        assertNull("no rule: no suppression", parse("""{"inside": 0.8}""").containerInside)
        assertNull("not a block: no suppression", parse("true").containerInside)
        val bare = parse("""{"rule": "drop_container"}""")
        assertEquals("missing values: the contract's", 0.8f, bare.containerInside)
        assertEquals(2, bare.containerMinInside)
        val odd = parse("""{"rule": "drop_container", "inside": 1.5, "min_inside": 0}""")
        assertEquals(1f, odd.containerInside)
        assertEquals(1, odd.containerMinInside)
        assertEquals(0.6f, parse("""{"rule": "drop_container", "inside": 0.6, "min_inside": 3}""").containerInside)
    }

    /** [b] (box to score) as the model gives them, by score descending, in a 1000×1000 photo. */
    private fun select(spec: DetectorSpec, vararg b: Pair<FloatArray, Float>): List<DetectedBox> {
        val rows = b.sortedByDescending { it.second }
        val boxes = FloatArray(rows.size * 4).also { a -> rows.forEachIndexed { i, (box, _) -> box.copyInto(a, i * 4) } }
        return DetectionMath.select(boxes, rows.map { it.second }.toFloatArray(), rows.size.toFloat(), 1000, 1000, spec)
    }

    @Test
    fun theBoxAroundAPileOfPiecesGoes() {
        // The potato plate: a box around the whole pile (best score) and the pieces inside it.
        val pile = floatArrayOf(0.2f, 0.2f, 0.8f, 0.8f) to 0.7f
        val pieces = listOf(
            floatArrayOf(0.25f, 0.25f, 0.4f, 0.4f) to 0.6f,
            floatArrayOf(0.3f, 0.5f, 0.45f, 0.65f) to 0.55f,
            floatArrayOf(0.6f, 0.3f, 0.75f, 0.45f) to 0.5f,
        )
        val kept = select(containers, pile, *pieces.toTypedArray())
        assertEquals(listOf(0.6f, 0.55f, 0.5f), kept.map { it.score })
        // The shares are then over the pieces only.
        assertEquals(1.0, DetectionMath.shares(kept, 1000, 1000).sum(), 1e-9)
        // Today's behaviour without the rule: the pile stays, first.
        assertEquals(listOf(0.7f, 0.6f, 0.55f, 0.5f), select(DetectorSpec.parse(dummyDetectorJson), pile, *pieces.toTypedArray()).map { it.score })
    }

    @Test
    fun aBigItemWithOneItemOnItOrItemsHalfOverItStays() {
        // A plate of rice (big box) with one fried egg on it: one box inside is not a pile.
        val rice = floatArrayOf(0.1f, 0.1f, 0.9f, 0.9f) to 0.8f
        val egg = floatArrayOf(0.3f, 0.3f, 0.5f, 0.5f) to 0.7f
        // Two pieces of bread half over its edge: under 0.8 inside each.
        val bread1 = floatArrayOf(0.05f, 0.7f, 0.25f, 0.95f) to 0.6f // 0.75 × 0.8 = 0.6 inside
        val bread2 = floatArrayOf(0.8f, 0.4f, 1.0f, 0.6f) to 0.5f // half inside
        assertEquals(4, select(containers, rice, egg, bread1, bread2).size)
        // A second item well inside makes it a container: it goes, the bread half outside stays.
        val tomato = floatArrayOf(0.6f, 0.6f, 0.75f, 0.75f) to 0.65f
        assertEquals(listOf(0.7f, 0.65f, 0.6f, 0.5f), select(containers, rice, egg, tomato, bread1, bread2).map { it.score })
        // Exactly the share in the json counts as inside.
        assertEquals(0.8f, DetectionMath.insideShare(DetectedBox(0f, 0f, 1f, 1f, 1f), DetectedBox(0f, 0f, 1f, 0.8f, 1f)), 1e-6f)
        assertEquals(0f, DetectionMath.insideShare(DetectedBox(0.5f, 0.5f, 0.5f, 0.5f, 1f), DetectedBox(0f, 0f, 1f, 1f, 1f)), 0f)
        // A rule asking for 3 items keeps it.
        assertEquals(5, select(containers.copy(containerMinInside = 3), rice, egg, tomato, bread1, bread2).size)
    }

    @Test
    fun nestedBoxesKeepTheInnermostItems() {
        // Tray ⊃ plate ⊃ {two pieces}: the plate holds 2, the tray holds 3 → both go.
        val tray = floatArrayOf(0f, 0f, 1f, 1f) to 0.9f
        val plate = floatArrayOf(0.2f, 0.2f, 0.8f, 0.8f) to 0.8f
        val a = floatArrayOf(0.3f, 0.3f, 0.45f, 0.45f) to 0.7f
        val b = floatArrayOf(0.5f, 0.5f, 0.65f, 0.65f) to 0.6f
        assertEquals(listOf(0.7f, 0.6f), select(containers, tray, plate, a, b).map { it.score })
        // Tray ⊃ plate ⊃ one piece: the tray holds two (the plate and the piece) and goes; the plate
        // with one piece on it is an item.
        assertEquals(listOf(0.8f, 0.7f), select(containers, tray, plate, a).map { it.score })
        // Plate ⊃ one piece: nothing goes.
        assertEquals(2, select(containers, plate, a).size)
        // Two equal boxes are not each other's container (one inside the other is only one item).
        assertEquals(2, select(containers, plate, floatArrayOf(0.2f, 0.2f, 0.8f, 0.8f) to 0.5f).size)
    }

    @Test
    fun containersGoBeforeMaxItems() {
        // A pile box plus 6 pieces: without the rule, max_items (6) would drop the weakest piece.
        val pile = floatArrayOf(0f, 0f, 1f, 1f) to 0.95f
        val pieces = (0 until 6).map { i -> floatArrayOf(0.1f, 0.05f + i * 0.15f, 0.2f, 0.15f + i * 0.15f) to 0.9f - i * 0.05f }
        val kept = select(containers, pile, *pieces.toTypedArray())
        assertEquals(pieces.map { it.second }, kept.map { it.score })
        assertEquals(0.95f, select(DetectorSpec.parse(dummyDetectorJson), pile, *pieces.toTypedArray()).first().score)
    }

    @Test
    fun cropsAreSquaresWithAMarginInsideThePhoto() {
        // A 200×100 box in the middle of a 1000×800 photo: side 200 × 1.2 = 240, centred.
        val box = DetectedBox(0.4375f, 0.4f, 0.5625f, 0.6f, 0.9f)
        assertEquals(CropSquare(380, 280, 240), DetectionMath.crop(box, 1000, 800, 0.1f))
        // Near the corner: shifted, not shrunk.
        assertEquals(CropSquare(0, 0, 240), DetectionMath.crop(DetectedBox(0f, 0f, 0.125f, 0.2f, 0.9f), 1000, 800, 0.1f))
        // Larger than the photo's short side: the short side, kept inside.
        val big = DetectionMath.crop(DetectedBox(0f, 0f, 1f, 1f, 0.9f), 1000, 800, 0.1f)
        assertEquals(800, big.size)
        assertEquals(0, big.top)
        assertEquals(100, big.left)
        // Every crop fits the bitmap.
        for (y in listOf(0f, 0.3f, 0.9f)) for (x in listOf(0f, 0.5f, 0.97f)) {
            val c = DetectionMath.crop(DetectedBox(y, x, (y + 0.1f).coerceAtMost(1f), (x + 0.03f).coerceAtMost(1f), 1f), 641, 479, 0.1f)
            assertTrue("$c", c.left >= 0 && c.top >= 0 && c.left + c.size <= 641 && c.top + c.size <= 479 && c.size > 0)
        }
    }

    @Test
    fun sharesAreTheBoxesPartOfTheirTotalArea() {
        val s = DetectionMath.shares(listOf(DetectedBox(0f, 0f, 0.5f, 0.5f, 1f), DetectedBox(0f, 0f, 0.5f, 0.25f, 1f)), 100, 100)
        assertEquals(2 / 3.0, s[0], 1e-9)
        assertEquals(1 / 3.0, s[1], 1e-9)
        assertEquals(listOf(0.5, 0.5), DetectionMath.shares(listOf(DetectedBox(0f, 0f, 0f, 0f, 1f), DetectedBox(0f, 0f, 0f, 0f, 1f)), 10, 10))
    }

    // ---- grams ---------------------------------------------------------------------------------

    private val gramsBlock = """
        "grams": {"enabled": true, "output": {"index": 1, "shape": [1, 1], "dtype": "float32", "kind": "log_grams"},
                  "b0": 0.0, "b_model": 1.0, "b_portion": 0.0, "b_share": 0.0,
                  "clamp_portion": [0.25, 4.0], "clamp_grams": [5, 1500], "round_to": 5}
    """.trimIndent()

    @Test
    fun theGramsBlockOfTheClassifier() {
        val spec = ModelSpec.parse("""{"version": 2, "labels": ["a", "_not_food"], "input": {"width": 260}, $gramsBlock}""")
        assertEquals(2, spec.version)
        assertEquals(260, spec.width)
        assertEquals(260, spec.height)
        assertEquals(GramsSpec(outputIndex = 1), spec.grams)
        assertNull("disabled: no estimate", ModelSpec.parse("""{"labels": ["a"], ${gramsBlock.replace("true", "false")}}""").grams)
        val shipped = ModelSpec.parse(File("src/main/assets/food/food_model.json").readText()).grams
        assertEquals("the shipped v2 json has the grams head", 1, shipped?.outputIndex)
        assertEquals(0.1, shipped!!.clampPortionMin, 1e-6)
        val odd = GramsSpec.parse(mapOf("enabled" to true, "clamp_portion" to listOf(4.0, 1.0), "round_to" to -1.0, "b_share" to 0.3))!!
        assertEquals("bad clamps take the defaults", 0.25, odd.clampPortionMin, 0.0)
        assertEquals(5.0, odd.roundTo, 0.0)
        assertEquals(0.3, odd.bShare, 0.0)
        assertNull(odd.outputIndex)
        // The final block (grams_contract.md, 2026-11-03): a floor of 0.1 × portion.
        val final = ModelSpec.parse("""{"labels": ["a"], "grams": {"enabled": true, "output": {"index": 1, "shape": [1, 1], "kind": "log_grams"},
            "b0": 0.5484, "b_model": 0.8824, "b_portion": 0.0402, "b_share": 0.3893,
            "clamp_portion": [0.1, 4.0], "clamp_grams": [5, 1500], "round_to": 5}}""").grams!!
        assertEquals(0.1, final.clampPortionMin, 1e-6)
        assertEquals(0.3893, final.bShare, 0.0)
        // A small piece (ln y = 1) of a food with a 200 g portion, 10 % of the plate: under the floor → 20 g.
        assertEquals(20.0, RecognitionMath.grams(final, 1.0, 200.0, 0.1)!!, 0.0)
    }

    @Test
    fun theGramsFormula() {
        val s = GramsSpec(outputIndex = 1)
        // exp(y), rounded to 5.
        assertEquals(180.0, RecognitionMath.grams(s, ln(181.0), 200.0, 1.0)!!, 0.0)
        // Clamped to [0.25, 4] × portion, then to [5, 1500].
        assertEquals(50.0, RecognitionMath.grams(s, ln(10.0), 200.0, 1.0)!!, 0.0)
        assertEquals(800.0, RecognitionMath.grams(s, ln(5000.0), 200.0, 1.0)!!, 0.0)
        assertEquals(1500.0, RecognitionMath.grams(s, ln(5000.0), 1000.0, 1.0)!!, 0.0)
        assertEquals("at least one step", 5.0, RecognitionMath.grams(s, ln(1.0), 4.0, 1.0)!!, 0.0)
        // The portion and the share terms.
        val full = GramsSpec(outputIndex = 1, b0 = 0.1, bModel = 0.8, bPortion = 0.2, bShare = 0.5)
        val expected = exp(0.1 + 0.8 * ln(150.0) + 0.2 * ln(120.0) + 0.5 * ln(0.4))
        assertEquals(Math.round(expected / 5) * 5.0, RecognitionMath.grams(full, ln(150.0), 120.0, 0.4)!!, 0.0)
        // No portion, or nothing finite: no estimate (the portion is used, unmarked).
        assertNull(RecognitionMath.grams(s, ln(100.0), null, 1.0))
        assertNull(RecognitionMath.grams(s, ln(100.0), 0.0, 1.0))
        assertNull(RecognitionMath.grams(s, Double.NaN, 100.0, 1.0))
        assertNull(RecognitionMath.grams(s, Double.POSITIVE_INFINITY, 100.0, 1.0))
        // Behind the interface.
        assertEquals(180.0, ModelGrams(s, ln(181.0)).estimate(200.0, 1.0)!!, 0.0)
    }

    @Test
    fun aBiggerClassifierWithMoreLabelsNeedsNoCodeChange() {
        val labels = (1..420).map { "food_$it" } + "_not_food"
        val json = """{"version": 2, "labels": [${labels.joinToString { "\"$it\"" }}],
            "input": {"width": 260, "height": 260, "mean": [0.5, 0.5, 0.5], "std": [0.5, 0.5, 0.5], "range": "0..1 before mean/std",
                      "resize": "short_side_then_center_crop", "resize_short_side": 297, "interpolation": "bilinear"},
            "output": {"kind": "logits"}, "temperature": 1.1, "min_confidence": 0.04, $gramsBlock}"""
        val spec = ModelSpec.parse(json)
        assertEquals(421, spec.labels.size)
        assertEquals(297, spec.resizeShortSide)
        val out = RecognitionMath.preprocess(IntArray(400 * 300) { 0xFF_40_80_C0.toInt() }, 400, 300, spec)
        assertEquals(260 * 260 * 3, out.size)
        val logits = FloatArray(421) { if (it == 7) 9f else 0f }
        val r = RecognitionMath.recognize(RecognitionMath.probabilities(logits, spec), spec.labels, spec.minConfidence)
        assertEquals("food_8", r.suggestions.first().label)
        assertFalse(r.notFood)
    }
}
