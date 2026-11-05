package com.enjoyyourtime.globalfitness.food.barcode

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.ReaderException
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.oned.UPCEReader

/**
 * Reads the product barcode (EAN-13, EAN-8, UPC-A, UPC-E) in a photo with ZXing, no live camera
 * preview: the photo is tried as it is and turned 90° (bars may run either way), with two
 * binarizers (uneven light, glossy packaging). Pure Java/Kotlin: [luminance] is one byte per pixel.
 */
object BarcodeReader {
    /** The formats of a photo aimed at a barcode. */
    val FORMATS = listOf(BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E)

    /**
     * On a meal photo (not aimed at a barcode) UPC-E is left out: its 6 digits are easily "found" in a
     * busy picture, and it is rare outside North America.
     */
    val MEAL_PHOTO_FORMATS = listOf(BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A)

    /** The digits of the barcode of one of [formats] in the [width]×[height] grey image, or null when none is found. */
    fun decode(luminance: ByteArray, width: Int, height: Int, formats: List<BarcodeFormat> = FORMATS): String? {
        require(luminance.size >= width * height)
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to formats,
            DecodeHintType.TRY_HARDER to true,
        )
        val reader = MultiFormatReader().apply { setHints(hints) }
        val sources = sequenceOf(GreySource(luminance, width, height), GreySource(rotate90(luminance, width, height), height, width))
        for (source in sources) {
            for (bitmap in sequenceOf(BinaryBitmap(HybridBinarizer(source)), BinaryBitmap(GlobalHistogramBinarizer(source)))) {
                try {
                    val result = reader.decodeWithState(bitmap)
                    return normalize(result.text, result.barcodeFormat)
                } catch (e: NotFoundException) {
                    // try the next way
                } catch (e: ReaderException) {
                    // checksum or format error: try the next way
                } finally {
                    reader.reset()
                }
            }
        }
        return null
    }

    /** Grey levels of ARGB pixels (ITU-R BT.601 luma). */
    fun luminanceOf(argb: IntArray, width: Int, height: Int): ByteArray {
        val out = ByteArray(width * height)
        for (i in 0 until width * height) {
            val p = argb[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            out[i] = ((r * 299 + g * 587 + b * 114) / 1000).toByte()
        }
        return out
    }

    /** UPC-E is looked up as its UPC-A form (what product databases store). Only digits pass. */
    fun normalize(text: String, format: BarcodeFormat): String? {
        if (text.isEmpty() || !text.all { it.isDigit() }) return null
        return if (format == BarcodeFormat.UPC_E && text.length == 8) UPCEReader.convertUPCEtoUPCA(text) else text
    }

    /**
     * One form per product: UPC-A (12 digits) and GTIN-14 with a leading 0 become the EAN-13 that
     * product databases store ("0" + UPC-A), so a cached product is found however it was read. EAN-8
     * and anything else stay as they are.
     */
    fun canonical(code: String): String {
        val digits = code.filter(Char::isDigit)
        return when {
            digits.length == 12 -> "0$digits"
            digits.length == 14 && digits.startsWith("0") -> digits.substring(1)
            else -> digits
        }
    }

    /** A plausible product barcode typed by hand: 8, 12 or 13 digits with a valid check digit. */
    fun isValidCode(code: String): Boolean {
        if (code.length !in setOf(8, 12, 13) || !code.all { it.isDigit() }) return false
        val digits = code.map { it - '0' }
        val body = digits.dropLast(1).reversed()
        val sum = body.withIndex().sumOf { (i, d) -> if (i % 2 == 0) d * 3 else d }
        return (10 - sum % 10) % 10 == digits.last()
    }

    private fun rotate90(src: ByteArray, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) out[x * h + (h - 1 - y)] = src[y * w + x]
        return out
    }

    private class GreySource(private val data: ByteArray, width: Int, height: Int) : LuminanceSource(width, height) {
        override fun getRow(y: Int, row: ByteArray?): ByteArray {
            val out = if (row == null || row.size < width) ByteArray(width) else row
            System.arraycopy(data, y * width, out, 0, width)
            return out
        }

        override fun getMatrix(): ByteArray = if (data.size == width * height) data else data.copyOf(width * height)
    }
}
