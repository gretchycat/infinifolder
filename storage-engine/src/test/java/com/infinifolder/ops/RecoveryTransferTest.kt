package com.infinifolder.ops

import android.net.TestUri
import com.infinifolder.model.*
import com.infinifolder.storage.StorageProvider
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Recovery policy must run before destination creation, independent of the menu used. */
class RecoveryTransferTest {
    private fun entry(path: String, directory: Boolean = false, note: String? = null, readable: Boolean = true) = ResourceEntry(
        ResourceRef("mock", path), ResourceMetadata(path.substringAfterLast('/'), "application/octet-stream",
            if (directory) null else 4L, null, directory, canRead = readable, recoveryNote = note,
            readError = if (readable) null else "Allocated clusters"), TestUri("mock://$path"))
    private inner class Store : StorageProvider {
        override val id = "mock"
        val bytes = mutableMapOf("recovery/file.bin" to "data".toByteArray(), "nested/file.bin" to "data".toByteArray())
        val children = mutableMapOf<String, List<ResourceEntry>>()
        val origins = mutableMapOf<String, ResourceRef>()
        val backing = mutableMapOf<String, ResourceRef>()
        val created = mutableListOf<String>()
        var notice: String? = null
        var loading = false
        var corrupt = false
        var failRead = false
        var failWrite = false
        var failCreate = false
        var capacity: Long? = null
        var failDelete = false
        var onWrite: (() -> Unit)? = null
        var failReportCreate = false
        var failReportWrite = false
        var throwDestinationOpen = false
        var corruptReport = false
        var badRecoveryDigest = false
        var throwPartialRead = false
        override fun openPartialRecovery(ref: ResourceRef): ProviderResult<RecoveryExport> {
            if (throwPartialRead) throw IOException("Injected partial staging failure")
            val payload = bytes[ref.documentId] ?: return ProviderResult.Failure("Missing partial source")
            val digest = if (badRecoveryDigest) "0".repeat(64) else java.security.MessageDigest.getInstance("SHA-256").digest(payload)
                .joinToString("") { "%02x".format(it) }
            val report = RecoveryReport(ref.documentId, payload.size.toLong(), "FAT16", listOf(
                RecoveryRange(0, payload.size.toLong(), RecoveryGrade.POSSIBLY_GOOD, RecoveryDisposition.COPIED_UNVERIFIED, "test source")
            ), digest, "Unverified")
            return ProviderResult.Success(RecoveryExport(payload.inputStream(), report))
        }
        override fun fileSystemInfo(ref: ResourceRef): ProviderResult<com.infinifolder.storage.FileSystemInfo> =
            capacity?.let { ProviderResult.Success(com.infinifolder.storage.FileSystemInfo("test", maxFileSizeBytes = null, evidence = "Injected test capacity", availableBytes = it)) }
                ?: ProviderResult.Unsupported(ResourceCapability.FILESYSTEM_INFO)
        override fun recoverySource(ref: ResourceRef) = origins[ref.documentId]
        override fun backingResource(ref: ResourceRef) = backing[ref.documentId]
        override fun list(parent: ResourceRef) = ProviderResult.Success(children[parent.documentId].orEmpty(), isLoading = loading && parent.documentId == "recovery", notice = if (parent.documentId == "recovery") notice else null)
        override fun openRead(ref: ResourceRef): ProviderResult<InputStream> =
            if (failRead && ref.documentId.startsWith("recovery/")) ProviderResult.Failure("Injected source read failure")
            else bytes[ref.documentId]?.let { ProviderResult.Success(it.inputStream()) } ?: ProviderResult.Failure("Missing file")
        override fun openWrite(ref: ResourceRef): ProviderResult<OutputStream> {
            if (throwDestinationOpen) throw IOException("Injected destination open exception")
            if (failWrite || failReportWrite && ref.documentId.endsWith(".recovery.json")) return ProviderResult.Failure("Injected destination failure")
            return ProviderResult.Success(object : ByteArrayOutputStream() {
                override fun write(b: ByteArray, off: Int, len: Int) { super.write(b, off, len); onWrite?.invoke() }
                override fun close() { bytes[ref.documentId] = if (corrupt || corruptReport && ref.documentId.endsWith(".recovery.json")) "bad!".toByteArray() else toByteArray() }
            })
        }
        override fun createFile(parent: ResourceRef, name: String, mimeType: String): ProviderResult<ResourceEntry> {
            if (failCreate || failReportCreate && name.endsWith(".recovery.json")) return ProviderResult.Failure("Destination full")
            val path = "${parent.documentId}/$name"
            if (path in bytes) return ProviderResult.Failure("Already exists")
            created += path; bytes[path] = byteArrayOf()
            return ProviderResult.Success(entry(path))
        }
        override fun createDirectory(parent: ResourceRef, name: String): ProviderResult<ResourceEntry> {
            val path = "${parent.documentId}/$name"; created += path
            return ProviderResult.Success(entry(path, true))
        }
        override fun delete(ref: ResourceRef): ProviderResult<Unit> { if (failDelete) return ProviderResult.Failure("Injected cleanup failure"); bytes.remove(ref.documentId); return ProviderResult.Success(Unit) }
        override fun rename(ref: ResourceRef, name: String) = ProviderResult.Unsupported(ResourceCapability.RENAME)
        override fun copy(source: ResourceRef, destination: ResourceRef) = ProviderResult.Unsupported(ResourceCapability.COPY)
        override fun move(source: ResourceRef, destination: ResourceRef) = ProviderResult.Unsupported(ResourceCapability.MOVE)
    }
    private val origin = ResourceRef("mock", "disk.img")
    private fun store() = Store().apply {
        origins["recovery/file.bin"] = origin
        origins["recovery"] = origin
        origins["nested/file.bin"] = origin
    }
    private fun transfer(store: Store, sources: List<ResourceEntry> = listOf(entry("recovery/file.bin", note = "Unverified deleted-file candidate")),
                         target: String = "dest", move: Boolean = false, links: Boolean = false,
                         warnings: MutableList<String> = mutableListOf(), cancelled: () -> Boolean = { false }, partial: Boolean = false) =
        FileOperations.batchTransfer(sources, TestUri("mock://$target"), move, store,
            { ResourceRef("mock", target) }, {}, cancelled, links, warnings::add, partialRecovery = partial)

    @Test fun originalImageDestinationIsRejectedBeforeCreation() {
        val store = store().apply { backing["inside"] = origin }
        assertThrows(IOException::class.java) { transfer(store, target = "inside") }
        assertTrue(store.created.isEmpty())
        assertEquals("data", String(store.bytes.getValue("recovery/file.bin")))
    }
    @Test fun enclosingArchiveDestinationIsAlsoRejected() {
        val store = store().apply {
            backing["disk.img"] = ResourceRef("mock", "outer.zip")
            backing["inside-outer"] = ResourceRef("mock", "outer.zip")
        }
        assertThrows(IOException::class.java) { transfer(store, target = "inside-outer") }
        assertTrue(store.created.isEmpty())
    }
    @Test fun nestedArchiveReadKeepsItsRecoveryOrigin() {
        val store = store().apply { backing["inside"] = origin }
        assertThrows(IOException::class.java) { transfer(store, sources = listOf(entry("nested/file.bin")), target = "inside") }
        assertTrue(store.created.isEmpty())
    }
    @Test fun recoveryMoveIsRejected() {
        val store = store()
        assertThrows(IOException::class.java) { transfer(store, move = true) }
        assertTrue(store.created.isEmpty())
    }
    @Test fun shortcutCreationCannotBypassSourceProtection() {
        val store = store().apply { backing["inside"] = origin }
        assertThrows(IOException::class.java) { transfer(store, target = "inside", links = true) }
        assertTrue(store.created.isEmpty())
    }
    @Test fun unresolvedRecoveryOriginFailsClosed() {
        val store = Store()
        assertThrows(IOException::class.java) { transfer(store) }
        assertTrue(store.created.isEmpty())
    }
    @Test fun cyclicRecoveryDependencyFailsClosed() {
        val store = store().apply { backing["disk.img"] = ResourceRef("mock", "outer.zip"); backing["outer.zip"] = origin }
        assertThrows(IOException::class.java) { transfer(store) }
        assertTrue(store.created.isEmpty())
    }
    @Test fun mixedSelectionUnsafeMemberStopsTheEntireTransferBeforeWrites() {
        val store = store().apply { backing["inside"] = origin }
        assertThrows(IOException::class.java) {
            transfer(store, listOf(entry("ordinary.bin"), entry("recovery/file.bin")), target = "inside")
        }
        assertTrue(store.created.isEmpty())
    }
    @Test fun verifiedCopyStillReportsUnverifiedOriginalContents() {
        val store = store(); val warnings = mutableListOf<String>()
        transfer(store, warnings = warnings)
        assertEquals("data", String(store.bytes.getValue("dest/file.bin")))
        assertEquals("data", String(store.bytes.getValue("recovery/file.bin")))
        assertTrue(warnings.single().contains("unverified"))
    }
    @Test fun recoveryFoldersAllowInformationalNoticesAndSkipBlockedChildren() {
        val good = entry("recovery/file.bin", note = "Unverified")
        val bad = entry("recovery/blocked.bin", note = "Unverified", readable = false)
        val store = store().apply {
            origins[bad.ref.documentId] = origin
            children["recovery"] = listOf(good, bad)
            notice = "Unverified candidates; some clusters are allocated"
        }
        val warnings = mutableListOf<String>()
        transfer(store, sources = listOf(entry("recovery", directory = true)), warnings = warnings)
        assertEquals("data", String(store.bytes.getValue("dest/recovery/file.bin")))
        assertFalse(store.bytes.containsKey("dest/recovery/blocked.bin"))
        assertTrue(warnings.any { it.contains("Skipped") })
        assertTrue(warnings.any { it.contains("unverified") })
    }
    @Test fun loadingRecoveryFolderIsNotTreatedAsComplete() {
        val store = store().apply { loading = true }
        val warnings = mutableListOf<String>()
        transfer(store, sources = listOf(entry("recovery", directory = true)), warnings = warnings)
        assertTrue(store.created.isEmpty())
        assertTrue(warnings.any { it.contains("Skipped") })
    }
    @Test fun cancellationBeforeCreationLeavesSourceAndDestinationAlone() {
        val store = store()
        assertThrows(InterruptedException::class.java) { transfer(store, cancelled = { true }) }
        assertTrue(store.created.isEmpty())
    }
    @Test fun failedSourceStagingRemovesTheEmptyDestination() {
        val store = store().apply { failRead = true }; val warnings = mutableListOf<String>()
        transfer(store, warnings = warnings)
        assertFalse(store.bytes.containsKey("dest/file.bin"))
        assertEquals("data", String(store.bytes.getValue("recovery/file.bin")))
        assertTrue(warnings.any { it.contains("Skipped") })
    }
    @Test fun failedDestinationRemovesPartialCopy() {
        val store = store().apply { failWrite = true }
        assertThrows(IllegalStateException::class.java) { transfer(store) }
        assertFalse(store.bytes.containsKey("dest/file.bin"))
        assertEquals("data", String(store.bytes.getValue("recovery/file.bin")))
    }
    @Test fun failedDestinationVerificationKeepsOriginal() {
        val store = store().apply { corrupt = true }
        assertThrows(IOException::class.java) { transfer(store) }
        assertFalse(store.bytes.containsKey("dest/file.bin"))
        assertEquals("data", String(store.bytes.getValue("recovery/file.bin")))
    }
    @Test fun fullDestinationNeverCreatesAFile() {
        val store = store().apply { failCreate = true }
        assertThrows(IllegalStateException::class.java) { transfer(store) }
        assertTrue(store.created.isEmpty())
    }
    @Test fun independentDestinationCanShareTheOuterLocalFilesystem() {
        val store = store().apply { backing["disk.img"] = ResourceRef("mock", "outer.zip") }
        transfer(store)
        assertEquals("data", String(store.bytes.getValue("dest/file.bin")))
    }
    @Test fun multipleCandidatesAreCopiedAndEachRemainsUnverified() {
        val store = store(); val warnings = mutableListOf<String>()
        val sources = (1..3).map { n -> entry("recovery/file$n.bin", note = "Unverified").also {
            store.bytes[it.ref.documentId] = "data".toByteArray(); store.origins[it.ref.documentId] = origin
        } }
        transfer(store, sources = sources, warnings = warnings)
        assertEquals(3, store.created.size)
        assertEquals(3, warnings.count { it.contains("unverified") })
        sources.forEach { assertEquals("data", String(store.bytes.getValue(it.ref.documentId))) }
    }
    @Test fun confirmedInsufficientCapacityStopsBeforeCreation() {
        val store = store().apply { capacity = 0 }
        assertThrows(IOException::class.java) { transfer(store) }
        assertTrue(store.created.isEmpty())
    }
    @Test fun cancellationDuringWritingRemovesPartialDestination() {
        var cancelled = false
        val store = store().apply { onWrite = { cancelled = true } }
        assertThrows(InterruptedException::class.java) { transfer(store, cancelled = { cancelled }) }
        assertFalse(store.bytes.containsKey("dest/file.bin"))
        assertEquals("data", String(store.bytes.getValue("recovery/file.bin")))
    }
    @Test fun failedCleanupIsReportedAndNeverDeletesTheSource() {
        val store = store().apply { failRead = true; failDelete = true }
        val error = assertThrows(IOException::class.java) { transfer(store) }
        assertTrue(error.message!!.contains("cleanup failed"))
        assertTrue(store.bytes.containsKey("dest/file.bin"))
        assertEquals("data", String(store.bytes.getValue("recovery/file.bin")))
    }

    private fun partialSource(readable: Boolean = false) = entry("recovery/file.bin", note = "Unverified", readable = readable)
        .let { it.copy(metadata = it.metadata.copy(partialRecoveryAvailable = true)) }
    @Test fun explicitPartialRecoveryCanExportBlockedSourceWithVerifiedSidecar() {
        val store = store(); val warnings = mutableListOf<String>()
        transfer(store, sources = listOf(partialSource()), partial = true, warnings = warnings)
        assertEquals("data", String(store.bytes.getValue("dest/file.partial.bin")))
        val report = org.json.JSONObject(String(store.bytes.getValue("dest/file.partial.bin.recovery.json")))
        assertEquals("file.partial.bin", report.getString("output_file"))
        assertEquals(4L, report.getLong("declared_size_bytes"))
        assertFalse(report.getBoolean("original_contents_verified"))
        assertTrue(warnings.single().contains("Byte-range report"))
    }
    @Test fun partialRecoveryCannotBypassOriginChecks() {
        val store = store().apply { backing["inside"] = origin }
        assertThrows(IOException::class.java) { transfer(store, listOf(partialSource()), target = "inside", partial = true) }
        assertTrue(store.created.isEmpty())
    }
    @Test fun reportCreationFailureRemovesThePartialDataCopy() {
        val store = store().apply { failReportCreate = true }
        assertThrows(IOException::class.java) { transfer(store, listOf(partialSource()), partial = true) }
        assertFalse(store.bytes.keys.any { it.startsWith("dest/") })
        assertEquals("data", String(store.bytes.getValue("recovery/file.bin")))
    }
    @Test fun reportWriteFailureRemovesBothNewFiles() {
        val store = store().apply { failReportWrite = true }
        assertThrows(IOException::class.java) { transfer(store, listOf(partialSource()), partial = true) }
        assertFalse(store.bytes.keys.any { it.startsWith("dest/") })
    }
    @Test fun reportVerificationFailureRemovesBothNewFiles() {
        val store = store().apply { corruptReport = true }
        assertThrows(IOException::class.java) { transfer(store, listOf(partialSource()), partial = true) }
        assertFalse(store.bytes.keys.any { it.startsWith("dest/") })
    }
    @Test fun mismatchedRecoveryDigestPreventsReportPublication() {
        val store = store().apply { badRecoveryDigest = true }
        assertThrows(IOException::class.java) { transfer(store, listOf(partialSource()), partial = true) }
        assertFalse(store.bytes.keys.any { it.startsWith("dest/") })
    }
    @Test fun thrownPartialReadFailureAlsoCleansCreatedDestination() {
        val store = store().apply { throwPartialRead = true }; val warnings = mutableListOf<String>()
        transfer(store, listOf(partialSource()), partial = true, warnings = warnings)
        assertFalse(store.bytes.keys.any { it.startsWith("dest/") })
        assertTrue(warnings.single().contains("Skipped"))
    }
    @Test fun partialModeCannotMoveOrCreateShortcuts() {
        for (link in listOf(false, true)) {
            val store = store()
            assertThrows(IOException::class.java) { transfer(store, listOf(partialSource()), move = !link, links = link, partial = true) }
            assertTrue(store.created.isEmpty())
        }
    }

    @Test fun thrownDestinationOpenRemovesThePartialDataCopy() {
        val store = store().apply { throwDestinationOpen = true }
        assertThrows(IllegalStateException::class.java) { transfer(store, listOf(partialSource()), partial = true) }
        assertFalse(store.bytes.keys.any { it.startsWith("dest/") })
    }
    @Test fun cancellationDuringReportWritingRemovesBothNewFiles() {
        var cancelled = false; var writes = 0
        val store = store().apply { onWrite = { if (++writes == 2) cancelled = true } }
        assertThrows(InterruptedException::class.java) {
            transfer(store, listOf(partialSource()), partial = true, cancelled = { cancelled })
        }
        assertEquals(2, writes)
        assertFalse(store.bytes.keys.any { it.startsWith("dest/") })
        assertEquals("data", String(store.bytes.getValue("recovery/file.bin")))
    }

}
