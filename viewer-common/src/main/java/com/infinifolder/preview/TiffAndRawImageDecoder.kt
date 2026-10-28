package com.infinifolder.preview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.infinifolder.preview.ImageDecodeUtils.createScaledBitmap
import com.infinifolder.preview.ImageDecodeUtils.decodePackBits
import com.infinifolder.preview.ImageDecodeUtils.isValidDimensions
import com.infinifolder.preview.ImageDecodeUtils.packArgb
import com.infinifolder.preview.ImageDecodeUtils.u16be
import com.infinifolder.preview.ImageDecodeUtils.u16le
import com.infinifolder.preview.ImageDecodeUtils.u32be
import com.infinifolder.preview.ImageDecodeUtils.u32le
import com.infinifolder.preview.ImageDecodeUtils.u8
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.InflaterInputStream
import kotlin.math.max

/**
 * Decodes Baseline & Extended TIFF (.tif, .tiff), Adobe DNG (.dng) uncompressed/compressed IFDs,
 * and DOS Binary EPS (.ept) embedded TIFF previews.
 */
object TiffAndRawImageDecoder {

    val extensions: Set<String> = setOf("tif", "tiff", "dng", "ept")

    fun decode(input: InputStream, targetPx: Int): Bitmap? = decode(input.readBytes(), targetPx)

    fun decode(bytes: ByteArray, targetPx: Int): Bitmap? {
        if (bytes.size < 8) return null

        // Check for DOS Binary EPS (.ept) header: C5 D0 D3 C6
        if (bytes.size >= 30 && u8(bytes, 0) == 0xC5 && u8(bytes, 1) == 0xD0 &&
            u8(bytes, 2) == 0xD3 && u8(bytes, 3) == 0xC6
        ) {
            val tiffOffset = u32le(bytes, 20).toInt()
            val tiffLength = u32le(bytes, 24).toInt()
            if (tiffLength > 8 && tiffOffset >= 30 && tiffOffset + tiffLength <= bytes.size) {
                val slice = bytes.copyOfRange(tiffOffset, tiffOffset + tiffLength)
                val bmp = decodeTiff(slice, targetPx)
                if (bmp != null || ImageDecodeUtils.lastDecodedPixelsForTest != null) return bmp
            }
            val wmfOffset = u32le(bytes, 12).toInt()
            val wmfLength = u32le(bytes, 16).toInt()
            if (wmfLength > 18 && wmfOffset >= 30 && wmfOffset + wmfLength <= bytes.size) {
                val slice = bytes.copyOfRange(wmfOffset, wmfOffset + wmfLength)
                val bmp = MetafileAndVectorImageDecoder.decodeWmf(slice, targetPx)
                if (bmp != null || ImageDecodeUtils.lastDecodedPixelsForTest != null) return bmp
            }
        }

        return decodeTiff(bytes, targetPx)
    }

    fun decodeTiff(bytes: ByteArray, targetPx: Int): Bitmap? {
        if (bytes.size < 8) return null
        val endianTag = String(bytes, 0, 2, StandardCharsets.US_ASCII)
        val le = when (endianTag) {
            "II" -> true
            "MM" -> false
            else -> return null
        }

        fun r16(off: Int): Int = if (le) u16le(bytes, off) else u16be(bytes, off)
        fun r32(off: Int): Long = if (le) u32le(bytes, off) else u32be(bytes, off)

        val magic = r16(2)
        if (magic != 42 && magic != 43) return null
        val firstIfdOffset = r32(4).toInt()
        if (firstIfdOffset !in 8 until bytes.size) return null

        // Walk IFD chain and pick the highest-resolution decodable IFD (or SubIFD)
        val candidateIfds = ArrayList<Int>()
        var curIfd = firstIfdOffset
        var guard = 0
        while (curIfd in 8..(bytes.size - 2) && guard++ < 16) {
            candidateIfds.add(curIfd)
            val entryCount = r16(curIfd)
            // Also inspect SubIFDs (tag 330) if present
            for (i in 0 until entryCount) {
                val eOff = curIfd + 2 + i * 12
                if (eOff + 12 > bytes.size) break
                if (r16(eOff) == 330) { // SubIFDs
                    val count = r32(eOff + 4).toInt().coerceAtMost(8)
                    val valOrOff = r32(eOff + 8).toInt()
                    if (count == 1 && valOrOff in 8 until bytes.size) {
                        candidateIfds.add(valOrOff)
                    } else if (count > 1 && valOrOff in 8..(bytes.size - count * 4)) {
                        for (k in 0 until count) {
                            val subOff = r32(valOrOff + k * 4).toInt()
                            if (subOff in 8 until bytes.size) candidateIfds.add(subOff)
                        }
                    }
                }
            }
            val nextPtrPos = curIfd + 2 + entryCount * 12
            curIfd = if (nextPtrPos + 4 <= bytes.size) r32(nextPtrPos).toInt() else 0
        }

        var bestBitmap: Bitmap? = null
        var bestArea = 0L
        var bestPixelsForTest: ImageDecodeUtils.DecodedPixels? = null
        for (ifdOff in candidateIfds) {
            ImageDecodeUtils.lastDecodedPixelsForTest = null
            val bmp = decodeIfd(bytes, ifdOff, le, targetPx)
            val decoded = ImageDecodeUtils.lastDecodedPixelsForTest
            if (decoded != null) {
                val area = decoded.width.toLong() * decoded.height.toLong()
                if (area > bestArea) {
                    bestPixelsForTest = decoded
                    bestArea = area
                }
            }
            if (bmp != null) {
                val area = bmp.width.toLong() * bmp.height.toLong()
                if (area >= bestArea || bestBitmap == null) {
                    bestBitmap?.recycle()
                    bestBitmap = bmp
                    bestArea = area
                } else {
                    bmp.recycle()
                }
            }
        }
        if (bestPixelsForTest != null) {
            ImageDecodeUtils.lastDecodedPixelsForTest = bestPixelsForTest
        }
        return bestBitmap
    }

    private fun decodeIfd(bytes: ByteArray, ifdOffset: Int, le: Boolean, targetPx: Int): Bitmap? {
        fun r16(off: Int): Int = if (le) u16le(bytes, off) else u16be(bytes, off)
        fun r32(off: Int): Long = if (le) u32le(bytes, off) else u32be(bytes, off)

        if (ifdOffset + 2 > bytes.size) return null
        val numEntries = r16(ifdOffset)
        if (numEntries <= 0 || ifdOffset + 2 + numEntries * 12 > bytes.size) return null

        fun typeByteSize(type: Int): Int = when (type) {
            1, 2, 6, 7 -> 1
            3, 8 -> 2
            4, 9, 11 -> 4
            5, 10, 12 -> 8
            else -> 1
        }

        fun readIntValues(entryOff: Int): IntArray {
            val type = r16(entryOff + 2)
            val count = r32(entryOff + 4).toInt().coerceIn(0, 65536)
            val totalBytes = count * typeByteSize(type)
            val valueOffset = if (totalBytes <= 4) (entryOff + 8) else r32(entryOff + 8).toInt()
            if (valueOffset < 0 || valueOffset + totalBytes > bytes.size) return IntArray(0)
            val out = IntArray(count)
            for (i in 0 until count) {
                val p = valueOffset + i * typeByteSize(type)
                out[i] = when (type) {
                    1, 2, 6, 7 -> u8(bytes, p)
                    3, 8 -> r16(p)
                    4, 9 -> r32(p).toInt()
                    5 -> {
                        val num = r32(p)
                        val den = r32(p + 4).coerceAtLeast(1L)
                        (num / den).toInt()
                    }
                    else -> 0
                }
            }
            return out
        }

        var width = 0
        var height = 0
        var compression = 1
        var photometric = 2
        var samplesPerPixel = 1
        var rowsPerStrip = Int.MAX_VALUE
        var planarConfig = 1
        var predictor = 1
        var bitsPerSample = intArrayOf(8)
        var stripOffsets = IntArray(0)
        var stripByteCounts = IntArray(0)
        var tileWidth = 0
        var tileLength = 0
        var tileOffsets = IntArray(0)
        var tileByteCounts = IntArray(0)
        var colorMap = IntArray(0)
        var jpegOffset = 0
        var jpegLength = 0

        for (i in 0 until numEntries) {
            val eOff = ifdOffset + 2 + i * 12
            val tag = r16(eOff)
            val vals = readIntValues(eOff)
            if (vals.isEmpty()) continue
            when (tag) {
                256 -> width = vals[0]
                257 -> height = vals[0]
                258 -> bitsPerSample = vals
                259 -> compression = vals[0]
                262 -> photometric = vals[0]
                273 -> stripOffsets = vals
                277 -> samplesPerPixel = vals[0]
                278 -> rowsPerStrip = vals[0]
                279 -> stripByteCounts = vals
                284 -> planarConfig = vals[0]
                317 -> predictor = vals[0]
                320 -> colorMap = vals
                322 -> tileWidth = vals[0]
                323 -> tileLength = vals[0]
                324 -> tileOffsets = vals
                325 -> tileByteCounts = vals
                513 -> jpegOffset = vals[0]
                514 -> jpegLength = vals[0]
            }
        }

        // Embedded JPEG-in-TIFF / DNG preview
        if ((compression == 6 || compression == 7) && jpegOffset > 0 && jpegLength > 0 && jpegOffset + jpegLength <= bytes.size) {
            BitmapFactory.decodeByteArray(bytes, jpegOffset, jpegLength)?.let { return it }
        }
        if ((compression == 6 || compression == 7) && stripOffsets.isNotEmpty() && stripByteCounts.isNotEmpty()) {
            val off = stripOffsets[0]
            val len = stripByteCounts[0]
            if (off > 0 && len > 0 && off + len <= bytes.size) {
                BitmapFactory.decodeByteArray(bytes, off, len)?.let { return it }
            }
        }

        if (!isValidDimensions(width, height)) return null
        if (planarConfig != 1) return null // Chunky interleaved supported
        val bps = bitsPerSample.firstOrNull()?.takeIf { it in setOf(1, 2, 4, 8, 16, 32) } ?: 8
        val spp = samplesPerPixel.coerceIn(1, 8)
        val rowBytes = (width * spp * bps + 7) / 8
        val rawImageBytes = ByteArray(rowBytes * height)

        if (tileOffsets.isNotEmpty() && tileByteCounts.isNotEmpty() && tileWidth > 0 && tileLength > 0) {
            val tilesAcross = (width + tileWidth - 1) / tileWidth
            val tileRowBytes = (tileWidth * spp * bps + 7) / 8
            val expectedTileBytes = tileRowBytes * tileLength
            for (tIdx in tileOffsets.indices) {
                val off = tileOffsets[tIdx]
                val count = tileByteCounts.getOrElse(tIdx) { 0 }
                if (off <= 0 || count <= 0 || off + count > bytes.size) continue
                val tileData = decompressStrip(bytes, off, count, compression, expectedTileBytes) ?: continue
                if (predictor == 2 && bps == 8) {
                    applyHorizontalDifferencingPredictor(tileData, tileWidth, tileLength, spp)
                }
                val tx = (tIdx % tilesAcross) * tileWidth
                val ty = (tIdx / tilesAcross) * tileLength
                val copyBytesPerRow = ((minOf(tileWidth, width - tx) * spp * bps + 7) / 8).coerceAtLeast(0)
                for (ry in 0 until minOf(tileLength, height - ty)) {
                    val srcPos = ry * tileRowBytes
                    val dstPos = (ty + ry) * rowBytes + (tx * spp * bps) / 8
                    if (srcPos + copyBytesPerRow <= tileData.size && dstPos + copyBytesPerRow <= rawImageBytes.size) {
                        System.arraycopy(tileData, srcPos, rawImageBytes, dstPos, copyBytesPerRow)
                    }
                }
            }
        } else if (stripOffsets.isNotEmpty()) {
            val effectiveRowsPerStrip = if (rowsPerStrip <= 0 || rowsPerStrip > height) height else rowsPerStrip
            var curRow = 0
            for (sIdx in stripOffsets.indices) {
                if (curRow >= height) break
                val off = stripOffsets[sIdx]
                val rowsInThisStrip = minOf(effectiveRowsPerStrip, height - curRow)
                val expectedStripBytes = rowsInThisStrip * rowBytes
                val count = stripByteCounts.getOrElse(sIdx) { expectedStripBytes }
                if (off <= 0 || count <= 0 || off + count > bytes.size) {
                    curRow += rowsInThisStrip
                    continue
                }
                val stripData = decompressStrip(bytes, off, count, compression, expectedStripBytes)
                if (stripData != null) {
                    if (predictor == 2 && bps == 8) {
                        applyHorizontalDifferencingPredictor(stripData, width, rowsInThisStrip, spp)
                    }
                    val copyLen = minOf(stripData.size, expectedStripBytes, rawImageBytes.size - curRow * rowBytes)
                    if (copyLen > 0) {
                        System.arraycopy(stripData, 0, rawImageBytes, curRow * rowBytes, copyLen)
                    }
                }
                curRow += rowsInThisStrip
            }
        } else {
            return null
        }

        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val rowOff = y * rowBytes
            for (x in 0 until width) {
                val color = when {
                    bps == 1 -> {
                        val b = u8(rawImageBytes, rowOff + (x ushr 3))
                        val bit = (b ushr (7 - (x and 7))) and 1
                        val v = if (photometric == 0) {
                            if (bit == 0) 255 else 0
                        } else {
                            if (bit == 1) 255 else 0
                        }
                        packArgb(255, v, v, v)
                    }
                    bps == 8 || bps == 16 -> {
                        val step = if (bps == 16) 2 else 1
                        val pxOff = rowOff + x * spp * step
                        fun ch(c: Int): Int {
                            val p = pxOff + c * step
                            if (p + step > rawImageBytes.size) return 0
                            return if (bps == 16) {
                                if (le) u8(rawImageBytes, p + 1) else u8(rawImageBytes, p)
                            } else {
                                u8(rawImageBytes, p)
                            }
                        }
                        when (photometric) {
                            0 -> { // WhiteIsZero
                                val v = 255 - ch(0)
                                val a = if (spp >= 2) ch(1) else 255
                                packArgb(a, v, v, v)
                            }
                            1 -> { // BlackIsZero
                                val v = ch(0)
                                val a = if (spp >= 2) ch(1) else 255
                                packArgb(a, v, v, v)
                            }
                            3 -> { // Palette Color
                                val idx = ch(0)
                                val nEntries = colorMap.size / 3
                                if (idx < nEntries) {
                                    val r = (colorMap[idx] ushr 8) and 0xFF
                                    val g = (colorMap[nEntries + idx] ushr 8) and 0xFF
                                    val b = (colorMap[nEntries * 2 + idx] ushr 8) and 0xFF
                                    packArgb(255, r, g, b)
                                } else {
                                    packArgb(255, idx, idx, idx)
                                }
                            }
                            5 -> { // CMYK
                                val c = ch(0)
                                val m = ch(1)
                                val yVal = ch(2)
                                val k = if (spp >= 4) ch(3) else 0
                                val r = ((255 - c) * (255 - k)) / 255
                                val g = ((255 - m) * (255 - k)) / 255
                                val b = ((255 - yVal) * (255 - k)) / 255
                                packArgb(255, r, g, b)
                            }
                            else -> { // RGB (photometric == 2) or CFA
                                val r = ch(0)
                                val g = if (spp >= 2) ch(1) else r
                                val b = if (spp >= 3) ch(2) else r
                                val a = if (spp >= 4) ch(3) else 255
                                packArgb(a, r, g, b)
                            }
                        }
                    }
                    else -> 0xFF000000.toInt()
                }
                pixels[y * width + x] = color
            }
        }

        return createScaledBitmap(pixels, width, height, targetPx)
    }

    private fun applyHorizontalDifferencingPredictor(data: ByteArray, width: Int, rows: Int, spp: Int) {
        val rowStride = width * spp
        for (y in 0 until rows) {
            val rowStart = y * rowStride
            if (rowStart + rowStride > data.size) break
            for (i in spp until rowStride) {
                data[rowStart + i] = ((data[rowStart + i].toInt() + data[rowStart + i - spp].toInt()) and 0xFF).toByte()
            }
        }
    }

    private fun decompressStrip(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        compression: Int,
        expectedSize: Int
    ): ByteArray? {
        return when (compression) {
            1 -> bytes.copyOfRange(offset, (offset + length).coerceAtMost(bytes.size))
            5 -> decodeTiffLzw(bytes, offset, length, expectedSize)
            8, 32946 -> { // Deflate / Adobe Deflate
                try {
                    InflaterInputStream(ByteArrayInputStream(bytes, offset, length)).use {
                        it.readBytes()
                    }
                } catch (_: Throwable) {
                    null
                }
            }
            32773 -> decodePackBits(bytes, offset, expectedSize, offset + length).first
            else -> null
        }
    }

    /**
     * Decodes MSB-first TIFF LZW compressed strips (variable code width 9..12 bits, Clear=256, EOI=257).
     */
    private fun decodeTiffLzw(src: ByteArray, offset: Int, length: Int, expectedSize: Int): ByteArray {
        val out = ByteArrayOutputStream(expectedSize.coerceAtLeast(1024))
        val limit = (offset + length).coerceAtMost(src.size)
        var bitPos = 0
        val totalBits = (limit - offset) * 8

        fun nextCode(codeBits: Int): Int {
            if (bitPos + codeBits > totalBits) return 257
            var acc = 0
            for (i in 0 until 3) {
                val byteIdx = offset + (bitPos ushr 3) + i
                acc = (acc shl 8) or (if (byteIdx < limit) (src[byteIdx].toInt() and 0xFF) else 0)
            }
            val shift = 24 - (bitPos and 7) - codeBits
            bitPos += codeBits
            return (acc ushr shift) and ((1 shl codeBits) - 1)
        }

        val table = arrayOfNulls<ByteArray>(4096)
        for (i in 0..255) {
            table[i] = byteArrayOf(i.toByte())
        }
        var nextEntry = 258
        var codeSize = 9
        var oldCode = -1

        while (out.size() < expectedSize) {
            val code = nextCode(codeSize)
            if (code == 257) break // EOI
            if (code == 256) { // Clear
                for (i in 258 until 4096) table[i] = null
                nextEntry = 258
                codeSize = 9
                oldCode = nextCode(codeSize)
                if (oldCode == 257 || oldCode !in 0..255) break
                val first = table[oldCode] ?: break
                out.write(first)
            } else {
                val entry: ByteArray = if (code < nextEntry && table[code] != null) {
                    table[code]!!
                } else if (code == nextEntry && oldCode in 0 until nextEntry && table[oldCode] != null) {
                    val prev = table[oldCode]!!
                    val combined = ByteArray(prev.size + 1)
                    System.arraycopy(prev, 0, combined, 0, prev.size)
                    combined[prev.size] = prev[0]
                    combined
                } else {
                    break
                }
                out.write(entry)
                if (oldCode in 0 until nextEntry && table[oldCode] != null && nextEntry < 4096) {
                    val prev = table[oldCode]!!
                    val newSeq = ByteArray(prev.size + 1)
                    System.arraycopy(prev, 0, newSeq, 0, prev.size)
                    newSeq[prev.size] = entry[0]
                    table[nextEntry++] = newSeq
                    // TIFF LZW uses "early change" (increments code size at 511, 1023, 2047)
                    when (nextEntry) {
                        511 -> codeSize = 10
                        1023 -> codeSize = 11
                        2047 -> codeSize = 12
                    }
                }
                oldCode = code
            }
        }
        return out.toByteArray()
    }
}
