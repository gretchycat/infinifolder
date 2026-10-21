package com.infinifolder.archive

import org.apache.commons.compress.compressors.CompressorStreamFactory
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.lzma.LZMACompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Pure-Kotlin user-space reader for raw and compressed filesystem disc images:
 * - ISO 9660 + Joliet + Rock Ridge (.iso, .cdr)
 * - FAT12 / FAT16 / FAT32 + VFAT Long File Names (.fat, .vfat, .img, .ima, .flp)
 * - exFAT (.exfat, .img)
 * - ext2 / ext3 / ext4 with both direct/indirect blocks and ext4 extent trees (.ext2, .ext3, .ext4, .img)
 * - MBR and GPT partitioned disk images (.img, .raw)
 * - Transparent decompression for .gz, .bz2, .bz, .xz, .lzma, .zst wrapped disc images
 */
class DiscImageReader(private val tempDir: File) {

    data class DiscEntry(
        val path: String,
        val isDirectory: Boolean,
        val sizeBytes: Long?,
        val modifiedAtMillis: Long?,
        internal val extractor: ((BlockDevice, OutputStream) -> Unit)? = null
    )

    fun readEntries(
        archiveName: String,
        openStream: () -> InputStream,
        localFile: File?
    ): List<DiscEntry> {
        return withBlockDevice(archiveName, openStream, localFile) { dev ->
            parseDiscImage(dev)
        }
    }

    fun extractEntry(
        archiveName: String,
        entryPath: String,
        targetFile: File,
        openStream: () -> InputStream,
        localFile: File?
    ): File {
        val cleanTarget = normalizePath(entryPath)
        targetFile.parentFile?.mkdirs()
        withBlockDevice(archiveName, openStream, localFile) { dev ->
            val entries = parseDiscImage(dev)
            val match = entries.firstOrNull { normalizePath(it.path) == cleanTarget }
                ?: entries.firstOrNull { normalizePath(it.path).equals(cleanTarget, ignoreCase = true) }
                ?: throw NoSuchElementException("Entry not found in disc image: $entryPath")
            val extractor = match.extractor
                ?: throw IOException("Cannot extract directory entry: $entryPath")
            targetFile.outputStream().use { out ->
                extractor(dev, out)
            }
        }
        return targetFile
    }

    fun openEntryStream(
        archiveName: String,
        entryPath: String,
        openStream: () -> InputStream,
        localFile: File?
    ): InputStream {
        val cleanTarget = normalizePath(entryPath)
        return withBlockDevice(archiveName, openStream, localFile) { dev ->
            val entries = parseDiscImage(dev)
            val match = entries.firstOrNull { normalizePath(it.path) == cleanTarget }
                ?: entries.firstOrNull { normalizePath(it.path).equals(cleanTarget, ignoreCase = true) }
                ?: throw NoSuchElementException("Entry not found in disc image: $entryPath")
            val extractor = match.extractor
                ?: throw IOException("Cannot open stream for directory entry: $entryPath")
            val baos = ByteArrayOutputStream(match.sizeBytes?.toInt()?.coerceIn(0, 16 * 1024 * 1024) ?: 4096)
            extractor(dev, baos)
            baos.toByteArray().inputStream()
        }
    }

    fun isWritable(archiveName: String, localFile: File?): Boolean {
        if (localFile == null || !localFile.exists() || !localFile.canWrite()) return false
        val mountCheck = MountedPartitionDetector.checkMounted(localFile)
        if (mountCheck.isMounted) return false

        val lowerName = archiveName.lowercase(Locale.ROOT)
        val isCompressed = lowerName.endsWith(".gz") || lowerName.endsWith(".bz2") ||
            lowerName.endsWith(".bz") || lowerName.endsWith(".xz") ||
            lowerName.endsWith(".lzma") || lowerName.endsWith(".zst") ||
            isCompressedFileMagic(localFile)
        if (isCompressed) return false

        return try {
            RandomAccessFile(localFile, "r").use { raf ->
                val dev = VmDiskDecoder.open(raf, isReadOnly = false, baseFile = localFile)
                    ?: FileBlockDevice(raf, isReadOnly = false)
                if (dev.isReadOnly) return false
                if (isFatFs(dev, 0L)) return true
                val partitions = detectPartitions(dev)
                for (part in partitions) {
                    val slice = PartitionSliceBlockDevice(dev, part.offsetBytes, part.sizeBytes)
                    if (isFatFs(slice, 0L)) return true
                }
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun <T> withWritableFat(
        archiveName: String,
        localFile: File?,
        entryPath: String,
        block: (FatFileSystem, String) -> T
    ): T {
        require(localFile != null && localFile.exists()) { "Local file required for disc writing" }
        val mountCheck = MountedPartitionDetector.checkMounted(localFile)
        if (mountCheck.isMounted) {
            throw MountedPartitionException(localFile.name, mountCheck.reason)
        }
        return withBlockDevice(archiveName, { throw IOException("Local file required") }, localFile, writable = true) { dev ->
            if (isFatFs(dev, 0L)) {
                val fatFs = FatFileSystem(dev, 0L, "")
                return@withBlockDevice block(fatFs, entryPath)
            }
            val partitions = detectPartitions(dev)
            if (partitions.isNotEmpty()) {
                val cleanPath = normalizePath(entryPath)
                if (partitions.size == 1) {
                    val p = partitions.first()
                    val slice = PartitionSliceBlockDevice(dev, p.offsetBytes, p.sizeBytes)
                    if (isFatFs(slice, 0L)) {
                        val subPath = if (cleanPath.startsWith("${p.name}/")) cleanPath.removePrefix("${p.name}/") else cleanPath
                        val fatFs = FatFileSystem(slice, 0L, "")
                        return@withBlockDevice block(fatFs, subPath)
                    }
                } else {
                    for (p in partitions) {
                        if (cleanPath == p.name || cleanPath.startsWith("${p.name}/")) {
                            val slice = PartitionSliceBlockDevice(dev, p.offsetBytes, p.sizeBytes)
                            if (isFatFs(slice, 0L)) {
                                val subPath = cleanPath.removePrefix("${p.name}/").removePrefix(p.name)
                                val fatFs = FatFileSystem(slice, 0L, "")
                                return@withBlockDevice block(fatFs, subPath)
                            }
                        }
                    }
                }
            }
            throw IOException("No writable FAT filesystem found for path: $entryPath")
        }
    }

    fun createFile(archiveName: String, localFile: File?, entryPath: String, data: ByteArray = ByteArray(0)) {
        withWritableFat(archiveName, localFile, entryPath) { fatFs, relPath ->
            fatFs.createFile(relPath, data)
        }
    }

    fun createDirectory(archiveName: String, localFile: File?, entryPath: String) {
        withWritableFat(archiveName, localFile, entryPath) { fatFs, relPath ->
            fatFs.createDirectory(relPath)
        }
    }

    fun writeEntry(archiveName: String, localFile: File?, entryPath: String, data: ByteArray) {
        withWritableFat(archiveName, localFile, entryPath) { fatFs, relPath ->
            fatFs.writeEntry(relPath, data)
        }
    }

    fun deleteEntry(archiveName: String, localFile: File?, entryPath: String) {
        withWritableFat(archiveName, localFile, entryPath) { fatFs, relPath ->
            fatFs.deleteEntry(relPath)
        }
    }

    private fun <T> withBlockDevice(
        archiveName: String,
        openStream: () -> InputStream,
        localFile: File?,
        writable: Boolean = false,
        block: (BlockDevice) -> T
    ): T {
        val lowerName = archiveName.lowercase(Locale.ROOT)
        val isCompressedByName = lowerName.endsWith(".gz") ||
            lowerName.endsWith(".bz2") ||
            lowerName.endsWith(".bz") ||
            lowerName.endsWith(".xz") ||
            lowerName.endsWith(".lzma") ||
            lowerName.endsWith(".zst")

        if (localFile != null && localFile.exists() && !isCompressedByName && !isCompressedFileMagic(localFile)) {
            val mode = if (writable) "rw" else "r"
            return RandomAccessFile(localFile, mode).use { raf ->
                val dev = VmDiskDecoder.open(raf, isReadOnly = !writable, baseFile = localFile)
                    ?: FileBlockDevice(raf, isReadOnly = !writable)
                dev.use { d ->
                    val result = block(d)
                    if (writable) d.flush()
                    result
                }
            }
        }

        if (writable) {
            throw IOException("Cannot write to compressed or stream-only disc image")
        }

        tempDir.mkdirs()
        if (localFile != null && localFile.exists()) {
            val cacheKey = sha256("${localFile.absolutePath}:${localFile.length()}:${localFile.lastModified()}")
            val cachedRaw = File(tempDir, "disc_cache_$cacheKey.raw")
            if (!cachedRaw.exists() || cachedRaw.length() == 0L) {
                val tmpOut = File(tempDir, "disc_cache_${cacheKey}_${System.nanoTime()}.tmp")
                try {
                    localFile.inputStream().use { rawIn ->
                        openDecompressedStream(rawIn, lowerName).use { decIn ->
                            tmpOut.outputStream().use { out ->
                                decIn.copyTo(out)
                            }
                        }
                    }
                    if (!tmpOut.renameTo(cachedRaw)) {
                        tmpOut.copyTo(cachedRaw, overwrite = true)
                        tmpOut.delete()
                    }
                } finally {
                    if (tmpOut.exists()) tmpOut.delete()
                }
            }
            return RandomAccessFile(cachedRaw, "r").use { raf ->
                val dev = VmDiskDecoder.open(raf, isReadOnly = true, baseFile = cachedRaw)
                    ?: FileBlockDevice(raf, isReadOnly = true)
                dev.use(block)
            }
        }

        val tempFile = File.createTempFile("inf_disc_", ".raw", tempDir)
        try {
            openStream().use { rawIn ->
                openDecompressedStream(rawIn, lowerName).use { decIn ->
                    tempFile.outputStream().use { out ->
                        val buffer = ByteArray(65536)
                        var bytesCopied = 0L
                        var read = decIn.read(buffer)
                        while (read >= 0) {
                            bytesCopied += read
                            if (bytesCopied > 512L * 1024L * 1024L) {
                                throw IOException("Disk image '$archiveName' exceeds 512 MB streaming limit. Download the file locally to inspect its contents.")
                            }
                            out.write(buffer, 0, read)
                            read = decIn.read(buffer)
                        }
                    }
                }
            }
            return RandomAccessFile(tempFile, "r").use { raf ->
                val dev = VmDiskDecoder.open(raf, isReadOnly = true, baseFile = tempFile)
                    ?: FileBlockDevice(raf, isReadOnly = true)
                dev.use(block)
            }
        } finally {
            tempFile.delete()
        }
    }

    private fun isCompressedFileMagic(file: File): Boolean {
        if (file.length() < 4) return false
        val header = ByteArray(6)
        RandomAccessFile(file, "r").use { raf ->
            raf.readFully(header)
        }
        // Gzip: 1F 8B
        if (u8(header, 0) == 0x1F && u8(header, 1) == 0x8B) return true
        // BZip2: 'B' 'Z' 'h'
        if (header[0] == 'B'.code.toByte() && header[1] == 'Z'.code.toByte() && header[2] == 'h'.code.toByte()) return true
        // XZ: FD '7' 'z' 'X' 'Z' 00
        if (u8(header, 0) == 0xFD && header[1] == '7'.code.toByte() && header[2] == 'z'.code.toByte()) return true
        // Zstd: 28 B5 2F FD
        if (u8(header, 0) == 0x28 && u8(header, 1) == 0xB5 && u8(header, 2) == 0x2F && u8(header, 3) == 0xFD) return true
        return false
    }

    private fun openDecompressedStream(raw: InputStream, lowerName: String): InputStream {
        val bis = BufferedInputStream(raw, 65536)
        bis.mark(16)
        val header = ByteArray(6)
        val read = bis.read(header)
        bis.reset()

        if (read >= 2 && u8(header, 0) == 0x1F && u8(header, 1) == 0x8B) {
            return GzipCompressorInputStream(bis, true)
        }
        if (read >= 3 && header[0] == 'B'.code.toByte() && header[1] == 'Z'.code.toByte() && header[2] == 'h'.code.toByte()) {
            return BZip2CompressorInputStream(bis, true)
        }
        if (read >= 6 && u8(header, 0) == 0xFD && header[1] == '7'.code.toByte() && header[2] == 'z'.code.toByte()) {
            return XZCompressorInputStream(bis, true)
        }
        if (read >= 4 && u8(header, 0) == 0x28 && u8(header, 1) == 0xB5 && u8(header, 2) == 0x2F && u8(header, 3) == 0xFD) {
            return CompressorStreamFactory().createCompressorInputStream(bis)
        }

        return when {
            lowerName.endsWith(".gz") -> GzipCompressorInputStream(bis, true)
            lowerName.endsWith(".bz2") || lowerName.endsWith(".bz") -> BZip2CompressorInputStream(bis, true)
            lowerName.endsWith(".xz") -> XZCompressorInputStream(bis, true)
            lowerName.endsWith(".lzma") -> LZMACompressorInputStream(bis)
            lowerName.endsWith(".zst") -> CompressorStreamFactory().createCompressorInputStream(bis)
            else -> bis
        }
    }

    private fun parseDiscImage(dev: BlockDevice): List<DiscEntry> {
        val length = dev.size
        if (length < 512) throw IOException("Disc image is too small ($length bytes)")

        // 1. Check ISO 9660 / Joliet first (supports hybrid ISOs that also have an MBR at sector 0)
        if (isIso9660(dev, 0L)) {
            return parseIso9660(dev, 0L, "")
        }

        // 2. Check direct filesystems at offset 0 (ext2/3/4, exFAT, FAT12/16/32)
        if (isExtFs(dev, 0L)) {
            return parseExtFs(dev, 0L, "")
        }
        if (isExFat(dev, 0L)) {
            return parseExFat(dev, 0L, "")
        }
        if (isFatFs(dev, 0L)) {
            return parseFatFs(dev, 0L, "")
        }

        // 3. Check GPT or MBR partition tables
        val partitions = detectPartitions(dev)
        if (partitions.isNotEmpty()) {
            val parsedPartitions = mutableListOf<Pair<PartitionSlice, List<DiscEntry>>>()
            for (part in partitions) {
                val entries = try {
                    val slice = PartitionSliceBlockDevice(dev, part.offsetBytes, part.sizeBytes)
                    parseFilesystemAtOffset(slice, 0L, "")
                } catch (_: Exception) {
                    null
                }
                if (entries != null) {
                    parsedPartitions += part to entries
                }
            }
            if (parsedPartitions.size == 1) {
                val (part, entries) = parsedPartitions.first()
                return entries.map { entry ->
                    val origExtractor = entry.extractor
                    if (origExtractor != null) {
                        entry.copy(extractor = { parentDev, out ->
                            val partSlice = PartitionSliceBlockDevice(parentDev, part.offsetBytes, part.sizeBytes)
                            origExtractor(partSlice, out)
                        })
                    } else entry
                }
            } else if (parsedPartitions.size > 1) {
                val combined = mutableListOf<DiscEntry>()
                for ((part, entries) in parsedPartitions) {
                    val prefix = part.name
                    combined += DiscEntry(
                        path = prefix,
                        isDirectory = true,
                        sizeBytes = null,
                        modifiedAtMillis = null
                    )
                    for (entry in entries) {
                        val origExtractor = entry.extractor
                        val wrappedExtractor: ((BlockDevice, OutputStream) -> Unit)? = if (origExtractor != null) {
                            { parentDev, out ->
                                val partSlice = PartitionSliceBlockDevice(parentDev, part.offsetBytes, part.sizeBytes)
                                origExtractor(partSlice, out)
                            }
                        } else null
                        combined += entry.copy(
                            path = "$prefix/${entry.path}",
                            extractor = wrappedExtractor
                        )
                    }
                }
                return combined
            }
        }

        throw IOException("Unsupported or unrecognized disc image format")
    }

    private fun parseFilesystemAtOffset(dev: BlockDevice, offset: Long, prefix: String): List<DiscEntry> {
        return when {
            isIso9660(dev, offset) -> parseIso9660(dev, offset, prefix)
            isExtFs(dev, offset) -> parseExtFs(dev, offset, prefix)
            isExFat(dev, offset) -> parseExFat(dev, offset, prefix)
            isFatFs(dev, offset) -> parseFatFs(dev, offset, prefix)
            else -> throw IOException("Unrecognized filesystem at offset $offset")
        }
    }

    // =========================================================================
    // Partition Table Detection (GPT & MBR)
    // =========================================================================

    private data class PartitionSlice(
        val name: String,
        val offsetBytes: Long,
        val sizeBytes: Long
    )

    private fun detectPartitions(dev: BlockDevice): List<PartitionSlice> {
        val fileLen = dev.size
        if (fileLen < 1024) return emptyList()

        // Check GPT header at LBA 1 (offset 512)
        val gptHeader = readBytesAt(dev, 512L, 92)
        if (gptHeader.size == 92 && String(gptHeader, 0, 8, StandardCharsets.US_ASCII) == "EFI PART") {
            val partEntryLba = i64le(gptHeader, 72)
            val numPartEntries = u32le(gptHeader, 80).toInt().coerceIn(0, 128)
            val partEntrySize = u32le(gptHeader, 84).toInt().coerceIn(128, 512)
            val slices = mutableListOf<PartitionSlice>()
            for (i in 0 until numPartEntries) {
                val entryOffset = partEntryLba * 512L + i.toLong() * partEntrySize
                if (entryOffset + partEntrySize > fileLen) break
                val entryBytes = readBytesAt(dev, entryOffset, partEntrySize)
                if (entryBytes.take(16).all { it == 0.toByte() }) continue
                val firstLba = i64le(entryBytes, 32)
                val lastLba = i64le(entryBytes, 40)
                if (firstLba <= 0L || lastLba < firstLba) continue
                val startBytes = firstLba * 512L
                val sizeBytes = (lastLba - firstLba + 1L) * 512L
                if (startBytes >= fileLen) continue
                val rawLabel = String(entryBytes, 56, 72, StandardCharsets.UTF_16LE).substringBefore('\u0000').trim()
                val label = if (rawLabel.isNotEmpty()) sanitizeSegment(rawLabel) else "Partition_${slices.size + 1}"
                slices += PartitionSlice(label, startBytes, sizeBytes)
            }
            if (slices.isNotEmpty()) return slices
        }

        // Check MBR at sector 0
        val mbr = readBytesAt(dev, 0L, 512)
        if (mbr.size == 512 && u8(mbr, 510) == 0x55 && u8(mbr, 511) == 0xAA) {
            val slices = mutableListOf<PartitionSlice>()
            for (i in 0 until 4) {
                val pos = 446 + i * 16
                val status = u8(mbr, pos)
                if (status != 0x00 && status != 0x80) continue
                val type = u8(mbr, pos + 4)
                if (type == 0x00 || type == 0xEE) continue
                val startLba = u32le(mbr, pos + 8)
                val numSectors = u32le(mbr, pos + 12)
                if (startLba <= 0L || numSectors <= 0L) continue
                val startBytes = startLba * 512L
                val sizeBytes = numSectors * 512L
                if (startBytes + 512L > fileLen) continue
                slices += PartitionSlice("Partition_${slices.size + 1}", startBytes, sizeBytes)
            }
            if (slices.isNotEmpty()) return slices
        }

        return emptyList()
    }

    // =========================================================================
    // 1. ISO 9660 + Joliet + Rock Ridge
    // =========================================================================

    private fun isIso9660(dev: BlockDevice, baseOffset: Long): Boolean {
        val fileLen = dev.size
        for (sector in 16..20) {
            val vdOffset = baseOffset + sector * 2048L
            if (vdOffset + 7 > fileLen) break
            val magic = readBytesAt(dev, vdOffset + 1, 5)
            if (magic.contentEquals("CD001".toByteArray(StandardCharsets.US_ASCII))) {
                return true
            }
        }
        return false
    }

    private data class IsoDirRecord(
        val extentLba: Long,
        val dataLen: Long,
        val modifiedAtMillis: Long?,
        val isDirectory: Boolean,
        val name: String
    )

    private fun parseIso9660(dev: BlockDevice, baseOffset: Long, prefix: String): List<DiscEntry> {
        val fileLen = dev.size
        var pvdRoot: ByteArray? = null
        var jolietRoot: ByteArray? = null

        for (sector in 16..64) {
            val vdOffset = baseOffset + sector * 2048L
            if (vdOffset + 2048 > fileLen) break
            val vd = readBytesAt(dev, vdOffset, 2048)
            val type = u8(vd, 0)
            val id = String(vd, 1, 5, StandardCharsets.US_ASCII)
            if (id != "CD001") continue
            when (type) {
                1 -> if (pvdRoot == null) {
                    pvdRoot = vd.copyOfRange(156, 156 + 34)
                }
                2 -> {
                    // Check Joliet UCS-2 escape sequences at bytes 88..119 (%/@, %/C, %/E)
                    val esc0 = u8(vd, 88)
                    val esc1 = u8(vd, 89)
                    val esc2 = u8(vd, 90)
                    if (esc0 == 0x25 && esc1 == 0x2F && (esc2 == 0x40 || esc2 == 0x43 || esc2 == 0x45)) {
                        jolietRoot = vd.copyOfRange(156, 156 + 34)
                    }
                }
                255 -> break
            }
        }

        val rootRecordBytes = jolietRoot ?: pvdRoot
            ?: throw IOException("ISO 9660 Primary Volume Descriptor not found")
        val isJoliet = jolietRoot != null
        val rootRecord = parseIsoDirectoryRecord(rootRecordBytes, 0, isJoliet)
            ?: throw IOException("Invalid ISO 9660 root directory record")

        val entries = mutableListOf<DiscEntry>()
        val visitedExtents = mutableSetOf<Long>()

        fun walkDirectory(dirLba: Long, dirLen: Long, currentPath: String) {
            if (dirLba <= 0L || dirLen <= 0L || !visitedExtents.add(dirLba)) return
            val dirStart = baseOffset + dirLba * 2048L
            if (dirStart >= fileLen) return
            val safeLen = dirLen.coerceAtMost(fileLen - dirStart).coerceAtMost(16 * 1024 * 1024L).toInt()
            val dirBytes = readBytesAt(dev, dirStart, safeLen)

            var pos = 0
            while (pos < dirBytes.size) {
                val recLen = u8(dirBytes, pos)
                if (recLen == 0) {
                    // Advance to next 2048-byte sector boundary
                    val nextSector = ((pos / 2048) + 1) * 2048
                    if (nextSector <= pos) break
                    pos = nextSector
                    continue
                }
                if (pos + recLen > dirBytes.size) break
                val rec = parseIsoDirectoryRecord(dirBytes, pos, isJoliet)
                pos += recLen
                if (rec == null || rec.name.isEmpty() || rec.name == "." || rec.name == "..") continue

                val childPath = joinPath(currentPath, rec.name)
                if (rec.isDirectory) {
                    entries += DiscEntry(
                        path = childPath,
                        isDirectory = true,
                        sizeBytes = null,
                        modifiedAtMillis = rec.modifiedAtMillis
                    )
                    walkDirectory(rec.extentLba, rec.dataLen, childPath)
                } else {
                    val fileOffset = baseOffset + rec.extentLba * 2048L
                    val fileSize = rec.dataLen
                    entries += DiscEntry(
                        path = childPath,
                        isDirectory = false,
                        sizeBytes = fileSize,
                        modifiedAtMillis = rec.modifiedAtMillis,
                        extractor = { d, out ->
                            copyRangeToStream(d, fileOffset, fileSize, out)
                        }
                    )
                }
            }
        }

        walkDirectory(rootRecord.extentLba, rootRecord.dataLen, prefix)
        return entries
    }

    private fun parseIsoDirectoryRecord(buf: ByteArray, offset: Int, isJoliet: Boolean): IsoDirRecord? {
        val recLen = u8(buf, offset)
        if (recLen < 34 || offset + recLen > buf.size) return null
        val extentLba = u32le(buf, offset + 2)
        val dataLen = u32le(buf, offset + 10)

        val year = u8(buf, offset + 18) + 1900
        val month = u8(buf, offset + 19).coerceIn(1, 12)
        val day = u8(buf, offset + 20).coerceIn(1, 31)
        val hour = u8(buf, offset + 21).coerceIn(0, 23)
        val minute = u8(buf, offset + 22).coerceIn(0, 59)
        val second = u8(buf, offset + 23).coerceIn(0, 59)
        val modifiedAtMillis = toUtcMillis(year, month, day, hour, minute, second)

        val flags = u8(buf, offset + 25)
        val isDir = (flags and 0x02) != 0
        val nameLen = u8(buf, offset + 32)
        if (offset + 33 + nameLen > offset + recLen) return null

        if (nameLen == 1) {
            val b0 = u8(buf, offset + 33)
            if (b0 == 0) return IsoDirRecord(extentLba, dataLen, modifiedAtMillis, isDir, ".")
            if (b0 == 1) return IsoDirRecord(extentLba, dataLen, modifiedAtMillis, isDir, "..")
        }

        // Check Rock Ridge NM (Alternate Name) in System Use area
        val suStart = 33 + nameLen + (if (nameLen % 2 == 0) 1 else 0)
        val rockRidgeName = if (suStart + 5 <= recLen) {
            parseRockRidgeName(buf, offset + suStart, offset + recLen)
        } else null

        val rawDecoded = when {
            !rockRidgeName.isNullOrEmpty() -> rockRidgeName
            isJoliet -> {
                val str = String(buf, offset + 33, nameLen, StandardCharsets.UTF_16BE)
                cleanIsoFileName(str)
            }
            else -> {
                val str = String(buf, offset + 33, nameLen, StandardCharsets.ISO_8859_1)
                cleanIsoFileName(str)
            }
        }
        return IsoDirRecord(extentLba, dataLen, modifiedAtMillis, isDir, rawDecoded)
    }

    private fun parseRockRidgeName(buf: ByteArray, start: Int, end: Int): String? {
        val sb = StringBuilder()
        var pos = start
        while (pos + 4 <= end) {
            val sig0 = u8(buf, pos)
            val sig1 = u8(buf, pos + 1)
            val len = u8(buf, pos + 2)
            if (len < 4 || pos + len > end) break
            if (sig0 == 'N'.code && sig1 == 'M'.code && len > 5) {
                val flags = u8(buf, pos + 4)
                if ((flags and 0x06) == 0) {
                    sb.append(String(buf, pos + 5, len - 5, StandardCharsets.UTF_8))
                }
            }
            pos += len
        }
        return sb.toString().takeIf { it.isNotEmpty() }
    }

    private fun cleanIsoFileName(raw: String): String {
        var s = raw.substringBefore('\u0000')
        val semiIdx = s.lastIndexOf(';')
        if (semiIdx > 0 && s.substring(semiIdx + 1).all { it.isDigit() }) {
            s = s.substring(0, semiIdx)
        }
        if (s.endsWith('.')) {
            s = s.dropLast(1)
        }
        return s.trim()
    }

    // =========================================================================
    // 2. FAT12 / FAT16 / FAT32 + VFAT Long File Names
    // =========================================================================

    private fun isFatFs(dev: BlockDevice, baseOffset: Long): Boolean {
        if (dev.size < baseOffset + 512L) return false
        val bpb = readBytesAt(dev, baseOffset, 512)
        if (bpb.size < 512) return false
        if (String(bpb, 3, 8, StandardCharsets.US_ASCII) == "EXFAT   ") return false

        val bytesPerSector = u16le(bpb, 11)
        if (bytesPerSector !in setOf(512, 1024, 2048, 4096)) return false
        val sectorsPerCluster = u8(bpb, 13)
        if (sectorsPerCluster == 0 || (sectorsPerCluster and (sectorsPerCluster - 1)) != 0 || sectorsPerCluster > 128) {
            return false
        }
        val reservedSectors = u16le(bpb, 14)
        if (reservedSectors <= 0) return false
        val numFats = u8(bpb, 16)
        if (numFats !in 1..4) return false

        val totalSectors16 = u16le(bpb, 19)
        val totalSectors32 = u32le(bpb, 32)
        val totalSectors = if (totalSectors16 != 0) totalSectors16.toLong() else totalSectors32
        if (totalSectors <= reservedSectors) return false

        val sectorsPerFat16 = u16le(bpb, 22)
        val sectorsPerFat32 = u32le(bpb, 36)
        val sectorsPerFat = if (sectorsPerFat16 != 0) sectorsPerFat16.toLong() else sectorsPerFat32
        if (sectorsPerFat <= 0L) return false

        val fsType16 = String(bpb, 54, 8, StandardCharsets.US_ASCII)
        val fsType32 = String(bpb, 82, 8, StandardCharsets.US_ASCII)
        if (fsType16.startsWith("FAT") || fsType32.startsWith("FAT")) return true

        val jump0 = u8(bpb, 0)
        val hasBootSig = u8(bpb, 510) == 0x55 && u8(bpb, 511) == 0xAA
        return (jump0 == 0xEB || jump0 == 0xE9) && hasBootSig
    }

    private fun parseFatFs(dev: BlockDevice, baseOffset: Long, prefix: String): List<DiscEntry> {
        return FatFileSystem(dev, baseOffset, prefix).readEntries()
    }

    // =========================================================================
    // 3. exFAT
    // =========================================================================

    private fun isExFat(dev: BlockDevice, baseOffset: Long): Boolean {
        if (dev.size < baseOffset + 512L) return false
        val header = readBytesAt(dev, baseOffset, 12)
        return header.size >= 11 && String(header, 3, 8, StandardCharsets.US_ASCII) == "EXFAT   "
    }

    private fun parseExFat(dev: BlockDevice, baseOffset: Long, prefix: String): List<DiscEntry> {
        val boot = readBytesAt(dev, baseOffset, 512)
        val fatOffsetSectors = u32le(boot, 80)
        val clusterHeapOffsetSectors = u32le(boot, 88)
        val rootCluster = u32le(boot, 96)
        val bytesPerSectorShift = u8(boot, 108).coerceIn(9, 12)
        val sectorsPerClusterShift = u8(boot, 109).coerceIn(0, 25 - bytesPerSectorShift)

        val bytesPerSector = 1L shl bytesPerSectorShift
        val clusterSize = (1L shl (bytesPerSectorShift + sectorsPerClusterShift)).toInt()
        val fatStartOffset = baseOffset + fatOffsetSectors * bytesPerSector
        val clusterHeapOffset = baseOffset + clusterHeapOffsetSectors * bytesPerSector

        fun getNextExFatCluster(cluster: Long): Long? {
            if (cluster < 2L) return null
            val entOffset = fatStartOffset + cluster * 4L
            val quad = readBytesAt(dev, entOffset, 4)
            if (quad.size < 4) return null
            val next = u32le(quad, 0)
            return if (next in 2L..0xFFFFFFF6L) next else null
        }

        fun getExFatClusterList(startCluster: Long, byteLength: Long, noFatChain: Boolean): List<Long> {
            if (startCluster < 2L || byteLength <= 0L) return emptyList()
            val numClusters = ((byteLength + clusterSize - 1L) / clusterSize).coerceAtMost(262144L).toInt()
            if (noFatChain) {
                return (0 until numClusters).map { startCluster + it }
            }
            val list = mutableListOf<Long>()
            val visited = HashSet<Long>()
            var curr: Long? = startCluster
            while (curr != null && curr >= 2L && visited.add(curr) && list.size < numClusters) {
                list += curr
                curr = getNextExFatCluster(curr)
            }
            return list
        }

        fun readExFatData(startCluster: Long, byteLength: Long, noFatChain: Boolean): ByteArray {
            val safeLen = byteLength.coerceIn(0L, 16 * 1024 * 1024L)
            val clusters = getExFatClusterList(startCluster, safeLen, noFatChain)
            val out = ByteArrayOutputStream(safeLen.toInt())
            var remaining = safeLen
            for (c in clusters) {
                if (remaining <= 0L) break
                val cOffset = clusterHeapOffset + (c - 2L) * clusterSize
                val toRead = remaining.coerceAtMost(clusterSize.toLong()).toInt()
                out.write(readBytesAt(dev, cOffset, toRead))
                remaining -= toRead
            }
            return out.toByteArray()
        }

        val entries = mutableListOf<DiscEntry>()
        val visitedDirs = HashSet<Long>()

        fun walkExFatDir(dirCluster: Long, dirByteLen: Long, noFatChain: Boolean, currentPath: String) {
            if (dirCluster < 2L || !visitedDirs.add(dirCluster)) return
            val dirBytes = readExFatData(dirCluster, dirByteLen, noFatChain)
            var pos = 0
            while (pos + 32 <= dirBytes.size) {
                val entryType = u8(dirBytes, pos)
                if (entryType == 0x00) break
                if (entryType == 0x85) {
                    val secondaryCount = u8(dirBytes, pos + 1)
                    if (secondaryCount >= 2 && pos + (secondaryCount + 1) * 32 <= dirBytes.size) {
                        val fileAttrs = u16le(dirBytes, pos + 4)
                        val isDir = (fileAttrs and 0x10) != 0
                        val modTimestamp = u32le(dirBytes, pos + 12).toInt()
                        val modDate = (modTimestamp ushr 16) and 0xFFFF
                        val modTime = modTimestamp and 0xFFFF
                        val modifiedAtMillis = decodeDosDateTime(modDate, modTime)

                        val streamPos = pos + 32
                        val streamType = u8(dirBytes, streamPos)
                        if (streamType == 0xC0) {
                            val genFlags = u8(dirBytes, streamPos + 1)
                            val childNoFatChain = (genFlags and 0x02) != 0
                            val nameLenChars = u8(dirBytes, streamPos + 3)
                            val validDataLen = i64le(dirBytes, streamPos + 8)
                            val firstCluster = u32le(dirBytes, streamPos + 20)
                            val dataLen = i64le(dirBytes, streamPos + 24)
                            val actualSize = if (validDataLen in 0..dataLen) validDataLen else dataLen

                            val sb = StringBuilder(nameLenChars)
                            for (s in 2..secondaryCount) {
                                val fnPos = pos + s * 32
                                if (u8(dirBytes, fnPos) != 0xC1) break
                                val charsInEntry = (nameLenChars - sb.length).coerceIn(0, 15)
                                if (charsInEntry > 0) {
                                    sb.append(String(dirBytes, fnPos + 2, charsInEntry * 2, StandardCharsets.UTF_16LE))
                                }
                            }
                            val name = sb.toString().substringBefore('\u0000').trim()
                            if (name.isNotEmpty() && name != "." && name != "..") {
                                val childPath = joinPath(currentPath, name)
                                if (isDir) {
                                    entries += DiscEntry(
                                        path = childPath,
                                        isDirectory = true,
                                        sizeBytes = null,
                                        modifiedAtMillis = modifiedAtMillis
                                    )
                                    val subDirLen = if (actualSize > 0L) actualSize else (clusterSize.toLong() * 64L)
                                    walkExFatDir(firstCluster, subDirLen, childNoFatChain, childPath)
                                } else {
                                    entries += DiscEntry(
                                        path = childPath,
                                        isDirectory = false,
                                        sizeBytes = actualSize,
                                        modifiedAtMillis = modifiedAtMillis,
                                        extractor = { d, out ->
                                            var remaining = actualSize
                                            val clusters = getExFatClusterList(firstCluster, actualSize, childNoFatChain)
                                            for (c in clusters) {
                                                if (remaining <= 0L) break
                                                val cOffset = clusterHeapOffset + (c - 2L) * clusterSize
                                                val toRead = remaining.coerceAtMost(clusterSize.toLong()).toInt()
                                                copyRangeToStream(d, cOffset, toRead.toLong(), out)
                                                remaining -= toRead
                                            }
                                        }
                                    )
                                }
                            }
                        }
                        pos += (secondaryCount + 1) * 32
                        continue
                    }
                }
                pos += 32
            }
        }

        walkExFatDir(rootCluster, clusterSize.toLong() * 64L, false, prefix)
        return entries
    }

    // =========================================================================
    // 4. ext2 / ext3 / ext4 (Direct/Indirect Block Pointers + Extent Trees)
    // =========================================================================

    private fun isExtFs(dev: BlockDevice, baseOffset: Long): Boolean {
        if (dev.size < baseOffset + 2048L) return false
        val magicBytes = readBytesAt(dev, baseOffset + 1024L + 56L, 2)
        if (magicBytes.size < 2) return false
        return u16le(magicBytes, 0) == 0xEF53
    }

    private data class ExtRun(
        val logicalBlock: Long,
        val physBlock: Long, // 0 means sparse hole (zeros)
        val blockCount: Long
    )

    private fun parseExtFs(dev: BlockDevice, baseOffset: Long, prefix: String): List<DiscEntry> {
        val sb = readBytesAt(dev, baseOffset + 1024L, 1024)
        val inodesCount = u32le(sb, 0)
        val logBlockSize = u32le(sb, 24).toInt()
        if (logBlockSize !in 0..6) throw IOException("Invalid ext* block size shift: $logBlockSize")
        val blockSize = 1024 shl logBlockSize
        val inodesPerGroup = u32le(sb, 40)
        val revLevel = u32le(sb, 76).toInt()
        val inodeSize = if (revLevel >= 1) u16le(sb, 88).coerceAtLeast(128) else 128
        val featureIncompat = u32le(sb, 96).toInt()
        val is64Bit = (featureIncompat and 0x80) != 0
        val rawDescSize = u16le(sb, 254)
        val descSize = if (is64Bit && rawDescSize >= 64) rawDescSize else 32

        val gdtOffset = baseOffset + if (blockSize == 1024) 2048L else blockSize.toLong()

        fun getInodeOffset(inodeNum: Long): Long {
            val idx = inodeNum - 1L
            val group = idx / inodesPerGroup
            val indexInGroup = idx % inodesPerGroup
            val gdBytes = readBytesAt(dev, gdtOffset + group * descSize, descSize)
            val tableLo = u32le(gdBytes, 8)
            val tableHi = if (descSize >= 64) u32le(gdBytes, 40) else 0L
            val inodeTableBlock = (tableHi shl 32) or tableLo
            return baseOffset + inodeTableBlock * blockSize + indexInGroup * inodeSize
        }

        fun parseExtentHeader(nodeBytes: ByteArray, offset: Int, maxLen: Int, outRuns: MutableList<ExtRun>, depthGuard: Int) {
            val endLimit = (offset + maxLen).coerceAtMost(nodeBytes.size)
            if (depthGuard > 8 || offset + 12 > endLimit) return
            val magic = u16le(nodeBytes, offset)
            if (magic != 0xF30A) return
            val entriesCount = u16le(nodeBytes, offset + 2)
            val depth = u16le(nodeBytes, offset + 6)

            if (depth == 0) {
                for (i in 0 until entriesCount) {
                    val pos = offset + 12 + i * 12
                    if (pos + 12 > endLimit) break
                    val eeBlock = u32le(nodeBytes, pos)
                    val rawLen = u16le(nodeBytes, pos + 4)
                    val isUninitialized = rawLen > 32768
                    val eeLen = (if (isUninitialized) rawLen - 32768 else rawLen).toLong()
                    val startHi = u16le(nodeBytes, pos + 6).toLong()
                    val startLo = u32le(nodeBytes, pos + 8)
                    val physBlock = if (isUninitialized) 0L else ((startHi shl 32) or startLo)
                    if (eeLen > 0L) {
                        outRuns += ExtRun(eeBlock, physBlock, eeLen)
                    }
                }
            } else {
                for (i in 0 until entriesCount) {
                    val pos = offset + 12 + i * 12
                    if (pos + 12 > endLimit) break
                    val leafLo = u32le(nodeBytes, pos + 4)
                    val leafHi = u16le(nodeBytes, pos + 8).toLong()
                    val leafBlock = (leafHi shl 32) or leafLo
                    val childBytes = readBytesAt(dev, baseOffset + leafBlock * blockSize, blockSize)
                    parseExtentHeader(childBytes, 0, childBytes.size, outRuns, depthGuard + 1)
                }
            }
        }

        fun collectIndirectBlocks(indBlock: Long, level: Int, maxBlocks: Int, outBlocks: MutableList<Long>) {
            if (level <= 0 || outBlocks.size >= maxBlocks) return
            val ptrsPerBlock = blockSize / 4
            if (indBlock == 0L) {
                var count = 1
                for (i in 1 until level) count *= ptrsPerBlock
                val toAdd = count.coerceAtMost(maxBlocks - outBlocks.size)
                repeat(toAdd) { outBlocks += 0L }
                return
            }
            val blockBytes = readBytesAt(dev, baseOffset + indBlock * blockSize, blockSize)
            for (i in 0 until ptrsPerBlock) {
                if (outBlocks.size >= maxBlocks || i * 4 + 4 > blockBytes.size) break
                val ptr = u32le(blockBytes, i * 4)
                if (level == 1) {
                    outBlocks += ptr
                } else {
                    collectIndirectBlocks(ptr, level - 1, maxBlocks, outBlocks)
                }
            }
        }

        fun getInodeRuns(inodeBytes: ByteArray, fileSize: Long): List<ExtRun> {
            val flags = u32le(inodeBytes, 32).toInt()
            val usesExtents = (flags and 0x00080000) != 0
            if (usesExtents) {
                val runs = mutableListOf<ExtRun>()
                parseExtentHeader(inodeBytes, 40, 60, runs, 0)
                return runs.sortedBy { it.logicalBlock }
            }

            val totalBlocksNeeded = ((fileSize + blockSize - 1L) / blockSize).coerceIn(0L, 262144L).toInt()
            if (totalBlocksNeeded == 0) return emptyList()
            val blocks = ArrayList<Long>(totalBlocksNeeded.coerceAtMost(4096))
            for (i in 0 until 12) {
                if (blocks.size >= totalBlocksNeeded) break
                blocks += u32le(inodeBytes, 40 + i * 4)
            }
            if (blocks.size < totalBlocksNeeded) {
                val ind1 = u32le(inodeBytes, 40 + 12 * 4)
                collectIndirectBlocks(ind1, 1, totalBlocksNeeded, blocks)
            }
            if (blocks.size < totalBlocksNeeded) {
                val ind2 = u32le(inodeBytes, 40 + 13 * 4)
                collectIndirectBlocks(ind2, 2, totalBlocksNeeded, blocks)
            }
            if (blocks.size < totalBlocksNeeded) {
                val ind3 = u32le(inodeBytes, 40 + 14 * 4)
                collectIndirectBlocks(ind3, 3, totalBlocksNeeded, blocks)
            }

            val runs = mutableListOf<ExtRun>()
            var idx = 0L
            for (phys in blocks) {
                val last = runs.lastOrNull()
                if (last != null && last.logicalBlock + last.blockCount == idx &&
                    ((last.physBlock == 0L && phys == 0L) || (last.physBlock != 0L && last.physBlock + last.blockCount == phys))
                ) {
                    runs[runs.lastIndex] = last.copy(blockCount = last.blockCount + 1L)
                } else {
                    runs += ExtRun(idx, phys, 1L)
                }
                idx++
            }
            return runs
        }

        fun streamInodeData(r: BlockDevice, inodeBytes: ByteArray, fileSize: Long, out: OutputStream) {
            if (fileSize <= 0L) return
            val runs = getInodeRuns(inodeBytes, fileSize)
            var currentLogicalBlock = 0L
            var remainingBytes = fileSize
            val zeroBuf = ByteArray(blockSize.coerceAtMost(65536))

            fun writeZeros(byteCount: Long) {
                var rem = byteCount
                while (rem > 0L) {
                    val chunk = rem.coerceAtMost(zeroBuf.size.toLong()).toInt()
                    out.write(zeroBuf, 0, chunk)
                    rem -= chunk
                }
            }

            for (run in runs) {
                if (remainingBytes <= 0L) break
                if (run.logicalBlock > currentLogicalBlock) {
                    val gapBlocks = run.logicalBlock - currentLogicalBlock
                    val gapBytes = (gapBlocks * blockSize).coerceAtMost(remainingBytes)
                    writeZeros(gapBytes)
                    remainingBytes -= gapBytes
                    currentLogicalBlock = run.logicalBlock
                }
                if (remainingBytes <= 0L) break
                val runBytes = (run.blockCount * blockSize).coerceAtMost(remainingBytes)
                if (run.physBlock == 0L) {
                    writeZeros(runBytes)
                } else {
                    copyRangeToStream(r, baseOffset + run.physBlock * blockSize, runBytes, out)
                }
                remainingBytes -= runBytes
                currentLogicalBlock += run.blockCount
            }
            if (remainingBytes > 0L) {
                writeZeros(remainingBytes)
            }
        }

        val entries = mutableListOf<DiscEntry>()
        val visitedInodes = HashSet<Long>()

        fun walkExtDir(dirInodeNum: Long, currentPath: String) {
            if (dirInodeNum < 2L || dirInodeNum > inodesCount || !visitedInodes.add(dirInodeNum)) return
            val inodeBytes = readBytesAt(dev, getInodeOffset(dirInodeNum), inodeSize)
            val dirSize = u32le(inodeBytes, 4).coerceAtMost(16 * 1024 * 1024L)
            val baos = ByteArrayOutputStream(dirSize.toInt().coerceAtLeast(blockSize))
            streamInodeData(dev, inodeBytes, dirSize, baos)
            val dirBytes = baos.toByteArray()

            var pos = 0
            while (pos + 8 <= dirBytes.size) {
                val childInode = u32le(dirBytes, pos)
                val recLen = u16le(dirBytes, pos + 4)
                if (recLen < 8 || pos + recLen > dirBytes.size) break
                val nameLen = u8(dirBytes, pos + 6)
                if (childInode in 1L..inodesCount && nameLen > 0 && pos + 8 + nameLen <= pos + recLen) {
                    val name = String(dirBytes, pos + 8, nameLen, StandardCharsets.UTF_8)
                    if (name != "." && name != "..") {
                        val childInodeBytes = readBytesAt(dev, getInodeOffset(childInode), inodeSize)
                        val mode = u16le(childInodeBytes, 0)
                        val isDir = (mode and 0xF000) == 0x4000
                        val mtimeSec = u32le(childInodeBytes, 16)
                        val modifiedAtMillis = if (mtimeSec > 0L) mtimeSec * 1000L else null
                        val sizeLo = u32le(childInodeBytes, 4)
                        val sizeHi = if (!isDir) u32le(childInodeBytes, 108) else 0L
                        val childSize = (sizeHi shl 32) or sizeLo

                        val childPath = joinPath(currentPath, name)
                        if (isDir) {
                            entries += DiscEntry(
                                path = childPath,
                                isDirectory = true,
                                sizeBytes = null,
                                modifiedAtMillis = modifiedAtMillis
                            )
                            walkExtDir(childInode, childPath)
                        } else {
                            val snapshotInode = childInodeBytes.copyOf()
                            entries += DiscEntry(
                                path = childPath,
                                isDirectory = false,
                                sizeBytes = childSize,
                                modifiedAtMillis = modifiedAtMillis,
                                extractor = { d, out ->
                                    streamInodeData(d, snapshotInode, childSize, out)
                                }
                            )
                        }
                    }
                }
                pos += recLen
            }
        }

        walkExtDir(2L, prefix)
        return entries
    }

    // =========================================================================
    // Low-Level Little-Endian Primitives, I/O & Date Helpers
    // =========================================================================

    private fun u8(buf: ByteArray, offset: Int): Int =
        buf[offset].toInt() and 0xFF

    private fun u16le(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or ((buf[offset + 1].toInt() and 0xFF) shl 8)

    private fun u32le(buf: ByteArray, offset: Int): Long =
        (buf[offset].toLong() and 0xFFL) or
            ((buf[offset + 1].toLong() and 0xFFL) shl 8) or
            ((buf[offset + 2].toLong() and 0xFFL) shl 16) or
            ((buf[offset + 3].toLong() and 0xFFL) shl 24)

    private fun i64le(buf: ByteArray, offset: Int): Long =
        u32le(buf, offset) or (u32le(buf, offset + 4) shl 32)

    private fun readBytesAt(dev: BlockDevice, offset: Long, length: Int): ByteArray {
        return dev.readBytes(offset, length)
    }

    private fun copyRangeToStream(dev: BlockDevice, offset: Long, length: Long, out: OutputStream) {
        if (length <= 0L) return
        var remaining = length
        val buffer = ByteArray(65536)
        var curOffset = offset
        while (remaining > 0L) {
            val toRead = remaining.coerceAtMost(buffer.size.toLong()).toInt()
            val read = dev.readAt(curOffset, buffer, 0, toRead)
            if (read <= 0) break
            out.write(buffer, 0, read)
            curOffset += read
            remaining -= read
        }
    }

    private fun decodeDosDateTime(dosDate: Int, dosTime: Int): Long? {
        if (dosDate == 0) return null
        val year = ((dosDate ushr 9) and 0x7F) + 1980
        val month = ((dosDate ushr 5) and 0x0F).coerceIn(1, 12)
        val day = (dosDate and 0x1F).coerceIn(1, 31)
        val hour = ((dosTime ushr 11) and 0x1F).coerceIn(0, 23)
        val minute = ((dosTime ushr 5) and 0x3F).coerceIn(0, 59)
        val second = ((dosTime and 0x1F) * 2).coerceIn(0, 59)
        return toUtcMillis(year, month, day, hour, minute, second)
    }

    private fun toUtcMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(year, month - 1, day, hour, minute, second)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun sanitizeSegment(name: String): String =
        name.replace('/', '_').replace('\\', '_').trim()

    private fun joinPath(parent: String, child: String): String {
        val cleanParent = normalizePath(parent)
        val cleanChild = sanitizeSegment(child)
        return if (cleanParent.isEmpty()) cleanChild else "$cleanParent/$cleanChild"
    }

    private fun normalizePath(rawName: String): String =
        rawName.replace('\\', '/').trim().removePrefix("/").removePrefix("./").trim('/')

    private fun sha256(str: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(str.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
