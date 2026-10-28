package com.infinifolder.preview

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Shared safety limits and bitmap construction helpers for pure-Kotlin image decoders.
 */
internal object ImageDecodeUtils {
    const val MAX_DIMENSION = 40_000
    const val MAX_PIXELS = 160_000_000L

    data class DecodedPixels(val width: Int, val height: Int, val pixels: IntArray)

    @Volatile
    var lastDecodedPixelsForTest: DecodedPixels? = null

    fun isValidDimensions(width: Int, height: Int): Boolean {
        return width in 1..MAX_DIMENSION &&
            height in 1..MAX_DIMENSION &&
            width.toLong() * height.toLong() <= MAX_PIXELS
    }

    fun createScaledBitmap(pixels: IntArray, width: Int, height: Int, targetPx: Int): Bitmap? {
        if (!isValidDimensions(width, height) || pixels.size < width * height) return null
        lastDecodedPixelsForTest = DecodedPixels(width, height, pixels)
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888) ?: return null
        val maxSide = max(width, height)
        if (targetPx <= 0 || maxSide <= targetPx) return bitmap

        val scale = targetPx.toFloat() / maxSide.toFloat()
        val dstW = (width * scale).roundToInt().coerceAtLeast(1)
        val dstH = (height * scale).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, dstW, dstH, true) ?: return bitmap
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    fun u8(bytes: ByteArray, offset: Int): Int = bytes[offset].toInt() and 0xFF

    fun u16le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    fun s16le(bytes: ByteArray, offset: Int): Int = u16le(bytes, offset).toShort().toInt()

    fun u16be(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    fun s16be(bytes: ByteArray, offset: Int): Int = u16be(bytes, offset).toShort().toInt()

    fun u32le(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFFL) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFFL) shl 24)

    fun s32le(bytes: ByteArray, offset: Int): Int = u32le(bytes, offset).toInt()

    fun u32be(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFFL) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 8) or
            (bytes[offset + 3].toLong() and 0xFFL)

    fun s32be(bytes: ByteArray, offset: Int): Int = u32be(bytes, offset).toInt()

    fun packArgb(a: Int, r: Int, g: Int, b: Int): Int =
        ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    /**
     * Decodes Apple/TIFF/ILBM PackBits RLE bytes into [expectedLen] uncompressed bytes.
     * Returns pair of (uncompressed bytes, next source offset).
     */
    fun decodePackBits(src: ByteArray, startOffset: Int, expectedLen: Int, maxSrcOffset: Int = src.size): Pair<ByteArray, Int> {
        val out = ByteArray(expectedLen)
        var dst = 0
        var pos = startOffset
        val limit = maxSrcOffset.coerceAtMost(src.size)
        while (dst < expectedLen && pos < limit) {
            val n = src[pos++].toInt() // signed byte -128..127
            when {
                n >= 0 -> {
                    val count = (n + 1).coerceAtMost(expectedLen - dst).coerceAtMost(limit - pos)
                    System.arraycopy(src, pos, out, dst, count)
                    pos += count
                    dst += count
                }
                n != -128 -> {
                    val count = (1 - n).coerceAtMost(expectedLen - dst)
                    if (pos >= limit) break
                    val b = src[pos++]
                    for (i in 0 until count) {
                        out[dst++] = b
                    }
                }
            }
        }
        return out to pos
    }
}
