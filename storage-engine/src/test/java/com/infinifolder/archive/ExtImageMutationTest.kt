package com.infinifolder.archive

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

/** End-to-end image selection + shipped JNI + the existing Kotlin reader.
 * Uses disposable copies, never writes user-owned image fixtures.
 */
class ExtImageMutationTest {
    @Test fun copyBetweenImagesAndMoveKeepsContentsAndMetadata() {
        val work = Files.createTempDirectory("if-ext-cross-image").toFile()
        try {
            val generator = File(root, "filesystem-test-images/generate-ext-metadata-fixture.py")
                .copyTo(File(work, "generate-fixture.py"))
            val generate = ProcessBuilder("python3", generator.absolutePath).redirectErrorStream(true).start()
            val generateReport = generate.inputStream.bufferedReader().readText()
            assertEquals(generateReport, 0, generate.waitFor())
            val generated = File(work, "filesystems/linux/ext4-metadata-tests.img")
            val source = generated.copyTo(File(work, "source.img"))
            val target = generated.copyTo(File(work, "target.img"))
            val mkdir = ProcessBuilder("debugfs", "-w", "-R", "mkdir /copy-to", target.absolutePath)
                .redirectErrorStream(true).start()
            val mkdirReport = mkdir.inputStream.bufferedReader().readText()
            assertEquals(mkdirReport, 0, mkdir.waitFor())
            checkFs(source)
            checkFs(target)
            val reader = DiscImageReader(File(work, "cache"))
            var filesCopied = 0
            PrivateBatchBlockDevice(source, true).use { sourceDev ->
                PrivateBatchBlockDevice(target, true).use { targetDev ->
                    fun copyTree(from: File, fromDev: BlockDevice, fromPath: String,
                                 to: File, toDev: BlockDevice, toPath: String) {
                        val metadata = reader.readTransferMetadata(from.name, fromPath, { from.inputStream() }, from, fromDev)!!
                        reader.createDirectory(to.name, to, toPath, toDev)
                        val children = reader.readDirectoryEntries(from.name, fromPath, { from.inputStream() }, from, fromDev)
                        for (child in children) {
                            val name = child.path.substringAfterLast('/')
                            val destination = "$toPath/$name"
                            if (child.isDirectory) {
                                copyTree(from, fromDev, child.path, to, toDev, destination)
                            } else {
                                val attrs = reader.readTransferMetadata(from.name, child.path, { from.inputStream() }, from, fromDev)!!
                                val bytes = reader.openEntryStream(from.name, child.path, { from.inputStream() }, from, fromDev).use { it.readBytes() }
                                reader.createFile(to.name, to, destination, bytes, toDev)
                                reader.applyTransferMetadata(to.name, to, toDev, destination, attrs)
                                val actual = reader.readTransferMetadata(to.name, destination, { to.inputStream() }, to, toDev)!!
                                assertTrue("metadata differs for ${child.path}", attrs.sameAs(actual))
                                val copied = reader.openEntryStream(to.name, destination, { to.inputStream() }, to, toDev).use { it.readBytes() }
                                assertArrayEquals("content differs for ${child.path}", bytes, copied)
                                filesCopied++
                            }
                        }
                        reader.applyTransferMetadata(to.name, to, toDev, toPath, metadata)
                        assertTrue("directory metadata differs for $fromPath", metadata.sameAs(
                            reader.readTransferMetadata(to.name, toPath, { to.inputStream() }, to, toDev)!!))
                    }
                    copyTree(source, sourceDev, "copy-from", target, targetDev, "copy-to/copied")
                    assertEquals(5, filesCopied)
                    reader.relocateEntry(target.name, target, "copy-to/copied", "copy-to/moved", targetDev)
                    copyTree(target, targetDev, "copy-to/moved", source, sourceDev, "copy-back-here/returned")
                    assertEquals(10, filesCopied)
                }
            }
            checkFs(source)
            checkFs(target)
            val hashes = mapOf(
                "plain-0644.txt" to "ffae35757439fda4ecfc0cf441b7630939e2d48cef4bca248784aed26720fe4f",
                "private-0600.txt" to "6d38af5b258b28bf9acd4dba949519c967d600e5c04de1b2f016122e5d9b1951",
                "executable-0755.sh" to "4602bd9017f843f0c59e256e37e2bcbaea3250462db6ca9ddecdc9b236a572c2",
                "acl-and-xattrs-0640.txt" to "0dcfcc2500cdf8e4d56617b26fe3af24983cfcb1660aa32c719ef52762820d5b",
                "nested/nested-0640.txt" to "724c27d6e988b463a789b3fa8ca8a9497b3b048e6dab5fd4df14caffc92571b8"
            )
            // Reopen committed images so verification cannot read only an in-memory batch.
            for ((relative, expected) in hashes) {
                val original = reader.readTransferMetadata(source.name, "copy-from/$relative", { source.inputStream() }, source, null)!!
                for ((image, prefix) in listOf(source to "copy-from", target to "copy-to/moved", source to "copy-back-here/returned")) {
                    val path = "$prefix/$relative"
                    val bytes = reader.openEntryStream(image.name, path, { image.inputStream() }, image).use { it.readBytes() }
                    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
                    assertEquals("hash differs for $path", expected, digest)
                    val metadata = reader.readTransferMetadata(image.name, path, { image.inputStream() }, image, null)!!
                    assertTrue("committed metadata differs for $path", original.sameAs(metadata))
                }
            }
        } finally { work.deleteRecursively() }
    }
    @Test fun newIndexedDirectoriesRemainValidWithNonzeroFreeBlocks() {
        val work = Files.createTempDirectory("if-ext-indexed-reuse").toFile()
        try {
            val image = File(work, "image.img")
            RandomAccessFile(image, "rw").use { it.setLength(24L * 1024 * 1024) }
            val format = ProcessBuilder("mke2fs", "-q", "-t", "ext4", "-b", "1024", image.absolutePath)
                .redirectErrorStream(true).start()
            val formatReport = format.inputStream.bufferedReader().readText()
            assertEquals(formatReport, 0, format.waitFor())
            val free = ProcessBuilder("debugfs", "-R", "ffb 128", image.absolutePath).redirectErrorStream(true).start()
            val freeReport = free.inputStream.bufferedReader().readText()
            assertEquals(freeReport, 0, free.waitFor())
            val blocks = freeReport.substringAfter("Free blocks found:", "").trim().split(Regex("\\s+"))
                .mapNotNull { it.toLongOrNull() }
            assertEquals("Expected 128 free blocks: $freeReport", 128, blocks.size)
            RandomAccessFile(image, "rw").use { raw ->
                blocks.forEach { block -> raw.seek(block * 1024); raw.write(ByteArray(1024) { 0xa5.toByte() }) }
            }
            checkFs(image) // Nonzero bytes in unallocated blocks are legal.
            val reader = DiscImageReader(File(work, "cache"))
            PrivateBatchBlockDevice(image, true).use { device ->
                repeat(12) { index ->
                    reader.createDirectory(image.name, image, "dir-$index", device)
                    reader.createFile(image.name, image, "dir-$index/child.txt", "data-$index".toByteArray(), device)
                }
            }
            checkFs(image)
        } finally { work.deleteRecursively() }
    }
    @Test fun movingPopulatedNonIndexedDirectoryKeepsItsChecksumValid() {
        val work = Files.createTempDirectory("if-ext-unindexed-move").toFile()
        try {
            val sourceTree = File(work, "tree").apply { mkdirs() }
            val source = File(sourceTree, "source").apply { mkdirs() }
            File(sourceTree, "destination").mkdirs()
            File(source, "payload.txt").writeText("Preserve this directory and its contents.\n")
            val image = File(work, "image.img")
            RandomAccessFile(image, "rw").use { it.setLength(24L * 1024 * 1024) }
            val format = ProcessBuilder("mke2fs", "-q", "-t", "ext4", "-b", "1024", "-d", sourceTree.absolutePath, image.absolutePath)
                .redirectErrorStream(true).start()
            val formatReport = format.inputStream.bufferedReader().readText()
            assertEquals(formatReport, 0, format.waitFor())
            val stat = ProcessBuilder("debugfs", "-R", "stat /source", image.absolutePath).redirectErrorStream(true).start()
            val statReport = stat.inputStream.bufferedReader().readText()
            assertEquals(statReport, 0, stat.waitFor())
            val flags = Regex("Flags: 0x([0-9a-fA-F]+)").find(statReport)!!.groupValues[1].toLong(16)
            assertEquals("Fixture must exercise a non-indexed directory", 0L, flags and 0x1000L)
            checkFs(image)
            val reader = DiscImageReader(File(work, "cache"))
            PrivateBatchBlockDevice(image, true).use { device ->
                reader.relocateEntry(image.name, image, "source", "destination/source", device)
            }
            checkFs(image)
            val recovered = File(work, "recovered.txt")
            val dump = ProcessBuilder("debugfs", "-R", "dump /destination/source/payload.txt ${recovered.absolutePath}", image.absolutePath)
                .redirectErrorStream(true).start()
            val dumpReport = dump.inputStream.bufferedReader().readText()
            assertEquals(dumpReport, 0, dump.waitFor())
            assertEquals(File(source, "payload.txt").readText(), recovered.readText())
        } finally { work.deleteRecursively() }
    }
    @Test fun metadataRoundTripKeepsPermissionsAclAndTimestamps() {
        val work = Files.createTempDirectory("if-ext-metadata").toFile()
        try {
            val copy = File(root, "filesystem-test-images/filesystems/linux/ext4.img").copyTo(File(work,"metadata.img"))
            val reader = DiscImageReader(File(work,"cache"))
            PrivateBatchBlockDevice(copy,true).use { dev ->
                reader.createFile(copy.name,copy,"metadata-source",byteArrayOf(1,2,3),dev)
                val acl = java.nio.ByteBuffer.allocate(4 + 5*8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(2)
                listOf(Triple(1,6,-1),Triple(2,4,1234),Triple(4,0,-1),Triple(16,4,-1),Triple(32,0,-1)).forEach {
                    acl.putShort(it.first.toShort()).putShort(it.second.toShort()).putInt(it.third)
                }
                val metadata = com.infinifolder.model.TransferMetadata(mode=0x1a0,uid=123,gid=456,
                    modifiedSeconds=1770000000,modifiedNanos=123456789,accessedSeconds=1760000000,accessedNanos=987654321,
                    xattrs=mapOf("user.infini" to "metadata retained".toByteArray(),"system.posix_acl_access" to acl.array(),
                        "user.external" to ByteArray(400) { it.toByte() }))
                try { reader.applyTransferMetadata(copy.name,copy,dev,"metadata-source",metadata) }
                catch (error: Exception) {
                    val actual = reader.readTransferMetadata(copy.name,"metadata-source",{copy.inputStream()},copy,dev)
                    copy.copyTo(File(root,"build/ext-metadata-failed.img"), overwrite=true)
                    throw AssertionError("Expected $metadata; actual $actual",error)
                }
                val captured = reader.readTransferMetadata(copy.name,"metadata-source",{copy.inputStream()},copy,dev)!!
                assertTrue(metadata.sameAs(captured))
                reader.createFile(copy.name,copy,"metadata-copy",byteArrayOf(1,2,3),dev)
                reader.applyTransferMetadata(copy.name,copy,dev,"metadata-copy",captured)
                reader.relocateEntry(copy.name,copy,"metadata-copy","metadata-moved",dev)
                assertTrue(captured.sameAs(reader.readTransferMetadata(copy.name,"metadata-moved",{copy.inputStream()},copy,dev)!!))
                val bad = metadata.copy(mode=0x1ff)
                try { reader.applyTransferMetadata(copy.name,copy,dev,"metadata-moved",bad); fail("Inconsistent ACL/mode accepted") }
                catch (_: IllegalArgumentException) { }
                assertTrue(captured.sameAs(reader.readTransferMetadata(copy.name,"metadata-moved",{copy.inputStream()},copy,dev)!!))
            }
            checkFs(copy)
        } finally { work.deleteRecursively() }
    }
    private val root = File(requireNotNull(System.getProperty("user.dir"))).let {
        if (File(it, "filesystem-test-images").isDirectory) it else it.parentFile
    }
    private fun checkFs(file: File) {
        val process = ProcessBuilder("e2fsck", "-fn", file.absolutePath).redirectErrorStream(true).start()
        val report = process.inputStream.bufferedReader().readText()
        assertEquals(report, 0, process.waitFor())
    }
    private fun exercise(fixture: File, prefix: String?, type: String) {
        val work = Files.createTempDirectory("if-ext-integration").toFile()
        try {
            val copy = fixture.copyTo(File(work, "working.img"))
            val reader = DiscImageReader(File(work, "cache"))
            PrivateBatchBlockDevice(copy, writable = true).use { dev ->
                val base = prefix ?: ""
                fun path(name: String) = if (base.isEmpty()) name else "$base/$name"
                assertNull(reader.writableFailureReason(copy.name, copy, dev, base, probeOnly = true))
                assertEquals(type, reader.fileSystemInfo(copy.name, base, { copy.inputStream() }, copy, dev)?.type)
                reader.createDirectory(copy.name, copy, path("move-source"), dev)
                reader.createDirectory(copy.name, copy, path("move-destination"), dev)
                reader.createDirectory(copy.name, copy, path("move-source/subtree"), dev)
                val payload = ByteArray(300_000) { (it % 251).toByte() }
                reader.createFile(copy.name, copy, path("move-source/subtree/hello-🌍.bin"), payload, dev)
                reader.relocateEntry(copy.name, copy, path("move-source/subtree"), path("move-destination/subtree"), dev)
                val source = reader.readDirectoryEntries(copy.name, path("move-source"), { copy.inputStream() }, copy, dev)
                assertTrue(source.none { it.path.endsWith("subtree") })
                val entries = reader.readDirectoryEntries(copy.name, path("move-destination/subtree"), { copy.inputStream() }, copy, dev)
                assertEquals(1, entries.size)
                assertNotNull(entries.single().permissionsMode)
                reader.openEntryStream(copy.name, path("move-destination/subtree/hello-🌍.bin"), { copy.inputStream() }, copy, dev).use {
                    assertArrayEquals(payload, it.readBytes())
                }
                reader.relocateEntry(copy.name, copy, path("move-destination/subtree/hello-🌍.bin"), path("move-source/moved.bin"), dev)
                reader.deleteEntry(copy.name, copy, path("move-destination"), dev)
            }
            if (prefix == null) checkFs(copy)
            else {
                // Check the ext partition after image-level operations.
                FileBlockDevice(RandomAccessFile(copy, "r"), true, ownsFile = true).use { dev ->
                    val selected = reader.getPartitionSlice(dev, prefix)!!
                    val partition = File(work, "partition.img")
                    partition.outputStream().use { out -> selected.asInputStream().use { it.copyTo(out) } }
                    checkFs(partition)
                }
            }
        } finally { work.deleteRecursively() }
    }
    @Test fun ext2Ext3Ext4MovesAndWrites() {
        listOf("ext2", "ext3", "ext4").forEach { type ->
            exercise(File(root, "filesystem-test-images/filesystems/linux/$type.img"), null, type)
        }
    }
    @Test fun selectedGptExt4PartitionMoves() {
        val image = File(root, "filesystem-test-images/disks/gpt-mixed.img")
        val reader = DiscImageReader(File(root, "build/ext-partition-test-cache"))
        val partition = reader.readDirectoryEntries(image.name, "", { image.inputStream() }, image).first { it.isDirectory }
        exercise(image, partition.path, "ext4")
    }
    @Test fun failedPrivateMutationPoisonsTheSnapshot() {
        val work = Files.createTempDirectory("if-ext-failure").toFile()
        try {
            val copy = File(root, "filesystem-test-images/filesystems/linux/ext4.img").copyTo(File(work, "image.img"))
            var failure: Throwable? = null
            PrivateBatchBlockDevice(copy, true) { failure = it }.use { dev ->
                val reader = DiscImageReader(File(work, "cache"))
                reader.createDirectory(copy.name, copy, "exists", dev)
                try { reader.createDirectory(copy.name, copy, "exists", dev); fail("Collision should fail") }
                catch (_: java.io.IOException) { assertNotNull(failure) }
            }
        } finally { work.deleteRecursively() }
    }
    @Test fun compressedExtProbeAndPublicWriteGuard() {
        val work = Files.createTempDirectory("if-ext-compressed").toFile()
        try {
            val image = File(root, "filesystem-test-images/filesystems/linux/ext4.img")
            val gz = File(work, "image.img.gz")
            java.util.zip.GZIPOutputStream(gz.outputStream()).use { out -> image.inputStream().use { it.copyTo(out) } }
            val reader = DiscImageReader(File(work, "cache"))
            assertNull(reader.compressedWritableFailureReason(gz.name, "", { gz.inputStream() }, gz, null))
            val copy = image.copyTo(File(work, "public.img"))
            val before = copy.readBytes()
            try { reader.createDirectory(copy.name, copy, "forbidden"); fail("Public ext mutation bypassed staging") }
            catch (expected: java.io.IOException) { assertTrue(expected.message!!.contains("private writable image batch")) }
            assertArrayEquals(before, copy.readBytes())
        } finally { work.deleteRecursively() }
    }
}
