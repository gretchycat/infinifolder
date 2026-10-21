package com.infinifolder.sftp

import android.content.Context
import android.net.Uri
import com.infinifolder.model.*
import com.infinifolder.storage.ProviderConnection
import com.infinifolder.storage.StorageProvider
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpATTRS
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Vector

class SftpStorageProvider(
    private val context: Context,
    val credentialStore: SftpCredentialStore = SftpCredentialStore(context),
    val clientPool: SftpClientPool = SftpClientPool(),
    private val connectionLookup: (String) -> ProviderConnection?
) : StorageProvider {

    override val id = "sftp"

    private fun resolveConfigAndCredentials(connectionId: String): Pair<SftpConfig, SftpCredentials> {
        val conn = connectionLookup(connectionId)
            ?: throw IOException("Saved SFTP connection not found: $connectionId")
        val creds = credentialStore.load(connectionId)
            ?: throw IOException("Credentials not found for SFTP connection: ${conn.displayName}")

        val endpoint = conn.endpoint ?: "localhost:22"
        val host = conn.options["host"]?.ifEmpty { null }
            ?: (if (endpoint.contains(':')) endpoint.substringBefore(':').ifEmpty { "localhost" } else endpoint.ifEmpty { "localhost" })
        val port = conn.options["port"]?.toIntOrNull()
            ?: (if (endpoint.contains(':')) endpoint.substringAfter(':').toIntOrNull() else null) ?: 22
        val username = conn.options["username"] ?: "root"
        val initialPath = conn.options["initialPath"] ?: "/"

        val config = SftpConfig(
            id = conn.id,
            displayName = conn.displayName,
            host = host,
            port = port,
            username = username,
            initialPath = initialPath
        )
        return config to creds
    }

    override fun list(parent: ResourceRef): ProviderResult<List<ResourceEntry>> = try {
        val uri = Uri.parse(parent.documentId)
        val connId = SftpUri.getConnectionId(uri)
        val remotePath = SftpUri.getRemotePath(uri)
        val (config, creds) = resolveConfigAndCredentials(connId)

        val entries = clientPool.withChannel(config, creds) { channel ->
            val list = mutableListOf<ResourceEntry>()
            @Suppress("UNCHECKED_CAST")
            val vector = channel.ls(remotePath) as Vector<ChannelSftp.LsEntry>

            for (item in vector) {
                val filename = item.filename
                if (filename == "." || filename == "..") continue

                val attrs: SftpATTRS? = item.attrs
                val isDir = attrs?.isDir ?: false
                val sizeBytes = if (isDir) null else attrs?.size
                val mtime = (attrs?.mTime?.toLong() ?: 0L) * 1000L
                val childRemotePath = if (remotePath == "/") "/$filename" else "$remotePath/$filename"
                val childUri = SftpUri.build(connId, childRemotePath)
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
        ProviderResult.Failure(e.message ?: "Failed to list remote directory", e)
    }

    fun getMetadata(uri: Uri): Pair<Long?, Long?>? = try {
        val connId = SftpUri.getConnectionId(uri)
        val remotePath = SftpUri.getRemotePath(uri)
        val (config, creds) = resolveConfigAndCredentials(connId)
        clientPool.withChannel(config, creds) { channel ->
            val attrs = channel.stat(remotePath)
            val size = if (attrs.isDir) null else attrs.size
            val mtime = attrs.mTime.toLong() * 1000L
            Pair(size, mtime)
        }
    } catch (_: Throwable) {
        null
    }

    override fun openRead(ref: ResourceRef): ProviderResult<InputStream> = try {
        val uri = Uri.parse(ref.documentId)
        val connId = SftpUri.getConnectionId(uri)
        val remotePath = SftpUri.getRemotePath(uri)
        val (config, creds) = resolveConfigAndCredentials(connId)
        val stream = clientPool.openStream(config, creds, remotePath)
        ProviderResult.Success(stream)
    } catch (e: Throwable) {
        ProviderResult.Failure(e.message ?: "Failed to read remote file", e)
    }

    override fun openWrite(ref: ResourceRef): ProviderResult<OutputStream> = try {
        val uri = Uri.parse(ref.documentId)
        val connId = SftpUri.getConnectionId(uri)
        val remotePath = SftpUri.getRemotePath(uri)
        val (config, creds) = resolveConfigAndCredentials(connId)
        val stream = clientPool.openWriteStream(config, creds, remotePath)
        ProviderResult.Success(stream)
    } catch (e: Throwable) {
        ProviderResult.Failure(e.message ?: "Failed to write remote file", e)
    }

    override fun createDirectory(parent: ResourceRef, name: String): ProviderResult<ResourceEntry> = try {
        val uri = Uri.parse(parent.documentId)
        val connId = SftpUri.getConnectionId(uri)
        val remoteParent = SftpUri.getRemotePath(uri)
        val newRemotePath = if (remoteParent == "/") "/$name" else "$remoteParent/$name"
        val (config, creds) = resolveConfigAndCredentials(connId)

        clientPool.withChannel(config, creds) { channel ->
            channel.mkdir(newRemotePath)
        }

        val childUri = SftpUri.build(connId, newRemotePath)
        ProviderResult.Success(
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
        )
    } catch (e: Exception) {
        ProviderResult.Failure(e.message ?: "Failed to create remote directory", e)
    }

    override fun createFile(parent: ResourceRef, name: String, mimeType: String): ProviderResult<ResourceEntry> = try {
        val uri = Uri.parse(parent.documentId)
        val connId = SftpUri.getConnectionId(uri)
        val remoteParent = SftpUri.getRemotePath(uri)
        val newRemotePath = if (remoteParent == "/") "/$name" else "$remoteParent/$name"
        val (config, creds) = resolveConfigAndCredentials(connId)

        clientPool.withChannel(config, creds) { channel ->
            channel.put(ByteArrayInputStream(ByteArray(0)), newRemotePath, ChannelSftp.OVERWRITE)
        }

        val childUri = SftpUri.build(connId, newRemotePath)
        ProviderResult.Success(
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
        )
    } catch (e: Exception) {
        ProviderResult.Failure(e.message ?: "Failed to create remote file", e)
    }

    override fun rename(ref: ResourceRef, name: String): ProviderResult<ResourceEntry> = try {
        val uri = Uri.parse(ref.documentId)
        val connId = SftpUri.getConnectionId(uri)
        val oldPath = SftpUri.getRemotePath(uri)
        val parentPath = oldPath.substringBeforeLast('/', "").ifEmpty { "/" }
        val newPath = if (parentPath == "/") "/$name" else "$parentPath/$name"
        val (config, creds) = resolveConfigAndCredentials(connId)

        clientPool.withChannel(config, creds) { channel ->
            channel.rename(oldPath, newPath)
            val stat = channel.stat(newPath)
            val isDir = stat.isDir
            val newUri = SftpUri.build(connId, newPath)
            ResourceEntry(
                ref = ResourceRef(id, newUri.toString()),
                metadata = ResourceMetadata(
                    name = name,
                    mimeType = if (isDir) "vnd.android.document/directory" else guessMimeType(name),
                    sizeBytes = if (isDir) null else stat.size,
                    modifiedAtMillis = stat.mTime.toLong() * 1000L,
                    isDirectory = isDir
                ),
                uri = newUri
            )
        }.let { ProviderResult.Success(it) }
    } catch (e: Exception) {
        ProviderResult.Failure(e.message ?: "Failed to rename remote item", e)
    }

    override fun delete(ref: ResourceRef): ProviderResult<Unit> = try {
        val uri = Uri.parse(ref.documentId)
        val connId = SftpUri.getConnectionId(uri)
        val remotePath = SftpUri.getRemotePath(uri)
        val (config, creds) = resolveConfigAndCredentials(connId)

        clientPool.withChannel(config, creds) { channel ->
            val stat = channel.stat(remotePath)
            if (stat.isDir) {
                deleteDirRecursive(channel, remotePath)
            } else {
                channel.rm(remotePath)
            }
        }
        ProviderResult.Success(Unit)
    } catch (e: Exception) {
        ProviderResult.Failure(e.message ?: "Failed to delete remote item", e)
    }

    private fun deleteDirRecursive(channel: ChannelSftp, path: String) {
        @Suppress("UNCHECKED_CAST")
        val vector = channel.ls(path) as Vector<ChannelSftp.LsEntry>
        for (item in vector) {
            val filename = item.filename
            if (filename == "." || filename == "..") continue
            val childPath = if (path == "/") "/$filename" else "$path/$filename"
            if (item.attrs.isDir) {
                deleteDirRecursive(channel, childPath)
            } else {
                channel.rm(childPath)
            }
        }
        channel.rmdir(path)
    }

    override fun copy(source: ResourceRef, destination: ResourceRef): ProviderResult<ResourceRef> = try {
        val srcUri = Uri.parse(source.documentId)
        val dstUri = Uri.parse(destination.documentId)
        val srcConnId = SftpUri.getConnectionId(srcUri)
        val dstConnId = SftpUri.getConnectionId(dstUri)

        if (srcConnId != dstConnId) {
            ProviderResult.Unsupported(ResourceCapability.COPY)
        } else {
            val srcPath = SftpUri.getRemotePath(srcUri)
            val dstPath = SftpUri.getRemotePath(dstUri)
            val (config, creds) = resolveConfigAndCredentials(srcConnId)

            clientPool.withChannel(config, creds) { channel ->
                val stat = channel.stat(srcPath)
                if (stat.isDir) {
                    throw IOException("Directory copying over SFTP is not directly supported")
                }
            }
            val srcStream = clientPool.openStream(config, creds, srcPath)
            val dstStream = clientPool.openWriteStream(config, creds, dstPath)
            srcStream.use { input ->
                dstStream.use { output ->
                    input.copyTo(output, bufferSize = 128 * 1024)
                    output.flush()
                }
            }
            ProviderResult.Success(destination)
        }
    } catch (e: Exception) {
        ProviderResult.Failure(e.message ?: "Failed to copy remote item", e)
    }

    override fun move(source: ResourceRef, destination: ResourceRef): ProviderResult<ResourceRef> = try {
        val srcUri = Uri.parse(source.documentId)
        val dstUri = Uri.parse(destination.documentId)
        val srcConnId = SftpUri.getConnectionId(srcUri)
        val dstConnId = SftpUri.getConnectionId(dstUri)

        if (srcConnId != dstConnId) {
            ProviderResult.Unsupported(ResourceCapability.MOVE)
        } else {
            val srcPath = SftpUri.getRemotePath(srcUri)
            val dstPath = SftpUri.getRemotePath(dstUri)
            val (config, creds) = resolveConfigAndCredentials(srcConnId)

            clientPool.withChannel(config, creds) { channel ->
                channel.rename(srcPath, dstPath)
            }
            ProviderResult.Success(destination)
        }
    } catch (e: Exception) {
        ProviderResult.Failure(e.message ?: "Failed to move remote item", e)
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
            "tar", "gz", "bz2", "xz", "7z" -> "application/x-archive"
            "apk" -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }
}
