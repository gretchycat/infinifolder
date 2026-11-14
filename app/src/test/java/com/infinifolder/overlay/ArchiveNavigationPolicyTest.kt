package com.infinifolder.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveNavigationPolicyTest {
    @Test
    fun browsableArchivesNavigateInsteadOfBeingAddedToMultipleSelection() {
        assertTrue(ArchiveNavigationPolicy.shouldBrowse("album.zip", "application/zip", false))
        assertFalse(ArchiveNavigationPolicy.shouldToggleMultipleSelection(
            selectionMode = true,
            allowMultiple = true,
            isDirectory = false,
            isBrowsableContainer = true,
            isLink = false,
        ))
    }

    @Test
    fun ordinaryFilesStillToggleInMultipleSelectionMode() {
        assertTrue(ArchiveNavigationPolicy.shouldToggleMultipleSelection(
            selectionMode = true,
            allowMultiple = true,
            isDirectory = false,
            isBrowsableContainer = false,
            isLink = false,
        ))
    }

    @Test
    fun packagedFilesPlaylistsAndAppUrisAreNotNavigatedAsArchives() {
        assertFalse(ArchiveNavigationPolicy.shouldBrowse("reader.apk", "application/vnd.android.package-archive", false))
        assertFalse(ArchiveNavigationPolicy.shouldBrowse("tracks.m3u", "audio/x-mpegurl", false))
        assertFalse(ArchiveNavigationPolicy.shouldBrowse("album.zip", "application/zip", true))
    }
}
