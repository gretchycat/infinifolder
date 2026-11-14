package com.infinifolder.overlay

import com.infinifolder.archive.ArchiveFormats

/** Keeps archive navigation ahead of file selection in the floating browser. */
internal object ArchiveNavigationPolicy {
    fun shouldBrowse(name: String, mimeType: String, isApplicationUri: Boolean): Boolean =
        !isApplicationUri && (
            ArchiveFormats.isDefaultBrowsableArchive(name, mimeType) ||
                ArchiveFormats.isDiscImage(name, mimeType)
            )

    fun shouldToggleMultipleSelection(
        selectionMode: Boolean,
        allowMultiple: Boolean,
        isDirectory: Boolean,
        isBrowsableContainer: Boolean,
        isLink: Boolean,
    ): Boolean = selectionMode && allowMultiple && !isDirectory &&
        !isBrowsableContainer && !isLink
}
