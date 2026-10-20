package com.infinifolder.archive

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.LruCache
import com.infinifolder.model.*
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

    private val reader = ArchiveReader(File(context.cacheDir, "archive_temp"))
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
        if (localProvider != null && localProvider.owns(sourceUri)) {
            val file = localProvider.fileFor(sourceUri)
            return Pair({ file.inputStream() }, file)
        }
        if (sourceUri.scheme == "file") {
            val file = File(sourceUri.path ?: "")
            return Pair({ file.inputStream() }, file)
        }
        if (ArchiveUri.isArchiveUri(sourceUri)) {
            val cachedFile = extractToCache(sourceUri)
            return Pair({ cachedFile.inputStream() }, cachedFile)
        }
        val openStream: () -> InputStream = {
            context.contentResolver.openInputStream(sourceUri)
                ?: throw IOException("Cannot open input stream for: $sourceUri")
        }
        return Pair(openStream, null)
    }

    private fun getSourceName(sourceUri: Uri): String {
        if (localProvider != null && localProvider.owns(sourceUri)) {
            return localProvider.fileFor(sourceUri).name
        }
        if (sourceUri.scheme == "file") {
            return File(sourceUri.path ?: "").name
        }
        if (ArchiveUri.isArchiveUri(sourceUri)) {
            return ArchiveUri.getEntryPath(sourceUri).substringAfterLast('/')
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
        if (localProvider != null && localProvider.owns(sourceUri)) {
            val file = localProvider.fileFor(sourceUri)
            return Pair(file.length(), file.lastModified())
        }
        if (sourceUri.scheme == "file") {
            val file = File(sourceUri.path ?: "")
            return Pair(file.length(), file.lastModified())
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
            localProvider != null && localProvider.owns(sourceUri) -> {
                val file = localProvider.fileFor(sourceUri)
                val parts = listOf("Internal shared storage") + localProvider.store.components(file)
                parts.joinToString("/")
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
    } catch (e: Exception) {
        ProviderResult.Failure(e.message ?: "Failed to list archive contents", e)
    }

    override fun openRead(ref: ResourceRef): ProviderResult<InputStream> = try {
        val uri = Uri.parse(ref.documentId)
        ProviderResult.Success(openStream(uri))
    } catch (e: Exception) {
        ProviderResult.Failure(e.message ?: "Failed to read archive entry", e)
    }

    override fun openWrite(ref: ResourceRef): ProviderResult<java.io.OutputStream> =
        ProviderResult.Unsupported(ResourceCapability.CREATE)

    override fun createDirectory(parent: ResourceRef, name: String): ProviderResult<ResourceEntry> =
        ProviderResult.Unsupported(ResourceCapability.CREATE)
    override fun createFile(parent: ResourceRef, name: String, mimeType: String): ProviderResult<ResourceEntry> =
        ProviderResult.Unsupported(ResourceCapability.CREATE)

    override fun rename(ref: ResourceRef, name: String): ProviderResult<ResourceEntry> =
        ProviderResult.Unsupported(ResourceCapability.RENAME)

    override fun delete(ref: ResourceRef): ProviderResult<Unit> =
        ProviderResult.Unsupported(ResourceCapability.DELETE)

    override fun copy(source: ResourceRef, destination: ResourceRef): ProviderResult<ResourceRef> =
        ProviderResult.Unsupported(ResourceCapability.COPY)

    override fun move(source: ResourceRef, destination: ResourceRef): ProviderResult<ResourceRef> =
        ProviderResult.Unsupported(ResourceCapability.MOVE)
}
