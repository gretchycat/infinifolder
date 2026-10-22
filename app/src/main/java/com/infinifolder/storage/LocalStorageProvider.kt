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
import java.io.IOException
import java.nio.file.Files

class SharedFileProvider : FileProvider() {
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): android.database.Cursor {
        val cursor = super.query(uri, projection, selection, selectionArgs, sortOrder)
        val requestedColumns = projection ?: return cursor
        if (!requestedColumns.contains(android.provider.MediaStore.MediaColumns.DATA)) {
            return cursor
        }
        val columnNames = cursor.columnNames.toMutableList()
        if (!columnNames.contains(android.provider.MediaStore.MediaColumns.DATA)) {
            columnNames.add(android.provider.MediaStore.MediaColumns.DATA)
        }
        val matrix = android.database.MatrixCursor(columnNames.toTypedArray(), cursor.count)
        cursor.moveToPosition(-1)
        while (cursor.moveToNext()) {
            val row = matrix.newRow()
            for (col in cursor.columnNames) {
                val idx = cursor.getColumnIndex(col)
                when (cursor.getType(idx)) {
                    android.database.Cursor.FIELD_TYPE_STRING -> row.add(col, cursor.getString(idx))
                    android.database.Cursor.FIELD_TYPE_INTEGER -> row.add(col, cursor.getLong(idx))
                    android.database.Cursor.FIELD_TYPE_FLOAT -> row.add(col, cursor.getDouble(idx))
                    android.database.Cursor.FIELD_TYPE_BLOB -> row.add(col, cursor.getBlob(idx))
                    else -> row.add(col, null)
                }
            }
            val path = uri.path?.removePrefix("/shared/")?.removePrefix("/root/")?.removePrefix("/cache/")
            val file = if (path != null) {
                val ext = File(Environment.getExternalStorageDirectory(), path)
                if (ext.exists()) ext else File("/", path)
            } else null
            if (file != null && file.exists()) {
                row.add(android.provider.MediaStore.MediaColumns.DATA, file.absolutePath)
            }
        }
        cursor.close()
        return matrix
    }

    override fun getType(uri: Uri): String? {
        val path = uri.path?.removePrefix("/shared/")?.removePrefix("/root/")?.removePrefix("/cache/")
        val file = if (path != null) {
            val ext = File(Environment.getExternalStorageDirectory(), path)
            if (ext.exists()) ext else File("/", path)
        } else null
        if (file != null && file.exists()) {
            val pkgMime = com.infinifolder.archive.ArchiveFormats.packageMimeType(file.name)
            if (pkgMime != null) return pkgMime
            val ext = file.extension.lowercase()
            val extMime = MimeTypeMap.getSingleton()?.getMimeTypeFromExtension(ext)
                ?: com.infinifolder.archive.ArchiveMimeTypes.guessMimeType(file.name, false)
            if (FileMagicDetector.isAmbiguousOrUnknown(file.name, extMime)) {
                return FileMagicDetector.detect(file)?.mimeType ?: extMime
            }
            return extMime
        }
        return super.getType(uri)
    }
}

/** Primary shared storage only; root discovered from Android, enabled by an explicit system grant. */
class LocalStorageProvider(private val context: Context) : StorageProvider {
    override val id = "local"
    val authority = context.packageName + ".files"
    val store = LocalFileStore(Environment.getExternalStorageDirectory())
    val rootUri: Uri = uriFor(store.root)

    fun hasAccess(): Boolean = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    fun owns(uri: Uri): Boolean = try {
        if (uri.scheme == "content" && uri.authority == authority) {
            val first = uri.pathSegments.firstOrNull()
            first == "shared" || first == "root" || first == "cache"
        } else if (uri.scheme == "file") {
            true
        } else {
            false
        }
    } catch (_: Throwable) {
        false
    }

    fun fileFor(uri: Uri): File {
        if (uri.scheme == "file") {
            val path = uri.path ?: throw IllegalArgumentException("Missing file path")
            return File(Uri.decode(path)).canonicalFile
        }
        require(owns(uri)) { "Unknown local resource: $uri" }
        val first = uri.pathSegments.firstOrNull()
        return when (first) {
            "shared" -> store.resolve(uri.pathSegments.drop(1))
            "root" -> File("/" + uri.pathSegments.drop(1).joinToString("/")).canonicalFile
            "cache" -> File(context.cacheDir, uri.pathSegments.drop(1).joinToString("/")).canonicalFile
            else -> store.resolve(uri.pathSegments.drop(1))
        }
    }
    fun uriFor(file: File): Uri {
        val canonical = file.canonicalFile
        val cacheCanonical = context.cacheDir.canonicalFile
        val sharedCanonical = store.root.canonicalFile
        if (canonical == cacheCanonical || canonical.toPath().startsWith(cacheCanonical.toPath())) {
            val builder = Uri.Builder().scheme("content").authority(authority).appendPath("cache")
            cacheCanonical.toPath().relativize(canonical.toPath()).map { it.toString() }.filter { it.isNotEmpty() }
                .forEach(builder::appendPath)
            return builder.build()
        }
        if (canonical == sharedCanonical || canonical.toPath().startsWith(sharedCanonical.toPath())) {
            val builder = Uri.Builder().scheme("content").authority(authority).appendPath("shared")
            sharedCanonical.toPath().relativize(canonical.toPath()).map { it.toString() }.filter { it.isNotEmpty() }
                .forEach(builder::appendPath)
            return builder.build()
        }
        val builder = Uri.Builder().scheme("content").authority(authority).appendPath("root")
        canonical.toPath().map { it.toString() }.filter { it.isNotEmpty() }.forEach(builder::appendPath)
        return builder.build()
    }
    fun entry(file: File): ResourceEntry {
        val uri = uriFor(file)
        val isSymlink = try { Files.isSymbolicLink(file.toPath()) } catch (_: Throwable) { false }
        val targetPath = if (isSymlink) {
            try { Files.readSymbolicLink(file.toPath()).toString() } catch (_: Throwable) { null }
        } else null
        val canonicalTarget = if (isSymlink) {
            try { file.canonicalFile } catch (_: Throwable) { null }
        } else null
        val targetExists = canonicalTarget?.exists() ?: false
        val isBroken = isSymlink && !targetExists
        val targetFile = if (isSymlink && targetExists) canonicalTarget ?: file else file

        val isDir = targetFile.isDirectory
        val mime = if (isDir) {
            DocumentsContract.Document.MIME_TYPE_DIR
        } else {
            val pkgMime = com.infinifolder.archive.ArchiveFormats.packageMimeType(targetFile.name)
            if (pkgMime != null) {
                pkgMime
            } else {
                val extMime = MimeTypeMap.getSingleton()?.getMimeTypeFromExtension(targetFile.extension.lowercase())
                    ?: com.infinifolder.archive.ArchiveMimeTypes.guessMimeType(targetFile.name, false)
                if (FileMagicDetector.isAmbiguousOrUnknown(targetFile.name, extMime)) {
                    FileMagicDetector.detect(targetFile)?.mimeType ?: extMime
                } else {
                    extMime
                }
            }
        }
        val perm = if (isBroken) {
            FilePermissions(canRead = false, canWrite = false, canExecute = false, mode = null, formatted = null, owner = null, group = null)
        } else {
            PermissionHelper.getFilePermissions(targetFile)
        }
        val displayName = when {
            file == store.root -> "Internal shared storage"
            else -> file.name.ifBlank { file.path }
        }
        return ResourceEntry(ResourceRef(id, uri.toString()), ResourceMetadata(
            name = displayName,
            mimeType = mime,
            sizeBytes = if (isDir) null else (if (isBroken) 0L else targetFile.length()),
            modifiedAtMillis = if (isBroken) file.lastModified() else targetFile.lastModified(),
            isDirectory = isDir,
            canRead = perm.canRead,
            canWrite = perm.canWrite,
            canExecute = perm.canExecute,
            permissionsMode = perm.mode,
            permissionsFormatted = perm.formatted,
            owner = perm.owner,
            group = perm.group,
            isSymbolicLink = isSymlink,
            linkTarget = targetPath,
            isBrokenLink = isBroken
        ), uri)
    }
    private fun file(ref: ResourceRef): File {
        require(ref.providerId == id) { "Wrong storage provider" }
        return fileFor(Uri.parse(ref.documentId))
    }

    private fun isAppInternal(file: File): Boolean {
        val canonical = file.canonicalPath
        if (canonical.startsWith(context.filesDir.canonicalPath)) return true
        if (canonical.startsWith(context.cacheDir.canonicalPath)) return true
        runCatching {
            context.getExternalFilesDirs(null).filterNotNull().forEach {
                if (canonical.startsWith(it.canonicalPath)) return true
            }
            context.getExternalCacheDirs().filterNotNull().forEach {
                if (canonical.startsWith(it.canonicalPath)) return true
            }
        }
        return false
    }

    private fun checkAccess(file: File) {
        if (isAppInternal(file)) return
        if (!hasAccess() && !file.canRead()) {
            throw SecurityException("Storage permission is required to access ${file.name}")
        }
    }

    private fun <T> access(action: () -> T): ProviderResult<T> = try {
        ProviderResult.Success(action())
    } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Local storage operation failed", e) }

    override fun list(parent: ResourceRef) = access {
        val dir = file(parent)
        checkAccess(dir)
        val files = dir.listFiles()?.toList() ?: throw IOException("Android did not allow reading directory: ${dir.name}")
        files.map(::entry)
    }
    override fun openRead(ref: ResourceRef) = access {
        val f = file(ref)
        checkAccess(f)
        f.inputStream()
    }
    override fun openWrite(ref: ResourceRef) = access {
        val f = file(ref)
        checkAccess(f)
        f.outputStream()
    }
    override fun createDirectory(parent: ResourceRef, name: String) = access {
        val parentDir = file(parent)
        checkAccess(parentDir)
        val created = File(parentDir, name)
        if (!created.mkdir() && !created.isDirectory) {
            Files.createDirectory(created.toPath())
        }
        entry(created)
    }
    override fun createFile(parent: ResourceRef, name: String, mimeType: String) = access {
        val parentDir = file(parent)
        checkAccess(parentDir)
        val created = File(parentDir, name)
        if (!created.createNewFile() && !created.exists()) {
            Files.createFile(created.toPath())
        }
        entry(created)
    }
    override fun rename(ref: ResourceRef, name: String) = access {
        val src = file(ref)
        checkAccess(src)
        val dest = File(src.parentFile, name)
        if (!src.renameTo(dest)) {
            Files.move(src.toPath(), dest.toPath())
        }
        entry(dest)
    }
    override fun delete(ref: ResourceRef) = access {
        val target = file(ref)
        checkAccess(target)
        val success = if (target.isDirectory) target.deleteRecursively() else target.delete()
        if (!success && target.exists()) {
            throw IOException("Could not delete ${target.name}")
        }
    }
    override fun copy(source: ResourceRef, destination: ResourceRef) = access {
        val src = file(source)
        val destDir = file(destination)
        checkAccess(src)
        checkAccess(destDir)
        val dest = File(destDir, src.name)
        if (src.isDirectory) {
            src.copyRecursively(dest, overwrite = true)
        } else {
            src.copyTo(dest, overwrite = true)
        }
        entry(dest).ref
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
