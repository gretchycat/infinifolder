package com.infinifolder.archive

import android.net.Uri
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.ArchiveStreamFactory
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.cpio.CpioArchiveInputStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.CompressorStreamFactory
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.lzma.LZMACompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import com.infinifolder.storage.FileMagicDetector
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream

class ArchiveReader(private val tempDir: File) {

    private val discImageReader = DiscImageReader(tempDir)

    private fun resolveFormat(
        archiveName: String,
        openStream: () -> InputStream,
        localFile: File?
    ): Pair<String, Boolean> {
        val rawExt = ArchiveFormats.archiveExtension(archiveName)
        if (!FileMagicDetector.isAmbiguousOrUnknown(archiveName, "application/x-known")) {
            return rawExt to ArchiveFormats.isDiscImage(archiveName)
        }
        val magic = if (localFile != null && localFile.exists()) {
            FileMagicDetector.detect(localFile, peekCompressedPayload = true)
        } else {
            FileMagicDetector.detectFromStream(openStream)
        }
        return when (magic?.mimeType) {
            "application/x-iso9660-image", "application/x-raw-disk-image",
            "application/x-virtualbox-vdi", "application/x-qemu-disk",
            "application/x-vmdk", "application/x-vhd", "application/x-vhdx" -> rawExt to true
            "application/x-7z-compressed" -> "7z" to false
            "application/zip" -> "zip" to false
            "application/x-tar" -> (if (rawExt in setOf("gz", "bz2", "bz", "xz", "lzma", "zst")) "tar.$rawExt" else "tar") to false
            "application/x-cpio" -> "cpio" to false
            "application/x-archive" -> "ar" to false
            else -> rawExt to ArchiveFormats.isDiscImage(archiveName)
        }
    }

    fun isWritable(archiveName: String, localFile: File?, device: BlockDevice? = null, entryPath: String? = null): Boolean {
        if (entryPath != null) {
            val effectiveDev = device ?: localFile?.let { if (it.exists() && it.canWrite()) FileBlockDevice(java.io.RandomAccessFile(it, "rw"), false) else null }
            if (effectiveDev != null && (archiveName.endsWith(".zip") || archiveName.endsWith(".jar") || archiveName.endsWith(".apk") || archiveName.endsWith(".cbz"))) {
                if (ZipInPlaceModifier.canModifyInPlace(effectiveDev, entryPath)) return true
            }
        }
        return discImageReader.isWritable(archiveName, localFile, device, entryPath)
    }

    fun checkMounted(archiveName: String, localFile: File?): MountCheckResult =
        MountedPartitionDetector.checkMounted(localFile)

    fun createFile(archiveName: String, localFile: File?, entryPath: String, data: ByteArray, device: BlockDevice? = null) =
        discImageReader.createFile(archiveName, localFile, entryPath, data, device)

    fun createDirectory(archiveName: String, localFile: File?, entryPath: String, device: BlockDevice? = null) =
        discImageReader.createDirectory(archiveName, localFile, entryPath, device)

    fun writeEntry(archiveName: String, localFile: File?, entryPath: String, data: ByteArray, device: BlockDevice? = null) {
        val effectiveDev = device ?: localFile?.let { if (it.exists() && it.canWrite()) FileBlockDevice(java.io.RandomAccessFile(it, "rw"), false) else null }
        if (effectiveDev != null && (archiveName.endsWith(".zip") || archiveName.endsWith(".jar") || archiveName.endsWith(".apk") || archiveName.endsWith(".cbz"))) {
            if (ZipInPlaceModifier.canModifyInPlace(effectiveDev, entryPath)) {
                ZipInPlaceModifier.modifyStoredEntry(effectiveDev, entryPath, data)
                return
            }
        }
        discImageReader.writeEntry(archiveName, localFile, entryPath, data, device)
    }

    fun deleteEntry(archiveName: String, localFile: File?, entryPath: String, device: BlockDevice? = null) =
        discImageReader.deleteEntry(archiveName, localFile, entryPath, device)

    fun getEntryBlockDevice(
        archiveName: String,
        entryPath: String,
        device: BlockDevice
    ): BlockDevice? {
        val cleanTarget = normalizePath(entryPath)
        val ext = ArchiveFormats.archiveExtension(archiveName)
        if (ext == "zip" || ext == "jar" || ext == "apk" || ext == "aar" || ext == "cbz") {
            try {
                ZipFile.builder().setSeekableByteChannel(device.asSeekableByteChannel()).get().use { zf ->
                    val entry = zf.getEntry(cleanTarget)
                        ?: zf.getEntry("/$cleanTarget")
                        ?: zf.entries.asSequence().firstOrNull { normalizePath(it.name) == cleanTarget }
                    if (entry != null && entry.method == org.apache.commons.compress.archivers.zip.ZipArchiveEntry.STORED && entry.size >= 0) {
                        val localOffset = entry.localHeaderOffset
                        val lfh = device.readBytes(localOffset, 30)
                        if (lfh.size >= 30) {
                            val nLen = (lfh[26].toInt() and 0xFF) or ((lfh[27].toInt() and 0xFF) shl 8)
                            val eLen = (lfh[28].toInt() and 0xFF) or ((lfh[29].toInt() and 0xFF) shl 8)
                            val dataOffset = localOffset + 30L + nLen + eLen
                            return PartitionSliceBlockDevice(device, dataOffset, entry.size, device.isReadOnly)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        val slice = discImageReader.getPartitionSlice(device, cleanTarget)
        if (slice != null) return slice
        return null
    }

    private data class RawEntry(
        val name: String,
        val isDirectory: Boolean,
        val sizeBytes: Long?,
        val modifiedAtMillis: Long?,
        val isSymbolicLink: Boolean = false,
        val linkTarget: String? = null
    )

    private fun normalizePath(rawName: String): String =
        rawName.replace('\\', '/').trim().removePrefix("/").removePrefix("./").trim('/')

    fun buildTree(
        sourceUri: Uri?,
        archiveName: String,
        openStream: () -> InputStream,
        localFile: File? = null,
        device: BlockDevice? = null
    ): ArchiveTree {
        val rawEntries = readEntries(archiveName, openStream, localFile, device)
        return buildTreeFromRawEntries(sourceUri, rawEntries)
    }

    private fun readEntries(
        archiveName: String,
        openStream: () -> InputStream,
        localFile: File?,
        device: BlockDevice? = null
    ): List<RawEntry> {
        val effectiveDev = device ?: localFile?.let { if (it.exists()) FileBlockDevice(java.io.RandomAccessFile(it, "r"), true) else null }
        val (ext, isDisc) = resolveFormat(archiveName, openStream, localFile)
        if (isDisc) {
            return discImageReader.readEntries(archiveName, openStream, localFile, effectiveDev).map {
                RawEntry(
                    name = it.path,
                    isDirectory = it.isDirectory,
                    sizeBytes = it.sizeBytes,
                    modifiedAtMillis = it.modifiedAtMillis
                )
            }
        }

        if (ext == "7z" || ext == "cb7") {
            if (effectiveDev != null) {
                return read7zEntries(effectiveDev)
            }
            val file = localFile ?: materializeTempArchive(openStream)
            return try {
                read7zEntries(FileBlockDevice(java.io.RandomAccessFile(file, "r"), true))
            } finally {
                if (localFile == null) file.delete()
            }
        }

        if (ext == "zip" || ext == "jar" || ext == "apk" || ext == "aar" || ext == "cbz") {
            if (effectiveDev != null) {
                return readZipEntries(effectiveDev)
            }
            if (localFile != null) {
                return readZipFileEntries(localFile)
            }
        }

        return try {
            openArchiveInputStream(openStream(), ext).use { ais ->
                val list = mutableListOf<RawEntry>()
                var entry = ais.nextEntry
                while (entry != null) {
                    val isSymlink = if (entry is TarArchiveEntry) entry.isSymbolicLink else false
                    val linkTarget = if (entry is TarArchiveEntry && isSymlink) entry.linkName else null
                    list += RawEntry(
                        name = entry.name,
                        isDirectory = entry.isDirectory || entry.name.endsWith('/') || entry.name.endsWith('\\'),
                        sizeBytes = if (entry.isDirectory) null else entry.size.takeIf { it >= 0 },
                        modifiedAtMillis = entry.lastModifiedDate?.time,
                        isSymbolicLink = isSymlink,
                        linkTarget = linkTarget
                    )
                    entry = ais.nextEntry
                }
                if (list.isEmpty()) {
                    // Check if it is actually a disc image with a generic name/extension
                    try {
                        return discImageReader.readEntries(archiveName, openStream, localFile).map {
                            RawEntry(it.path, it.isDirectory, it.sizeBytes, it.modifiedAtMillis)
                        }
                    } catch (_: Exception) {}
                }
                list
            }
        } catch (primaryError: Exception) {
            try {
                discImageReader.readEntries(archiveName, openStream, localFile).map {
                    RawEntry(
                        name = it.path,
                        isDirectory = it.isDirectory,
                        sizeBytes = it.sizeBytes,
                        modifiedAtMillis = it.modifiedAtMillis
                    )
                }
            } catch (_: Exception) {
                throw primaryError
            }
        }
    }

    private fun read7zEntries(device: BlockDevice): List<RawEntry> {
        return SevenZFile.builder().setSeekableByteChannel(device.asSeekableByteChannel()).get().use { sz ->
            val list = mutableListOf<RawEntry>()
            var entry = sz.nextEntry
            while (entry != null) {
                list += RawEntry(
                    name = entry.name,
                    isDirectory = entry.isDirectory || entry.name.endsWith('/') || entry.name.endsWith('\\'),
                    sizeBytes = if (entry.isDirectory) null else entry.size.takeIf { it >= 0 },
                    modifiedAtMillis = entry.lastModifiedDate?.time
                )
                entry = sz.nextEntry
            }
            list
        }
    }

    private fun read7zEntries(file: File): List<RawEntry> {
        return SevenZFile.builder().setFile(file).get().use { sz ->
            val list = mutableListOf<RawEntry>()
            var entry = sz.nextEntry
            while (entry != null) {
                list += RawEntry(
                    name = entry.name,
                    isDirectory = entry.isDirectory || entry.name.endsWith('/') || entry.name.endsWith('\\'),
                    sizeBytes = if (entry.isDirectory) null else entry.size.takeIf { it >= 0 },
                    modifiedAtMillis = entry.lastModifiedDate?.time
                )
                entry = sz.nextEntry
            }
            list
        }
    }

    private fun readZipEntries(device: BlockDevice): List<RawEntry> {
        return ZipFile.builder().setSeekableByteChannel(device.asSeekableByteChannel()).get().use { zf ->
            val list = mutableListOf<RawEntry>()
            val en = zf.entries
            while (en.hasMoreElements()) {
                val entry = en.nextElement()
                list += RawEntry(
                    name = entry.name,
                    isDirectory = entry.isDirectory || entry.name.endsWith('/') || entry.name.endsWith('\\'),
                    sizeBytes = if (entry.isDirectory) null else entry.size.takeIf { it >= 0 },
                    modifiedAtMillis = entry.lastModifiedDate?.time
                )
            }
            list
        }
    }

    private fun readZipFileEntries(file: File): List<RawEntry> {
        return ZipFile.builder().setFile(file).get().use { zf ->
            val list = mutableListOf<RawEntry>()
            val en = zf.entries
            while (en.hasMoreElements()) {
                val entry = en.nextElement()
                list += RawEntry(
                    name = entry.name,
                    isDirectory = entry.isDirectory || entry.name.endsWith('/') || entry.name.endsWith('\\'),
                    sizeBytes = if (entry.isDirectory) null else entry.size.takeIf { it >= 0 },
                    modifiedAtMillis = entry.lastModifiedDate?.time
                )
            }
            list
        }
    }

    fun extractEntry(
        archiveName: String,
        entryPath: String,
        targetFile: File,
        openStream: () -> InputStream,
        localFile: File? = null,
        device: BlockDevice? = null
    ): File {
        val cleanTarget = normalizePath(entryPath)
        targetFile.parentFile?.mkdirs()
        val effectiveDev = device ?: localFile?.let { if (it.exists()) FileBlockDevice(java.io.RandomAccessFile(it, "r"), true) else null }

        val (ext, isDisc) = resolveFormat(archiveName, openStream, localFile)
        if (isDisc) {
            return discImageReader.extractEntry(archiveName, cleanTarget, targetFile, openStream, localFile, effectiveDev)
        }

        if (ext == "7z" || ext == "cb7") {
            if (effectiveDev != null) {
                SevenZFile.builder().setSeekableByteChannel(effectiveDev.asSeekableByteChannel()).get().use { sz ->
                    var entry = sz.nextEntry
                    while (entry != null) {
                        val norm = normalizePath(entry.name)
                        if (norm == cleanTarget || norm.equals(cleanTarget, ignoreCase = true)) {
                            targetFile.outputStream().use { out ->
                                val buffer = ByteArray(8192)
                                var count: Int
                                while (sz.read(buffer).also { count = it } > 0) {
                                    out.write(buffer, 0, count)
                                }
                            }
                            return targetFile
                        }
                        entry = sz.nextEntry
                    }
                }
                throw NoSuchElementException("Entry not found in 7z archive: $entryPath")
            }
            val file = localFile ?: materializeTempArchive(openStream)
            try {
                SevenZFile.builder().setFile(file).get().use { sz ->
                    var entry = sz.nextEntry
                    while (entry != null) {
                        val norm = normalizePath(entry.name)
                        if (norm == cleanTarget || norm.equals(cleanTarget, ignoreCase = true)) {
                            targetFile.outputStream().use { out ->
                                val buffer = ByteArray(8192)
                                var count: Int
                                while (sz.read(buffer).also { count = it } > 0) {
                                    out.write(buffer, 0, count)
                                }
                            }
                            return targetFile
                        }
                        entry = sz.nextEntry
                    }
                }
            } finally {
                if (localFile == null) file.delete()
            }
            throw NoSuchElementException("Entry not found in 7z archive: $entryPath")
        }

        if (ext == "zip" || ext == "jar" || ext == "apk" || ext == "aar" || ext == "cbz") {
            if (effectiveDev != null) {
                ZipFile.builder().setSeekableByteChannel(effectiveDev.asSeekableByteChannel()).get().use { zf ->
                    val entry = zf.getEntry(cleanTarget)
                        ?: zf.getEntry("/$cleanTarget")
                        ?: zf.entries.asSequence().firstOrNull { normalizePath(it.name) == cleanTarget }
                        ?: zf.entries.asSequence().firstOrNull { normalizePath(it.name).equals(cleanTarget, ignoreCase = true) }
                        ?: throw NoSuchElementException("Entry not found in zip archive: $entryPath")
                    zf.getInputStream(entry).use { inStream ->
                        targetFile.outputStream().use { inStream.copyTo(it) }
                    }
                }
                return targetFile
            }
            if (localFile != null) {
                ZipFile.builder().setFile(localFile).get().use { zf ->
                    val entry = zf.getEntry(cleanTarget)
                        ?: zf.getEntry("/$cleanTarget")
                        ?: zf.entries.asSequence().firstOrNull { normalizePath(it.name) == cleanTarget }
                        ?: zf.entries.asSequence().firstOrNull { normalizePath(it.name).equals(cleanTarget, ignoreCase = true) }
                        ?: throw NoSuchElementException("Entry not found in zip archive: $entryPath")
                    zf.getInputStream(entry).use { inStream ->
                        targetFile.outputStream().use { inStream.copyTo(it) }
                    }
                }
                return targetFile
            }
        }

        try {
            openArchiveInputStream(openStream(), ext).use { ais ->
                var entry = ais.nextEntry
                while (entry != null) {
                    val norm = normalizePath(entry.name)
                    if (norm == cleanTarget || norm.equals(cleanTarget, ignoreCase = true)) {
                        targetFile.outputStream().use { ais.copyTo(it) }
                        return targetFile
                    }
                    entry = ais.nextEntry
                }
            }
        } catch (primaryError: Exception) {
            return try {
                discImageReader.extractEntry(archiveName, cleanTarget, targetFile, openStream, localFile, effectiveDev)
            } catch (_: Exception) {
                throw primaryError
            }
        }
        return try {
            discImageReader.extractEntry(archiveName, cleanTarget, targetFile, openStream, localFile, effectiveDev)
        } catch (_: Exception) {
            throw NoSuchElementException("Entry not found in archive: $entryPath")
        }
    }

    /**
     * Reads a single entry's bytes directly in-memory without saving anything to the cache directory.
     */
    fun openEntryStream(
        archiveName: String,
        entryPath: String,
        openStream: () -> InputStream,
        localFile: File? = null,
        device: BlockDevice? = null
    ): InputStream {
        val cleanTarget = normalizePath(entryPath)
        val effectiveDev = device ?: localFile?.let { if (it.exists()) FileBlockDevice(java.io.RandomAccessFile(it, "r"), true) else null }
        val (ext, isDisc) = resolveFormat(archiveName, openStream, localFile)
        if (isDisc) {
            return discImageReader.openEntryStream(archiveName, cleanTarget, openStream, localFile, effectiveDev)
        }

        if (ext == "7z" || ext == "cb7") {
            if (effectiveDev != null) {
                val sz = SevenZFile.builder().setSeekableByteChannel(effectiveDev.asSeekableByteChannel()).get()
                try {
                    var entry = sz.nextEntry
                    while (entry != null) {
                        val norm = normalizePath(entry.name)
                        if (norm == cleanTarget || norm.equals(cleanTarget, ignoreCase = true)) {
                            val content = ByteArray(entry.size.toInt().coerceAtLeast(0))
                            var read = 0
                            while (read < content.size) {
                                val count = sz.read(content, read, content.size - read)
                                if (count <= 0) break
                                read += count
                            }
                            return java.io.ByteArrayInputStream(content)
                        }
                        entry = sz.nextEntry
                    }
                } finally {
                    sz.close()
                }
                throw NoSuchElementException("Entry not found in 7z archive: $entryPath")
            }
            val file = localFile ?: materializeTempArchive(openStream)
            val sz = SevenZFile.builder().setFile(file).get()
            try {
                var entry = sz.nextEntry
                while (entry != null) {
                    val norm = normalizePath(entry.name)
                    if (norm == cleanTarget || norm.equals(cleanTarget, ignoreCase = true)) {
                        val content = ByteArray(entry.size.toInt().coerceAtLeast(0))
                        var read = 0
                        while (read < content.size) {
                            val count = sz.read(content, read, content.size - read)
                            if (count <= 0) break
                            read += count
                        }
                        return java.io.ByteArrayInputStream(content)
                    }
                    entry = sz.nextEntry
                }
            } finally {
                sz.close()
                if (localFile == null) file.delete()
            }
            throw NoSuchElementException("Entry not found in 7z archive: $entryPath")
        }

        if (ext == "zip" || ext == "jar" || ext == "apk" || ext == "aar" || ext == "cbz") {
            if (effectiveDev != null) {
                val zf = ZipFile.builder().setSeekableByteChannel(effectiveDev.asSeekableByteChannel()).get()
                val entry = zf.getEntry(cleanTarget)
                    ?: zf.getEntry("/$cleanTarget")
                    ?: zf.entries.asSequence().firstOrNull { normalizePath(it.name) == cleanTarget }
                    ?: zf.entries.asSequence().firstOrNull { normalizePath(it.name).equals(cleanTarget, ignoreCase = true) }
                    ?: throw NoSuchElementException("Entry not found in zip archive: $entryPath")
                val bytes = zf.getInputStream(entry).use { it.readBytes() }
                zf.close()
                return java.io.ByteArrayInputStream(bytes)
            }
            if (localFile != null) {
                val zf = ZipFile.builder().setFile(localFile).get()
                val entry = zf.getEntry(cleanTarget)
                    ?: zf.getEntry("/$cleanTarget")
                    ?: zf.entries.asSequence().firstOrNull { normalizePath(it.name) == cleanTarget }
                    ?: zf.entries.asSequence().firstOrNull { normalizePath(it.name).equals(cleanTarget, ignoreCase = true) }
                    ?: throw NoSuchElementException("Entry not found in zip archive: $entryPath")
                val bytes = zf.getInputStream(entry).use { it.readBytes() }
                zf.close()
                return java.io.ByteArrayInputStream(bytes)
            }
        }

        try {
            openArchiveInputStream(openStream(), ext).use { ais ->
                var entry = ais.nextEntry
                while (entry != null) {
                    val norm = normalizePath(entry.name)
                    if (norm == cleanTarget || norm.equals(cleanTarget, ignoreCase = true)) {
                        val bytes = ais.readBytes()
                        return java.io.ByteArrayInputStream(bytes)
                    }
                    entry = ais.nextEntry
                }
            }
        } catch (primaryError: Exception) {
            return try {
                discImageReader.openEntryStream(archiveName, cleanTarget, openStream, localFile)
            } catch (_: Exception) {
                throw primaryError
            }
        }
        return try {
            discImageReader.openEntryStream(archiveName, cleanTarget, openStream, localFile)
        } catch (_: Exception) {
            throw NoSuchElementException("Entry not found in archive: $entryPath")
        }
    }

    private fun openArchiveInputStream(raw: InputStream, ext: String): ArchiveInputStream<*> {
        val bis = BufferedInputStream(raw)
        return when {
            ext == "tar.gz" || ext == "tgz" || ext == "tar.z" ->
                TarArchiveInputStream(GzipCompressorInputStream(bis))
            ext == "tar.bz2" || ext == "tbz" || ext == "tbz2" || ext == "tar.bz" ->
                TarArchiveInputStream(BZip2CompressorInputStream(bis))
            ext == "tar.xz" || ext == "txz" ->
                TarArchiveInputStream(XZCompressorInputStream(bis))
            ext == "tar.lzma" || ext == "tlz" ->
                TarArchiveInputStream(LZMACompressorInputStream(bis))
            ext == "tar" || ext == "cbt" ->
                TarArchiveInputStream(bis)
            ext == "zip" || ext == "jar" || ext == "apk" || ext == "aar" || ext == "cbz" ->
                ZipArchiveInputStream(bis)
            ext == "cpio" ->
                CpioArchiveInputStream(bis)
            ext == "ar" || ext == "deb" ->
                ArArchiveInputStream(bis)
            else -> {
                // Try decompressor first, then archiver
                val decompressed = try {
                    CompressorStreamFactory().createCompressorInputStream(bis)
                } catch (_: Exception) {
                    bis
                }
                val bufferedDecompressed = BufferedInputStream(decompressed)
                try {
                    ArchiveStreamFactory().createArchiveInputStream(bufferedDecompressed)
                } catch (_: Exception) {
                    // Fall back to TarArchiveInputStream or ZipArchiveInputStream
                    TarArchiveInputStream(bufferedDecompressed)
                }
            }
        }
    }

    private fun materializeTempArchive(openStream: () -> InputStream): File {
        tempDir.mkdirs()
        val temp = File.createTempFile("inf_archive_", ".tmp", tempDir)
        openStream().use { input ->
            temp.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return temp
    }

    private fun buildTreeFromRawEntries(sourceUri: Uri?, rawEntries: List<RawEntry>): ArchiveTree {
        val intermediateDirs = mutableMapOf<String, ArchiveEntry>()
        val directChildren = mutableMapOf<String, MutableMap<String, ArchiveEntry>>()
        val allEntriesByPath = mutableMapOf<String, ArchiveEntry>()

        fun ensureParentDir(parentPath: String, childName: String, dirEntry: ArchiveEntry) {
            directChildren.getOrPut(parentPath) { mutableMapOf() }[childName] = dirEntry
        }

        for (entry in rawEntries) {
            val clean = normalizePath(entry.name)
            if (clean.isEmpty()) continue
            val isDir = entry.isDirectory || entry.name.endsWith('/') || entry.name.endsWith('\\')
            val segments = clean.split('/').filter { it.isNotEmpty() }
            if (segments.isEmpty()) continue

            var currPath = ""
            for (i in 0 until segments.size - 1) {
                val seg = segments[i]
                val nextPath = if (currPath.isEmpty()) seg else "$currPath/$seg"
                if (!intermediateDirs.containsKey(nextPath)) {
                    val synthetic = ArchiveEntry(
                        path = nextPath,
                        name = seg,
                        isDirectory = true,
                        sizeBytes = null,
                        modifiedAtMillis = null,
                        mimeType = ArchiveMimeTypes.MIME_TYPE_DIR
                    )
                    intermediateDirs[nextPath] = synthetic
                    allEntriesByPath[nextPath] = synthetic
                    ensureParentDir(currPath, seg, synthetic)
                }
                currPath = nextPath
            }

            val leafName = segments.last()
            val leafEntry = ArchiveEntry(
                path = clean,
                name = leafName,
                isDirectory = isDir,
                sizeBytes = if (isDir) null else entry.sizeBytes,
                modifiedAtMillis = entry.modifiedAtMillis,
                mimeType = ArchiveMimeTypes.guessMimeType(leafName, isDir),
                isSymbolicLink = entry.isSymbolicLink,
                linkTarget = entry.linkTarget
            )

            if (isDir) {
                intermediateDirs[clean] = leafEntry
            }
            allEntriesByPath[clean] = leafEntry
            ensureParentDir(currPath, leafName, leafEntry)
        }

        val entriesByParent = directChildren.mapValues { (_, map) -> map.values.toList() }
        return ArchiveTree(sourceUri, entriesByParent, allEntriesByPath)
    }
}
