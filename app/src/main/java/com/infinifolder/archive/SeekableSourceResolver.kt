package com.infinifolder.archive

import android.content.Context
import android.net.Uri
import com.infinifolder.ftp.FtpBlockDevice
import com.infinifolder.ftp.FtpStorageProvider
import com.infinifolder.ftp.FtpUri
import com.infinifolder.sftp.SftpBlockDevice
import com.infinifolder.sftp.SftpStorageProvider
import com.infinifolder.sftp.SftpUri
import com.infinifolder.smb.SmbBlockDevice
import com.infinifolder.smb.SmbStorageProvider
import com.infinifolder.smb.SmbUri
import com.infinifolder.storage.LocalStorageProvider
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Resolves any supported local or remote storage URI to an optimized,
 * range-cached BlockDevice. Handles transparent nesting, cycle detection,
 * and capability propagation.
 */
object SeekableSourceResolver {

    const val MAX_NESTING_DEPTH = 16
    private const val RANGE_CACHE_BLOCK_SIZE = 64 * 1024 // 64 KiB
    private const val RANGE_CACHE_MAX_BLOCKS = 256 // 16 MiB per open container

    fun resolve(
        context: Context,
        sourceUri: Uri,
        writable: Boolean = false,
        localProvider: LocalStorageProvider? = null,
        sftpProvider: SftpStorageProvider? = null,
        ftpProvider: FtpStorageProvider? = null,
        smbProvider: SmbStorageProvider? = null,
        archiveProvider: ArchiveStorageProvider? = null,
        visitedUris: Set<Uri> = emptySet(),
        depth: Int = 0
    ): BlockDevice? {
        if (depth > MAX_NESTING_DEPTH || sourceUri in visitedUris) {
            throw IOException("Cyclic reference or excessive nesting depth detected ($depth) for URI: $sourceUri")
        }
        val nextVisited = visitedUris + sourceUri

        // 1. Local filesystem via LocalStorageProvider
        if (localProvider != null && localProvider.owns(sourceUri)) {
            val file = runCatching { localProvider.fileFor(sourceUri) }.getOrNull()
            if (file != null && file.exists()) {
                val mode = if (writable && file.canWrite()) "rw" else "r"
                return FileBlockDevice(RandomAccessFile(file, mode), isReadOnly = (mode == "r"))
            }
        }

        // 2. Direct file:// URI
        if (sourceUri.scheme == "file") {
            val file = File(sourceUri.path ?: "")
            if (file.exists()) {
                val mode = if (writable && file.canWrite()) "rw" else "r"
                return FileBlockDevice(RandomAccessFile(file, mode), isReadOnly = (mode == "r"))
            }
        }

        // 3. Remote SMB
        if (SmbUri.isSmbUri(sourceUri) && smbProvider != null) {
            return try {
                val connId = SmbUri.getConnectionId(sourceUri)
                val remotePath = SmbUri.getRemotePath(sourceUri)
                val (config, creds) = smbProvider.resolveConfigAndCredentials(connId)
                val smbFile = smbProvider.clientPool.getSmbFile(config, creds, remotePath)
                if (smbFile.exists() && !smbFile.isDirectory) {
                    val rawDevice = SmbBlockDevice(smbFile, isReadOnly = !writable)
                    RangeCachedBlockDevice(rawDevice, RANGE_CACHE_BLOCK_SIZE, RANGE_CACHE_MAX_BLOCKS)
                } else null
            } catch (_: Exception) {
                null
            }
        }

        // 4. Remote SFTP
        if (SftpUri.isSftpUri(sourceUri) && sftpProvider != null) {
            return try {
                val connId = SftpUri.getConnectionId(sourceUri)
                val remotePath = SftpUri.getRemotePath(sourceUri)
                val (config, creds) = sftpProvider.resolveConfigAndCredentials(connId)
                val initialSize = sftpProvider.getMetadata(sourceUri)?.first
                val rawDevice = SftpBlockDevice(
                    clientPool = sftpProvider.clientPool,
                    config = config,
                    credentials = creds,
                    remotePath = remotePath,
                    isReadOnly = !writable,
                    initialSize = initialSize
                )
                RangeCachedBlockDevice(rawDevice, RANGE_CACHE_BLOCK_SIZE, RANGE_CACHE_MAX_BLOCKS)
            } catch (e: Exception) {
                android.util.Log.w("SeekableSourceResolver", "SFTP BlockDevice init failed for $sourceUri", e)
                null
            }
        }

        // 5. Remote FTP
        if (FtpUri.isFtpUri(sourceUri) && ftpProvider != null) {
            if (writable) return null // Random writes safely rejected on FTP
            return try {
                val connId = FtpUri.getConnectionId(sourceUri)
                val remotePath = FtpUri.getRemotePath(sourceUri)
                val (config, creds) = ftpProvider.resolveConfigAndCredentials(connId)
                val meta = ftpProvider.getMetadata(sourceUri)
                val size = meta?.first ?: 0L
                val rawDevice = FtpBlockDevice(
                    clientPool = ftpProvider.clientPool,
                    config = config,
                    credentials = creds,
                    remotePath = remotePath,
                    size = size
                )
                RangeCachedBlockDevice(rawDevice, RANGE_CACHE_BLOCK_SIZE, RANGE_CACHE_MAX_BLOCKS)
            } catch (_: Exception) {
                null
            }
        }

        // 6. Nested container via archive://
        if (ArchiveUri.isArchiveUri(sourceUri)) {
            val parentSourceUri = ArchiveUri.getSourceUri(sourceUri)
            val entryPath = ArchiveUri.getEntryPath(sourceUri)

            val parentDevice = resolve(
                context = context,
                sourceUri = parentSourceUri,
                writable = writable,
                localProvider = localProvider,
                sftpProvider = sftpProvider,
                ftpProvider = ftpProvider,
                smbProvider = smbProvider,
                archiveProvider = archiveProvider,
                visitedUris = nextVisited,
                depth = depth + 1
            )

            if (parentDevice != null && archiveProvider != null) {
                val parentName = archiveProvider.getSourceName(parentSourceUri)
                // Check if the nested entry is an uncompressed slice (e.g. STORED in ZIP)
                val slice = archiveProvider.reader.getEntryBlockDevice(parentName, entryPath, parentDevice)
                if (slice != null) {
                    return slice
                }
            }

            // Fallback for compressed entries: extract to cache and open as FileBlockDevice
            if (archiveProvider != null) {
                val cachedFile = archiveProvider.extractToCache(sourceUri)
                if (cachedFile.exists()) {
                    val mode = if (writable && cachedFile.canWrite()) "rw" else "r"
                    return FileBlockDevice(RandomAccessFile(cachedFile, mode), isReadOnly = (mode == "r"))
                }
            }
        }

        // 7. Android SAF content:// URI via ParcelFileDescriptor
        if (sourceUri.scheme == "content") {
            try {
                val mode = if (writable) "rw" else "r"
                val pfd = context.contentResolver.openFileDescriptor(sourceUri, mode)
                if (pfd != null) {
                    val dev = ContentUriBlockDevice(pfd, isReadOnly = !writable)
                    return RangeCachedBlockDevice(dev, RANGE_CACHE_BLOCK_SIZE, RANGE_CACHE_MAX_BLOCKS)
                }
            } catch (_: Exception) {}
        }

        return null
    }
}
