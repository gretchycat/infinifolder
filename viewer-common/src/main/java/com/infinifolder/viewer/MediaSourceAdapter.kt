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

    fun readAllBytes(context: Context, uri: Uri): ByteArray {
        return openStream(context, uri).use { it.readBytes() }
    }

    fun getParentDirectory(context: Context, uri: Uri): Uri? {
        val provider = com.infinifolder.storage.StorageRuntime.get(context).provider
        val ref = provider.refForUri(uri) ?: return null
        return (provider.parent(ref) as? ProviderResult.Success)?.value?.documentId?.let(Uri::parse)
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
        val document = io.github.gretchycat.infiniscribe.TextDocuments.read(source, maxBytes, charset)
        return LoadedTable(io.github.gretchycat.infiniscribe.TableDocuments.parse(document, fileName), document.hasMore,
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

    fun stage(context: Context, uri: Uri, fileName: String? = null): StagedSource {
        val cache = com.infinifolder.storage.DiskCache.get(context)
        val local = resolveLocalFile(context, uri)
        if (local != null && local.isFile && local.canRead()) return StagedSource(local, cache.pin(local))
        val provider = com.infinifolder.storage.StorageRuntime.get(context).provider
        val metadata = provider.refForUri(uri)?.let { (provider.stat(it) as? ProviderResult.Success)?.value?.metadata }
        val key = metadata?.takeIf { it.sizeBytes != null && (it.modifiedAtMillis ?: 0) > 0 }
            ?.let { "viewer:$uri:${it.sizeBytes}:${it.modifiedAtMillis}" }
        val lease = cache.stage(fileName ?: metadata?.name ?: "media", metadata?.sizeBytes, key) { openStream(context, uri) }
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
