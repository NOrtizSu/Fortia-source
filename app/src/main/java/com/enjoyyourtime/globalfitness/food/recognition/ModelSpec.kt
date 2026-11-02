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
) {
    /** For the log: everything but the labels. */
    fun describe(): String =
        "v$version, ${labels.size} labels, ${width}x$height, ${if (channelsFirst) "NCHW" else "NHWC"}${if (bgr) " BGR" else ""}, $inputType, " +
            "mean=$mean, std=$std, unit=$unitRange, crop=$centerCrop/$cropFraction/${resizeShortSide ?: "-"}, " +
            "${if (bilinear) "bilinear" else "area"}, in=$inputScale/$inputZeroPoint, " +
            "out=$outputType/$outputScale/$outputZeroPoint, logits=$logits, T=$temperature, min=$minConfidence"

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
            )
        }

        private fun type(v: Any?): TensorType = when ((v as? String)?.lowercase()) {
            "uint8" -> TensorType.UINT8
            "int8" -> TensorType.INT8
            else -> TensorType.FLOAT32
        }

        private fun int(v: Any?): Int? = (v as? Number)?.toInt()

        private fun float(v: Any?): Float? = (v as? Number)?.toFloat()?.takeIf { it.isFinite() }

        private fun floats(v: Any?): List<Float>? = (v as? List<*>)?.map { (it as? Number)?.toFloat()?.takeIf { f -> f.isFinite() } ?: return null }
    }
}
