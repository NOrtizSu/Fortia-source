package com.enjoyyourtime.globalfitness.food.recognition

import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.enjoyyourtime.globalfitness.food.pack.Embedded
import com.enjoyyourtime.globalfitness.food.pack.PackMath
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Our food recognizer (`assets/food/food_model.tflite`, described by `food_model.json`) and, when the
 * app ships it, the food item detector (`food_detector.tflite` + `food_detector.json`, optional), run
 * on the phone with LiteRT: the photo never leaves the device. Loaded off the main thread when a meal
 * photo is about to be taken or picked (never at app start), and closed again after [idleMs] without
 * use or when Android is short of memory ([release]). A missing or broken classifier makes
 * [recognize] return null (the screens fall back to the search); a missing or broken detector makes
 * [detect] return null (one food per photo, as before).
 */
class FoodRecognizer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val idleMs: Long = IDLE_MS,
) {
    /** One output tensor read back: where, how many values, and how to turn them into floats. */
    private class Output(val index: Int, val size: Int, val type: TensorType, val scale: Float, val zeroPoint: Int)

    private class Loaded(
        val interpreter: Interpreter,
        val spec: ModelSpec,
        val inputType: TensorType,
        val inputScale: Float,
        val inputZeroPoint: Int,
        val logits: Output,
        /** The ln(grams) output when the spec enables it and the model has it. */
        val grams: Output?,
        /** The image embedding output when the spec has one and the model has it (embedding_contract.md). */
        val embedding: Output? = null,
    )

    private class LoadedDetector(val interpreter: Interpreter, val spec: DetectorSpec, val boxes: Int, val scores: Int, val count: Int, val k: Int)

    private val mutex = Mutex()
    private var loaded: Loaded? = null
    private var failed = false
    private var detector: LoadedDetector? = null
    private var detectorFailed = false

    /** Closes the models once they were not used for [idleMs] (replaced on every use, under [mutex]). */
    private var idleJob: Job? = null

    /** Whether the model files are in the app at all (cheap: no loading). */
    val bundled: Boolean by lazy { hasAssets(MODEL_FILE, SPEC_FILE) }

    /** Whether the app ships the item detector (cheap: no loading). */
    val detectorBundled: Boolean by lazy { hasAssets(DETECTOR_FILE, DETECTOR_SPEC_FILE) }

    /**
     * The classifier's embedding as its json describes it (cheap: the json only), null when the app's
     * model has none, or once loading showed the model is unusable or lacks that output (remembered for
     * this process): "products you already scanned" is then off.
     */
    val embeddingSpec: EmbeddingSpec?
        get() = if (embeddingMissing) null else jsonEmbedding

    private val jsonEmbedding: EmbeddingSpec? by lazy {
        if (!bundled) return@lazy null
        runCatching { ModelSpec.parse(context.assets.open("$DIR/$SPEC_FILE").bufferedReader().use { it.readText() }).embedding }.getOrNull()
    }

    /** Loading showed there is no usable embedding (the model failed to load, or its json names an output it lacks). */
    @Volatile
    private var embeddingMissing = false

    private fun hasAssets(vararg names: String): Boolean =
        runCatching { context.assets.list(DIR)?.toSet().orEmpty().containsAll(names.toList()) }.getOrDefault(false)

    /** Loads the models now (the camera or the photo picker is opening) so the photo is recognized sooner. */
    suspend fun warmUp() {
        withContext(compute) {
            mutex.withLock {
                load()
                loadDetector()
                closeWhenIdle()
            }
        }
    }

    /** Frees the models' memory now (they load again for the next photo). Safe from any thread. */
    fun release() {
        scope.launch(compute) { mutex.withLock { close() } }
    }

    /** Top-5 foods of [bitmap] among those [known] (in the catalog), or null when the model is not available. */
    suspend fun recognize(bitmap: Bitmap, known: (String) -> Boolean): Recognition? = withContext(compute) {
        mutex.withLock {
            val m = load() ?: return@withLock null
            val started = SystemClock.elapsedRealtime()
            try {
                val w = bitmap.width
                val h = bitmap.height
                val pixels = IntArray(w * h)
                bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                val input = RecognitionMath.preprocess(pixels, w, h, m.spec)
                val inBuffer = when (m.inputType) {
                    TensorType.FLOAT32 -> ByteBuffer.allocateDirect(input.size * 4).order(ByteOrder.nativeOrder()).apply {
                        asFloatBuffer().put(input)
                    }
                    TensorType.UINT8, TensorType.INT8 -> ByteBuffer.allocateDirect(input.size).order(ByteOrder.nativeOrder()).apply {
                        for (v in input) put(RecognitionMath.quantize(v, m.inputScale, m.inputZeroPoint, m.inputType).toByte())
                        rewind()
                    }
                }
                val outputs = listOfNotNull(m.logits, m.grams)
                val buffers = outputs.associate { o -> o.index to ByteBuffer.allocateDirect(o.size * if (o.type == TensorType.FLOAT32) 4 else 1).order(ByteOrder.nativeOrder()) }
                val prepared = SystemClock.elapsedRealtime()
                m.interpreter.runForMultipleInputsOutputs(arrayOf<Any>(inBuffer), buffers)
                val ran = SystemClock.elapsedRealtime()
                val raw = read(m.logits, buffers.getValue(m.logits.index))
                val probabilities = RecognitionMath.probabilities(raw, m.spec)
                val logGrams = m.grams?.let { read(it, buffers.getValue(it.index)).firstOrNull()?.toDouble() }
                val grams = if (m.spec.grams != null && logGrams != null && logGrams.isFinite()) ModelGrams(m.spec.grams, logGrams) else null
                RecognitionMath.recognize(probabilities, m.spec.labels, m.spec.minConfidence, known, m.spec.notFoodLabel)
                    .copy(latencyMs = SystemClock.elapsedRealtime() - started, grams = grams)
                    .also { Log.i(TAG, "Recognized ${w}x$h in ${it.latencyMs} ms (image ${prepared - started} ms, model ${ran - prepared} ms): ${it.topLabel} ${it.topConfidence}${logGrams?.let { g -> ", ln(g) $g" }.orEmpty()}") }
            } catch (e: Exception) {
                Log.w(TAG, "Food recognition failed", e)
                null
            } finally {
                closeWhenIdle()
            }
        }
    }

    /**
     * The image embedding of [bitmap] (embedding_contract.md): its own run of the classifier on the whole
     * picture stretched to the input size, L2-normalised. [Embedded.Zero] when the model gives an all-zero
     * vector for it (the picture has no usable embedding); [Embedded.Failed] when this picture's run
     * failed (out of memory…: worth trying again later); [Embedded.Unavailable] when the model has no
     * usable embedding at all (not bundled, could not load, no such output).
     */
    suspend fun embed(bitmap: Bitmap): Embedded = withContext(compute) {
        mutex.withLock {
            val m = load() ?: return@withLock Embedded.Unavailable
            val out = m.embedding ?: return@withLock Embedded.Unavailable
            val e = m.spec.embedding ?: return@withLock Embedded.Unavailable
            val started = SystemClock.elapsedRealtime()
            try {
                val picture = if (e.stretch) stretched(bitmap, m.spec.width, m.spec.height) else bitmap
                val w = picture.width
                val h = picture.height
                val pixels = IntArray(w * h)
                picture.getPixels(pixels, 0, w, 0, 0, w, h)
                if (picture !== bitmap) picture.recycle()
                // Already the model's size when stretched: the preprocessing only normalises it.
                val spec = if (e.stretch) m.spec.copy(centerCrop = false, cropFraction = 1f, bilinear = false, resizeShortSide = null) else m.spec
                val input = RecognitionMath.preprocess(pixels, w, h, spec)
                val inBuffer = when (m.inputType) {
                    TensorType.FLOAT32 -> ByteBuffer.allocateDirect(input.size * 4).order(ByteOrder.nativeOrder()).apply { asFloatBuffer().put(input) }
                    TensorType.UINT8, TensorType.INT8 -> ByteBuffer.allocateDirect(input.size).order(ByteOrder.nativeOrder()).apply {
                        for (v in input) put(RecognitionMath.quantize(v, m.inputScale, m.inputZeroPoint, m.inputType).toByte())
                        rewind()
                    }
                }
                val buffer = ByteBuffer.allocateDirect(out.size * if (out.type == TensorType.FLOAT32) 4 else 1).order(ByteOrder.nativeOrder())
                m.interpreter.runForMultipleInputsOutputs(arrayOf<Any>(inBuffer), mapOf(out.index to buffer))
                PackMath.embedded(read(out, buffer))
                    .also { Log.i(TAG, "Embedded ${bitmap.width}x${bitmap.height} in ${SystemClock.elapsedRealtime() - started} ms: ${it.javaClass.simpleName}") }
            } catch (e: Exception) {
                Log.w(TAG, "Image embedding failed", e)
                Embedded.Failed
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "Image embedding out of memory", e)
                Embedded.Failed
            } finally {
                closeWhenIdle()
            }
        }
    }

    /**
     * The food items the detector finds in [bitmap] (detector_contract.md: kept by score, size and
     * count), best first; null when the app has no usable detector.
     */
    suspend fun detect(bitmap: Bitmap): Detection? = withContext(compute) {
        mutex.withLock {
            val d = loadDetector() ?: return@withLock null
            val started = SystemClock.elapsedRealtime()
            try {
                val w = bitmap.width
                val h = bitmap.height
                // The whole photo stretched to the detector's size by the platform (halving first so it
                // stays antialiased): the contract allows small differences, and it is far faster.
                val small = stretched(bitmap, d.spec.width, d.spec.height)
                val pixels = IntArray(small.width * small.height)
                small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
                if (small !== bitmap) small.recycle()
                val input = RecognitionMath.preprocess(pixels, d.spec.width, d.spec.height, d.spec.inputSpec())
                val inBuffer = ByteBuffer.allocateDirect(input.size * 4).order(ByteOrder.nativeOrder()).apply { asFloatBuffer().put(input) }
                val boxes = ByteBuffer.allocateDirect(d.k * 4 * 4).order(ByteOrder.nativeOrder())
                val scores = ByteBuffer.allocateDirect(d.k * 4).order(ByteOrder.nativeOrder())
                val count = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
                val prepared = SystemClock.elapsedRealtime()
                d.interpreter.runForMultipleInputsOutputs(arrayOf<Any>(inBuffer), mapOf(d.boxes to boxes, d.scores to scores, d.count to count))
                val ran = SystemClock.elapsedRealtime()
                fun floats(b: ByteBuffer, n: Int) = FloatArray(n).also { b.rewind(); b.asFloatBuffer().get(it) }
                val items = DetectionMath.select(floats(boxes, d.k * 4), floats(scores, d.k), floats(count, 1)[0], w, h, d.spec)
                Log.i(TAG, "Detected ${items.size} items in ${SystemClock.elapsedRealtime() - started} ms (image ${prepared - started} ms, model ${ran - prepared} ms)")
                Detection(items, d.spec.cropMargin)
            } catch (e: Exception) {
                Log.w(TAG, "Food item detection failed", e)
                null
            } finally {
                closeWhenIdle()
            }
        }
    }

    /** [src] scaled to exactly [w]×[h] (aspect not kept), halved step by step while it is twice as large. */
    private fun stretched(src: Bitmap, w: Int, h: Int): Bitmap {
        if (src.width == w && src.height == h) return src
        var b = src
        while (b.width >= w * 2 && b.height >= h * 2) {
            val half = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)
            if (b !== src) b.recycle()
            b = half
        }
        val out = Bitmap.createScaledBitmap(b, w, h, true)
        if (b !== src && b !== out) b.recycle()
        return out
    }

    /** The values of output [o] as floats (dequantized). */
    private fun read(o: Output, buffer: ByteBuffer): FloatArray {
        buffer.rewind()
        return when (o.type) {
            TensorType.FLOAT32 -> FloatArray(o.size).also { buffer.asFloatBuffer().get(it) }
            TensorType.UINT8 -> RecognitionMath.dequantize(IntArray(o.size) { buffer.get(it).toInt() and 0xFF }, o.scale, o.zeroPoint)
            TensorType.INT8 -> RecognitionMath.dequantize(IntArray(o.size) { buffer.get(it).toInt() }, o.scale, o.zeroPoint)
        }
    }

    /** Call under [mutex]. */
    private fun closeWhenIdle() {
        idleJob?.cancel()
        if (loaded == null && detector == null) return
        idleJob = scope.launch(compute) {
            delay(idleMs)
            mutex.withLock { close() }
        }
    }

    /** Call under [mutex]. */
    private fun close() {
        loaded?.let { m ->
            loaded = null
            runCatching { m.interpreter.close() }
            Log.i(TAG, "Food model released")
        }
        detector?.let { d ->
            detector = null
            runCatching { d.interpreter.close() }
            Log.i(TAG, "Food detector released")
        }
    }

    /** Call under [mutex]. Once a load failed, it is not tried again in this process. */
    private fun load(): Loaded? {
        loaded?.let { return it }
        if (failed || !bundled) return null
        return try {
            val spec = ModelSpec.parse(context.assets.open("$DIR/$SPEC_FILE").bufferedReader().use { it.readText() })
            val interpreter = Interpreter(mapModel(MODEL_FILE), Interpreter.Options().setNumThreads(THREADS))
            try {
                inspect(interpreter, spec).also {
                    loaded = it
                    Log.i(TAG, "Food model loaded: ${it.spec.describe()}, input ${it.inputType}, output ${it.logits.type}${if (it.grams != null) " + grams" else ""}${if (it.embedding != null) " + embedding ${it.embedding.size}" else ""}")
                }
            } catch (e: Throwable) {
                // A model that does not match its spec: its native memory is given back at once.
                interpreter.close()
                throw e
            }
        } catch (e: Exception) {
            failed = true
            embeddingMissing = true
            Log.w(TAG, "Food model not usable; search only", e)
            null
        } catch (e: LinkageError) {
            // The native runtime is missing for this ABI.
            failed = true
            embeddingMissing = true
            Log.w(TAG, "LiteRT runtime not available; search only", e)
            null
        }
    }

    /** Call under [mutex]. The detector is optional: a failure only turns it off for this process. */
    private fun loadDetector(): LoadedDetector? {
        detector?.let { return it }
        if (detectorFailed || !detectorBundled) return null
        return try {
            val spec = DetectorSpec.parse(context.assets.open("$DIR/$DETECTOR_SPEC_FILE").bufferedReader().use { it.readText() })
            val interpreter = Interpreter(mapModel(DETECTOR_FILE), Interpreter.Options().setNumThreads(THREADS))
            try {
                inspectDetector(interpreter, spec).also {
                    detector = it
                    Log.i(TAG, "Food detector loaded: v${spec.version}, ${it.spec.width}x${it.spec.height}, K ${it.k}, threshold ${spec.scoreThreshold}, " +
                        "containers ${spec.containerInside?.let { inside -> "dropped ($inside, ${spec.containerMinInside})" } ?: "kept"}")
                }
            } catch (e: Throwable) {
                interpreter.close()
                throw e
            }
        } catch (e: Exception) {
            detectorFailed = true
            Log.w(TAG, "Food detector not usable; one food per photo", e)
            null
        } catch (e: LinkageError) {
            detectorFailed = true
            Log.w(TAG, "LiteRT runtime not available for the detector", e)
            null
        }
    }

    /** The model's tensors read against [spec]; throws when they do not match it. */
    private fun inspect(interpreter: Interpreter, spec: ModelSpec): Loaded {
        val inTensor = interpreter.getInputTensor(0)
        val shape = inTensor.shape()
        // The file is the truth for sizes and types; the spec says how to fill them.
        val (h, w) = if (spec.channelsFirst) shape.getOrNull(2) to shape.getOrNull(3) else shape.getOrNull(1) to shape.getOrNull(2)
        val effective = if (w != null && h != null && (w != spec.width || h != spec.height)) spec.copy(width = w, height = h) else spec
        val outputs = (0 until interpreter.outputTensorCount).map { it to interpreter.getOutputTensor(it) }
        // The class scores are the output with one value per label (version 2 adds a grams output).
        val (logitsIndex, logitsTensor) = outputs.firstOrNull { (_, t) -> t.numElements() == spec.labels.size }
            ?: throw IllegalArgumentException("no output has ${spec.labels.size} values (${outputs.map { it.second.numElements() }})")
        val grams = spec.grams?.let { g ->
            val named = g.outputIndex?.let { i -> outputs.firstOrNull { it.first == i && it.first != logitsIndex && it.second.numElements() == 1 } }
            (named ?: outputs.firstOrNull { (i, t) -> i != logitsIndex && t.numElements() == 1 })?.let { (i, t) -> output(i, t, 1f, 0) }
        }
        if (spec.grams != null && grams == null) Log.w(TAG, "The spec enables grams but the model has no such output: no estimate")
        // The embedding: the output the json names, else the one that is neither the logits nor a single value.
        val embedding = spec.embedding?.let { e ->
            fun usable(i: Int, t: Tensor) = i != logitsIndex && i != grams?.index && t.numElements() > 1
            val named = e.outputIndex?.let { i -> outputs.firstOrNull { (j, t) -> j == i && usable(j, t) } }
            (named ?: outputs.firstOrNull { (i, t) -> usable(i, t) && t.numElements() != spec.labels.size })?.let { (i, t) -> output(i, t, 1f, 0) }
        }
        if (spec.embedding != null && embedding == null) {
            // Remembered: the background embedding is not tried again for nothing at every visit.
            embeddingMissing = true
            Log.w(TAG, "The spec has an embedding but the model has no such output: no product matching")
        }
        val inQ = inTensor.quantizationParams()
        return Loaded(
            interpreter = interpreter,
            spec = effective.let { if (grams == null) it.copy(grams = null) else it }.let { if (embedding == null) it.copy(embedding = null) else it },
            inputType = type(inTensor.dataType()),
            inputScale = spec.inputScale ?: inQ.scale.takeIf { it > 0f } ?: 1f,
            inputZeroPoint = spec.inputZeroPoint ?: inQ.zeroPoint,
            logits = output(logitsIndex, logitsTensor, spec.outputScale, spec.outputZeroPoint),
            grams = grams,
            embedding = embedding,
        )
    }

    /** An output's reading: the file's quantization when it has one, else the spec's ([fallbackScale]). */
    private fun output(index: Int, t: Tensor, fallbackScale: Float, fallbackZeroPoint: Int): Output {
        val q = t.quantizationParams()
        return Output(
            index = index,
            size = t.numElements(),
            type = type(t.dataType()),
            scale = q.scale.takeIf { it > 0f } ?: fallbackScale,
            zeroPoint = if (q.scale > 0f) q.zeroPoint else fallbackZeroPoint,
        )
    }

    /** The detector's tensors: float32 in and out, boxes [1,K,4], scores [1,K], count [1] (by the json's indices, else by rank). */
    private fun inspectDetector(interpreter: Interpreter, spec: DetectorSpec): LoadedDetector {
        val inTensor = interpreter.getInputTensor(0)
        require(inTensor.dataType() == DataType.FLOAT32) { "detector input ${inTensor.dataType()}" }
        val shape = inTensor.shape()
        val effective = if (shape.size == 4 && (shape[1] != spec.height || shape[2] != spec.width)) spec.copy(height = shape[1], width = shape[2]) else spec
        val outputs = (0 until interpreter.outputTensorCount).map { it to interpreter.getOutputTensor(it) }
        fun byRank(rank: Int) = outputs.firstOrNull { it.second.shape().size == rank }?.first
        fun pick(named: Int?, rank: Int) = named?.takeIf { it in outputs.indices && outputs[it].second.shape().size == rank } ?: byRank(rank)
        val boxes = pick(spec.boxesIndex, 3) ?: throw IllegalArgumentException("no boxes output")
        val scores = pick(spec.scoresIndex, 2) ?: throw IllegalArgumentException("no scores output")
        val count = pick(spec.countIndex, 1) ?: throw IllegalArgumentException("no count output")
        val k = outputs[scores].second.numElements()
        require(outputs[boxes].second.numElements() == k * 4) { "boxes do not match scores" }
        require(listOf(boxes, scores, count).all { outputs[it].second.dataType() == DataType.FLOAT32 }) { "detector outputs must be float32" }
        return LoadedDetector(interpreter, effective, boxes, scores, count, k)
    }

    private fun mapModel(file: String): MappedByteBuffer = context.assets.openFd("$DIR/$file").use { fd ->
        FileInputStream(fd.fileDescriptor).use { stream ->
            stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }

    private fun type(t: DataType): TensorType = when (t) {
        DataType.UINT8 -> TensorType.UINT8
        DataType.INT8 -> TensorType.INT8
        DataType.FLOAT32 -> TensorType.FLOAT32
        else -> throw IllegalArgumentException("unsupported tensor type $t")
    }

    companion object {
        private const val TAG = "FoodRecognizer"
        const val DIR = "food"
        const val MODEL_FILE = "food_model.tflite"
        const val SPEC_FILE = "food_model.json"
        const val DETECTOR_FILE = "food_detector.tflite"
        const val DETECTOR_SPEC_FILE = "food_detector.json"
        private const val THREADS = 4

        /** Unused this long, the models are closed (the foods of one plate are logged well within it). */
        const val IDLE_MS = 3 * 60_000L

        /**
         * Whether Android's memory signal [level] (ComponentCallbacks2) should free the models: memory
         * running low while in front, or the process put on the background list. Not for "UI hidden"
         * alone (the camera app taking the meal photo); a reload costs a fraction of a second anyway.
         */
        @Suppress("DEPRECATION")
        fun releaseOnTrim(level: Int): Boolean =
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
                level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
    }
}
