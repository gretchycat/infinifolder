package com.infinifolder.archive

import android.net.TestUri
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Real mkfs/mtools fixtures plus deliberately damaged allocation and directory records. */
class FatRecoveryTest {
    @get:Rule val temp = TemporaryFolder()
    private val root = File("../filesystem-test-images/filesystems/windows/recovery")
    private val baseline by lazy { JSONObject(File(root, "expected.json").readText()) }
    private val safety by lazy { JSONObject(File(root, "safety/expected.json").readText()) }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun sha(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun fixture(name: String) = File(root, name).also { check(it.isFile) { "Generate recovery fixtures: $it" } }
    private fun reader() = DiscImageReader(temp.newFolder())
    private fun candidates(reader: DiscImageReader, file: File) = reader.readDeletedFatEntries(file.name, { file.inputStream() }, file)
    private fun matching(entries: List<DiscImageReader.DiscEntry>, original: String) = entries.single { !it.isDirectory &&
        (it.path.endsWith(original) || it.path.endsWith("_" + original.substring(1))) }
    private fun extract(reader: DiscImageReader, file: File, entry: DiscImageReader.DiscEntry) =
        reader.openDeletedFatEntry(file.name, entry.path, { file.inputStream() }, file).use { it.readBytes() }

    private fun baselineCheck(name: String, expectedBits: Int = 16) {
        val file = fixture(name); val before = sha(file); val reader = reader()
        val description = if (name.startsWith("safety/")) safety.getJSONObject(file.name) else baseline.getJSONObject(file.name)
        assertEquals("Fixture must match its known initial image hash", description.getString("sha256"), before)
        val entries = candidates(reader, file)
        assertEquals(name, 5, entries.count { !it.isDirectory })
        val expected = baseline.getJSONObject("fat$expectedBits-deleted.img").getJSONObject("deleted")
        for (path in expected.keys()) {
            val entry = matching(entries, path.substringAfterLast('/'))
            assertNull(entry.readError)
            assertTrue(entry.recoveryNote!!.contains("Unverified"))
            assertEquals(expected.getJSONObject(path).getString("sha256"), sha(extract(reader, file, entry)))
        }
        assertEquals("Recovery must leave the entire source unchanged", before, sha(file))
    }
    @Test fun fat12IntactBytesAndSourceImmutability() = baselineCheck("fat12-deleted.img", 12)
    @Test fun fat16IntactBytesAndSourceImmutability() = baselineCheck("fat16-deleted.img")
    @Test fun fat32IntactBytesAndSourceImmutability() = baselineCheck("fat32-deleted.img", 32)
    @Test fun gzipVolume() = baselineCheck("safety/fat16-deleted.img.gz")
    @Test fun bzip2Volume() = baselineCheck("safety/fat16-deleted.img.bz2")
    @Test fun xzVolume() = baselineCheck("safety/fat16-deleted.img.xz")
    @Test fun mbrPartition() = baselineCheck("safety/mbr-fat16-deleted.img")
    @Test fun gptPartition() = baselineCheck("safety/gpt-fat16-deleted.img")
    @Test fun applePartitionMap() = baselineCheck("safety/apm-fat16-deleted.img")
    @Test fun gzipPartition() = baselineCheck("safety/mbr-fat16-deleted.img.gz")
    @Test fun bzip2Partition() = baselineCheck("safety/mbr-fat16-deleted.img.bz2")
    @Test fun xzPartition() = baselineCheck("safety/mbr-fat16-deleted.img.xz")
    @Test fun staleInactiveFatDoesNotMasqueradeAsALiveAllocation() = baselineCheck("safety/fat32-stale-inactive-fat.img", 32)
    @Test fun fat32UsesTheActiveSecondFat() = baselineCheck("safety/fat32-active-second-fat.img", 32)

    private fun adversarial(bits: Int) {
        val file = fixture("safety/fat$bits-adversarial.img"); val before = sha(file); val reader = reader()
        val entries = candidates(reader, file)
        val cases = safety.getJSONObject(file.name).getJSONObject("cases")
        for (name in cases.keys()) {
            val case = cases.getJSONObject(name)
            if (case.getString("outcome") == "absent") {
                assertFalse(name, entries.any { it.path.endsWith(name.substringAfterLast('/')) || it.path.endsWith("_" + name.substringAfterLast('/').substring(1)) })
                continue
            }
            val entry = matching(entries, name)
            when (case.getString("outcome")) {
                "blocked" -> {
                    assertNotNull(name, entry.readError)
                    assertThrows(IOException::class.java) { extract(reader, file, entry) }
                }
                "unverified-intact", "unverified-wrong-bytes" -> {
                    assertNull(name, entry.readError)
                    assertTrue(name, entry.recoveryNote!!.startsWith("Unverified"))
                    val actual = sha(extract(reader, file, entry))
                    assertEquals(name, case.getString("assumed_contiguous_sha256"), actual)
                    if (case.getString("outcome") == "unverified-wrong-bytes") {
                        assertNotEquals("Free clusters cannot prove original contents", case.getString("original_sha256"), actual)
                        if (name == "FRAGFREE.BIN") assertTrue(entry.recoveryNote!!.contains("Assumes 2 contiguous"))
                    }
                }
                else -> fail("Unknown outcome: $case")
            }
        }
        assertEquals(before, sha(file))
        assertTrue("Failed recovery must remove temporary payloads", temp.root.walkTopDown().none { it.name.startsWith("fat-recovery-") })
    }
    @Test fun fat12ReusedFragmentedOverwrittenAndDeletedDirectoryCases() = adversarial(12)
    @Test fun fat16ReusedFragmentedOverwrittenAndDeletedDirectoryCases() = adversarial(16)
    @Test fun fat32ReusedFragmentedOverwrittenAndDeletedDirectoryCases() = adversarial(32)

    private fun refused(name: String) {
        val file = fixture("safety/$name"); val before = sha(file)
        assertThrows(name, IOException::class.java) { candidates(reader(), file) }
        assertEquals(before, sha(file))
    }
    @Test fun badPrimaryGptChecksumIsRefused() = refused("gpt-bad-primary-crc.img")
    @Test fun badGptEntryChecksumIsRefused() = refused("gpt-bad-entry-crc.img")
    @Test fun badBackupGptChecksumIsRefused() = refused("gpt-bad-backup-crc.img")
    @Test fun dirtyVolumeIsRefused() = refused("fat16-dirty.img")
    @Test fun liveCycleIsRefused() = refused("fat16-live-cycle.img")
    @Test fun conflictingLiveFatCopiesAreRefused() = refused("fat16-mirror-disagreement.img")
    @Test fun malformedRootGeometryIsRefused() = refused("fat16-invalid-geometry.img")
    @Test fun nonPowerOfTwoClusterIsRefused() = refused("fat16-invalid-cluster-size.img")
    @Test fun invalidSignatureIsRefused() = refused("fat16-invalid-boot-signature.img")
    @Test fun invalidActiveFatIsRefused() = refused("fat32-invalid-active-fat.img")
    @Test fun partitionOutsideDiskIsRefused() = refused("mbr-outside-volume.img")
    @Test fun overlappingPartitionsAreRefused() = refused("mbr-overlapping-volumes.img")

    @Test fun unreadableAssessmentReachesArchiveTree() {
        val file = fixture("safety/fat16-adversarial.img")
        val tree = ArchiveReader(temp.newFolder()).buildDeletedFatTree(TestUri("file://${file.path}"), file.name, { file.inputStream() }, file)
        assertTrue(tree.warnings.any { it.contains("unverified") })
        val entry = tree.list("").single { it.name.endsWith("_LLOC.BIN") }
        assertNotNull(entry.readError)
        assertNotNull(entry.recoveryNote)
    }

    @Test fun changedDeletedRecordCannotReuseAListedCandidateIdentity() {
        val file = fixture("fat16-deleted.img").copyTo(temp.newFile("changed.img"), overwrite = true)
        val reader = reader(); val old = matching(candidates(reader, file), "DELETE.TXT")
        val slot = old.path.substringAfter("deleted-").substringBefore('-').toLong(16)
        RandomAccessFile(file, "rw").use { it.seek(slot + 28); it.write(byteArrayOf(49, 0, 0, 0)) }
        assertThrows(IOException::class.java) { extract(reader, file, old) }
        assertNotEquals(old.path, matching(candidates(reader, file), "DELETE.TXT").path)
    }

    @Test fun changedCompressedSourceDoesNotReuseTheNormalBrowsingCache() {
        val file = fixture("safety/fat16-deleted.img.gz").copyTo(temp.newFile("changed.img.gz"), overwrite = true)
        val reader = reader(); val old = matching(candidates(reader, file), "DELETE.TXT")
        // Seed the ordinary cache, then replace with different compressed bytes of the same size and mtime.
        val raw = java.util.zip.GZIPInputStream(file.inputStream()).use { it.readBytes() }
        fun gzip(data: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
            java.util.zip.GZIPOutputStream(out).use { it.write(data) }
        }.toByteArray()
        val original = gzip(raw)
        file.writeBytes(original)
        reader.readEntries(file.name, { file.inputStream() }, file)
        val timestamp = file.lastModified()
        val slot = old.path.substringAfter("deleted-").substringBefore('-').toInt(16)
        val replacement = (0..255).asSequence().filter { it != (raw[slot + 22].toInt() and 255) }.map { value ->
            val changed = raw.copyOf(); changed[slot + 22] = value.toByte(); gzip(changed)
        }.first { it.size == original.size }
        file.writeBytes(replacement); file.setLastModified(timestamp)
        assertEquals(original.size.toLong(), file.length())
        assertThrows(IOException::class.java) { extract(reader, file, old) }
    }

    @Test fun allocatedAfterListingIsRefusedAndTempPayloadIsRemoved() {
        val file = fixture("fat16-deleted.img").copyTo(temp.newFile("reallocated.img"), overwrite = true)
        val reader = reader(); val entry = matching(candidates(reader, file), "DELETE.TXT")
        RandomAccessFile(file, "rw").use { raf ->
            val fs = FatFileSystem(FileBlockDevice(raf, false))
            val slot = entry.path.substringAfter("deleted-").substringBefore('-').toLong(16)
            val cluster = raf.run { seek(slot + 26); readUnsignedByte() or (readUnsignedByte() shl 8) }
            for (i in 0 until fs.numFats) { raf.seek(fs.fatStartOffset + i * fs.sectorsPerFat * fs.bytesPerSector + cluster * 2); raf.write(byteArrayOf(-1, -1)) }
        }
        assertThrows(IOException::class.java) { extract(reader, file, entry) }
        assertTrue(temp.root.walkTopDown().none { it.name.startsWith("fat-recovery-") })
    }

    @Test fun allocationOrDirectoryChangesDuringExtractionFailBeforePublication() {
        val file = fixture("fat16-deleted.img")
        for (changeDirectory in listOf(false, true)) {
            val bytes = file.readBytes()
            val dev = MemoryDevice(bytes)
            val entries = FatDeletedFiles(FatFileSystem(dev)).entries()
            val entry = matching(entries, "DELETE.TXT")
            val slot = entry.path.substringAfter("deleted-").substringBefore('-').toInt(16)
            val out = object : ByteArrayOutputStream() {
                override fun write(b: ByteArray, off: Int, len: Int) {
                    super.write(b, off, len)
                    if (changeDirectory) bytes[slot + 28] = 49 else bytes[512 + 8] = -1
                }
            }
            assertThrows(IOException::class.java) { entry.extractor!!(dev, out) }
            assertEquals(0, dev.writeAttempts)
        }
    }

    @Test fun cancellingScanAndStagingRemovesTempsAndLeavesSourceUnchanged() {
        val file = fixture("fat16-deleted.img"); val before = sha(file); val reader = reader()
        var checks = 0
        assertThrows(InterruptedIOException::class.java) {
            RecoveryCancellation.withCancellation({ ++checks > 5 }) { candidates(reader, file) }
        }
        val entry = matching(candidates(reader, file), "CONTIG.BIN")
        checks = 0
        assertThrows(InterruptedIOException::class.java) {
            RecoveryCancellation.withCancellation({ ++checks > 35 }) { extract(reader, file, entry) }
        }
        assertEquals(before, sha(file))
        assertTrue(temp.root.walkTopDown().none { it.name.startsWith("fat-recovery-") })
        // The thread-local flag must be cleared even when extraction fails.
        assertTrue(extract(reader, file, entry).isNotEmpty())
    }

    @Test fun interruptedThreadIsRespectedWithoutClearingItsFlag() {
        try {
            Thread.currentThread().interrupt()
            assertThrows(InterruptedIOException::class.java) { candidates(reader(), fixture("fat16-deleted.img")) }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun sourcePayloadChangingDuringExtractionIsDetected() {
        val file = fixture("fat16-deleted.img"); val bytes = file.readBytes(); val dev = MemoryDevice(bytes)
        val fs = FatFileSystem(dev)
        val entry = matching(FatDeletedFiles(fs).entries(), "DELETE.TXT")
        val slot = entry.path.substringAfter("deleted-").substringBefore('-').toInt(16)
        val cluster = (bytes[slot + 26].toInt() and 255) or ((bytes[slot + 27].toInt() and 255) shl 8)
        val dataOffset = (fs.dataStartOffset + (cluster - 2) * fs.clusterSize).toInt()
        val out = object : ByteArrayOutputStream() {
            override fun write(b: ByteArray, off: Int, len: Int) {
                super.write(b, off, len); bytes[dataOffset] = (bytes[dataOffset].toInt() xor 1).toByte()
            }
        }
        assertThrows(IOException::class.java) { entry.extractor!!(dev, out) }
        assertEquals(0, dev.writeAttempts)
    }

    @Test fun payloadReadFailureAndCancellationCleanStagedOutputs() {
        val file = fixture("fat16-deleted.img"); val before = sha(file)
        for (cancelInsteadOfFailure in listOf(false, true)) {
            val bytes = file.readBytes(); val base = MemoryDevice(bytes); val fs = FatFileSystem(base)
            val entry = matching(FatDeletedFiles(fs).entries(), "CONTIG.BIN")
            val slot = entry.path.substringAfter("deleted-").substringBefore('-').toInt(16)
            val cluster = (bytes[slot + 26].toInt() and 255) or ((bytes[slot + 27].toInt() and 255) shl 8)
            val dataOffset = fs.dataStartOffset + (cluster - 2) * fs.clusterSize
            var cancelled = false; var payloadReads = 0
            val dev = object : BlockDevice by base {
                override fun readAt(offset: Long, buffer: ByteArray, dstOffset: Int, length: Int): Int {
                    if (offset == dataOffset) {
                        payloadReads++
                        if (!cancelInsteadOfFailure) throw IOException("Injected unreadable payload")
                        cancelled = true
                    }
                    return base.readAt(offset, buffer, dstOffset, length)
                }
            }
            val reader = reader()
            assertThrows(IOException::class.java) {
                RecoveryCancellation.withCancellation({ cancelled }) {
                    reader.openDeletedFatEntry(file.name, entry.path, { file.inputStream() }, file, dev).use { it.readBytes() }
                }
            }
            assertEquals(1, payloadReads)
            assertEquals(0, base.writeAttempts)
            assertTrue(temp.root.walkTopDown().none { it.name.startsWith("fat-recovery-") })
        }
        assertEquals(before, sha(file))
    }

    @Test fun invalidDeletedClusterMetadataIsBlocked() {
        for (bits in listOf(12, 16, 32)) {
            val bytes = fixture("fat$bits-deleted.img").readBytes()
            val initial = MemoryDevice(bytes)
            val entry = matching(FatDeletedFiles(FatFileSystem(initial)).entries(), "DELETE.TXT")
            val slot = entry.path.substringAfter("deleted-").substringBefore('-').toInt(16)
            bytes[slot + 20] = 0; bytes[slot + 21] = 0x80.toByte()
            val changed = matching(FatDeletedFiles(FatFileSystem(MemoryDevice(bytes))).entries(), "DELETE.TXT")
            assertTrue(changed.readError!!.contains("reserved cluster bits"))
        }
    }

    @Test fun malformedLongNamesFallBackToUnknownShortNames() {
        val source = fixture("fat16-deleted.img").readBytes()
        val initial = matching(FatDeletedFiles(FatFileSystem(MemoryDevice(source))).entries(), "Deleted long filename.txt")
        val slot = initial.path.substringAfter("deleted-").substringBefore('-').toInt(16)
        for (position in listOf(slot - 32 + 12, slot - 32 + 13, slot - 32 + 1)) {
            val bytes = source.copyOf()
            if (position == slot - 32 + 1) { bytes[position] = 0; bytes[position + 1] = 0xd8.toByte() }
            else bytes[position] = (bytes[position].toInt() xor 1).toByte()
            val entries = FatDeletedFiles(FatFileSystem(MemoryDevice(bytes))).entries()
            val changed = entries.single { it.path.startsWith("deleted-${slot.toString(16)}-") }
            assertFalse(changed.path.contains("Deleted long filename"))
            assertTrue(changed.recoveryNote!!.contains("tentative"))
        }
    }

    @Test fun truncatedSourceIsRefusedWithoutWrites() {
        val bytes = fixture("fat16-deleted.img").readBytes().copyOf(100000)
        val dev = MemoryDevice(bytes)
        assertThrows(IOException::class.java) { FatDeletedFiles(FatFileSystem(dev)).entries() }
        assertEquals(0, dev.writeAttempts)
    }

    @Test fun unicodeCandidateNamesFitDestinationByteLimitsWithoutSplittingSurrogates() {
        val bytes = fixture("fat16-deleted.img").readBytes(); val dev = MemoryDevice(bytes); val fs = FatFileSystem(dev)
        bytes.fill(0, fs.rootOffset.toInt(), fs.rootOffset.toInt() + fs.rootEntryCount * 32)
        val name = "界".repeat(100) + "😀".repeat(50)
        val short = "UNICODE TXT".toByteArray()
        var checksum = 0
        for (b in short) checksum = (((checksum and 1) shl 7) + (checksum shr 1) + (b.toInt() and 255)) and 255
        val units = name.map { it.code }.toMutableList().apply { add(0); while (size % 13 != 0) add(0xffff) }
        var at = fs.rootOffset.toInt()
        for (part in units.chunked(13).asReversed()) {
            val record = ByteArray(32); record[0] = 0xe5.toByte(); record[11] = 15; record[13] = checksum.toByte()
            for ((i, p) in listOf(1,3,5,7,9,14,16,18,20,22,24,28,30).withIndex()) {
                record[p] = part[i].toByte(); record[p + 1] = (part[i] shr 8).toByte()
            }
            record.copyInto(bytes, at); at += 32
        }
        val record = ByteArray(32); short.copyInto(record); record[0] = 0xe5.toByte(); record[11] = 32
        record[26] = 40; record[28] = 1; record.copyInto(bytes, at)
        val entry = FatDeletedFiles(FatFileSystem(dev)).entries().single()
        assertTrue(entry.path.contains("界"))
        assertTrue(entry.path.toByteArray(Charsets.UTF_8).size < 255)
        assertFalse(entry.path.last().isHighSurrogate())
        assertNull(entry.readError)
    }

    private class MemoryDevice(val bytes: ByteArray) : BlockDevice {
        override val size get() = bytes.size.toLong()
        override val capabilities = setOf(DeviceCapability.READ, DeviceCapability.SEEK)
        var writeAttempts = 0
        override fun readAt(offset: Long, buffer: ByteArray, dstOffset: Int, length: Int): Int {
            if (offset < 0 || offset >= size) return 0
            val n = minOf(length, bytes.size - offset.toInt()); bytes.copyInto(buffer, dstOffset, offset.toInt(), offset.toInt() + n); return n
        }
        override fun writeAt(offset: Long, buffer: ByteArray, srcOffset: Int, length: Int) { writeAttempts++; error("Recovery attempted a write") }
        override fun close() {}
    }
}
