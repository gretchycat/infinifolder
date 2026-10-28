package com.infinifolder.preview

import android.graphics.Bitmap
import com.infinifolder.preview.ImageDecodeUtils.createScaledBitmap
import com.infinifolder.preview.ImageDecodeUtils.isValidDimensions
import com.infinifolder.preview.ImageDecodeUtils.packArgb
import com.infinifolder.preview.ImageDecodeUtils.u32be
import com.infinifolder.preview.ImageDecodeUtils.u8
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.InflaterInputStream
import kotlin.math.abs

/**
 * Minimal pure-Kotlin PNG decoder used as a fallback when Android's native BitmapFactory
 * is unavailable (e.g. inside local JVM unit tests with returnDefaultValues=true) or when
 * decoding embedded PNG streams in container formats (.ora, .kra, .icns).
 */
internal object PureJavaPngFallback {

    fun decode(bytes: ByteArray, targetPx: Int): Bitmap? {
        if (bytes.size < 33) return null
        if (u8(bytes, 0) != 0x89 || u8(bytes, 1) != 0x50 || u8(bytes, 2) != 0x4E || u8(bytes, 3) != 0x47) return null

        var pos = 8
        var width = 0
        var height = 0
        var bitDepth = 8
        var colorType = 6
        var palette = IntArray(0)
        val idatOut = ByteArrayOutputStream()

        while (pos + 8 <= bytes.size) {
            val len = u32be(bytes, pos).toInt()
            val type = String(bytes, pos + 4, 4, StandardCharsets.US_ASCII)
            pos += 8
            if (len < 0 || pos + len > bytes.size) break
            when (type) {
                "IHDR" -> {
                    if (len >= 13) {
                        width = u32be(bytes, pos).toInt()
                        height = u32be(bytes, pos + 4).toInt()
                        bitDepth = u8(bytes, pos + 8)
                        colorType = u8(bytes, pos + 9)
                    }
                }
                "PLTE" -> {
                    val count = len / 3
                    palette = IntArray(count) { i ->
                        packArgb(255, u8(bytes, pos + i * 3), u8(bytes, pos + i * 3 + 1), u8(bytes, pos + i * 3 + 2))
                    }
                }
                "IDAT" -> idatOut.write(bytes, pos, len)
                "IEND" -> break
            }
            pos += len + 4 // skip CRC
        }

        if (!isValidDimensions(width, height)) return null
        val compressed = idatOut.toByteArray()
        if (compressed.isEmpty()) return null
        val inflated = try {
            InflaterInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }
        } catch (_: Throwable) {
            return null
        }

        val samplesPerPixel = when (colorType) {
            0 -> 1
            2 -> 3
            3 -> 1
            4 -> 2
            6 -> 4
            else -> return null
        }
        val bpp = ((samplesPerPixel * bitDepth + 7) / 8).coerceAtLeast(1)
        val rowBytes = (width * samplesPerPixel * bitDepth + 7) / 8
        val pixels = IntArray(width * height)
        var prevRow = ByteArray(rowBytes)
        val curRow = ByteArray(rowBytes)
        var inPos = 0

        for (y in 0 until height) {
            if (inPos >= inflated.size) break
            val filter = inflated[inPos++].toInt() and 0xFF
            for (i in 0 until rowBytes) {
                val raw = if (inPos < inflated.size) (inflated[inPos++].toInt() and 0xFF) else 0
                val a = if (i >= bpp) (curRow[i - bpp].toInt() and 0xFF) else 0
                val b = prevRow[i].toInt() and 0xFF
                val c = if (i >= bpp) (prevRow[i - bpp].toInt() and 0xFF) else 0
                val recon = when (filter) {
                    1 -> (raw + a) and 0xFF
                    2 -> (raw + b) and 0xFF
                    3 -> (raw + ((a + b) ushr 1)) and 0xFF
                    4 -> {
                        val p = a + b - c
                        val pa = abs(p - a)
                        val pb = abs(p - b)
                        val pc = abs(p - c)
                        val pr = if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
                        (raw + pr) and 0xFF
                    }
                    else -> raw
                }
                curRow[i] = recon.toByte()
            }

            for (x in 0 until width) {
                val color = when (colorType) {
                    6 -> {
                        val step = if (bitDepth == 16) 8 else 4
                        val off = x * step
                        if (off + 3 < curRow.size) {
                            packArgb(
                                curRow[off + (if (bitDepth == 16) 6 else 3)].toInt() and 0xFF,
                                curRow[off].toInt() and 0xFF,
                                curRow[off + (if (bitDepth == 16) 2 else 1)].toInt() and 0xFF,
                                curRow[off + (if (bitDepth == 16) 4 else 2)].toInt() and 0xFF
                            )
                        } else 0xFF000000.toInt()
                    }
                    2 -> {
                        val step = if (bitDepth == 16) 6 else 3
                        val off = x * step
                        if (off + 2 < curRow.size) {
                            packArgb(
                                255,
                                curRow[off].toInt() and 0xFF,
                                curRow[off + (if (bitDepth == 16) 2 else 1)].toInt() and 0xFF,
                                curRow[off + (if (bitDepth == 16) 4 else 2)].toInt() and 0xFF
                            )
                        } else 0xFF000000.toInt()
                    }
                    0 -> {
                        val v = curRow[x.coerceAtMost(curRow.size - 1)].toInt() and 0xFF
                        packArgb(255, v, v, v)
                    }
                    4 -> {
                        val off = x * 2
                        val v = curRow[off.coerceAtMost(curRow.size - 1)].toInt() and 0xFF
                        val al = curRow[(off + 1).coerceAtMost(curRow.size - 1)].toInt() and 0xFF
                        packArgb(al, v, v, v)
                    }
                    3 -> {
                        val idx = curRow[x.coerceAtMost(curRow.size - 1)].toInt() and 0xFF
                        if (idx in palette.indices) palette[idx] else 0xFF000000.toInt()
                    }
                    else -> 0xFF000000.toInt()
                }
                pixels[y * width + x] = color
            }
            prevRow = curRow.copyOf()
        }

        return createScaledBitmap(pixels, width, height, targetPx)
    }
}
