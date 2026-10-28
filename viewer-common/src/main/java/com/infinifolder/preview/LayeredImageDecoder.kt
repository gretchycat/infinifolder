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
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.InflaterInputStream
import java.util.zip.ZipInputStream
import kotlin.math.max

/**
 * Decodes multi-layer authoring & pixel-art project formats:
 * - OpenRaster (.ora) & Krita (.kra)
 * - Adobe Photoshop (.psd) & Large Document Format (.psb)
 * - GIMP (.xcf)
 * - Aseprite (.ase, .aseprite)
 */
object LayeredImageDecoder {

    val extensions: Set<String> = setOf("ora", "kra", "psd", "psb", "xcf", "ase", "aseprite")

    fun decode(input: InputStream, targetPx: Int, extHint: String = ""): Bitmap? =
        decode(input.readBytes(), targetPx, extHint)

    fun decode(bytes: ByteArray, targetPx: Int, extHint: String = ""): Bitmap? {
        if (bytes.size < 4) return null
        // 1. PSD / PSB ("8BPS")
        if (String(bytes, 0, 4, StandardCharsets.US_ASCII) == "8BPS") {
            return decodePsdOrPsb(bytes, targetPx)
        }
        // 2. GIMP XCF ("gimp xcf ")
        if (bytes.size >= 14 && String(bytes, 0, 9, StandardCharsets.US_ASCII) == "gimp xcf ") {
            return decodeXcf(bytes, targetPx)
        }
        // 3. Aseprite (magic 0xA5E0 at offset 4)
        if (bytes.size >= 128 && u16le(bytes, 4) == 0xA5E0) {
            return decodeAseprite(bytes, targetPx)
        }
        // 4. OpenRaster (.ora) / Krita (.kra) ZIP container
        if (u8(bytes, 0) == 0x50 && u8(bytes, 1) == 0x4B &&
            (extHint in setOf("ora", "kra") || looksLikeOraOrKra(bytes))
        ) {
            return decodeOraOrKra(bytes, targetPx)
        }
        return null
    }

    private fun looksLikeOraOrKra(bytes: ByteArray): Boolean {
        val head = String(bytes, 0, bytes.size.coerceAtMost(256), StandardCharsets.ISO_8859_1)
        return head.contains("image/openraster") || head.contains("application/x-krita") || head.contains("mergedimage.png")
    }

    fun decodeOraOrKra(bytes: ByteArray, targetPx: Int): Bitmap? {
        var bestPng: ByteArray? = null
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name.lowercase()
                    if (name == "mergedimage.png") {
                        bestPng = zis.readBytes()
                        break
                    } else if ((name == "preview.png" || name.endsWith("thumbnail.png")) && bestPng == null) {
                        bestPng = zis.readBytes()
                    }
                    entry = zis.nextEntry
                }
            }
        } catch (_: Throwable) {
            return null
        }
        val pngBytes = bestPng ?: return null
        val bmp = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
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
        // In unit tests with returnDefaultValues=true, BitmapFactory returns null; decode PNG via pure-Kotlin PNG fallback if needed
        return PureJavaPngFallback.decode(pngBytes, targetPx)
    }

    fun decodePsdOrPsb(bytes: ByteArray, targetPx: Int): Bitmap? {
        if (bytes.size < 26 || String(bytes, 0, 4, StandardCharsets.US_ASCII) != "8BPS") return null
        val version = u16be(bytes, 4) // 1 = PSD, 2 = PSB
        if (version != 1 && version != 2) return null

        val channels = u16be(bytes, 12)
        val height = u32be(bytes, 14).toInt()
        val width = u32be(bytes, 18).toInt()
        val depth = u16be(bytes, 22)
        val colorMode = u16be(bytes, 24) // 0=Bitmap, 1=Grayscale, 2=Indexed, 3=RGB, 4=CMYK
        if (!isValidDimensions(width, height) || channels !in 1..56) return null
        if (depth !in setOf(1, 8, 16)) return null

        var pos = 26
        if (pos + 4 > bytes.size) return null
        val colorModeLen = u32be(bytes, pos).toInt()
        val colorModeStart = pos + 4
        pos += 4 + colorModeLen

        if (pos + 4 > bytes.size) return null
        val imageResLen = u32be(bytes, pos).toInt()
        pos += 4 + imageResLen

        if (version == 1) {
            if (pos + 4 > bytes.size) return null
            val layerMaskLen = u32be(bytes, pos).toInt()
            pos += 4 + layerMaskLen
        } else {
            if (pos + 8 > bytes.size) return null
            val layerMaskLen = ((u32be(bytes, pos) shl 32) or u32be(bytes, pos + 4)).toInt()
            pos += 8 + layerMaskLen
        }

        if (pos + 2 > bytes.size) return null
        val compression = u16be(bytes, pos) // 0 = Raw, 1 = RLE (PackBits)
        pos += 2

        val activeChannels = channels.coerceAtMost(4)
        val bytesPerSample = when (depth) {
            16 -> 2
            else -> 1
        }
        val rowBytes = if (depth == 1) (width + 7) / 8 else width * bytesPerSample
        val channelData = Array(activeChannels) { ByteArray(rowBytes * height) }

        when (compression) {
            0 -> { // Uncompressed planar channels
                for (c in 0 until activeChannels) {
                    val dst = channelData[c]
                    val toCopy = dst.size.coerceAtMost((bytes.size - pos).coerceAtLeast(0))
                    if (toCopy > 0) {
                        System.arraycopy(bytes, pos, dst, 0, toCopy)
                        pos += dst.size
                    }
                }
            }
            1 -> { // PackBits RLE: row byte-count table for ALL channels, followed by compressed rows
                val countEntrySize = if (version == 2) 4 else 2
                val totalRows = channels * height
                val dataStart = pos + totalRows * countEntrySize
                if (dataStart > bytes.size) return null
                var rlePos = dataStart
                for (c in 0 until channels) {
                    for (y in 0 until height) {
                        val rowIdx = c * height + y
                        val countOff = pos + rowIdx * countEntrySize
                        val byteCount = if (version == 2) u32be(bytes, countOff).toInt() else u16be(bytes, countOff)
                        if (c < activeChannels) {
                            val (decodedRow, _) = decodePackBits(bytes, rlePos, rowBytes, (rlePos + byteCount).coerceAtMost(bytes.size))
                            System.arraycopy(decodedRow, 0, channelData[c], y * rowBytes, rowBytes)
                        }
                        rlePos += byteCount
                    }
                }
            }
            else -> return null
        }

        val totalPixels = width * height
        val pixels = IntArray(totalPixels)

        fun sample8(c: Int, pxIdx: Int): Int {
            if (c >= activeChannels) return 255
            val arr = channelData[c]
            return if (depth == 16) {
                val off = pxIdx * 2
                if (off < arr.size) (arr[off].toInt() and 0xFF) else 0
            } else {
                if (pxIdx < arr.size) (arr[pxIdx].toInt() and 0xFF) else 0
            }
        }

        when (colorMode) {
            0 -> { // 1-bit Bitmap
                val ch0 = channelData[0]
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val b = ch0[y * rowBytes + (x ushr 3)].toInt() and 0xFF
                        val bit = (b ushr (7 - (x and 7))) and 1
                        val v = if (bit == 0) 255 else 0
                        pixels[y * width + x] = packArgb(255, v, v, v)
                    }
                }
            }
            1 -> { // Grayscale
                for (i in 0 until totalPixels) {
                    val v = sample8(0, i)
                    val a = if (activeChannels >= 2) sample8(1, i) else 255
                    pixels[i] = packArgb(a, v, v, v)
                }
            }
            2 -> { // Indexed (768-byte non-interleaved RGB table in colorModeData)
                for (i in 0 until totalPixels) {
                    val idx = sample8(0, i)
                    if (colorModeLen >= 768 && colorModeStart + 768 <= bytes.size) {
                        val r = u8(bytes, colorModeStart + idx)
                        val g = u8(bytes, colorModeStart + 256 + idx)
                        val b = u8(bytes, colorModeStart + 512 + idx)
                        pixels[i] = packArgb(255, r, g, b)
                    } else {
                        pixels[i] = packArgb(255, idx, idx, idx)
                    }
                }
            }
            4 -> { // CMYK (Photoshop stores 0=full ink, 255=no ink)
                for (i in 0 until totalPixels) {
                    val cInv = sample8(0, i)
                    val mInv = sample8(1, i)
                    val yInv = sample8(2, i)
                    val kInv = if (activeChannels >= 4) sample8(3, i) else 255
                    val r = (cInv * kInv) / 255
                    val g = (mInv * kInv) / 255
                    val b = (yInv * kInv) / 255
                    pixels[i] = packArgb(255, r, g, b)
                }
            }
            else -> { // RGB (mode 3) or Multichannel
                for (i in 0 until totalPixels) {
                    val r = sample8(0, i)
                    val g = if (activeChannels >= 2) sample8(1, i) else r
                    val b = if (activeChannels >= 3) sample8(2, i) else r
                    val a = if (activeChannels >= 4) sample8(3, i) else 255
                    pixels[i] = packArgb(a, r, g, b)
                }
            }
        }

        return createScaledBitmap(pixels, width, height, targetPx)
    }

    fun decodeXcf(bytes: ByteArray, targetPx: Int): Bitmap? {
        if (bytes.size < 26 || String(bytes, 0, 9, StandardCharsets.US_ASCII) != "gimp xcf ") return null
        val versionStr = String(bytes, 9, 4, StandardCharsets.US_ASCII)
        val ptrSize = if (versionStr.startsWith("v0") && (versionStr.substring(1, 4).toIntOrNull() ?: 0) >= 11) 8 else 4

        var pos = 14
        val width = u32be(bytes, pos).toInt()
        val height = u32be(bytes, pos + 4).toInt()
        if (!isValidDimensions(width, height)) return null
        pos += 12 // width, height, base_type

        // Skip precision field in XCF v004+
        val verNum = if (versionStr == "file") 0 else (versionStr.removePrefix("v").toIntOrNull() ?: 0)
        if (verNum >= 4) {
            pos += 4
        }

        var compression = 0 // 0 = None, 1 = RLE
        while (pos + 8 <= bytes.size) {
            val propType = u32be(bytes, pos).toInt()
            val propLen = u32be(bytes, pos + 4).toInt()
            pos += 8
            if (propType == 0) break // PROP_END
            if (propType == 17 && propLen >= 1 && pos < bytes.size) { // PROP_COMPRESSION
                compression = u8(bytes, pos)
            }
            if (propLen < 0 || pos + propLen > bytes.size) return null
            pos += propLen
        }

        fun readPtr(offset: Int): Int {
            if (offset + ptrSize > bytes.size) return 0
            return if (ptrSize == 8) u32be(bytes, offset + 4).toInt() else u32be(bytes, offset).toInt()
        }

        val layerOffsets = ArrayList<Int>()
        while (pos + ptrSize <= bytes.size) {
            val ptr = readPtr(pos)
            pos += ptrSize
            if (ptr == 0) break
            if (ptr in 1 until bytes.size) layerOffsets.add(ptr)
        }

        val canvas = IntArray(width * height)
        // Composite layers bottom-to-top (XCF lists top-most layer first)
        for (layerOffset in layerOffsets.asReversed()) {
            decodeAndCompositeXcfLayer(bytes, layerOffset, ptrSize, compression, width, height, canvas)
        }

        return createScaledBitmap(canvas, width, height, targetPx)
    }

    private fun decodeAndCompositeXcfLayer(
        bytes: ByteArray,
        layerOffset: Int,
        ptrSize: Int,
        compression: Int,
        canvasW: Int,
        canvasH: Int,
        canvas: IntArray
    ) {
        if (layerOffset + 16 > bytes.size) return
        val layerW = u32be(bytes, layerOffset).toInt()
        val layerH = u32be(bytes, layerOffset + 4).toInt()
        val layerType = u32be(bytes, layerOffset + 8).toInt()
        val nameLen = u32be(bytes, layerOffset + 12).toInt()
        if (layerW <= 0 || layerH <= 0 || nameLen < 0) return

        var pos = layerOffset + 16 + nameLen
        var visible = true
        var opacity = 255
        var offsetX = 0
        var offsetY = 0

        while (pos + 8 <= bytes.size) {
            val propType = u32be(bytes, pos).toInt()
            val propLen = u32be(bytes, pos + 4).toInt()
            pos += 8
            if (propType == 0) break
            if (pos + propLen > bytes.size || propLen < 0) return
            when (propType) {
                6 -> if (propLen >= 4) opacity = u32be(bytes, pos).toInt().coerceIn(0, 255) // PROP_OPACITY
                8 -> if (propLen >= 4) visible = u32be(bytes, pos) != 0L // PROP_VISIBLE
                15 -> if (propLen >= 8) { // PROP_OFFSETS
                    offsetX = u32be(bytes, pos).toInt()
                    offsetY = u32be(bytes, pos + 4).toInt()
                }
            }
            pos += propLen
        }
        if (!visible || opacity == 0) return

        val hierarchyPtr = if (ptrSize == 8) u32be(bytes, pos + 4).toInt() else u32be(bytes, pos).toInt()
        if (hierarchyPtr <= 0 || hierarchyPtr + 12 + ptrSize > bytes.size) return

        val bpp = u32be(bytes, hierarchyPtr + 8).toInt().coerceIn(1, 16)
        val levelPtr = if (ptrSize == 8) u32be(bytes, hierarchyPtr + 16).toInt() else u32be(bytes, hierarchyPtr + 12).toInt()
        if (levelPtr <= 0 || levelPtr + 8 + ptrSize > bytes.size) return

        val tilesX = (layerW + 63) / 64
        val tilesY = (layerH + 63) / 64
        var tPtrPos = levelPtr + 8

        for (ty in 0 until tilesY) {
            for (tx in 0 until tilesX) {
                if (tPtrPos + ptrSize > bytes.size) return
                val tileOffset = if (ptrSize == 8) u32be(bytes, tPtrPos + 4).toInt() else u32be(bytes, tPtrPos).toInt()
                tPtrPos += ptrSize
                if (tileOffset <= 0 || tileOffset >= bytes.size) continue

                val tileW = minOf(64, layerW - tx * 64)
                val tileH = minOf(64, layerH - ty * 64)
                val tilePixels = tileW * tileH
                val tileBuf = ByteArray(tilePixels * bpp)

                if (compression == 0) {
                    val avail = (bytes.size - tileOffset).coerceAtMost(tileBuf.size)
                    if (avail > 0) System.arraycopy(bytes, tileOffset, tileBuf, 0, avail)
                } else if (compression == 1) {
                    decodeXcfRleTile(bytes, tileOffset, tilePixels, bpp, tileBuf)
                }

                for (py in 0 until tileH) {
                    val dstY = offsetY + ty * 64 + py
                    if (dstY !in 0 until canvasH) continue
                    for (px in 0 until tileW) {
                        val dstX = offsetX + tx * 64 + px
                        if (dstX !in 0 until canvasW) continue
                        val pOff = (py * tileW + px) * bpp
                        var r = 0
                        var g = 0
                        var b = 0
                        var a = 255
                        when (layerType) {
                            0 -> { // RGB
                                r = tileBuf[pOff].toInt() and 0xFF
                                g = if (bpp >= 2) tileBuf[pOff + 1].toInt() and 0xFF else r
                                b = if (bpp >= 3) tileBuf[pOff + 2].toInt() and 0xFF else r
                            }
                            1 -> { // RGBA
                                r = tileBuf[pOff].toInt() and 0xFF
                                g = if (bpp >= 2) tileBuf[pOff + 1].toInt() and 0xFF else r
                                b = if (bpp >= 3) tileBuf[pOff + 2].toInt() and 0xFF else r
                                a = if (bpp >= 4) tileBuf[pOff + 3].toInt() and 0xFF else 255
                            }
                            2 -> { // Grayscale
                                r = tileBuf[pOff].toInt() and 0xFF
                                g = r; b = r
                            }
                            3 -> { // Grayscale + Alpha
                                r = tileBuf[pOff].toInt() and 0xFF
                                g = r; b = r
                                a = if (bpp >= 2) tileBuf[pOff + 1].toInt() and 0xFF else 255
                            }
                            else -> {
                                r = tileBuf[pOff].toInt() and 0xFF
                                g = r; b = r
                            }
                        }
                        val srcAlpha = (a * opacity) / 255
                        val dstIdx = dstY * canvasW + dstX
                        val dstColor = canvas[dstIdx]
                        if (srcAlpha == 255 || dstColor == 0) {
                            canvas[dstIdx] = packArgb(srcAlpha.coerceAtLeast(if (dstColor != 0) 255 else srcAlpha), r, g, b)
                        } else if (srcAlpha > 0) {
                            val dr = (dstColor ushr 16) and 0xFF
                            val dg = (dstColor ushr 8) and 0xFF
                            val db = dstColor and 0xFF
                            val outR = (r * srcAlpha + dr * (255 - srcAlpha)) / 255
                            val outG = (g * srcAlpha + dg * (255 - srcAlpha)) / 255
                            val outB = (b * srcAlpha + db * (255 - srcAlpha)) / 255
                            canvas[dstIdx] = packArgb(255, outR, outG, outB)
                        }
                    }
                }
            }
        }
    }

    private fun decodeXcfRleTile(bytes: ByteArray, startPos: Int, numPixels: Int, bpp: Int, outInterleaved: ByteArray) {
        var pos = startPos
        for (c in 0 until bpp) {
            var remaining = numPixels
            var px = 0
            while (remaining > 0 && pos < bytes.size) {
                val n = u8(bytes, pos++)
                if (n <= 126) {
                    val run = (n + 1).coerceAtMost(remaining)
                    val v = if (pos < bytes.size) bytes[pos++] else 0
                    for (i in 0 until run) {
                        outInterleaved[(px++) * bpp + c] = v
                    }
                    remaining -= run
                } else if (n == 127) {
                    if (pos + 2 > bytes.size) break
                    val run = u16be(bytes, pos).coerceAtMost(remaining)
                    pos += 2
                    val v = if (pos < bytes.size) bytes[pos++] else 0
                    for (i in 0 until run) {
                        outInterleaved[(px++) * bpp + c] = v
                    }
                    remaining -= run
                } else if (n == 128) {
                    if (pos + 2 > bytes.size) break
                    val run = u16be(bytes, pos).coerceAtMost(remaining)
                    pos += 2
                    for (i in 0 until run) {
                        outInterleaved[(px++) * bpp + c] = if (pos < bytes.size) bytes[pos++] else 0
                    }
                    remaining -= run
                } else {
                    val run = (256 - n).coerceAtMost(remaining)
                    for (i in 0 until run) {
                        outInterleaved[(px++) * bpp + c] = if (pos < bytes.size) bytes[pos++] else 0
                    }
                    remaining -= run
                }
            }
        }
    }

    fun decodeAseprite(bytes: ByteArray, targetPx: Int): Bitmap? {
        if (bytes.size < 128 || u16le(bytes, 4) != 0xA5E0) return null
        val width = u16le(bytes, 8)
        val height = u16le(bytes, 10)
        val colorDepth = u16le(bytes, 12) // 32 = RGBA, 16 = Grayscale, 8 = Indexed
        if (!isValidDimensions(width, height)) return null
        if (colorDepth !in setOf(8, 16, 32)) return null

        var pos = 128
        if (pos + 16 > bytes.size) return null
        val frameMagic = u16le(bytes, pos + 4)
        if (frameMagic != 0xF1FA) return null
        val oldChunks = u16le(bytes, pos + 6)
        val newChunks = u32le(bytes, pos + 12).toInt()
        val numChunks = if (newChunks != 0) newChunks else oldChunks
        pos += 16

        val palette = IntArray(256) { i -> packArgb(255, i, i, i) }
        val layerVisible = ArrayList<Boolean>()
        val canvas = IntArray(width * height)

        for (cIdx in 0 until numChunks) {
            if (pos + 6 > bytes.size) break
            val chunkSize = u32le(bytes, pos).toInt()
            val chunkType = u16le(bytes, pos + 4)
            if (chunkSize < 6 || pos + chunkSize > bytes.size) break
            val dataStart = pos + 6

            when (chunkType) {
                0x2004 -> { // Layer Chunk
                    if (dataStart + 2 <= bytes.size) {
                        val flags = u16le(bytes, dataStart)
                        layerVisible.add((flags and 1) != 0)
                    }
                }
                0x2019 -> { // New Palette Chunk
                    if (dataStart + 12 <= bytes.size) {
                        val firstIdx = u32le(bytes, dataStart + 4).toInt()
                        val lastIdx = u32le(bytes, dataStart + 8).toInt()
                        var pPos = dataStart + 20
                        for (idx in firstIdx..lastIdx.coerceAtMost(255)) {
                            if (pPos + 6 > pos + chunkSize) break
                            val entryFlags = u16le(bytes, pPos)
                            val r = u8(bytes, pPos + 2)
                            val g = u8(bytes, pPos + 3)
                            val b = u8(bytes, pPos + 4)
                            val a = u8(bytes, pPos + 5)
                            palette[idx] = packArgb(a, r, g, b)
                            pPos += 6
                            if ((entryFlags and 1) != 0 && pPos + 2 <= pos + chunkSize) {
                                val nameLen = u16le(bytes, pPos)
                                pPos += 2 + nameLen
                            }
                        }
                    }
                }
                0x0004 -> { // Old Palette Chunk (0..255 RGB)
                    if (dataStart + 2 <= bytes.size) {
                        val packets = u16le(bytes, dataStart)
                        var pPos = dataStart + 2
                        var cEntry = 0
                        for (p in 0 until packets) {
                            if (pPos + 2 > pos + chunkSize) break
                            cEntry += u8(bytes, pPos)
                            var count = u8(bytes, pPos + 1)
                            if (count == 0) count = 256
                            pPos += 2
                            for (k in 0 until count) {
                                if (pPos + 3 > pos + chunkSize) break
                                if (cEntry in 0..255) {
                                    palette[cEntry] = packArgb(255, u8(bytes, pPos), u8(bytes, pPos + 1), u8(bytes, pPos + 2))
                                }
                                cEntry++
                                pPos += 3
                            }
                        }
                    }
                }
                0x2005 -> { // Cel Chunk
                    if (dataStart + 16 <= pos + chunkSize) {
                        val layerIdx = u16le(bytes, dataStart)
                        val isVis = layerVisible.getOrNull(layerIdx) ?: true
                        if (isVis) {
                            val celX = ImageDecodeUtils.s16le(bytes, dataStart + 2)
                            val celY = ImageDecodeUtils.s16le(bytes, dataStart + 4)
                            val celOpacity = u8(bytes, dataStart + 6)
                            val celType = u16le(bytes, dataStart + 7)
                            if (celType == 0 || celType == 2) {
                                if (dataStart + 20 <= pos + chunkSize) {
                                    val celW = u16le(bytes, dataStart + 16)
                                    val celH = u16le(bytes, dataStart + 18)
                                    val bytesPerPx = colorDepth / 8
                                    val rawLen = celW * celH * bytesPerPx
                                    val payloadOff = dataStart + 20
                                    val payloadLen = (pos + chunkSize) - payloadOff
                                    val celBytes = if (celType == 0) {
                                        bytes.copyOfRange(payloadOff, (payloadOff + rawLen).coerceAtMost(pos + chunkSize))
                                    } else {
                                        try {
                                            InflaterInputStream(ByteArrayInputStream(bytes, payloadOff, payloadLen)).use {
                                                it.readBytes()
                                            }
                                        } catch (_: Throwable) {
                                            ByteArray(0)
                                        }
                                    }
                                    compositeAsepriteCel(celBytes, celW, celH, celX, celY, celOpacity, colorDepth, palette, width, height, canvas)
                                }
                            }
                        }
                    }
                }
            }
            pos += chunkSize
        }

        return createScaledBitmap(canvas, width, height, targetPx)
    }

    private fun compositeAsepriteCel(
        celBytes: ByteArray,
        celW: Int,
        celH: Int,
        celX: Int,
        celY: Int,
        celOpacity: Int,
        colorDepth: Int,
        palette: IntArray,
        canvasW: Int,
        canvasH: Int,
        canvas: IntArray
    ) {
        val bytesPerPx = colorDepth / 8
        for (y in 0 until celH) {
            val dstY = celY + y
            if (dstY !in 0 until canvasH) continue
            for (x in 0 until celW) {
                val dstX = celX + x
                if (dstX !in 0 until canvasW) continue
                val off = (y * celW + x) * bytesPerPx
                if (off + bytesPerPx > celBytes.size) break
                var r = 0
                var g = 0
                var b = 0
                var a = 255
                when (colorDepth) {
                    32 -> {
                        r = u8(celBytes, off)
                        g = u8(celBytes, off + 1)
                        b = u8(celBytes, off + 2)
                        a = u8(celBytes, off + 3)
                    }
                    16 -> {
                        val v = u8(celBytes, off)
                        r = v; g = v; b = v
                        a = u8(celBytes, off + 1)
                    }
                    8 -> {
                        val idx = u8(celBytes, off)
                        val c = palette[idx]
                        a = (c ushr 24) and 0xFF
                        r = (c ushr 16) and 0xFF
                        g = (c ushr 8) and 0xFF
                        b = c and 0xFF
                    }
                }
                val effAlpha = (a * celOpacity) / 255
                if (effAlpha > 0) {
                    canvas[dstY * canvasW + dstX] = packArgb(effAlpha, r, g, b)
                }
            }
        }
    }
}
