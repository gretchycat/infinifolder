package com.infinifolder.storage

import android.content.Context
import com.infinifolder.apps.AppsStorageProvider
import com.infinifolder.archive.ArchiveStorageProvider
import com.infinifolder.archive.SeekableSourceResolver
import com.infinifolder.sftp.SftpProviderFactory
import com.infinifolder.ftp.FtpProviderFactory
import com.infinifolder.smb.SmbProviderFactory
import com.infinifolder.webdav.WebDavProviderFactory
import com.infinifolder.torrent.MagnetStorageProvider
import com.infinifolder.model.*
import java.io.IOException
import io.github.gretchycat.infinistream.InputStreamSource
import io.github.gretchycat.infinistream.asInputStream

/** One provider graph per application process, shared by browser, editors, and background services. */
class StorageRuntime private constructor(context: Context) {
    private val app = context.applicationContext
    val connections = ConnectionPreferences(app)
    val commonLocations = CommonLocationsPreferences(app)
    private val locationRecorder = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "common-locations-record").apply { isDaemon = true }
    }
    val local = LocalStorageProvider(app)
    val archives = ArchiveStorageProvider(app, local)
    val apps = AppsStorageProvider(app)
    val containerLinks = ContainerLinksProvider(app)
    val partialDownloads = PartialDownloadsProvider(app)
    private val sftpFactory = SftpProviderFactory(app) { id -> connections.list().firstOrNull { it.id == id } }
    private val ftpFactory = FtpProviderFactory(app) { id -> connections.list().firstOrNull { it.id == id } }
    private val smbFactory = SmbProviderFactory(app) { id -> connections.list().firstOrNull { it.id == id } }
    private val webDavFactory = WebDavProviderFactory(app) { id -> connections.list().firstOrNull { it.id == id } }
    val sftp = sftpFactory.provider
    val ftp = ftpFactory.provider
    val smb = smbFactory.provider
    val webdav = webDavFactory.provider
    val torrents = MagnetStorageProvider(app)
    val registry = StorageProviderRegistry().apply {
        register(SafProviderFactory()); register(sftpFactory); register(ftpFactory); register(smbFactory); register(webDavFactory)
    }
    val provider = RoutingStorageProvider(local, archives, apps, sftp, ftp, smb, webdav, torrents, containerLinks, partialDownloads, SafProvider(app.contentResolver),
        uriResolver = { uri -> if (ContainerUris.networkType(uri) == null) uri else ContainerUris.resolve(uri, connections.list()) })
    init {
        DiagnosticLog.providerResolver = { provider }
        DiagnosticLog.init(app)
        provider.onEntriesObserved = partialDownloads::observe
        provider.onResourceRemoved = partialDownloads::forget
        provider.onRandomAccessTargetWritten = { destination ->
            locationRecorder.execute {
                runCatching {
                    val parent = (provider.parent(destination) as? ProviderResult.Success)?.value
                    parent?.let { commonLocations.recordRandomWriteSuccess(android.net.Uri.parse(it.documentId)) }
                }.onFailure { DiagnosticLog.record("common-locations.observe.failed", error = it) }
            }
        }
        containerLinks.validateTarget = validate@ { shortcut ->
            val uri = android.net.Uri.parse(shortcut.targetUri)
            // Saving an alias must not connect to a server; opening it validates remote access.
            if (ContainerUris.networkType(uri) != null || uri.scheme.equals("magnet", true) ||
                com.infinifolder.archive.ArchiveUri.isArchiveUri(uri) ||
                (uri.isHierarchical && (com.infinifolder.archive.ArchiveFormats.isArchive(uri.lastPathSegment.orEmpty()) ||
                    uri.lastPathSegment.orEmpty().endsWith(".torrent", true)))) return@validate
            val ref = provider.refForUri(uri) ?: return@validate
            val entry = (provider.stat(ref) as? ProviderResult.Success)?.value ?: return@validate // Offline links remain saveable.
            if (!entry.metadata.isDirectory) {
                if (com.infinifolder.archive.ArchiveFormats.isArchive(entry.metadata.name, entry.metadata.mimeType)) return@validate
                val opened = provider.openSeekable(ref)
                val image = if (opened is ProviderResult.Success) opened.value.use { device ->
                    val header = ByteArray(minOf(device.size, 69632L).coerceAtLeast(0L).toInt())
                    var read = 0
                    while (read < header.size) {
                        val n = device.readAt(read.toLong(), header, read, header.size - read)
                        if (n <= 0) break
                        read += n
                    }
                    FileMagicDetector.detectFromBytes(header.copyOf(read))?.isArchiveOrDiscImage == true
                } else false
                if (!image) throw IOException("Virtual root accepts links to containers, not regular files")
            }
        }
        archives.sourceProvider = provider
        archives.deviceResolver = { uri, writable -> SeekableSourceResolver.resolve(provider, uri, writable) }
        archives.streamResolver = { uri ->
            val ref = provider.refForUri(uri) ?: throw IOException("No provider owns $uri")
            when (val result = provider.openRead(ref)) {
                is ProviderResult.Success -> InputStreamSource(result.value).asInputStream()
                is ProviderResult.Failure -> throw IOException(result.message, result.cause)
                is ProviderResult.Unsupported -> null
            }
        }
        archives.metadataResolver = { uri ->
            provider.refForUri(uri)?.let { (provider.stat(it) as? ProviderResult.Success)?.value }
                ?.metadata?.let { it.sizeBytes to it.modifiedAtMillis }
        }
    }
    companion object {
        @Volatile private var instance: StorageRuntime? = null
        fun get(context: Context): StorageRuntime = instance ?: synchronized(this) {
            instance ?: StorageRuntime(context.applicationContext).also { instance = it }
        }
    }
}
