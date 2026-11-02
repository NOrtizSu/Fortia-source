package com.enjoyyourtime.globalfitness.food

import com.enjoyyourtime.globalfitness.food.recognition.ModelSpec
import com.enjoyyourtime.globalfitness.food.recognition.RecognitionMath
import com.enjoyyourtime.globalfitness.wearable.banglejs.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.abs

/**
 * The app's preprocessing against the training pipeline's own (tools/food-model/parity.py), on the
 * fixed images of `src/test/resources/food/golden` (made by make_golden.py there): the same tensor,
 * value for value. One case has the bundled model's spec on a shrunk photo, one on an enlarged one,
 * one other numbers and an odd crop offset.
 */
class RecognitionGoldenTest {
    /** The same image as make_golden.py's pixel(): a ramp, a checkerboard and a diagonal saw. */
    private fun image(w: Int, h: Int) = IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        val r = x * 255 / (w - 1)
        val g = if ((x / 7 + y / 5) % 2 == 0) 230 else 25
        val b = (x + 2 * y) * 3 % 256
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun resource(name: String) = checkNotNull(javaClass.getResource("/food/golden/$name")) { "missing fixture $name" }

    @Test
    fun preprocessingMatchesTheTrainingPipeline() {
        val golden = MiniJson.parse(resource("golden.json").readText()) as Map<*, *>
        val cases = golden["cases"] as List<*>
        assertEquals(3, cases.size)
        for (c in cases) {
            c as Map<*, *>
            val name = c["name"] as String
            val w = (c["width"] as Number).toInt()
            val h = (c["height"] as Number).toInt()
            val spec = ModelSpec.parse("""{"labels": ["a"], "input": ${json(c["input"])}}""")
            assertTrue("$name: the spec asks for bilinear", spec.bilinear)
            val out = RecognitionMath.preprocess(image(w, h), w, h, spec)

            // The 8-bit crop parity.py normalized (RGB rows stored as differences from the pixel on the
            // left), normalized here the same way (float32).
            val crop = GZIPInputStream(resource(c["rgb"] as String).openStream()).use { it.readBytes() }
            assertEquals(spec.width * spec.height * 3, crop.size)
            val row = spec.width * 3
            for (i in crop.indices) if (i % row >= 3) crop[i] = (crop[i] + crop[i - 3]).toByte()
            val mean = spec.mean.toFloatArray()
            val std = spec.std.toFloatArray()
            var worst = 0f
            for (i in crop.indices) {
                val expected = ((crop[i].toInt() and 0xFF) / 255f - mean[i % 3]) / std[i % 3]
                worst = maxOf(worst, abs(out[i] - expected))
            }
            assertEquals("$name: largest difference", 0f, worst, 1e-6f)
            // parity.py's own float values.
            for (s in c["samples"] as List<*>) {
                val (index, value) = (s as List<*>).map { (it as Number).toDouble() }
                assertEquals("$name[$index]", value.toFloat(), out[index.toInt()], 1e-6f)
            }
            assertEquals("$name: sum", (c["sum"] as Number).toDouble(), out.sumOf { it.toDouble() }, 1e-3)
        }
    }

    /** The golden cases use the bundled model's preprocessing (regenerate them when it changes). */
    @Test
    fun theFixturesFollowTheBundledSpec() {
        val bundled = MiniJson.parse(File("src/main/assets/food/food_model.json").readText()) as Map<*, *>
        val golden = MiniJson.parse(resource("golden.json").readText()) as Map<*, *>
        val landscape = (golden["cases"] as List<*>).first() as Map<*, *>
        assertEquals("the bundled model's input changed: run make_golden.py again", json(bundled["input"]), json(landscape["input"]))
    }

    private fun json(v: Any?): String = when (v) {
        null -> "null"
        is String -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") { (k, x) -> json(k.toString()) + ":" + json(x) }
        is List<*> -> v.joinToString(",", "[", "]") { json(it) }
        else -> v.toString()
    }
}
