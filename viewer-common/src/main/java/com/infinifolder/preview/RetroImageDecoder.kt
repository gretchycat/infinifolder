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
import com.infinifolder.preview.ImageDecodeUtils.u8
import java.io.InputStream
import java.nio.charset.StandardCharsets
import kotlin.math.max

/**
 * Decodes classic workstation, home-computer, terminal, and mobile raster image formats:
 * - Amiga IFF / ILBM / PBM (.iff, .ilbm, .lbm)
 * - Atari ST Neochrome (.neo) & DEGAS (.pi1, .pi2, .pi3, .pc1, .pc2, .pc3)
 * - ZX Spectrum Screen (.scr)
 * - Commodore 64 KoalaPainter (.kla, .koa)
 * - Apple ICNS (.icns), MacPaint (.pntg, .mac), and QuickDraw PICT (.pct, .pict)
 * - Sun Rasterfile (.ras, .sun) & Silicon Graphics RGB (.sgi, .rgb, .bw, .rgba)
 * - Palm Database ImageViewer (.pdb) & Nokia OTA Bitmap (.otb)
 * - DEC Sixel Graphics (.sixel, .six)
 */
object RetroImageDecoder {

    val extensions: Set<String> = setOf(
        "iff", "ilbm", "lbm",
        "neo", "pi1", "pi2", "pi3", "pc1", "pc2", "pc3",
        "scr", "kla", "koa",
        "icns", "pntg", "mac", "pct", "pict",
        "ras", "sun", "sgi", "rgb", "bw", "rgba",
        "pdb", "otb", "sixel", "six"
    )

    fun decode(input: InputStream, targetPx: Int, extHint: String = ""): Bitmap? =
        decode(input.readBytes(), targetPx, extHint)

    fun decode(bytes: ByteArray, targetPx: Int, extHint: String = ""): Bitmap? {
        if (bytes.isEmpty()) return null
        val ext = extHint.lowercase()

        // 1. Magic-byte dispatch
        if (bytes.size >= 12 && String(bytes, 0, 4, StandardCharsets.US_ASCII) == "FORM") {
            val formType = String(bytes, 8, 4, StandardCharsets.US_ASCII)
            if (formType == "ILBM" || formType == "PBM ") {
                return decodeAmigaIlbm(bytes, targetPx)
            }
        }
        if (bytes.size >= 8 && String(bytes, 0, 4, StandardCharsets.US_ASCII) == "icns") {
            return decodeAppleIcns(bytes, targetPx)
        }
        if (bytes.size >= 32 && u32be(bytes, 0) == 0x59A66A95L) {
            return decodeSunRaster(bytes, targetPx)
        }
        if (bytes.size >= 512 && u16be(bytes, 0) == 0x01DA) {
            return decodeSgiRgb(bytes, targetPx)
        }
        if (bytes.size >= 78 && String(bytes, 60, 8, StandardCharsets.US_ASCII) == "vIMGView") {
            return decodePalmPdb(bytes, targetPx)
        }
        if (looksLikeSixel(bytes)) {
            return decodeSixel(bytes, targetPx)
        }

        // 2. Extension-guided dispatch for headerless / fixed-size retro formats
        return when (ext) {
            "iff", "ilbm", "lbm" -> decodeAmigaIlbm(bytes, targetPx)
            "icns" -> decodeAppleIcns(bytes, targetPx)
            "ras", "sun" -> decodeSunRaster(bytes, targetPx)
            "sgi", "rgb", "bw", "rgba" -> decodeSgiRgb(bytes, targetPx)
            "neo" -> decodeAtariNeo(bytes, targetPx)
            "pi1", "pi2", "pi3", "pc1", "pc2", "pc3" -> decodeAtariDegas(bytes, ext, targetPx)
            "scr" -> decodeZxSpectrumScr(bytes, targetPx)
            "kla", "koa" -> decodeC64Koala(bytes, targetPx)
            "pntg", "mac" -> decodeMacPaint(bytes, targetPx)
            "pct", "pict" -> decodeQuickDrawPict(bytes, targetPx)
            "pdb" -> decodePalmPdb(bytes, targetPx)
            "otb" -> decodeNokiaOtb(bytes, targetPx)
            "sixel", "six" -> decodeSixel(bytes, targetPx)
            else -> {
                // Size-based heuristics when extension is unknown
                when (bytes.size) {
                    6912 -> decodeZxSpectrumScr(bytes, targetPx)
                    10003, 10000 -> decodeC64Koala(bytes, targetPx)
                    32128 -> decodeAtariNeo(bytes, targetPx)
                    32034, 32066 -> decodeAtariDegas(bytes, "pi1", targetPx)
                    else -> decodeQuickDrawPict(bytes, targetPx)
                }
            }
        }
    }

    // =========================================================================
    // 1. Amiga IFF / ILBM / PBM
    // =========================================================================
    fun decodeAmigaIlbm(bytes: ByteArray, targetPx: Int): Bitmap? {
        if (bytes.size < 12 || String(bytes, 0, 4, StandardCharsets.US_ASCII) != "FORM") return null
        val formType = String(bytes, 8, 4, StandardCharsets.US_ASCII)
        if (formType != "ILBM" && formType != "PBM ") return null

        var width = 0
        var height = 0
        var nPlanes = 0
        var masking = 0
        var compression = 0
        var camgMode = 0L
        var palette = IntArray(256) { i -> packArgb(255, i, i, i) }
        var bodyOffset = -1
        var bodyLen = 0

        var pos = 12
        while (pos + 8 <= bytes.size) {
            val tag = String(bytes, pos, 4, StandardCharsets.US_ASCII)
            val len = u32be(bytes, pos + 4).toInt()
            val dataStart = pos + 8
            if (len < 0 || dataStart + len > bytes.size) break
            when (tag) {
                "BMHD" -> {
                    if (len >= 20) {
                        width = u16be(bytes, dataStart)
                        height = u16be(bytes, dataStart + 2)
                        nPlanes = u8(bytes, dataStart + 8)
                        masking = u8(bytes, dataStart + 9)
                        compression = u8(bytes, dataStart + 10)
                    }
                }
                "CMAP" -> {
                    val count = (len / 3).coerceAtMost(256)
                    for (i in 0 until count) {
                        val r = u8(bytes, dataStart + i * 3)
                        val g = u8(bytes, dataStart + i * 3 + 1)
                        val b = u8(bytes, dataStart + i * 3 + 2)
                        palette[i] = packArgb(255, r, g, b)
                    }
                }
                "CAMG" -> {
                    if (len >= 4) camgMode = u32be(bytes, dataStart)
                }
                "BODY" -> {
                    bodyOffset = dataStart
                    bodyLen = len
                }
            }
            pos = dataStart + len + (len and 1)
        }

        if (!isValidDimensions(width, height) || bodyOffset < 0 || nPlanes !in 1..32) return null

        // Extra Half-Brite (EHB) mode: 6 planes where colors 32..63 are half-brightness copies of 0..31
        if (nPlanes == 6 && (camgMode and 0x80L) != 0L) {
            for (i in 0 until 32) {
                val c = palette[i]
                val r = ((c ushr 16) and 0xFF) ushr 1
                val g = ((c ushr 8) and 0xFF) ushr 1
                val b = (c and 0xFF) ushr 1
                palette[i + 32] = packArgb(255, r, g, b)
            }
        }

        val isHam = (camgMode and 0x800L) != 0L && (nPlanes == 6 || nPlanes == 8)
        val totalPlanesInFile = nPlanes + (if (masking == 1) 1 else 0)
        val pixels = IntArray(width * height)

        if (formType == "PBM ") {
            val rowBytes = (width + 1) and 1.inv()
            val raw = if (compression == 1) {
                decodePackBits(bytes, bodyOffset, rowBytes * height, bodyOffset + bodyLen).first
            } else {
                bytes.copyOfRange(bodyOffset, (bodyOffset + rowBytes * height).coerceAtMost(bytes.size))
            }
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val idx = raw.getOrElse(y * rowBytes + x) { 0 }.toInt() and 0xFF
                    pixels[y * width + x] = palette[idx]
                }
            }
            return createScaledBitmap(pixels, width, height, targetPx)
        }

        val planeRowBytes = ((width + 15) / 16) * 2
        val totalScanlineBytes = planeRowBytes * totalPlanesInFile * height
        val bodyBytes = if (compression == 1) {
            decodePackBits(bytes, bodyOffset, totalScanlineBytes, bodyOffset + bodyLen).first
        } else {
            bytes.copyOfRange(bodyOffset, (bodyOffset + bodyLen).coerceAtMost(bytes.size))
        }

        for (y in 0 until height) {
            val scanlineBase = y * totalPlanesInFile * planeRowBytes
            var holdR = (palette[0] ushr 16) and 0xFF
            var holdG = (palette[0] ushr 8) and 0xFF
            var holdB = palette[0] and 0xFF

            for (x in 0 until width) {
                val byteInRow = x ushr 3
                val bitShift = 7 - (x and 7)
                var value = 0
                for (p in 0 until nPlanes) {
                    val bIdx = scanlineBase + p * planeRowBytes + byteInRow
                    val b = if (bIdx < bodyBytes.size) (bodyBytes[bIdx].toInt() and 0xFF) else 0
                    val bit = (b ushr bitShift) and 1
                    value = value or (bit shl p)
                }

                val color = when {
                    nPlanes >= 24 -> {
                        val r = value and 0xFF
                        val g = (value ushr 8) and 0xFF
                        val b = (value ushr 16) and 0xFF
                        val a = if (nPlanes == 32) ((value ushr 24) and 0xFF) else 255
                        packArgb(a, r, g, b)
                    }
                    isHam -> {
                        val dataBits = nPlanes - 2
                        val cmd = (value ushr dataBits) and 0x03
                        val data = value and ((1 shl dataBits) - 1)
                        val scaled = if (dataBits == 4) (data * 17) else ((data shl 2) or (data ushr 4))
                        when (cmd) {
                            0 -> {
                                val c = palette[data.coerceIn(0, 255)]
                                holdR = (c ushr 16) and 0xFF
                                holdG = (c ushr 8) and 0xFF
                                holdB = c and 0xFF
                            }
                            1 -> holdB = scaled
                            2 -> holdR = scaled
                            3 -> holdG = scaled
                        }
                        packArgb(255, holdR, holdG, holdB)
                    }
                    else -> palette[value and 0xFF]
                }
                pixels[y * width + x] = color
            }
        }

        return createScaledBitmap(pixels, width, height, targetPx)
    }

    // =========================================================================
    // 2. Atari ST Neochrome (.neo) & DEGAS (.pi1..pi3, .pc1..pc3)
    // =========================================================================
    private fun decodeAtariStPalette(bytes: ByteArray, offset: Int): IntArray {
        val pal = IntArray(16)
        for (i in 0 until 16) {
            val w = u16be(bytes, offset + i * 2)
            // Atari ST 9-bit / STE 12-bit palette word: 0x0RGB
            val r3 = (w ushr 8) and 0x07
            val g3 = (w ushr 4) and 0x07
            val b3 = w and 0x07
            pal[i] = packArgb(255, r3 * 255 / 7, g3 * 255 / 7, b3 * 255 / 7)
        }
        return pal
    }

    fun decodeAtariNeo(bytes: ByteArray, targetPx: Int): Bitmap? {
        if (bytes.size < 32128) return null
        val res = u16be(bytes, 2)
        val pal = decodeAtariStPalette(bytes, 4)
        return decodeAtariScreenMemory(bytes, 128, res, pal, targetPx)
    }

    fun decodeAtariDegas(bytes: ByteArray, ext: String, targetPx: Int): Bitmap? {
        if (bytes.size < 34) return null
        val resWord = u16be(bytes, 0)
        val isCompressed = (resWord and 0x8000) != 0 || ext.startsWith("pc")
        val res = resWord and 0x0003
        val pal = decodeAtariStPalette(bytes, 2)
        val screenBytes = if (isCompressed) {
            decodePackBits(bytes, 34, 32000).first
        } else {
            if (bytes.size < 32034) return null
            bytes.copyOfRange(34, 32034)
        }
        return decodeAtariScreenMemory(screenBytes, 0, res, pal, targetPx)
    }

    private fun decodeAtariScreenMemory(bytes: ByteArray, offset: Int, res: Int, pal: IntArray, targetPx: Int): Bitmap? {
        val (width, height, nPlanes) = when (res) {
            1 -> Triple(640, 200, 2)
            2 -> Triple(640, 400, 1)
            else -> Triple(320, 200, 4)
        }
        val pixels = IntArray(width * height)
        var pos = offset
        val groupsPerRow = width / 16
        for (y in 0 until height) {
            for (g in 0 until groupsPerRow) {
                val words = IntArray(nPlanes)
                for (p in 0 until nPlanes) {
                    if (pos + 2 <= bytes.size) {
                        words[p] = u16be(bytes, pos)
                        pos += 2
                    }
                }
                for (bit in 0 until 16) {
                    val shift = 15 - bit
                    var idx = 0
                    for (p in 0 until nPlanes) {
                        idx = idx or (((words[p] ushr shift) and 1) shl p)
                    }
                    val color = if (nPlanes == 1) {
                        if (idx == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
                    } else {
                        pal[idx and 0x0F]
                    }
                    pixels[y * width + g * 16 + bit] = color
                }
            }
        }
        return createScaledBitmap(pixels, width, height, targetPx)
    }

    // =========================================================================
    // 3. ZX Spectrum Screen (.scr) & Commodore 64 KoalaPainter (.kla, .koa)
    // =========================================================================
    fun decodeZxSpectrumScr(bytes: ByteArray, targetPx: Int): Bitmap? {
        if (bytes.size < 6912) return null
        val width = 256
        val height = 192
        val pixels = IntArray(width * height)
        val zxColors = intArrayOf(
            0xFF000000.toInt(), 0xFF0000D7.toInt(), 0xFFD70000.toInt(), 0xFFD700D7.toInt(),
            0xFF00D700.toInt(), 0xFF00D7D7.toInt(), 0xFFD7D700.toInt(), 0xFFD7D7D7.toInt(),
            0xFF000000.toInt(), 0xFF0000FF.toInt(), 0xFFFF0000.toInt(), 0xFFFF00FF.toInt(),
            0xFF00FF00.toInt(), 0xFF00FFFF.toInt(), 0xFFFFFF00.toInt(), 0xFFFFFFFF.toInt()
        )
        for (y in 0 until height) {
            val yAddr = ((y and 0xC0) shl 5) or ((y and 0x07) shl 8) or ((y and 0x38) shl 2)
            val attrRow = y / 8
            for (bx in 0 until 32) {
                val b = u8(bytes, yAddr or bx)
                val attr = u8(bytes, 6144 + attrRow * 32 + bx)
                val bright = if ((attr and 0x40) != 0) 8 else 0
                val ink = zxColors[(attr and 0x07) + bright]
                val paper = zxColors[((attr ushr 3) and 0x07) + bright]
                for (bit in 0 until 8) {
                    val on = ((b ushr (7 - bit)) and 1) != 0
                    pixels[y * width + bx * 8 + bit] = if (on) ink else paper
                }
            }
        }
        return createScaledBitmap(pixels, width, height, targetPx)
    }

    fun decodeC64Koala(bytes: ByteArray, targetPx: Int): Bitmap? {
        val base = if (bytes.size == 10003) 2 else if (bytes.size == 10000) 0 else return null
        val c64Pal = intArrayOf(
            0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF880000.toInt(), 0xFFAAFFEE.toInt(),
            0xFFCC44CC.toInt(), 0xFF00CC55.toInt(), 0xFF0000AA.toInt(), 0xFFEEEE77.toInt(),
            0xFFDD8855.toInt(), 0xFF664400.toInt(), 0xFFFF7777.toInt(), 0xFF333333.toInt(),
            0xFF777777.toInt(), 0xFFAAFF66.toInt(), 0xFF0088FF.toInt(), 0xFFBBBBBB.toInt()
        )
        val width = 320
        val height = 200
        val pixels = IntArray(width * height)
        val bgIdx = u8(bytes, base + 10000) and 0x0F

        for (cy in 0 until 25) {
            for (cx in 0 until 40) {
                val cellIdx = cy * 40 + cx
                val scrByte = u8(bytes, base + 8000 + cellIdx)
                val colByte = u8(bytes, base + 9000 + cellIdx)
                val c0 = c64Pal[bgIdx]
                val c1 = c64Pal[(scrByte ushr 4) and 0x0F]
                val c2 = c64Pal[scrByte and 0x0F]
                val c3 = c64Pal[colByte and 0x0F]

                for (row in 0 until 8) {
                    val y = cy * 8 + row
                    val bmpByte = u8(bytes, base + cellIdx * 8 + row)
                    for (px in 0 until 4) {
                        val twoBit = (bmpByte ushr ((3 - px) * 2)) and 0x03
                        val color = when (twoBit) {
                            0 -> c0
                            1 -> c1
                            2 -> c2
                            else -> c3
                        }
                        val dstX = (cx * 4 + px) * 2
                        pixels[y * width + dstX] = color
                        pixels[y * width + dstX + 1] = color
                    }
                }
            }
        }
        return createScaledBitmap(pixels, width, height, targetPx)
    }

    // =========================================================================
    // 4. Apple ICNS (.icns), MacPaint (.pntg, .mac), QuickDraw PICT (.pct, .pict)
    // =========================================================================
    fun decodeAppleIcns(bytes: ByteArray, targetPx: Int): Bitmap? {
        if (bytes.size < 16 || String(bytes, 0, 4, StandardCharsets.US_ASCII) != "icns") return null
        var pos = 8
        var bestPngBytes: ByteArray? = null

        while (pos + 8 <= bytes.size) {
            val chunkLen = u32be(bytes, pos + 4).toInt()
            if (chunkLen < 8 || pos + chunkLen > bytes.size) break
            val dataStart = pos + 8
            val dataLen = chunkLen - 8
            // Check for embedded PNG signature (89 50 4E 47)
            if (dataLen >= 8 && u8(bytes, dataStart) == 0x89 && u8(bytes, dataStart + 1) == 0x50 &&
                u8(bytes, dataStart + 2) == 0x4E && u8(bytes, dataStart + 3) == 0x47
            ) {
                if (bestPngBytes == null || dataLen > bestPngBytes.size) {
                    bestPngBytes = bytes.copyOfRange(dataStart, dataStart + dataLen)
                }
            }
            pos += chunkLen
        }

        if (bestPngBytes != null) {
            val bmp = BitmapFactory.decodeByteArray(bestPngBytes, 0, bestPngBytes.size)
                ?: PureJavaPngFallback.decode(bestPngBytes, targetPx)
            if (bmp != null) {
                val maxSide = max(bmp.width, bmp.height)
                if (targetPx > 0 && maxSide > targetPx) {
                    val scale = targetPx.toFloat() / maxSide.toFloat()
                    val scaled = Bitmap.createScaledBitmap(
                        bmp,
                        (bmp.width * scale).toInt().coerceAtLeast(1),
                        (bmp.height * scale).toInt().coerceAtLeast(1),
                        true
                    )
                    if (scaled !== bmp) bmp.recycle()
                    return scaled
                }
                return bmp
            }
        }
        return null
    }

    fun decodeMacPaint(bytes: ByteArray, targetPx: Int): Bitmap? {
        if (bytes.size < 512 + 720) return null
        val width = 576
        val height = 720
        // Optional 128-byte MacBinary header: byte 0 == 0, byte 1 is filename length (1..63),
        // bytes 65..68 == "PNTG" file type, byte 74 == 0, byte 82 == 0.
        val isMacBinary = bytes.size > 640 &&
            u8(bytes, 0) == 0 &&
            u8(bytes, 1) in 1..63 &&
            u8(bytes, 74) == 0 &&
            u8(bytes, 82) == 0 &&
            (String(bytes, 65, 4, StandardCharsets.US_ASCII) == "PNTG" || u32be(bytes, 83) > 512L)
        val startOffset = if (isMacBinary) 640 else 512
        val (unpacked, _) = decodePackBits(bytes, startOffset, 72 * height)
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val rowOff = y * 72
            for (x in 0 until width) {
                val b = u8(unpacked, rowOff + (x ushr 3))
                val bit = (b ushr (7 - (x and 7))) and 1
                val v = if (bit == 1) 0 else 255
                pixels[y * width + x] = packArgb(255, v, v, v)
            }
        }
        return createScaledBitmap(pixels, width, height, targetPx)
    }

    fun decodeQuickDrawPict(bytes: ByteArray, targetPx: Int): Bitmap? {
        // PICT files can start at offset 512 (with 512-byte header) or offset 0
        val startCandidates = if (bytes.size > 530) listOf(512, 0) else listOf(0)
        for (base in startCandidates) {
            if (base + 18 > bytes.size) continue
            // Search for DirectBitsRect (0x009A) or DirectBitsRgn (0x009B) or PackBitsRect (0x0098)
            val bmp = decodePictAt(bytes, base, targetPx)
            if (bmp != null || ImageDecodeUtils.lastDecodedPixelsForTest != null) return bmp
        }
        return null
    }

    private fun decodePictAt(bytes: ByteArray, base: Int, targetPx: Int): Bitmap? {
        val top = u16be(bytes, base + 2)
        val left = u16be(bytes, base + 4)
        val bottom = u16be(bytes, base + 6)
        val right = u16be(bytes, base + 8)
        val frameW = right - left
        val frameH = bottom - top
        if (!isValidDimensions(frameW, frameH)) return null

        // Scan for DirectBitsRect opcode (0x009A) followed by 0x000000FF baseAddr and high-bit rowBytes
        for (pos in (base + 10) until (bytes.size - 50).coerceAtMost(base + 4096)) {
            if (u16be(bytes, pos) == 0x009A && u32be(bytes, pos + 2) == 0x000000FFL) {
                val pmOff = pos + 6
                val rowBytesRaw = u16be(bytes, pmOff)
                if ((rowBytesRaw and 0x8000) == 0) continue
                val rowBytes = rowBytesRaw and 0x3FFF
                val bTop = u16be(bytes, pmOff + 2)
                val bLeft = u16be(bytes, pmOff + 4)
                val bBottom = u16be(bytes, pmOff + 6)
                val bRight = u16be(bytes, pmOff + 8)
                val w = bRight - bLeft
                val h = bBottom - bTop
                val packType = u16be(bytes, pmOff + 12)
                val pixelSize = u16be(bytes, pmOff + 28)
                val cmpCount = u16be(bytes, pmOff + 30)
                if (w != frameW || h != frameH || (pixelSize != 32 && pixelSize != 16)) continue

                // After 46-byte PixMap (baseAddr + 46 = 50 bytes from pos+2) + srcRect(8) + dstRect(8) + mode(2) = pos + 70
                var dataPos = pos + 70
                val pixels = IntArray(w * h)
                for (y in 0 until h) {
                    if (dataPos >= bytes.size) break
                    val packedLen = if (rowBytes > 250) {
                        if (dataPos + 2 > bytes.size) break
                        val l = u16be(bytes, dataPos); dataPos += 2; l
                    } else {
                        val l = u8(bytes, dataPos); dataPos += 1; l
                    }
                    if (pixelSize == 32 && (packType == 4 || packType == 0)) {
                        val expectedRow = w * cmpCount
                        val (unpacked, _) = decodePackBits(bytes, dataPos, expectedRow, (dataPos + packedLen).coerceAtMost(bytes.size))
                        dataPos += packedLen
                        for (x in 0 until w) {
                            if (cmpCount == 4) {
                                val a = u8(unpacked, x)
                                val r = u8(unpacked, w + x)
                                val g = u8(unpacked, w * 2 + x)
                                val b = u8(unpacked, w * 3 + x)
                                pixels[y * w + x] = packArgb(if (a == 0) 255 else a, r, g, b)
                            } else {
                                val r = u8(unpacked, x)
                                val g = u8(unpacked, w + x)
                                val b = u8(unpacked, w * 2 + x)
                                pixels[y * w + x] = packArgb(255, r, g, b)
                            }
                        }
                    } else {
                        dataPos += packedLen
                    }
                }
                return createScaledBitmap(pixels, w, h, targetPx)
            }
        }
        return null
    }

    // =========================================================================
    // 5. Sun Rasterfile (.ras, .sun) & Silicon Graphics SGI (.sgi, .rgb)
    // =========================================================================
    fun decodeSunRaster(bytes: ByteArray, targetPx: Int): Bitmap? {
        if (bytes.size < 32 || u32be(bytes, 0) != 0x59A66A95L) return null
        val width = u32be(bytes, 4).toInt()
        val height = u32be(bytes, 8).toInt()
        val depth = u32be(bytes, 12).toInt()
        val type = u32be(bytes, 20).toInt() // 0=Old, 1=Standard, 2=ByteEncoded(RLE), 3=RGB
        val mapType = u32be(bytes, 24).toInt()
        val mapLength = u32be(bytes, 28).toInt()
        if (!isValidDimensions(width, height) || depth !in setOf(1, 8, 24, 32)) return null

        val mapStart = 32
        val dataStart = mapStart + mapLength.coerceAtLeast(0)
        if (dataStart > bytes.size) return null

        val palette = IntArray(256) { i -> packArgb(255, i, i, i) }
        if (mapType == 1 && mapLength >= 3) {
            val entries = (mapLength / 3).coerceAtMost(256)
            for (i in 0 until entries) {
                val r = u8(bytes, mapStart + i)
                val g = u8(bytes, mapStart + entries + i)
                val b = u8(bytes, mapStart + entries * 2 + i)
                palette[i] = packArgb(255, r, g, b)
            }
        }

        // Sun Raster scanlines are padded to 16-bit (even byte) boundaries
        val rawRowBytes = (width * depth + 7) / 8
        val rowStride = (rawRowBytes + 1) and 1.inv()
        val expectedBytes = rowStride * height

        val rawData = if (type == 2) {
            val out = ByteArray(expectedBytes)
            var dst = 0
            var pos = dataStart
            while (dst < expectedBytes && pos < bytes.size) {
                val b = bytes[pos++]
                if ((b.toInt() and 0xFF) == 0x80) {
                    if (pos >= bytes.size) break
                    val count = u8(bytes, pos++)
                    if (count == 0) {
                        out[dst++] = 0x80.toByte()
                    } else {
                        if (pos >= bytes.size) break
                        val valByte = bytes[pos++]
                        val run = (count + 1).coerceAtMost(expectedBytes - dst)
                        for (i in 0 until run) out[dst++] = valByte
                    }
                } else {
                    out[dst++] = b
                }
            }
            out
        } else {
            bytes.copyOfRange(dataStart, (dataStart + expectedBytes).coerceAtMost(bytes.size))
        }

        val pixels = IntArray(width * height)
        val isRgbOrder = (type == 3)
        for (y in 0 until height) {
            val rowOff = y * rowStride
            for (x in 0 until width) {
                val color = when (depth) {
                    1 -> {
                        val b = u8(rawData, (rowOff + (x ushr 3)).coerceAtMost(rawData.size - 1))
                        val bit = (b ushr (7 - (x and 7))) and 1
                        val v = if (bit == 0) 255 else 0
                        packArgb(255, v, v, v)
                    }
                    8 -> palette[u8(rawData, (rowOff + x).coerceAtMost(rawData.size - 1))]
                    24 -> {
                        val p = rowOff + x * 3
                        if (p + 3 > rawData.size) break
                        if (isRgbOrder) {
                            packArgb(255, u8(rawData, p), u8(rawData, p + 1), u8(rawData, p + 2))
                        } else {
                            packArgb(255, u8(rawData, p + 2), u8(rawData, p + 1), u8(rawData, p))
                        }
                    }
                    32 -> {
                        val p = rowOff + x * 4
                        if (p + 4 > rawData.size) break
                        val c0 = u8(rawData, p)
                        val c1 = u8(rawData, p + 1)
                        val c2 = u8(rawData, p + 2)
                        val c3 = u8(rawData, p + 3)
                        val a = if (c0 == 0) 255 else c0
                        if (isRgbOrder) packArgb(a, c1, c2, c3) else packArgb(a, c3, c2, c1)
                    }
                    else -> 0xFF000000.toInt()
                }
                pixels[y * width + x] = color
            }
        }
        return createScaledBitmap(pixels, width, height, targetPx)
    }

    fun decodeSgiRgb(bytes: ByteArray, targetPx: Int): Bitmap? {
        if (bytes.size < 512 || u16be(bytes, 0) != 0x01DA) return null
        val storage = u8(bytes, 2) // 0 = Verbatim, 1 = RLE
        val bpc = u8(bytes, 3)     // 1 or 2 bytes per channel
        val width = u16be(bytes, 6)
        val height = u16be(bytes, 8)
        val zsize = u16be(bytes, 10).coerceIn(1, 4)
        if (!isValidDimensions(width, height) || (bpc != 1 && bpc != 2)) return null

        val channelPlane = Array(zsize) { ByteArray(width * height) }

        if (storage == 0) {
            var pos = 512
            val step = bpc
            for (z in 0 until zsize) {
                for (y in 0 until height) {
                    val dstY = height - 1 - y // SGI stores bottom-up
                    for (x in 0 until width) {
                        if (pos + step > bytes.size) break
                        channelPlane[z][dstY * width + x] = bytes[pos]
                        pos += step
                    }
                }
            }
        } else if (storage == 1) {
            val tableEntries = height * zsize
            if (512 + tableEntries * 8 > bytes.size) return null
            for (z in 0 until zsize) {
                for (y in 0 until height) {
                    val tabIdx = z * height + y
                    val rleOff = u32be(bytes, 512 + tabIdx * 4).toInt()
                    if (rleOff !in 512 until bytes.size) continue
                    val dstY = height - 1 - y
                    val dstRowBase = dstY * width
                    var x = 0
                    var p = rleOff
                    while (x < width && p < bytes.size) {
                        val pixel = u8(bytes, p)
                        p += bpc
                        val count = pixel and 0x7F
                        if (count == 0) break
                        if ((pixel and 0x80) != 0) {
                            // Literal copy of `count` pixels
                            for (i in 0 until count) {
                                if (x >= width || p >= bytes.size) break
                                channelPlane[z][dstRowBase + x++] = bytes[p]
                                p += bpc
                            }
                        } else {
                            // Repeat 1 pixel `count` times
                            if (p >= bytes.size) break
                            val v = bytes[p]
                            p += bpc
                            for (i in 0 until count) {
                                if (x >= width) break
                                channelPlane[z][dstRowBase + x++] = v
                            }
                        }
                    }
                }
            }
        } else {
            return null
        }

        val totalPixels = width * height
        val pixels = IntArray(totalPixels)
        for (i in 0 until totalPixels) {
            val r = channelPlane[0][i].toInt() and 0xFF
            val g = if (zsize >= 3) (channelPlane[1][i].toInt() and 0xFF) else r
            val b = if (zsize >= 3) (channelPlane[2][i].toInt() and 0xFF) else r
            val a = when (zsize) {
                2 -> channelPlane[1][i].toInt() and 0xFF
                4 -> channelPlane[3][i].toInt() and 0xFF
                else -> 255
            }
            pixels[i] = packArgb(a, r, g, b)
        }
        return createScaledBitmap(pixels, width, height, targetPx)
    }

    // =========================================================================
    // 6. Palm Database ImageViewer (.pdb) & Nokia OTA Bitmap (.otb) & Sixel
    // =========================================================================
    fun decodePalmPdb(bytes: ByteArray, targetPx: Int): Bitmap? {
        if (bytes.size < 86 || String(bytes, 60, 8, StandardCharsets.US_ASCII) != "vIMGView") return null
        val numRecords = u16be(bytes, 76)
        if (numRecords < 1 || 78 + numRecords * 8 > bytes.size) return null
        val rec0Offset = u32be(bytes, 78).toInt()
        val rec0End = if (numRecords >= 2) {
            val nextOff = u32be(bytes, 86).toInt()
            if (nextOff > rec0Offset && nextOff <= bytes.size) nextOff else bytes.size
        } else {
            bytes.size
        }
        if (rec0Offset + 58 > rec0End) return null

        val version = u8(bytes, rec0Offset + 32)
        val type = u8(bytes, rec0Offset + 33) // 0x00 or 0xFF = 1-bit monochrome, 0x01 = 2-bit, 0x02 = 4-bit
        val width = u16be(bytes, rec0Offset + 54)
        val height = u16be(bytes, rec0Offset + 56)
        if (!isValidDimensions(width, height)) return null

        val bpp = when (type) {
            0x01 -> 2
            0x02 -> 4
            else -> 1
        }
        val rowBytes = (width * bpp + 7) / 8
        val expectedBytes = rowBytes * height
        val dataStart = rec0Offset + 58

        val unpacked = if ((version and 1) != 0) {
            // ImageViewer RLE: >0x80 repeats next byte (b - 0x80 + 1) times; <=0x80 copies (b + 1) literal bytes
            val out = ByteArray(expectedBytes)
            var dst = 0
            var pos = dataStart
            while (dst < expectedBytes && pos < rec0End) {
                val b = u8(bytes, pos++)
                if (b > 0x80) {
                    val count = (b - 0x80 + 1).coerceAtMost(expectedBytes - dst)
                    val valByte = if (pos < rec0End) bytes[pos++] else 0
                    for (i in 0 until count) out[dst++] = valByte
                } else {
                    val count = (b + 1).coerceAtMost(expectedBytes - dst).coerceAtMost(rec0End - pos)
                    if (count <= 0) break
                    System.arraycopy(bytes, pos, out, dst, count)
                    pos += count
                    dst += count
                }
            }
            out
        } else {
            bytes.copyOfRange(dataStart, (dataStart + expectedBytes).coerceAtMost(rec0End))
        }

        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val rowOff = y * rowBytes
            for (x in 0 until width) {
                val idx = (rowOff + (x ushr 3)).coerceAtMost(unpacked.size - 1)
                val b = if (idx >= 0) u8(unpacked, idx) else 0
                val bit = (b ushr (7 - (x and 7))) and 1
                val v = if (bit == 1) 0 else 255
                pixels[y * width + x] = packArgb(255, v, v, v)
            }
        }
        return createScaledBitmap(pixels, width, height, targetPx)
    }

    fun decodeNokiaOtb(bytes: ByteArray, targetPx: Int): Bitmap? {
        if (bytes.size < 5) return null
        val infoField = u8(bytes, 0)
        val extDimensions = (infoField and 0x10) != 0
        val width: Int
        val height: Int
        val depth: Int
        val headerSize: Int
        if (extDimensions) {
            if (bytes.size < 7) return null
            width = u16be(bytes, 1)
            height = u16be(bytes, 3)
            depth = u8(bytes, 5)
            headerSize = 6
        } else {
            width = u8(bytes, 1)
            height = u8(bytes, 2)
            depth = u8(bytes, 3)
            headerSize = 4
        }
        if (!isValidDimensions(width, height) || depth != 1) return null

        val totalBits = width * height
        val pixels = IntArray(totalBits)
        for (i in 0 until totalBits) {
            val byteIdx = headerSize + (i ushr 3)
            val b = if (byteIdx < bytes.size) u8(bytes, byteIdx) else 0
            val bit = (b ushr (7 - (i and 7))) and 1
            val v = if (bit == 1) 0 else 255
            pixels[i] = packArgb(255, v, v, v)
        }
        return createScaledBitmap(pixels, width, height, targetPx)
    }

    private fun looksLikeSixel(bytes: ByteArray): Boolean {
        val len = bytes.size.coerceAtMost(128)
        val head = String(bytes, 0, len, StandardCharsets.ISO_8859_1)
        return head.contains("\u001bP") || (head.startsWith("\u0090") && head.contains('q'))
    }

    fun decodeSixel(bytes: ByteArray, targetPx: Int): Bitmap? {
        val text = String(bytes, StandardCharsets.ISO_8859_1)
        val qIdx = text.indexOf('q')
        if (qIdx < 0) return null

        var pos = qIdx + 1
        val endLimit = text.indexOf("\u001b\\", pos).takeIf { it >= 0 } ?: text.length

        // Default dimensions if no raster attribute ("Pan;Pad;Ph;Pv) is present
        var width = 256
        var height = 256
        val palette = HashMap<Int, Int>()
        palette[0] = 0xFF000000.toInt()
        palette[1] = 0xFFFFFFFF.toInt()

        // Pre-scan for raster attributes ("Pan;Pad;Ph;Pv) to allocate exact canvas
        val rasterMatch = Regex(""""(\d+);(\d+);(\d+);(\d+)""").find(text, pos)
        if (rasterMatch != null) {
            val rw = rasterMatch.groupValues[3].toIntOrNull() ?: 0
            val rh = rasterMatch.groupValues[4].toIntOrNull() ?: 0
            if (isValidDimensions(rw, rh)) {
                width = rw
                height = rh
            }
        }

        val pixels = IntArray(width * height) { 0xFF141814.toInt() }
        var curColor = 0xFFFFFFFF.toInt()
        var x = 0
        var bandY = 0

        fun readNumber(): Int {
            var num = 0
            while (pos < endLimit && text[pos] in '0'..'9') {
                num = num * 10 + (text[pos] - '0')
                pos++
            }
            return num
        }

        while (pos < endLimit) {
            val ch = text[pos++]
            when (ch) {
                '"' -> { // Raster attributes "Pan;Pad;Ph;Pv
                    val params = ArrayList<Int>(4)
                    while (pos < endLimit && (text[pos] in '0'..'9' || text[pos] == ';')) {
                        if (text[pos] == ';') pos++ else params.add(readNumber())
                    }
                }
                '#' -> { // Color selector or definition #Pc;Pu;Px;Py;Pz
                    val pc = readNumber()
                    if (pos < endLimit && text[pos] == ';') {
                        pos++
                        val pu = readNumber()
                        val p1 = if (pos < endLimit && text[pos] == ';') { pos++; readNumber() } else 0
                        val p2 = if (pos < endLimit && text[pos] == ';') { pos++; readNumber() } else 0
                        val p3 = if (pos < endLimit && text[pos] == ';') { pos++; readNumber() } else 0
                        if (pu == 2) { // RGB percentages 0..100
                            val r = (p1 * 255 / 100).coerceIn(0, 255)
                            val g = (p2 * 255 / 100).coerceIn(0, 255)
                            val b = (p3 * 255 / 100).coerceIn(0, 255)
                            palette[pc] = packArgb(255, r, g, b)
                        }
                    }
                    curColor = palette[pc] ?: 0xFFFFFFFF.toInt()
                }
                '!' -> { // Repeat introducer !Pn<sixel>
                    val count = readNumber().coerceAtLeast(1)
                    if (pos < endLimit) {
                        val sixCh = text[pos++]
                        if (sixCh.code in 0x3F..0x7E) {
                            val bits = sixCh.code - 0x3F
                            for (r in 0 until count) {
                                if (x in 0 until width) {
                                    for (dy in 0 until 6) {
                                        if ((bits and (1 shl dy)) != 0 && (bandY + dy) in 0 until height) {
                                            pixels[(bandY + dy) * width + x] = curColor
                                        }
                                    }
                                }
                                x++
                            }
                        }
                    }
                }
                '$' -> x = 0 // Graphics carriage return
                '-' -> {     // Graphics new line (next 6-pixel band)
                    x = 0
                    bandY += 6
                }
                in '?'..'~' -> {
                    val bits = ch.code - 0x3F
                    if (x in 0 until width) {
                        for (dy in 0 until 6) {
                            if ((bits and (1 shl dy)) != 0 && (bandY + dy) in 0 until height) {
                                pixels[(bandY + dy) * width + x] = curColor
                            }
                        }
                    }
                    x++
                }
            }
        }

        return createScaledBitmap(pixels, width, height, targetPx)
    }
}
