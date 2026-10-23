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
    override val features = BackendFeatures(randomRead = Support.SUPPORTED, randomWrite = Support.UNSUPPORTED, creation = CreationGuarantee.EXCLUSIVE, rename = Support.UNSUPPORTED, delete = Support.UNKNOWN, nativeLinks = Support.SUPPORTED)


    override fun refForUri(uri: Uri): ResourceRef? = if (ArchiveUri.isArchiveUri(uri)) ResourceRef(id, uri.toString()) else null

    override fun deviceIdentity(ref: ResourceRef): String? = runCatching {
        "container:${ArchiveUri.getSourceUri(Uri.parse(ref.documentId))}"
    }.getOrNull()

    override fun beginWriteBatch(destination: ResourceRef, isCancelled: () -> Boolean): ProviderResult<com.infinifolder.storage.StorageWriteBatch> = try {
        val uri = Uri.parse(destination.documentId)
        if (!isWritable(uri)) throw IOException(writableFailures[uri.toString()] ?: "Container is read-only")
        val storage = sourceProvider ?: throw IOException("Container backing storage is unavailable")
        ProviderResult.Success(ContainerWriteBatch.open(context, localProvider, storage, destination,
            getSourceName(ArchiveUri.getSourceUri(uri)), isCancelled) {
            treeCache.evictAll(); writableHints.clear(); writableFailures.clear()
        })
    } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Cannot prepare container batch", e) }

    override fun backingResource(ref: ResourceRef): ResourceRef? {
        val source = ArchiveUri.getSourceUri(Uri.parse(ref.documentId))
        return sourceProvider?.refForUri(source) ?: localProvider?.refForUri(source)
    }

    override fun fileSystemInfo(ref: ResourceRef): ProviderResult<com.infinifolder.storage.FileSystemInfo> = try {
        val uri = Uri.parse(ref.documentId)
        val source = ArchiveUri.getSourceUri(uri)
        val (openStream, localFile, dev) = resolveSource(source)
        val info = try { reader.fileSystemInfo(getSourceName(source), ArchiveUri.getEntryPath(uri), openStream, localFile, dev) }
            finally { dev?.close() }
        if (info == null) ProviderResult.Unsupported(ResourceCapability.FILESYSTEM_INFO) else ProviderResult.Success(info)
    } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Cannot identify mounted filesystem", e) }

    override fun parent(ref: ResourceRef): ProviderResult<ResourceRef?> = try {
        val uri = Uri.parse(ref.documentId)
        val path = ArchiveUri.getEntryPath(uri)
        ProviderResult.Success(if (path.isEmpty()) null else ResourceRef(id,
            ArchiveUri.build(ArchiveUri.getSourceUri(uri), path.substringBeforeLast('/', "")).toString()))
    } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Cannot resolve archive parent", e) }

    override fun resolveLink(ref: ResourceRef): ProviderResult<ResourceRef> = try {
        val uri = Uri.parse(ref.documentId)
        val source = ArchiveUri.getSourceUri(uri)
        val path = ArchiveUri.getEntryPath(uri)
        val entry = getArchiveTree(source).getEntry(path) ?: throw IOException("Archive link not found")
        val target = entry.linkTarget ?: throw IOException("Archive link target is unavailable")
        val components = mutableListOf<String>()
        val relative = if (target.startsWith('/')) target else path.substringBeforeLast('/', "") + "/" + target
        for (part in relative.split('/')) when (part) {
            "", "." -> Unit
            ".." -> { if (components.isEmpty()) throw IOException("Link escapes archive root"); components.removeAt(components.lastIndex) }
            else -> components.add(part)
        }
        ProviderResult.Success(ResourceRef(id, ArchiveUri.build(source, components.joinToString("/")).toString()))
    } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Cannot resolve archive link", e) }
    override fun stat(ref: ResourceRef): ProviderResult<ResourceEntry> = try {
        val uri = Uri.parse(ref.documentId)
        val source = ArchiveUri.getSourceUri(uri)
        val path = ArchiveUri.getEntryPath(uri)
        if (path.isEmpty()) {
            ProviderResult.Success(ResourceEntry(ref, ResourceMetadata(getSourceName(source),
                "vnd.android.document/directory", null, null, true, canWrite = isWritable(uri)), uri))
        } else {
            val entry = getArchiveTree(source).getEntry(path) ?: throw IOException("Archive entry not found")
            ProviderResult.Success(ResourceEntry(ref, ResourceMetadata(entry.name, entry.mimeType,
                entry.sizeBytes, entry.modifiedAtMillis, entry.isDirectory, canWrite = isWritable(uri),
                isSymbolicLink = entry.isSymbolicLink, linkTarget = entry.linkTarget), uri))
        }
    } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Cannot query archive entry", e) }

    override fun capabilities(ref: ResourceRef): ProviderResult<ResourceCapabilities> = when (val result = stat(ref)) {
        is ProviderResult.Success -> {
            val uri = Uri.parse(ref.documentId)
            val source = ArchiveUri.getSourceUri(uri)
            val path = ArchiveUri.getEntryPath(uri)
            val meta = result.value.metadata
            val writable = meta.canWrite
            val base = ResourceCapabilities.from(meta, features)
            ProviderResult.Success(base.copy(operations = base.operations + mapOf(
                ResourceCapability.ACCEPT_MOVE to Support.UNSUPPORTED,
                ResourceCapability.BATCH_WRITE to if (meta.isDirectory && writable) Support.SUPPORTED else Support.UNSUPPORTED,
                ResourceCapability.CREATE to if (meta.isDirectory && writable) Support.SUPPORTED else Support.UNSUPPORTED,
                ResourceCapability.DELETE to if (path.isNotEmpty() && writable) Support.SUPPORTED else Support.UNSUPPORTED,
                ResourceCapability.MOVE to if (path.isNotEmpty() && writable && !meta.isSymbolicLink) Support.SUPPORTED else Support.UNSUPPORTED)),
                notice = if (writable) null else writableFailures[uri.toString()])
        }
        is ProviderResult.Failure -> result
        is ProviderResult.Unsupported -> result
    }

    private val seekDepth = ThreadLocal.withInitial { 0 }

    override fun openSeekable(ref: ResourceRef, writable: Boolean): ProviderResult<BlockDevice> {
        if (writable) return ProviderResult.Unsupported(ResourceCapability.WRITE)
        val depth = seekDepth.get() ?: 0
        if (depth >= SeekableSourceResolver.MAX_NESTING_DEPTH) return ProviderResult.Failure("Container nesting limit exceeded")
        seekDepth.set(depth + 1)
        var parentDevice: BlockDevice? = null
        return try {
            val uri = Uri.parse(ref.documentId)
            val source = ArchiveUri.getSourceUri(uri)
            val path = ArchiveUri.getEntryPath(uri)
            parentDevice = resolveSource(source).third
            val slice = parentDevice?.let { reader.getEntryBlockDevice(getSourceName(source), path, it) }
            if (slice != null) {
                val owner = parentDevice!!
                parentDevice = null
                ProviderResult.Success(object : BlockDevice by slice {
                    override val isReadOnly = true
                    override val capabilities = setOf(DeviceCapability.READ, DeviceCapability.SEEK)
                    override fun writeAt(offset: Long, buffer: ByteArray, srcOffset: Int, length: Int) { throw IOException("Nested containers are read-only") }
                    override fun truncate(newSize: Long) { throw IOException("Nested containers are read-only") }
                    override fun close() { try { slice.close() } finally { owner.close() } }
                })
            } else {
                parentDevice?.close()
                parentDevice = null
                val snapshot = extractToCache(uri)
                val device = FileBlockDevice(java.io.RandomAccessFile(snapshot, "r"), true, ownsFile = true)
                ProviderResult.Success(object : BlockDevice by device {
                    override fun close() { try { device.close() } finally { snapshot.delete() } }
                })
            }
        } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Cannot open nested container", e) }
        finally { parentDevice?.close(); seekDepth.set(depth) }
    }

    var sourceProvider: StorageProvider? = null
    var streamResolver: ((Uri) -> InputStream?)? = null
    var metadataResolver: ((Uri) -> Pair<Long?, Long?>?)? = null
    var deviceResolver: ((Uri, Boolean) -> BlockDevice?)? = null

    val reader = ArchiveReader(File(customCacheDir ?: context.cacheDir, "archive_temp"))
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

    fun resolveSource(sourceUri: Uri, writable: Boolean = false): Triple<() -> InputStream, File?, BlockDevice?> {
        if (writable && ArchiveUri.isArchiveUri(sourceUri)) {
            throw IOException("Nested containers are read-only until changes can be committed to every enclosing container")
        }
        val dev = deviceResolver?.invoke(sourceUri, writable)
        val flushOnRequest = !trustedBatchSources.contains(sourceUri.toString())
        val lp = localProvider
        if (lp != null && lp.owns(sourceUri)) {
            val file = runCatching { lp.fileFor(sourceUri) }.getOrNull()
            if (file != null) {
                val fileDev = dev ?: if (file.exists()) FileBlockDevice(java.io.RandomAccessFile(file, if (writable) "rw" else "r"), isReadOnly = !writable, ownsFile = true, flushOnRequest = flushOnRequest) else null
                return Triple({ file.inputStream() }, file, fileDev)
            }
        }
        if (sourceUri.scheme == "file") {
            val file = File(sourceUri.path ?: "")
            val fileDev = dev ?: if (file.exists()) FileBlockDevice(java.io.RandomAccessFile(file, if (writable) "rw" else "r"), isReadOnly = !writable, ownsFile = true, flushOnRequest = flushOnRequest) else null
            return Triple({ file.inputStream() }, file, fileDev)
        }
        if (dev != null) {
            return Triple({ dev.asInputStream() }, null, dev)
        }
        if (ArchiveUri.isArchiveUri(sourceUri)) {
            val cachedFile = extractToCache(sourceUri)
            val fileDev = if (cachedFile.exists()) FileBlockDevice(java.io.RandomAccessFile(cachedFile, if (writable) "rw" else "r"), isReadOnly = !writable, ownsFile = true) else null
            return Triple({ cachedFile.inputStream() }, cachedFile, fileDev)
        }
        val customResolver = streamResolver
        if (customResolver != null && (SftpUri.isSftpUri(sourceUri) || sourceUri.scheme != "content")) {
            val openStream: () -> InputStream = {
                customResolver(sourceUri)
                    ?: throw IOException("Cannot open input stream for: $sourceUri")
            }
            return Triple(openStream, null, null)
        }
        if (sourceUri.scheme == "content") {
            val openStream: () -> InputStream = {
                context.contentResolver.openInputStream(sourceUri)
                    ?: throw IOException("Cannot open input stream for: $sourceUri")
            }
            return Triple(openStream, null, null)
        }
        if (customResolver != null) {
            val openStream: () -> InputStream = {
                customResolver(sourceUri)
                    ?: throw IOException("Cannot open input stream for: $sourceUri")
            }
            return Triple(openStream, null, null)
        }
        throw IOException("Cannot resolve stream for URI scheme: ${sourceUri.scheme ?: "unknown"}")
    }

    fun getSourceName(sourceUri: Uri): String {
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
            if (cached != null && modified != null && modified > 0) return cached
        }

        val (openStream, localFile, dev) = resolveSource(sourceUri)
        val archiveName = getSourceName(sourceUri)
        if (dev == null && localFile == null && ArchiveFormats.isDiscImage(archiveName) && size != null && size > MAX_REMOTE_DISC_IMAGE_SIZE) {
            val formatted = android.text.format.Formatter.formatShortFileSize(context, size)
            throw IOException("Disk image '$archiveName' ($formatted) is too large to stream over network. Download the file locally to inspect its contents.")
        }
        val tree = try { reader.buildTree(sourceUri, archiveName, openStream, localFile, dev) }
            finally { dev?.close() }

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
        val (openStream, localFile, dev) = resolveSource(sourceUri)
        val archiveName = getSourceName(sourceUri)

        val virtualPath = virtualPathOverride ?: resolveVirtualPath(archiveUri)
        val sanitizedRelativePath = virtualPath.split('/')
            .filter { it.isNotEmpty() }
            .joinToString("/") { segment ->
                segment.replace(':', '_').replace('\u0000', '_')
            }

        val cacheBase = File(getCacheRoot(), sha256(sourceUri.toString()) + "/" + java.util.UUID.randomUUID()).apply { mkdirs() }
        val targetFile = File(cacheBase, sanitizedRelativePath)
        require(targetFile.canonicalFile.toPath().startsWith(cacheBase.canonicalFile.toPath())) { "Archive path escapes cache" }

        val tree = getArchiveTree(sourceUri)
        val entry = tree.getEntry(entryPath)
        targetFile.parentFile?.mkdirs()
        val canonicalPath = entry?.path ?: entryPath
        val extracted = try { reader.extractEntry(archiveName, canonicalPath, targetFile, openStream, localFile, dev) }
            catch (e: Exception) { targetFile.delete(); throw e }
            finally { dev?.close() }
        if (entry?.modifiedAtMillis != null && entry.modifiedAtMillis > 0L) {
            extracted.setLastModified(entry.modifiedAtMillis)
        }
        return extracted
    }

    fun openStream(archiveUri: Uri): InputStream {
        val sourceUri = ArchiveUri.getSourceUri(archiveUri)
        val entryPath = ArchiveUri.getEntryPath(archiveUri)
        val (openStream, localFile, dev) = resolveSource(sourceUri)
        val archiveName = getSourceName(sourceUri)
        val tree = try { getArchiveTree(sourceUri) } catch (_: Exception) { null }
        val canonicalPath = tree?.getEntry(entryPath)?.path ?: entryPath
        val stream = try { reader.openEntryStream(archiveName, canonicalPath, openStream, localFile, dev) }
            catch (e: Exception) { dev?.close(); throw e }
        return object : java.io.FilterInputStream(stream) {
            override fun close() { try { super.close() } finally { dev?.close() } }
        }
    }

    override fun list(parent: ResourceRef): ProviderResult<List<ResourceEntry>> = try {
        val uri = Uri.parse(parent.documentId)
        val sourceUri = ArchiveUri.getSourceUri(uri)
        val parentPath = ArchiveUri.getEntryPath(uri)
        val tree = getArchiveTree(sourceUri)
        val entries = tree.list(parentPath)

        val archiveWritable = try { isWritable(Uri.parse(parent.documentId)) } catch (_: Throwable) { false }
        val resourceEntries = entries.map { entry ->
            val entryUri = ArchiveUri.build(sourceUri, entry.path)
            ResourceEntry(
                ref = ResourceRef(id, entryUri.toString()),
                metadata = ResourceMetadata(
                    name = entry.name,
                    mimeType = entry.mimeType,
                    sizeBytes = entry.sizeBytes,
                    modifiedAtMillis = entry.modifiedAtMillis,
                    isDirectory = entry.isDirectory,
                    canRead = true,
                    canWrite = archiveWritable,
                    isSymbolicLink = entry.isSymbolicLink,
                    linkTarget = entry.linkTarget
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
        // A batch snapshot lives in app-private cache and cannot be mounted by
        // Android while this batch is operating. Probing /proc/mounts for every
        // per-entry mutation adds noticeable latency to large copies.
        if (trustedBatchSources.contains(sourceUri.toString())) return null
        // Only a local source can be mounted by this Android device. Do not open remote handles for UI checks.
        if (sourceUri.scheme != "file" && localProvider?.owns(sourceUri) != true) return null
        val (_, localFile, dev) = try { resolveSource(sourceUri) } catch (_: Exception) { return null }
        return try { localFile?.let { MountedPartitionDetector.checkMounted(it) } } finally { dev?.close() }
    }

    private val writableFailures = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val writableHints = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    /** Only private, app-created batch snapshots may skip repeated full-image probes. */
    private val trustedBatchSources = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val loggedBatchSources = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    internal fun trustPrivateBatchSource(sourceUri: Uri) {
        require(sourceUri.scheme == "file" && localProvider?.owns(sourceUri) == true) {
            "Only a private local batch snapshot can be trusted"
        }
        val file = localProvider.fileFor(sourceUri)
        require(file.isFile && file.canWrite()) { "Batch snapshot is not writable" }
        trustedBatchSources += sourceUri.toString()
    }

    /** Presentation hint only: mutations always probe their actual writable handle again. */
    fun cachedWritable(archiveUri: Uri): Boolean = writableHints[archiveUri.toString()] ?: false

    fun isWritable(archiveUri: Uri): Boolean {
        val sourceUri = runCatching { ArchiveUri.getSourceUri(archiveUri) }.getOrNull()
        val trustedSnapshot = sourceUri != null && trustedBatchSources.contains(sourceUri.toString())
        val failure = if (trustedSnapshot) {
            val file = runCatching { localProvider?.fileFor(sourceUri!!) }.getOrNull()
            if (file?.isFile == true && file.canWrite()) null else "Private batch image is no longer writable"
        } else try { probeWritableFailure(archiveUri) }
            catch (e: Exception) { e.message ?: "Cannot inspect destination write access" }
        if (writableHints.size > 512) { writableHints.clear(); writableFailures.clear() }
        val key = archiveUri.toString()
        if (failure == null) writableFailures.remove(key) else writableFailures[key] = failure
        writableHints[key] = failure == null
        if (!trustedSnapshot || failure != null || loggedBatchSources.add(sourceUri.toString())) {
            com.infinifolder.storage.DiagnosticLog.record("archive.write-capability",
                "source=${runCatching { getSourceName(sourceUri ?: ArchiveUri.getSourceUri(archiveUri)) }.getOrDefault("unknown")} " +
                    "writable=${failure == null} batchCache=$trustedSnapshot reason=${failure.orEmpty()}")
        }
        return failure == null
    }

    private fun isZipSource(source: Uri): Boolean = ArchiveFormats.containerExtension(getSourceName(source)) == "zip"

    private fun rewriteZip(source: Uri, path: String, directory: Boolean = false, content: File? = null,
        createNew: Boolean = false, delete: Boolean = false) {
        if (ArchiveUri.isArchiveUri(source)) throw IOException("Nested containers are read-only until outer-layer commits exist")
        val storage = sourceProvider ?: throw IOException("Container storage is unavailable")
        val ref = storage.refForUri(source) ?: throw IOException("Container provider is unavailable")
        val work = File(context.cacheDir, "zip_updates/${java.util.UUID.randomUUID()}")
        if (!work.mkdirs()) throw IOException("Cannot allocate ZIP staging directory")
        val original = File(work, "original.zip")
        val updated = File(work, "updated.zip")
        try {
            val opened = storage.openRead(ref)
            if (opened !is ProviderResult.Success) throw IOException((opened as? ProviderResult.Failure)?.message ?: "Cannot read ZIP", (opened as? ProviderResult.Failure)?.cause)
            opened.value.use { input -> original.outputStream().use { output -> input.copyTo(output); output.fd.sync() } }
            val revision = original.inputStream().use(com.infinifolder.storage.ContentRevision::read)
            ZipContainerEditor.rewrite(original, updated, path, directory, content, createNew, delete)
            when (val saved = com.infinifolder.storage.ContentReplacement.replaceFromFile(storage, ref, updated,
                File(context.filesDir, "save_recovery"), revision)) {
                is ProviderResult.Success -> Unit
                is ProviderResult.Failure -> throw IOException(saved.message, saved.cause)
                is ProviderResult.Unsupported -> throw IOException("ZIP backing storage cannot publish replacements")
            }
        } finally { treeCache.evictAll(); writableHints.clear(); work.deleteRecursively() }
    }

    private fun zipCreated(parent: ResourceRef, name: String, directory: Boolean, mime: String): ResourceEntry {
        com.infinifolder.storage.StorageSafety.requireNewName(this, parent, name)
        val uri = Uri.parse(parent.documentId)
        val source = ArchiveUri.getSourceUri(uri)
        val base = ArchiveUri.getEntryPath(uri)
        val path = if (base.isEmpty()) name else "$base/$name"
        rewriteZip(source, path, directory = directory, createNew = true)
        val entryUri = ArchiveUri.build(source, path)
        return ResourceEntry(ResourceRef(id, entryUri.toString()), ResourceMetadata(name,
            if (directory) "vnd.android.document/directory" else mime, if (directory) null else 0L,
            System.currentTimeMillis(), directory, canWrite = true), entryUri)
    }

    private fun probeWritableFailure(archiveUri: Uri): String? {
        if (!ArchiveUri.isArchiveUri(archiveUri)) return "Not a mounted container"
        val sourceUri = ArchiveUri.getSourceUri(archiveUri)
        val entryPath = ArchiveUri.getEntryPath(archiveUri)
        if (ArchiveUri.isArchiveUri(sourceUri)) return "Nested container destinations are read-only"
        if (isZipSource(sourceUri)) {
            if (getSourceName(sourceUri).endsWith(".apk", ignoreCase = true)) return "APK packages are read-only"
            val storage = sourceProvider ?: return "No backing storage provider is available"
            val ref = storage.refForUri(sourceUri) ?: return "No provider owns the backing archive"
            return when (val result = storage.stat(ref)) {
                is ProviderResult.Success -> if (result.value.metadata.canWrite) null else "The backing ZIP file is read-only"
                is ProviderResult.Failure -> result.message
                is ProviderResult.Unsupported -> "The backing provider cannot inspect ZIP write access"
            }
        }
        // A provider advertising staged/native seek writes can be checked using read access.
        // Unknown providers still have to supply an actual writable handle. Mutations always
        // open a fresh writable device regardless of this presentation hint.
        val storage = sourceProvider
        val sourceRef = storage?.refForUri(sourceUri)
        val capabilities = sourceRef?.let { storage.capabilities(it) }
        if (capabilities is ProviderResult.Failure) return capabilities.message
        val support = (capabilities as? ProviderResult.Success)?.value?.get(ResourceCapability.SEEK_WRITE)
        if (support == Support.UNSUPPORTED)
            return "This storage connection cannot safely save changes inside an image. Copy the image to writable local storage and edit that copy."
        val probeOnly = support == Support.SUPPORTED
        val (_, localFile, dev) = try { resolveSource(sourceUri, writable = !probeOnly) }
            catch (e: Exception) { return "Cannot open image for random access: ${e.message ?: "provider refused access"}" }
        return try {
            reader.writableFailureReason(getSourceName(sourceUri), localFile, dev, entryPath, probeOnly)
        } finally { dev?.close() }
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
            val stageDir = File(context.cacheDir, "archive_writes").apply { mkdirs() }
            val content = File.createTempFile("entry-", ".tmp", stageDir)
            val output = content.outputStream()
            val pending = object : java.io.OutputStream(), com.infinifolder.storage.PendingWrite {
                private var closed = false
                private var aborted = false
                override fun abort() { aborted = true }
                override fun write(value: Int) { try { output.write(value) } catch (e: Exception) { aborted = true; throw e } }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    try { output.write(bytes, offset, length) } catch (e: Exception) { aborted = true; throw e }
                }
                override fun flush() { try { output.flush() } catch (e: Exception) { aborted = true; throw e } }
                override fun close() {
                    if (closed) return
                    closed = true
                    try {
                        output.use { if (!aborted) it.fd.sync() }
                        if (!aborted) {
                            if (isZipSource(sourceUri)) rewriteZip(sourceUri, entryPath, content = content)
                            else synchronized(this@ArchiveStorageProvider) {
                                val (_, localFile, dev) = resolveSource(sourceUri, writable = true)
                                try { reader.writeEntryFromFile(getSourceName(sourceUri), localFile, entryPath, content, dev) }
                                finally { dev?.close() }
                            }
                        }
                    } finally { treeCache.evictAll(); content.delete() }
                }
            }
            ProviderResult.Success(pending)
        }
    } catch (e: Exception) {
        ProviderResult.Failure(e.message ?: "Failed to write archive entry", e)
    }

    override fun createDirectory(parent: ResourceRef, name: String): ProviderResult<ResourceEntry> { return try {
        val source = ArchiveUri.getSourceUri(Uri.parse(parent.documentId))
        if (isZipSource(source)) {
            if (!isWritable(Uri.parse(parent.documentId))) return ProviderResult.Unsupported(ResourceCapability.CREATE)
            return ProviderResult.Success(zipCreated(parent, name, true, "vnd.android.document/directory"))
        }
        // FatFileSystem enforces create-new semantics, including case-folded
        // names and short-name aliases, while writing the directory entry.
        com.infinifolder.storage.StorageSafety.validateName(name)
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
            val (_, localFile, dev) = resolveSource(sourceUri, writable = true)
            val archiveName = getSourceName(sourceUri)
            val newEntryPath = if (parentPath.isEmpty()) name else "$parentPath/$name"
            try { synchronized(this) { reader.createDirectory(archiveName, localFile, newEntryPath, dev) } } finally { treeCache.evictAll(); dev?.close() }
            treeCache.evictAll()
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
    }

    override fun createFile(parent: ResourceRef, name: String, mimeType: String): ProviderResult<ResourceEntry> { return try {
        val source = ArchiveUri.getSourceUri(Uri.parse(parent.documentId))
        if (isZipSource(source)) {
            if (!isWritable(Uri.parse(parent.documentId))) return ProviderResult.Unsupported(ResourceCapability.CREATE)
            return ProviderResult.Success(zipCreated(parent, name, false, mimeType))
        }
        // FatFileSystem enforces create-new semantics, including case-folded
        // names and short-name aliases, during the actual directory entry write.
        com.infinifolder.storage.StorageSafety.validateName(name)
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
            val (_, localFile, dev) = resolveSource(sourceUri, writable = true)
            val archiveName = getSourceName(sourceUri)
            val newEntryPath = if (parentPath.isEmpty()) name else "$parentPath/$name"
            try { synchronized(this) { reader.createFile(archiveName, localFile, newEntryPath, ByteArray(0), dev) } } finally { treeCache.evictAll(); dev?.close() }
            treeCache.evictAll()
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
    }

    @Synchronized
    override fun rename(ref: ResourceRef, name: String): ProviderResult<ResourceEntry> =
        ProviderResult.Unsupported(ResourceCapability.RENAME)

    override fun delete(ref: ResourceRef): ProviderResult<Unit> { return try {
        val uri = Uri.parse(ref.documentId)
        val source = ArchiveUri.getSourceUri(uri)
        if (isZipSource(source)) {
            if (!isWritable(uri)) return ProviderResult.Unsupported(ResourceCapability.DELETE)
            val path = ArchiveUri.getEntryPath(uri)
            if (path.isEmpty()) throw IOException("Cannot delete a container root")
            rewriteZip(source, path, delete = true)
            return ProviderResult.Success(Unit)
        }
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
            val (_, localFile, dev) = resolveSource(sourceUri, writable = true)
            val archiveName = getSourceName(sourceUri)
            try { synchronized(this) { reader.deleteEntry(archiveName, localFile, entryPath, dev) } } finally { treeCache.evictAll(); dev?.close() }
            treeCache.evictAll()
            ProviderResult.Success(Unit)
        }
    } catch (e: Exception) {
        ProviderResult.Failure(e.message ?: "Failed to delete archive entry", e)
    }
    }

    override fun copy(source: ResourceRef, destination: ResourceRef): ProviderResult<ResourceRef> =
        ProviderResult.Unsupported(ResourceCapability.COPY)

    override fun move(source: ResourceRef, destination: ResourceRef): ProviderResult<ResourceRef> =
        ProviderResult.Unsupported(ResourceCapability.MOVE)

    companion object {
        const val MAX_REMOTE_DISC_IMAGE_SIZE = 512L * 1024L * 1024L // 512 MB
    }
}
