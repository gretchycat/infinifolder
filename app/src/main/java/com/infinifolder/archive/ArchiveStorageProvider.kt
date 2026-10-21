package com.infinifolder.archive

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.LruCache
import com.infinifolder.model.*
import com.infinifolder.sftp.SftpUri
import com.infinifolder.storage.LocalStorageProvider
import com.infinifolder.storage.StorageProvider
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

class ArchiveStorageProvider(
    private val context: Context,
    private val localProvider: LocalStorageProvider? = null,
    private val customCacheDir: File? = null
) : StorageProvider {

    override val id: String = "archive"

    var streamResolver: ((Uri) -> InputStream?)? = null
    var metadataResolver: ((Uri) -> Pair<Long?, Long?>?)? = null

    private val reader = ArchiveReader(File(customCacheDir ?: context.cacheDir, "archive_temp"))
    private val treeCache = LruCache<String, ArchiveTree>(64)

    private fun sha256(str: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(str.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }

    /** Returns internal storage/infinifolder/cache, creating it if it does not exist. */
    fun getCacheRoot(): File {
        if (customCacheDir != null) {
            if (!customCacheDir.exists()) customCacheDir.mkdirs()
            return customCacheDir
        }
        return try {
            val externalStorage = Environment.getExternalStorageDirectory()
            if (externalStorage != null) {
                val primaryDir = File(externalStorage, "infinifolder/cache")
                if (!primaryDir.exists()) {
                    primaryDir.mkdirs()
                }
                if (primaryDir.exists() && (primaryDir.canWrite() || primaryDir.isDirectory)) {
                    return primaryDir
                }
            }
            fallbackCacheRoot()
        } catch (_: Exception) {
            fallbackCacheRoot()
        }
    }

    private fun fallbackCacheRoot(): File {
        val extCache = context.getExternalCacheDir()?.resolve("infinifolder/cache")
        if (extCache != null) {
            if (!extCache.exists()) extCache.mkdirs()
            if (extCache.exists()) return extCache
        }
        val internalCache = File(context.cacheDir, "infinifolder/cache")
        if (!internalCache.exists()) internalCache.mkdirs()
        return internalCache
    }

    private fun resolveSource(sourceUri: Uri): Pair<() -> InputStream, File?> {
        val lp = localProvider
        if (lp != null && lp.owns(sourceUri)) {
            val file = runCatching { lp.fileFor(sourceUri) }.getOrNull()
            if (file != null) return Pair({ file.inputStream() }, file)
        }
        if (sourceUri.scheme == "file") {
            val file = File(sourceUri.path ?: "")
            return Pair({ file.inputStream() }, file)
        }
        if (ArchiveUri.isArchiveUri(sourceUri)) {
            val cachedFile = extractToCache(sourceUri)
            return Pair({ cachedFile.inputStream() }, cachedFile)
        }
        val customResolver = streamResolver
        if (customResolver != null && (SftpUri.isSftpUri(sourceUri) || sourceUri.scheme != "content")) {
            val openStream: () -> InputStream = {
                customResolver(sourceUri)
                    ?: throw IOException("Cannot open input stream for: $sourceUri")
            }
            return Pair(openStream, null)
        }
        if (sourceUri.scheme == "content") {
            val openStream: () -> InputStream = {
                context.contentResolver.openInputStream(sourceUri)
                    ?: throw IOException("Cannot open input stream for: $sourceUri")
            }
            return Pair(openStream, null)
        }
        if (customResolver != null) {
            val openStream: () -> InputStream = {
                customResolver(sourceUri)
                    ?: throw IOException("Cannot open input stream for: $sourceUri")
            }
            return Pair(openStream, null)
        }
        throw IOException("Cannot resolve stream for URI scheme: ${sourceUri.scheme ?: "unknown"}")
    }

    private fun getSourceName(sourceUri: Uri): String {
        val lp = localProvider
        if (lp != null && lp.owns(sourceUri)) {
            val name = runCatching { lp.fileFor(sourceUri).name }.getOrNull()
            if (name != null) return name
        }
        if (sourceUri.scheme == "file") {
            return File(sourceUri.path ?: "").name
        }
        if (ArchiveUri.isArchiveUri(sourceUri)) {
            return ArchiveUri.getEntryPath(sourceUri).substringAfterLast('/')
        }
        if (SftpUri.isSftpUri(sourceUri)) {
            val remotePath = SftpUri.getRemotePath(sourceUri)
            val name = remotePath.substringAfterLast('/')
            if (name.isNotEmpty()) return name
        }
        if (sourceUri.scheme == "content") {
            try {
                context.contentResolver.query(sourceUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val name = cursor.getString(0)
                        if (!name.isNullOrEmpty()) return name
                    }
                }
            } catch (_: Exception) {}
        }
        return sourceUri.lastPathSegment?.substringAfterLast('/') ?: "archive.zip"
    }

    private fun getSourceMetadata(sourceUri: Uri): Pair<Long?, Long?> {
        val lp = localProvider
        if (lp != null && lp.owns(sourceUri)) {
            val file = runCatching { lp.fileFor(sourceUri) }.getOrNull()
            if (file != null) return Pair(file.length(), file.lastModified())
        }
        if (sourceUri.scheme == "file") {
            val file = File(sourceUri.path ?: "")
            return Pair(file.length(), file.lastModified())
        }
        val meta = metadataResolver?.invoke(sourceUri)
        if (meta != null) return meta
        if (sourceUri.scheme == "content") {
            try {
                context.contentResolver.query(sourceUri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) {
                        return Pair(cursor.getLong(0), null)
                    }
                }
            } catch (_: Exception) {}
        }
        return Pair(null, null)
    }

    fun getArchiveTree(sourceUri: Uri): ArchiveTree {
        val (size, modified) = getSourceMetadata(sourceUri)
        val cacheKey = "${sourceUri}:${size ?: 0}:${modified ?: 0}"
        synchronized(treeCache) {
            val cached = treeCache.get(cacheKey)
            if (cached != null) return cached
        }

        val (openStream, localFile) = resolveSource(sourceUri)
        val archiveName = getSourceName(sourceUri)
        if (localFile == null && ArchiveFormats.isDiscImage(archiveName) && size != null && size > MAX_REMOTE_DISC_IMAGE_SIZE) {
            val formatted = android.text.format.Formatter.formatShortFileSize(context, size)
            throw IOException("Disk image '$archiveName' ($formatted) is too large to stream over network. Download the file locally to inspect its contents.")
        }
        val tree = reader.buildTree(sourceUri, archiveName, openStream, localFile)

        synchronized(treeCache) {
            treeCache.put(cacheKey, tree)
        }
        return tree
    }

    /**
     * Resolves the relative path of an archive entry structured as if from the virtual root.
     * E.g.: "Internal shared storage/Download/test.zip/subfolder/file.txt"
     */
    fun resolveVirtualPath(archiveUri: Uri): String {
        val sourceUri = ArchiveUri.getSourceUri(archiveUri)
        val entryPath = ArchiveUri.getEntryPath(archiveUri)

        val sourceVirtualPath = when {
            localProvider != null && localProvider!!.owns(sourceUri) -> {
                val lp = localProvider!!
                val file = runCatching { lp.fileFor(sourceUri) }.getOrNull()
                if (file != null) {
                    val parts = listOf("Internal shared storage") + lp.store.components(file)
                    parts.joinToString("/")
                } else getSourceName(sourceUri)
            }
            sourceUri.scheme == "file" -> {
                val file = File(sourceUri.path ?: "")
                val extStorage = Environment.getExternalStorageDirectory()
                if (extStorage != null && file.absolutePath.startsWith(extStorage.absolutePath)) {
                    val rel = file.absolutePath.removePrefix(extStorage.absolutePath).trimStart('/')
                    "Internal shared storage/$rel"
                } else {
                    file.name
                }
            }
            ArchiveUri.isArchiveUri(sourceUri) -> {
                resolveVirtualPath(sourceUri)
            }
            SftpUri.isSftpUri(sourceUri) -> {
                val remotePath = SftpUri.getRemotePath(sourceUri).trimStart('/')
                "sftp/${SftpUri.getConnectionId(sourceUri)}/$remotePath"
            }
            sourceUri.scheme == "content" -> {
                val auth = sourceUri.authority.orEmpty()
                if (auth == "com.android.externalstorage.documents") {
                    val docId = try {
                        if (sourceUri.pathSegments.contains("document")) DocumentsContract.getDocumentId(sourceUri)
                        else DocumentsContract.getTreeDocumentId(sourceUri)
                    } catch (_: Exception) { "" }
                    if (docId.startsWith("primary:")) {
                        val rel = docId.removePrefix("primary:").trimStart('/')
                        "Internal shared storage/$rel"
                    } else {
                        getSourceName(sourceUri)
                    }
                } else {
                    getSourceName(sourceUri)
                }
            }
            else -> getSourceName(sourceUri)
        }

        val tree = try { getArchiveTree(sourceUri) } catch (_: Exception) { null }
        val matchedEntry = tree?.getEntry(entryPath)
        val canonicalEntryPath = matchedEntry?.path ?: entryPath
        val cleanEntry = canonicalEntryPath.replace('\\', '/').trim().removePrefix("/").removeSuffix("/")
        return if (cleanEntry.isEmpty()) sourceVirtualPath else "$sourceVirtualPath/$cleanEntry"
    }

    fun extractToCache(archiveUri: Uri, virtualPathOverride: String? = null): File {
        require(ArchiveUri.isArchiveUri(archiveUri)) { "Expected archive URI: $archiveUri" }
        val sourceUri = ArchiveUri.getSourceUri(archiveUri)
        val entryPath = ArchiveUri.getEntryPath(archiveUri)
        val (openStream, localFile) = resolveSource(sourceUri)
        val archiveName = getSourceName(sourceUri)

        val virtualPath = virtualPathOverride ?: resolveVirtualPath(archiveUri)
        val sanitizedRelativePath = virtualPath.split('/')
            .filter { it.isNotEmpty() }
            .joinToString("/") { segment ->
                segment.replace(':', '_').replace('\u0000', '_')
            }

        val cacheBase = getCacheRoot()
        val targetFile = File(cacheBase, sanitizedRelativePath)

        val tree = getArchiveTree(sourceUri)
        val entry = tree.getEntry(entryPath)
        val expectedSize = entry?.sizeBytes

        if (targetFile.exists() && (expectedSize == null || targetFile.length() == expectedSize)) {
            return targetFile
        }

        targetFile.parentFile?.mkdirs()
        val canonicalPath = entry?.path ?: entryPath
        val extracted = reader.extractEntry(archiveName, canonicalPath, targetFile, openStream, localFile)
        if (entry?.modifiedAtMillis != null && entry.modifiedAtMillis > 0L) {
            extracted.setLastModified(entry.modifiedAtMillis)
        }
        return extracted
    }

    fun openStream(archiveUri: Uri): InputStream {
        val sourceUri = ArchiveUri.getSourceUri(archiveUri)
        val entryPath = ArchiveUri.getEntryPath(archiveUri)
        val (openStream, localFile) = resolveSource(sourceUri)
        val archiveName = getSourceName(sourceUri)
        val tree = try { getArchiveTree(sourceUri) } catch (_: Exception) { null }
        val canonicalPath = tree?.getEntry(entryPath)?.path ?: entryPath
        return reader.openEntryStream(archiveName, canonicalPath, openStream, localFile)
    }

    override fun list(parent: ResourceRef): ProviderResult<List<ResourceEntry>> = try {
        val uri = Uri.parse(parent.documentId)
        val sourceUri = ArchiveUri.getSourceUri(uri)
        val parentPath = ArchiveUri.getEntryPath(uri)
        val tree = getArchiveTree(sourceUri)
        val entries = tree.list(parentPath)

        val resourceEntries = entries.map { entry ->
            val entryUri = ArchiveUri.build(sourceUri, entry.path)
            ResourceEntry(
                ref = ResourceRef(id, entryUri.toString()),
                metadata = ResourceMetadata(
                    name = entry.name,
                    mimeType = entry.mimeType,
                    sizeBytes = entry.sizeBytes,
                    modifiedAtMillis = entry.modifiedAtMillis,
                    isDirectory = entry.isDirectory
                ),
                uri = entryUri
            )
        }
        ProviderResult.Success(resourceEntries)
    } catch (e: Throwable) {
        ProviderResult.Failure(e.message ?: "Failed to list archive contents", e)
    }

    override fun openRead(ref: ResourceRef): ProviderResult<InputStream> = try {
        val uri = Uri.parse(ref.documentId)
        ProviderResult.Success(openStream(uri))
    } catch (e: Throwable) {
        ProviderResult.Failure(e.message ?: "Failed to read archive entry", e)
    }

    fun checkMounted(archiveUri: Uri): MountCheckResult? {
        if (!ArchiveUri.isArchiveUri(archiveUri)) return null
        val sourceUri = ArchiveUri.getSourceUri(archiveUri)
        val (_, localFile) = try { resolveSource(sourceUri) } catch (_: Exception) { return null }
        if (localFile == null) return null
        return MountedPartitionDetector.checkMounted(localFile)
    }

    fun isWritable(archiveUri: Uri): Boolean {
        if (!ArchiveUri.isArchiveUri(archiveUri)) return false
        val sourceUri = ArchiveUri.getSourceUri(archiveUri)
        val (_, localFile) = try { resolveSource(sourceUri) } catch (_: Exception) { return false }
        if (localFile == null || !localFile.exists() || !localFile.canWrite()) return false
        val mountCheck = MountedPartitionDetector.checkMounted(localFile)
        if (mountCheck.isMounted) return false
        val archiveName = getSourceName(sourceUri)
        return reader.isWritable(archiveName, localFile)
    }

    override fun openWrite(ref: ResourceRef): ProviderResult<java.io.OutputStream> = try {
        val uri = Uri.parse(ref.documentId)
        val mountCheck = checkMounted(uri)
        if (mountCheck?.isMounted == true) {
            val sourceUri = ArchiveUri.getSourceUri(uri)
            val archiveName = getSourceName(sourceUri)
            ProviderResult.Failure(
                "Cannot write to '$archiveName': partition is currently mounted (${mountCheck.reason})",
                MountedPartitionException(archiveName, mountCheck.reason)
            )
        } else if (!isWritable(uri)) {
            ProviderResult.Unsupported(ResourceCapability.CREATE)
        } else {
            val sourceUri = ArchiveUri.getSourceUri(uri)
            val entryPath = ArchiveUri.getEntryPath(uri)
            val (_, localFile) = resolveSource(sourceUri)
            val archiveName = getSourceName(sourceUri)
            val baos = object : java.io.ByteArrayOutputStream() {
                override fun close() {
                    super.close()
                    val data = toByteArray()
                    reader.writeEntry(archiveName, localFile, entryPath, data)
                    treeCache.remove(sourceUri.toString())
                }
            }
            ProviderResult.Success(baos)
        }
    } catch (e: Exception) {
        ProviderResult.Failure(e.message ?: "Failed to write archive entry", e)
    }

    override fun createDirectory(parent: ResourceRef, name: String): ProviderResult<ResourceEntry> = try {
        val parentUri = Uri.parse(parent.documentId)
        val mountCheck = checkMounted(parentUri)
        if (mountCheck?.isMounted == true) {
            val sourceUri = ArchiveUri.getSourceUri(parentUri)
            val archiveName = getSourceName(sourceUri)
            ProviderResult.Failure(
                "Cannot create directory in '$archiveName': partition is currently mounted (${mountCheck.reason})",
                MountedPartitionException(archiveName, mountCheck.reason)
            )
        } else if (!isWritable(parentUri)) {
            ProviderResult.Unsupported(ResourceCapability.CREATE)
        } else {
            val sourceUri = ArchiveUri.getSourceUri(parentUri)
            val parentPath = ArchiveUri.getEntryPath(parentUri)
            val (_, localFile) = resolveSource(sourceUri)
            val archiveName = getSourceName(sourceUri)
            val newEntryPath = if (parentPath.isEmpty()) name else "$parentPath/$name"
            reader.createDirectory(archiveName, localFile, newEntryPath)
            treeCache.remove(sourceUri.toString())
            val newUri = ArchiveUri.build(sourceUri, newEntryPath)
            ProviderResult.Success(
                ResourceEntry(
                    ref = ResourceRef(id, newUri.toString()),
                    metadata = ResourceMetadata(
                        name = name,
                        mimeType = "",
                        sizeBytes = null,
                        modifiedAtMillis = System.currentTimeMillis(),
                        isDirectory = true
                    ),
                    uri = newUri
                )
            )
        }
    } catch (e: Exception) {
        ProviderResult.Failure(e.message ?: "Failed to create directory in archive", e)
    }

    override fun createFile(parent: ResourceRef, name: String, mimeType: String): ProviderResult<ResourceEntry> = try {
        val parentUri = Uri.parse(parent.documentId)
        val mountCheck = checkMounted(parentUri)
        if (mountCheck?.isMounted == true) {
            val sourceUri = ArchiveUri.getSourceUri(parentUri)
            val archiveName = getSourceName(sourceUri)
            ProviderResult.Failure(
                "Cannot create file in '$archiveName': partition is currently mounted (${mountCheck.reason})",
                MountedPartitionException(archiveName, mountCheck.reason)
            )
        } else if (!isWritable(parentUri)) {
            ProviderResult.Unsupported(ResourceCapability.CREATE)
        } else {
            val sourceUri = ArchiveUri.getSourceUri(parentUri)
            val parentPath = ArchiveUri.getEntryPath(parentUri)
            val (_, localFile) = resolveSource(sourceUri)
            val archiveName = getSourceName(sourceUri)
            val newEntryPath = if (parentPath.isEmpty()) name else "$parentPath/$name"
            reader.createFile(archiveName, localFile, newEntryPath, ByteArray(0))
            treeCache.remove(sourceUri.toString())
            val newUri = ArchiveUri.build(sourceUri, newEntryPath)
            ProviderResult.Success(
                ResourceEntry(
                    ref = ResourceRef(id, newUri.toString()),
                    metadata = ResourceMetadata(
                        name = name,
                        mimeType = mimeType.ifEmpty { "application/octet-stream" },
                        sizeBytes = 0L,
                        modifiedAtMillis = System.currentTimeMillis(),
                        isDirectory = false
                    ),
                    uri = newUri
                )
            )
        }
    } catch (e: Exception) {
        ProviderResult.Failure(e.message ?: "Failed to create file in archive", e)
    }

    override fun rename(ref: ResourceRef, name: String): ProviderResult<ResourceEntry> =
        ProviderResult.Unsupported(ResourceCapability.RENAME)

    override fun delete(ref: ResourceRef): ProviderResult<Unit> = try {
        val uri = Uri.parse(ref.documentId)
        val mountCheck = checkMounted(uri)
        if (mountCheck?.isMounted == true) {
            val sourceUri = ArchiveUri.getSourceUri(uri)
            val archiveName = getSourceName(sourceUri)
            ProviderResult.Failure(
                "Cannot delete from '$archiveName': partition is currently mounted (${mountCheck.reason})",
                MountedPartitionException(archiveName, mountCheck.reason)
            )
        } else if (!isWritable(uri)) {
            ProviderResult.Unsupported(ResourceCapability.DELETE)
        } else {
            val sourceUri = ArchiveUri.getSourceUri(uri)
            val entryPath = ArchiveUri.getEntryPath(uri)
            val (_, localFile) = resolveSource(sourceUri)
            val archiveName = getSourceName(sourceUri)
            reader.deleteEntry(archiveName, localFile, entryPath)
            treeCache.remove(sourceUri.toString())
            ProviderResult.Success(Unit)
        }
    } catch (e: Exception) {
        ProviderResult.Failure(e.message ?: "Failed to delete archive entry", e)
    }

    override fun copy(source: ResourceRef, destination: ResourceRef): ProviderResult<ResourceRef> =
        ProviderResult.Unsupported(ResourceCapability.COPY)

    override fun move(source: ResourceRef, destination: ResourceRef): ProviderResult<ResourceRef> =
        ProviderResult.Unsupported(ResourceCapability.MOVE)

    companion object {
        const val MAX_REMOTE_DISC_IMAGE_SIZE = 512L * 1024L * 1024L // 512 MB
    }
}
