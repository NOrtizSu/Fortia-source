package com.enjoyyourtime.globalfitness.food.pack

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.common.MlKit
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** What reading the text of a photo gave. */
sealed interface TextReading {
    /** The lines read (maybe none). */
    data class Lines(val lines: List<TextLine>) : TextReading

    /** Text recognition is not on this phone: being downloaded by Google Play services ([preparing]), or no Play services at all. */
    data class Unavailable(val preparing: Boolean) : TextReading

    /** The recognizer failed on this photo. */
    data object Failed : TextReading
}

/**
 * The writing systems ML Kit reads on the phone; each also reads Latin text. The app's language picks
 * one; Russian (Cyrillic) and Arabic have none, so packages are not read in those languages (their
 * words would be misread as Latin letters).
 */
enum class TextScript(private val scripts: Set<Character.UnicodeScript>) {
    LATIN(setOf(Character.UnicodeScript.LATIN)),
    CHINESE(setOf(Character.UnicodeScript.HAN)),
    JAPANESE(setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA)),
    KOREAN(setOf(Character.UnicodeScript.HANGUL, Character.UnicodeScript.HAN)),
    DEVANAGARI(setOf(Character.UnicodeScript.DEVANAGARI)),
    ;

    /** Whether [c] is written in this script (a Latin letter is not Japanese, even when the Japanese recognizer read it). */
    fun owns(c: Char): Boolean = runCatching { Character.UnicodeScript.of(c.code) in scripts }.getOrDefault(false)

    companion object {
        /** The script for the app's [language] (two letters), or null when ML Kit has none for it. */
        fun forLanguage(language: String): TextScript? = when (language.lowercase()) {
            "ru", "ar" -> null
            "zh" -> CHINESE
            "ja" -> JAPANESE
            "ko" -> KOREAN
            "hi" -> DEVANAGARI
            else -> LATIN
        }
    }
}

/**
 * Text recognition of a package photo, on the phone: Google ML Kit's recognizers, which Google Play
 * services keeps (downloaded once, then offline; the photo and its text are not sent to Google, only
 * ML Kit's usage metrics). Nothing of ML Kit runs before the first package is read (no start-up
 * provider, no client, no download request); the recognizers are closed after [idleMs] without use.
 */
class TextReader(private val context: Context, private val scope: CoroutineScope, private val idleMs: Long = IDLE_MS) {
    private val mutex = Mutex()
    private val recognizers = HashMap<TextScript, TextRecognizer>()
    private var idleJob: Job? = null
    private var initialized = false

    /** The scripts whose download Google Play services was asked for in this run (each is its own module). */
    private val installAsked: MutableSet<TextScript> = ConcurrentHashMap.newKeySet()

    private fun playServices(): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    /** The lines of text in [bitmap] (upright) in [script], with their place as shares of its height. */
    suspend fun read(bitmap: Bitmap, script: TextScript): TextReading = withContext(Dispatchers.Default) {
        if (!playServices()) return@withContext TextReading.Unavailable(preparing = false)
        val reading = mutex.withLock {
            val started = SystemClock.elapsedRealtime()
            try {
                val recognizer = open(script)
                val text = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
                val h = bitmap.height.toFloat()
                val lines = text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                    val box = line.boundingBox ?: return@mapNotNull null
                    TextLine(line.text, box.top / h, box.height() / h, line.confidence)
                }
                Log.i(TAG, "Read ${lines.size} lines ($script) in ${SystemClock.elapsedRealtime() - started} ms")
                TextReading.Lines(lines)
            } catch (e: CancellationException) {
                throw e
            } catch (e: MlKitException) {
                Log.w(TAG, "Text recognition failed (${e.errorCode})", e)
                if (e.errorCode == MlKitException.UNAVAILABLE) TextReading.Unavailable(preparing = true) else TextReading.Failed
            } catch (e: Exception) {
                Log.w(TAG, "Text recognition failed", e)
                TextReading.Failed
            } finally {
                closeWhenIdle()
            }
        }
        // Not downloaded yet: Google Play services is asked for it (once per run and script), now that it is needed.
        if (reading is TextReading.Unavailable && reading.preparing) requestInstall(script)
        reading
    }

    private fun requestInstall(script: TextScript) {
        if (!installAsked.add(script)) return
        scope.launch(Dispatchers.IO) {
            try {
                val r = mutex.withLock { open(script) }
                ModuleInstall.getClient(context).installModules(ModuleInstallRequest.newBuilder().addApi(r).build()).await()
                Log.i(TAG, "Text recognition download requested ($script)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                installAsked.remove(script)
                Log.w(TAG, "Could not ask for text recognition", e)
            } finally {
                mutex.withLock { closeWhenIdle() }
            }
        }
    }

    /** Call under [mutex]. ML Kit is started here, the first time (its start-up provider is removed from the app). */
    private fun open(script: TextScript): TextRecognizer {
        recognizers[script]?.let { return it }
        if (!initialized) {
            try {
                MlKit.initialize(context)
            } catch (e: IllegalStateException) {
                // Already started (by another library): fine. Any other failure is tried again at the next read.
            }
            initialized = true
        }
        val options = when (script) {
            TextScript.LATIN -> TextRecognizerOptions.DEFAULT_OPTIONS
            TextScript.CHINESE -> ChineseTextRecognizerOptions.Builder().build()
            TextScript.JAPANESE -> JapaneseTextRecognizerOptions.Builder().build()
            TextScript.KOREAN -> KoreanTextRecognizerOptions.Builder().build()
            TextScript.DEVANAGARI -> DevanagariTextRecognizerOptions.Builder().build()
        }
        return TextRecognition.getClient(options).also { recognizers[script] = it }
    }

    /** Call under [mutex]. */
    private fun closeWhenIdle() {
        idleJob?.cancel()
        if (recognizers.isEmpty()) return
        idleJob = scope.launch {
            delay(idleMs)
            mutex.withLock {
                recognizers.values.forEach { runCatching { it.close() } }
                recognizers.clear()
            }
        }
    }

    private companion object {
        const val TAG = "TextReader"
        const val IDLE_MS = 3 * 60_000L
    }
}
