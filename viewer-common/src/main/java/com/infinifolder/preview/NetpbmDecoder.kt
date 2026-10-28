package com.infinifolder.preview

import android.graphics.Bitmap
import com.infinifolder.preview.ImageDecodeUtils.createScaledBitmap
import com.infinifolder.preview.ImageDecodeUtils.isValidDimensions
import com.infinifolder.preview.ImageDecodeUtils.packArgb
import com.infinifolder.preview.ImageDecodeUtils.u16be
import com.infinifolder.preview.ImageDecodeUtils.u32be
import com.infinifolder.preview.ImageDecodeUtils.u32le
import com.infinifolder.preview.ImageDecodeUtils.u8
import java.io.InputStream
import java.nio.charset.StandardCharsets
import kotlin.math.pow

/**
 * Decodes the Netpbm family (.pbm, .pgm, .ppm, .pnm, .pam) and Portable FloatMap (.pfm).
 */
object NetpbmDecoder {

    val extensions: Set<String> = setOf("pbm", "pgm", "ppm", "pnm", "pam", "pfm")

    fun decode(input: InputStream, targetPx: Int): Bitmap? = decode(input.readBytes(), targetPx)

    fun decode(bytes: ByteArray, targetPx: Int): Bitmap? {
        if (bytes.size < 3 || bytes[0] != 'P'.code.toByte()) return null
        return when (val second = bytes[1].toInt().toChar()) {
            '1', '2', '3', '4', '5', '6' -> decodeClassicPnm(bytes, second - '0', targetPx)
            '7' -> decodePam(bytes, targetPx)
            'F', 'f' -> decodePfm(bytes, second == 'F', targetPx)
            else -> null
        }
    }

    private class HeaderScanner(private val bytes: ByteArray, startPos: Int) {
        var pos: Int = startPos

        fun skipWhitespaceAndComments() {
            while (pos < bytes.size) {
                val b = bytes[pos].toInt() and 0xFF
                if (b == '#'.code) {
                    while (pos < bytes.size && bytes[pos].toInt() != '\n'.code && bytes[pos].toInt() != '\r'.code) {
                        pos++
                    }
                } else if (b == ' '.code || b == '\t'.code || b == '\n'.code || b == '\r'.code) {
                    pos++
                } else {
                    break
                }
            }
        }

        fun nextToken(): String? {
            skipWhitespaceAndComments()
            if (pos >= bytes.size) return null
            val start = pos
            while (pos < bytes.size) {
                val b = bytes[pos].toInt() and 0xFF
                if (b == ' '.code || b == '\t'.code || b == '\n'.code || b == '\r'.code || b == '#'.code) {
                    break
                }
                pos++
            }
            return String(bytes, start, pos - start, StandardCharsets.US_ASCII)
        }

        fun nextInt(): Int? = nextToken()?.toIntOrNull()

        fun consumeSingleWhitespaceAfterHeader() {
            if (pos < bytes.size) {
                val b = bytes[pos].toInt() and 0xFF
                if (b == '\r'.code && pos + 1 < bytes.size && bytes[pos + 1].toInt() == '\n'.code) {
                    pos += 2
                } else if (b == ' '.code || b == '\t'.code || b == '\n'.code || b == '\r'.code) {
                    pos++
                }
            }
        }
    }

    private fun decodeClassicPnm(bytes: ByteArray, format: Int, targetPx: Int): Bitmap? {
        val scanner = HeaderScanner(bytes, 2)
        val width = scanner.nextInt() ?: return null
        val height = scanner.nextInt() ?: return null
        if (!isValidDimensions(width, height)) return null
        val maxVal = if (format == 1 || format == 4) 1 else (scanner.nextInt() ?: return null)
        if (maxVal <= 0 || maxVal > 65535) return null
        scanner.consumeSingleWhitespaceAfterHeader()

        val totalPixels = width * height
        val pixels = IntArray(totalPixels)
        var pos = scanner.pos

        when (format) {
            1 -> { // ASCII PBM (1 = black, 0 = white)
                var idx = 0
                while (idx < totalPixels && scanner.pos < bytes.size) {
                    val tok = scanner.nextToken() ?: break
                    for (ch in tok) {
                        if (idx >= totalPixels) break
                        if (ch == '0' || ch == '1') {
                            val v = if (ch == '1') 0 else 255
                            pixels[idx++] = packArgb(255, v, v, v)
                        }
                    }
                }
            }
            2 -> { // ASCII PGM
                for (i in 0 until totalPixels) {
                    val raw = scanner.nextInt() ?: break
                    val v = (raw * 255 / maxVal).coerceIn(0, 255)
                    pixels[i] = packArgb(255, v, v, v)
                }
            }
            3 -> { // ASCII PPM
                for (i in 0 until totalPixels) {
                    val r = ((scanner.nextInt() ?: break) * 255 / maxVal).coerceIn(0, 255)
                    val g = ((scanner.nextInt() ?: break) * 255 / maxVal).coerceIn(0, 255)
                    val b = ((scanner.nextInt() ?: break) * 255 / maxVal).coerceIn(0, 255)
                    pixels[i] = packArgb(255, r, g, b)
                }
            }
            4 -> { // Binary PBM (1-bit packed per row, 1 = black, 0 = white)
                val rowBytes = (width + 7) / 8
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val byteIdx = pos + (x ushr 3)
                        if (byteIdx >= bytes.size) break
                        val bit = (u8(bytes, byteIdx) ushr (7 - (x and 7))) and 1
                        val v = if (bit == 1) 0 else 255
                        pixels[y * width + x] = packArgb(255, v, v, v)
                    }
                    pos += rowBytes
                }
            }
            5 -> { // Binary PGM
                val twoByte = maxVal > 255
                for (i in 0 until totalPixels) {
                    val raw = if (twoByte) {
                        if (pos + 2 > bytes.size) break
                        val v = u16be(bytes, pos); pos += 2; v
                    } else {
                        if (pos >= bytes.size) break
                        u8(bytes, pos++)
                    }
                    val v = (raw * 255 / maxVal).coerceIn(0, 255)
                    pixels[i] = packArgb(255, v, v, v)
                }
            }
            6 -> { // Binary PPM
                val twoByte = maxVal > 255
                for (i in 0 until totalPixels) {
                    if (twoByte) {
                        if (pos + 6 > bytes.size) break
                        val r = (u16be(bytes, pos) * 255 / maxVal).coerceIn(0, 255)
                        val g = (u16be(bytes, pos + 2) * 255 / maxVal).coerceIn(0, 255)
                        val b = (u16be(bytes, pos + 4) * 255 / maxVal).coerceIn(0, 255)
                        pos += 6
                        pixels[i] = packArgb(255, r, g, b)
                    } else {
                        if (pos + 3 > bytes.size) break
                        val r = (u8(bytes, pos) * 255 / maxVal).coerceIn(0, 255)
                        val g = (u8(bytes, pos + 1) * 255 / maxVal).coerceIn(0, 255)
                        val b = (u8(bytes, pos + 2) * 255 / maxVal).coerceIn(0, 255)
                        pos += 3
                        pixels[i] = packArgb(255, r, g, b)
                    }
                }
            }
        }

        return createScaledBitmap(pixels, width, height, targetPx)
    }

    private fun decodePam(bytes: ByteArray, targetPx: Int): Bitmap? {
        var pos = 2
        var width = 0
        var height = 0
        var depth = 0
        var maxVal = 255

        while (pos < bytes.size) {
            val lineStart = pos
            while (pos < bytes.size && bytes[pos].toInt() != '\n'.code) pos++
            val line = String(bytes, lineStart, pos - lineStart, StandardCharsets.US_ASCII).trim()
            if (pos < bytes.size && bytes[pos].toInt() == '\n'.code) pos++
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line == "ENDHDR") break
            val parts = line.split(Regex("\\s+"), limit = 2)
            if (parts.size == 2) {
                when (parts[0]) {
                    "WIDTH" -> width = parts[1].toIntOrNull() ?: 0
                    "HEIGHT" -> height = parts[1].toIntOrNull() ?: 0
                    "DEPTH" -> depth = parts[1].toIntOrNull() ?: 0
                    "MAXVAL" -> maxVal = parts[1].toIntOrNull() ?: 255
                }
            }
        }

        if (!isValidDimensions(width, height) || depth !in 1..4 || maxVal !in 1..65535) return null
        val totalPixels = width * height
        val pixels = IntArray(totalPixels)
        val twoByte = maxVal > 255

        fun nextSample(): Int {
            return if (twoByte) {
                if (pos + 2 > bytes.size) 0 else {
                    val v = u16be(bytes, pos); pos += 2; (v * 255 / maxVal).coerceIn(0, 255)
                }
            } else {
                if (pos >= bytes.size) 0 else (u8(bytes, pos++) * 255 / maxVal).coerceIn(0, 255)
            }
        }

        for (i in 0 until totalPixels) {
            when (depth) {
                1 -> {
                    val v = nextSample()
                    pixels[i] = packArgb(255, v, v, v)
                }
                2 -> {
                    val v = nextSample()
                    val a = nextSample()
                    pixels[i] = packArgb(a, v, v, v)
                }
                3 -> {
                    val r = nextSample()
                    val g = nextSample()
                    val b = nextSample()
                    pixels[i] = packArgb(255, r, g, b)
                }
                4 -> {
                    val r = nextSample()
                    val g = nextSample()
                    val b = nextSample()
                    val a = nextSample()
                    pixels[i] = packArgb(a, r, g, b)
                }
            }
        }

        return createScaledBitmap(pixels, width, height, targetPx)
    }

    private fun decodePfm(bytes: ByteArray, isColor: Boolean, targetPx: Int): Bitmap? {
        val scanner = HeaderScanner(bytes, 2)
        val width = scanner.nextInt() ?: return null
        val height = scanner.nextInt() ?: return null
        val scaleStr = scanner.nextToken() ?: return null
        val scale = scaleStr.toFloatOrNull() ?: return null
        if (!isValidDimensions(width, height) || scale == 0f) return null
        scanner.consumeSingleWhitespaceAfterHeader()

        val littleEndian = scale < 0f
        val channels = if (isColor) 3 else 1
        val totalPixels = width * height
        val pixels = IntArray(totalPixels)
        var pos = scanner.pos

        fun readFloat(): Float {
            if (pos + 4 > bytes.size) return 0f
            val bits = if (littleEndian) u32le(bytes, pos).toInt() else u32be(bytes, pos).toInt()
            pos += 4
            val f = Float.fromBits(bits)
            return if (f.isNaN() || f.isInfinite()) 0f else f
        }

        fun toneMap(v: Float): Int {
            val posVal = v.coerceAtLeast(0f)
            val mapped = if (posVal > 1f) posVal / (1f + posVal) else posVal
            return (mapped.pow(1f / 2.2f) * 255f).toInt().coerceIn(0, 255)
        }

        // PFM stores scanlines bottom-to-top
        for (row in height - 1 downTo 0) {
            val rowOffset = row * width
            for (x in 0 until width) {
                if (channels == 3) {
                    val r = toneMap(readFloat())
                    val g = toneMap(readFloat())
                    val b = toneMap(readFloat())
                    pixels[rowOffset + x] = packArgb(255, r, g, b)
                } else {
                    val v = toneMap(readFloat())
                    pixels[rowOffset + x] = packArgb(255, v, v, v)
                }
            }
        }

        return createScaledBitmap(pixels, width, height, targetPx)
    }
}
