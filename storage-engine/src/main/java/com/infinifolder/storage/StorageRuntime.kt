package com.infinifolder.storage

import android.content.Context
import com.infinifolder.apps.AppsStorageProvider
import com.infinifolder.archive.ArchiveStorageProvider
import com.infinifolder.archive.SeekableSourceResolver
import com.infinifolder.sftp.SftpProviderFactory
import com.infinifolder.ftp.FtpProviderFactory
import com.infinifolder.smb.SmbProviderFactory
import com.infinifolder.model.*
import java.io.IOException

/** One provider graph per application process, shared by browser, editors, and background services. */
class StorageRuntime private constructor(context: Context) {
    private val app = context.applicationContext
    val connections = ConnectionPreferences(app)
    val local = LocalStorageProvider(app)
    val archives = ArchiveStorageProvider(app, local)
    val apps = AppsStorageProvider(app)
    private val sftpFactory = SftpProviderFactory(app) { id -> connections.list().firstOrNull { it.id == id } }
    private val ftpFactory = FtpProviderFactory(app) { id -> connections.list().firstOrNull { it.id == id } }
    private val smbFactory = SmbProviderFactory(app) { id -> connections.list().firstOrNull { it.id == id } }
    val sftp = sftpFactory.provider
    val ftp = ftpFactory.provider
    val smb = smbFactory.provider
    val registry = StorageProviderRegistry().apply {
        register(SafProviderFactory()); register(sftpFactory); register(ftpFactory); register(smbFactory)
    }
    val provider = RoutingStorageProvider(local, archives, apps, sftp, ftp, smb, SafProvider(app.contentResolver))
    init {
        DiagnosticLog.init(app)
        archives.sourceProvider = provider
        archives.deviceResolver = { uri, writable -> SeekableSourceResolver.resolve(provider, uri, writable) }
        archives.streamResolver = { uri ->
            val ref = provider.refForUri(uri) ?: throw IOException("No provider owns $uri")
            when (val result = provider.openRead(ref)) {
                is ProviderResult.Success -> result.value
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
