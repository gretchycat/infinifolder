package com.infinifolder.ftp

import android.content.Context
import android.net.Uri
import com.infinifolder.model.*
import com.infinifolder.storage.ProviderConnection
import com.infinifolder.storage.StorageProvider
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class FtpStorageProvider(
    private val context: Context,
    val credentialStore: FtpCredentialStore = FtpCredentialStore(context),
    val clientPool: FtpClientPool = FtpClientPool(),
    private val connectionLookup: (String) -> ProviderConnection?
) : StorageProvider {

    override val id = "ftp"

    private fun resolveConfigAndCredentials(connectionId: String): Pair<FtpConfig, FtpCredentials> {
        val conn = connectionLookup(connectionId)
            ?: throw IOException("Saved FTP connection not found: $connectionId")
        val creds = credentialStore.load(connectionId) ?: FtpCredentials()

        val endpoint = conn.endpoint ?: "localhost:21"
        val host = conn.options["host"]?.ifEmpty { null }
            ?: (if (endpoint.contains(':')) endpoint.substringBefore(':').ifEmpty { "localhost" } else endpoint.ifEmpty { "localhost" })
        val port = conn.options["port"]?.toIntOrNull()
            ?: (if (endpoint.contains(':')) endpoint.substringAfter(':').toIntOrNull() else null) ?: 21
        val username = conn.options["username"] ?: "anonymous"
        val isAnonymous = conn.options["isAnonymous"]?.toBooleanStrictOrNull() ?: (username == "anonymous")
        val initialPath = conn.options["initialPath"] ?: "/"
        val useFtps = conn.options["useFtps"]?.toBooleanStrictOrNull() ?: false
        val passiveMode = conn.options["passiveMode"]?.toBooleanStrictOrNull() ?: true
        val encoding = conn.options["encoding"] ?: "UTF-8"

        val config = FtpConfig(
            id = conn.id,
            displayName = conn.displayName,
            host = host,
            port = port,
            username = username,
            isAnonymous = isAnonymous,
            initialPath = initialPath,
            useFtps = useFtps,
            passiveMode = passiveMode,
            encoding = encoding
        )
        return config to creds
    }

    override fun list(parent: ResourceRef): ProviderResult<List<ResourceEntry>> = try {
        val uri = Uri.parse(parent.documentId)
        val connId = FtpUri.getConnectionId(uri)
        val remotePath = FtpUri.getRemotePath(uri)
        val (config, creds) = resolveConfigAndCredentials(connId)

        val entries = clientPool.withClient(config, creds) { client ->
            val list = mutableListOf<ResourceEntry>()
            val files: Array<FTPFile> = client.listFiles(remotePath) ?: emptyArray()

            for (item in files) {
                var filename = item.name ?: continue
                if (filename.contains('/')) {
                    filename = filename.substringAfterLast('/')
                }
                if (filename == "." || filename == ".." || filename.isEmpty()) continue

                val isDir = item.isDirectory
                val sizeBytes = if (isDir) null else item.size
                val mtime = item.timestamp?.timeInMillis ?: 0L
                val childRemotePath = if (remotePath == "/") "/$filename" else "$remotePath/$filename"
                val childUri = FtpUri.build(connId, childRemotePath)
                val mimeType = if (isDir) "vnd.android.document/directory" else guessMimeType(filename)

                list += ResourceEntry(
                    ref = ResourceRef(id, childUri.toString()),
                    metadata = ResourceMetadata(
                        name = filename,
                        mimeType = mimeType,
                        sizeBytes = sizeBytes,
                        modifiedAtMillis = mtime,
                        isDirectory = isDir
                    ),
                    uri = childUri
                )
            }
            list.sortedWith(compareBy({ !it.metadata.isDirectory }, { it.metadata.name.lowercase() }))
        }
        ProviderResult.Success(entries)
    } catch (e: Throwable) {
        ProviderResult.Failure(e.message ?: "Failed to list FTP remote directory", e)
    }

    fun getMetadata(uri: Uri): Pair<Long?, Long?>? = try {
        val connId = FtpUri.getConnectionId(uri)
        val remotePath = FtpUri.getRemotePath(uri)
        val (config, creds) = resolveConfigAndCredentials(connId)
        clientPool.withClient(config, creds) { client ->
            val files = client.listFiles(remotePath)
            val file = files?.firstOrNull()
            if (file != null) {
                val size = if (file.isDirectory) null else file.size
                val mtime = file.timestamp?.timeInMillis
                size to mtime
            } else {
                null
            }
        }
    } catch (_: Throwable) {
        null
    }

    override fun openRead(ref: ResourceRef): ProviderResult<InputStream> = try {
        val uri = Uri.parse(ref.documentId)
        val connId = FtpUri.getConnectionId(uri)
        val remotePath = FtpUri.getRemotePath(uri)
        val (config, creds) = resolveConfigAndCredentials(connId)

        // Use a dedicated client connection for this stream so it doesn't block command pool
        val client = FtpClientPool().getClient(config, creds)
        val stream = client.retrieveFileStream(remotePath)
            ?: throw IOException("Failed to retrieve file stream: ${client.safeReplyString()}")

        val wrapped = object : FilterInputStream(stream) {
            private var closed = false
            override fun close() {
                if (!closed) {
                    closed = true
                    try { super.close() } finally {
                        try { client.completePendingCommand() } catch (_: Throwable) {}
                        try { client.disconnect() } catch (_: Throwable) {}
                    }
                }
            }
        }
        ProviderResult.Success(wrapped)
    } catch (e: Throwable) {
        ProviderResult.Failure(e.message ?: "Failed to open FTP file for reading", e)
    }

    override fun openWrite(ref: ResourceRef): ProviderResult<OutputStream> = try {
        val uri = Uri.parse(ref.documentId)
        val connId = FtpUri.getConnectionId(uri)
        val remotePath = FtpUri.getRemotePath(uri)
        val (config, creds) = resolveConfigAndCredentials(connId)

        val client = FtpClientPool().getClient(config, creds)
        val stream = client.storeFileStream(remotePath)
            ?: throw IOException("Failed to store file stream: ${client.safeReplyString()}")

        val wrapped = object : FilterOutputStream(stream) {
            private var closed = false
            override fun close() {
                if (!closed) {
                    closed = true
                    try { super.close() } finally {
                        try { client.completePendingCommand() } catch (_: Throwable) {}
                        try { client.disconnect() } catch (_: Throwable) {}
                    }
                }
            }
        }
        ProviderResult.Success(wrapped)
    } catch (e: Throwable) {
        ProviderResult.Failure(e.message ?: "Failed to open FTP file for writing", e)
    }

    override fun createDirectory(parent: ResourceRef, name: String): ProviderResult<ResourceEntry> = try {
        val uri = Uri.parse(parent.documentId)
        val connId = FtpUri.getConnectionId(uri)
        val remotePath = FtpUri.getRemotePath(uri)
        val (config, creds) = resolveConfigAndCredentials(connId)

        val childRemotePath = if (remotePath == "/") "/$name" else "$remotePath/$name"
        val childUri = FtpUri.build(connId, childRemotePath)

        clientPool.withClient(config, creds) { client ->
            val ok = client.makeDirectory(childRemotePath)
            if (!ok) throw IOException("FTP server failed to create directory: ${client.safeReplyString()}")
            ResourceEntry(
                ref = ResourceRef(id, childUri.toString()),
                metadata = ResourceMetadata(
                    name = name,
                    mimeType = "vnd.android.document/directory",
                    sizeBytes = null,
                    modifiedAtMillis = System.currentTimeMillis(),
                    isDirectory = true
                ),
                uri = childUri
            )
        }.let { ProviderResult.Success(it) }
    } catch (e: Throwable) {
        ProviderResult.Failure(e.message ?: "Failed to create remote directory", e)
    }

    override fun createFile(parent: ResourceRef, name: String, mimeType: String): ProviderResult<ResourceEntry> = try {
        val uri = Uri.parse(parent.documentId)
        val connId = FtpUri.getConnectionId(uri)
        val remotePath = FtpUri.getRemotePath(uri)
        val (config, creds) = resolveConfigAndCredentials(connId)

        val childRemotePath = if (remotePath == "/") "/$name" else "$remotePath/$name"
        val childUri = FtpUri.build(connId, childRemotePath)

        clientPool.withClient(config, creds) { client ->
            val emptyStream = java.io.ByteArrayInputStream(ByteArray(0))
            val ok = client.storeFile(childRemotePath, emptyStream)
            if (!ok) throw IOException("FTP server failed to create file: ${client.safeReplyString()}")
            ResourceEntry(
                ref = ResourceRef(id, childUri.toString()),
                metadata = ResourceMetadata(
                    name = name,
                    mimeType = mimeType.ifEmpty { guessMimeType(name) },
                    sizeBytes = 0L,
                    modifiedAtMillis = System.currentTimeMillis(),
                    isDirectory = false
                ),
                uri = childUri
            )
        }.let { ProviderResult.Success(it) }
    } catch (e: Throwable) {
        ProviderResult.Failure(e.message ?: "Failed to create remote file", e)
    }

    override fun rename(ref: ResourceRef, name: String): ProviderResult<ResourceEntry> = try {
        val uri = Uri.parse(ref.documentId)
        val connId = FtpUri.getConnectionId(uri)
        val oldPath = FtpUri.getRemotePath(uri)
        val (config, creds) = resolveConfigAndCredentials(connId)

        val parentPath = oldPath.substringBeforeLast('/', "").ifEmpty { "/" }
        val newPath = if (parentPath == "/") "/$name" else "$parentPath/$name"
        val newUri = FtpUri.build(connId, newPath)

        clientPool.withClient(config, creds) { client ->
            val ok = client.rename(oldPath, newPath)
            if (!ok) throw IOException("FTP server failed to rename: ${client.safeReplyString()}")
            val files = client.listFiles(newPath)
            val stat = files?.firstOrNull()
            val isDir = stat?.isDirectory ?: false
            ResourceEntry(
                ref = ResourceRef(id, newUri.toString()),
                metadata = ResourceMetadata(
                    name = name,
                    mimeType = if (isDir) "vnd.android.document/directory" else guessMimeType(name),
                    sizeBytes = if (isDir) null else stat?.size,
                    modifiedAtMillis = stat?.timestamp?.timeInMillis ?: System.currentTimeMillis(),
                    isDirectory = isDir
                ),
                uri = newUri
            )
        }.let { ProviderResult.Success(it) }
    } catch (e: Throwable) {
        ProviderResult.Failure(e.message ?: "Failed to rename FTP item", e)
    }

    override fun delete(ref: ResourceRef): ProviderResult<Unit> = try {
        val uri = Uri.parse(ref.documentId)
        val connId = FtpUri.getConnectionId(uri)
        val remotePath = FtpUri.getRemotePath(uri)
        val (config, creds) = resolveConfigAndCredentials(connId)

        clientPool.withClient(config, creds) { client ->
            // Try deleting as file first; if failed, try removing directory
            var ok = client.deleteFile(remotePath)
            if (!ok) {
                ok = client.removeDirectory(remotePath)
            }
            if (!ok) {
                throw IOException("FTP server failed to delete '$remotePath': ${client.safeReplyString()}")
            }
            ProviderResult.Success(Unit)
        }
    } catch (e: Throwable) {
        ProviderResult.Failure(e.message ?: "Failed to delete FTP item", e)
    }

    override fun copy(source: ResourceRef, destination: ResourceRef): ProviderResult<ResourceRef> {
        return ProviderResult.Unsupported(ResourceCapability.COPY)
    }

    override fun move(source: ResourceRef, destination: ResourceRef): ProviderResult<ResourceRef> = try {
        val srcUri = Uri.parse(source.documentId)
        val destUri = Uri.parse(destination.documentId)
        val srcConnId = FtpUri.getConnectionId(srcUri)
        val destConnId = FtpUri.getConnectionId(destUri)

        if (srcConnId != destConnId) {
            ProviderResult.Unsupported(ResourceCapability.MOVE)
        } else {
            val srcPath = FtpUri.getRemotePath(srcUri)
            val destDir = FtpUri.getRemotePath(destUri)
            val fileName = srcPath.substringAfterLast('/')
            val destPath = if (destDir == "/") "/$fileName" else "$destDir/$fileName"
            val (config, creds) = resolveConfigAndCredentials(srcConnId)

            clientPool.withClient(config, creds) { client ->
                val ok = client.rename(srcPath, destPath)
                if (!ok) throw IOException("FTP move failed: ${client.safeReplyString()}")
                val newUri = FtpUri.build(srcConnId, destPath)
                ProviderResult.Success(ResourceRef(id, newUri.toString()))
            }
        }
    } catch (e: Throwable) {
        ProviderResult.Failure(e.message ?: "Failed to move FTP item", e)
    }

    private fun guessMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "txt", "log", "md", "cfg", "conf", "ini", "sh", "py", "json", "xml", "yaml", "yml" -> "text/plain"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "tar", "gz", "xz", "bz2" -> "application/x-compressed"
            "apk" -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }
}
