package com.infinifolder.viewer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.Closeable
class StagedSourceTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun borrowedOriginalSurvivesCleanup() {
        val original = temporary.newFile("media_original.epub").apply { writeText("original") }
        MediaSourceAdapter.StagedSource(original, null).close()
        assertEquals("original", original.readText())
        val owned = temporary.newFile("copy.epub")
        MediaSourceAdapter.StagedSource(owned, Closeable { owned.delete() }).close()
        assertFalse(owned.exists())
    }
}
