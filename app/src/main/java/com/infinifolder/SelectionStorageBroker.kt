package com.infinifolder

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import com.infinifolder.archive.ArchiveFormats
import com.infinifolder.archive.ArchiveUri
import com.infinifolder.model.*
import com.infinifolder.storage.*
import io.github.gretchycat.infiniselect.CollectionScope
import io.github.gretchycat.infiniselect.FileShortcut
import java.io.File
import java.io.FileNotFoundException
import org.json.JSONArray

/** Folder-owned storage operations for permission-free InfiniSelect clients. */
internal class SelectionStorageBroker(private val app: Context) {
    private val runtime
        get() = StorageRuntime.get(app)

    private fun <T> ProviderResult<T>.value(): T =
        when (this) {
            is ProviderResult.Success -> value
            is ProviderResult.Failure ->
                throw FileNotFoundException(message).apply { initCause(cause) }
            is ProviderResult.Unsupported ->
                throw FileNotFoundException("Unsupported storage operation: $capability")
        }

    fun list(args: Bundle): Bundle {
        val directory = args.getString("directory")?.let(Uri::parse)
        var loading = false
        var notice: String? = null
        val entries =
            if (directory == null) {
                val roots = mutableListOf<Pair<Uri, String>>()
                if (runtime.local.hasAccess()) roots += runtime.local.rootUri to "Internal storage"
                runtime.connections.list().forEach { connection ->
                    runtime.registry.rootUri(connection)?.let {
                        roots += it to connection.displayName
                    }
                }
                app.contentResolver.persistedUriPermissions
                    .filter {
                        it.isReadPermission &&
                            android.provider.DocumentsContract.isTreeUri(it.uri) &&
                            it.uri.authority != StorageDocumentsProvider.AUTHORITY
                    }
                    .forEach {
                        roots +=
                            android.provider.DocumentsContract.buildDocumentUriUsingTree(
                                it.uri,
                                android.provider.DocumentsContract.getTreeDocumentId(it.uri),
                            ) to "Android storage"
                    }
                roots += ContainerLinksProvider.ROOT to "Saved containers"
                roots
                    .distinctBy { it.first }
                    .mapNotNull { (uri, name) ->
                        runtime.provider.refForUri(uri)?.let { ref ->
                            ResourceEntry(
                                ref,
                                ResourceMetadata(
                                    name,
                                    "vnd.android.document/directory",
                                    null,
                                    null,
                                    true,
                                ),
                                uri,
                            )
                        }
                    }
            } else {
                val source =
                    StorageDocumentsProvider.resolveReference(app, directory)
                        ?: throw FileNotFoundException("Storage location unavailable")
                val ref =
                    runtime.provider.refForUri(source)
                        ?: throw FileNotFoundException("Storage source unavailable")
                val result = runtime.provider.list(ref)
                if (result is ProviderResult.Success) {
                    loading = result.isLoading
                    notice = result.notice
                }
                result.value()
            }
        val offset = args.getInt("offset", 0)
        require(offset >= 0)
        val visible = entries.filterNot { it.metadata.isHidden }.sortedBy { it.uri.toString() }
        val rows = JSONArray()
        var index = offset
        var budget = 0
        while (index < visible.size && index - offset < 256) {
            val entry = visible[index]
            val meta = entry.metadata
            val archive = !meta.isDirectory && ArchiveFormats.isArchive(meta.name, meta.mimeType)
            var label = meta.name
            if (entry.uri.scheme == "file" && meta.name.endsWith(".iflink", true)) {
                label =
                    runCatching {
                            val file = File(entry.uri.path!!)
                            require(file.length() <= FileShortcut.MAX_BYTES)
                            FileShortcut.decode(file.readText()).displayName
                        }
                        .getOrDefault(label)
            }
            val row =
                io.github.gretchycat.infiniselect
                    .DirectoryListingEntry(
                        (if (archive) ArchiveUri.toArchiveUri(entry.uri) else entry.uri).toString(),
                        meta.name,
                        label,
                        if (archive || meta.isDirectory) "vnd.android.document/directory"
                        else meta.mimeType,
                        meta.isDirectory || archive,
                        meta.sizeBytes,
                        meta.modifiedAtMillis ?: 0L,
                    )
                    .toJson()
            val bytes = row.toString().toByteArray(Charsets.UTF_8).size
            require(bytes <= 256 * 1024) { "Directory entry is too large" }
            if (budget + bytes > 380 * 1024 && index > offset) break
            rows.put(row)
            budget += bytes
            index++
        }
        return Bundle().apply {
            putInt("version", 1)
            putString("entries", rows.toString())
            putInt("next", if (index < visible.size) index else -1)
            putBoolean("loading", loading)
            notice?.let { putString("notice", it) }
        }
    }

    fun collection(args: Bundle, caller: String): Bundle {
        val client = requireNotNull(args.getString("client"))
        val segments = args.getStringArrayList("segments").orEmpty()
        val directory =
            CollectionScope.directory(
                Environment.getExternalStorageDirectory(),
                caller,
                client,
                segments,
            )
        check(directory.isDirectory || directory.mkdirs()) {
            "InfiniFolder cannot create this collection; check Folder's storage access"
        }
        val uri =
            when (args.getString("operation")) {
                "directory" -> Uri.fromFile(directory)
                "put",
                "remove" -> {
                    val text = requireNotNull(args.getString("shortcut"))
                    val shortcut = FileShortcut.decode(text)
                    if (args.getString("operation") == "remove") {
                        CollectionScope.remove(directory, shortcut.targetUri)
                        Uri.fromFile(directory)
                    } else
                        Uri.fromFile(
                            shortcut.writeTo(directory).apply {
                                if (args.getBoolean("touch"))
                                    setLastModified(System.currentTimeMillis())
                            }
                        )
                }
                else -> throw IllegalArgumentException("Unknown collection operation")
            }
        return Bundle().apply { putString("uri", uri.toString()) }
    }
}
