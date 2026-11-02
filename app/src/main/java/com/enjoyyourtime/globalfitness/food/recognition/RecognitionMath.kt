package com.enjoyyourtime.globalfitness.food.recognition

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One class the recognizer proposes, with its (calibrated) probability. */
data class Suggestion(val label: String, val confidence: Float)

/** What the recognizer saw: up to five foods, or no food at all. */
data class Recognition(
    /** Best foods first (the "not food" class never among them). */
    val suggestions: List<Suggestion>,
    /** `_not_food` won, or every food is under the model's minimum confidence. */
    val notFood: Boolean,
    /** The model's first class (the "not food" class included) and its probability, kept with the entry for quality stats. */
    val topLabel: String?,
    val topConfidence: Float?,
    val latencyMs: Long = 0,
)

/** How sure the hint next to a suggestion sounds ("Muy probable", "Probable", "Posible"), never a raw %. */
enum class ConfidenceHint { VERY_LIKELY, LIKELY, POSSIBLE }

/**
 * Pure pre- and post-processing of the food recognizer, following [ModelSpec] (unit-tested; the
 * Android side only moves pixels and bytes).
 */
object RecognitionMath {
    const val TOP_K = 5

    /**
     * The model's input from a photo: "resize the short side, then centre crop" (or the whole image
     * stretched). With [ModelSpec.bilinear], exactly as the training pipeline (see [pilBilinear]);
     * otherwise (a spec that names no filter) the centre region ([ModelSpec.cropFraction] of the short
     * side) area-averaged to the model's size: also anti-aliased, with a box instead of a triangle.
     * [argb]: row-major pixels of a [w]×[h] image. Returns RGB floats normalized with the spec's range,
     * mean and std, in its layout.
     */
    fun preprocess(argb: IntArray, w: Int, h: Int, spec: ModelSpec): FloatArray {
        require(argb.size >= w * h && w > 0 && h > 0)
        if (spec.bilinear) return pilBilinear(argb, w, h, spec)
        val (x0, y0, cw, ch) = if (spec.centerCrop) {
            // Crop a region with the model's aspect ratio covering cropFraction of the short side.
            val aspect = spec.width.toFloat() / spec.height
            val shortSide = min(w, h).toFloat()
            var cropW = shortSide * spec.cropFraction
            var cropH = cropW / aspect
            if (cropH > h) {
                cropH = h.toFloat()
                cropW = cropH * aspect
            }
            if (cropW > w) {
                cropW = w.toFloat()
                cropH = cropW / aspect
            }
            listOf((w - cropW) / 2f, (h - cropH) / 2f, cropW, cropH)
        } else {
            listOf(0f, 0f, w.toFloat(), h.toFloat())
        }
        val outW = spec.width
        val outH = spec.height
        // Separable area average: each source pixel counts by the part of an output pixel's footprint it
        // covers, first along the rows, then down the columns (no allocation per pixel: this runs on a
        // phone for every photo).
        val tx = Taps.of(x0, cw / outW, outW, w)
        val ty = Taps.of(y0, ch / outH, outH, h)
        val rowFirst = ty.first
        val rows = ty.last - rowFirst + 1
        val tmp = FloatArray(rows * outW * 3)
        for (ry in 0 until rows) {
            val src = (rowFirst + ry) * w
            val dst = ry * outW * 3
            for (ox in 0 until outW) {
                var r = 0f
                var g = 0f
                var b = 0f
                val t0 = ox * tx.maxTaps
                for (k in 0 until tx.count[ox]) {
                    val wgt = tx.weight[t0 + k]
                    val px = argb[src + tx.start[ox] + k]
                    r += ((px shr 16) and 0xFF) * wgt
                    g += ((px shr 8) and 0xFF) * wgt
                    b += (px and 0xFF) * wgt
                }
                tmp[dst + ox * 3] = r
                tmp[dst + ox * 3 + 1] = g
                tmp[dst + ox * 3 + 2] = b
            }
        }
        val scale = if (spec.unitRange) 1f / 255f else 1f
        val mean = spec.mean.toFloatArray()
        val std = spec.std.toFloatArray()
        // Output channel c takes source channel order[c] (blue first for a BGR model).
        val order = if (spec.bgr) intArrayOf(2, 1, 0) else intArrayOf(0, 1, 2)
        val out = FloatArray(outW * outH * 3)
        val plane = outW * outH
        for (oy in 0 until outH) {
            val t0 = oy * ty.maxTaps
            for (ox in 0 until outW) {
                for (c in 0 until 3) {
                    var v = 0f
                    for (k in 0 until ty.count[oy]) {
                        v += tmp[(ty.start[oy] - rowFirst + k) * outW * 3 + ox * 3 + order[c]] * ty.weight[t0 + k]
                    }
                    val index = if (spec.channelsFirst) c * plane + oy * outW + ox else (oy * outW + ox) * 3 + c
                    out[index] = (v * scale - mean[c]) / std[c]
                }
            }
        }
        return out
    }

    /**
     * The training pipeline's preprocessing (food-model's `parity.py`, torchvision's Resize + CenterCrop
     * on a PIL image), bit for bit: the short side resized to [ModelSpec.resizeShortSide] (the long side
     * to floor(long · short / shortSide)) with Pillow's BILINEAR filter, which is antialiased (its
     * support grows with the reduction), uses 22-bit fixed-point weights and rounds to 8 bits after the
     * horizontal and after the vertical pass; then the crop at round-half-even((size − crop) / 2), black
     * outside the image; then /255 and the mean and std in float32. Only the cropped pixels are computed.
     */
    private fun pilBilinear(argb: IntArray, w: Int, h: Int, spec: ModelSpec): FloatArray {
        val outW = spec.width
        val outH = spec.height
        val rw: Int
        val rh: Int
        val left: Int
        val top: Int
        if (spec.centerCrop) {
            val short = spec.resizeShortSide ?: (min(outW, outH) / spec.cropFraction).roundToInt()
            if (w <= h) {
                rw = short
                rh = (short.toDouble() * h / w).toInt()
            } else {
                rw = (short.toDouble() * w / h).toInt()
                rh = short
            }
            left = Math.rint((rw - outW) / 2.0).toInt()
            top = Math.rint((rh - outH) / 2.0).toInt()
        } else {
            rw = outW
            rh = outH
            left = 0
            top = 0
        }
        val cx = PilTaps.of(w, rw, left, outW)
        val cy = PilTaps.of(h, rh, top, outH)
        val rowFirst = cy.first
        val rows = max(0, cy.last - rowFirst + 1)
        // Horizontal pass, 8-bit, on the source rows the vertical pass reads.
        val tmp = IntArray(rows * outW * 3)
        for (ry in 0 until rows) {
            val src = (rowFirst + ry) * w
            val dst = ry * outW * 3
            for (ox in 0 until outW) {
                var r = PilTaps.HALF
                var g = PilTaps.HALF
                var b = PilTaps.HALF
                val k0 = ox * cx.ksize
                for (k in 0 until cx.count[ox]) {
                    val wgt = cx.k[k0 + k]
                    val px = argb[src + cx.start[ox] + k]
                    r += ((px shr 16) and 0xFF) * wgt
                    g += ((px shr 8) and 0xFF) * wgt
                    b += (px and 0xFF) * wgt
                }
                tmp[dst + ox * 3] = PilTaps.clip8(r)
                tmp[dst + ox * 3 + 1] = PilTaps.clip8(g)
                tmp[dst + ox * 3 + 2] = PilTaps.clip8(b)
            }
        }
        val mean = spec.mean.toFloatArray()
        val std = spec.std.toFloatArray()
        val order = if (spec.bgr) intArrayOf(2, 1, 0) else intArrayOf(0, 1, 2)
        val out = FloatArray(outW * outH * 3)
        val plane = outW * outH
        for (oy in 0 until outH) {
            val k0 = oy * cy.ksize
            for (ox in 0 until outW) {
                for (c in 0 until 3) {
                    var v = PilTaps.HALF
                    for (k in 0 until cy.count[oy]) {
                        v += tmp[(cy.start[oy] - rowFirst + k) * outW * 3 + ox * 3 + order[c]] * cy.k[k0 + k]
                    }
                    val x = PilTaps.clip8(v).toFloat()
                    val index = if (spec.channelsFirst) c * plane + oy * outW + ox else (oy * outW + ox) * 3 + c
                    out[index] = ((if (spec.unitRange) x / 255f else x) - mean[c]) / std[c]
                }
            }
        }
        return out
    }

    /**
     * Pillow's resampling weights (`precompute_coeffs` + `normalize_coeffs_8bpc` of Resample.c, bilinear
     * filter) for the [n] output pixels from [from] of an axis resized from [inSize] to [outSize] pixels.
     * An output pixel outside the resized axis (a crop larger than the image) gets no taps: black.
     */
    private class PilTaps(val start: IntArray, val count: IntArray, val k: IntArray, val ksize: Int, val first: Int, val last: Int) {
        companion object {
            private const val PRECISION_BITS = 32 - 8 - 2
            const val HALF = 1 shl (PRECISION_BITS - 1)

            fun clip8(v: Int): Int = (v shr PRECISION_BITS).coerceIn(0, 255)

            fun of(inSize: Int, outSize: Int, from: Int, n: Int): PilTaps {
                val scale = inSize.toDouble() / outSize
                val filterScale = max(scale, 1.0)
                val support = filterScale // the bilinear filter's support is 1
                val ss = 1.0 / filterScale
                val ksize = kotlin.math.ceil(support).toInt() * 2 + 1
                val start = IntArray(n)
                val count = IntArray(n)
                val k = IntArray(n * ksize)
                val pre = DoubleArray(ksize)
                var first = Int.MAX_VALUE
                var last = -1
                for (o in 0 until n) {
                    val xx = from + o
                    if (xx < 0 || xx >= outSize) continue
                    val center = (xx + 0.5) * scale
                    val xmin = max((center - support + 0.5).toInt(), 0)
                    val xmax = min((center + support + 0.5).toInt(), inSize) - xmin
                    var total = 0.0
                    for (x in 0 until xmax) {
                        // The same operations, in the same order, as Pillow (the weights must round alike).
                        val t = kotlin.math.abs((x + xmin - center + 0.5) * ss)
                        pre[x] = if (t < 1.0) 1.0 - t else 0.0
                        total += pre[x]
                    }
                    for (x in 0 until xmax) {
                        val weight = if (total != 0.0) pre[x] / total else pre[x]
                        k[o * ksize + x] = (0.5 + weight * (1 shl PRECISION_BITS)).toInt()
                    }
                    start[o] = xmin
                    count[o] = xmax
                    if (xmax > 0) {
                        first = min(first, xmin)
                        last = max(last, xmin + xmax - 1)
                    }
                }
                return PilTaps(start, count, k, ksize, if (last < 0) 0 else first, last)
            }
        }
    }

    /**
     * Which source pixels make each of [n] output pixels along one axis, and how much: output pixel o
     * covers [origin + o·step, origin + (o + 1)·step) of a [size]-pixel axis. Weights add up to 1; a
     * footprint that rounding left outside the axis takes the nearest pixel.
     */
    private class Taps(val start: IntArray, val count: IntArray, val weight: FloatArray, val maxTaps: Int, val first: Int, val last: Int) {
        companion object {
            fun of(origin: Float, step: Float, n: Int, size: Int): Taps {
                val maxTaps = ceil(step) + 2
                val start = IntArray(n)
                val count = IntArray(n)
                val weight = FloatArray(n * maxTaps)
                var first = size - 1
                var last = 0
                for (o in 0 until n) {
                    val f0 = origin + o * step
                    val f1 = f0 + step
                    val i0 = f0.toInt().coerceIn(0, size - 1)
                    val i1 = (ceil(f1) - 1).coerceIn(i0, minOf(size - 1, i0 + maxTaps - 1))
                    var sum = 0f
                    for (i in i0..i1) {
                        val wgt = overlap(f0, f1, i)
                        weight[o * maxTaps + i - i0] = wgt
                        sum += wgt
                    }
                    start[o] = i0
                    if (sum <= 0f) {
                        count[o] = 1
                        weight[o * maxTaps] = 1f
                    } else {
                        count[o] = i1 - i0 + 1
                        for (k in 0 until count[o]) weight[o * maxTaps + k] /= sum
                    }
                    first = minOf(first, i0)
                    last = maxOf(last, i0 + count[o] - 1)
                }
                return Taps(start, count, weight, maxTaps, first, last)
            }
        }
    }

    /** Length of [a0, a1) inside pixel [i, i + 1). */
    private fun overlap(a0: Float, a1: Float, i: Int): Float = (min(a1, i + 1f) - max(a0, i.toFloat())).coerceAtLeast(0f)

    private fun ceil(v: Float): Int = kotlin.math.ceil(v.toDouble()).toInt()

    /** A normalized value as the quantized input stores it: round(v / scale) + zeroPoint, clamped to the type. */
    fun quantize(v: Float, scale: Float, zeroPoint: Int, type: TensorType): Int {
        val q = (v / scale).roundToInt() + zeroPoint
        return when (type) {
            TensorType.UINT8 -> q.coerceIn(0, 255)
            TensorType.INT8 -> q.coerceIn(-128, 127)
            TensorType.FLOAT32 -> q
        }
    }

    /** Raw quantized outputs (as stored: unsigned for uint8, signed for int8) to real values. */
    fun dequantize(raw: IntArray, scale: Float, zeroPoint: Int): FloatArray = FloatArray(raw.size) { (raw[it] - zeroPoint) * scale }

    /** Softmax of [logits] / [temperature] (numerically stable). */
    fun softmax(logits: FloatArray, temperature: Float = 1f): FloatArray {
        if (logits.isEmpty()) return logits
        val t = if (temperature > 0f) temperature else 1f
        var maxV = Float.NEGATIVE_INFINITY
        for (v in logits) if (v / t > maxV) maxV = v / t
        val e = DoubleArray(logits.size) { exp((logits[it] / t - maxV).toDouble()) }
        val sum = e.sum()
        return FloatArray(logits.size) { (e[it] / sum).toFloat() }
    }

    /** Probabilities sharpened or softened by [temperature] (p^(1/T), renormalized); also renormalizes a sum ≠ 1. */
    fun temper(probabilities: FloatArray, temperature: Float): FloatArray {
        if (probabilities.isEmpty()) return probabilities
        if (temperature == 1f) {
            val sum = probabilities.sum().takeIf { it > 0f } ?: return probabilities
            return FloatArray(probabilities.size) { probabilities[it].coerceAtLeast(0f) / sum }
        }
        val logs = FloatArray(probabilities.size) { ln(probabilities[it].coerceAtLeast(1e-12f).toDouble()).toFloat() }
        return softmax(logs, temperature)
    }

    /** The model's output as calibrated probabilities. */
    fun probabilities(output: FloatArray, spec: ModelSpec): FloatArray =
        if (spec.logits) softmax(output, spec.temperature) else temper(output, spec.temperature)

    /** Indices of the [k] largest values, largest first (ties: lower index first). */
    fun topK(values: FloatArray, k: Int): List<Int> =
        values.indices.sortedWith(compareByDescending<Int> { values[it] }.thenBy { it }).take(k)

    /**
     * The suggestions for [probabilities] (in [labels] order): the [TOP_K] best foods among [known]
     * (null: every label), and "no food" when [notFood] wins or every food is under [minConfidence].
     */
    fun recognize(
        probabilities: FloatArray,
        labels: List<String>,
        minConfidence: Float,
        known: ((String) -> Boolean)? = null,
        notFood: String = ModelSpec.NOT_FOOD,
    ): Recognition {
        val n = min(probabilities.size, labels.size)
        if (n == 0) return Recognition(emptyList(), notFood = true, topLabel = null, topConfidence = null)
        val best = topK(probabilities.copyOf(n), 1).first()
        val foods = (0 until n).filter { labels[it] != notFood && (known == null || known(labels[it])) }
        val ranked = foods.sortedWith(compareByDescending<Int> { probabilities[it] }.thenBy { it }).take(TOP_K)
        val suggestions = ranked.map { Suggestion(labels[it], probabilities[it]) }
        val none = labels[best] == notFood || suggestions.none { it.confidence >= minConfidence }
        return Recognition(suggestions, none, labels[best], probabilities[best])
    }

    /** "Muy probable" from 50 %, "Probable" from 20 %, else "Posible". */
    fun hint(confidence: Float): ConfidenceHint = when {
        confidence >= 0.5f -> ConfidenceHint.VERY_LIKELY
        confidence >= 0.2f -> ConfidenceHint.LIKELY
        else -> ConfidenceHint.POSSIBLE
    }
}
