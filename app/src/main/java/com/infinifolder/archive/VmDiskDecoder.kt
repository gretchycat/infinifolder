package com.infinifolder.archive

import android.util.LruCache
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.zip.Inflater

/**
 * Pure-Kotlin reader and writer for common Virtual Machine disk image formats:
 * - VDI: VirtualBox Dynamic and Fixed disk images (.vdi)
 * - QCOW2: QEMU Copy-On-Write v2 & v3 disk images (.qcow2)
 * - VMDK: VMware Monolithic Sparse & Flat disk images (.vmdk)
 * - VHD: Microsoft Virtual Hard Disk Fixed & Dynamic (.vhd)
 * - VHDX: Microsoft Hyper-V Virtual Hard Disk v2 (.vhdx)
 */
object VmDiskDecoder {

    fun open(raf: RandomAccessFile, isReadOnly: Boolean = false, baseFile: File? = null): BlockDevice? {
        val fileLength = raf.length()
        if (fileLength < 512) return null

        val header = ByteArray(512)
        synchronized(raf) {
            raf.seek(0L)
            raf.readFully(header)
        }

        // 1. QCOW2 (Magic: QFI\xfb at 0)
        if (u8(header, 0) == 0x51 && u8(header, 1) == 0x46 && u8(header, 2) == 0x49 && u8(header, 3) == 0xFB) {
            return try {
                Qcow2BlockDevice(raf, header, isReadOnly)
            } catch (_: Exception) { null }
        }

        // 2. VDI (Magic: 0xBEDA107F at offset 0x40 = 64)
        if (fileLength >= 400 && u32le(header, 0x40) == 0xBEDA107FL) {
            return try {
                VdiBlockDevice(raf, header, isReadOnly)
            } catch (_: Exception) { null }
        }

        // 3. VMDK Sparse (Magic: "KDMV" = 0x564D444B at 0)
        if (header[0] == 'K'.code.toByte() && header[1] == 'D'.code.toByte() &&
            header[2] == 'M'.code.toByte() && header[3] == 'V'.code.toByte()
        ) {
            return try {
                VmdkSparseBlockDevice(raf, header, isReadOnly)
            } catch (_: Exception) { null }
        }

        // VMDK Text Descriptor
        if (fileLength < 1024 * 1024) {
            val textHead = String(header, 0, 64.coerceAtMost(header.size), StandardCharsets.US_ASCII)
            if (textHead.contains("# Disk DescriptorFile") || textHead.startsWith("# Disk DescriptorFile")) {
                val fullText = ByteArray(fileLength.toInt())
                synchronized(raf) {
                    raf.seek(0L)
                    raf.readFully(fullText)
                }
                val descStr = String(fullText, StandardCharsets.UTF_8)
                val flatDev = openVmdkFlat(descStr, baseFile, isReadOnly)
                if (flatDev != null) return flatDev
            }
        }

        // 4. VHDX (Magic: "vhdxfile" at 0)
        if (header.size >= 8 && String(header, 0, 8, StandardCharsets.US_ASCII) == "vhdxfile") {
            return try {
                VhdxBlockDevice(raf, isReadOnly)
            } catch (_: Exception) { null }
        }

        // 5. VHD (Dynamic: "conectix" at 0, or Fixed: "conectix" at EOF - 512)
        if (String(header, 0, 8, StandardCharsets.US_ASCII) == "conectix") {
            return try {
                VhdBlockDevice(raf, header, isReadOnly, isFooterAtEof = false)
            } catch (_: Exception) { null }
        }
        if (fileLength >= 1024) {
            val footer = ByteArray(512)
            synchronized(raf) {
                raf.seek(fileLength - 512L)
                raf.readFully(footer)
            }
            if (String(footer, 0, 8, StandardCharsets.US_ASCII) == "conectix") {
                return try {
                    VhdBlockDevice(raf, footer, isReadOnly, isFooterAtEof = true)
                } catch (_: Exception) { null }
            }
        }

        return null
    }

    private fun openVmdkFlat(descriptor: String, baseFile: File?, isReadOnly: Boolean): BlockDevice? {
        val lines = descriptor.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("RW") && trimmed.contains("FLAT")) {
                val parts = trimmed.split(Regex("\\s+"))
                val flatIndex = parts.indexOf("FLAT")
                if (flatIndex != -1 && flatIndex + 1 < parts.size) {
                    val rawTarget = parts[flatIndex + 1].removeSurrounding("\"")
                    val flatFile = if (baseFile != null) File(baseFile.parentFile, rawTarget) else File(rawTarget)
                    if (flatFile.exists() && flatFile.isFile) {
                        val flatRaf = RandomAccessFile(flatFile, if (isReadOnly) "r" else "rw")
                        return FileBlockDevice(flatRaf, isReadOnly)
                    }
                }
            }
        }
        return null
    }

    // Helper byte decoders
    internal fun u8(buf: ByteArray, offset: Int): Int = buf[offset].toInt() and 0xFF

    internal fun u16le(buf: ByteArray, offset: Int): Int =
        (u8(buf, offset)) or (u8(buf, offset + 1) shl 8)

    internal fun u32le(buf: ByteArray, offset: Int): Long =
        ((u8(buf, offset).toLong()) or
            (u8(buf, offset + 1).toLong() shl 8) or
            (u8(buf, offset + 2).toLong() shl 16) or
            (u8(buf, offset + 3).toLong() shl 24)) and 0xFFFFFFFFL

    internal fun i64le(buf: ByteArray, offset: Int): Long =
        (u32le(buf, offset)) or (u32le(buf, offset + 4) shl 32)

    internal fun u16be(buf: ByteArray, offset: Int): Int =
        (u8(buf, offset) shl 8) or (u8(buf, offset + 1))

    internal fun u32be(buf: ByteArray, offset: Int): Long =
        ((u8(buf, offset).toLong() shl 24) or
            (u8(buf, offset + 1).toLong() shl 16) or
            (u8(buf, offset + 2).toLong() shl 8) or
            (u8(buf, offset + 3).toLong())) and 0xFFFFFFFFL

    internal fun u64be(buf: ByteArray, offset: Int): Long =
        (u32be(buf, offset) shl 32) or u32be(buf, offset + 4)
}

/**
 * Oracle VM VirtualBox VDI BlockDevice.
 */
class VdiBlockDevice(
    private val raf: RandomAccessFile,
    header: ByteArray,
    override val isReadOnly: Boolean
) : BlockDevice {

    override val size: Long
    private val blockSize: Int
    private val offBlocks: Long
    private val offData: Long
    private val totalBlocks: Int
    private var allocatedBlocks: Int
    private val blockMap: IntArray

    init {
        size = VmDiskDecoder.i64le(header, 0x170)
        blockSize = VmDiskDecoder.u32le(header, 0x178).toInt()
        offBlocks = VmDiskDecoder.u32le(header, 0x154)
        offData = VmDiskDecoder.u32le(header, 0x158)
        totalBlocks = VmDiskDecoder.u32le(header, 0x180).toInt()
        allocatedBlocks = VmDiskDecoder.u32le(header, 0x184).toInt()

        if (blockSize <= 0 || totalBlocks <= 0) {
            throw IOException("Corrupt VDI header: blockSize=$blockSize, totalBlocks=$totalBlocks")
        }

        // Read block map
        val mapBytes = ByteArray(totalBlocks * 4)
        synchronized(raf) {
            raf.seek(offBlocks)
            raf.readFully(mapBytes)
        }
        blockMap = IntArray(totalBlocks) { i ->
            VmDiskDecoder.u32le(mapBytes, i * 4).toInt()
        }
    }

    override fun readAt(offset: Long, buffer: ByteArray, dstOffset: Int, length: Int): Int {
        if (offset < 0 || offset >= size || length <= 0) return 0
        val toRead = length.toLong().coerceAtMost(size - offset).toInt()
        var bytesRead = 0

        while (bytesRead < toRead) {
            val currOffset = offset + bytesRead
            val blockIdx = (currOffset / blockSize).toInt()
            val offsetInBlock = (currOffset % blockSize).toInt()
            val chunk = (blockSize - offsetInBlock).toLong().coerceAtMost((toRead - bytesRead).toLong()).toInt()

            if (blockIdx >= totalBlocks) break
            val physBlock = blockMap[blockIdx]

            if (physBlock == -1 || (physBlock.toLong() and 0xFFFFFFFFL) >= 0xFFFFFFFEL) {
                // Unallocated: fill with zeroes
                buffer.fill(0, dstOffset + bytesRead, dstOffset + bytesRead + chunk)
            } else {
                val physOffset = offData + (physBlock.toLong() and 0xFFFFFFFFL) * blockSize + offsetInBlock
                synchronized(raf) {
                    raf.seek(physOffset)
                    raf.readFully(buffer, dstOffset + bytesRead, chunk)
                }
            }
            bytesRead += chunk
        }

        return bytesRead
    }

    override fun writeAt(offset: Long, buffer: ByteArray, srcOffset: Int, length: Int) {
        if (isReadOnly) throw IOException("VDI BlockDevice is read-only")
        if (offset < 0 || offset + length > size) {
            throw IOException("VDI write out of bounds: offset=$offset, length=$length, diskSize=$size")
        }

        var bytesWritten = 0
        while (bytesWritten < length) {
            val currOffset = offset + bytesWritten
            val blockIdx = (currOffset / blockSize).toInt()
            val offsetInBlock = (currOffset % blockSize).toInt()
            val chunk = (blockSize - offsetInBlock).coerceAtMost(length - bytesWritten)

            var physBlock = blockMap[blockIdx]
            if (physBlock == -1 || (physBlock.toLong() and 0xFFFFFFFFL) >= 0xFFFFFFFEL) {
                // Dynamically allocate a new block
                synchronized(raf) {
                    val newPhysBlock = allocatedBlocks
                    val newBlockFileOffset = offData + newPhysBlock.toLong() * blockSize
                    raf.setLength(newBlockFileOffset + blockSize)

                    // Write zeroes to the new block
                    val zeroBlock = ByteArray(blockSize)
                    raf.seek(newBlockFileOffset)
                    raf.write(zeroBlock)

                    // Update block map on disk and memory
                    val entryBytes = ByteArray(4)
                    entryBytes[0] = (newPhysBlock and 0xFF).toByte()
                    entryBytes[1] = ((newPhysBlock ushr 8) and 0xFF).toByte()
                    entryBytes[2] = ((newPhysBlock ushr 16) and 0xFF).toByte()
                    entryBytes[3] = ((newPhysBlock ushr 24) and 0xFF).toByte()
                    raf.seek(offBlocks + blockIdx * 4L)
                    raf.write(entryBytes)

                    allocatedBlocks++
                    val countBytes = ByteArray(4)
                    countBytes[0] = (allocatedBlocks and 0xFF).toByte()
                    countBytes[1] = ((allocatedBlocks ushr 8) and 0xFF).toByte()
                    countBytes[2] = ((allocatedBlocks ushr 16) and 0xFF).toByte()
                    countBytes[3] = ((allocatedBlocks ushr 24) and 0xFF).toByte()
                    raf.seek(0x184L)
                    raf.write(countBytes)

                    blockMap[blockIdx] = newPhysBlock
                    physBlock = newPhysBlock
                }
            }

            val physOffset = offData + (physBlock.toLong() and 0xFFFFFFFFL) * blockSize + offsetInBlock
            synchronized(raf) {
                raf.seek(physOffset)
                raf.write(buffer, srcOffset + bytesWritten, chunk)
            }
            bytesWritten += chunk
        }
    }

    override fun flush() {
        synchronized(raf) {
            try { raf.channel.force(true) } catch (_: Exception) {}
        }
    }

    override fun close() {}
}

/**
 * QEMU QCOW2 BlockDevice supporting both uncompressed and zlib/deflate compressed clusters.
 */
class Qcow2BlockDevice(
    private val raf: RandomAccessFile,
    header: ByteArray,
    override val isReadOnly: Boolean
) : BlockDevice {

    override val size: Long
    private val clusterBits: Int
    private val clusterSize: Long
    private val l1Size: Int
    private val l1TableOffset: Long
    private val l1Table: LongArray
    private val decompressedClusterCache = LruCache<Long, ByteArray>(32)

    init {
        clusterBits = VmDiskDecoder.u32be(header, 20).toInt()
        clusterSize = 1L shl clusterBits
        size = VmDiskDecoder.u64be(header, 24)
        l1Size = VmDiskDecoder.u32be(header, 36).toInt()
        l1TableOffset = VmDiskDecoder.u64be(header, 40)

        if (clusterBits !in 9..26 || l1Size <= 0) {
            throw IOException("Corrupt QCOW2 header: clusterBits=$clusterBits, l1Size=$l1Size")
        }

        // Read L1 table
        val l1Bytes = ByteArray(l1Size * 8)
        synchronized(raf) {
            raf.seek(l1TableOffset)
            raf.readFully(l1Bytes)
        }
        l1Table = LongArray(l1Size) { i ->
            VmDiskDecoder.u64be(l1Bytes, i * 8)
        }
    }

    override fun readAt(offset: Long, buffer: ByteArray, dstOffset: Int, length: Int): Int {
        if (offset < 0 || offset >= size || length <= 0) return 0
        val toRead = length.toLong().coerceAtMost(size - offset).toInt()
        var bytesRead = 0

        val l2Entries = clusterSize / 8L

        while (bytesRead < toRead) {
            val currOffset = offset + bytesRead
            val clusterIdx = currOffset / clusterSize
            val l1Idx = (clusterIdx / l2Entries).toInt()
            val l2Idx = (clusterIdx % l2Entries).toInt()
            val offsetInCluster = (currOffset % clusterSize).toInt()
            val chunk = (clusterSize - offsetInCluster).coerceAtMost((toRead - bytesRead).toLong()).toInt()

            if (l1Idx >= l1Size) break
            val l1Entry = l1Table[l1Idx]
            val l2Offset = l1Entry and 0x00FFFFFFFFFFFF00L

            if (l2Offset == 0L) {
                buffer.fill(0, dstOffset + bytesRead, dstOffset + bytesRead + chunk)
            } else {
                val l2Entry = readL2Entry(l2Offset, l2Idx)
                val isCompressed = (l2Entry and (1L shl 62)) != 0L

                if (l2Entry == 0L) {
                    buffer.fill(0, dstOffset + bytesRead, dstOffset + bytesRead + chunk)
                } else if (!isCompressed) {
                    val hostOffset = l2Entry and 0x00FFFFFFFFFFFF00L
                    if (hostOffset == 0L) {
                        buffer.fill(0, dstOffset + bytesRead, dstOffset + bytesRead + chunk)
                    } else {
                        synchronized(raf) {
                            raf.seek(hostOffset + offsetInCluster)
                            raf.readFully(buffer, dstOffset + bytesRead, chunk)
                        }
                    }
                } else {
                    // Compressed cluster
                    val clusterBytes = getOrDecompressCluster(clusterIdx, l2Entry)
                    System.arraycopy(clusterBytes, offsetInCluster, buffer, dstOffset + bytesRead, chunk)
                }
            }

            bytesRead += chunk
        }

        return bytesRead
    }

    private fun readL2Entry(l2TableOffset: Long, l2Idx: Int): Long {
        val entryBytes = ByteArray(8)
        synchronized(raf) {
            raf.seek(l2TableOffset + l2Idx * 8L)
            raf.readFully(entryBytes)
        }
        return VmDiskDecoder.u64be(entryBytes, 0)
    }

    private fun getOrDecompressCluster(clusterIdx: Long, l2Entry: Long): ByteArray {
        synchronized(decompressedClusterCache) {
            decompressedClusterCache.get(clusterIdx)?.let { return it }
        }

        val csizeBits = clusterBits - 9
        val csizeSectors = (((l2Entry shr (62 - csizeBits)) and ((1L shl csizeBits) - 1)) + 1).toInt()
        val coffset = l2Entry and ((1L shl (62 - csizeBits)) - 1)
        val compressedBytes = ByteArray(csizeSectors * 512)

        synchronized(raf) {
            raf.seek(coffset)
            raf.readFully(compressedBytes)
        }

        val outBuf = ByteArray(clusterSize.toInt())
        val inflater = Inflater(true)
        try {
            inflater.setInput(compressedBytes)
            inflater.inflate(outBuf)
        } finally {
            inflater.end()
        }

        synchronized(decompressedClusterCache) {
            decompressedClusterCache.put(clusterIdx, outBuf)
        }
        return outBuf
    }

    override fun writeAt(offset: Long, buffer: ByteArray, srcOffset: Int, length: Int) {
        if (isReadOnly) throw IOException("QCOW2 BlockDevice is read-only")
        if (offset < 0 || offset + length > size) {
            throw IOException("QCOW2 write out of bounds: offset=$offset, length=$length, diskSize=$size")
        }

        val l2Entries = clusterSize / 8L
        var bytesWritten = 0

        while (bytesWritten < length) {
            val currOffset = offset + bytesWritten
            val clusterIdx = currOffset / clusterSize
            val l1Idx = (clusterIdx / l2Entries).toInt()
            val l2Idx = (clusterIdx % l2Entries).toInt()
            val offsetInCluster = (currOffset % clusterSize).toInt()
            val chunk = (clusterSize - offsetInCluster).coerceAtMost((length - bytesWritten).toLong()).toInt()

            if (l1Idx >= l1Size) throw IOException("L1 index out of range for QCOW2 write")
            val l1Entry = l1Table[l1Idx]
            val l2Offset = l1Entry and 0x00FFFFFFFFFFFF00L
            if (l2Offset == 0L) throw IOException("Write to unallocated QCOW2 L2 table is not supported")

            val l2Entry = readL2Entry(l2Offset, l2Idx)
            val isCompressed = (l2Entry and (1L shl 62)) != 0L
            if (isCompressed) throw IOException("Direct write to compressed QCOW2 cluster is not supported")

            val hostOffset = l2Entry and 0x00FFFFFFFFFFFF00L
            if (hostOffset == 0L) throw IOException("Write to unallocated QCOW2 cluster is not supported")

            synchronized(raf) {
                raf.seek(hostOffset + offsetInCluster)
                raf.write(buffer, srcOffset + bytesWritten, chunk)
            }
            bytesWritten += chunk
        }
    }

    override fun flush() {
        synchronized(raf) {
            try { raf.channel.force(true) } catch (_: Exception) {}
        }
    }

    override fun close() {}
}

/**
 * VMware VMDK Sparse BlockDevice (Monolithic Sparse).
 */
class VmdkSparseBlockDevice(
    private val raf: RandomAccessFile,
    header: ByteArray,
    override val isReadOnly: Boolean
) : BlockDevice {

    override val size: Long
    private val grainBytes: Long
    private val numGTEsPerGT: Int
    private val gdTable: IntArray
    private val compressAlgorithm: Int
    private val decompressedGrainCache = LruCache<Long, ByteArray>(32)

    init {
        val capacitySectors = VmDiskDecoder.i64le(header, 12)
        val grainSectors = VmDiskDecoder.i64le(header, 20)
        numGTEsPerGT = VmDiskDecoder.u32le(header, 44).toInt()
        val gdOffsetSectors = VmDiskDecoder.i64le(header, 56)
        compressAlgorithm = VmDiskDecoder.u16le(header, 77)

        size = capacitySectors * 512L
        grainBytes = grainSectors * 512L

        if (grainBytes <= 0 || numGTEsPerGT <= 0) {
            throw IOException("Corrupt VMDK sparse header: grainSectors=$grainSectors, numGTEsPerGT=$numGTEsPerGT")
        }

        val totalGrains = (size + grainBytes - 1) / grainBytes
        val numGTs = ((totalGrains + numGTEsPerGT - 1) / numGTEsPerGT).toInt()

        val gdBytes = ByteArray(numGTs * 4)
        synchronized(raf) {
            raf.seek(gdOffsetSectors * 512L)
            raf.readFully(gdBytes)
        }
        gdTable = IntArray(numGTs) { i ->
            VmDiskDecoder.u32le(gdBytes, i * 4).toInt()
        }
    }

    override fun readAt(offset: Long, buffer: ByteArray, dstOffset: Int, length: Int): Int {
        if (offset < 0 || offset >= size || length <= 0) return 0
        val toRead = length.toLong().coerceAtMost(size - offset).toInt()
        var bytesRead = 0

        while (bytesRead < toRead) {
            val currOffset = offset + bytesRead
            val grainIdx = currOffset / grainBytes
            val gdIdx = (grainIdx / numGTEsPerGT).toInt()
            val gtIdx = (grainIdx % numGTEsPerGT).toInt()
            val offsetInGrain = (currOffset % grainBytes).toInt()
            val chunk = (grainBytes - offsetInGrain).coerceAtMost((toRead - bytesRead).toLong()).toInt()

            if (gdIdx >= gdTable.size) break
            val gtSector = gdTable[gdIdx]

            if (gtSector == 0) {
                buffer.fill(0, dstOffset + bytesRead, dstOffset + bytesRead + chunk)
            } else {
                val gtEntryBytes = ByteArray(4)
                synchronized(raf) {
                    raf.seek((gtSector.toLong() and 0xFFFFFFFFL) * 512L + gtIdx * 4L)
                    raf.readFully(gtEntryBytes)
                }
                val grainSector = VmDiskDecoder.u32le(gtEntryBytes, 0)
                if (grainSector == 0L) {
                    buffer.fill(0, dstOffset + bytesRead, dstOffset + bytesRead + chunk)
                } else if (compressAlgorithm == 0) {
                    val physOffset = (grainSector and 0xFFFFFFFFL) * 512L + offsetInGrain
                    synchronized(raf) {
                        raf.seek(physOffset)
                        raf.readFully(buffer, dstOffset + bytesRead, chunk)
                    }
                } else {
                    val grainBuf = getOrDecompressGrain(grainIdx, grainSector)
                    System.arraycopy(grainBuf, offsetInGrain, buffer, dstOffset + bytesRead, chunk)
                }
            }

            bytesRead += chunk
        }

        return bytesRead
    }

    private fun getOrDecompressGrain(grainIdx: Long, grainSector: Long): ByteArray {
        synchronized(decompressedGrainCache) {
            decompressedGrainCache.get(grainIdx)?.let { return it }
        }

        val marker = ByteArray(12)
        synchronized(raf) {
            raf.seek(grainSector * 512L)
            raf.readFully(marker)
        }
        val compSize = VmDiskDecoder.u32le(marker, 8).toInt()
        val compData = ByteArray(compSize)
        synchronized(raf) {
            raf.seek(grainSector * 512L + 12L)
            raf.readFully(compData)
        }

        val outBuf = ByteArray(grainBytes.toInt())
        val inflater = Inflater(true)
        try {
            inflater.setInput(compData)
            inflater.inflate(outBuf)
        } finally {
            inflater.end()
        }

        synchronized(decompressedGrainCache) {
            decompressedGrainCache.put(grainIdx, outBuf)
        }
        return outBuf
    }

    override fun writeAt(offset: Long, buffer: ByteArray, srcOffset: Int, length: Int) {
        if (isReadOnly) throw IOException("VMDK BlockDevice is read-only")
        if (compressAlgorithm != 0) throw IOException("Direct write to compressed VMDK is not supported")
        if (offset < 0 || offset + length > size) {
            throw IOException("VMDK write out of bounds: offset=$offset, length=$length, diskSize=$size")
        }

        var bytesWritten = 0
        while (bytesWritten < length) {
            val currOffset = offset + bytesWritten
            val grainIdx = currOffset / grainBytes
            val gdIdx = (grainIdx / numGTEsPerGT).toInt()
            val gtIdx = (grainIdx % numGTEsPerGT).toInt()
            val offsetInGrain = (currOffset % grainBytes).toInt()
            val chunk = (grainBytes - offsetInGrain).coerceAtMost((length - bytesWritten).toLong()).toInt()

            if (gdIdx >= gdTable.size) throw IOException("GD index out of bounds")
            val gtSector = gdTable[gdIdx]
            if (gtSector == 0) throw IOException("Write to unallocated VMDK GT table is not supported")

            val gtEntryBytes = ByteArray(4)
            synchronized(raf) {
                raf.seek((gtSector.toLong() and 0xFFFFFFFFL) * 512L + gtIdx * 4L)
                raf.readFully(gtEntryBytes)
            }
            val grainSector = VmDiskDecoder.u32le(gtEntryBytes, 0)
            if (grainSector == 0L) throw IOException("Write to unallocated VMDK grain is not supported")

            val physOffset = (grainSector and 0xFFFFFFFFL) * 512L + offsetInGrain
            synchronized(raf) {
                raf.seek(physOffset)
                raf.write(buffer, srcOffset + bytesWritten, chunk)
            }
            bytesWritten += chunk
        }
    }

    override fun flush() {
        synchronized(raf) {
            try { raf.channel.force(true) } catch (_: Exception) {}
        }
    }

    override fun close() {}
}

/**
 * Microsoft VHD BlockDevice (Fixed and Dynamic).
 */
class VhdBlockDevice(
    private val raf: RandomAccessFile,
    footer: ByteArray,
    override val isReadOnly: Boolean,
    private val isFooterAtEof: Boolean
) : BlockDevice {

    override val size: Long
    private val diskType: Int
    private val isFixed: Boolean
    private val blockSize: Int
    private val maxTableEntries: Int
    private val bat: IntArray?
    private val bitmapSectors: Int

    init {
        diskType = VmDiskDecoder.u32be(footer, 60).toInt()
        size = VmDiskDecoder.u64be(footer, 40)
        isFixed = (diskType == 2)

        if (isFixed) {
            blockSize = 0
            maxTableEntries = 0
            bat = null
            bitmapSectors = 0
        } else {
            // Dynamic VHD
            val dataOffset = VmDiskDecoder.u64be(footer, 16)
            val dynHeader = ByteArray(1024)
            synchronized(raf) {
                raf.seek(dataOffset)
                raf.readFully(dynHeader)
            }
            val tableOffset = VmDiskDecoder.u64be(dynHeader, 16)
            maxTableEntries = VmDiskDecoder.u32be(dynHeader, 28).toInt()
            blockSize = VmDiskDecoder.u32be(dynHeader, 32).toInt()

            val bitmapBytes = (blockSize / 512 + 7) / 8
            bitmapSectors = (bitmapBytes + 511) / 512

            val batBytes = ByteArray(maxTableEntries * 4)
            synchronized(raf) {
                raf.seek(tableOffset)
                raf.readFully(batBytes)
            }
            bat = IntArray(maxTableEntries) { i ->
                VmDiskDecoder.u32be(batBytes, i * 4).toInt()
            }
        }
    }

    override fun readAt(offset: Long, buffer: ByteArray, dstOffset: Int, length: Int): Int {
        if (offset < 0 || offset >= size || length <= 0) return 0
        val toRead = length.toLong().coerceAtMost(size - offset).toInt()

        if (isFixed) {
            synchronized(raf) {
                raf.seek(offset)
                return raf.read(buffer, dstOffset, toRead)
            }
        }

        var bytesRead = 0
        val batArr = bat ?: return 0

        while (bytesRead < toRead) {
            val currOffset = offset + bytesRead
            val blockIdx = (currOffset / blockSize).toInt()
            val offsetInBlock = (currOffset % blockSize).toInt()
            val chunk = (blockSize - offsetInBlock).coerceAtMost(toRead - bytesRead)

            if (blockIdx >= maxTableEntries) break
            val batEntry = batArr[blockIdx]

            if (batEntry == -1 || (batEntry.toLong() and 0xFFFFFFFFL) == 0xFFFFFFFFL) {
                buffer.fill(0, dstOffset + bytesRead, dstOffset + bytesRead + chunk)
            } else {
                val blockSector = batEntry.toLong() and 0xFFFFFFFFL
                val blockDataOffset = (blockSector * 512L) + (bitmapSectors * 512L)
                val physOffset = blockDataOffset + offsetInBlock

                synchronized(raf) {
                    raf.seek(physOffset)
                    raf.readFully(buffer, dstOffset + bytesRead, chunk)
                }
            }

            bytesRead += chunk
        }

        return bytesRead
    }

    override fun writeAt(offset: Long, buffer: ByteArray, srcOffset: Int, length: Int) {
        if (isReadOnly) throw IOException("VHD BlockDevice is read-only")
        if (offset < 0 || offset + length > size) {
            throw IOException("VHD write out of bounds: offset=$offset, length=$length, diskSize=$size")
        }

        if (isFixed) {
            synchronized(raf) {
                raf.seek(offset)
                raf.write(buffer, srcOffset, length)
            }
            return
        }

        val batArr = bat ?: throw IOException("Dynamic VHD BAT missing")
        var bytesWritten = 0

        while (bytesWritten < length) {
            val currOffset = offset + bytesWritten
            val blockIdx = (currOffset / blockSize).toInt()
            val offsetInBlock = (currOffset % blockSize).toInt()
            val chunk = (blockSize - offsetInBlock).coerceAtMost(length - bytesWritten)

            if (blockIdx >= maxTableEntries) throw IOException("Block index out of bounds in VHD")
            val batEntry = batArr[blockIdx]
            if (batEntry == -1 || (batEntry.toLong() and 0xFFFFFFFFL) == 0xFFFFFFFFL) {
                throw IOException("Write to unallocated VHD block is not supported")
            }

            val blockSector = batEntry.toLong() and 0xFFFFFFFFL
            val blockDataOffset = (blockSector * 512L) + (bitmapSectors * 512L)
            val physOffset = blockDataOffset + offsetInBlock

            synchronized(raf) {
                raf.seek(physOffset)
                raf.write(buffer, srcOffset + bytesWritten, chunk)

                // Update sector bitmap bit to 1
                val sectorInBlock = offsetInBlock / 512
                val bitmapByteOffset = (blockSector * 512L) + (sectorInBlock / 8)
                raf.seek(bitmapByteOffset)
                val curByte = raf.read()
                if (curByte != -1) {
                    val newByte = curByte or (0x80 ushr (sectorInBlock % 8))
                    raf.seek(bitmapByteOffset)
                    raf.write(newByte)
                }
            }
            bytesWritten += chunk
        }
    }

    override fun flush() {
        synchronized(raf) {
            try { raf.channel.force(true) } catch (_: Exception) {}
        }
    }

    override fun close() {}
}

/**
 * Microsoft VHDX BlockDevice (Hyper-V Virtual Hard Disk v2).
 */
class VhdxBlockDevice(
    private val raf: RandomAccessFile,
    override val isReadOnly: Boolean
) : BlockDevice {

    override val size: Long
    private val blockSize: Int
    private val batOffset: Long
    private val chunkRatio: Long

    init {
        // Read region table at 192KB (0x30000) or 256KB (0x40000)
        var regionBytes = ByteArray(65536)
        var validRegion = false
        for (candOffset in listOf(0x30000L, 0x40000L)) {
            synchronized(raf) {
                raf.seek(candOffset)
                raf.readFully(regionBytes)
            }
            if (String(regionBytes, 0, 4, StandardCharsets.US_ASCII) == "regi") {
                validRegion = true
                break
            }
        }
        if (!validRegion) throw IOException("VHDX region table not found")

        val entryCount = VmDiskDecoder.u32le(regionBytes, 8).toInt().coerceIn(1, 2048)
        var batOff = 0L
        var metaOff = 0L

        // BAT GUID: 2DC27766-F623-4200-9D64-115E9BFD4A08
        // Little-endian bytes: 66 77 C2 2D 23 F6 00 42 9D 64 11 5E 9B FD 4A 08
        val batGuid = byteArrayOf(
            0x66.toByte(), 0x77.toByte(), 0xC2.toByte(), 0x2D.toByte(),
            0x23.toByte(), 0xF6.toByte(), 0x00.toByte(), 0x42.toByte(),
            0x9D.toByte(), 0x64.toByte(), 0x11.toByte(), 0x5E.toByte(),
            0x9B.toByte(), 0xFD.toByte(), 0x4A.toByte(), 0x08.toByte()
        )
        // Metadata GUID: 8B7D40A2-B44E-4D43-B31B-423637464C9A
        // Little-endian bytes: A2 40 7D 8B 4E B4 43 4D B3 1B 42 36 37 46 4C 9A
        val metaGuid = byteArrayOf(
            0xA2.toByte(), 0x40.toByte(), 0x7D.toByte(), 0x8B.toByte(),
            0x4E.toByte(), 0xB4.toByte(), 0x43.toByte(), 0x4D.toByte(),
            0xB3.toByte(), 0x1B.toByte(), 0x42.toByte(), 0x36.toByte(),
            0x37.toByte(), 0x46.toByte(), 0x4C.toByte(), 0x9A.toByte()
        )

        for (i in 0 until entryCount) {
            val ep = 16 + i * 32
            if (ep + 32 > regionBytes.size) break
            val isBat = (0 until 16).all { regionBytes[ep + it] == batGuid[it] }
            val isMeta = (0 until 16).all { regionBytes[ep + it] == metaGuid[it] }
            val off = VmDiskDecoder.i64le(regionBytes, ep + 16)
            if (isBat) batOff = off
            if (isMeta) metaOff = off
        }

        if (batOff == 0L || metaOff == 0L) throw IOException("VHDX missing BAT or Metadata region")
        batOffset = batOff

        // Read Metadata table
        val metaBytes = ByteArray(65536)
        synchronized(raf) {
            raf.seek(metaOff)
            raf.readFully(metaBytes)
        }
        if (String(metaBytes, 0, 8, StandardCharsets.US_ASCII) != "metadata") {
            throw IOException("Corrupt VHDX metadata table")
        }

        val metaEntries = VmDiskDecoder.u16le(metaBytes, 10)
        var parsedBlockSize = 32 * 1024 * 1024
        var parsedVirtualSize = 0L
        var parsedLogicalSectorSize = 512

        // File Parameters GUID: CAA16737-FA36-4D43-B3B6-33F0AA0EAD67
        val fileParamsGuid = byteArrayOf(
            0x37.toByte(), 0x67.toByte(), 0xA1.toByte(), 0xCA.toByte(),
            0x36.toByte(), 0xFA.toByte(), 0x43.toByte(), 0x4D.toByte(),
            0xB3.toByte(), 0xB6.toByte(), 0x33.toByte(), 0xF0.toByte(),
            0xAA.toByte(), 0x0E.toByte(), 0xAD.toByte(), 0x67.toByte()
        )
        // Virtual Disk Size GUID: 2FA54261-B276-4ECF-B3B6-733848995C24
        val diskSizeGuid = byteArrayOf(
            0x61.toByte(), 0x42.toByte(), 0xA5.toByte(), 0x2F.toByte(),
            0x76.toByte(), 0xB2.toByte(), 0xCF.toByte(), 0x4E.toByte(),
            0xB3.toByte(), 0xB6.toByte(), 0x73.toByte(), 0x38.toByte(),
            0x48.toByte(), 0x99.toByte(), 0x5C.toByte(), 0x24.toByte()
        )
        // Logical Sector Size GUID: 8141BF1D-ED6F-4709-AB77-9E99345837CE
        val sectorSizeGuid = byteArrayOf(
            0x1D.toByte(), 0xBF.toByte(), 0x41.toByte(), 0x81.toByte(),
            0x6F.toByte(), 0xED.toByte(), 0x09.toByte(), 0x47.toByte(),
            0xAB.toByte(), 0x77.toByte(), 0x9E.toByte(), 0x99.toByte(),
            0x34.toByte(), 0x58.toByte(), 0x37.toByte(), 0xCE.toByte()
        )

        for (i in 0 until metaEntries) {
            val mp = 32 + i * 32
            if (mp + 32 > metaBytes.size) break
            val itemOffset = VmDiskDecoder.u32le(metaBytes, mp + 16).toInt()
            val isParams = (0 until 16).all { metaBytes[mp + it] == fileParamsGuid[it] }
            val isDiskSize = (0 until 16).all { metaBytes[mp + it] == diskSizeGuid[it] }
            val isSectorSize = (0 until 16).all { metaBytes[mp + it] == sectorSizeGuid[it] }

            if (isParams && itemOffset + 4 <= metaBytes.size) {
                parsedBlockSize = VmDiskDecoder.u32le(metaBytes, itemOffset).toInt()
            }
            if (isDiskSize && itemOffset + 8 <= metaBytes.size) {
                parsedVirtualSize = VmDiskDecoder.i64le(metaBytes, itemOffset)
            }
            if (isSectorSize && itemOffset + 4 <= metaBytes.size) {
                parsedLogicalSectorSize = VmDiskDecoder.u32le(metaBytes, itemOffset).toInt()
            }
        }

        blockSize = parsedBlockSize
        size = parsedVirtualSize
        chunkRatio = (1L shl 23) * parsedLogicalSectorSize / blockSize
    }

    override fun readAt(offset: Long, buffer: ByteArray, dstOffset: Int, length: Int): Int {
        if (offset < 0 || offset >= size || length <= 0) return 0
        val toRead = length.toLong().coerceAtMost(size - offset).toInt()
        var bytesRead = 0

        while (bytesRead < toRead) {
            val currOffset = offset + bytesRead
            val blockIdx = (currOffset / blockSize).toInt()
            val offsetInBlock = (currOffset % blockSize).toInt()
            val chunk = (blockSize - offsetInBlock).coerceAtMost(toRead - bytesRead)

            val batIndex = if (chunkRatio > 0) blockIdx + (blockIdx / chunkRatio).toInt() else blockIdx
            val batEntryBytes = ByteArray(8)
            synchronized(raf) {
                raf.seek(batOffset + batIndex * 8L)
                raf.readFully(batEntryBytes)
            }
            val batEntry = VmDiskDecoder.i64le(batEntryBytes, 0)
            val state = (batEntry and 0x7L).toInt()

            if (state == 6) {
                // PAYLOAD_BLOCK_FULLY_PRESENT
                val fileOffset = (batEntry ushr 20) shl 20
                val physOffset = fileOffset + offsetInBlock
                synchronized(raf) {
                    raf.seek(physOffset)
                    raf.readFully(buffer, dstOffset + bytesRead, chunk)
                }
            } else {
                buffer.fill(0, dstOffset + bytesRead, dstOffset + bytesRead + chunk)
            }

            bytesRead += chunk
        }

        return bytesRead
    }

    override fun writeAt(offset: Long, buffer: ByteArray, srcOffset: Int, length: Int) {
        if (isReadOnly) throw IOException("VHDX BlockDevice is read-only")
        if (offset < 0 || offset + length > size) {
            throw IOException("VHDX write out of bounds: offset=$offset, length=$length, diskSize=$size")
        }

        var bytesWritten = 0
        while (bytesWritten < length) {
            val currOffset = offset + bytesWritten
            val blockIdx = (currOffset / blockSize).toInt()
            val offsetInBlock = (currOffset % blockSize).toInt()
            val chunk = (blockSize - offsetInBlock).coerceAtMost(length - bytesWritten)

            val batIndex = if (chunkRatio > 0) blockIdx + (blockIdx / chunkRatio).toInt() else blockIdx
            val batEntryBytes = ByteArray(8)
            synchronized(raf) {
                raf.seek(batOffset + batIndex * 8L)
                raf.readFully(batEntryBytes)
            }
            val batEntry = VmDiskDecoder.i64le(batEntryBytes, 0)
            val state = (batEntry and 0x7L).toInt()

            if (state != 6) {
                throw IOException("Write to unallocated VHDX payload block is not supported")
            }

            val fileOffset = (batEntry ushr 20) shl 20
            val physOffset = fileOffset + offsetInBlock
            synchronized(raf) {
                raf.seek(physOffset)
                raf.write(buffer, srcOffset + bytesWritten, chunk)
            }
            bytesWritten += chunk
        }
    }

    override fun flush() {
        synchronized(raf) {
            try { raf.channel.force(true) } catch (_: Exception) {}
        }
    }

    override fun close() {}
}
