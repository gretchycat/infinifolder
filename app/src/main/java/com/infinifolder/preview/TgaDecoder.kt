package com.infinifolder.preview

import android.graphics.Bitmap
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pure-Kotlin decoder for Truevision TGA images (.tga, .icb, .vda, .vst).
 * Supports uncompressed and RLE true-color (24-bit RGB, 32-bit ARGB) and grayscale (8-bit).
 */
object TgaDecoder {
    fun decode(stream: InputStream, targetPx: Int): Bitmap? {
        val bytes = stream.readBytes()
        if (bytes.size < 18) return null

        val idLength = bytes[0].toInt() and 0xFF
        val colorMapType = bytes[1].toInt() and 0xFF
        val imageType = bytes[2].toInt() and 0xFF

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val width = buf.getShort(12).toInt() and 0xFFFF
        val height = buf.getShort(14).toInt() and 0xFFFF
        val bpp = bytes[16].toInt() and 0xFF
        val descriptor = bytes[17].toInt() and 0xFF

        if (width <= 0 || height <= 0 || width > 40_000 || height > 40_000) return null
        if (width.toLong() * height.toLong() > 160_000_000L) return null

        val isTopDown = (descriptor and 0x20) != 0
        var offset = 18 + idLength

        // Skip color map specification if present
        if (colorMapType == 1) {
            val colorMapLength = buf.getShort(5).toInt() and 0xFFFF
            val colorMapEntrySize = (bytes[7].toInt() and 0xFF + 7) / 8
            offset += colorMapLength * colorMapEntrySize
        }
        if (offset > bytes.size) return null

        val totalPixels = width * height
        val pixels = IntArray(totalPixels)
        val bytesPerPixel = bpp / 8
        if (bytesPerPixel !in setOf(1, 3, 4)) return null

        when (imageType) {
            // Uncompressed True-Color (2) or Grayscale (3)
            2, 3 -> {
                val needed = offset + totalPixels * bytesPerPixel
                if (needed > bytes.size) return null
                var ptr = offset
                for (i in 0 until totalPixels) {
                    pixels[i] = readPixel(bytes, ptr, bytesPerPixel)
                    ptr += bytesPerPixel
                }
            }
            // RLE True-Color (10) or Grayscale (11)
            10, 11 -> {
                var pixelCount = 0
                var ptr = offset
                while (pixelCount < totalPixels && ptr < bytes.size) {
                    val packetHeader = bytes[ptr++].toInt() and 0xFF
                    val count = (packetHeader and 0x7F) + 1
                    if ((packetHeader and 0x80) != 0) {
                        // Run-length packet
                        if (ptr + bytesPerPixel > bytes.size) break
                        val color = readPixel(bytes, ptr, bytesPerPixel)
                        ptr += bytesPerPixel
                        val limit = minOf(pixelCount + count, totalPixels)
                        while (pixelCount < limit) {
                            pixels[pixelCount++] = color
                        }
                    } else {
                        // Raw packet
                        val limit = minOf(pixelCount + count, totalPixels)
                        while (pixelCount < limit && ptr + bytesPerPixel <= bytes.size) {
                            pixels[pixelCount++] = readPixel(bytes, ptr, bytesPerPixel)
                            ptr += bytesPerPixel
                        }
                    }
                }
                if (pixelCount < totalPixels) return null
            }
            else -> return null
        }

        // TGA is normally bottom-up unless bit 5 of descriptor is set
        val finalPixels = if (isTopDown) {
            pixels
        } else {
            val flipped = IntArray(totalPixels)
            for (y in 0 until height) {
                val srcY = height - 1 - y
                System.arraycopy(pixels, srcY * width, flipped, y * width, width)
            }
            flipped
        }

        val fullBitmap = Bitmap.createBitmap(finalPixels, width, height, Bitmap.Config.ARGB_8888)
        val maxDim = max(width, height)
        if (maxDim <= targetPx) return fullBitmap

        val scale = targetPx.toDouble() / maxDim
        val outW = (width * scale).roundToInt().coerceAtLeast(1)
        val outH = (height * scale).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(fullBitmap, outW, outH, true)
        if (scaled !== fullBitmap) fullBitmap.recycle()
        return scaled
    }

    private fun readPixel(bytes: ByteArray, ptr: Int, bpp: Int): Int {
        return when (bpp) {
            1 -> {
                // Grayscale
                val gray = bytes[ptr].toInt() and 0xFF
                (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
            }
            3 -> {
                // BGR
                val b = bytes[ptr].toInt() and 0xFF
                val g = bytes[ptr + 1].toInt() and 0xFF
                val r = bytes[ptr + 2].toInt() and 0xFF
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            4 -> {
                // BGRA
                val b = bytes[ptr].toInt() and 0xFF
                val g = bytes[ptr + 1].toInt() and 0xFF
                val r = bytes[ptr + 2].toInt() and 0xFF
                val a = bytes[ptr + 3].toInt() and 0xFF
                (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            else -> 0
        }
    }
}
