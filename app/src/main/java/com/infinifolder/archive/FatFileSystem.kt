package com.infinifolder.archive

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Pure-Kotlin FAT12, FAT16, and FAT32 reader and writer supporting:
 * - VFAT Long File Names (LFN)
 * - File and directory creation
 * - In-place and appending writes
 * - File and directory deletion
 * - Multi-FAT synchronization
 */
class FatFileSystem(
    val dev: BlockDevice,
    val baseOffset: Long = 0L,
    val prefix: String = ""
) {

    enum class FatType { FAT12, FAT16, FAT32 }

    val bpb: ByteArray
    val bytesPerSector: Int
    val sectorsPerCluster: Int
    val reservedSectors: Int
    val numFats: Int
    val rootEntryCount: Int
    val totalSectors: Long
    val sectorsPerFat: Long
    val rootDirSectors: Long
    val firstDataSector: Long
    val dataSectors: Long
    val totalClusters: Long
    val fatType: FatType
    val fatStartOffset: Long
    val clusterSize: Int
    val dataStartOffset: Long
    val rootOffset: Long
    val rootCluster: Long

    init {
        bpb = dev.readBytes(baseOffset, 512)
        if (bpb.size < 512) throw IOException("Failed to read FAT BPB at offset $baseOffset")
        bytesPerSector = u16le(bpb, 11)
        sectorsPerCluster = u8(bpb, 13)
        reservedSectors = u16le(bpb, 14)
        numFats = u8(bpb, 16)
        rootEntryCount = u16le(bpb, 17)
        val totalSectors16 = u16le(bpb, 19)
        val sectorsPerFat16 = u16le(bpb, 22)
        val totalSectors32 = u32le(bpb, 32)
        totalSectors = if (totalSectors16 != 0) totalSectors16.toLong() else totalSectors32
        sectorsPerFat = if (sectorsPerFat16 != 0) sectorsPerFat16.toLong() else u32le(bpb, 36)

        rootDirSectors = if (bytesPerSector > 0) (((rootEntryCount * 32) + (bytesPerSector - 1)) / bytesPerSector).toLong() else 0L
        firstDataSector = reservedSectors.toLong() + (numFats.toLong() * sectorsPerFat) + rootDirSectors
        dataSectors = (totalSectors - firstDataSector).coerceAtLeast(0L)
        totalClusters = if (sectorsPerCluster > 0) dataSectors / sectorsPerCluster else 0L

        fatType = when {
            sectorsPerFat16 == 0 && rootEntryCount == 0 -> FatType.FAT32
            totalClusters < 4085 -> FatType.FAT12
            totalClusters < 65525 -> FatType.FAT16
            else -> FatType.FAT32
        }

        fatStartOffset = baseOffset + reservedSectors.toLong() * bytesPerSector
        clusterSize = bytesPerSector * sectorsPerCluster
        dataStartOffset = baseOffset + firstDataSector * bytesPerSector
        rootOffset = baseOffset + (reservedSectors.toLong() + numFats.toLong() * sectorsPerFat) * bytesPerSector
        rootCluster = if (fatType == FatType.FAT32) (u32le(bpb, 44) and 0x0FFFFFFFL) else 0L
    }

    // =========================================================================
    // Cluster Chain Navigation and Modification
    // =========================================================================

    fun getNextCluster(cluster: Long): Long? {
        if (cluster < 2L) return null
        return when (fatType) {
            FatType.FAT12 -> {
                val entOffset = fatStartOffset + cluster + (cluster / 2)
                val pair = dev.readBytes(entOffset, 2)
                if (pair.size < 2) return null
                val raw = u16le(pair, 0)
                val next = if (cluster % 2L == 0L) (raw and 0x0FFF) else (raw ushr 4)
                if (next in 2..0xFF6) next.toLong() else null
            }
            FatType.FAT16 -> {
                val entOffset = fatStartOffset + cluster * 2L
                val pair = dev.readBytes(entOffset, 2)
                if (pair.size < 2) return null
                val next = u16le(pair, 0)
                if (next in 2..0xFFF6) next.toLong() else null
            }
            FatType.FAT32 -> {
                val entOffset = fatStartOffset + cluster * 4L
                val quad = dev.readBytes(entOffset, 4)
                if (quad.size < 4) return null
                val next = u32le(quad, 0) and 0x0FFFFFFFL
                if (next in 2L..0x0FFFFFF6L) next else null
            }
        }
    }

    fun getClusterChain(startCluster: Long, maxClusters: Int = 262144): List<Long> {
        if (startCluster < 2L) return emptyList()
        val chain = mutableListOf<Long>()
        val visited = HashSet<Long>()
        var curr: Long? = startCluster
        while (curr != null && curr >= 2L && visited.add(curr) && chain.size < maxClusters) {
            chain += curr
            curr = getNextCluster(curr)
        }
        return chain
    }

    fun setNextCluster(cluster: Long, next: Long) {
        if (cluster < 2L) return
        for (f in 0 until numFats) {
            val fatOffset = fatStartOffset + (f * sectorsPerFat * bytesPerSector)
            when (fatType) {
                FatType.FAT12 -> {
                    val entOffset = fatOffset + cluster + (cluster / 2)
                    val pair = dev.readBytes(entOffset, 2)
                    var raw = if (pair.size >= 2) u16le(pair, 0) else 0
                    val n = (next and 0x0FFF).toInt()
                    raw = if (cluster % 2L == 0L) {
                        (raw and 0xF000) or n
                    } else {
                        (raw and 0x000F) or (n shl 4)
                    }
                    val outBuf = byteArrayOf((raw and 0xFF).toByte(), ((raw ushr 8) and 0xFF).toByte())
                    dev.writeBytes(entOffset, outBuf)
                }
                FatType.FAT16 -> {
                    val entOffset = fatOffset + cluster * 2L
                    val outBuf = byteArrayOf((next and 0xFF).toByte(), ((next ushr 8) and 0xFF).toByte())
                    dev.writeBytes(entOffset, outBuf)
                }
                FatType.FAT32 -> {
                    val entOffset = fatOffset + cluster * 4L
                    val oldBuf = dev.readBytes(entOffset, 4)
                    val oldVal = if (oldBuf.size >= 4) u32le(oldBuf, 0) else 0L
                    val newVal = (oldVal and 0xF0000000L) or (next and 0x0FFFFFFFL)
                    val outBuf = ByteArray(4)
                    outBuf[0] = (newVal and 0xFF).toByte()
                    outBuf[1] = ((newVal ushr 8) and 0xFF).toByte()
                    outBuf[2] = ((newVal ushr 16) and 0xFF).toByte()
                    outBuf[3] = ((newVal ushr 24) and 0xFF).toByte()
                    dev.writeBytes(entOffset, outBuf)
                }
            }
        }
    }

    private fun isClusterFree(cluster: Long): Boolean {
        if (cluster < 2L) return false
        val entOffset = when (fatType) {
            FatType.FAT12 -> fatStartOffset + cluster + (cluster / 2)
            FatType.FAT16 -> fatStartOffset + cluster * 2L
            FatType.FAT32 -> fatStartOffset + cluster * 4L
        }
        val bytes = dev.readBytes(entOffset, if (fatType == FatType.FAT32) 4 else 2)
        return when (fatType) {
            FatType.FAT12 -> {
                if (bytes.size < 2) false
                else {
                    val raw = u16le(bytes, 0)
                    val v = if (cluster % 2L == 0L) (raw and 0x0FFF) else (raw ushr 4)
                    v == 0
                }
            }
            FatType.FAT16 -> {
                if (bytes.size < 2) false else u16le(bytes, 0) == 0
            }
            FatType.FAT32 -> {
                if (bytes.size < 4) false else (u32le(bytes, 0) and 0x0FFFFFFFL) == 0L
            }
        }
    }

    fun allocateClusters(count: Int): List<Long> {
        if (count <= 0) return emptyList()
        val allocated = mutableListOf<Long>()
        val maxCluster = totalClusters + 2L
        var c = 2L
        while (c < maxCluster && allocated.size < count) {
            if (isClusterFree(c)) {
                allocated += c
            }
            c++
        }
        if (allocated.size < count) {
            throw IOException("Disk full: insufficient free clusters (needed $count, found ${allocated.size})")
        }

        val eofMarker = when (fatType) {
            FatType.FAT12 -> 0x0FFFL
            FatType.FAT16 -> 0xFFFFL
            FatType.FAT32 -> 0x0FFFFFFFL
        }

        for (i in 0 until allocated.size - 1) {
            setNextCluster(allocated[i], allocated[i + 1])
        }
        setNextCluster(allocated.last(), eofMarker)
        return allocated
    }

    fun freeClusterChain(startCluster: Long) {
        val chain = getClusterChain(startCluster)
        for (c in chain) {
            setNextCluster(c, 0L)
        }
    }

    // =========================================================================
    // Reading Entries
    // =========================================================================

    fun readEntries(): List<DiscImageReader.DiscEntry> {
        val entries = mutableListOf<DiscImageReader.DiscEntry>()
        val visitedDirs = HashSet<Long>()

        fun readClusterChainBytes(startCluster: Long, maxBytes: Int = 8 * 1024 * 1024): ByteArray {
            val chain = getClusterChain(startCluster, (maxBytes / clusterSize).coerceAtLeast(16))
            val out = ByteArrayOutputStream(chain.size * clusterSize)
            for (c in chain) {
                val cOffset = dataStartOffset + (c - 2L) * clusterSize
                val chunk = dev.readBytes(cOffset, clusterSize)
                out.write(chunk)
            }
            return out.toByteArray()
        }

        fun parseDirectoryBytes(dirBytes: ByteArray, currentPath: String) {
            val lfnParts = sortedMapOf<Int, String>()
            var pos = 0
            while (pos + 32 <= dirBytes.size) {
                val firstByte = u8(dirBytes, pos)
                if (firstByte == 0x00) break
                if (firstByte == 0xE5) {
                    lfnParts.clear()
                    pos += 32
                    continue
                }
                val attr = u8(dirBytes, pos + 11)
                if (attr == 0x0F) {
                    val seq = firstByte and 0x1F
                    if ((firstByte and 0x40) != 0) {
                        lfnParts.clear()
                    }
                    lfnParts[seq] = decodeVfatLfnEntry(dirBytes, pos)
                    pos += 32
                    continue
                }
                if ((attr and 0x08) != 0) {
                    lfnParts.clear()
                    pos += 32
                    continue
                }

                val name = if (lfnParts.isNotEmpty()) {
                    val joined = lfnParts.values.joinToString("")
                    lfnParts.clear()
                    joined.substringBefore('\u0000').substringBefore('\uFFFF').trim()
                } else {
                    decodeFatShortName(dirBytes, pos)
                }

                val isDir = (attr and 0x10) != 0
                val clusterHi = if (fatType == FatType.FAT32) u16le(dirBytes, pos + 20).toLong() else 0L
                val clusterLo = u16le(dirBytes, pos + 26).toLong()
                val startCluster = (clusterHi shl 16) or clusterLo
                val fileSize = u32le(dirBytes, pos + 28)
                val wrtTime = u16le(dirBytes, pos + 22)
                val wrtDate = u16le(dirBytes, pos + 24)
                val modifiedAtMillis = decodeDosDateTime(wrtDate, wrtTime)

                pos += 32

                if (name.isEmpty() || name == "." || name == "..") continue
                val childPath = joinPath(currentPath, name)

                if (isDir) {
                    entries += DiscImageReader.DiscEntry(
                        path = childPath,
                        isDirectory = true,
                        sizeBytes = null,
                        modifiedAtMillis = modifiedAtMillis
                    )
                    if (startCluster >= 2L && visitedDirs.add(startCluster)) {
                        val subDirBytes = readClusterChainBytes(startCluster)
                        parseDirectoryBytes(subDirBytes, childPath)
                    }
                } else {
                    entries += DiscImageReader.DiscEntry(
                        path = childPath,
                        isDirectory = false,
                        sizeBytes = fileSize,
                        modifiedAtMillis = modifiedAtMillis,
                        extractor = { d, out ->
                            var remaining = fileSize
                            if (remaining > 0L && startCluster >= 2L) {
                                val chain = getClusterChain(startCluster)
                                for (c in chain) {
                                    if (remaining <= 0L) break
                                    val cOffset = dataStartOffset + (c - 2L) * clusterSize
                                    val toRead = remaining.coerceAtMost(clusterSize.toLong()).toInt()
                                    val chunk = d.readBytes(cOffset, toRead)
                                    out.write(chunk)
                                    remaining -= toRead
                                }
                            }
                        }
                    )
                }
            }
        }

        if (fatType == FatType.FAT32) {
            visitedDirs.add(rootCluster)
            val rootBytes = readClusterChainBytes(rootCluster)
            parseDirectoryBytes(rootBytes, prefix)
        } else {
            val rootBytes = dev.readBytes(rootOffset, rootEntryCount * 32)
            parseDirectoryBytes(rootBytes, prefix)
        }

        return entries
    }

    // =========================================================================
    // Directory Management and Free Slot Allocation
    // =========================================================================

    private data class ParsedDirItem(
        val name: String,
        val isDirectory: Boolean,
        val startCluster: Long,
        val fileSize: Long,
        val slotDevOffset: Long,
        val lfnStartSlotDevOffset: Long
    )

    private fun listDirectoryItems(dirCluster: Long): List<ParsedDirItem> {
        val items = mutableListOf<ParsedDirItem>()
        val lfnParts = sortedMapOf<Int, String>()
        var lfnFirstSlotOffset = -1L

        val (dirBytes, slotOffsets) = readDirectoryBytesWithOffsets(dirCluster)
        var pos = 0
        while (pos + 32 <= dirBytes.size) {
            val slotOffset = slotOffsets[pos / 32]
            val firstByte = u8(dirBytes, pos)
            if (firstByte == 0x00) break
            if (firstByte == 0xE5) {
                lfnParts.clear()
                lfnFirstSlotOffset = -1L
                pos += 32
                continue
            }
            val attr = u8(dirBytes, pos + 11)
            if (attr == 0x0F) {
                val seq = firstByte and 0x1F
                if ((firstByte and 0x40) != 0) {
                    lfnParts.clear()
                    lfnFirstSlotOffset = slotOffset
                }
                lfnParts[seq] = decodeVfatLfnEntry(dirBytes, pos)
                pos += 32
                continue
            }
            if ((attr and 0x08) != 0) {
                lfnParts.clear()
                lfnFirstSlotOffset = -1L
                pos += 32
                continue
            }

            val name = if (lfnParts.isNotEmpty()) {
                val joined = lfnParts.values.joinToString("")
                joined.substringBefore('\u0000').substringBefore('\uFFFF').trim()
            } else {
                decodeFatShortName(dirBytes, pos)
            }

            val isDir = (attr and 0x10) != 0
            val clusterHi = if (fatType == FatType.FAT32) u16le(dirBytes, pos + 20).toLong() else 0L
            val clusterLo = u16le(dirBytes, pos + 26).toLong()
            val startCluster = (clusterHi shl 16) or clusterLo
            val fileSize = u32le(dirBytes, pos + 28)

            val entryLfnStart = if (lfnFirstSlotOffset != -1L) lfnFirstSlotOffset else slotOffset
            lfnParts.clear()
            lfnFirstSlotOffset = -1L

            pos += 32
            if (name.isNotEmpty() && name != "." && name != "..") {
                items += ParsedDirItem(name, isDir, startCluster, fileSize, slotOffset, entryLfnStart)
            }
        }
        return items
    }

    private fun readDirectoryBytesWithOffsets(dirCluster: Long): Pair<ByteArray, List<Long>> {
        val isRoot = (fatType != FatType.FAT32 && dirCluster == 0L)
        if (isRoot) {
            val totalBytes = rootEntryCount * 32
            val bytes = dev.readBytes(rootOffset, totalBytes)
            val offsets = List(rootEntryCount) { i -> rootOffset + i * 32L }
            return Pair(bytes, offsets)
        }

        val chain = getClusterChain(if (dirCluster == 0L) rootCluster else dirCluster)
        val out = ByteArrayOutputStream(chain.size * clusterSize)
        val offsets = mutableListOf<Long>()
        for (c in chain) {
            val cOffset = dataStartOffset + (c - 2L) * clusterSize
            val chunk = dev.readBytes(cOffset, clusterSize)
            out.write(chunk)
            val entriesInCluster = clusterSize / 32
            for (i in 0 until entriesInCluster) {
                offsets += cOffset + i * 32L
            }
        }
        return Pair(out.toByteArray(), offsets)
    }

    private fun findParentDirectoryCluster(dirPath: String): Long {
        val segments = dirPath.split('/').map { it.trim() }.filter { it.isNotEmpty() }
        var currentCluster = if (fatType == FatType.FAT32) rootCluster else 0L

        for (segment in segments) {
            val items = listDirectoryItems(currentCluster)
            val match = items.firstOrNull { it.name.equals(segment, ignoreCase = true) && it.isDirectory }
                ?: throw NoSuchElementException("Directory not found: $segment in path $dirPath")
            currentCluster = match.startCluster
        }
        return currentCluster
    }

    private fun allocateDirectorySlots(dirCluster: Long, slotsNeeded: Int): List<Long> {
        val (dirBytes, slotOffsets) = readDirectoryBytesWithOffsets(dirCluster)
        var consecutiveCount = 0
        var startIndex = -1

        for (i in slotOffsets.indices) {
            val firstByte = if (i * 32 < dirBytes.size) u8(dirBytes, i * 32) else 0x00
            if (firstByte == 0x00 || firstByte == 0xE5) {
                if (consecutiveCount == 0) startIndex = i
                consecutiveCount++
                if (consecutiveCount == slotsNeeded) {
                    return (startIndex until startIndex + slotsNeeded).map { slotOffsets[it] }
                }
            } else {
                consecutiveCount = 0
                startIndex = -1
            }
        }

        val isRoot16 = (fatType != FatType.FAT32 && dirCluster == 0L)
        if (isRoot16) {
            throw IOException("Root directory is full (FAT12/FAT16 root has maximum $rootEntryCount entries)")
        }

        // Allocate a new cluster for the directory and chain it
        val newDirCluster = allocateClusters(1).first()
        val zeroCluster = ByteArray(clusterSize)
        val newClusterOffset = dataStartOffset + (newDirCluster - 2L) * clusterSize
        dev.writeBytes(newClusterOffset, zeroCluster)

        val targetCluster = if (dirCluster == 0L) rootCluster else dirCluster
        val chain = getClusterChain(targetCluster)
        if (chain.isNotEmpty()) {
            setNextCluster(chain.last(), newDirCluster)
        } else {
            setNextCluster(targetCluster, newDirCluster)
        }

        return (0 until slotsNeeded).map { i -> newClusterOffset + i * 32L }
    }

    // =========================================================================
    // Write Operations: Create File, Create Directory, Delete Entry
    // =========================================================================

    fun createFile(relativePath: String, data: ByteArray) {
        val cleanPath = normalizePath(relativePath)
        val parentPath = cleanPath.substringBeforeLast('/', "")
        val fileName = cleanPath.substringAfterLast('/')
        if (fileName.isEmpty()) throw IllegalArgumentException("Empty file name")

        val parentCluster = findParentDirectoryCluster(parentPath)
        val existingItems = listDirectoryItems(parentCluster)
        val existingNames = existingItems.map { it.name }.toSet()

        // If file already exists, overwrite it
        val existing = existingItems.firstOrNull { it.name.equals(fileName, ignoreCase = true) }
        if (existing != null) {
            if (existing.isDirectory) throw IOException("Cannot overwrite directory '$fileName' with a file")
            writeEntry(relativePath, data)
            return
        }

        // Allocate clusters for data
        val clustersNeeded = if (data.isEmpty()) 0 else (data.size + clusterSize - 1) / clusterSize
        val chain = if (clustersNeeded > 0) allocateClusters(clustersNeeded) else emptyList()
        val startCluster = if (chain.isNotEmpty()) chain.first() else 0L

        for (i in chain.indices) {
            val c = chain[i]
            val cOffset = dataStartOffset + (c - 2L) * clusterSize
            val startByte = i * clusterSize
            val endByte = (startByte + clusterSize).coerceAtMost(data.size)
            val chunk = data.copyOfRange(startByte, endByte)
            dev.writeBytes(cOffset, chunk)
        }

        // Generate 8.3 name and VFAT entries
        val shortName11 = generate83Name(fileName, existingNames)
        val lfnEntries = if (needsLfn(fileName, shortName11)) createVfatEntries(fileName, shortName11) else emptyList()
        val totalSlotsNeeded = lfnEntries.size + 1
        val slotOffsets = allocateDirectorySlots(parentCluster, totalSlotsNeeded)

        // Write LFN slots in order
        for (i in lfnEntries.indices) {
            dev.writeBytes(slotOffsets[i], lfnEntries[i])
        }

        // Write main 32-byte dir entry
        val mainSlotOffset = slotOffsets.last()
        val dirEntryBytes = createDirEntryBytes(
            shortName11 = shortName11,
            attr = 0x20, // Archive
            startCluster = startCluster,
            fileSize = data.size.toLong()
        )
        dev.writeBytes(mainSlotOffset, dirEntryBytes)
        dev.flush()
    }

    fun createDirectory(relativePath: String) {
        val cleanPath = normalizePath(relativePath)
        val parentPath = cleanPath.substringBeforeLast('/', "")
        val dirName = cleanPath.substringAfterLast('/')
        if (dirName.isEmpty()) throw IllegalArgumentException("Empty directory name")

        val parentCluster = findParentDirectoryCluster(parentPath)
        val existingItems = listDirectoryItems(parentCluster)
        if (existingItems.any { it.name.equals(dirName, ignoreCase = true) }) {
            throw IOException("File or directory already exists: $dirName")
        }

        // Allocate 1 cluster for the new directory
        val newCluster = allocateClusters(1).first()
        val clusterOffset = dataStartOffset + (newCluster - 2L) * clusterSize
        val zeroBlock = ByteArray(clusterSize)
        dev.writeBytes(clusterOffset, zeroBlock)

        // Initialize "." and ".." inside the new directory
        val dotName = ".".padEnd(11, ' ').toByteArray(StandardCharsets.US_ASCII)
        val dotDotName = "..".padEnd(11, ' ').toByteArray(StandardCharsets.US_ASCII)
        val dotEntry = createDirEntryBytes(dotName, attr = 0x10, startCluster = newCluster, fileSize = 0L)
        val dotDotCluster = if (parentCluster == rootCluster) 0L else parentCluster
        val dotDotEntry = createDirEntryBytes(dotDotName, attr = 0x10, startCluster = dotDotCluster, fileSize = 0L)

        dev.writeBytes(clusterOffset, dotEntry)
        dev.writeBytes(clusterOffset + 32L, dotDotEntry)

        // Generate 8.3 name and VFAT entries in parent directory
        val shortName11 = generate83Name(dirName, existingItems.map { it.name }.toSet())
        val lfnEntries = if (needsLfn(dirName, shortName11)) createVfatEntries(dirName, shortName11) else emptyList()
        val totalSlots = lfnEntries.size + 1
        val slotOffsets = allocateDirectorySlots(parentCluster, totalSlots)

        for (i in lfnEntries.indices) {
            dev.writeBytes(slotOffsets[i], lfnEntries[i])
        }

        val mainDirEntry = createDirEntryBytes(
            shortName11 = shortName11,
            attr = 0x10, // Directory
            startCluster = newCluster,
            fileSize = 0L
        )
        dev.writeBytes(slotOffsets.last(), mainDirEntry)
        dev.flush()
    }

    fun writeEntry(relativePath: String, data: ByteArray) {
        val cleanPath = normalizePath(relativePath)
        val parentPath = cleanPath.substringBeforeLast('/', "")
        val fileName = cleanPath.substringAfterLast('/')

        val parentCluster = findParentDirectoryCluster(parentPath)
        val existingItems = listDirectoryItems(parentCluster)
        val match = existingItems.firstOrNull { it.name.equals(fileName, ignoreCase = true) }

        if (match == null) {
            createFile(relativePath, data)
            return
        }
        if (match.isDirectory) {
            throw IOException("Cannot write file content to directory '$fileName'")
        }

        // Free old clusters
        if (match.startCluster >= 2L) {
            freeClusterChain(match.startCluster)
        }

        // Allocate new clusters
        val clustersNeeded = if (data.isEmpty()) 0 else (data.size + clusterSize - 1) / clusterSize
        val chain = if (clustersNeeded > 0) allocateClusters(clustersNeeded) else emptyList()
        val newStartCluster = if (chain.isNotEmpty()) chain.first() else 0L

        for (i in chain.indices) {
            val c = chain[i]
            val cOffset = dataStartOffset + (c - 2L) * clusterSize
            val startByte = i * clusterSize
            val endByte = (startByte + clusterSize).coerceAtMost(data.size)
            val chunk = data.copyOfRange(startByte, endByte)
            dev.writeBytes(cOffset, chunk)
        }

        // Update directory entry on dev
        val dirEntry = dev.readBytes(match.slotDevOffset, 32)
        val (dosDate, dosTime) = currentDosDateTime()
        // Attributes: retain or set archive
        dirEntry[11] = 0x20
        // Write Time & Date
        dirEntry[22] = (dosTime and 0xFF).toByte()
        dirEntry[23] = ((dosTime ushr 8) and 0xFF).toByte()
        dirEntry[24] = (dosDate and 0xFF).toByte()
        dirEntry[25] = ((dosDate ushr 8) and 0xFF).toByte()
        // Cluster Hi & Lo
        val hi = (newStartCluster ushr 16) and 0xFFFFL
        val lo = newStartCluster and 0xFFFFL
        if (fatType == FatType.FAT32) {
            dirEntry[20] = (hi and 0xFF).toByte()
            dirEntry[21] = ((hi ushr 8) and 0xFF).toByte()
        }
        dirEntry[26] = (lo and 0xFF).toByte()
        dirEntry[27] = ((lo ushr 8) and 0xFF).toByte()
        // File size
        val sizeL = data.size.toLong()
        dirEntry[28] = (sizeL and 0xFF).toByte()
        dirEntry[29] = ((sizeL ushr 8) and 0xFF).toByte()
        dirEntry[30] = ((sizeL ushr 16) and 0xFF).toByte()
        dirEntry[31] = ((sizeL ushr 24) and 0xFF).toByte()

        dev.writeBytes(match.slotDevOffset, dirEntry)
        dev.flush()
    }

    fun deleteEntry(relativePath: String) {
        val cleanPath = normalizePath(relativePath)
        val parentPath = cleanPath.substringBeforeLast('/', "")
        val entryName = cleanPath.substringAfterLast('/')

        val parentCluster = findParentDirectoryCluster(parentPath)
        val items = listDirectoryItems(parentCluster)
        val match = items.firstOrNull { it.name.equals(entryName, ignoreCase = true) }
            ?: throw NoSuchElementException("Entry not found to delete: $relativePath")

        if (match.isDirectory) {
            val subItems = listDirectoryItems(match.startCluster)
            if (subItems.isNotEmpty()) {
                throw IOException("Directory is not empty: $entryName")
            }
        }

        // Free clusters
        if (match.startCluster >= 2L) {
            freeClusterChain(match.startCluster)
        }

        // Mark all slots (LFN slots + main slot) as deleted (0xE5)
        var offset = match.lfnStartSlotDevOffset
        while (offset <= match.slotDevOffset) {
            dev.writeBytes(offset, byteArrayOf(0xE5.toByte()))
            offset += 32L
        }
        dev.flush()
    }

    // =========================================================================
    // Encoding & Helpers
    // =========================================================================

    private fun normalizePath(p: String): String =
        p.replace('\\', '/').trim().removePrefix("/").removeSuffix("/")

    private fun joinPath(parent: String, child: String): String =
        if (parent.isEmpty()) child else "$parent/$child"

    private fun generate83Name(fileName: String, existingNames: Set<String>): ByteArray {
        val clean = fileName.trim()
        val dot = clean.lastIndexOf('.')
        val base = (if (dot >= 0) clean.substring(0, dot) else clean)
            .filter { it.isLetterOrDigit() || it == '_' || it == '-' }
            .uppercase(Locale.ROOT)
        val ext = (if (dot >= 0) clean.substring(dot + 1) else "")
            .filter { it.isLetterOrDigit() || it == '_' || it == '-' }
            .uppercase(Locale.ROOT)

        var candidate = (base.take(8)).padEnd(8, ' ') + ext.take(3).padEnd(3, ' ')
        if (!existingNames.contains(candidate.trim())) {
            return candidate.toByteArray(StandardCharsets.US_ASCII)
        }

        var num = 1
        while (num < 100) {
            val numStr = "~$num"
            val prefix = base.take(8 - numStr.length)
            candidate = (prefix + numStr).padEnd(8, ' ') + ext.take(3).padEnd(3, ' ')
            if (!existingNames.contains(candidate.trim())) {
                return candidate.toByteArray(StandardCharsets.US_ASCII)
            }
            num++
        }
        return candidate.toByteArray(StandardCharsets.US_ASCII)
    }

    private fun needsLfn(fileName: String, shortName11: ByteArray): Boolean {
        val reconstructed = decodeFatShortName(shortName11, 0)
        return !fileName.equals(reconstructed, ignoreCase = false)
    }

    private fun lfnChecksum(shortName11: ByteArray): Int {
        var sum = 0
        for (i in 0 until 11) {
            sum = (((sum and 1) shl 7) or (sum ushr 1)) + (shortName11[i].toInt() and 0xFF)
            sum = sum and 0xFF
        }
        return sum
    }

    private fun createVfatEntries(fullName: String, shortName11: ByteArray): List<ByteArray> {
        val chk = lfnChecksum(shortName11).toByte()
        val charList = fullName.toCharArray()
        val totalChars = charList.size
        val numEntries = (totalChars + 12) / 13
        val entries = mutableListOf<ByteArray>()

        for (e in numEntries downTo 1) {
            val entry = ByteArray(32)
            val seq = if (e == numEntries) (e or 0x40) else e
            entry[0] = seq.toByte()
            entry[11] = 0x0F // VFAT attribute
            entry[12] = 0x00
            entry[13] = chk
            entry[26] = 0x00
            entry[27] = 0x00

            val startChar = (e - 1) * 13
            val charOffsets = intArrayOf(1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30)
            for (ci in 0 until 13) {
                val cIdx = startChar + ci
                val chVal = when {
                    cIdx < totalChars -> charList[cIdx].code
                    cIdx == totalChars -> 0x0000
                    else -> 0xFFFF
                }
                val bo = charOffsets[ci]
                entry[bo] = (chVal and 0xFF).toByte()
                entry[bo + 1] = ((chVal ushr 8) and 0xFF).toByte()
            }
            entries += entry
        }
        return entries
    }

    private fun currentDosDateTime(): Pair<Int, Int> {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = System.currentTimeMillis()
        val year = cal.get(Calendar.YEAR).coerceAtLeast(1980)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val min = cal.get(Calendar.MINUTE)
        val sec = cal.get(Calendar.SECOND)
        val dosDate = (((year - 1980) and 0x7F) shl 9) or ((month and 0x0F) shl 5) or (day and 0x1F)
        val dosTime = ((hour and 0x1F) shl 11) or ((min and 0x3F) shl 5) or ((sec / 2) and 0x1F)
        return Pair(dosDate, dosTime)
    }

    private fun createDirEntryBytes(
        shortName11: ByteArray,
        attr: Int,
        startCluster: Long,
        fileSize: Long
    ): ByteArray {
        val entry = ByteArray(32)
        System.arraycopy(shortName11, 0, entry, 0, 11)
        entry[11] = attr.toByte()

        val (dosDate, dosTime) = currentDosDateTime()
        // Create time & date
        entry[14] = (dosTime and 0xFF).toByte()
        entry[15] = ((dosTime ushr 8) and 0xFF).toByte()
        entry[16] = (dosDate and 0xFF).toByte()
        entry[17] = ((dosDate ushr 8) and 0xFF).toByte()
        // Last access date
        entry[18] = (dosDate and 0xFF).toByte()
        entry[19] = ((dosDate ushr 8) and 0xFF).toByte()
        // Cluster Hi (FAT32)
        val hi = (startCluster ushr 16) and 0xFFFFL
        entry[20] = (hi and 0xFF).toByte()
        entry[21] = ((hi ushr 8) and 0xFF).toByte()
        // Last write time & date
        entry[22] = (dosTime and 0xFF).toByte()
        entry[23] = ((dosTime ushr 8) and 0xFF).toByte()
        entry[24] = (dosDate and 0xFF).toByte()
        entry[25] = ((dosDate ushr 8) and 0xFF).toByte()
        // Cluster Lo
        val lo = startCluster and 0xFFFFL
        entry[26] = (lo and 0xFF).toByte()
        entry[27] = ((lo ushr 8) and 0xFF).toByte()
        // File Size
        entry[28] = (fileSize and 0xFF).toByte()
        entry[29] = ((fileSize ushr 8) and 0xFF).toByte()
        entry[30] = ((fileSize ushr 16) and 0xFF).toByte()
        entry[31] = ((fileSize ushr 24) and 0xFF).toByte()

        return entry
    }

    private fun decodeFatShortName(buf: ByteArray, offset: Int): String {
        val base = String(buf, offset, 8, StandardCharsets.US_ASCII).trimEnd()
        val ext = String(buf, offset + 8, 3, StandardCharsets.US_ASCII).trimEnd()
        return if (ext.isNotEmpty()) "$base.$ext" else base
    }

    private fun decodeVfatLfnEntry(buf: ByteArray, offset: Int): String {
        val chars = CharArray(13)
        val byteOffsets = intArrayOf(1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30)
        var len = 0
        for (bo in byteOffsets) {
            val code = u16le(buf, offset + bo)
            if (code == 0x0000 || code == 0xFFFF) break
            chars[len++] = code.toChar()
        }
        return String(chars, 0, len)
    }

    private fun decodeDosDateTime(dosDate: Int, dosTime: Int): Long {
        if (dosDate == 0) return 0L
        val year = ((dosDate ushr 9) and 0x7F) + 1980
        val month = ((dosDate ushr 5) and 0x0F) - 1
        val day = dosDate and 0x1F
        val hour = (dosTime ushr 11) and 0x1F
        val min = (dosTime ushr 5) and 0x3F
        val sec = (dosTime and 0x1F) * 2
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(year, month.coerceIn(0, 11), day.coerceIn(1, 31), hour.coerceIn(0, 23), min.coerceIn(0, 59), sec.coerceIn(0, 59))
        return cal.timeInMillis
    }

    private fun u8(buf: ByteArray, offset: Int): Int = buf[offset].toInt() and 0xFF

    private fun u16le(buf: ByteArray, offset: Int): Int =
        (u8(buf, offset)) or (u8(buf, offset + 1) shl 8)

    private fun u32le(buf: ByteArray, offset: Int): Long =
        ((u8(buf, offset).toLong()) or
            (u8(buf, offset + 1).toLong() shl 8) or
            (u8(buf, offset + 2).toLong() shl 16) or
            (u8(buf, offset + 3).toLong() shl 24)) and 0xFFFFFFFFL
}
