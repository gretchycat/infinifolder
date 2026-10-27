package com.infinifolder.archive

import android.net.Uri
import com.infinifolder.model.*
import com.infinifolder.storage.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.file.Files

/** Exercise image mutation and the actual streaming publication/recovery boundary. */
class ContainerPublicationIntegrityTest {
    private class StreamingBackend(original: ByteArray) : StorageProvider {
        override val id = "publication-test"
        var bytes = original.copyOf()
        var writes = 0
        var reads = 0
        var failFirstWrite = false
        var corruptFirstWrite = false
        var failRestoration = false
        var changeOnRead = -1
        override fun openRead(ref: ResourceRef): ProviderResult<InputStream> {
            reads++
            if (reads == changeOnRead) bytes = "changed by someone else".toByteArray()
            return ProviderResult.Success(ByteArrayInputStream(bytes.copyOf()))
        }
        override fun openWrite(ref: ResourceRef): ProviderResult<OutputStream> {
            writes++
            val attempt = writes
            bytes = ByteArray(0) // a streaming provider may truncate on open
            return ProviderResult.Success(object : ByteArrayOutputStream() {
                override fun write(b: ByteArray, off: Int, len: Int) {
                    if ((attempt == 1 && failFirstWrite) || (attempt > 1 && failRestoration)) {
                        bytes = b.copyOfRange(off, off + minOf(len, 127))
                        throw IOException("injected interrupted write")
                    }
                    super.write(b, off, len)
                }
                override fun close() {
                    if ((attempt == 1 && failFirstWrite) || (attempt > 1 && failRestoration)) return
                    bytes = toByteArray()
                    if (attempt == 1 && corruptFirstWrite && bytes.isNotEmpty()) bytes[0] = (bytes[0].toInt() xor 1).toByte()
                }
            })
        }
        override fun refForUri(uri: Uri): ResourceRef? = null
        override fun list(parent: ResourceRef) = ProviderResult.Success(emptyList<ResourceEntry>())
        override fun createDirectory(parent: ResourceRef, name: String) = ProviderResult.Unsupported(ResourceCapability.CREATE)
        override fun createFile(parent: ResourceRef, name: String, mimeType: String) = ProviderResult.Unsupported(ResourceCapability.CREATE)
        override fun rename(ref: ResourceRef, name: String) = ProviderResult.Unsupported(ResourceCapability.RENAME)
        override fun delete(ref: ResourceRef) = ProviderResult.Unsupported(ResourceCapability.DELETE)
        override fun copy(source: ResourceRef, destination: ResourceRef) = ProviderResult.Unsupported(ResourceCapability.COPY)
        override fun move(source: ResourceRef, destination: ResourceRef) = ProviderResult.Unsupported(ResourceCapability.MOVE)
    }
    private val root = File(requireNotNull(System.getProperty("user.dir"))).let {
        if (File(it, "filesystem-test-images").isDirectory) it else it.parentFile
    }
    private fun checkFs(image: File) {
        val check = ProcessBuilder("e2fsck", "-fn", image.absolutePath).redirectErrorStream(true).start()
        val report = check.inputStream.bufferedReader().readText()
        assertEquals(report, 0, check.waitFor())
    }
    private fun exercise(mode: String) {
        val work = Files.createTempDirectory("if-publication-$mode").toFile()
        try {
            val fixture = File(root, "filesystem-test-images/filesystems/linux/ext4.img")
            val original = fixture.readBytes()
            val staged = fixture.copyTo(File(work, "staged.img"))
            val reader = DiscImageReader(File(work, "cache"))
            PrivateBatchBlockDevice(staged, true).use { device ->
                reader.createDirectory(staged.name, staged, "new-directory", device)
                reader.createFile(staged.name, staged, "new-directory/file.txt", "published data".toByteArray(), device)
            }
            checkFs(staged)
            val backend = StreamingBackend(original).apply {
                failFirstWrite = mode == "interrupted" || mode == "restore-fails"
                corruptFirstWrite = mode == "corrupt"
                failRestoration = mode == "restore-fails"
                changeOnRead = if (mode == "conflict") 3 else -1
            }
            val ref = ResourceRef(backend.id, "container")
            val result = ContentReplacement.replaceFromFile(backend, ref, staged, File(work, "recovery"), ContentRevision.of(original))
            when (mode) {
                "success" -> {
                    assertTrue(result is ProviderResult.Success)
                    assertArrayEquals(staged.readBytes(), backend.bytes)
                    assertTrue(File(work, "recovery").listFiles().orEmpty().isEmpty())
                    val published = File(work, "published.img").apply { writeBytes(backend.bytes) }
                    checkFs(published)
                    assertEquals("published data", reader.openEntryStream(published.name, "new-directory/file.txt", { published.inputStream() }, published).use { it.reader().readText() })
                }
                "conflict" -> {
                    assertTrue(result is ProviderResult.Failure)
                    assertEquals(0, backend.writes)
                    assertEquals("changed by someone else", String(backend.bytes))
                }
                "interrupted", "corrupt" -> {
                    assertTrue(result is ProviderResult.Failure)
                    assertArrayEquals(original, backend.bytes)
                    val backup = File(work, "recovery").walkTopDown().first { it.name == "original.bin" }
                    assertArrayEquals(original, backup.readBytes())
                    checkFs(File(work, "restored.img").apply { writeBytes(backend.bytes) })
                }
                "restore-fails" -> {
                    assertTrue(result is ProviderResult.Failure)
                    assertTrue((result as ProviderResult.Failure).message.contains("could not be restored"))
                    val backup = File(work, "recovery").walkTopDown().first { it.name == "original.bin" }
                    assertArrayEquals(original, backup.readBytes())
                    checkFs(backup)
                    val pending = SaveRecovery.pending(File(work, "recovery"))
                    assertEquals(1, pending.size)
                    assertEquals("interrupted", pending.single().state)
                    assertEquals(ContentRevision.of(original), pending.single().revision)
                }
            }
            assertArrayEquals("Original fixture changed", original, fixture.readBytes())
        } finally { work.deleteRecursively() }
    }
    @Test fun verifiedImagePublication() = exercise("success")
    @Test fun revisionConflictDoesNotStartWriting() = exercise("conflict")
    @Test fun interruptedPublicationRestoresOriginal() = exercise("interrupted")
    @Test fun corruptedPublicationRestoresOriginal() = exercise("corrupt")
    @Test fun failedRestorationKeepsVerifiedRecoveryCopy() = exercise("restore-fails")
}
