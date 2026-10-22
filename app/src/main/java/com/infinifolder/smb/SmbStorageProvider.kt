package com.infinifolder.smb

import android.content.Context
import android.net.Uri
import com.infinifolder.model.*
import com.infinifolder.storage.ProviderConnection
import com.infinifolder.storage.StorageProvider
import jcifs.smb.SmbFile
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class SmbStorageProvider(
    private val context: Context,
    val credentialStore: SmbCredentialStore = SmbCredentialStore(context),
    val clientPool: SmbClientPool = SmbClientPool(),
    private val connectionLookup: (String) -> ProviderConnection?
) : StorageProvider {

    override val id = "smb"

    fun resolveConfigAndCredentials(connectionId: String): Pair<SmbConfig, SmbCredentials> {
        val conn = connectionLookup(connectionId)
            ?: throw IOException("Saved SMB connection not found: $connectionId")
        val creds = credentialStore.load(connectionId) ?: SmbCredentials()

        val endpoint = conn.endpoint ?: "localhost:445"
        val host = conn.options["host"]?.ifEmpty { null }
            ?: (if (endpoint.contains(':')) endpoint.substringBefore(':').ifEmpty { "localhost" } else endpoint.ifEmpty { "localhost" })
        val port = conn.options["port"]?.toIntOrNull()
            ?: (if (endpoint.contains(':')) endpoint.substringAfter(':').toIntOrNull() else null) ?: 445
        val domain = conn.options["domain"] ?: "WORKGROUP"
        val username = conn.options["username"] ?: ""
        val isGuest = conn.options["isGuest"]?.toBooleanStrictOrNull() ?: false
        val initialShare = conn.options["initialShare"]?.ifEmpty { null }
        val initialPath = conn.options["initialPath"] ?: "/"

        val config = SmbConfig(
            id = conn.id,
            displayName = conn.displayName,
            host = host,
            port = port,
            domain = domain,
            username = username,
            isGuest = isGuest,
            initialShare = initialShare,
            initialPath = initialPath
        )
        return config to creds
    }

    override fun list(parent: ResourceRef): ProviderResult<List<ResourceEntry>> {
        var remotePath = "/"
        return try {
            val uri = Uri.parse(parent.documentId)
            val connId = SmbUri.getConnectionId(uri)
            remotePath = SmbUri.getRemotePath(uri)
            val (config, creds) = resolveConfigAndCredentials(connId)

            if (remotePath == "/" && !config.initialShare.isNullOrBlank()) {
                remotePath = "/${config.initialShare.trim('/')}"
            }

            val smbFile = clientPool.getSmbFile(config, creds, remotePath, isDirectory = true)
            val files = smbFile.listFiles() ?: emptyArray()
            val list = mutableListOf<ResourceEntry>()

            for (item in files) {
                var filename = item.name.trimEnd('/')
                if (filename.contains('/')) {
                    filename = filename.substringAfterLast('/')
                }
                if (filename.isEmpty() || filename == "." || filename == "..") continue
                // When at server root, hide IPC$ or administrative pipe shares by default
                if ((remotePath == "/" || remotePath.isEmpty()) && filename.equals("IPC$", ignoreCase = true)) continue

                val isDir = item.isDirectory
                val sizeBytes = if (isDir) null else try { item.length() } catch (_: Throwable) { null }
                val mtime = try { item.lastModified() } catch (_: Throwable) { 0L }
                val normalizedParent = remotePath.trimEnd('/')
                val childRemotePath = if (normalizedParent.isEmpty() || normalizedParent == "/") "/$filename" else "$normalizedParent/$filename"
                val childUri = SmbUri.build(connId, childRemotePath)
                val mimeType = if (isDir) "vnd.android.document/directory" else guessMimeType(filename)

                val canRead = try { item.canRead() } catch (_: Throwable) { true }
                val canWrite = try { item.canWrite() } catch (_: Throwable) { true }

                list += ResourceEntry(
                    ref = ResourceRef(id, childUri.toString()),
                    metadata = ResourceMetadata(
                        name = filename,
                        mimeType = mimeType,
                        sizeBytes = sizeBytes,
                        modifiedAtMillis = mtime,
                        isDirectory = isDir,
                        canRead = canRead,
                        canWrite = canWrite
                    ),
                    uri = childUri
                )
            }
        list.sortedWith(compareBy({ !it.metadata.isDirectory }, { it.metadata.name.lowercase() }))
        ProviderResult.Success(list)
    } catch (e: Throwable) {
        val msg = e.message ?: ""
        val errorText = if (remotePath == "/" && (msg.contains("0xC000020C") || msg.contains("0xC0000022") || msg.contains("denied", ignoreCase = true) || msg.contains("refused", ignoreCase = true))) {
            "This server restricts public share listing ($msg). Please long-press this server on Home, tap 'Edit connection', and specify a Share name."
        } else {
            msg.ifEmpty { "Failed to list SMB shares or folders" }
        }
        ProviderResult.Failure(errorText, e)
    }
}

    fun getMetadata(uri: Uri): Pair<Long?, Long?>? = try {
        val connId = SmbUri.getConnectionId(uri)
        val remotePath = SmbUri.getRemotePath(uri)
        val (config, creds) = resolveConfigAndCredentials(connId)
        val smbFile = clientPool.getSmbFile(config, creds, remotePath)
        if (smbFile.exists()) {
            val size = if (smbFile.isDirectory) null else smbFile.length()
            val mtime = smbFile.lastModified()
            size to mtime
        } else {
            val dirFile = clientPool.getSmbFile(config, creds, remotePath, isDirectory = true)
            if (dirFile.exists()) {
                val size = if (dirFile.isDirectory) null else dirFile.length()
                val mtime = dirFile.lastModified()
                size to mtime
            } else null
        }
    } catch (_: Throwable) {
        null
    }

    override fun openRead(ref: ResourceRef): ProviderResult<InputStream> = try {
        val uri = Uri.parse(ref.documentId)
        val connId = SmbUri.getConnectionId(uri)
        val remotePath = SmbUri.getRemotePath(uri)
        val (config, creds) = resolveConfigAndCredentials(connId)

        val smbFile = clientPool.getSmbFile(config, creds, remotePath)
        val stream = smbFile.openInputStream()
        ProviderResult.Success(stream)
    } catch (e: Throwable) {
        ProviderResult.Failure(e.message ?: "Failed to open SMB file for reading", e)
    }

    override fun openWrite(ref: ResourceRef): ProviderResult<OutputStream> = try {
        val uri = Uri.parse(ref.documentId)
        val connId = SmbUri.getConnectionId(uri)
        val remotePath = SmbUri.getRemotePath(uri)
        val (config, creds) = resolveConfigAndCredentials(connId)

        val smbFile = clientPool.getSmbFile(config, creds, remotePath)
        val stream = smbFile.openOutputStream()
        ProviderResult.Success(stream)
    } catch (e: Throwable) {
        ProviderResult.Failure(e.message ?: "Failed to open SMB file for writing", e)
    }

    override fun createDirectory(parent: ResourceRef, name: String): ProviderResult<ResourceEntry> = try {
        val uri = Uri.parse(parent.documentId)
        val connId = SmbUri.getConnectionId(uri)
        val remotePath = SmbUri.getRemotePath(uri)
        val (config, creds) = resolveConfigAndCredentials(connId)

        val childRemotePath = if (remotePath == "/") "/$name" else "$remotePath/$name"
        val childUri = SmbUri.build(connId, childRemotePath)
        val smbFile = clientPool.getSmbFile(config, creds, childRemotePath, isDirectory = true)
        smbFile.mkdir()

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
    } catch (e: Throwable) {
        ProviderResult.Failure(e.message ?: "Failed to create SMB directory", e)
    }

    override fun createFile(parent: ResourceRef, name: String, mimeType: String): ProviderResult<ResourceEntry> = try {
        val uri = Uri.parse(parent.documentId)
        val connId = SmbUri.getConnectionId(uri)
        val remotePath = SmbUri.getRemotePath(uri)
        val (config, creds) = resolveConfigAndCredentials(connId)

        val childRemotePath = if (remotePath == "/") "/$name" else "$remotePath/$name"
        val childUri = SmbUri.build(connId, childRemotePath)
        val smbFile = clientPool.getSmbFile(config, creds, childRemotePath, isDirectory = false)
        smbFile.createNewFile()

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
    } catch (e: Throwable) {
        ProviderResult.Failure(e.message ?: "Failed to create SMB file", e)
    }

    override fun rename(ref: ResourceRef, name: String): ProviderResult<ResourceEntry> = try {
        val uri = Uri.parse(ref.documentId)
        val connId = SmbUri.getConnectionId(uri)
        val oldPath = SmbUri.getRemotePath(uri)
        val (config, creds) = resolveConfigAndCredentials(connId)

        val parentPath = oldPath.substringBeforeLast('/', "").ifEmpty { "/" }
        val newPath = if (parentPath == "/") "/$name" else "$parentPath/$name"
        val newUri = SmbUri.build(connId, newPath)

        val srcFile = clientPool.getSmbFile(config, creds, oldPath)
        val destFile = clientPool.getSmbFile(config, creds, newPath)
        srcFile.renameTo(destFile)

        val isDir = destFile.isDirectory
        val sizeBytes = if (isDir) null else destFile.length()
        val mtime = destFile.lastModified()

        ProviderResult.Success(
            ResourceEntry(
                ref = ResourceRef(id, newUri.toString()),
                metadata = ResourceMetadata(
                    name = name,
                    mimeType = if (isDir) "vnd.android.document/directory" else guessMimeType(name),
                    sizeBytes = sizeBytes,
                    modifiedAtMillis = mtime,
                    isDirectory = isDir
                ),
                uri = newUri
            )
        )
    } catch (e: Throwable) {
        ProviderResult.Failure(e.message ?: "Failed to rename SMB item", e)
    }

    override fun delete(ref: ResourceRef): ProviderResult<Unit> = try {
        val uri = Uri.parse(ref.documentId)
        val connId = SmbUri.getConnectionId(uri)
        val remotePath = SmbUri.getRemotePath(uri)
        val (config, creds) = resolveConfigAndCredentials(connId)

        val smbFile = clientPool.getSmbFile(config, creds, remotePath)
        if (smbFile.exists()) {
            smbFile.delete()
        } else {
            val dirFile = clientPool.getSmbFile(config, creds, remotePath, isDirectory = true)
            dirFile.delete()
        }
        ProviderResult.Success(Unit)
    } catch (e: Throwable) {
        ProviderResult.Failure(e.message ?: "Failed to delete SMB item", e)
    }

    override fun copy(source: ResourceRef, destination: ResourceRef): ProviderResult<ResourceRef> = try {
        val srcUri = Uri.parse(source.documentId)
        val destUri = Uri.parse(destination.documentId)
        val srcConnId = SmbUri.getConnectionId(srcUri)
        val destConnId = SmbUri.getConnectionId(destUri)

        if (srcConnId != destConnId) {
            ProviderResult.Unsupported(ResourceCapability.COPY)
        } else {
            val srcPath = SmbUri.getRemotePath(srcUri)
            val destDir = SmbUri.getRemotePath(destUri)
            val fileName = srcPath.substringAfterLast('/')
            val destPath = if (destDir == "/") "/$fileName" else "$destDir/$fileName"
            val (config, creds) = resolveConfigAndCredentials(srcConnId)

            val srcFile = clientPool.getSmbFile(config, creds, srcPath)
            val destFile = clientPool.getSmbFile(config, creds, destPath)
            srcFile.copyTo(destFile)

            val newUri = SmbUri.build(srcConnId, destPath)
            ProviderResult.Success(ResourceRef(id, newUri.toString()))
        }
    } catch (e: Throwable) {
        ProviderResult.Failure(e.message ?: "Failed to copy SMB item", e)
    }

    override fun move(source: ResourceRef, destination: ResourceRef): ProviderResult<ResourceRef> = try {
        val srcUri = Uri.parse(source.documentId)
        val destUri = Uri.parse(destination.documentId)
        val srcConnId = SmbUri.getConnectionId(srcUri)
        val destConnId = SmbUri.getConnectionId(destUri)

        if (srcConnId != destConnId) {
            ProviderResult.Unsupported(ResourceCapability.MOVE)
        } else {
            val srcPath = SmbUri.getRemotePath(srcUri)
            val destDir = SmbUri.getRemotePath(destUri)
            val fileName = srcPath.substringAfterLast('/')
            val destPath = if (destDir == "/") "/$fileName" else "$destDir/$fileName"
            val (config, creds) = resolveConfigAndCredentials(srcConnId)

            val srcFile = clientPool.getSmbFile(config, creds, srcPath)
            val destFile = clientPool.getSmbFile(config, creds, destPath)
            srcFile.renameTo(destFile)

            val newUri = SmbUri.build(srcConnId, destPath)
            ProviderResult.Success(ResourceRef(id, newUri.toString()))
        }
    } catch (e: Throwable) {
        ProviderResult.Failure(e.message ?: "Failed to move SMB item", e)
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
