package com.infinifolder.storage

import android.net.TestUri
import com.infinifolder.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.*

class RevisionAndRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val ref = ResourceRef("test", "test://source")
    private class Backend : StorageProvider {
        override val id = "test"
        var bytes = "original".toByteArray()
        var writes = 0
        var nativeCalls = 0
        var native = false
        override fun openRead(ref: ResourceRef): ProviderResult<InputStream> = ProviderResult.Success(ByteArrayInputStream(bytes))
        override fun openWrite(ref: ResourceRef): ProviderResult<OutputStream> {
            writes++
            return ProviderResult.Success(object : ByteArrayOutputStream() { override fun close() { bytes = toByteArray() } })
        }
        override fun replaceContent(ref: ResourceRef, data: ByteArray): ProviderResult<Unit> {
            nativeCalls++
            return if (native) { bytes = data; ProviderResult.Success(Unit) } else ProviderResult.Unsupported(ResourceCapability.WRITE)
        }
        override fun list(parent: ResourceRef) = ProviderResult.Success(emptyList<ResourceEntry>())
        override fun createDirectory(parent: ResourceRef, name: String) = ProviderResult.Unsupported(ResourceCapability.CREATE)
        override fun createFile(parent: ResourceRef, name: String, mimeType: String) = ProviderResult.Success(ResourceEntry(ResourceRef(id, name), ResourceMetadata(name, mimeType, 0, null, false), TestUri("test://$name")))
        override fun rename(ref: ResourceRef, name: String) = ProviderResult.Unsupported(ResourceCapability.RENAME)
        override fun delete(ref: ResourceRef) = ProviderResult.Success(Unit)
        override fun copy(source: ResourceRef, destination: ResourceRef) = ProviderResult.Unsupported(ResourceCapability.COPY)
        override fun move(source: ResourceRef, destination: ResourceRef) = ProviderResult.Unsupported(ResourceCapability.MOVE)
    }
    @Test fun staleEditorCannotReachEvenTheNativeWriter() {
        val backend = Backend().apply { native = true }
        val loaded = ContentRevision.of(backend.bytes)
        backend.bytes = "changed!".toByteArray() // Same size; timestamps are irrelevant.
        val result = ContentReplacement.replace(backend, ref, "my edits".toByteArray(), temporary.root, loaded)
        assertTrue(result is ProviderResult.Failure)
        assertTrue((result as ProviderResult.Failure).cause is RevisionConflictException)
        assertEquals("changed!", String(backend.bytes))
        assertEquals(0, backend.nativeCalls)
        assertEquals(0, backend.writes)
    }
    @Test fun successfulEditGetsANewRevisionForTheNextSave() {
        val backend = Backend()
        val first = ContentRevision.of(backend.bytes)
        assertTrue(ContentReplacement.replace(backend, ref, "edited".toByteArray(), temporary.root, first) is ProviderResult.Success)
        val second = ContentRevision.of(backend.bytes)
        assertNotEquals(first, second)
        assertTrue(ContentReplacement.replace(backend, ref, "again".toByteArray(), temporary.root, second) is ProviderResult.Success)
        assertEquals("again", String(backend.bytes))
    }
    @Test fun pendingRecoveryRequiresAnIntactSnapshotAndHidesActiveSessions() {
        val session = temporary.newFolder("session")
        val original = File(session, "original.bin").apply { writeText("original") }
        SaveRecovery.record(session, ref, "writing", ContentRevision.of(original.readBytes()))
        assertEquals(1, SaveRecovery.pending(temporary.root).size)
        SaveRecovery.activeSessions.add(session.name)
        try { assertTrue(SaveRecovery.pending(temporary.root).isEmpty()) } finally { SaveRecovery.activeSessions.remove(session.name) }
        original.writeText("corrupt!")
        assertTrue(SaveRecovery.pending(temporary.root).isEmpty())
    }
    @Test fun restoringAReviewedBackupRefusesNewerChanges() {
        val backend = Backend()
        val session = temporary.newFolder("session")
        val original = File(session, "original.bin").apply { writeText("older") }
        SaveRecovery.record(session, ref, "writing", ContentRevision.of(original.readBytes()))
        val record = SaveRecovery.pending(temporary.root).single()
        val reviewed = ContentRevision.of(backend.bytes)
        backend.bytes = "newer".toByteArray()
        assertTrue(SaveRecovery.restore(backend, record, reviewed, temporary.root) is ProviderResult.Failure)
        assertEquals("newer", String(backend.bytes))
        assertTrue(original.exists())
    }
    @Test fun debugArtifactsUseTheChosenProviderAndReturnActualEntries() {
        val backend = Backend()
        val result = DebugDumpWriter.write(backend, ref, listOf(DebugArtifact("capture.png", "image/png", byteArrayOf(1,2,3)), DebugArtifact("session.log", "text/plain", "log".toByteArray())))
        assertTrue(result is ProviderResult.Success)
        assertEquals(listOf("capture.png", "session.log"), (result as ProviderResult.Success).value.map { it.metadata.name })
        assertEquals(2, backend.writes)
    }
    @Test fun debugArtifactsCannotEscapeTheChosenDirectory() {
        val backend = Backend()
        val result = DebugDumpWriter.write(backend, ref, listOf(DebugArtifact("../original", "text/plain", byteArrayOf(1))))
        assertTrue(result is ProviderResult.Failure)
        assertEquals(0, backend.writes)
    }
    @Test fun diagnosticCredentialsAreRedacted() {
        val sanitized = DiagnosticLog.sanitize("sftp://user:password@example.com/file password=abc token=xyz Authorization:Bearer-secret")
        assertFalse(sanitized.contains("user:password"))
        assertFalse(sanitized.contains("abc"))
        assertFalse(sanitized.contains("xyz"))
        assertFalse(sanitized.contains("Bearer-secret"))
    }
}
