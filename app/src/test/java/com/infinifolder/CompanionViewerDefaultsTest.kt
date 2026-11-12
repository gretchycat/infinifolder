package com.infinifolder

import org.junit.Assert.*
import org.junit.Test

class CompanionViewerDefaultsTest {
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
}
