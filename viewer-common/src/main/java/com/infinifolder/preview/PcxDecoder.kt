package com.infinifolder.preview

import android.graphics.Bitmap
import com.infinifolder.preview.ImageDecodeUtils.createScaledBitmap
import com.infinifolder.preview.ImageDecodeUtils.isValidDimensions
import com.infinifolder.preview.ImageDecodeUtils.packArgb
import com.infinifolder.preview.ImageDecodeUtils.u16le
import com.infinifolder.preview.ImageDecodeUtils.u32le
import com.infinifolder.preview.ImageDecodeUtils.u8
import java.io.InputStream

/**
 * Decodes ZSoft PC Paintbrush (.pcx) and multi-page (.dcx) raster images.
 */
object PcxDecoder {

    val extensions: Set<String> = setOf("pcx", "dcx")

    fun decode(input: InputStream, targetPx: Int): Bitmap? = decode(input.readBytes(), targetPx)

    fun decode(bytes: ByteArray, targetPx: Int): Bitmap? {
        if (bytes.size >= 8 && u32le(bytes, 0) == 0x3ADE68B1L) {
            return decodeDcx(bytes, targetPx)
        }
        return decodePcxAt(bytes, 0, bytes.size, targetPx)
    }

    private fun decodeDcx(bytes: ByteArray, targetPx: Int): Bitmap? {
        // Multi-page PCX header: magic 0x3ADE68B1 followed by up to 1024 uint32 page offsets
        var firstOffset = 0
        var secondOffset = bytes.size
        var pos = 4
        while (pos + 4 <= bytes.size && pos < 4100) {
            val off = u32le(bytes, pos).toInt()
            if (off <= 0) break
            if (firstOffset == 0) {
                firstOffset = off
            } else if (off > firstOffset) {
                secondOffset = off.coerceAtMost(bytes.size)
                break
            }
            pos += 4
        }
        if (firstOffset !in 8 until bytes.size) return null
        return decodePcxAt(bytes, firstOffset, secondOffset, targetPx)
    }

    private fun decodePcxAt(bytes: ByteArray, startOffset: Int, endOffset: Int, targetPx: Int): Bitmap? {
        val len = endOffset - startOffset
        if (len < 128) return null
        val manufacturer = u8(bytes, startOffset)
        val version = u8(bytes, startOffset + 1)
        val encoding = u8(bytes, startOffset + 2)
        val bitsPerPlane = u8(bytes, startOffset + 3)
        if (manufacturer != 0x0A || (encoding != 0 && encoding != 1)) return null
        if (bitsPerPlane !in setOf(1, 2, 4, 8)) return null

        val xMin = u16le(bytes, startOffset + 4)
        val yMin = u16le(bytes, startOffset + 6)
        val xMax = u16le(bytes, startOffset + 8)
        val yMax = u16le(bytes, startOffset + 10)
        val width = xMax - xMin + 1
        val height = yMax - yMin + 1
        if (!isValidDimensions(width, height)) return null

        val nPlanes = u8(bytes, startOffset + 65)
        val bytesPerLine = u16le(bytes, startOffset + 66)
        if (nPlanes !in 1..4 || bytesPerLine <= 0) return null

        // Build 16-color header palette
        val palette = IntArray(256)
        for (i in 0 until 16) {
            val pOff = startOffset + 16 + i * 3
            palette[i] = packArgb(255, u8(bytes, pOff), u8(bytes, pOff + 1), u8(bytes, pOff + 2))
        }
        // Check for 256-color VGA palette at endOffset - 769 (marker byte 0x0C)
        var rleLimit = endOffset
        if (version >= 5 && bitsPerPlane == 8 && nPlanes == 1 && len >= 128 + 769) {
            val markerPos = endOffset - 769
            if (u8(bytes, markerPos) == 0x0C) {
                rleLimit = markerPos
                for (i in 0 until 256) {
                    val pOff = markerPos + 1 + i * 3
                    palette[i] = packArgb(255, u8(bytes, pOff), u8(bytes, pOff + 1), u8(bytes, pOff + 2))
                }
            } else {
                for (i in 0 until 256) palette[i] = packArgb(255, i, i, i)
            }
        }

        val scanlineStride = nPlanes * bytesPerLine
        val scanlineBuf = ByteArray(scanlineStride)
        val pixels = IntArray(width * height)
        var pos = startOffset + 128
        var runCount = 0
        var runVal: Byte = 0

        fun nextByte(): Byte {
            if (encoding == 0) {
                return if (pos < rleLimit) bytes[pos++] else 0
            }
            if (runCount > 0) {
                runCount--
                return runVal
            }
            if (pos >= rleLimit) return 0
            val b = u8(bytes, pos++)
            return if ((b and 0xC0) == 0xC0) {
                runCount = (b and 0x3F) - 1
                runVal = if (pos < rleLimit) bytes[pos++] else 0
                if (runCount < 0) runCount = 0
                runVal
            } else {
                b.toByte()
            }
        }

        for (y in 0 until height) {
            for (i in 0 until scanlineStride) {
                scanlineBuf[i] = nextByte()
            }
            val rowBase = y * width
            when {
                bitsPerPlane == 8 && nPlanes == 1 -> {
                    for (x in 0 until width.coerceAtMost(bytesPerLine)) {
                        val idx = scanlineBuf[x].toInt() and 0xFF
                        pixels[rowBase + x] = palette[idx]
                    }
                }
                bitsPerPlane == 8 && (nPlanes == 3 || nPlanes == 4) -> {
                    for (x in 0 until width.coerceAtMost(bytesPerLine)) {
                        val r = scanlineBuf[x].toInt() and 0xFF
                        val g = scanlineBuf[bytesPerLine + x].toInt() and 0xFF
                        val b = scanlineBuf[bytesPerLine * 2 + x].toInt() and 0xFF
                        val a = if (nPlanes == 4) (scanlineBuf[bytesPerLine * 3 + x].toInt() and 0xFF) else 255
                        pixels[rowBase + x] = packArgb(a, r, g, b)
                    }
                }
                bitsPerPlane == 1 && nPlanes in 1..4 -> {
                    for (x in 0 until width) {
                        val byteIdx = x ushr 3
                        if (byteIdx >= bytesPerLine) break
                        val bitShift = 7 - (x and 7)
                        var colorIdx = 0
                        for (p in 0 until nPlanes) {
                            val bit = ((scanlineBuf[p * bytesPerLine + byteIdx].toInt() and 0xFF) ushr bitShift) and 1
                            colorIdx = colorIdx or (bit shl p)
                        }
                        pixels[rowBase + x] = if (nPlanes == 1) {
                            val v = if (colorIdx == 1) 255 else 0
                            packArgb(255, v, v, v)
                        } else {
                            palette[colorIdx and 0x0F]
                        }
                    }
                }
                bitsPerPlane == 4 && nPlanes == 1 -> {
                    for (x in 0 until width) {
                        val byteIdx = x ushr 1
                        if (byteIdx >= bytesPerLine) break
                        val b = scanlineBuf[byteIdx].toInt() and 0xFF
                        val idx = if ((x and 1) == 0) (b ushr 4) else (b and 0x0F)
                        pixels[rowBase + x] = palette[idx]
                    }
                }
                bitsPerPlane == 2 && nPlanes == 1 -> {
                    for (x in 0 until width) {
                        val byteIdx = x ushr 2
                        if (byteIdx >= bytesPerLine) break
                        val b = scanlineBuf[byteIdx].toInt() and 0xFF
                        val shift = (3 - (x and 3)) * 2
                        val idx = (b ushr shift) and 0x03
                        pixels[rowBase + x] = palette[idx]
                    }
                }
            }
        }

        return createScaledBitmap(pixels, width, height, targetPx)
    }
}
