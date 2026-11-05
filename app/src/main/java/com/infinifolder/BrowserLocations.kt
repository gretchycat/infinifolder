package com.infinifolder

import android.net.Uri

/** Navigation destinations for browser pages, not filesystem/protocol capabilities. */
internal object BrowserLocations {
    const val SCHEME = "infinifolder-location"
    data class Location(val key: String, val title: String, val iconName: String, val fallback: String)
    private val locations = listOf(
        Location("internal_shared", "Internal shared storage", "drive-harddisk", "📱"),
        Location("application_public", "Application public", "folder-application-public", "▦"),
        Location("installed_apps", "Installed Applications", "start-here-android", "▦"),
        Location("partial_downloads", "Partial downloads", "folder-download", "⬇"),
        Location("external_link_history", "External link history", "document-open-recent", "🕘"),
        Location("pin_board", "Pin board", "folder-bookmark", "📌"),
        Location("virtual-root", "Virtual root", "user-home", "🏠")
    ).associateBy { it.key }
    fun forKey(key: String?) = locations[key]
    fun uri(key: String?) = forKey(key)?.let { Uri.Builder().scheme(SCHEME).authority(it.key).build() }
    fun owns(uri: Uri) = uri.scheme.equals(SCHEME, true)
    fun find(uri: Uri): Location? = runCatching {
        if (owns(uri) && uri.isHierarchical && uri.userInfo == null && uri.port == -1 &&
            uri.path.orEmpty() in setOf("", "/") && uri.query == null && uri.fragment == null) locations[uri.host] else null
    }.getOrNull()
}
