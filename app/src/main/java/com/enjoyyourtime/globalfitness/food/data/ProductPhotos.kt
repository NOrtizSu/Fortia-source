package com.enjoyyourtime.globalfitness.food.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.enjoyyourtime.globalfitness.food.model.CustomFood
import com.enjoyyourtime.globalfitness.training.data.ExercisePhotos
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Photos of packaged products from Open Food Facts (the front of the package, CC BY-SA): one small
 * JPEG per product (at most [MAX_SIDE_PX]) in the app's private storage (`files/product_photos`).
 * Downloaded again when needed, so they stay on this phone: never uploaded to the cloud backup and
 * left out of Android's backup and device transfer. Blocking (call on IO).
 */
class ProductPhotos(dir: () -> File) {
    // Created on first use (the module is built during Application.onCreate, on the main thread).
    private val dir by lazy { dir().apply { mkdirs() } }

    fun file(name: String): File = File(dir, name)

    /** A product photo of this phone with that name (a name of [store], never a path). */
    fun exists(name: String): Boolean = valid(name) && file(name).isFile

    /**
     * The downloaded [bytes] as a new JPEG of [targetSize] (decoding it again also checks it is an
     * image); its file name, or null when it cannot be read as an image or written (tried again at a
     * later lookup). The decoding and encoding run on Android only (checked on the emulator); the
     * sizes are [targetSize]'s and [ExercisePhotos.sampleSize]'s (unit-tested).
     */
    fun store(bytes: ByteArray): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        targetSize(bounds.outWidth, bounds.outHeight) ?: return null
        val opts = BitmapFactory.Options().apply { inSampleSize = ExercisePhotos.sampleSize(bounds.outWidth, bounds.outHeight, MAX_SIDE_PX) }
        val decoded = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (e: OutOfMemoryError) {
            null
        } ?: return null
        // A decoded bitmap always has a size.
        val (width, height) = targetSize(decoded.width, decoded.height) ?: (decoded.width to decoded.height)
        val scaled = if (width != decoded.width || height != decoded.height) Bitmap.createScaledBitmap(decoded, width, height, true) else decoded
        val name = PREFIX + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().take(8) + ".jpg"
        val tmp = File(dir, "$name.tmp")
        val written = try {
            FileOutputStream(tmp).use { scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        } catch (e: IOException) {
            false
        } finally {
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
        if (!written || tmp.length() == 0L) {
            tmp.delete()
            return null
        }
        val target = file(name)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        return name
    }

    fun delete(name: String?) {
        if (name != null && valid(name)) file(name).delete()
    }

    /**
     * Follows the products until cancelled: the photo of a product deleted (here or by the cloud
     * backup) or given another photo is deleted (its file name is never reused).
     */
    suspend fun deleteDropped(products: Flow<List<CustomFood>>) {
        var before = emptySet<String>()
        products.collect { foods ->
            val now = foods.mapNotNullTo(HashSet()) { it.image }
            (before - now).forEach(::delete)
            before = now
        }
    }

    /** Deletes the photos no product uses, except files written in the last [graceMs] (a download finishing now). */
    fun deleteUnused(used: Set<String>, graceMs: Long, now: Long = System.currentTimeMillis()) {
        val cutoff = now - graceMs
        dir.listFiles()?.forEach { f -> if (f.isFile && f.name !in used && f.lastModified() < cutoff) f.delete() }
    }

    /** A bitmap no larger than [maxPx] on its long side, for the UI. */
    fun loadBitmap(name: String, maxPx: Int): Bitmap? {
        if (!exists(name)) return null
        val f = file(name)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        val opts = BitmapFactory.Options().apply { inSampleSize = ExercisePhotos.sampleSize(bounds.outWidth, bounds.outHeight, maxPx) }
        return BitmapFactory.decodeFile(f.path, opts)
    }

    private fun valid(name: String) = name.isNotEmpty() && !name.contains('/') && !name.contains('\\') && name != "." && name != ".."

    companion object {
        const val DIR = "product_photos"
        private const val PREFIX = "off_"
        const val MAX_SIDE_PX = 400
        private const val QUALITY = 85

        /**
         * The size a photo of [width] × [height] is stored at: as it is, or scaled down to [maxSide] on
         * its long side (never below 1 px); null when it has no size (not an image).
         */
        fun targetSize(width: Int, height: Int, maxSide: Int = MAX_SIDE_PX): Pair<Int, Int>? {
            if (width <= 0 || height <= 0) return null
            val long = max(width, height)
            if (long <= maxSide) return width to height
            val scale = maxSide.toDouble() / long
            return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
        }
    }
}
