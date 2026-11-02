package com.enjoyyourtime.globalfitness.food.recognition

import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
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
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Our food recognizer (`assets/food/food_model.tflite`, described by `food_model.json`), run on the
 * phone with LiteRT: the photo never leaves the device. Loaded off the main thread when a meal photo
 * is about to be taken or picked (never at app start), and closed again after [idleMs] without use or
 * when Android is short of memory ([release]). A missing or broken model makes [recognize] return
 * null: the screens fall back to the search.
 */
class FoodRecognizer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val idleMs: Long = IDLE_MS,
) {
    private class Loaded(val interpreter: Interpreter, val spec: ModelSpec, val inputType: TensorType, val inputScale: Float, val inputZeroPoint: Int, val outputType: TensorType, val outputScale: Float, val outputZeroPoint: Int, val outputSize: Int)

    private val mutex = Mutex()
    private var loaded: Loaded? = null
    private var failed = false

    /** Closes the model once it was not used for [idleMs] (replaced on every use, under [mutex]). */
    private var idleJob: Job? = null

    /** Whether the model files are in the app at all (cheap: no loading). */
    val bundled: Boolean by lazy {
        runCatching { context.assets.list(DIR)?.toSet().orEmpty().containsAll(listOf(MODEL_FILE, SPEC_FILE)) }.getOrDefault(false)
    }

    /** Loads the model now (the camera or the photo picker is opening) so the photo is recognized sooner. */
    suspend fun warmUp() {
        withContext(compute) {
            mutex.withLock {
                load()
                closeWhenIdle()
            }
        }
    }

    /** Frees the model's memory now (it loads again for the next photo). Safe from any thread. */
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
                val bytesPer = if (m.outputType == TensorType.FLOAT32) 4 else 1
                val outBuffer = ByteBuffer.allocateDirect(m.outputSize * bytesPer).order(ByteOrder.nativeOrder())
                val prepared = SystemClock.elapsedRealtime()
                m.interpreter.run(inBuffer, outBuffer)
                val ran = SystemClock.elapsedRealtime()
                outBuffer.rewind()
                val raw = when (m.outputType) {
                    TensorType.FLOAT32 -> FloatArray(m.outputSize).also { outBuffer.asFloatBuffer().get(it) }
                    TensorType.UINT8 -> RecognitionMath.dequantize(IntArray(m.outputSize) { outBuffer.get(it).toInt() and 0xFF }, m.outputScale, m.outputZeroPoint)
                    TensorType.INT8 -> RecognitionMath.dequantize(IntArray(m.outputSize) { outBuffer.get(it).toInt() }, m.outputScale, m.outputZeroPoint)
                }
                val probabilities = RecognitionMath.probabilities(raw, m.spec)
                RecognitionMath.recognize(probabilities, m.spec.labels, m.spec.minConfidence, known, m.spec.notFoodLabel)
                    .copy(latencyMs = SystemClock.elapsedRealtime() - started)
                    .also { Log.i(TAG, "Recognized in ${it.latencyMs} ms (image ${prepared - started} ms, model ${ran - prepared} ms): ${it.topLabel} ${it.topConfidence}") }
            } catch (e: Exception) {
                Log.w(TAG, "Food recognition failed", e)
                null
            } finally {
                closeWhenIdle()
            }
        }
    }

    /** Call under [mutex]. */
    private fun closeWhenIdle() {
        idleJob?.cancel()
        if (loaded == null) return
        idleJob = scope.launch(compute) {
            delay(idleMs)
            mutex.withLock { close() }
        }
    }

    /** Call under [mutex]. */
    private fun close() {
        val m = loaded ?: return
        loaded = null
        runCatching { m.interpreter.close() }
        Log.i(TAG, "Food model released")
    }

    /** Call under [mutex]. Once a load failed, it is not tried again in this process. */
    private fun load(): Loaded? {
        loaded?.let { return it }
        if (failed || !bundled) return null
        return try {
            val spec = ModelSpec.parse(context.assets.open("$DIR/$SPEC_FILE").bufferedReader().use { it.readText() })
            val interpreter = Interpreter(mapModel(), Interpreter.Options().setNumThreads(THREADS))
            try {
                inspect(interpreter, spec).also {
                    loaded = it
                    Log.i(TAG, "Food model loaded: ${it.spec.describe()}, input ${it.inputType}, output ${it.outputType}")
                }
            } catch (e: Throwable) {
                // A model that does not match its spec: its native memory is given back at once.
                interpreter.close()
                throw e
            }
        } catch (e: Exception) {
            failed = true
            Log.w(TAG, "Food model not usable; search only", e)
            null
        } catch (e: LinkageError) {
            // The native runtime is missing for this ABI.
            failed = true
            Log.w(TAG, "LiteRT runtime not available; search only", e)
            null
        }
    }

    /** The model's tensors read against [spec]; throws when they do not match it. */
    private fun inspect(interpreter: Interpreter, spec: ModelSpec): Loaded {
        val inTensor = interpreter.getInputTensor(0)
        val outTensor = interpreter.getOutputTensor(0)
        val shape = inTensor.shape()
        // The file is the truth for sizes and types; the spec says how to fill them.
        val (h, w) = if (spec.channelsFirst) shape.getOrNull(2) to shape.getOrNull(3) else shape.getOrNull(1) to shape.getOrNull(2)
        val effective = if (w != null && h != null && (w != spec.width || h != spec.height)) spec.copy(width = w, height = h) else spec
        val outputSize = outTensor.shape().fold(1) { a, b -> a * b }
        require(outputSize == spec.labels.size) { "model has $outputSize outputs for ${spec.labels.size} labels" }
        val inQ = inTensor.quantizationParams()
        val outQ = outTensor.quantizationParams()
        return Loaded(
            interpreter = interpreter,
            spec = effective,
            inputType = type(inTensor.dataType()),
            inputScale = spec.inputScale ?: inQ.scale.takeIf { it > 0f } ?: 1f,
            inputZeroPoint = spec.inputZeroPoint ?: inQ.zeroPoint,
            outputType = type(outTensor.dataType()),
            outputScale = outQ.scale.takeIf { it > 0f } ?: spec.outputScale,
            outputZeroPoint = if (outQ.scale > 0f) outQ.zeroPoint else spec.outputZeroPoint,
            outputSize = outputSize,
        )
    }

    private fun mapModel(): MappedByteBuffer = context.assets.openFd("$DIR/$MODEL_FILE").use { fd ->
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
        private const val THREADS = 4

        /** Unused this long, the model is closed (the foods of one plate are logged well within it). */
        const val IDLE_MS = 3 * 60_000L

        /**
         * Whether Android's memory signal [level] (ComponentCallbacks2) should free the model: memory
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
