package com.infinifolder.ops

import android.net.TestUri
import com.infinifolder.model.ProviderResult
import com.infinifolder.model.ResourceCapability
import com.infinifolder.model.ResourceEntry
import com.infinifolder.model.ResourceMetadata
import com.infinifolder.model.ResourceRef
import com.infinifolder.storage.StorageProvider
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

class FileOperationsTest {

    private class MockStorage(
        val files: MutableMap<String, ByteArray> = mutableMapOf(),
        val dirs: MutableMap<String, MutableList<ResourceEntry>> = mutableMapOf()
    ) : StorageProvider {
        override val id: String = "mock"

        val deletedRefs = mutableListOf<ResourceRef>()
        val createdFiles = mutableListOf<String>()
        val listingResults = mutableMapOf<String, ProviderResult<List<ResourceEntry>>>()
        var failDelete: String? = null
        var corruptWrites = false

        override fun list(parent: ResourceRef): ProviderResult<List<ResourceEntry>> =
            listingResults[parent.documentId] ?: ProviderResult.Success(dirs[parent.documentId] ?: emptyList())

        override fun openRead(ref: ResourceRef): ProviderResult<InputStream> {
            val bytes = files[ref.documentId] ?: return ProviderResult.Failure("Not found")
            return ProviderResult.Success(ByteArrayInputStream(bytes))
        }

        override fun openWrite(ref: ResourceRef): ProviderResult<OutputStream> {
            val out = object : ByteArrayOutputStream() {
                override fun close() {
                    super.close()
                    files[ref.documentId] = if (corruptWrites && ref.documentId.startsWith("dest/")) byteArrayOf(0) else toByteArray()
                }
            }
            return ProviderResult.Success(out)
        }

        override fun createDirectory(parent: ResourceRef, name: String): ProviderResult<ResourceEntry> {
            val docId = "${parent.documentId}/$name"
            val entry = ResourceEntry(ResourceRef(id, docId), ResourceMetadata(name, "vnd.android.document/directory", null, 0L, true), TestUri("mock://$docId"))
            dirs.getOrPut(parent.documentId) { mutableListOf() }.add(entry)
            dirs.getOrPut(docId) { mutableListOf() }
            return ProviderResult.Success(entry)
        }

        override fun createFile(parent: ResourceRef, name: String, mimeType: String): ProviderResult<ResourceEntry> {
            val docId = "${parent.documentId}/$name"
            val entry = ResourceEntry(ResourceRef(id, docId), ResourceMetadata(name, mimeType, 0L, 0L, false), TestUri("mock://$docId"))
            dirs.getOrPut(parent.documentId) { mutableListOf() }.add(entry)
            files[docId] = ByteArray(0)
            createdFiles.add(docId)
            return ProviderResult.Success(entry)
        }

        override fun delete(ref: ResourceRef): ProviderResult<Unit> {
            if (ref.documentId == failDelete) return ProviderResult.Failure("Removal denied")
            deletedRefs.add(ref)
            files.remove(ref.documentId)
            dirs.remove(ref.documentId)
            dirs.values.forEach { list -> list.removeAll { it.ref == ref } }
            return ProviderResult.Success(Unit)
        }

        override fun rename(ref: ResourceRef, name: String) = ProviderResult.Unsupported(ResourceCapability.RENAME)
        override fun copy(source: ResourceRef, destination: ResourceRef) = ProviderResult.Unsupported(ResourceCapability.COPY)
        override fun move(source: ResourceRef, destination: ResourceRef) = ProviderResult.Unsupported(ResourceCapability.MOVE)
    }

    private fun entry(path: String, directory: Boolean = false, size: Long? = null, link: Boolean = false) =
        ResourceEntry(ResourceRef("mock", path), ResourceMetadata(path.substringAfterLast('/'),
            if (directory) "vnd.android.document/directory" else "text/plain", size, 0L, directory,
            isSymbolicLink = link), TestUri("mock://$path"))

    private fun move(storage: MockStorage, source: ResourceEntry, warnings: MutableList<String> = mutableListOf()) = FileOperations.batchTransfer(
        listOf(source), TestUri("mock://dest"), true, storage, { ResourceRef("mock", "dest") }, {}, { false }, onWarning = warnings::add)

    @Test fun incompleteListingsNeverDeleteOrCreateAnything() {
        val source = entry("root/folder", directory = true)
        val results = listOf<ProviderResult<List<ResourceEntry>>>(
            ProviderResult.Failure("Offline"),
            ProviderResult.Unsupported(ResourceCapability.LIST),
            ProviderResult.Success(emptyList(), isLoading = true),
            ProviderResult.Success(emptyList(), notice = "Partial listing"))
        for (result in results) {
            val storage = MockStorage()
            storage.listingResults[source.ref.documentId] = result
            val warnings = mutableListOf<String>()
            move(storage, source, warnings)
            assertTrue(warnings.any { it.contains("Skipped") })
            assertTrue(storage.deletedRefs.isEmpty())
            assertTrue(storage.createdFiles.isEmpty())
        }
    }

    @Test fun destinationCollisionKeepsExistingBytes() {
        val source = entry("root/file.txt", size = 3)
        val existing = entry("dest/file.txt", size = 3)
        val storage = MockStorage(mutableMapOf("root/file.txt" to "new".toByteArray(),
            "dest/file.txt" to "old".toByteArray()), mutableMapOf("dest" to mutableListOf(existing)))
        move(storage, source)
        assertEquals("old", String(storage.files.getValue("dest/file.txt")))
        assertEquals("new", String(storage.files.getValue("dest/file (2).txt")))
    }

    @Test fun corruptDestinationKeepsSourceAndRemovesPartialCopy() {
        val source = entry("root/file.txt", size = 3)
        val storage = MockStorage(mutableMapOf("root/file.txt" to "new".toByteArray()))
        storage.corruptWrites = true
        assertThrows(java.io.IOException::class.java) { move(storage, source) }
        assertEquals("new", String(storage.files.getValue("root/file.txt")))
        assertFalse(storage.files.containsKey("dest/file.txt"))
        assertFalse(storage.deletedRefs.contains(source.ref))
    }

    @Test fun shortReadRetainsOriginal() {
        val source = entry("root/file.txt", size = 100)
        val storage = MockStorage(mutableMapOf("root/file.txt" to "short".toByteArray()))
        val warnings = mutableListOf<String>()
        move(storage, source, warnings)
        assertTrue(warnings.any { it.contains("Skipped") })
        assertFalse(storage.files.containsKey("dest/file.txt"))
        assertTrue(storage.files.containsKey("root/file.txt"))
        assertFalse(storage.files.containsKey("dest/file.txt"))
    }

    @Test fun failedRemovalReportsPartialMoveAndRetainsBothCopies() {
        val source = entry("root/file.txt", size = 3)
        val storage = MockStorage(mutableMapOf("root/file.txt" to "new".toByteArray()))
        storage.failDelete = source.ref.documentId
        assertThrows(java.io.IOException::class.java) { move(storage, source) }
        assertEquals("new", String(storage.files.getValue("root/file.txt")))
        assertEquals("new", String(storage.files.getValue("dest/file.txt")))
    }

    @Test fun directoryLinksCannotEnterRecursiveTransfer() {
        val source = entry("root/link", directory = true, link = true)
        val storage = MockStorage()
        assertThrows(java.io.IOException::class.java) { move(storage, source) }
        assertTrue(storage.deletedRefs.isEmpty())
    }

    @Test fun recursiveProviderCycleIsRejectedBeforeCreation() {
        val source = entry("root/folder", directory = true)
        val storage = MockStorage(dirs = mutableMapOf("root/folder" to mutableListOf(source)))
        assertThrows(java.io.IOException::class.java) { move(storage, source) }
        assertTrue(storage.createdFiles.isEmpty())
        assertTrue(storage.deletedRefs.isEmpty())
    }

    @Test
    fun testCalculateTotalBytes() {
        val f1 = ResourceEntry(ResourceRef("mock", "root/f1.txt"), ResourceMetadata("f1.txt", "text/plain", 100L, 0L, false), TestUri("mock://root/f1.txt"))
        val f2 = ResourceEntry(ResourceRef("mock", "root/f2.txt"), ResourceMetadata("f2.txt", "text/plain", 250L, 0L, false), TestUri("mock://root/f2.txt"))
        val storage = MockStorage()

        val total = FileOperations.calculateTotalBytes(listOf(f1, f2), storage)
        assertEquals(350L, total)
    }

    @Test
    fun testBatchTransferCopy() {
        val f1 = ResourceEntry(ResourceRef("mock", "root/f1.txt"), ResourceMetadata("f1.txt", "text/plain", 12L, 0L, false), TestUri("mock://root/f1.txt"))
        val storage = MockStorage(
            files = mutableMapOf("root/f1.txt" to "Hello World!".toByteArray()),
            dirs = mutableMapOf("dest" to mutableListOf())
        )

        val updates = mutableListOf<OperationUpdate>()
        FileOperations.batchTransfer(
            entries = listOf(f1),
            destinationFolderUri = TestUri("mock://dest"),
            move = false,
            provider = storage,
            resourceRef = { ResourceRef("mock", "dest") },
            update = { updates.add(it) },
            isCancelled = { false }
        )

        // Verify destination file was created and written
        assertTrue(storage.files.containsKey("dest/f1.txt"))
        assertEquals("Hello World!", String(storage.files["dest/f1.txt"]!!))
        // Verify source was NOT deleted because move = false
        assertTrue(storage.files.containsKey("root/f1.txt"))
        assertFalse(updates.isEmpty())
    }

    @Test
    fun testBatchTransferMove() {
        val f1 = ResourceEntry(ResourceRef("mock", "root/f1.txt"), ResourceMetadata("f1.txt", "text/plain", 12L, 0L, false), TestUri("mock://root/f1.txt"))
        val storage = MockStorage(
            files = mutableMapOf("root/f1.txt" to "Hello World!".toByteArray()),
            dirs = mutableMapOf("dest" to mutableListOf())
        )

        FileOperations.batchTransfer(
            entries = listOf(f1),
            destinationFolderUri = TestUri("mock://dest"),
            move = true,
            provider = storage,
            resourceRef = { ResourceRef("mock", "dest") },
            update = {},
            isCancelled = { false }
        )

        // Verify destination file exists
        assertTrue(storage.files.containsKey("dest/f1.txt"))
        // Verify source was deleted because move = true
        assertFalse(storage.files.containsKey("root/f1.txt"))
        assertTrue(storage.deletedRefs.any { it.documentId == "root/f1.txt" })
    }

    @Test
    fun testCancellationCleanup() {
        val f1 = ResourceEntry(ResourceRef("mock", "root/large.bin"), ResourceMetadata("large.bin", "application/octet-stream", 1000L, 0L, false), TestUri("mock://root/large.bin"))
        val storage = MockStorage(
            files = mutableMapOf("root/large.bin" to ByteArray(1000) { 1 }),
            dirs = mutableMapOf("dest" to mutableListOf())
        )

        var cancelNext = false
        try {
            FileOperations.batchTransfer(
                entries = listOf(f1),
                destinationFolderUri = TestUri("mock://dest"),
                move = true,
                provider = storage,
                resourceRef = { ResourceRef("mock", "dest") },
                update = { cancelNext = true },
                isCancelled = { cancelNext }
            )
            fail("Expected InterruptedException")
        } catch (_: InterruptedException) {
            // Expected
        }

        // Verify destination partial file was deleted/cleaned up
        assertFalse(storage.files.containsKey("dest/large.bin"))
        // Verify source was NOT deleted
        assertTrue(storage.files.containsKey("root/large.bin"))
    }

    @Test
    fun testBatchDelete() {
        val f1 = ResourceEntry(ResourceRef("mock", "root/f1.txt"), ResourceMetadata("f1.txt", "text/plain", 10L, 0L, false), TestUri("mock://root/f1.txt"))
        val f2 = ResourceEntry(ResourceRef("mock", "root/f2.txt"), ResourceMetadata("f2.txt", "text/plain", 20L, 0L, false), TestUri("mock://root/f2.txt"))
        val storage = MockStorage(
            files = mutableMapOf("root/f1.txt" to ByteArray(10), "root/f2.txt" to ByteArray(20))
        )

        val updates = mutableListOf<OperationUpdate>()
        FileOperations.batchDelete(
            entries = listOf(f1, f2),
            provider = storage,
            update = { updates.add(it) },
            isCancelled = { false }
        )

        assertEquals(2, storage.deletedRefs.size)
        assertTrue(storage.files.isEmpty())
        assertEquals(2, updates.last().itemsProcessed)
    }

    @Test
    fun testOperationSnapshotRateAndProgress() {
        val opBytes = OperationSnapshot(
            id = "1",
            label = "Copying",
            state = OperationState.RUNNING,
            bytes = 50L * 1024L * 1024L,
            totalBytes = 100L * 1024L * 1024L,
            bytesPerSecond = 10L * 1024L * 1024L
        )
        assertEquals(50, opBytes.progressPercent)
        assertEquals("10.0 MB/s", opBytes.formatRate())

        val opItems = OperationSnapshot(
            id = "2",
            label = "Deleting",
            state = OperationState.RUNNING,
            itemsProcessed = 3,
            totalItems = 6
        )
        assertEquals(50, opItems.progressPercent)
    }

    @Test
    fun testCalculateFolderStats() {
        val f1 = ResourceEntry(ResourceRef("mock", "root/dir/f1.txt"), ResourceMetadata("f1.txt", "text/plain", 1000L, 0L, false), TestUri("mock://root/dir/f1.txt"))
        val f2 = ResourceEntry(ResourceRef("mock", "root/dir/sub/f2.txt"), ResourceMetadata("f2.txt", "text/plain", 2500L, 0L, false), TestUri("mock://root/dir/sub/f2.txt"))
        val subDir = ResourceEntry(ResourceRef("mock", "root/dir/sub"), ResourceMetadata("sub", "vnd.android.document/directory", null, 0L, true), TestUri("mock://root/dir/sub"))
        val rootDir = ResourceEntry(ResourceRef("mock", "root/dir"), ResourceMetadata("dir", "vnd.android.document/directory", null, 0L, true), TestUri("mock://root/dir"))

        val storage = MockStorage(
            dirs = mutableMapOf(
                "root/dir" to mutableListOf(f1, subDir),
                "root/dir/sub" to mutableListOf(f2)
            )
        )

        val stats = FileOperations.calculateFolderStats(listOf(rootDir), storage)
        assertEquals(3500L, stats.totalBytes)
        assertEquals(2, stats.fileCount)
        assertEquals(1, stats.directoryCount)
    }

    @Test
    fun testCalculateFolderStatsCancellation() {
        val f1 = ResourceEntry(ResourceRef("mock", "root/dir/f1.txt"), ResourceMetadata("f1.txt", "text/plain", 1000L, 0L, false), TestUri("mock://root/dir/f1.txt"))
        val rootDir = ResourceEntry(ResourceRef("mock", "root/dir"), ResourceMetadata("dir", "vnd.android.document/directory", null, 0L, true), TestUri("mock://root/dir"))

        val storage = MockStorage(
            dirs = mutableMapOf(
                "root/dir" to mutableListOf(f1)
            )
        )

        val stats = FileOperations.calculateFolderStats(listOf(rootDir), storage, isCancelled = { true })
        assertEquals(0L, stats.totalBytes)
        assertEquals(0, stats.fileCount)
    }

    @Test
    fun testOperationCoordinatorSequentialQueueing() {
        val coordinator = OperationCoordinator(concurrency = 1)
        val latch1Started = java.util.concurrent.CountDownLatch(1)
        val release1 = java.util.concurrent.CountDownLatch(1)
        val latch2Completed = java.util.concurrent.CountDownLatch(1)

        val id1 = coordinator.submit("Op 1", null, 1) { _, _ ->
            latch1Started.countDown()
            release1.await(5, java.util.concurrent.TimeUnit.SECONDS)
        }
        val id2 = coordinator.submit("Op 2", null, 1) { _, _ ->
            latch2Completed.countDown()
        }

        assertTrue(latch1Started.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val activeWhile1Running = coordinator.activeSnapshots()
        assertEquals(2, activeWhile1Running.size)
        assertEquals(id1, activeWhile1Running[0].id)
        assertEquals(OperationState.RUNNING, activeWhile1Running[0].state)
        assertEquals(id2, activeWhile1Running[1].id)
        assertEquals(OperationState.QUEUED, activeWhile1Running[1].state)

        release1.countDown()
        assertTrue(latch2Completed.await(5, java.util.concurrent.TimeUnit.SECONDS))
    }
}
