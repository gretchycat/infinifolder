package com.infinifolder

import org.junit.Assert.*
import org.junit.Test
import com.infinifolder.archive.ArchiveFormats
import com.infinifolder.mime.MimeRegistry
import com.infinifolder.preview.AudioIconFormats

class CompanionViewerDefaultsTest {
    @Test fun audioWithoutAnExtensionOrWithAGenericMimeStillTargetsMedia() {
        assertEquals("com.infinimedia", CompanionViewerDefaults.defaultPackageFor("track", "audio/mpeg"))
        assertEquals("com.infinimedia", CompanionViewerDefaults.defaultPackageFor("track.flac", "application/octet-stream"))
        assertEquals("com.infinimedia", CompanionViewerDefaults.defaultPackageFor("playlist.m3u", "audio/x-mpegurl"))
        assertNotEquals("com.infinimedia", CompanionViewerDefaults.defaultPackageFor("picture.png", "image/png"))
    }
    @Test fun removedAudioAndVideoPlayersMigrateToMedia() {
        for (name in listOf("AudioPlayerActivity", "VideoPlayerActivity")) {
            assertEquals("com.infinimedia", CompanionViewerDefaults.replacementPackageForRemovedViewer("com.infinifolder.viewer.$name"))
        }
    }
    @Test fun removedDocumentPlayersStillMigrateToScribe() {
        assertEquals("com.infiniscribe", CompanionViewerDefaults.replacementPackageForRemovedViewer("com.infinifolder.viewer.PdfViewerActivity"))
        assertEquals("com.infiniscribe", CompanionViewerDefaults.replacementPackageForRemovedViewer("com.infinifolder.viewer.TextViewerActivity"))
    }
    @Test fun existingViewersAndUnrelatedAppsAreNotMigrated() {
        assertNull(CompanionViewerDefaults.replacementPackageForRemovedViewer("com.infinifolder.viewer.HexViewerActivity"))
        assertNull(CompanionViewerDefaults.replacementPackageForRemovedViewer("com.other.AudioPlayerActivity"))
    }
    @Test fun everyRegisteredAudioAndPlaylistExtensionTargetsMedia() {
        val associations = MimeRegistry.associations().filter { (extension, mime) ->
            AudioIconFormats.isAudio(mime) || ArchiveFormats.isPlaylist("track.$extension", mime)
        }
        assertTrue("Expected the MIME registry to expose audio and playlist extensions", associations.size > 40)
        associations.forEach { (extension, mime) ->
            assertEquals(extension, "com.infinimedia", CompanionViewerDefaults.defaultPackageFor("track.$extension", mime))
        }
    }
    @Test fun installedMediaCanBeAssociatedWithoutQueryableMimeFilters() {
        assertTrue(CompanionViewerDefaults.mayAssociateWithoutIntentFilter("com.infinimedia"))
        assertFalse(CompanionViewerDefaults.mayAssociateWithoutIntentFilter("com.infiniscribe"))
        assertFalse(CompanionViewerDefaults.mayAssociateWithoutIntentFilter("org.example.player"))
    }
}
