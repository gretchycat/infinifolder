package com.infinifolder

import org.junit.Assert.*
import org.junit.Test
import com.infinifolder.archive.ArchiveFormats
import com.infinifolder.mime.MimeRegistry
import com.infinifolder.preview.AudioIconFormats

class CompanionViewerDefaultsTest {
    @Test fun modelsUseMeshEvenWhenProvidersReportTextOrGenericMime() {
        for (extension in io.github.gretchycat.infinemesh.ModelFormats.GCODE_EXTENSIONS + "stl") {
            for (mime in listOf("text/plain", "application/octet-stream")) {
                assertEquals(extension, "com.infinimesh", CompanionViewerDefaults.defaultPackageFor("part.v2.${extension.uppercase()}", mime))
            }
        }
        assertNotEquals("com.infinimesh", CompanionViewerDefaults.defaultPackageFor("game.ngc", "application/x-snk-neo-geo-pocket-rom"))
        assertFalse(CompanionViewerDefaults.isMeshType("game.ngc", "application/x-snk-neo-geo-pocket-rom"))
        assertEquals("com.infinimesh", CompanionViewerDefaults.defaultPackageFor("part", "application/x-gcode"))
        assertEquals("com.infinimesh", CompanionViewerDefaults.defaultPackageFor("part", "model/stl"))
        assertTrue(CompanionViewerDefaults.mayAssociateWithoutIntentFilter("com.infinimesh"))
        assertEquals("com.infiniscribe", CompanionViewerDefaults.defaultPackageFor("notes.txt", "text/plain"))
    }
    @Test fun meshFormatsEnterTheRuntimeMimeAndThumbnailCatalog() {
        val associations = MimeRegistry.associations()
        for (extension in io.github.gretchycat.infinemesh.ModelFormats.GCODE_EXTENSIONS) {
            assertTrue(extension, "text/x-gcode" in MimeRegistry.runtimeAssociations()[extension].orEmpty())
            if (extension != "ngc") assertEquals(extension, "text/x-gcode", associations[extension])
        }
        // NGC is shared with Neo Geo Pocket ROMs and must remain ambiguous without content evidence.
        assertNull(associations["ngc"])
        assertEquals("model/stl", associations["stl"])
        val handler = io.github.gretchycat.infinistream.RuntimeCapabilities.installed().query()
            .first { it.descriptor.library == "libinfinemesh" }.handler
        assertTrue(io.github.gretchycat.infinistream.Operations.THUMBNAIL in handler.describe().operations)
        assertEquals("true", handler.describe().attributes["encodedImageExtractor"])
    }
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
