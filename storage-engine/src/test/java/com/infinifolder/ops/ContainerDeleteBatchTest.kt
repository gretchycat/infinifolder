package com.infinifolder.ops

import android.net.TestUri
import com.infinifolder.model.*
import com.infinifolder.storage.StorageProvider
import com.infinifolder.storage.StorageWriteBatch
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ContainerDeleteBatchTest {
    private fun entry(path: String, directory: Boolean = false, link: Boolean = false) = ResourceEntry(
        ResourceRef("archive", path), ResourceMetadata(path.substringAfterLast('/'),
            if (directory) "vnd.android.document/directory" else "application/octet-stream",
            null, null, directory, isSymbolicLink = link), TestUri("test://$path"))

    private class Store(val entries: MutableMap<String, ResourceEntry>) : StorageProvider {
        override val id = "archive"
        val deleted = mutableListOf<String>()
        val listed = mutableListOf<String>()
        var failDelete: String? = null
        var incompleteFolder: String? = null
        override fun list(parent: ResourceRef): ProviderResult<List<ResourceEntry>> {
            listed += parent.documentId
            return ProviderResult.Success(entries.values.filter {
                it.ref.documentId.substringBeforeLast('/', "") == parent.documentId
            }, isLoading = parent.documentId == incompleteFolder)
        }
        override fun delete(ref: ResourceRef): ProviderResult<Unit> {
            if (ref.documentId == failDelete) return ProviderResult.Failure("Injected delete failure")
            val entry = entries[ref.documentId] ?: return ProviderResult.Failure("Missing entry")
            if (entry.metadata.isDirectory && !entry.metadata.isSymbolicLink && entries.keys.any {
                    it.startsWith("${ref.documentId}/")
                }) return ProviderResult.Failure("Directory not empty")
            deleted += ref.documentId
            entries.remove(ref.documentId)
            return ProviderResult.Success(Unit)
        }
        override fun openRead(ref: ResourceRef) = error("Unexpected read")
        override fun openWrite(ref: ResourceRef) = error("Unexpected write")
        override fun createDirectory(parent: ResourceRef, name: String) = error("Unexpected create")
        override fun createFile(parent: ResourceRef, name: String, mimeType: String) = error("Unexpected create")
        override fun rename(ref: ResourceRef, name: String) = error("Unexpected rename")
        override fun copy(source: ResourceRef, destination: ResourceRef) = error("Unexpected copy")
        override fun move(source: ResourceRef, destination: ResourceRef) = error("Unexpected move")
    }

    private class Router(val original: Store) : StorageProvider by original {
        val opened = mutableListOf<String>()
        val committed = mutableListOf<String>()
        val closed = mutableListOf<String>()
        val working = mutableMapOf<String, Store>()
        var failDelete: String? = null
        var incompleteFolder: String? = null
        var rejectCommit = false
        var unsupported = false
        var parentUnsupported = false
        var rejectCommitFor: String? = null
        var rejectPreparation: String? = null
        override fun backingResource(ref: ResourceRef) = ResourceRef("local", ref.documentId.substringBefore('/') + ".img")
        override fun parent(ref: ResourceRef): ProviderResult<ResourceRef?> = if (parentUnsupported)
            ProviderResult.Unsupported(ResourceCapability.PARENT) else ProviderResult.Success(
                ref.documentId.takeIf { '/' in it }?.substringBeforeLast('/')?.let { ResourceRef("archive", it) })
        override fun beginWriteBatch(destination: ResourceRef, isCancelled: () -> Boolean): ProviderResult<StorageWriteBatch> {
            if (unsupported) return ProviderResult.Unsupported(ResourceCapability.BATCH_WRITE)
            val key = destination.documentId.substringBefore('/')
            if (key == rejectPreparation) return ProviderResult.Failure("Cannot prepare image")
            opened += key
            val snapshot = Store(original.entries.filterKeys { it == key || it.startsWith("$key/") }.toMutableMap())
            snapshot.failDelete = failDelete
            snapshot.incompleteFolder = incompleteFolder
            working[key] = snapshot
            return ProviderResult.Success(object : StorageWriteBatch {
                override val provider = snapshot
                override fun commit() {
                    if (rejectCommit || rejectCommitFor == key) throw IOException("Backing image changed")
                    assertFalse(isCancelled())
                    original.entries.keys.filter { it == key || it.startsWith("$key/") }.toList().forEach(original.entries::remove)
                    original.entries.putAll(snapshot.entries)
                    committed += key
                }
                override fun close() { closed += key }
            })
        }
    }

    private fun store() = Store(listOf(entry("one", true), entry("one/folder", true),
        entry("one/folder/a"), entry("one/folder/b"), entry("one/keep"),
        entry("two", true), entry("two/file")).associateBy { it.ref.documentId }.toMutableMap())

    @Test fun recursiveSelectionAndDuplicatesUseOneSnapshotAndOnePublication() {
        val original = store()
        val router = Router(original)
        FileOperations.batchDelete(listOf(original.entries.getValue("one/folder"), original.entries.getValue("one/folder/a")),
            router, { if (it.message?.startsWith("Removing") == true) assertTrue(original.entries.containsKey("one/folder/a")) }, { false })
        assertEquals(listOf("one"), router.opened)
        assertEquals(listOf("one"), router.committed)
        assertEquals(listOf("one"), router.closed)
        assertEquals(listOf("one/folder/a", "one/folder/b", "one/folder"), router.working.getValue("one").deleted)
        assertTrue(original.deleted.isEmpty())
        assertFalse(original.entries.containsKey("one/folder"))
        assertTrue(original.entries.containsKey("one/keep"))
    }

    @Test fun mutationFailureDiscardsEarlierStagedDeletes() {
        val original = store(); val before = original.entries.toMap()
        val router = Router(original).apply { failDelete = "one/folder/b" }
        assertThrows(IOException::class.java) {
            FileOperations.batchDelete(listOf(original.entries.getValue("one/folder")), router, {}, { false })
        }
        assertEquals(before, original.entries)
        assertEquals(listOf("one/folder/a"), router.working.getValue("one").deleted)
        assertTrue(router.committed.isEmpty())
        assertEquals(listOf("one"), router.closed)
    }

    @Test fun cancellationDiscardsStagedDeletes() {
        val original = store(); val before = original.entries.toMap(); val router = Router(original)
        var cancelled = false
        assertThrows(InterruptedException::class.java) {
            FileOperations.batchDelete(listOf(original.entries.getValue("one/folder")), router,
                { if (it.message?.startsWith("Removing") == true) cancelled = true }, { cancelled })
        }
        assertEquals(before, original.entries)
        assertTrue(router.committed.isEmpty())
        assertEquals(listOf("one"), router.closed)
    }

    @Test fun incompleteListingPreventsAllDeletion() {
        val original = store(); val before = original.entries.toMap()
        val router = Router(original).apply { incompleteFolder = "one/folder" }
        assertThrows(IOException::class.java) {
            FileOperations.batchDelete(listOf(original.entries.getValue("one/folder")), router, {}, { false })
        }
        assertEquals(before, original.entries)
        assertTrue(router.working.getValue("one").deleted.isEmpty())
        assertTrue(router.committed.isEmpty())
        assertEquals(listOf("one"), router.closed)
    }

    @Test fun revisionFailureKeepsOriginalAndClosesSession() {
        val original = store(); val before = original.entries.toMap()
        val router = Router(original).apply { rejectCommit = true }
        assertThrows(IOException::class.java) {
            FileOperations.batchDelete(listOf(original.entries.getValue("one/folder")), router, {}, { false })
        }
        assertEquals(before, original.entries)
        assertTrue(router.committed.isEmpty())
        assertEquals(listOf("one"), router.closed)
    }

    @Test fun differentContainersHaveSeparateSessions() {
        val original = store(); val router = Router(original)
        FileOperations.batchDelete(listOf(original.entries.getValue("one/folder/a"), original.entries.getValue("two/file")), router, {}, { false })
        assertEquals(listOf("one", "two"), router.opened)
        assertEquals(listOf("one", "two"), router.committed)
        assertEquals(listOf("two", "one"), router.closed)
        assertTrue(original.entries.containsKey("one/folder/b"))
    }

    @Test fun secondPreparationFailureClosesFirstSessionWithoutDeleting() {
        val original = store(); val before = original.entries.toMap()
        val router = Router(original).apply { rejectPreparation = "two" }
        assertThrows(IOException::class.java) {
            FileOperations.batchDelete(listOf(original.entries.getValue("one/folder/a"), original.entries.getValue("two/file")), router, {}, { false })
        }
        assertEquals(before, original.entries)
        assertTrue(router.working.getValue("one").deleted.isEmpty())
        assertEquals(listOf("one"), router.closed)
    }

    @Test fun symbolicDirectoryLinksAreDeletedWithoutTraversingTargets() {
        val original = store(); val link = entry("one/link", directory = true, link = true)
        original.entries[link.ref.documentId] = link
        val router = Router(original)
        FileOperations.batchDelete(listOf(link), router, {}, { false })
        assertTrue(router.working.getValue("one").listed.isEmpty())
        assertTrue(original.entries.containsKey("one/folder/a"))
        assertFalse(original.entries.containsKey("one/link"))
    }

    @Test fun unsupportedBatchKeepsNormalDeleteBehavior() {
        val original = store(); val router = Router(original).apply { unsupported = true }
        FileOperations.batchDelete(listOf(original.entries.getValue("one/folder/a")), router, {}, { false })
        assertEquals(listOf("one/folder/a"), original.deleted)
        assertTrue(router.opened.isEmpty())
    }

    @Test fun containerRootCannotBeDeleted() {
        val original = store(); val before = original.entries.toMap(); val router = Router(original)
        assertThrows(IOException::class.java) {
            FileOperations.batchDelete(listOf(original.entries.getValue("one")), router, {}, { false })
        }
        assertEquals(before, original.entries)
        assertTrue(router.opened.isEmpty())
    }

    @Test fun missingOptionalParentHookKeepsNormalDeletion() {
        val original = store(); val router = Router(original).apply { parentUnsupported = true }
        FileOperations.batchDelete(listOf(original.entries.getValue("one/folder/a")), router, {}, { false })
        assertEquals(listOf("one/folder/a"), original.deleted)
        assertTrue(router.opened.isEmpty())
    }

    @Test fun laterPublicationFailureReportsEarlierCommittedContainer() {
        val original = store(); val router = Router(original).apply { rejectCommitFor = "two" }
        val error = assertThrows(IOException::class.java) {
            FileOperations.batchDelete(listOf(original.entries.getValue("one/folder/a"), original.entries.getValue("two/file")), router, {}, { false })
        }
        assertTrue(error.message!!.contains("Earlier deletions"))
        assertFalse(original.entries.containsKey("one/folder/a"))
        assertTrue(original.entries.containsKey("two/file"))
        assertEquals(listOf("one"), router.committed)
        assertEquals(listOf("two", "one"), router.closed)
    }
}
