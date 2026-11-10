package com.infinifolder.viewer

import android.content.Context
import android.net.Uri
import io.github.gretchycat.infinistream.*
import com.infinifolder.model.ProviderResult
import com.infinifolder.model.ResourceEntry
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset

/**
 * Universal content accessor that adapts any VFS URI (local, SAF, remote, archive)
 * for internal media players, document viewers, and in-place editors.
 */
object MediaSourceAdapter {

    data class AudioDirectoryArtworkInfo(
        val directoryUri: Uri?,
        val directoryName: String?,
        val coverUris: List<Uri>
    )

    fun resolveLocalFile(context: Context, uri: Uri): File? {
        if (uri.scheme == "file") {
            return uri.path?.let { File(it) }?.takeIf { it.exists() }
        }
        val local = com.infinifolder.storage.LocalStorageProvider(context.applicationContext)
        if (local.owns(uri)) {
            val f = runCatching { local.fileFor(uri) }.getOrNull()
            if (f != null && f.exists()) return f
        }
        if (uri.scheme == "content") {
            try {
                val docId = if (android.provider.DocumentsContract.isDocumentUri(context, uri)) {
                    android.provider.DocumentsContract.getDocumentId(uri)
                } else null
                val candidates = listOfNotNull(
                    docId,
                    docId?.let { java.net.URLDecoder.decode(it, "UTF-8") },
                    uri.lastPathSegment,
                    uri.lastPathSegment?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                )
                for (c in candidates) {
                    if (c.startsWith("/")) {
                        val f = File(c)
                        if (f.exists()) return f
                    }
                }
            } catch (_: Throwable) {}
        }
        return null
    }

    /** New source path preserves metadata; the InputStream entry point remains a compatibility adapter. */
    fun openStream(context: Context, uri: Uri): InputStream = openSource(context, uri).asInputStream()

    fun openSource(context: Context, uri: Uri): ByteSource {
        val provider = com.infinifolder.storage.StorageRuntime.get(context).provider
        val ref = provider?.refForUri(uri)
        if (provider != null && ref != null) {
            return com.infinifolder.storage.ProviderStreams.openRead(provider, ref, sourceIdentity = uri.toString())
        }
        if (uri.scheme == "content") return InputStreamSource(context.contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot open content stream: $uri"), StreamMetadata(provenance = Provenance(source = uri.toString())))
        if (uri.scheme == "file") return FileSourceResource(File(uri.path ?: throw IOException("Missing path"))).open()
        throw IOException("No provider owns $uri")
    }

    /** Reopenable viewer input. Metadata discovery never reads the encoded body. */
    fun resource(context: Context, uri: Uri): SourceResource {
        val provider = com.infinifolder.storage.StorageRuntime.get(context).provider
        val details = runCatching { provider.refForUri(uri)?.let { ref ->
            (provider.stat(ref) as? ProviderResult.Success)?.value?.metadata
        } }.getOrNull()
        val info = StreamMetadata(content = ContentType(mimeType = details?.mimeType), name = details?.name,
            length = details?.sizeBytes, capabilities = setOf(Capability.READ, Capability.REOPEN),
            provenance = Provenance(source = uri.toString()), warnings = listOfNotNull(details?.readError, details?.recoveryNote))
        return object : SourceResource {
            override val metadata = info
            override fun open() = openSource(context, uri)
        }
    }

    /** Prefer demonstrated positional handles. Archive seek adapters may extract whole entries, so use their stream path. */
    fun openDocumentSource(context: Context, uri: Uri): ByteSource {
        val provider = com.infinifolder.storage.StorageRuntime.get(context).provider
        val ref = provider?.refForUri(uri)
        if (provider != null && ref != null) return openDocumentSource(provider, ref, uri.toString(),
            allowPositional = !com.infinifolder.archive.ArchiveUri.isArchiveUri(uri))
        return openSource(context, uri)
    }

    fun openDocumentSource(provider: com.infinifolder.storage.StorageProvider,
        ref: com.infinifolder.model.ResourceRef, identity: String, allowPositional: Boolean = true): ByteSource {
        if (allowPositional) openPositionalSource(provider, ref, identity)?.let { return it }
        return com.infinifolder.storage.ProviderStreams.openRead(provider, ref, sourceIdentity = identity)
    }

    /** Audio can stage an unsupported source without opening and abandoning a sequential download. */
    fun openPositionalSource(context: Context, uri: Uri): ByteSource? {
        if (com.infinifolder.archive.ArchiveUri.isArchiveUri(uri)) return null
        val provider = com.infinifolder.storage.StorageRuntime.get(context).provider
        val ref = provider.refForUri(uri) ?: return null
        return openPositionalSource(provider, ref, uri.toString())
    }

    fun openPositionalSource(provider: com.infinifolder.storage.StorageProvider,
        ref: com.infinifolder.model.ResourceRef, identity: String): ByteSource? {
        when (val result = com.infinifolder.storage.validateSeekableResult(provider.openSeekable(ref, false), false)) {
            is ProviderResult.Success -> {
                try {
                    return com.infinifolder.storage.BlockDeviceSource(result.value,
                        StreamMetadata(provenance = Provenance(source = identity)))
                } catch (error: Throwable) {
                    try { result.value.close() } catch (close: Throwable) { error.addSuppressed(close) }
                    throw error
                }
            }
            is ProviderResult.Failure -> throw IOException(result.message, result.cause)
            is ProviderResult.Unsupported -> Unit
        }
        return null
    }

    /** Prefer actual random access, including container slices, without extraction fallback. */
    fun openStreamingPositionalSource(context: Context, uri: Uri): ByteSource? {
        if (!com.infinifolder.archive.ArchiveUri.isArchiveUri(uri)) return openPositionalSource(context, uri)
        val archives = com.infinifolder.storage.StorageRuntime.get(context).archives
        val ref = archives.refForUri(uri) ?: return null
        return when (val result = com.infinifolder.storage.validateSeekableResult(archives.openStreamingSeekable(ref), false)) {
            is ProviderResult.Success -> try {
                com.infinifolder.storage.BlockDeviceSource(result.value, StreamMetadata(provenance = Provenance(source = uri.toString())))
            } catch (error: Throwable) {
                try { result.value.close() } catch (close: Throwable) { error.addSuppressed(close) }
                throw error
            }
            is ProviderResult.Failure -> throw IOException(result.message, result.cause)
            is ProviderResult.Unsupported -> null
        }
    }

    fun readAllBytes(context: Context, uri: Uri): ByteArray {
        return openStream(context, uri).use { it.readBytes() }
    }

    fun getParentDirectory(context: Context, uri: Uri): Uri? {
        val provider = com.infinifolder.storage.StorageRuntime.get(context).provider
        val ref = provider.refForUri(uri) ?: return null
        return (provider.parent(ref) as? ProviderResult.Success)?.value?.documentId?.let(Uri::parse)
    }

    /** Resolve the track's containing directory and any readable cover images through storage providers. */
    fun getAudioDirectoryArtwork(context: Context, sourceUri: Uri): AudioDirectoryArtworkInfo? {
        val provider = com.infinifolder.storage.StorageRuntime.get(context).provider
        val source = provider.refForUri(sourceUri)
        if (source != null) {
            val parent = (provider.parent(source) as? ProviderResult.Success)?.value
            if (parent != null) {
                val directory = (provider.stat(parent) as? ProviderResult.Success)?.value
                val listing = provider.list(parent) as? ProviderResult.Success
                if (directory != null) {
                    val covers = listing?.value.orEmpty().asSequence()
                        .filter { !it.metadata.isDirectory && !it.metadata.isSymbolicLink && !it.metadata.isBrokenLink && it.metadata.canRead }
                        .filter { it.metadata.name.startsWith("cover.", ignoreCase = true) }
                        .filter {
                            it.metadata.mimeType.startsWith("image/", ignoreCase = true) ||
                                com.infinifolder.mime.MimeRegistry.guess(it.metadata.name).startsWith("image/", ignoreCase = true)
                        }
                        .sortedWith(Comparator { left, right ->
                            val byCaseInsensitiveName = left.metadata.name.compareTo(right.metadata.name, ignoreCase = true)
                            if (byCaseInsensitiveName != 0) byCaseInsensitiveName
                            else left.metadata.name.compareTo(right.metadata.name)
                        })
                        .map { it.uri }
                        .toList()
                    return AudioDirectoryArtworkInfo(directory.uri, directory.metadata.name, covers)
                }
            }
        }
        return queryInfiniFolderDirectoryArtwork(context, sourceUri)
    }

    /** Ask InfiniFolder's shared-file provider for a sibling cover when this viewer cannot own its URI. */
    fun queryInfiniFolderSiblingCover(context: Context, sourceUri: Uri): Uri? {
        return queryInfiniFolderDirectoryArtwork(context, sourceUri)?.coverUris?.firstOrNull()
    }

    fun queryInfiniFolderDirectoryArtwork(context: Context, sourceUri: Uri): AudioDirectoryArtworkInfo? {
        if (sourceUri.scheme != "content" || sourceUri.authority?.let {
                it.endsWith(".files", ignoreCase = true) || it.endsWith(".selection", ignoreCase = true)
            } != true) return null
        return try {
            context.contentResolver.query(sourceUri,
                arrayOf(
                    com.infinifolder.storage.SharedFileProvider.AUDIO_DIRECTORY_URI_COLUMN,
                    com.infinifolder.storage.SharedFileProvider.AUDIO_DIRECTORY_NAME_COLUMN,
                    com.infinifolder.storage.SharedFileProvider.AUDIO_COVER_URI_COLUMN
                ), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                fun value(columnName: String): String? {
                    val index = cursor.getColumnIndex(columnName)
                    return if (index < 0 || cursor.isNull(index)) null else cursor.getString(index)
                }
                val cover = value(com.infinifolder.storage.SharedFileProvider.AUDIO_COVER_URI_COLUMN)
                    ?.let(Uri::parse)?.let(::listOf).orEmpty()
                AudioDirectoryArtworkInfo(
                    value(com.infinifolder.storage.SharedFileProvider.AUDIO_DIRECTORY_URI_COLUMN)?.let(Uri::parse),
                    value(com.infinifolder.storage.SharedFileProvider.AUDIO_DIRECTORY_NAME_COLUMN),
                    cover
                )
            }
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: java.io.FileNotFoundException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    data class LoadedText(val text: String, val hasMore: Boolean, val revision: com.infinifolder.storage.ContentRevision?,
        val metadata: StreamMetadata = StreamMetadata(ContentType(DataKind.TEXT, "text/plain", "text", "unicode"),
            finiteness = Finiteness.FINITE, attributes = mapOf("complete" to (!hasMore).toString())))

    fun readDocument(context: Context, uri: Uri, maxBytes: Int = 4 * 1024 * 1024, charset: Charset = Charsets.UTF_8): LoadedText {
        return openSource(context, uri).use { source -> readDocument(source, maxBytes, charset) }
    }

    /** Borrowed source overload shared by Android document loads and contract tests. */
    fun readDocument(source: ByteSource, maxBytes: Int = 4 * 1024 * 1024, charset: Charset = Charsets.UTF_8): LoadedText {
        val document = io.github.gretchycat.infiniscribe.TextDocuments.read(source, maxBytes, charset)
        return LoadedText(document.text, document.hasMore,
            if (document.hasMore) null else com.infinifolder.storage.ContentRevision.of(document.bytes), document.metadata)
    }

    data class LoadedTable(val table: io.github.gretchycat.infiniscribe.TableDocument, val hasMore: Boolean,
        val revision: com.infinifolder.storage.ContentRevision?)

    fun readTable(context: Context, uri: Uri, fileName: String = "", maxBytes: Int = 8 * 1024 * 1024,
        charset: Charset = Charsets.UTF_8): LoadedTable = openSource(context, uri).use { source ->
        readTable(source, fileName, maxBytes, charset)
    }

    /** Parse through Scribe, retaining the exact original bytes for the existing guarded save path. Input is borrowed. */
    fun readTable(source: ByteSource, fileName: String = "", maxBytes: Int = 8 * 1024 * 1024,
        charset: Charset = Charsets.UTF_8): LoadedTable {
        val encoded = io.github.gretchycat.infiniscribe.TextDocuments.read(source, maxBytes, charset)
        val detectedCharset = io.github.gretchycat.infiniscribe.PlainTextIdentification
            .detect(encoded.bytes.copyOf(minOf(encoded.bytes.size, 512)))?.charset ?: charset
        val document = encoded.copy(
            text = String(encoded.bytes, detectedCharset),
            metadata = encoded.metadata.copy(attributes = encoded.metadata.attributes + mapOf(
                "charset" to detectedCharset.name(), "sourceCharset" to detectedCharset.name()))
        )
        return LoadedTable(io.github.gretchycat.infiniscribe.TableDocuments.parse(document, fileName, preserveWhitespace = true), document.hasMore,
            if (document.hasMore) null else com.infinifolder.storage.ContentRevision.of(document.bytes))
    }

    fun readText(context: Context, uri: Uri, maxBytes: Int = 4 * 1024 * 1024, charset: Charset = Charsets.UTF_8): Pair<String, Boolean> {
        openSource(context, uri).use { source ->
            val document = io.github.gretchycat.infiniscribe.TextDocuments.read(source, maxBytes, charset)
            return document.text to document.hasMore
        }
    }

    /**
     * Stages a remote or archive resource into a temporary cache file when platform APIs
     * (e.g. MediaPlayer, VideoView) require a local file descriptor or path.
     * Local files on the filesystem are returned directly without duplicate copies.
     */
    class StagedSource(val file: File, private val lease: java.io.Closeable? = null) : java.io.Closeable {
        override fun close() { lease?.close() }
    }

    fun stage(context: Context, uri: Uri, fileName: String? = null): StagedSource =
        stageInternal(context, uri, fileName, null)

    /** Cancellable staging uses the same storage/cache path as other viewers. */
    fun stageCancellable(context: Context, uri: Uri, fileName: String? = null, cancelled: () -> Boolean): StagedSource =
        stageInternal(context, uri, fileName, null, cancelled)

    /** Native decoders can require a complete local file while imposing their own byte limit. */
    fun stageBounded(context: Context, uri: Uri, maxBytes: Long, fileName: String? = null): StagedSource {
        require(maxBytes > 0)
        return stageInternal(context, uri, fileName, maxBytes)
    }

    private fun stageInternal(context: Context, uri: Uri, fileName: String?, maxBytes: Long?, cancelled: (() -> Boolean)? = null): StagedSource {
        if (cancelled?.invoke() == true) throw java.io.InterruptedIOException("Viewer load cancelled")
        fun checkSize(length: Long?) {
            if (maxBytes != null && length != null && length > maxBytes)
                throw IOException("This viewer supports files up to ${maxBytes / (1024 * 1024)} MiB")
        }
        val cache = com.infinifolder.storage.DiskCache.get(context)
        val local = resolveLocalFile(context, uri)
        if (local != null && local.isFile && local.canRead()) {
            checkSize(local.length())
            return StagedSource(local, cache.pin(local))
        }
        val provider = com.infinifolder.storage.StorageRuntime.get(context).provider
        val metadata = provider.refForUri(uri)?.let { (provider.stat(it) as? ProviderResult.Success)?.value?.metadata }
        checkSize(metadata?.sizeBytes)
        val key = metadata?.takeIf { it.sizeBytes != null && (it.modifiedAtMillis ?: 0) > 0 }
            ?.let { "viewer:$uri:${it.sizeBytes}:${it.modifiedAtMillis}" }
        val lease = cache.stage(fileName ?: metadata?.name ?: "media", metadata?.sizeBytes, key) {
            val input = openStream(context, uri)
            if (maxBytes == null && cancelled == null) input else object : java.io.FilterInputStream(input) {
                private var total = 0L
                override fun read(): Int {
                    val one = ByteArray(1)
                    return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
                }
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    if (Thread.currentThread().isInterrupted || cancelled?.invoke() == true) throw java.io.InterruptedIOException("Viewer load cancelled")
                    val requested = if (maxBytes == null) length else minOf(length.toLong(), maxBytes - total + 1).toInt()
                    val count = super.read(bytes, offset, requested)
                    if (count > 0) { total += count; checkSize(total) }
                    return count
                }
            }
        }
        try { checkSize(lease.file.length()) } catch (error: Throwable) { lease.close(); throw error }
        return StagedSource(lease.file, lease)
    }

    fun isWritable(context: Context, uri: Uri): Boolean {
        val provider = com.infinifolder.storage.StorageRuntime.get(context).provider
        val ref = provider.refForUri(uri) ?: return false
        val capabilities = (provider.capabilities(ref) as? ProviderResult.Success)?.value ?: return false
        return capabilities.mayAttempt(com.infinifolder.model.ResourceCapability.WRITE)
    }

    fun saveContent(context: Context, uri: Uri, data: ByteArray, expectedRevision: com.infinifolder.storage.ContentRevision? = null): ProviderResult<Unit> {
        val provider = com.infinifolder.storage.StorageRuntime.get(context).provider
        val ref = provider.refForUri(uri) ?: return ProviderResult.Failure("No provider owns $uri")
        return com.infinifolder.storage.ContentReplacement.replace(provider, ref, data, File(context.filesDir, "save_recovery"), expectedRevision)
    }

    fun listFolder(context: Context, folderUri: Uri): List<ResourceEntry> {
        val provider = com.infinifolder.storage.StorageRuntime.get(context).provider
        val ref = provider.refForUri(folderUri) ?: throw IOException("No provider owns $folderUri")
        return com.infinifolder.storage.StorageSafety.complete(provider.list(ref))
    }
}
