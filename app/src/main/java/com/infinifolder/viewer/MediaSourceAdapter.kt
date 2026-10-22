package com.infinifolder.viewer

import android.content.Context
import android.net.Uri
import com.infinifolder.MainActivity
import com.infinifolder.archive.ArchiveUri
import com.infinifolder.model.ProviderResult
import com.infinifolder.model.ResourceEntry
import com.infinifolder.model.ResourceMetadata
import com.infinifolder.model.ResourceRef
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.util.Locale

/**
 * Universal content accessor that adapts any VFS URI (local, SAF, remote, archive)
 * for internal media players, document viewers, and in-place editors.
 */
object MediaSourceAdapter {

    fun resolveLocalFile(context: Context, uri: Uri): File? {
        if (uri.scheme == "file") {
            val raw = uri.path ?: return null
            val decoded = Uri.decode(raw)
            val f = File(decoded)
            if (f.exists()) return f
            val direct = File(raw)
            if (direct.exists()) return direct
        }
        if (uri.authority?.contains(".files") == true) {
            val raw = uri.path ?: return null
            val clean = Uri.decode(raw)
            val sub = clean
                .removePrefix("/shared/")
                .removePrefix("/shared")
                .removePrefix("/root/")
                .removePrefix("/root")
                .removePrefix("/cache/")
                .removePrefix("/cache")
                .removePrefix("/external_cache/")
                .removePrefix("/external_files/")

            // 1. External storage directory
            val ext = File(android.os.Environment.getExternalStorageDirectory(), sub)
            if (ext.exists()) return ext

            // 2. Absolute filesystem path
            val root = File("/$sub")
            if (root.exists()) return root

            val direct = File(sub)
            if (direct.exists()) return direct

            // 3. Cache directory
            val cache = File(context.cacheDir, sub)
            if (cache.exists()) return cache
        }
        return null
    }

    fun openStream(context: Context, uri: Uri): InputStream {
        val local = resolveLocalFile(context, uri)
        if (local != null && local.isFile && local.canRead()) {
            return local.inputStream()
        }
        val activeProvider = MainActivity.activeProvider
        if (activeProvider != null) {
            val ref = MainActivity.resourceRef(uri)
            val res = activeProvider.openRead(ref)
            if (res is ProviderResult.Success) {
                return res.value
            }
        }
        val archiveProvider = MainActivity.activeArchiveProvider
        if (archiveProvider != null && ArchiveUri.isArchiveUri(uri)) {
            return archiveProvider.openStream(uri)
        }
        if (uri.scheme == "file") {
            val f = File(uri.path ?: "")
            if (f.exists()) return f.inputStream()
        }
        if (uri.scheme == "content") {
            return context.contentResolver.openInputStream(uri)
                ?: throw IOException("Cannot open content stream: $uri")
        }
        throw IOException("Cannot resolve stream for URI: $uri")
    }

    fun readAllBytes(context: Context, uri: Uri): ByteArray {
        return openStream(context, uri).use { it.readBytes() }
    }

    fun getParentDirectory(uri: Uri): Uri? {
        val str = uri.toString()
        val scheme = uri.scheme
        if (scheme == "file") {
            val f = File(uri.path ?: return null).parentFile ?: return null
            return Uri.fromFile(f)
        }
        val lastSlash = str.lastIndexOf('/')
        if (lastSlash > 0 && lastSlash > str.indexOf("://") + 2) {
            return Uri.parse(str.substring(0, lastSlash))
        }
        return null
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
    fun stageToTempFile(
        context: Context,
        uri: Uri,
        fileName: String? = null,
        maxBytes: Long = 500L * 1024L * 1024L,
        onProgress: ((Long, Long) -> Unit)? = null
    ): File {
        val local = resolveLocalFile(context, uri)
        if (local != null && local.isFile && local.canRead()) {
            return local
        }
        val name = fileName ?: uri.lastPathSegment?.substringAfterLast('/') ?: "media_staged"
        val cleanName = name.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
        val cacheDir = File(context.cacheDir, "media_staging").apply {
            mkdirs()
            setReadable(true, false)
            setExecutable(true, false)
        }
        val temp = File(cacheDir, "${System.currentTimeMillis()}_$cleanName")
        temp.deleteOnExit()

        openStream(context, uri).use { input ->
            temp.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                var r: Int
                while (input.read(buf).also { r = it } > 0) {
                    output.write(buf, 0, r)
                    total += r
                    onProgress?.invoke(total, -1L)
                    if (total > maxBytes) {
                        temp.delete()
                        throw IOException("File exceeds maximum staging limit of ${maxBytes / (1024 * 1024)} MB")
                    }
                }
            }
        }
        temp.setReadable(true, false)
        return temp
    }

    fun isWritable(uri: Uri): Boolean {
        val archiveProvider = MainActivity.activeArchiveProvider
        if (archiveProvider != null && ArchiveUri.isArchiveUri(uri)) {
            return archiveProvider.isWritable(uri)
        }
        if (uri.scheme == "file") {
            val f = File(uri.path ?: "")
            return f.canWrite() || (!f.exists() && f.parentFile?.canWrite() == true)
        }
        return true
    }

    fun saveContent(context: Context, uri: Uri, data: ByteArray): ProviderResult<Unit> {
        val activeProvider = MainActivity.activeProvider
        if (activeProvider != null) {
            val ref = MainActivity.resourceRef(uri)
            val res = activeProvider.openWrite(ref)
            if (res is ProviderResult.Success) {
                return try {
                    res.value.use { it.write(data) }
                    ProviderResult.Success(Unit)
                } catch (e: Exception) {
                    ProviderResult.Failure(e.message ?: "Failed to write data", e)
                }
            } else if (res is ProviderResult.Failure) {
                return res
            }
        }
        if (uri.scheme == "file") {
            return try {
                val f = File(uri.path ?: "")
                f.writeBytes(data)
                ProviderResult.Success(Unit)
            } catch (e: Exception) {
                ProviderResult.Failure(e.message ?: "Failed to write local file", e)
            }
        }
        if (uri.scheme == "content") {
            return try {
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(data) }
                    ?: return ProviderResult.Failure("Content provider returned null output stream")
                ProviderResult.Success(Unit)
            } catch (e: Exception) {
                ProviderResult.Failure(e.message ?: "Failed to write to content URI", e)
            }
        }
        return ProviderResult.Failure("No writable provider available for $uri")
    }

    fun listFolder(context: Context, folderUri: Uri): List<ResourceEntry> {
        val localDir = resolveLocalFile(context, folderUri)
        if (localDir != null && localDir.isDirectory) {
            val files = localDir.listFiles() ?: emptyArray()
            val mimeMap = android.webkit.MimeTypeMap.getSingleton()
            val result = mutableListOf<ResourceEntry>()
            for (f in files) {
                val uri = if (folderUri.authority?.contains(".files") == true) {
                    val rootPath = android.os.Environment.getExternalStorageDirectory().absolutePath
                    if (f.absolutePath.startsWith(rootPath)) {
                        val sub = f.absolutePath.removePrefix(rootPath).trimStart('/')
                        Uri.parse("content://${context.packageName}.files/shared/$sub")
                    } else {
                        Uri.parse("content://${context.packageName}.files/root${f.absolutePath}")
                    }
                } else {
                    Uri.fromFile(f)
                }
                val ext = f.extension.lowercase(Locale.ROOT)
                val mime = mimeMap?.getMimeTypeFromExtension(ext) ?: "application/octet-stream"
                result.add(
                    ResourceEntry(
                        ref = ResourceRef(uri.toString(), uri.toString()),
                        metadata = ResourceMetadata(f.name, mime, f.length(), f.lastModified(), f.isDirectory),
                        uri = uri
                    )
                )
            }
            if (result.isNotEmpty()) return result
        }

        val activeProvider = MainActivity.activeProvider
        if (activeProvider != null) {
            val ref = MainActivity.resourceRef(folderUri)
            val res = activeProvider.list(ref)
            if (res is ProviderResult.Success && res.value.isNotEmpty()) {
                return res.value
            }
        }
        if (folderUri.scheme == "file") {
            val dir = File(folderUri.path ?: "")
            if (dir.exists() && dir.isDirectory) {
                val files = dir.listFiles() ?: emptyArray()
                val mimeMap = android.webkit.MimeTypeMap.getSingleton()
                return files.map { f ->
                    val uri = Uri.fromFile(f)
                    val ext = f.extension.lowercase(Locale.ROOT)
                    val mime = mimeMap?.getMimeTypeFromExtension(ext) ?: "application/octet-stream"
                    ResourceEntry(
                        ref = ResourceRef(uri.toString(), uri.toString()),
                        metadata = ResourceMetadata(f.name, mime, f.length(), f.lastModified(), f.isDirectory),
                        uri = uri
                    )
                }
            }
        }
        return emptyList()
    }
}
