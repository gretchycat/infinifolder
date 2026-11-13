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
    @Test fun pdfIsDetectedAndAssignedToTheScribeCompanion() {
        assertEquals(com.infinifolder.viewer.MediaType.PDF,
            com.infinifolder.viewer.MediaTypeDetector.detect("manual.pdf", "application/pdf"))
        assertTrue(CompanionViewerDefaults.isScribeType(com.infinifolder.viewer.MediaType.PDF))
        assertEquals("com.infiniscribe",
            CompanionViewerDefaults.defaultPackageFor("manual.pdf", "application/pdf"))
    }
    @Test fun officeDocumentsKeepScribeAsThePrimaryRouteWhenMagicOnlyIdentifiesZip() {
        val docx = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        assertEquals("com.infiniscribe", CompanionViewerDefaults.defaultPackageFor("report.docx", docx))
        assertEquals("com.infiniscribe", CompanionViewerDefaults.defaultPackageFor("report.docx", "application/zip"))
        assertEquals(com.infinifolder.viewer.MediaType.DOCUMENT,
            com.infinifolder.viewer.MediaTypeDetector.detect("report.docx", "application/zip"))
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
        assertTrue(CompanionViewerDefaults.mayAssociateWithoutIntentFilter("com.infiniscribe"))
        assertFalse(CompanionViewerDefaults.mayAssociateWithoutIntentFilter("org.example.player"))
    }

    @Test fun visualParsersWinOverSourceTextAndOldCompanionDefaultsCanBeCleared() {
        assertEquals(com.infinifolder.viewer.MediaType.IMAGE,
            com.infinifolder.viewer.MediaTypeDetector.detect("icon.xpm", "text/plain"))
        assertEquals(com.infinifolder.viewer.MediaType.IMAGE,
            com.infinifolder.viewer.MediaTypeDetector.detect("icon.xcf", "image/x-xcf"))
        assertTrue(CompanionViewerDefaults.prefersFolderImage("icon.xpm", "image/x-xpixmap"))
        assertTrue(CompanionViewerDefaults.prefersFolderImage("icon.xcf", "image/x-xcf"))
        assertNull(CompanionViewerDefaults.defaultPackageFor("icon.xpm", "image/x-xpixmap"))
        assertTrue(CompanionViewerDefaults.hasTextBackupFor("icon.xpm", "image/x-xpixmap"))
    }

    @Test fun imageTypeKeepsImageViewerFallbackWhenPlainSourceIsAlsoDeclared() {
        val imageType = com.infinifolder.viewer.MediaTypeDetector.detect("icon.xpm", "image/x-xpixmap")
        assertEquals(com.infinifolder.viewer.MediaType.IMAGE, imageType)
        assertEquals("com.infinifolder.viewer.ImageViewerActivity",
            com.infinifolder.viewer.ViewerCatalog.activity(imageType))
    }

    @Test fun longPressOffersModuleDeclaredBackupViewers() {
        assertTrue(CompanionViewerDefaults.hasTextBackupFor("icon.xpm", "image/x-xpixmap"))
        assertTrue(CompanionViewerDefaults.hasTextBackupFor("art.ans", "image/x-ansi"))
        assertFalse(CompanionViewerDefaults.hasTextBackupFor("notes.txt", "text/plain"))
        assertTrue(CompanionViewerDefaults.isRawAnsiSource("art.ans", "image/x-ansi"))
        assertFalse(CompanionViewerDefaults.isRawAnsiSource("icon.xpm", "image/x-xpixmap"))
    }

    @Test fun overlappingAnsiViewDefaultsToMediaWhileKeepingScribeAsBackup() {
        assertEquals("com.infinimedia", CompanionViewerDefaults.defaultPackageFor("art.ans", "image/x-ansi"))
    }
}
