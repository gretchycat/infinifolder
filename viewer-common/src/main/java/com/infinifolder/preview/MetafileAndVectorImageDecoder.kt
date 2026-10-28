package com.infinifolder.preview

import android.graphics.Bitmap
import com.infinifolder.preview.ImageDecodeUtils.createScaledBitmap
import com.infinifolder.preview.ImageDecodeUtils.isValidDimensions
import com.infinifolder.preview.ImageDecodeUtils.packArgb
import com.infinifolder.preview.ImageDecodeUtils.s16le
import com.infinifolder.preview.ImageDecodeUtils.s32le
import com.infinifolder.preview.ImageDecodeUtils.u16le
import com.infinifolder.preview.ImageDecodeUtils.u32le
import com.infinifolder.preview.ImageDecodeUtils.u8
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

/**
 * Decodes Windows Metafiles (.wmf, .wmz), PostScript / EPS raster images (.ps, .eps, .ept),
 * and ANSI Art (.ans) character-cell graphics into Bitmaps.
 */
object MetafileAndVectorImageDecoder {

    val extensions: Set<String> = setOf("wmf", "wmz", "ps", "eps", "ept", "ans")

    fun decode(input: InputStream, targetPx: Int, extHint: String = ""): Bitmap? =
        decode(input.readBytes(), targetPx, extHint)

    fun decode(bytes: ByteArray, targetPx: Int, extHint: String = ""): Bitmap? {
        if (bytes.isEmpty()) return null
        val ext = extHint.lowercase()

        // 1. GZIP-compressed WMF (.wmz)
        if (bytes.size >= 4 && u8(bytes, 0) == 0x1F && u8(bytes, 1) == 0x8B) {
            val unzipped = try {
                GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
            } catch (_: Throwable) {
                null
            }
            if (unzipped != null) {
                return decode(unzipped, targetPx, if (ext == "wmz") "wmf" else ext)
            }
        }

        // 2. DOS Binary EPS (.ept) with embedded TIFF or WMF preview
        if (bytes.size >= 30 && u8(bytes, 0) == 0xC5 && u8(bytes, 1) == 0xD0 &&
            u8(bytes, 2) == 0xD3 && u8(bytes, 3) == 0xC6
        ) {
            val bmp = TiffAndRawImageDecoder.decode(bytes, targetPx)
            if (bmp != null || ImageDecodeUtils.lastDecodedPixelsForTest != null) return bmp
            val psOff = u32le(bytes, 4).toInt()
            val psLen = u32le(bytes, 8).toInt()
            if (psLen > 0 && psOff >= 30 && psOff + psLen <= bytes.size) {
                return decodePostScript(bytes.copyOfRange(psOff, psOff + psLen), targetPx)
            }
        }

        // 3. Aldus Placeable WMF (0x9AC6CDD7) or Standard WMF
        if ((bytes.size >= 22 && u32le(bytes, 0) == 0x9AC6CDD7L) ||
            (bytes.size >= 18 && u16le(bytes, 0) in 1..2 && u16le(bytes, 2) == 9 && u16le(bytes, 4) in setOf(0x0100, 0x0300))
        ) {
            return decodeWmf(bytes, targetPx)
        }

        // 4. PostScript / EPS ("%!PS")
        if (bytes.size >= 4 && String(bytes, 0, 4, StandardCharsets.US_ASCII) == "%!PS") {
            return decodePostScript(bytes, targetPx)
        }

        // 5. ANSI Art (.ans or SAUCE00 / CSI SGR sequences)
        if (ext == "ans" || looksLikeAnsiArt(bytes)) {
            return decodeAnsiArt(bytes, targetPx)
        }

        return null
    }

    // =========================================================================
    // 1. Windows Metafile (.wmf, .wmz)
    // =========================================================================
    fun decodeWmf(bytes: ByteArray, targetPx: Int): Bitmap? {
        if (bytes.size < 18) return null
        val hasPlaceable = bytes.size >= 22 && u32le(bytes, 0) == 0x9AC6CDD7L
        val wmfStart = if (hasPlaceable) 22 else 0
        if (wmfStart + 18 > bytes.size) return null

        var pos = wmfStart + 18
        while (pos + 6 <= bytes.size) {
            val sizeWords = u32le(bytes, pos).toInt()
            val func = u16le(bytes, pos + 4)
            if (func == 0x0000 || sizeWords < 3) break // META_EOF
            val recordByteLen = sizeWords * 2
            val nextPos = pos + recordByteLen

            when (func) {
                0x0F43 -> { // META_STRETCHDIB: size(4), func(2), rop(4), usage(2), srcH(2), srcW(2), srcY(2), srcX(2), dstH(2), dstW(2), dstY(2), dstX(2), dib...
                    val dibOffset = pos + 28
                    if (dibOffset + 40 <= bytes.size) {
                        val bmp = decodeDib(bytes, dibOffset, nextPos.coerceAtMost(bytes.size), targetPx)
                        if (bmp != null || ImageDecodeUtils.lastDecodedPixelsForTest != null) return bmp
                    }
                }
                0x0B41 -> { // META_DIBSTRETCHBLT: size(4), func(2), rop(4), srcH(2), srcW(2), srcY(2), srcX(2), dstH(2), dstW(2), dstY(2), dstX(2), dib...
                    val dibOffset = pos + 26
                    if (dibOffset + 40 <= bytes.size) {
                        val bmp = decodeDib(bytes, dibOffset, nextPos.coerceAtMost(bytes.size), targetPx)
                        if (bmp != null || ImageDecodeUtils.lastDecodedPixelsForTest != null) return bmp
                    }
                }
                0x0D33 -> { // META_SETDIBTODEV
                    val dibOffset = pos + 24
                    if (dibOffset + 40 <= bytes.size) {
                        val bmp = decodeDib(bytes, dibOffset, nextPos.coerceAtMost(bytes.size), targetPx)
                        if (bmp != null || ImageDecodeUtils.lastDecodedPixelsForTest != null) return bmp
                    }
                }
            }
            pos = nextPos
        }
        return null
    }

    private fun decodeDib(bytes: ByteArray, offset: Int, limit: Int, targetPx: Int): Bitmap? {
        if (offset + 40 > limit) return null
        val headerSize = s32le(bytes, offset)
        if (headerSize < 40 || offset + headerSize > limit) return null
        val width = s32le(bytes, offset + 4)
        val rawHeight = s32le(bytes, offset + 8)
        val topDown = rawHeight < 0
        val height = kotlin.math.abs(rawHeight)
        val bitCount = u16le(bytes, offset + 14)
        val compression = s32le(bytes, offset + 16)
        val clrUsed = s32le(bytes, offset + 32)
        if (!isValidDimensions(width, height) || compression != 0) return null
        if (bitCount !in setOf(1, 4, 8, 24, 32)) return null

        val paletteEntries = when {
            bitCount <= 8 -> if (clrUsed > 0) clrUsed else (1 shl bitCount)
            else -> 0
        }
        val palOffset = offset + headerSize
        val pixelOffset = palOffset + paletteEntries * 4
        if (pixelOffset > limit) return null

        val palette = IntArray(paletteEntries.coerceAtLeast(256))
        for (i in 0 until paletteEntries) {
            val p = palOffset + i * 4
            if (p + 4 > limit) break
            palette[i] = packArgb(255, u8(bytes, p + 2), u8(bytes, p + 1), u8(bytes, p))
        }

        val rowStride = ((width * bitCount + 31) / 32) * 4
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val srcY = if (topDown) y else (height - 1 - y)
            val rowOff = pixelOffset + srcY * rowStride
            if (rowOff >= limit) break
            for (x in 0 until width) {
                val color = when (bitCount) {
                    24 -> {
                        val p = rowOff + x * 3
                        if (p + 3 > limit) break
                        packArgb(255, u8(bytes, p + 2), u8(bytes, p + 1), u8(bytes, p))
                    }
                    32 -> {
                        val p = rowOff + x * 4
                        if (p + 4 > limit) break
                        val a = u8(bytes, p + 3).let { if (it == 0) 255 else it }
                        packArgb(a, u8(bytes, p + 2), u8(bytes, p + 1), u8(bytes, p))
                    }
                    8 -> palette[u8(bytes, (rowOff + x).coerceAtMost(limit - 1))]
                    4 -> {
                        val b = u8(bytes, (rowOff + (x ushr 1)).coerceAtMost(limit - 1))
                        val idx = if ((x and 1) == 0) (b ushr 4) else (b and 0x0F)
                        palette[idx]
                    }
                    1 -> {
                        val b = u8(bytes, (rowOff + (x ushr 3)).coerceAtMost(limit - 1))
                        val idx = (b ushr (7 - (x and 7))) and 1
                        palette[idx]
                    }
                    else -> 0xFF000000.toInt()
                }
                pixels[y * width + x] = color
            }
        }
        return createScaledBitmap(pixels, width, height, targetPx)
    }

    // =========================================================================
    // 2. PostScript / Encapsulated PostScript (.ps, .eps)
    // =========================================================================
    fun decodePostScript(bytes: ByteArray, targetPx: Int): Bitmap? {
        val text = String(bytes, StandardCharsets.ISO_8859_1)

        // 1. Check for ImageMagick `DisplayImage` block:
        //    DisplayImage \n x y \n sx sy \n pointsize \n columns rows \n class(0/1) \n compression(0/1) \n hex...
        val dispIdx = text.lastIndexOf("\nDisplayImage")
        if (dispIdx >= 0) {
            val bmp = decodeImageMagickDisplayImage(text, dispIdx + 1, targetPx)
            if (bmp != null || ImageDecodeUtils.lastDecodedPixelsForTest != null) return bmp
        }

        // 2. Check for EPSI hexadecimal preview (`%%BeginPreview: width height depth lines`)
        val prevIdx = text.indexOf("%%BeginPreview:")
        if (prevIdx >= 0) {
            val bmp = decodeEpsiPreview(text, prevIdx, targetPx)
            if (bmp != null || ImageDecodeUtils.lastDecodedPixelsForTest != null) return bmp
        }

        return null
    }

    private fun decodeImageMagickDisplayImage(text: String, startIdx: Int, targetPx: Int): Bitmap? {
        var pos = text.indexOf('\n', startIdx)
        if (pos < 0) return null
        pos++

        fun nextLine(): String? {
            while (pos < text.length) {
                val end = text.indexOf('\n', pos).let { if (it < 0) text.length else it }
                val line = text.substring(pos, end).trim()
                pos = end + 1
                if (line.isNotEmpty() && !line.startsWith("%")) return line
            }
            return null
        }

        nextLine() ?: return null // x y translation
        nextLine() ?: return null // x y scale
        val line3 = nextLine() ?: return null // pointsize (or label)
        var dimLine = nextLine() ?: return null
        if (!dimLine[0].isDigit()) {
            dimLine = nextLine() ?: return null
        }
        val dims = dimLine.split(Regex("\\s+"))
        if (dims.size < 2) return null
        val width = dims[0].toIntOrNull() ?: return null
        val height = dims[1].toIntOrNull() ?: return null
        if (!isValidDimensions(width, height)) return null

        val imageClass = nextLine()?.toIntOrNull() ?: 0 // 0 = DirectClass, 1 = PseudoClass
        val compression = nextLine()?.toIntOrNull() ?: 0 // 0 = None, 1 = RLE

        fun hexVal(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> -1
        }

        fun nextHexByte(): Int {
            var hi = -1
            while (pos < text.length) {
                hi = hexVal(text[pos++])
                if (hi >= 0) break
            }
            var lo = -1
            while (pos < text.length) {
                lo = hexVal(text[pos++])
                if (lo >= 0) break
            }
            if (hi < 0 || lo < 0) return -1
            return (hi shl 4) or lo
        }

        val totalPixels = width * height
        val pixels = IntArray(totalPixels)

        if (imageClass == 0) {
            var px = 0
            while (px < totalPixels && pos < text.length) {
                val r = nextHexByte()
                val g = nextHexByte()
                val b = nextHexByte()
                if (r < 0 || g < 0 || b < 0) break
                val count = if (compression == 0) 1 else ((nextHexByte().coerceAtLeast(0)) + 1)
                val color = packArgb(255, r, g, b)
                for (k in 0 until count) {
                    if (px >= totalPixels) break
                    pixels[px++] = color
                }
            }
        } else {
            val numColors = nextLine()?.toIntOrNull()?.coerceIn(1, 65536) ?: 256
            val palette = IntArray(numColors)
            for (i in 0 until numColors) {
                val r = nextHexByte().coerceAtLeast(0)
                val g = nextHexByte().coerceAtLeast(0)
                val b = nextHexByte().coerceAtLeast(0)
                palette[i] = packArgb(255, r, g, b)
            }
            var px = 0
            while (px < totalPixels && pos < text.length) {
                val idx = nextHexByte()
                if (idx < 0) break
                val count = if (compression == 0) 1 else ((nextHexByte().coerceAtLeast(0)) + 1)
                val color = palette[idx.coerceIn(0, numColors - 1)]
                for (k in 0 until count) {
                    if (px >= totalPixels) break
                    pixels[px++] = color
                }
            }
        }

        return createScaledBitmap(pixels, width, height, targetPx)
    }

    private fun decodeEpsiPreview(text: String, startIdx: Int, targetPx: Int): Bitmap? {
        val lineEnd = text.indexOf('\n', startIdx).takeIf { it >= 0 } ?: return null
        val header = text.substring(startIdx + "%%BeginPreview:".length, lineEnd).trim().split(Regex("\\s+"))
        if (header.size < 3) return null
        val width = header[0].toIntOrNull() ?: return null
        val height = header[1].toIntOrNull() ?: return null
        val depth = header[2].toIntOrNull() ?: 1
        if (!isValidDimensions(width, height) || depth !in setOf(1, 8)) return null

        val endPrev = text.indexOf("%%EndPreview", lineEnd).takeIf { it >= 0 } ?: text.length
        val body = text.substring(lineEnd + 1, endPrev)
        val hexBytes = ArrayList<Int>((width * height * depth + 7) / 8)
        var hi = -1
        for (ch in body) {
            if (ch == '%') continue
            val v = when (ch) {
                in '0'..'9' -> ch - '0'
                in 'a'..'f' -> ch - 'a' + 10
                in 'A'..'F' -> ch - 'A' + 10
                else -> -1
            }
            if (v >= 0) {
                if (hi < 0) hi = v else {
                    hexBytes.add((hi shl 4) or v)
                    hi = -1
                }
            }
        }

        val pixels = IntArray(width * height)
        val rowBytes = (width * depth + 7) / 8
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = if (depth == 1) {
                    val b = hexBytes.getOrElse(y * rowBytes + (x ushr 3)) { 0 }
                    if (((b ushr (7 - (x and 7))) and 1) == 1) 0 else 255
                } else {
                    hexBytes.getOrElse(y * rowBytes + x) { 255 }
                }
                pixels[y * width + x] = packArgb(255, v, v, v)
            }
        }
        return createScaledBitmap(pixels, width, height, targetPx)
    }

    // =========================================================================
    // 3. ANSI Art (.ans) with SAUCE00 & 24-bit / 256-color / 16-color SGR
    // =========================================================================
    private fun looksLikeAnsiArt(bytes: ByteArray): Boolean {
        if (bytes.size >= 128 && String(bytes, bytes.size - 128, 7, StandardCharsets.US_ASCII) == "SAUCE00") {
            return true
        }
        val sample = String(bytes, 0, bytes.size.coerceAtMost(512), StandardCharsets.ISO_8859_1)
        return sample.contains("\u001b[")
    }

    fun decodeAnsiArt(bytes: ByteArray, targetPx: Int): Bitmap? {
        var contentLen = bytes.size
        var cols = 80
        var rowsHint = 0

        if (bytes.size >= 128 && String(bytes, bytes.size - 128, 7, StandardCharsets.US_ASCII) == "SAUCE00") {
            val sauceOff = bytes.size - 128
            val tInfo1 = u16le(bytes, sauceOff + 96)
            val tInfo2 = u16le(bytes, sauceOff + 98)
            if (tInfo1 in 1..320) cols = tInfo1
            if (tInfo2 in 1..1000) rowsHint = tInfo2
            contentLen = sauceOff
            if (contentLen > 0 && u8(bytes, contentLen - 1) == 0x1A) {
                contentLen--
            }
        }

        // Standard 16-color CGA/VGA ANSI palette
        val vga16 = intArrayOf(
            0xFF000000.toInt(), 0xFFAA0000.toInt(), 0xFF00AA00.toInt(), 0xFFAA5500.toInt(),
            0xFF0000AA.toInt(), 0xFFAA00AA.toInt(), 0xFF00AAAA.toInt(), 0xFFAAAAAA.toInt(),
            0xFF555555.toInt(), 0xFFFF5555.toInt(), 0xFF55FF55.toInt(), 0xFFFFFF55.toInt(),
            0xFF5555FF.toInt(), 0xFFFF55FF.toInt(), 0xFF55FFFF.toInt(), 0xFFFFFFFF.toInt()
        )

        fun xterm256(idx: Int): Int {
            if (idx in 0..15) return vga16[idx]
            if (idx in 16..231) {
                val c = idx - 16
                val r = (c / 36) * 51
                val g = ((c / 6) % 6) * 51
                val b = (c % 6) * 51
                return packArgb(255, r, g, b)
            }
            val gray = ((idx - 232).coerceIn(0, 23)) * 10 + 8
            return packArgb(255, gray, gray, gray)
        }

        data class Cell(val ch: Int, val fg: Int, val bg: Int)

        val maxRows = (rowsHint.takeIf { it > 0 } ?: 100).coerceIn(25, 500)
        val grid = Array(maxRows) { Array(cols) { Cell(0x20, vga16[7], vga16[0]) } }

        var curX = 0
        var curY = 0
        var maxRowUsed = 0
        var fg = vga16[7]
        var bg = vga16[0]
        var bold = false
        var pos = 0

        while (pos < contentLen) {
            val b = u8(bytes, pos++)
            when (b) {
                0x1A -> break // SUB / EOF
                0x0D -> curX = 0
                0x0A -> {
                    curY++
                    if (curY > maxRowUsed) maxRowUsed = curY
                }
                0x1B -> {
                    if (pos < contentLen && bytes[pos].toInt().toChar() == '[') {
                        pos++
                        val params = ArrayList<Int>()
                        var curNum = -1
                        var cmdChar = 'm'
                        while (pos < contentLen) {
                            val c = bytes[pos++].toInt().toChar()
                            if (c in '0'..'9') {
                                curNum = (if (curNum < 0) 0 else curNum * 10) + (c - '0')
                            } else if (c == ';') {
                                params.add(if (curNum < 0) 0 else curNum)
                                curNum = -1
                            } else if (c in '@'..'~') {
                                if (curNum >= 0 || params.isNotEmpty()) {
                                    params.add(if (curNum < 0) 0 else curNum)
                                }
                                cmdChar = c
                                break
                            }
                        }
                        when (cmdChar) {
                            'm' -> {
                                if (params.isEmpty()) params.add(0)
                                var pIdx = 0
                                while (pIdx < params.size) {
                                    when (val code = params[pIdx++]) {
                                        0 -> { bold = false; fg = vga16[7]; bg = vga16[0] }
                                        1 -> bold = true
                                        22 -> bold = false
                                        in 30..37 -> fg = vga16[(code - 30) + (if (bold) 8 else 0)]
                                        in 40..47 -> bg = vga16[code - 40]
                                        in 90..97 -> fg = vga16[code - 90 + 8]
                                        in 100..107 -> bg = vga16[code - 100 + 8]
                                        38 -> {
                                            if (pIdx < params.size) {
                                                val mode = params[pIdx++]
                                                if (mode == 2 && pIdx + 2 < params.size) {
                                                    fg = packArgb(255, params[pIdx], params[pIdx + 1], params[pIdx + 2])
                                                    pIdx += 3
                                                } else if (mode == 5 && pIdx < params.size) {
                                                    fg = xterm256(params[pIdx++])
                                                }
                                            }
                                        }
                                        48 -> {
                                            if (pIdx < params.size) {
                                                val mode = params[pIdx++]
                                                if (mode == 2 && pIdx + 2 < params.size) {
                                                    bg = packArgb(255, params[pIdx], params[pIdx + 1], params[pIdx + 2])
                                                    pIdx += 3
                                                } else if (mode == 5 && pIdx < params.size) {
                                                    bg = xterm256(params[pIdx++])
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            'A' -> curY = (curY - (params.firstOrNull()?.coerceAtLeast(1) ?: 1)).coerceAtLeast(0)
                            'B' -> {
                                curY = (curY + (params.firstOrNull()?.coerceAtLeast(1) ?: 1)).coerceAtMost(maxRows - 1)
                                if (curY > maxRowUsed) maxRowUsed = curY
                            }
                            'C' -> curX = (curX + (params.firstOrNull()?.coerceAtLeast(1) ?: 1)).coerceAtMost(cols - 1)
                            'D' -> curX = (curX - (params.firstOrNull()?.coerceAtLeast(1) ?: 1)).coerceAtLeast(0)
                            'H', 'f' -> {
                                curY = ((params.getOrElse(0) { 1 } - 1)).coerceIn(0, maxRows - 1)
                                curX = ((params.getOrElse(1) { 1 } - 1)).coerceIn(0, cols - 1)
                                if (curY > maxRowUsed) maxRowUsed = curY
                            }
                        }
                    }
                }
                else -> {
                    if (curY in 0 until maxRows && curX in 0 until cols) {
                        grid[curY][curX] = Cell(b, fg, bg)
                        if (curY > maxRowUsed) maxRowUsed = curY
                        curX++
                        if (curX >= cols) {
                            curX = 0
                            curY++
                        }
                    }
                }
            }
        }

        val finalRows = (if (rowsHint > 0) rowsHint else (maxRowUsed + 1)).coerceIn(1, maxRows)
        val cellW = 8
        val cellH = 16
        val width = cols * cellW
        val height = finalRows * cellH
        val pixels = IntArray(width * height)

        for (ry in 0 until finalRows) {
            for (cx in 0 until cols) {
                val cell = grid[ry][cx]
                renderCp437Cell(cell.ch, cell.fg, cell.bg, cx * cellW, ry * cellH, width, pixels)
            }
        }

        return createScaledBitmap(pixels, width, height, targetPx)
    }

    private fun renderCp437Cell(
        ch: Int,
        fg: Int,
        bg: Int,
        baseX: Int,
        baseY: Int,
        canvasW: Int,
        pixels: IntArray
    ) {
        for (py in 0 until 16) {
            val rowOff = (baseY + py) * canvasW + baseX
            for (px in 0 until 8) {
                val useFg = when (ch) {
                    0x20, 0x00, 0xFF -> false
                    0xDB -> true                           // Full block █
                    0xDF -> py < 8                         // Upper half block ▀
                    0xDC -> py >= 8                        // Lower half block ▄
                    0xDD -> px < 4                         // Left half block ▌
                    0xDE -> px >= 4                        // Right half block ▐
                    0xB0 -> ((px + py) and 3) == 0         // Light shade ░
                    0xB1 -> ((px + py) and 1) == 0         // Medium shade ▒
                    0xB2 -> ((px + py) and 3) != 0         // Dark shade ▓
                    else -> {
                        // Simple deterministic glyph stipple for ASCII / box drawing characters
                        val inBox = px in 1..6 && py in 2..13
                        inBox && (((ch ushr ((px + py) and 7)) and 1) != 0)
                    }
                }
                pixels[rowOff + px] = if (useFg) fg else bg
            }
        }
    }
}
