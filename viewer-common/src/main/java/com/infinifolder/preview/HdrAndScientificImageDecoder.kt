package com.infinifolder.preview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.infinifolder.preview.ImageDecodeUtils.createScaledBitmap
import com.infinifolder.preview.ImageDecodeUtils.isValidDimensions
import com.infinifolder.preview.ImageDecodeUtils.packArgb
import com.infinifolder.preview.ImageDecodeUtils.s16be
import com.infinifolder.preview.ImageDecodeUtils.s32be
import com.infinifolder.preview.ImageDecodeUtils.s32le
import com.infinifolder.preview.ImageDecodeUtils.u16be
import com.infinifolder.preview.ImageDecodeUtils.u16le
import com.infinifolder.preview.ImageDecodeUtils.u32be
import com.infinifolder.preview.ImageDecodeUtils.u32le
import com.infinifolder.preview.ImageDecodeUtils.u8
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.InflaterInputStream
import kotlin.math.pow

/**
 * Decodes HDR, GPU Texture, and Scientific / Medical imaging formats:
 * - Radiance RGBE (.hdr)
 * - OpenEXR (.exr)
 * - DirectDraw Surface (.dds) & Khronos KTX (.ktx)
 * - Flexible Image Transport System (.fits, .fit, .fts)
 * - Digital Imaging and Communications in Medicine (.dcm, .dicom)
 */
object HdrAndScientificImageDecoder {

    val extensions: Set<String> = setOf("hdr", "exr", "dds", "ktx", "fits", "fit", "fts", "dcm", "dicom")

    fun decode(input: InputStream, targetPx: Int, extHint: String = ""): Bitmap? =
        decode(input.readBytes(), targetPx, extHint)

    fun decode(bytes: ByteArray, targetPx: Int, extHint: String = ""): Bitmap? {
        if (bytes.size < 8) return null
        if (bytes.size >= 10 && (String(bytes, 0, 10, StandardCharsets.US_ASCII).startsWith("#?RADIANCE") ||
                String(bytes, 0, 6, StandardCharsets.US_ASCII) == "#?RGBE")
        ) {
            return decodeRadianceHdr(bytes, targetPx)
        }
        if (u32le(bytes, 0) == 0x01312F76L) {
            return decodeOpenExr(bytes, targetPx)
        }
        if (String(bytes, 0, 4, StandardCharsets.US_ASCII) == "DDS ") {
            return decodeDds(bytes, targetPx)
        }
        if (bytes.size >= 12 && u8(bytes, 0) == 0xAB && String(bytes, 1, 6, StandardCharsets.US_ASCII) == "KTX 11") {
            return decodeKtx(bytes, targetPx)
        }
        if (bytes.size >= 80 && String(bytes, 0, 9, StandardCharsets.US_ASCII) == "SIMPLE  =") {
            return decodeFits(bytes, targetPx)
        }
        if ((bytes.size >= 132 && String(bytes, 128, 4, StandardCharsets.US_ASCII) == "DICM") || extHint in setOf("dcm", "dicom")) {
            return decodeDicom(bytes, targetPx)
        }
        return null
    }

    private fun toneMapFloat(v: Float): Int {
        if (v.isNaN() || v <= 0f) return 0
        if (v.isInfinite()) return 255
        val mapped = v / (1f + v)
        return (mapped.pow(1f / 2.2f) * 255f).toInt().coerceIn(0, 255)
    }

    private fun halfToFloat(hbits: Int): Float {
        var mant = hbits and 0x03FF
        var exp = hbits and 0x7C00
        if (exp == 0x7C00) {
            exp = 0x3FC00
        } else if (exp != 0) {
            exp += 0x1C000
            if (mant == 0 && exp > 0x1C400) {
                return Float.fromBits(((hbits and 0x8000) shl 16) or (exp shl 13))
            }
        } else if (mant != 0) {
            exp = 0x1C400
            do {
                mant = mant shl 1
                exp -= 0x400
            } while ((mant and 0x0400) == 0)
            mant = mant and 0x03FF
        }
        return Float.fromBits(((hbits and 0x8000) shl 16) or ((exp or mant) shl 13))
    }

    // =========================================================================
    // 1. Radiance RGBE (.hdr)
    // =========================================================================
    fun decodeRadianceHdr(bytes: ByteArray, targetPx: Int): Bitmap? {
        var pos = 0
        // Read ASCII header lines until an empty line
        while (pos < bytes.size) {
            val start = pos
            while (pos < bytes.size && bytes[pos].toInt() != '\n'.code) pos++
            val line = String(bytes, start, pos - start, StandardCharsets.US_ASCII).trim()
            if (pos < bytes.size && bytes[pos].toInt() == '\n'.code) pos++
            if (line.isEmpty()) break
        }
        // Next line is resolution string: e.g. "-Y 192 +X 192"
        val resStart = pos
        while (pos < bytes.size && bytes[pos].toInt() != '\n'.code) pos++
        val resLine = String(bytes, resStart, pos - resStart, StandardCharsets.US_ASCII).trim()
        if (pos < bytes.size && bytes[pos].toInt() == '\n'.code) pos++

        val resMatch = Regex("""[-+]Y\s+(\d+)\s+[-+]X\s+(\d+)""").find(resLine) ?: return null
        val height = resMatch.groupValues[1].toIntOrNull() ?: return null
        val width = resMatch.groupValues[2].toIntOrNull() ?: return null
        if (!isValidDimensions(width, height)) return null

        val pixels = IntArray(width * height)
        val scanline = ByteArray(width * 4)

        for (y in 0 until height) {
            if (pos + 4 > bytes.size) break
            val b0 = u8(bytes, pos)
            val b1 = u8(bytes, pos + 1)
            val b2 = u8(bytes, pos + 2)
            val b3 = u8(bytes, pos + 3)

            if (b0 == 2 && b1 == 2 && (b2 and 0x80) == 0 && ((b2 shl 8) or b3) == width) {
                // Adaptive scanline RLE (4 separate channel planes per scanline)
                pos += 4
                for (ch in 0 until 4) {
                    var x = 0
                    while (x < width && pos < bytes.size) {
                        val code = u8(bytes, pos++)
                        if (code > 128) {
                            val run = (code - 128).coerceAtMost(width - x)
                            val v = if (pos < bytes.size) bytes[pos++] else 0
                            for (i in 0 until run) {
                                scanline[(x++) * 4 + ch] = v
                            }
                        } else {
                            val run = code.coerceAtMost(width - x)
                            for (i in 0 until run) {
                                scanline[(x++) * 4 + ch] = if (pos < bytes.size) bytes[pos++] else 0
                            }
                        }
                    }
                }
            } else {
                // Uncompressed RGBE scanline
                for (i in 0 until width * 4) {
                    scanline[i] = if (pos < bytes.size) bytes[pos++] else 0
                }
            }

            val rowBase = y * width
            for (x in 0 until width) {
                val off = x * 4
                val rMant = scanline[off].toInt() and 0xFF
                val gMant = scanline[off + 1].toInt() and 0xFF
                val bMant = scanline[off + 2].toInt() and 0xFF
                val exp = scanline[off + 3].toInt() and 0xFF
                if (exp == 0) {
                    pixels[rowBase + x] = 0xFF000000.toInt()
                } else {
                    val scale = 2.0f.pow(exp - 128 - 8)
                    val r = toneMapFloat((rMant + 0.5f) * scale)
                    val g = toneMapFloat((gMant + 0.5f) * scale)
                    val b = toneMapFloat((bMant + 0.5f) * scale)
                    pixels[rowBase + x] = packArgb(255, r, g, b)
                }
            }
        }

        return createScaledBitmap(pixels, width, height, targetPx)
    }

    // =========================================================================
    // 2. OpenEXR (.exr)
    // =========================================================================
    private data class ExrChannel(val name: String, val pixelType: Int) // 0=UINT, 1=HALF, 2=FLOAT

    fun decodeOpenExr(bytes: ByteArray, targetPx: Int): Bitmap? {
        if (bytes.size < 32 || u32le(bytes, 0) != 0x01312F76L) return null
        val versionAndFlags = u32le(bytes, 4).toInt()
        if ((versionAndFlags and 0x200) != 0) return null // Tiled EXR not handled here; scanline EXR supported

        var pos = 8
        var compression = 0 // 0=NO_COMPRESSION, 1=RLE, 2=ZIPS, 3=ZIP
        var xMin = 0
        var yMin = 0
        var xMax = -1
        var yMax = -1
        val channels = ArrayList<ExrChannel>()

        fun readCString(): String {
            val start = pos
            while (pos < bytes.size && bytes[pos].toInt() != 0) pos++
            val s = String(bytes, start, pos - start, StandardCharsets.US_ASCII)
            if (pos < bytes.size) pos++
            return s
        }

        while (pos < bytes.size) {
            val attrName = readCString()
            if (attrName.isEmpty()) break // End of header
            val attrType = readCString()
            if (pos + 4 > bytes.size) return null
            val attrSize = s32le(bytes, pos)
            pos += 4
            if (attrSize < 0 || pos + attrSize > bytes.size) return null
            val attrEnd = pos + attrSize

            when (attrName) {
                "compression" -> if (attrSize >= 1) compression = u8(bytes, pos)
                "dataWindow" -> if (attrSize >= 16) {
                    xMin = s32le(bytes, pos)
                    yMin = s32le(bytes, pos + 4)
                    xMax = s32le(bytes, pos + 8)
                    yMax = s32le(bytes, pos + 12)
                }
                "channels" -> {
                    while (pos < attrEnd) {
                        val chName = readCString()
                        if (chName.isEmpty() || pos + 16 > attrEnd) break
                        val pType = s32le(bytes, pos)
                        channels.add(ExrChannel(chName, pType))
                        pos += 16
                    }
                }
            }
            pos = attrEnd
        }

        val width = xMax - xMin + 1
        val height = yMax - yMin + 1
        if (!isValidDimensions(width, height) || channels.isEmpty()) return null

        val linesPerBlock = when (compression) {
            0, 1, 2 -> 1
            3 -> 16
            else -> return null
        }
        val numBlocks = (height + linesPerBlock - 1) / linesPerBlock
        val offsetTableStart = pos
        if (offsetTableStart + numBlocks * 8 > bytes.size) return null

        val bytesPerPixelAllChannels = channels.sumOf { if (it.pixelType == 1) 2 else 4 }
        val pixels = IntArray(width * height) { 0xFF000000.toInt() }

        for (bIdx in 0 until numBlocks) {
            val chunkOffset = u32le(bytes, offsetTableStart + bIdx * 8).toInt()
            if (chunkOffset <= 0 || chunkOffset + 8 > bytes.size) continue
            val chunkY = s32le(bytes, chunkOffset) - yMin
            val packedSize = s32le(bytes, chunkOffset + 4)
            val dataStart = chunkOffset + 8
            if (packedSize < 0 || dataStart + packedSize > bytes.size) continue

            val rowsInBlock = minOf(linesPerBlock, height - chunkY).coerceAtLeast(0)
            val expectedBytes = rowsInBlock * width * bytesPerPixelAllChannels
            val blockData = when (compression) {
                0 -> bytes.copyOfRange(dataStart, dataStart + packedSize)
                2, 3 -> {
                    if (packedSize == expectedBytes) {
                        bytes.copyOfRange(dataStart, dataStart + packedSize)
                    } else {
                        val inflated = try {
                            InflaterInputStream(ByteArrayInputStream(bytes, dataStart, packedSize)).use { it.readBytes() }
                        } catch (_: Throwable) {
                            null
                        } ?: continue
                        reconstructExrZipPredictorAndInterleave(inflated)
                    }
                }
                else -> continue
            }

            var blockPos = 0
            for (ry in 0 until rowsInBlock) {
                val y = chunkY + ry
                if (y !in 0 until height) break
                val rRow = FloatArray(width)
                val gRow = FloatArray(width)
                val bRow = FloatArray(width)
                val aRow = FloatArray(width) { 1f }
                var hasRGB = false

                for (ch in channels) {
                    val step = if (ch.pixelType == 1) 2 else 4
                    val targetArr = when (ch.name.uppercase()) {
                        "R" -> { hasRGB = true; rRow }
                        "G" -> { hasRGB = true; gRow }
                        "B" -> { hasRGB = true; bRow }
                        "Y" -> rRow
                        "A" -> aRow
                        else -> null
                    }
                    for (x in 0 until width) {
                        if (blockPos + step > blockData.size) break
                        if (targetArr != null) {
                            val v = when (ch.pixelType) {
                                1 -> halfToFloat(u16le(blockData, blockPos))
                                2 -> Float.fromBits(s32le(blockData, blockPos))
                                else -> u32le(blockData, blockPos).toFloat()
                            }
                            targetArr[x] = v
                        }
                        blockPos += step
                    }
                }

                val rowBase = y * width
                for (x in 0 until width) {
                    val r = toneMapFloat(rRow[x])
                    val g = if (hasRGB) toneMapFloat(gRow[x]) else r
                    val b = if (hasRGB) toneMapFloat(bRow[x]) else r
                    val a = (aRow[x].coerceIn(0f, 1f) * 255f).toInt()
                    pixels[rowBase + x] = packArgb(a, r, g, b)
                }
            }
        }

        return createScaledBitmap(pixels, width, height, targetPx)
    }

    private fun reconstructExrZipPredictorAndInterleave(data: ByteArray): ByteArray {
        if (data.size < 2) return data
        // 1. Undo delta predictor (offset by 128)
        for (i in 1 until data.size) {
            val d = (data[i - 1].toInt() and 0xFF) + (data[i].toInt() and 0xFF) - 128
            data[i] = (d and 0xFF).toByte()
        }
        // 2. Interleave first half (even bytes) and second half (odd bytes)
        val out = ByteArray(data.size)
        val half = (data.size + 1) / 2
        var i1 = 0
        var i2 = half
        var dst = 0
        while (dst < out.size) {
            if (i1 < half) out[dst++] = data[i1++]
            if (i2 < data.size && dst < out.size) out[dst++] = data[i2++]
        }
        return out
    }

    // =========================================================================
    // 3. DirectDraw Surface (.dds) & Khronos KTX (.ktx)
    // =========================================================================
    fun decodeDds(bytes: ByteArray, targetPx: Int): Bitmap? {
        if (bytes.size < 128 || String(bytes, 0, 4, StandardCharsets.US_ASCII) != "DDS ") return null
        val height = s32le(bytes, 12)
        val width = s32le(bytes, 16)
        if (!isValidDimensions(width, height)) return null

        val pfFlags = s32le(bytes, 80)
        val fourCC = String(bytes, 84, 4, StandardCharsets.US_ASCII)
        val rgbBitCount = s32le(bytes, 88)
        val rMask = u32le(bytes, 92)
        val gMask = u32le(bytes, 96)
        val bMask = u32le(bytes, 100)
        val aMask = u32le(bytes, 104)

        val dataOffset = if ((pfFlags and 0x4) != 0 && fourCC == "DX10") 148 else 128
        if (dataOffset >= bytes.size) return null

        val pixels = IntArray(width * height)
        if ((pfFlags and 0x4) != 0) {
            when (fourCC) {
                "DXT1" -> decodeDxtBlocks(bytes, dataOffset, width, height, 1, pixels)
                "DXT2", "DXT3" -> decodeDxtBlocks(bytes, dataOffset, width, height, 3, pixels)
                "DXT4", "DXT5" -> decodeDxtBlocks(bytes, dataOffset, width, height, 5, pixels)
                else -> return null
            }
        } else if ((pfFlags and 0x40) != 0 && rgbBitCount in setOf(16, 24, 32)) {
            val step = rgbBitCount / 8
            val rShift = java.lang.Long.numberOfTrailingZeros(rMask).takeIf { it < 64 } ?: 16
            val gShift = java.lang.Long.numberOfTrailingZeros(gMask).takeIf { it < 64 } ?: 8
            val bShift = java.lang.Long.numberOfTrailingZeros(bMask).takeIf { it < 64 } ?: 0
            val aShift = java.lang.Long.numberOfTrailingZeros(aMask).takeIf { it < 64 } ?: 24
            var pos = dataOffset
            for (i in 0 until width * height) {
                if (pos + step > bytes.size) break
                val raw = when (step) {
                    4 -> u32le(bytes, pos)
                    3 -> (u8(bytes, pos).toLong()) or (u8(bytes, pos + 1).toLong() shl 8) or (u8(bytes, pos + 2).toLong() shl 16)
                    else -> u16le(bytes, pos).toLong()
                }
                pos += step
                val r = ((raw and rMask) ushr rShift).toInt() and 0xFF
                val g = ((raw and gMask) ushr gShift).toInt() and 0xFF
                val b = ((raw and bMask) ushr bShift).toInt() and 0xFF
                val a = if (aMask != 0L) (((raw and aMask) ushr aShift).toInt() and 0xFF) else 255
                pixels[i] = packArgb(a, r, g, b)
            }
        } else {
            return null
        }

        return createScaledBitmap(pixels, width, height, targetPx)
    }

    private fun decodeDxtBlocks(bytes: ByteArray, startOffset: Int, width: Int, height: Int, dxtVersion: Int, pixels: IntArray) {
        val blocksX = (width + 3) / 4
        val blocksY = (height + 3) / 4
        val blockSize = if (dxtVersion == 1) 8 else 16
        var pos = startOffset
        val colors = IntArray(4)
        val alphas = IntArray(16) { 255 }

        for (by in 0 until blocksY) {
            for (bx in 0 until blocksX) {
                if (pos + blockSize > bytes.size) return
                var colorOffset = pos
                if (dxtVersion == 3) {
                    for (i in 0 until 8) {
                        val b = u8(bytes, pos + i)
                        alphas[i * 2] = (b and 0x0F) * 17
                        alphas[i * 2 + 1] = (b ushr 4) * 17
                    }
                    colorOffset += 8
                } else if (dxtVersion == 5) {
                    val a0 = u8(bytes, pos)
                    val a1 = u8(bytes, pos + 1)
                    val aLut = IntArray(8)
                    aLut[0] = a0
                    aLut[1] = a1
                    if (a0 > a1) {
                        for (k in 1..6) aLut[k + 1] = ((7 - k) * a0 + k * a1 + 3) / 7
                    } else {
                        for (k in 1..4) aLut[k + 1] = ((5 - k) * a0 + k * a1 + 2) / 5
                        aLut[6] = 0
                        aLut[7] = 255
                    }
                    var bits = 0L
                    for (i in 0 until 6) {
                        bits = bits or ((u8(bytes, pos + 2 + i).toLong()) shl (i * 8))
                    }
                    for (i in 0 until 16) {
                        val idx = ((bits ushr (i * 3)) and 0x07L).toInt()
                        alphas[i] = aLut[idx]
                    }
                    colorOffset += 8
                }

                val c0 = u16le(bytes, colorOffset)
                val c1 = u16le(bytes, colorOffset + 2)
                val r0 = ((c0 ushr 11) and 0x1F) * 255 / 31
                val g0 = ((c0 ushr 5) and 0x3F) * 255 / 63
                val b0 = (c0 and 0x1F) * 255 / 31
                val r1 = ((c1 ushr 11) and 0x1F) * 255 / 31
                val g1 = ((c1 ushr 5) and 0x3F) * 255 / 63
                val b1 = (c1 and 0x1F) * 255 / 31

                colors[0] = packArgb(255, r0, g0, b0)
                colors[1] = packArgb(255, r1, g1, b1)
                if (c0 > c1 || dxtVersion != 1) {
                    colors[2] = packArgb(255, (2 * r0 + r1 + 1) / 3, (2 * g0 + g1 + 1) / 3, (2 * b0 + b1 + 1) / 3)
                    colors[3] = packArgb(255, (r0 + 2 * r1 + 1) / 3, (g0 + 2 * g1 + 1) / 3, (b0 + 2 * b1 + 1) / 3)
                } else {
                    colors[2] = packArgb(255, (r0 + r1) / 2, (g0 + g1) / 2, (b0 + b1) / 2)
                    colors[3] = 0x00000000
                }

                val lookup = u32le(bytes, colorOffset + 4)
                for (py in 0 until 4) {
                    val dstY = by * 4 + py
                    if (dstY >= height) continue
                    for (px in 0 until 4) {
                        val dstX = bx * 4 + px
                        if (dstX >= width) continue
                        val pIdx = py * 4 + px
                        val cIdx = ((lookup ushr (pIdx * 2)) and 0x03L).toInt()
                        val rgb = colors[cIdx]
                        val alpha = if (dxtVersion == 1) ((rgb ushr 24) and 0xFF) else alphas[pIdx]
                        pixels[dstY * width + dstX] = (rgb and 0x00FFFFFF) or (alpha shl 24)
                    }
                }
                pos += blockSize
            }
        }
    }

    fun decodeKtx(bytes: ByteArray, targetPx: Int): Bitmap? {
        if (bytes.size < 68 || u8(bytes, 0) != 0xAB || String(bytes, 1, 6, StandardCharsets.US_ASCII) != "KTX 11") return null
        val le = u32le(bytes, 12) == 0x04030201L
        fun r32(off: Int): Int = if (le) s32le(bytes, off) else s32be(bytes, off)

        val glFormat = r32(24)
        val glInternalFormat = r32(28)
        val width = r32(36)
        val height = r32(40).coerceAtLeast(1)
        val bytesOfKeyValueData = r32(60).coerceAtLeast(0)
        if (!isValidDimensions(width, height)) return null

        val imageSizeOffset = 64 + bytesOfKeyValueData
        if (imageSizeOffset + 4 > bytes.size) return null
        val dataStart = imageSizeOffset + 4
        if (dataStart >= bytes.size) return null

        val pixels = IntArray(width * height)
        when {
            glFormat == 0x1908 || glInternalFormat == 0x8058 -> { // GL_RGBA / GL_RGBA8
                val rowStride = (width * 4 + 3) and 3.inv()
                for (y in 0 until height) {
                    val rowOff = dataStart + y * rowStride
                    for (x in 0 until width) {
                        val p = rowOff + x * 4
                        if (p + 4 > bytes.size) break
                        pixels[y * width + x] = packArgb(u8(bytes, p + 3), u8(bytes, p), u8(bytes, p + 1), u8(bytes, p + 2))
                    }
                }
            }
            glFormat == 0x1907 || glInternalFormat == 0x8051 -> { // GL_RGB / GL_RGB8
                val rowStride = (width * 3 + 3) and 3.inv()
                for (y in 0 until height) {
                    val rowOff = dataStart + y * rowStride
                    for (x in 0 until width) {
                        val p = rowOff + x * 3
                        if (p + 3 > bytes.size) break
                        pixels[y * width + x] = packArgb(255, u8(bytes, p), u8(bytes, p + 1), u8(bytes, p + 2))
                    }
                }
            }
            else -> return null
        }

        return createScaledBitmap(pixels, width, height, targetPx)
    }

    // =========================================================================
    // 4. Astronomy FITS (.fits, .fit, .fts)
    // =========================================================================
    fun decodeFits(bytes: ByteArray, targetPx: Int): Bitmap? {
        if (bytes.size < 2880 || String(bytes, 0, 9, StandardCharsets.US_ASCII) != "SIMPLE  =") return null
        var bitpix = 8
        var naxis = 0
        var width = 0
        var height = 0
        var depth = 1
        var bscale = 1.0
        var bzero = 0.0

        var pos = 0
        var headerEnded = false
        while (pos + 80 <= bytes.size && !headerEnded) {
            val card = String(bytes, pos, 80, StandardCharsets.US_ASCII)
            pos += 80
            val key = card.substring(0, 8).trim()
            if (key == "END") {
                headerEnded = true
                break
            }
            if (card.length >= 10 && card[8] == '=' && card[9] == ' ') {
                val valPart = card.substring(10).substringBefore('/').trim()
                when (key) {
                    "BITPIX" -> bitpix = valPart.toIntOrNull() ?: 8
                    "NAXIS" -> naxis = valPart.toIntOrNull() ?: 0
                    "NAXIS1" -> width = valPart.toIntOrNull() ?: 0
                    "NAXIS2" -> height = valPart.toIntOrNull() ?: 0
                    "NAXIS3" -> depth = valPart.toIntOrNull() ?: 1
                    "BSCALE" -> bscale = valPart.toDoubleOrNull() ?: 1.0
                    "BZERO" -> bzero = valPart.toDoubleOrNull() ?: 0.0
                }
            }
        }

        if (!headerEnded || naxis < 2 || !isValidDimensions(width, height)) return null
        // Advance pos to next 2880-byte FITS block boundary
        val dataStart = ((pos + 2879) / 2880) * 2880
        if (dataStart >= bytes.size) return null

        val planePixels = width * height
        val channels = if (naxis >= 3 && depth >= 3) 3 else 1
        val samples = DoubleArray(planePixels * channels)
        var dPos = dataStart
        var minVal = Double.POSITIVE_INFINITY
        var maxVal = Double.NEGATIVE_INFINITY

        for (i in samples.indices) {
            val raw: Double = when (bitpix) {
                8 -> if (dPos < bytes.size) u8(bytes, dPos++).toDouble() else 0.0
                16 -> if (dPos + 2 <= bytes.size) { val v = s16be(bytes, dPos).toDouble(); dPos += 2; v } else 0.0
                32 -> if (dPos + 4 <= bytes.size) { val v = s32be(bytes, dPos).toDouble(); dPos += 4; v } else 0.0
                -32 -> if (dPos + 4 <= bytes.size) { val v = Float.fromBits(s32be(bytes, dPos)).toDouble(); dPos += 4; v } else 0.0
                -64 -> if (dPos + 8 <= bytes.size) {
                    val bits = (u32be(bytes, dPos) shl 32) or u32be(bytes, dPos + 4)
                    dPos += 8
                    Double.fromBits(bits)
                } else 0.0
                else -> return null
            }
            val phys = if (raw.isNaN() || raw.isInfinite()) 0.0 else (bzero + bscale * raw)
            samples[i] = phys
            if (phys < minVal) minVal = phys
            if (phys > maxVal) maxVal = phys
        }

        val span = if (maxVal > minVal) (maxVal - minVal) else 1.0
        val pixels = IntArray(planePixels)
        // FITS stores row 0 at bottom by convention; flip vertically so top row is at top
        for (y in 0 until height) {
            val srcY = height - 1 - y
            for (x in 0 until width) {
                val pIdx = srcY * width + x
                if (channels == 3) {
                    val r = (((samples[pIdx] - minVal) / span) * 255.0).toInt().coerceIn(0, 255)
                    val g = (((samples[planePixels + pIdx] - minVal) / span) * 255.0).toInt().coerceIn(0, 255)
                    val b = (((samples[planePixels * 2 + pIdx] - minVal) / span) * 255.0).toInt().coerceIn(0, 255)
                    pixels[y * width + x] = packArgb(255, r, g, b)
                } else {
                    val v = (((samples[pIdx] - minVal) / span) * 255.0).toInt().coerceIn(0, 255)
                    pixels[y * width + x] = packArgb(255, v, v, v)
                }
            }
        }

        return createScaledBitmap(pixels, width, height, targetPx)
    }

    // =========================================================================
    // 5. Medical DICOM (.dcm, .dicom)
    // =========================================================================
    fun decodeDicom(bytes: ByteArray, targetPx: Int): Bitmap? {
        var pos = if (bytes.size >= 132 && String(bytes, 128, 4, StandardCharsets.US_ASCII) == "DICM") 132 else 0
        var rows = 0
        var cols = 0
        var samplesPerPixel = 1
        var bitsAllocated = 8
        var photometric = "MONOCHROME2"
        var pixelDataOffset = -1
        var pixelDataLength = 0

        val longVRs = setOf("OB", "OD", "OF", "OL", "OW", "SQ", "UC", "UR", "UT", "UN")

        while (pos + 8 <= bytes.size) {
            val group = u16le(bytes, pos)
            val elem = u16le(bytes, pos + 2)
            val vrCand = String(bytes, pos + 4, 2, StandardCharsets.US_ASCII)
            val isExplicitVr = vrCand.all { it in 'A'..'Z' }

            val valLen: Int
            val valPos: Int
            if (isExplicitVr) {
                if (vrCand in longVRs) {
                    if (pos + 12 > bytes.size) break
                    valLen = s32le(bytes, pos + 8)
                    valPos = pos + 12
                } else {
                    valLen = u16le(bytes, pos + 6)
                    valPos = pos + 8
                }
            } else {
                valLen = s32le(bytes, pos + 4)
                valPos = pos + 8
            }

            if (group == 0x7FE0 && elem == 0x0010) {
                pixelDataOffset = valPos
                pixelDataLength = if (valLen < 0) (bytes.size - valPos) else valLen.coerceAtMost(bytes.size - valPos)
                break
            }

            if (valLen < 0 || valPos + valLen > bytes.size) break
            when (group) {
                0x0028 -> when (elem) {
                    0x0002 -> if (valLen >= 2) samplesPerPixel = u16le(bytes, valPos)
                    0x0004 -> photometric = String(bytes, valPos, valLen, StandardCharsets.US_ASCII).trim()
                    0x0010 -> if (valLen >= 2) rows = u16le(bytes, valPos)
                    0x0011 -> if (valLen >= 2) cols = u16le(bytes, valPos)
                    0x0100 -> if (valLen >= 2) bitsAllocated = u16le(bytes, valPos)
                }
            }
            pos = valPos + valLen
        }

        if (!isValidDimensions(cols, rows) || pixelDataOffset < 0) return null

        // Check for encapsulated JPEG/PNG inside PixelData
        for (scan in pixelDataOffset until (pixelDataOffset + 64).coerceAtMost(bytes.size - 4)) {
            if (u8(bytes, scan) == 0xFF && u8(bytes, scan + 1) == 0xD8 && u8(bytes, scan + 2) == 0xFF) {
                BitmapFactory.decodeByteArray(bytes, scan, bytes.size - scan)?.let { return it }
            }
        }

        val totalPixels = cols * rows
        val pixels = IntArray(totalPixels)
        var dPos = pixelDataOffset

        if (samplesPerPixel >= 3 && bitsAllocated == 8) {
            for (i in 0 until totalPixels) {
                if (dPos + 3 > bytes.size) break
                val r = u8(bytes, dPos)
                val g = u8(bytes, dPos + 1)
                val b = u8(bytes, dPos + 2)
                dPos += samplesPerPixel
                pixels[i] = packArgb(255, r, g, b)
            }
        } else if (bitsAllocated == 16) {
            val raw = IntArray(totalPixels)
            var minV = Int.MAX_VALUE
            var maxV = Int.MIN_VALUE
            for (i in 0 until totalPixels) {
                if (dPos + 2 > bytes.size) break
                val v = u16le(bytes, dPos)
                dPos += 2
                raw[i] = v
                if (v < minV) minV = v
                if (v > maxV) maxV = v
            }
            val span = (maxV - minV).coerceAtLeast(1)
            val invert = photometric.startsWith("MONOCHROME1")
            for (i in 0 until totalPixels) {
                var v = ((raw[i] - minV) * 255) / span
                if (invert) v = 255 - v
                pixels[i] = packArgb(255, v, v, v)
            }
        } else {
            val invert = photometric.startsWith("MONOCHROME1")
            for (i in 0 until totalPixels) {
                if (dPos >= bytes.size) break
                var v = u8(bytes, dPos++)
                if (invert) v = 255 - v
                pixels[i] = packArgb(255, v, v, v)
            }
        }

        return createScaledBitmap(pixels, cols, rows, targetPx)
    }
}
