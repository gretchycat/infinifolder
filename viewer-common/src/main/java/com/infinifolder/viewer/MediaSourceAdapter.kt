package com.infinifolder.viewer

import android.content.Context
import android.net.Uri
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
        val local = com.infinifolder.storage.LocalStorageProvider(context.applicationContext)
        return if (local.owns(uri)) runCatching { local.fileFor(uri) }.getOrNull() else null
    }

    fun openStream(context: Context, uri: Uri): InputStream {
        val provider = com.infinifolder.storage.StorageRuntime.get(context).provider
        val ref = provider?.refForUri(uri)
        if (provider != null && ref != null) {
            return when (val result = provider.openRead(ref)) {
                is ProviderResult.Success -> result.value
                is ProviderResult.Failure -> throw IOException(result.message, result.cause)
                is ProviderResult.Unsupported -> throw IOException("Reading this resource is unsupported")
            }
        }
        if (uri.scheme == "content") return context.contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot open content stream: $uri")
        if (uri.scheme == "file") return File(uri.path ?: throw IOException("Missing path")).inputStream()
        throw IOException("No provider owns $uri")
    }

    fun readAllBytes(context: Context, uri: Uri): ByteArray {
        return openStream(context, uri).use { it.readBytes() }
    }

    fun getParentDirectory(context: Context, uri: Uri): Uri? {
        val provider = com.infinifolder.storage.StorageRuntime.get(context).provider
        val ref = provider.refForUri(uri) ?: return null
        return (provider.parent(ref) as? ProviderResult.Success)?.value?.documentId?.let(Uri::parse)
    }

    data class LoadedText(val text: String, val hasMore: Boolean, val revision: com.infinifolder.storage.ContentRevision?)

    fun readDocument(context: Context, uri: Uri, maxBytes: Int = 4 * 1024 * 1024, charset: Charset = Charsets.UTF_8): LoadedText {
        openStream(context, uri).use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (output.size() < maxBytes) {
                val n = input.read(buffer, 0, minOf(buffer.size, maxBytes - output.size()))
                if (n < 0) break
                output.write(buffer, 0, n)
            }
            val bytes = output.toByteArray()
            val hasMore = input.read() != -1
            return LoadedText(String(bytes, charset), hasMore,
                if (hasMore) null else com.infinifolder.storage.ContentRevision.of(bytes))
        }
    }

    fun readText(context: Context, uri: Uri, maxBytes: Int = 4 * 1024 * 1024, charset: Charset = Charsets.UTF_8): Pair<String, Boolean> {
        val input = openStream(context, uri)
        input.use { s ->
            val buf = ByteArray(maxBytes)
            var total = 0
            while (total < maxBytes) {
                val r = s.read(buf, total, maxBytes - total)
                if (r <= 0) break
                total += r
            }
            val hasMore = s.read() != -1
            val text = String(buf, 0, total, charset)
            return text to hasMore
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
