package com.infinifolder.storage

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.infinifolder.model.*
import java.io.File

class SharedFileProvider : FileProvider()

/** Primary shared storage only; root discovered from Android, enabled by an explicit system grant. */
class LocalStorageProvider(private val context: Context) : StorageProvider {
    override val id = "local"
    val authority = context.packageName + ".files"
    val store = LocalFileStore(Environment.getExternalStorageDirectory())
    val rootUri: Uri get() = uriFor(store.root)

    fun hasAccess(): Boolean = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    fun owns(uri: Uri): Boolean = uri.scheme == "content" && uri.authority == authority
    fun fileFor(uri: Uri): File {
        require(owns(uri) && uri.pathSegments.firstOrNull() == "shared") { "Unknown local resource" }
        return store.resolve(uri.pathSegments.drop(1))
    }
    fun uriFor(file: File): Uri {
        val builder = Uri.Builder().scheme("content").authority(authority).appendPath("shared")
        store.components(file).forEach(builder::appendPath)
        return builder.build()
    }
    fun entry(file: File): ResourceEntry {
        val uri = uriFor(file)
        val mime = if (file.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
        return ResourceEntry(ResourceRef(id, uri.toString()), ResourceMetadata(
            if (file == store.root) "Internal shared storage" else file.name, mime,
            if (file.isDirectory) null else file.length(), file.lastModified(), file.isDirectory
        ), uri)
    }
    private fun file(ref: ResourceRef): File {
        require(ref.providerId == id) { "Wrong storage provider" }
        return fileFor(Uri.parse(ref.documentId))
    }
    private fun <T> access(action: () -> T): ProviderResult<T> = try {
        if (!hasAccess()) throw SecurityException("Shared storage permission is required")
        ProviderResult.Success(action())
    } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Local storage operation failed", e) }

    override fun list(parent: ResourceRef) = access { store.list(file(parent)).map(::entry) }
    override fun openRead(ref: ResourceRef) = access { file(ref).inputStream() }
    override fun openWrite(ref: ResourceRef) = access { file(ref).outputStream() }
    override fun createDirectory(parent: ResourceRef, name: String) = access { entry(store.createDirectory(file(parent), name)) }
    override fun rename(ref: ResourceRef, name: String) = access { entry(store.rename(file(ref), name)) }
    override fun delete(ref: ResourceRef) = access { store.delete(file(ref)) }
    override fun copy(source: ResourceRef, destination: ResourceRef) = access {
        entry(store.copy(file(source), file(destination))).ref
    }
    override fun move(source: ResourceRef, destination: ResourceRef): ProviderResult<ResourceRef> {
        val copied = copy(source, destination)
        if (copied !is ProviderResult.Success) return copied
        return when (val removed = delete(source)) {
            is ProviderResult.Success -> copied
            is ProviderResult.Failure -> ProviderResult.Failure("Copy completed, but original removal failed: ${removed.message}", removed.cause)
            is ProviderResult.Unsupported -> removed
        }
    }
}
