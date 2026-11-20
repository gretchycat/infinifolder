package com.infinifolder

/** Layout choices shared by Folder's full, floating, and embedded browsers. */
internal enum class BrowserViewMode {
    LIST,
    GRID,
    STAGGERED;

    val showsIcons: Boolean get() = this != LIST
    val displayName: String get() = when (this) {
        LIST -> "List"
        GRID -> "Grid"
        STAGGERED -> "Staggered Icons"
    }

    fun next(): BrowserViewMode = when (this) {
        LIST -> GRID
        GRID -> STAGGERED
        STAGGERED -> LIST
    }

    companion object {
        fun fromPreference(value: String?, legacyIcons: Boolean): BrowserViewMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: if (legacyIcons) GRID else LIST
    }
}
