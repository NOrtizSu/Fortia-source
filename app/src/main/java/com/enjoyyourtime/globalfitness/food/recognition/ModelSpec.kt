package com.enjoyyourtime.globalfitness.food.recognition

import com.enjoyyourtime.globalfitness.wearable.banglejs.MiniJson

/** Element type of a model tensor. */
enum class TensorType { FLOAT32, UINT8, INT8 }

/**
 * How to feed and read the food recognizer, from `assets/food/food_model.json` (written by the
 * training pipeline next to `food_model.tflite`): nothing about the preprocessing is hard-coded in
 * the app. Unknown fields are ignored; missing ones take the defaults of the contract.
 */
data class ModelSpec(
    val version: Int,
    /** Class ids in output order; [notFoodLabel] is the "no food in the photo" class. */
    val labels: List<String>,
    val notFoodLabel: String,
    val width: Int,
    val height: Int,
    /** "NHWC" (default) or "NCHW". */
    val channelsFirst: Boolean,
    val inputType: TensorType,
    /** The channels in blue-green-red order instead of RGB. */
    val bgr: Boolean,
    val mean: List<Float>,
    val std: List<Float>,
    /** Pixel values are 0..1 (true) or 0..255 (false) before the mean and std are applied. */
    val unitRange: Boolean,
    /**
     * Short side resized then the centre cropped ([cropFraction] of it: the model's size over
     * `resize_short_side` when given, else `crop_fraction`), or the whole photo stretched.
     */
    val centerCrop: Boolean,
    val cropFraction: Float,
    /** Quantization of a uint8 / int8 input (null: taken from the model file itself). */
    val inputScale: Float?,
    val inputZeroPoint: Int?,
    val outputType: TensorType,
    val outputScale: Float,
    val outputZeroPoint: Int,
    /** The output is logits (softmax applied here) rather than probabilities. */
    val logits: Boolean,
    val temperature: Float,
    val minConfidence: Float,
    /**
     * `interpolation: "bilinear"`: resized exactly as the training pipeline did (Pillow's antialiased
     * bilinear filter, 8-bit between its two passes), from the short side `resize_short_side`
     * ([resizeShortSide]). Otherwise the crop is area-averaged: the same picture to within rounding.
     */
    val bilinear: Boolean = false,
    val resizeShortSide: Int? = null,
    /** The grams estimate of the model's second output (version 2; null: none, or not enabled). */
    val grams: GramsSpec? = null,
) {
    /** For the log: everything but the labels. */
    fun describe(): String =
        "v$version, ${labels.size} labels, ${width}x$height, ${if (channelsFirst) "NCHW" else "NHWC"}${if (bgr) " BGR" else ""}, $inputType, " +
            "mean=$mean, std=$std, unit=$unitRange, crop=$centerCrop/$cropFraction/${resizeShortSide ?: "-"}, " +
            "${if (bilinear) "bilinear" else "area"}, in=$inputScale/$inputZeroPoint, " +
            "out=$outputType/$outputScale/$outputZeroPoint, logits=$logits, T=$temperature, min=$minConfidence, " +
            "grams=${grams?.let { "output ${it.outputIndex ?: "?"}" } ?: "off"}"

    companion object {
        const val NOT_FOOD = "_not_food"
        private const val MAX_SIDE = 4096

        /** The spec in [json]; throws [IllegalArgumentException] when it is not usable (no labels, bad sizes). */
        fun parse(json: String): ModelSpec {
            val root = MiniJson.parse(json) as? Map<*, *> ?: throw IllegalArgumentException("not an object")
            val labels = (root["labels"] as? List<*>).orEmpty().map { it as? String ?: throw IllegalArgumentException("bad label") }
            require(labels.isNotEmpty()) { "no labels" }
            val input = root["input"] as? Map<*, *> ?: emptyMap<String, Any?>()
            val output = root["output"] as? Map<*, *> ?: emptyMap<String, Any?>()
            val width = int(input["width"]) ?: 224
            val height = int(input["height"]) ?: width
            require(width in 1..MAX_SIDE && height in 1..MAX_SIDE) { "bad input size" }
            val mean = floats(input["mean"]) ?: listOf(0f, 0f, 0f)
            val std = floats(input["std"]) ?: listOf(1f, 1f, 1f)
            require(mean.size == 3 && std.size == 3 && std.all { it > 0f }) { "bad mean / std" }
            val crop = (input["resize"] as? String)?.lowercase() ?: "short_side_then_center_crop"
            // "Resize the short side to N, then crop the model's size": the crop is size / N of the short side.
            val shortSide = float(input["resize_short_side"])?.takeIf { it > 0f }
            return ModelSpec(
                version = int(root["version"]) ?: 1,
                labels = labels,
                notFoodLabel = (root["not_food_label"] as? String)?.takeIf { it.isNotEmpty() } ?: NOT_FOOD,
                width = width,
                height = height,
                channelsFirst = (input["layout"] as? String).equals("NCHW", ignoreCase = true),
                inputType = type(input["dtype"]),
                bgr = (input["channels"] as? String).equals("BGR", ignoreCase = true),
                mean = mean,
                std = std,
                unitRange = !(input["range"] as? String).orEmpty().trim().startsWith("0..255"),
                centerCrop = crop.contains("crop"),
                cropFraction = (shortSide?.let { minOf(width, height) / it } ?: float(input["crop_fraction"]) ?: 0.875f).coerceIn(0.1f, 1f),
                inputScale = float(input["scale"])?.takeIf { it > 0f },
                inputZeroPoint = int(input["zero_point"]),
                outputType = type(output["dtype"]),
                outputScale = float(output["scale"])?.takeIf { it > 0f } ?: 1f,
                outputZeroPoint = int(output["zero_point"]) ?: 0,
                logits = (output["kind"] as? String)?.lowercase() != "probabilities",
                temperature = float(root["temperature"])?.takeIf { it > 0f } ?: 1f,
                minConfidence = (float(root["min_confidence"]) ?: 0.05f).coerceIn(0f, 1f),
                bilinear = (input["interpolation"] as? String).equals("bilinear", ignoreCase = true),
                resizeShortSide = shortSide?.let { kotlin.math.round(it).toInt() }?.takeIf { it in 1..MAX_SIDE },
                grams = GramsSpec.parse(root["grams"] as? Map<*, *>),
            )
        }

        private fun type(v: Any?): TensorType = when ((v as? String)?.lowercase()) {
            "uint8" -> TensorType.UINT8
            "int8" -> TensorType.INT8
            else -> TensorType.FLOAT32
        }

        internal fun int(v: Any?): Int? = (v as? Number)?.toInt()

        internal fun float(v: Any?): Float? = (v as? Number)?.toFloat()?.takeIf { it.isFinite() }

        internal fun floats(v: Any?): List<Float>? = (v as? List<*>)?.map { (it as? Number)?.toFloat()?.takeIf { f -> f.isFinite() } ?: return null }
    }
}

/**
 * The grams estimate of the classifier (`grams` in food_model.json, see grams_contract.md): its
 * second output is ln(grams) of the food in the picture; the app's estimate is
 * `exp(b0 + bModel·y + bPortion·ln(portion) + bShare·ln(share))`, clamped to [clampPortion] times the
 * food's portion and to [clampGrams], rounded to [roundTo].
 */
data class GramsSpec(
    /** The output tensor, when the json names it (else: the output with a single value). */
    val outputIndex: Int?,
    val b0: Double = 0.0,
    val bModel: Double = 1.0,
    val bPortion: Double = 0.0,
    val bShare: Double = 0.0,
    val clampPortionMin: Double = 0.25,
    val clampPortionMax: Double = 4.0,
    val clampGramsMin: Double = 5.0,
    val clampGramsMax: Double = 1500.0,
    val roundTo: Double = 5.0,
) {
    companion object {
        /** The block, or null when absent or `enabled` is not true (then no estimate: the food's portion). */
        fun parse(block: Map<*, *>?): GramsSpec? {
            if (block == null || block["enabled"] != true) return null
            val output = block["output"] as? Map<*, *>
            fun d(key: String, default: Double) = (block[key] as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: default
            fun pair(key: String, default: Pair<Double, Double>): Pair<Double, Double> {
                val v = ModelSpec.floats(block[key])?.map { it.toDouble() }
                return if (v != null && v.size == 2 && v[0] > 0 && v[1] >= v[0]) v[0] to v[1] else default
            }
            val portion = pair("clamp_portion", 0.25 to 4.0)
            val grams = pair("clamp_grams", 5.0 to 1500.0)
            return GramsSpec(
                outputIndex = ModelSpec.int(output?.get("index"))?.takeIf { it >= 0 },
                b0 = d("b0", 0.0),
                bModel = d("b_model", 1.0),
                bPortion = d("b_portion", 0.0),
                bShare = d("b_share", 0.0),
                clampPortionMin = portion.first,
                clampPortionMax = portion.second,
                clampGramsMin = grams.first,
                clampGramsMax = grams.second,
                roundTo = d("round_to", 5.0).takeIf { it > 0 } ?: 5.0,
            )
        }
    }
}

/**
 * How to feed and read the food item detector (`assets/food/food_detector.json`, see
 * detector_contract.md): the whole photo stretched to [width]×[height], RGB 0..1; outputs boxes
 * `[1,K,4]` (ymin, xmin, ymax, xmax normalised to the photo), scores `[1,K]` (descending), count `[1]`,
 * decoded and filtered inside the model.
 */
data class DetectorSpec(
    val version: Int,
    val width: Int,
    val height: Int,
    /** Output tensor indices (null: found by rank, 3-D boxes, 2-D scores, 1-D count). */
    val boxesIndex: Int?,
    val scoresIndex: Int?,
    val countIndex: Int?,
    val scoreThreshold: Float = 0.25f,
    val maxItems: Int = 6,
    /** A box's short side under this share of the photo's short side is dropped. */
    val minBoxSide: Float = 0.06f,
    /** Margin of an item's square crop, as a share of the box's long side on each side. */
    val cropMargin: Float = 0.1f,
    /**
     * `group_suppression` with `"rule": "drop_container"` ([DetectionMath.withoutContainers]): a box holding at
     * least [containerMinInside] other kept boxes, each with at least this share of its area inside it, is
     * dropped. Null (no block, or another rule): no suppression.
     */
    val containerInside: Float? = null,
    val containerMinInside: Int = 2,
) {
    /** The detector's input as the classifier's preprocessing understands it: stretched, 0..1, no mean/std. */
    fun inputSpec(): ModelSpec = ModelSpec(
        version = version,
        labels = listOf("item"),
        notFoodLabel = ModelSpec.NOT_FOOD,
        width = width,
        height = height,
        channelsFirst = false,
        inputType = TensorType.FLOAT32,
        bgr = false,
        mean = listOf(0f, 0f, 0f),
        std = listOf(1f, 1f, 1f),
        unitRange = true,
        centerCrop = false,
        cropFraction = 1f,
        inputScale = null,
        inputZeroPoint = null,
        outputType = TensorType.FLOAT32,
        outputScale = 1f,
        outputZeroPoint = 0,
        logits = false,
        temperature = 1f,
        minConfidence = 0f,
    )

    companion object {
        /** The spec in [json]; throws [IllegalArgumentException] when it is not usable. */
        fun parse(json: String): DetectorSpec {
            val root = MiniJson.parse(json) as? Map<*, *> ?: throw IllegalArgumentException("not an object")
            val input = root["input"] as? Map<*, *> ?: emptyMap<String, Any?>()
            val outputs = root["outputs"] as? Map<*, *> ?: emptyMap<String, Any?>()
            val width = ModelSpec.int(input["width"]) ?: 320
            val height = ModelSpec.int(input["height"]) ?: width
            require(width in 1..4096 && height in 1..4096) { "bad input size" }
            fun index(name: String) = ModelSpec.int((outputs[name] as? Map<*, *>)?.get("index"))?.takeIf { it >= 0 }
            val crop = root["crop"] as? Map<*, *>
            // Only "drop_container" exists; no block or an unknown rule leaves every box in.
            val group = (root["group_suppression"] as? Map<*, *>)?.takeIf { it["rule"] == "drop_container" }
            return DetectorSpec(
                version = ModelSpec.int(root["version"]) ?: 1,
                width = width,
                height = height,
                boxesIndex = index("boxes"),
                scoresIndex = index("scores"),
                countIndex = index("count"),
                scoreThreshold = (ModelSpec.float(root["score_threshold"]) ?: 0.25f).coerceIn(0f, 1f),
                maxItems = (ModelSpec.int(root["max_items"]) ?: 6).coerceIn(1, 20),
                minBoxSide = (ModelSpec.float(root["min_box_side"]) ?: 0.06f).coerceIn(0f, 1f),
                cropMargin = (ModelSpec.float(crop?.get("margin")) ?: 0.1f).coerceIn(0f, 1f),
                containerInside = group?.let { (ModelSpec.float(it["inside"])?.takeIf { v -> v > 0f } ?: 0.8f).coerceAtMost(1f) },
                containerMinInside = (group?.let { ModelSpec.int(it["min_inside"]) } ?: 2).coerceAtLeast(1),
            )
        }
    }
}
