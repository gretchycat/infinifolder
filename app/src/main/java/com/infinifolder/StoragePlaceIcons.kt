package com.infinifolder

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import com.infinifolder.storage.*
import java.util.Locale

/** Presentation roles for actual storage roots. No decoding, grants, or speculative network probes.
 * All discovery runs on icon/source workers; first-frame callers use remembered roles only. */
internal class StoragePlaceIcons(context: Context) {
    private val app = context.applicationContext
    private val runtime = StorageRuntime.get(app)
    private data class Roots(val at: Long, val values: List<DocumentRootInfo>)
    private val roots = object : android.util.LruCache<String, Roots>(32) {}
    @Volatile private var volumesAt = 0L
    @Volatile private var volumes = emptyList<StorageVolume>()
    fun clear() { roots.evictAll(); volumesAt = 0L; volumes = emptyList() }

    private fun roots(authority: String): List<DocumentRootInfo> {
        val now = android.os.SystemClock.elapsedRealtime()
        roots.get(authority)?.takeIf { now - it.at < 30_000 }?.let { return it.values }
        val values = IconWork.bounded(500, lane = "storage-roots:$authority") {
            DocumentRootInfo.query(app, authority)
        }.orEmpty()
        roots.put(authority, Roots(now, values))
        return values
    }

    private fun volumes(): List<StorageVolume> {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - volumesAt >= 30_000 || volumesAt == 0L) {
            volumes = runCatching { app.getSystemService(StorageManager::class.java)?.storageVolumes.orEmpty() }
                .getOrDefault(emptyList())
            volumesAt = now
        }
        return volumes
    }

    fun volume(volume: StorageVolume): String {
        if (volume.isPrimary || volume.isEmulated) return "internal_shared"
        val id = volume.uuid ?: return "removable_storage"
        val root = roots(EXTERNAL).firstOrNull { it.documentId.substringBefore(':').equals(id, true) }
        return root?.let(::role) ?: "removable_storage"
    }

    private fun role(root: DocumentRootInfo): String {
        // Use the provider's icon metadata, never a volume's user-editable title.
        val icon = root.iconResourceName.orEmpty().lowercase(Locale.ROOT)
        return when {
            "usb" in icon -> "usb_storage"
            "sdcard" in icon || "sd_card" in icon || "sd_mmc" in icon -> "sd_card"
            root.localOnly == false -> "cloud_storage"
            root.documentId == "primary:" -> "internal_shared"
            else -> "removable_storage"
        }
    }

    /** Root artwork must also be inherited by links, tree rows, and directory listings. */
    fun forUri(uri: Uri): String? {
        BrowserLocations.find(uri)?.let { return it.key }
        if (runtime.local.owns(uri)) {
            val file = runCatching { runtime.local.fileFor(uri) }.getOrNull() ?: return null
            if (file == runtime.local.store.root) return "internal_shared"
            if (Build.VERSION.SDK_INT >= 30) volumes().firstOrNull {
                runCatching { it.directory?.absoluteFile == file }.getOrDefault(false)
            }?.let { return volume(it) }
            return null
        }
        if (ContainerUris.networkType(uri) != null) {
            val connections = runtime.connections.list()
            val bound = ContainerUris.resolve(uri, connections) ?: return null
            val connection = connections.firstOrNull { it.id == bound.authority } ?: return null
            val path = bound.path.orEmpty().trimEnd('/')
            val initial = if (connection.sourceTypeId == "smb")
                com.infinifolder.smb.SmbConfig.rootPath(connection.options["initialShare"], connection.options["initialPath"])
                else connection.options["initialPath"].orEmpty()
            if (path.isEmpty() || path == initial.trimEnd('/')) return "network_storage"
            return null
        }
        if (uri.scheme != "content") return null
        val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            ?: runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        val authority = uri.authority ?: return null
        // Inner folders keep folder artwork. An arbitrary tree grant is not necessarily a device root.
        return roots(authority).firstOrNull { it.documentId == id }?.let { root ->
            if (authority == EXTERNAL || root.localOnly == false) role(root) else null
        }
    }

    companion object { private const val EXTERNAL = "com.android.externalstorage.documents" }
}
