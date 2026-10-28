package com.infinifolder.preview

import android.graphics.Bitmap
import com.infinifolder.preview.ImageDecodeUtils.createScaledBitmap
import com.infinifolder.preview.ImageDecodeUtils.isValidDimensions
import com.infinifolder.preview.ImageDecodeUtils.packArgb
import com.infinifolder.preview.ImageDecodeUtils.u16be
import com.infinifolder.preview.ImageDecodeUtils.u16le
import com.infinifolder.preview.ImageDecodeUtils.u32be
import com.infinifolder.preview.ImageDecodeUtils.u32le
import com.infinifolder.preview.ImageDecodeUtils.u8
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Decodes X Window System raster formats:
 * - X BitMap (.xbm)
 * - X PixMap (.xpm)
 * - X Window Dump (.xwd)
 */
object X11ImageDecoder {

    val extensions: Set<String> = setOf("xbm", "xpm", "xwd")

    fun decode(input: InputStream, targetPx: Int): Bitmap? = decode(input.readBytes(), targetPx)

    fun decode(bytes: ByteArray, targetPx: Int): Bitmap? {
        if (bytes.size >= 100 && u32be(bytes, 4) == 7L && u32be(bytes, 0) in 100L..65536L) {
            return decodeXwd(bytes, targetPx)
        }
        val text = String(bytes, 0, bytes.size.coerceAtMost(2 * 1024 * 1024), StandardCharsets.ISO_8859_1)
        if (text.contains("/* XPM") || (text.contains("static") && text.contains("char") && text.contains("*"))) {
            val bmp = decodeXpm(text, targetPx)
            if (bmp != null || ImageDecodeUtils.lastDecodedPixelsForTest != null) return bmp
        }
        if (text.contains("#define") && text.contains("_width") && text.contains("_height")) {
            return decodeXbm(text, targetPx)
        }
        return null
    }

    fun decodeXbm(text: String, targetPx: Int): Bitmap? {
        val widthMatch = Regex("""#define\s+\S+_width\s+(\d+)""").find(text) ?: return null
        val heightMatch = Regex("""#define\s+\S+_height\s+(\d+)""").find(text) ?: return null
        val width = widthMatch.groupValues[1].toIntOrNull() ?: return null
        val height = heightMatch.groupValues[1].toIntOrNull() ?: return null
        if (!isValidDimensions(width, height)) return null

        val braceStart = text.indexOf('{')
        val braceEnd = text.lastIndexOf('}')
        if (braceStart < 0 || braceEnd <= braceStart) return null
        val body = text.substring(braceStart + 1, braceEnd)

        val rawBytes = ArrayList<Int>((width + 7) / 8 * height)
        val tokenRegex = Regex("""0[xX][0-9a-fA-F]+|-?\d+""")
        for (match in tokenRegex.findAll(body)) {
            val tok = match.value
            val v = if (tok.startsWith("0x", true)) {
                tok.substring(2).toIntOrNull(16) ?: 0
            } else {
                tok.toIntOrNull() ?: 0
            }
            rawBytes.add(v and 0xFF)
        }

        val rowBytes = (width + 7) / 8
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val rowBase = y * rowBytes
            for (x in 0 until width) {
                val bIdx = rowBase + (x ushr 3)
                val byteVal = if (bIdx < rawBytes.size) rawBytes[bIdx] else 0
                // XBM stores bits LSB-first within each byte; 1 = foreground (black), 0 = background (white)
                val bit = (byteVal ushr (x and 7)) and 1
                val v = if (bit == 1) 0 else 255
                pixels[y * width + x] = packArgb(255, v, v, v)
            }
        }

        return createScaledBitmap(pixels, width, height, targetPx)
    }

    fun decodeXpm(text: String, targetPx: Int): Bitmap? {
        // Extract all double-quoted strings inside the C array
        val strings = ArrayList<String>()
        var i = 0
        val n = text.length
        while (i < n) {
            if (text[i] == '/' && i + 1 < n && text[i + 1] == '*') {
                val endComment = text.indexOf("*/", i + 2)
                i = if (endComment >= 0) endComment + 2 else n
                continue
            }
            if (text[i] == '"') {
                val sb = StringBuilder()
                i++
                while (i < n && text[i] != '"') {
                    if (text[i] == '\\' && i + 1 < n) {
                        i++
                    }
                    sb.append(text[i])
                    i++
                }
                i++ // skip closing quote
                strings.add(sb.toString())
            } else {
                i++
            }
        }

        if (strings.size < 2) return null
        val headerParts = strings[0].trim().split(Regex("\\s+"))
        if (headerParts.size < 4) return null
        val width = headerParts[0].toIntOrNull() ?: return null
        val height = headerParts[1].toIntOrNull() ?: return null
        val numColors = headerParts[2].toIntOrNull() ?: return null
        val cpp = headerParts[3].toIntOrNull() ?: return null
        if (!isValidDimensions(width, height) || numColors <= 0 || cpp !in 1..8) return null
        if (strings.size < 1 + numColors + height) return null

        val colorMap = HashMap<String, Int>(numColors * 2)
        for (cIdx in 0 until numColors) {
            val line = strings[1 + cIdx]
            if (line.length < cpp) continue
            val symbol = line.substring(0, cpp)
            val rest = line.substring(cpp).trim()
            val colorVal = parseXpmColorEntry(rest)
            colorMap[symbol] = colorVal
        }

        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val rowStr = strings[1 + numColors + y]
            for (x in 0 until width) {
                val start = x * cpp
                val sym = if (start + cpp <= rowStr.length) rowStr.substring(start, start + cpp) else ""
                pixels[y * width + x] = colorMap[sym] ?: 0xFF000000.toInt()
            }
        }

        return createScaledBitmap(pixels, width, height, targetPx)
    }

    private fun parseXpmColorEntry(rest: String): Int {
        val tokens = rest.split(Regex("\\s+"))
        var i = 0
        var chosenColor: String? = null
        while (i + 1 < tokens.size) {
            val key = tokens[i]
            val value = tokens[i + 1]
            if (key == "c" || key == "g" || key == "m") {
                chosenColor = value
                if (key == "c") break
            }
            i += 2
        }
        val colorSpec = (chosenColor ?: tokens.lastOrNull() ?: "black").lowercase(Locale.ROOT)
        if (colorSpec == "none") return 0x00000000
        if (colorSpec.startsWith("#")) {
            val hex = colorSpec.substring(1)
            return when (hex.length) {
                3 -> {
                    val r = (hex.substring(0, 1).toIntOrNull(16) ?: 0) * 17
                    val g = (hex.substring(1, 2).toIntOrNull(16) ?: 0) * 17
                    val b = (hex.substring(2, 3).toIntOrNull(16) ?: 0) * 17
                    packArgb(255, r, g, b)
                }
                6 -> {
                    val rgb = hex.toIntOrNull(16) ?: 0
                    0xFF000000.toInt() or rgb
                }
                12 -> { // 16-bit per channel #RRRRGGGGBBBB
                    val r = hex.substring(0, 2).toIntOrNull(16) ?: 0
                    val g = hex.substring(4, 6).toIntOrNull(16) ?: 0
                    val b = hex.substring(8, 10).toIntOrNull(16) ?: 0
                    packArgb(255, r, g, b)
                }
                else -> 0xFF000000.toInt()
            }
        }
        return NAMED_X11_COLORS[colorSpec] ?: 0xFF000000.toInt()
    }

    fun decodeXwd(bytes: ByteArray, targetPx: Int): Bitmap? {
        if (bytes.size < 100) return null
        val headerSize = u32be(bytes, 0).toInt()
        val version = u32be(bytes, 4).toInt()
        if (version != 7 || headerSize < 100 || headerSize > bytes.size) return null

        val pixmapDepth = u32be(bytes, 12).toInt()
        val width = u32be(bytes, 16).toInt()
        val height = u32be(bytes, 20).toInt()
        val byteOrder = u32be(bytes, 28).toInt() // 0 = LSBFirst, 1 = MSBFirst
        val bitsPerPixel = u32be(bytes, 44).toInt()
        val bytesPerLine = u32be(bytes, 48).toInt()
        val redMask = u32be(bytes, 56)
        val greenMask = u32be(bytes, 60)
        val blueMask = u32be(bytes, 64)
        val ncolors = u32be(bytes, 76).toInt()

        if (!isValidDimensions(width, height) || bytesPerLine <= 0) return null
        val colorTableOffset = headerSize
        val colorTableBytes = ncolors.coerceIn(0, 65536) * 12
        val dataOffset = colorTableOffset + colorTableBytes
        if (dataOffset > bytes.size) return null

        val palette = IntArray(ncolors.coerceAtLeast(256))
        for (c in 0 until ncolors.coerceAtMost(65536)) {
            val cOff = colorTableOffset + c * 12
            if (cOff + 12 > bytes.size) break
            val pixelIdx = u32be(bytes, cOff).toInt()
            val r = u16be(bytes, cOff + 4) ushr 8
            val g = u16be(bytes, cOff + 6) ushr 8
            val b = u16be(bytes, cOff + 8) ushr 8
            val idx = if (pixelIdx in palette.indices) pixelIdx else c
            if (idx in palette.indices) {
                palette[idx] = packArgb(255, r, g, b)
            }
        }

        val pixels = IntArray(width * height)
        val rShift = java.lang.Long.numberOfTrailingZeros(redMask).takeIf { it < 64 } ?: 16
        val gShift = java.lang.Long.numberOfTrailingZeros(greenMask).takeIf { it < 64 } ?: 8
        val bShift = java.lang.Long.numberOfTrailingZeros(blueMask).takeIf { it < 64 } ?: 0

        for (y in 0 until height) {
            val rowStart = dataOffset + y * bytesPerLine
            if (rowStart >= bytes.size) break
            for (x in 0 until width) {
                val color = when (bitsPerPixel) {
                    32 -> {
                        val p = rowStart + x * 4
                        if (p + 4 > bytes.size) break
                        val raw = if (byteOrder == 1) u32be(bytes, p) else u32le(bytes, p)
                        val r = ((raw and redMask) ushr rShift).toInt() and 0xFF
                        val g = ((raw and greenMask) ushr gShift).toInt() and 0xFF
                        val b = ((raw and blueMask) ushr bShift).toInt() and 0xFF
                        packArgb(255, r, g, b)
                    }
                    24 -> {
                        val p = rowStart + x * 3
                        if (p + 3 > bytes.size) break
                        if (byteOrder == 1) {
                            packArgb(255, u8(bytes, p), u8(bytes, p + 1), u8(bytes, p + 2))
                        } else {
                            packArgb(255, u8(bytes, p + 2), u8(bytes, p + 1), u8(bytes, p))
                        }
                    }
                    16 -> {
                        val p = rowStart + x * 2
                        if (p + 2 > bytes.size) break
                        val raw = if (byteOrder == 1) u16be(bytes, p) else u16le(bytes, p)
                        if (pixmapDepth == 15) {
                            val r = ((raw ushr 10) and 0x1F) * 255 / 31
                            val g = ((raw ushr 5) and 0x1F) * 255 / 31
                            val b = (raw and 0x1F) * 255 / 31
                            packArgb(255, r, g, b)
                        } else {
                            val r = ((raw ushr 11) and 0x1F) * 255 / 31
                            val g = ((raw ushr 5) and 0x3F) * 255 / 63
                            val b = (raw and 0x1F) * 255 / 31
                            packArgb(255, r, g, b)
                        }
                    }
                    8 -> {
                        val p = rowStart + x
                        if (p >= bytes.size) break
                        val idx = u8(bytes, p)
                        if (ncolors > 0 && idx < palette.size) palette[idx] else packArgb(255, idx, idx, idx)
                    }
                    1 -> {
                        val p = rowStart + (x ushr 3)
                        if (p >= bytes.size) break
                        val bit = if (byteOrder == 1) {
                            (u8(bytes, p) ushr (7 - (x and 7))) and 1
                        } else {
                            (u8(bytes, p) ushr (x and 7)) and 1
                        }
                        if (ncolors >= 2) palette[bit] else {
                            val v = if (bit == 1) 255 else 0
                            packArgb(255, v, v, v)
                        }
                    }
                    else -> 0xFF000000.toInt()
                }
                pixels[y * width + x] = color
            }
        }

        return createScaledBitmap(pixels, width, height, targetPx)
    }

    private val NAMED_X11_COLORS = mapOf(
        "black" to 0xFF000000.toInt(),
        "white" to 0xFFFFFFFF.toInt(),
        "red" to 0xFFFF0000.toInt(),
        "green" to 0xFF00FF00.toInt(),
        "blue" to 0xFF0000FF.toInt(),
        "yellow" to 0xFFFFFF00.toInt(),
        "cyan" to 0xFF00FFFF.toInt(),
        "magenta" to 0xFFFF00FF.toInt(),
        "gray" to 0xFF808080.toInt(),
        "grey" to 0xFF808080.toInt(),
        "darkgray" to 0xFFA9A9A9.toInt(),
        "lightgray" to 0xFFD3D3D3.toInt(),
        "orange" to 0xFFFFA500.toInt(),
        "brown" to 0xFFA52A2A.toInt()
    )
}
