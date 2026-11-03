package com.infinifolder

import android.net.Uri
import com.infinifolder.storage.ContainerUris
import java.util.Locale

/** Decide whether a resolved pin belongs to a storage surface or an external URI handler. */
internal object HomeItemRouting {
    fun opensExternally(target: Uri, hasStorageEntry: Boolean): Boolean {
        if (hasStorageEntry) return false
        val scheme = target.scheme?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: return false
        if (ContainerUris.networkType(target) != null) return false
        return scheme !in setOf("file", "content", "archive", "apps", "container-links",
            "partial-downloads", "magnet", "torrent", "infinifolder-termux")
    }
}
