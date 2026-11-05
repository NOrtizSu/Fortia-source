package com.enjoyyourtime.globalfitness.food.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import com.enjoyyourtime.globalfitness.training.data.ExercisePhotos
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Meal photos: small JPEG files (at most [MAX_SIDE_PX] on the long side) in the app's private
 * storage (`files/meal_photos`). They stay on this phone: never uploaded to the cloud backup and
 * left out of Android's backup (backup_rules.xml), deleted with their entry. Blocking (call on IO).
 */
class MealPhotos(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    // Created on first use (the module is built during Application.onCreate, on the main thread).
    private val dir by lazy { File(appContext.filesDir, DIR).apply { mkdirs() } }
    private val cameraDir by lazy { File(appContext.cacheDir, DIR) }

    /**
     * Where the camera app writes a new photo (shared through the FileProvider path `meal_photos/` of
     * the cache; its folder is created when the camera is launched).
     */
    val cameraTarget: File get() = File(cameraDir, CAMERA_FILE)

    fun file(name: String): File = File(dir, name)

    /** The picked or captured image, upright, at most [maxSide] px on its long side; null if it cannot be read. */
    fun decode(uri: Uri, maxSide: Int): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder applies the EXIF orientation itself.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                val long = max(info.size.width, info.size.height)
                if (long > maxSide) {
                    val scale = maxSide.toFloat() / long
                    decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1), (info.size.height * scale).roundToInt().coerceAtLeast(1))
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            decodeLegacy(uri, maxSide)
        }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    /**
     * Stores [bitmap] (scaled down to [MAX_SIDE_PX] if needed) as a new JPEG; returns its file name.
     * Throws an IOException when it could not be written (no file is left behind).
     */
    fun store(bitmap: Bitmap): String {
        val long = max(bitmap.width, bitmap.height)
        val scaled = if (long > MAX_SIDE_PX) {
            val scale = MAX_SIDE_PX.toFloat() / long
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt().coerceAtLeast(1), (bitmap.height * scale).roundToInt().coerceAtLeast(1), true)
        } else {
            bitmap
        }
        val name = "meal_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().take(8) + ".jpg"
        val tmp = File(dir, "$name.tmp")
        val written = try {
            FileOutputStream(tmp).use { scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
        if (!written || tmp.length() == 0L) {
            tmp.delete()
            throw IOException("Could not write the meal photo")
        }
        val target = file(name)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        return name
    }

    fun delete(name: String?) {
        if (name != null && name.isNotEmpty() && !name.contains('/')) file(name).delete()
    }

    /** Deletes photos no entry uses, except files written in the last [graceMs] (a photo being logged now). */
    fun deleteUnused(used: Set<String>, graceMs: Long) {
        val cutoff = System.currentTimeMillis() - graceMs
        dir.listFiles()?.forEach { f -> if (f.isFile && f.name !in used && f.lastModified() < cutoff) f.delete() }
    }

    /** A bitmap no larger than [maxPx] on its long side, for the UI. */
    fun loadBitmap(name: String, maxPx: Int): Bitmap? {
        val f = file(name)
        if (!f.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        val opts = BitmapFactory.Options().apply { inSampleSize = ExercisePhotos.sampleSize(bounds.outWidth, bounds.outHeight, maxPx) }
        return BitmapFactory.decodeFile(f.path, opts)
    }

    private fun decodeLegacy(uri: Uri, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val opts = BitmapFactory.Options().apply { inSampleSize = ExercisePhotos.sampleSize(bounds.outWidth, bounds.outHeight, maxSide) }
        val raw = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val degrees = resolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        val long = max(raw.width, raw.height)
        val scale = if (long > maxSide) maxSide.toFloat() / long else 1f
        if (degrees == 0f && scale == 1f) return raw
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postRotate(degrees)
        }
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true).also { if (it !== raw) raw.recycle() }
    }

    companion object {
        const val DIR = "meal_photos"
        private const val CAMERA_FILE = "camera.jpg"
        const val MAX_SIDE_PX = 640
        private const val QUALITY = 82

        /** Barcodes need more pixels than the stored photo (thin bars, taken from a distance). */
        const val BARCODE_SIDE_PX = 1600
    }
}
