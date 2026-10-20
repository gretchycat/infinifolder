package com.infinifolder.storage

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalFileStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun store() = LocalFileStore(temporary.newFolder())

    @Test fun rootListingReflectsActualFilesAndDirectoriesIncludingDownload() {
        val store = store()
        File(store.root, "Download").mkdir()
        File(store.root, "custom folder").mkdir()
        File(store.root, "notes.txt").writeText("hello")
        assertEquals(setOf("Download", "custom folder", "notes.txt"), store.list(store.root).map { it.name }.toSet())
    }
    @Test fun traversalAndSymlinksCannotLeaveStorageRoot() {
        val store = store()
        listOf("..", ".", "../outside", "a/b", "a\\b").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { store.resolve(listOf(name)) }
        }
        Files.createSymbolicLink(File(store.root, "link").toPath(), temporary.newFolder().toPath())
        assertThrows(IllegalArgumentException::class.java) { store.resolve(listOf("link")) }
    }
    @Test fun recursiveCopyKeepsBothAndPreservesOriginalContents() {
        val store = store()
        val source = store.createDirectory(store.root, "source")
        File(source, "notes.txt").writeText("original")
        val destination = store.createDirectory(store.root, "destination")
        val existing = store.createDirectory(destination, "source")
        File(existing, "notes.txt").writeText("existing")
        val copied = store.copy(source, destination)
        assertEquals("source (2)", copied.name)
        assertEquals("original", File(copied, "notes.txt").readText())
        assertEquals("original", File(source, "notes.txt").readText())
        assertEquals("existing", File(existing, "notes.txt").readText())
        assertFalse(store.list(destination).any { it.name.startsWith(".infinifolder-copy-") })
    }
    @Test fun cannotCopyIntoOwnDescendantOrDeleteRoot() {
        val store = store()
        val source = store.createDirectory(store.root, "source")
        val child = store.createDirectory(source, "child")
        assertThrows(IllegalArgumentException::class.java) { store.copy(source, child) }
        assertThrows(IllegalArgumentException::class.java) { store.delete(store.root) }
    }
    @Test fun renameNeverReplacesExistingFile() {
        val store = store()
        val source = File(store.root, "source.txt").apply { writeText("source") }
        val destination = File(store.root, "existing.txt").apply { writeText("existing") }
        assertThrows(java.nio.file.FileAlreadyExistsException::class.java) { store.rename(source, destination.name) }
        assertEquals("source", source.readText())
        assertEquals("existing", destination.readText())
    }
    @Test fun failedCopyRemovesTemporaryDataAndRetainsSource() {
        val store = store()
        val source = store.createDirectory(store.root, "source")
        File(source, "a.txt").writeText("retained")
        Files.createSymbolicLink(File(source, "unsupported-link").toPath(), temporary.newFolder().toPath())
        val destination = store.createDirectory(store.root, "destination")
        assertThrows(IllegalArgumentException::class.java) { store.copy(source, destination) }
        assertEquals("retained", File(source, "a.txt").readText())
        assertTrue(store.list(destination).isEmpty())
    }
}
