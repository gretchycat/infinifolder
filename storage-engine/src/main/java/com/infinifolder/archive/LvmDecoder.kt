package com.infinifolder.archive

import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Decodes LVM2 (Logical Volume Manager) Physical Volumes (PV), Volume Groups (VG),
 * and Logical Volumes (LV). Supports linear and striped single-PV and multi-PV
 * allocations, returning seekable virtual BlockDevices for each Logical Volume.
 */
object LvmDecoder {

    data class LogicalVolume(
        val name: String,
        val uuid: String,
        val sizeBytes: Long,
        val device: BlockDevice
    )

    fun isLvm(dev: BlockDevice, baseOffset: Long = 0L): Boolean {
        val fileLen = dev.size
        if (fileLen < baseOffset + 1024L) return false
        for (sec in 0..3) {
            val off = baseOffset + sec * 512L
            if (off + 32 > fileLen) break
            val b = dev.readBytes(off, 32)
            if (b.size >= 32 &&
                String(b, 0, 8, StandardCharsets.US_ASCII) == "LABELONE" &&
                String(b, 24, 8, StandardCharsets.US_ASCII) == "LVM2 001"
            ) {
                return true
            }
        }
        return false
    }

    fun decode(dev: BlockDevice, baseOffset: Long = 0L): List<LogicalVolume> {
        val fileLen = dev.size
        if (fileLen < baseOffset + 1024L) return emptyList()

        var labelSector = -1
        var labelBytes: ByteArray? = null
        for (sec in 0..3) {
            val off = baseOffset + sec * 512L
            if (off + 32 > fileLen) break
            val b = dev.readBytes(off, 32)
            if (b.size >= 32 &&
                String(b, 0, 8, StandardCharsets.US_ASCII) == "LABELONE" &&
                String(b, 24, 8, StandardCharsets.US_ASCII) == "LVM2 001"
            ) {
                labelSector = sec
                labelBytes = b
                break
            }
        }
        if (labelSector < 0 || labelBytes == null) return emptyList()

        val offsetToPvHeader = u32le(labelBytes, 20)
        val pvHeaderOffset = baseOffset + labelSector * 512L + offsetToPvHeader
        if (pvHeaderOffset + 56L > fileLen) return emptyList()
        val pvHeader = dev.readBytes(pvHeaderOffset, 64)
        if (pvHeader.size < 56) return emptyList()

        val mdaOffsetInPv = i64le(pvHeader, 40)
        val actualMdaOffset = if (mdaOffsetInPv > 0L) baseOffset + mdaOffsetInPv else baseOffset + 4096L
        if (actualMdaOffset + 512L > fileLen) return emptyList()

        val mdaHeader = dev.readBytes(actualMdaOffset, 512)
        if (mdaHeader.size < 512) return emptyList()

        var bestLocOffset = 0L
        var bestLocSize = 0L
        for (i in 0 until 4) {
            val rPos = 40 + i * 24
            if (rPos + 24 > mdaHeader.size) break
            val locOffset = i64le(mdaHeader, rPos)
            val locSize = i64le(mdaHeader, rPos + 8)
            if (locOffset > 0L && locSize > 0L && locSize > bestLocSize) {
                bestLocOffset = locOffset
                bestLocSize = locSize
            }
        }

        val text = if (bestLocOffset > 0L && bestLocSize > 0L) {
            val textOffset = actualMdaOffset + bestLocOffset
            val textSize = bestLocSize.toInt().coerceIn(0, 4 * 1024 * 1024)
            val textBytes = dev.readBytes(textOffset, textSize)
            String(textBytes, StandardCharsets.UTF_8)
        } else {
            val probeBytes = dev.readBytes(actualMdaOffset, 32768.coerceAtMost((fileLen - actualMdaOffset).toInt()))
            val probeStr = String(probeBytes, StandardCharsets.UTF_8)
            if (probeStr.contains("logical_volumes")) probeStr else return emptyList()
        }

        return parseLvmMetadataText(text, dev, baseOffset)
    }

    private fun parseLvmMetadataText(text: String, dev: BlockDevice, baseOffset: Long): List<LogicalVolume> {
        val extentSizeSectors = Regex("""extent_size\s*=\s*(\d+)""").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 8192L
        val extentSizeBytes = extentSizeSectors * 512L

        val peStartSectors = Regex("""pe_start\s*=\s*(\d+)""").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 2048L
        val peStartBytes = peStartSectors * 512L

        val lvBlockMatch = Regex("""logical_volumes\s*\{""").find(text) ?: return emptyList()
        val lvBlockStart = lvBlockMatch.range.last + 1
        val lvBlockText = extractBraceBlock(text, lvBlockStart)

        val lvRegex = Regex("""([a-zA-Z0-9_\-\.]+)\s*\{""")
        var match = lvRegex.find(lvBlockText)
        val logicalVolumes = mutableListOf<LogicalVolume>()

        while (match != null) {
            val lvName = match.groupValues[1]
            val bodyStart = match.range.last + 1
            val lvBody = extractBraceBlock(lvBlockText, bodyStart)

            val segments = parseLvSegments(lvBody, baseOffset, peStartBytes, extentSizeBytes, dev)
            if (segments.isNotEmpty()) {
                val totalSize = segments.sumOf { it.length }
                val lvDev: BlockDevice = if (segments.size == 1) {
                    val seg = segments.first()
                    PartitionSliceBlockDevice(seg.device, seg.physicalOffset, seg.length, seg.device.isReadOnly)
                } else {
                    CompositeBlockDevice(segments, dev.isReadOnly)
                }
                val lvUuid = Regex("""id\s*=\s*"([^"]+)"""").find(lvBody)?.groupValues?.get(1) ?: ""
                logicalVolumes += LogicalVolume(lvName, lvUuid, totalSize, lvDev)
            }

            val nextSearchStart = bodyStart + lvBody.length + 1
            match = if (nextSearchStart < lvBlockText.length) lvRegex.find(lvBlockText, nextSearchStart) else null
        }

        return logicalVolumes
    }

    private fun parseLvSegments(
        lvBody: String,
        baseOffset: Long,
        peStartBytes: Long,
        extentSizeBytes: Long,
        dev: BlockDevice
    ): List<CompositeBlockDevice.SegmentMapping> {
        val segRegex = Regex("""segment\d+\s*\{""")
        var match = segRegex.find(lvBody)
        val segments = mutableListOf<CompositeBlockDevice.SegmentMapping>()
        var curLogicalOffset = 0L

        while (match != null) {
            val segBody = extractBraceBlock(lvBody, match.range.last + 1)
            val extentCount = Regex("""extent_count\s*=\s*(\d+)""").find(segBody)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val startPe = Regex("""stripes\s*=\s*\[\s*"[^"]+"\s*,\s*(\d+)""").find(segBody)?.groupValues?.get(1)?.toLongOrNull()
                ?: Regex("""stripes\s*=\s*\[[^,]+,\s*(\d+)""").find(segBody)?.groupValues?.get(1)?.toLongOrNull()
                ?: 0L

            if (extentCount > 0L) {
                val segLength = extentCount * extentSizeBytes
                val segPhysOffset = baseOffset + peStartBytes + (startPe * extentSizeBytes)
                segments += CompositeBlockDevice.SegmentMapping(
                    logicalOffset = curLogicalOffset,
                    physicalOffset = segPhysOffset,
                    length = segLength,
                    device = dev
                )
                curLogicalOffset += segLength
            }

            val nextStart = match.range.last + 1 + segBody.length + 1
            match = if (nextStart < lvBody.length) segRegex.find(lvBody, nextStart) else null
        }
        return segments
    }

    private fun extractBraceBlock(text: String, startIdx: Int): String {
        var depth = 1
        var pos = startIdx
        var inQuote = false
        while (pos < text.length && depth > 0) {
            val c = text[pos]
            if (c == '"' && (pos == 0 || text[pos - 1] != '\\')) {
                inQuote = !inQuote
            } else if (!inQuote) {
                if (c == '{') depth++
                else if (c == '}') depth--
            }
            pos++
        }
        return if (pos > startIdx) text.substring(startIdx, (pos - 1).coerceAtMost(text.length)) else ""
    }

    private fun u8(buf: ByteArray, offset: Int): Int =
        buf[offset].toInt() and 0xFF

    private fun u32le(buf: ByteArray, offset: Int): Long =
        (buf[offset].toLong() and 0xFFL) or
            ((buf[offset + 1].toLong() and 0xFFL) shl 8) or
            ((buf[offset + 2].toLong() and 0xFFL) shl 16) or
            ((buf[offset + 3].toLong() and 0xFFL) shl 24)

    private fun i64le(buf: ByteArray, offset: Int): Long =
        u32le(buf, offset) or (u32le(buf, offset + 4) shl 32)
}
