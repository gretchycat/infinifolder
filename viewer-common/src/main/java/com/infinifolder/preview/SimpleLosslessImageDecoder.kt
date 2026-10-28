package com.infinifolder.preview

import android.graphics.Bitmap
import com.infinifolder.preview.ImageDecodeUtils.createScaledBitmap
import com.infinifolder.preview.ImageDecodeUtils.isValidDimensions
import com.infinifolder.preview.ImageDecodeUtils.packArgb
import com.infinifolder.preview.ImageDecodeUtils.u16be
import com.infinifolder.preview.ImageDecodeUtils.u32be
import com.infinifolder.preview.ImageDecodeUtils.u8
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * Decodes Quite OK Image (.qoi) and suckless farbfeld (.ff) uncompressed/RLE lossless images.
 */
object SimpleLosslessImageDecoder {

    val qoiExtensions: Set<String> = setOf("qoi")
    val farbfeldExtensions: Set<String> = setOf("ff")

    fun decode(bytes: ByteArray, targetPx: Int): Bitmap? {
        if (bytes.size >= 14 && String(bytes, 0, 4, StandardCharsets.US_ASCII) == "qoif") {
            return decodeQoi(bytes, targetPx)
        }
        if (bytes.size >= 16 && String(bytes, 0, 8, StandardCharsets.US_ASCII) == "farbfeld") {
            return decodeFarbfeld(bytes, targetPx)
        }
        return null
    }

    fun decode(input: InputStream, targetPx: Int): Bitmap? = decode(input.readBytes(), targetPx)

    fun decodeQoi(bytes: ByteArray, targetPx: Int): Bitmap? {
        if (bytes.size < 22) return null
        if (String(bytes, 0, 4, StandardCharsets.US_ASCII) != "qoif") return null

        val width = u32be(bytes, 4).toInt()
        val height = u32be(bytes, 8).toInt()
        val channels = u8(bytes, 12)
        if (channels != 3 && channels != 4) return null
        if (!isValidDimensions(width, height)) return null

        val totalPixels = width * height
        val pixels = IntArray(totalPixels)
        val index = IntArray(64)

        var r = 0
        var g = 0
        var b = 0
        var a = 255
        var run = 0
        var pos = 14
        val chunksEnd = bytes.size - 8

        for (pxPos in 0 until totalPixels) {
            if (run > 0) {
                run--
            } else if (pos < chunksEnd) {
                val b1 = u8(bytes, pos++)
                when {
                    b1 == 0xFE -> { // QOI_OP_RGB
                        if (pos + 3 > bytes.size) break
                        r = u8(bytes, pos)
                        g = u8(bytes, pos + 1)
                        b = u8(bytes, pos + 2)
                        pos += 3
                    }
                    b1 == 0xFF -> { // QOI_OP_RGBA
                        if (pos + 4 > bytes.size) break
                        r = u8(bytes, pos)
                        g = u8(bytes, pos + 1)
                        b = u8(bytes, pos + 2)
                        a = u8(bytes, pos + 3)
                        pos += 4
                    }
                    (b1 and 0xC0) == 0x00 -> { // QOI_OP_INDEX
                        val color = index[b1 and 0x3F]
                        a = (color ushr 24) and 0xFF
                        r = (color ushr 16) and 0xFF
                        g = (color ushr 8) and 0xFF
                        b = color and 0xFF
                    }
                    (b1 and 0xC0) == 0x40 -> { // QOI_OP_DIFF
                        r = (r + ((b1 ushr 4) and 0x03) - 2) and 0xFF
                        g = (g + ((b1 ushr 2) and 0x03) - 2) and 0xFF
                        b = (b + (b1 and 0x03) - 2) and 0xFF
                    }
                    (b1 and 0xC0) == 0x80 -> { // QOI_OP_LUMA
                        if (pos >= bytes.size) break
                        val b2 = u8(bytes, pos++)
                        val vg = (b1 and 0x3F) - 32
                        r = (r + vg - 8 + ((b2 ushr 4) and 0x0F)) and 0xFF
                        g = (g + vg) and 0xFF
                        b = (b + vg - 8 + (b2 and 0x0F)) and 0xFF
                    }
                    (b1 and 0xC0) == 0xC0 -> { // QOI_OP_RUN
                        run = b1 and 0x3F
                    }
                }
                val hash = (r * 3 + g * 5 + b * 7 + a * 11) and 0x3F
                index[hash] = packArgb(a, r, g, b)
            }
            pixels[pxPos] = packArgb(a, r, g, b)
        }

        return createScaledBitmap(pixels, width, height, targetPx)
    }

    fun decodeFarbfeld(bytes: ByteArray, targetPx: Int): Bitmap? {
        if (bytes.size < 16) return null
        if (String(bytes, 0, 8, StandardCharsets.US_ASCII) != "farbfeld") return null

        val width = u32be(bytes, 8).toInt()
        val height = u32be(bytes, 12).toInt()
        if (!isValidDimensions(width, height)) return null

        val totalPixels = width * height
        if (bytes.size < 16L + totalPixels.toLong() * 8L) return null

        val pixels = IntArray(totalPixels)
        var pos = 16
        for (i in 0 until totalPixels) {
            val r = u16be(bytes, pos) ushr 8
            val g = u16be(bytes, pos + 2) ushr 8
            val b = u16be(bytes, pos + 4) ushr 8
            val a = u16be(bytes, pos + 6) ushr 8
            pixels[i] = packArgb(a, r, g, b)
            pos += 8
        }

        return createScaledBitmap(pixels, width, height, targetPx)
    }
}
