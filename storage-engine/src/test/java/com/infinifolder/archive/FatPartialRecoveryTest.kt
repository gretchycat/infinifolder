package com.infinifolder.archive

import com.infinifolder.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.security.MessageDigest

class FatPartialRecoveryTest {
    @get:Rule val temp = TemporaryFolder()
    private val folder = File("../filesystem-test-images/filesystems/windows/recovery")
    private fun fixture(name: String) = File(folder, name).also { check(it.isFile) { "Generate partial recovery fixtures: $it" } }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun reader() = DiscImageReader(temp.newFolder())
    private fun entry(reader: DiscImageReader, file: File, suffix: String) =
        reader.readDeletedFatEntries(file.name, { file.inputStream() }, file).single { it.path.endsWith(suffix) }
    private fun export(reader: DiscImageReader, file: File, entry: DiscImageReader.DiscEntry) =
        reader.openPartialDeletedFatEntry(file.name, entry.path, { file.inputStream() }, file)

    private fun reused(bits: Int) {
        val file = fixture("safety/fat$bits-adversarial.img"); val before = hash(file.readBytes()); val reader = reader()
        val entry = entry(reader, file, "_LLOC.BIN")
        assertNotNull(entry.readError); assertNotNull(entry.partialExtractor)
        export(reader, file, entry).use { recovered ->
            val bytes = recovered.input.readBytes()
            assertArrayEquals(ByteArray(512) + ByteArray(512) { 'S'.code.toByte() }, bytes)
            assertEquals(entry.sizeBytes, bytes.size.toLong())
            assertEquals(hash(bytes), recovered.report.sha256)
            assertEquals(512L, recovered.report.ranges.filter { it.disposition == RecoveryDisposition.ZERO_FILLED }.sumOf { it.length })
            assertTrue(recovered.report.ranges.none { it.grade in listOf(RecoveryGrade.GOOD, RecoveryGrade.OVERWRITTEN) })
            val json = JSONObject(recovered.report.toJson("output.partial.bin"))
            assertFalse(json.getBoolean("original_contents_verified"))
            assertEquals(0L, json.getJSONObject("byte_counts_by_grade").getLong("overwritten"))
            assertEquals(0L, json.getJSONObject("byte_counts_by_grade").getLong("good"))
        }
        assertEquals(before, hash(file.readBytes()))
        assertTrue(temp.root.walkTopDown().none { it.name.startsWith("fat-partial-") })
    }
    @Test fun fat12ReusedClusterIsZeroFilled() = reused(12)
    @Test fun fat16ReusedClusterIsZeroFilled() = reused(16)
    @Test fun fat32ReusedClusterIsZeroFilled() = reused(32)

    @Test fun allocatedGapIsNeverCopiedAsOriginalData() {
        val file = fixture("safety/fat16-adversarial.img"); val reader = reader()
        export(reader, file, entry(reader, file, "_RAGLIVE.BIN")).use { recovered ->
            assertArrayEquals(ByteArray(512) { 'A'.code.toByte() } + ByteArray(512), recovered.input.readBytes())
            assertEquals(RecoveryGrade.POSSIBLY_GOOD, recovered.report.ranges.first().grade)
            assertEquals(RecoveryDisposition.ZERO_FILLED, recovered.report.ranges.last().disposition)
        }
    }
    @Test fun freeGapAndUnknownOverwritesRemainUncertainAndAreKept() {
        val file = fixture("safety/fat16-adversarial.img"); val reader = reader()
        for (suffix in listOf("_RAGFREE.BIN", "_ARTIAL.BIN", "_VERWR.BIN")) {
            export(reader, file, entry(reader, file, suffix)).use { recovered ->
                assertTrue(recovered.input.readBytes().isNotEmpty())
                assertTrue(recovered.report.ranges.all { it.disposition == RecoveryDisposition.COPIED_UNVERIFIED })
                assertTrue(recovered.report.ranges.none { it.grade == RecoveryGrade.GOOD || it.grade == RecoveryGrade.OVERWRITTEN })
                if (suffix == "_RAGFREE.BIN") assertEquals(RecoveryGrade.POSSIBLY_BAD, recovered.report.ranges.last().grade)
            }
        }
    }
    @Test fun secondFatAllocationAlsoProducesZeros() {
        val file = fixture("safety/fat12-adversarial.img"); val reader = reader()
        export(reader, file, entry(reader, file, "_ATCOPY.BIN")).use { recovered ->
            assertArrayEquals(ByteArray(512), recovered.input.readBytes())
            assertTrue(recovered.report.ranges.single().reason.contains("allocated_or_reused"))
            assertNull(recovered.report.magicMime)
        }
    }
    @Test fun tailBeyondVolumeIsZeroFilledWithoutOutOfBoundsReads() {
        val file = fixture("safety/fat16-partial-tail.img"); val reader = reader()
        val candidate = entry(reader, file, "_AIL.BIN")
        assertNotNull(candidate.readError)
        export(reader, file, candidate).use { recovered ->
            assertArrayEquals(ByteArray(512) { 'T'.code.toByte() } + ByteArray(512), recovered.input.readBytes())
            assertEquals("outside_validated_volume", recovered.report.ranges.last().reason)
        }
    }
    @Test fun whollyInvalidDeletedMetadataStillCannotBeSalvaged() {
        val file = fixture("safety/fat16-adversarial.img"); val reader = reader()
        val candidate = entry(reader, file, "_UTSIDE.BIN")
        assertNull(candidate.partialExtractor)
        assertThrows(IOException::class.java) { export(reader, file, candidate).close() }
        assertTrue(temp.root.walkTopDown().none { it.name.startsWith("fat-partial-") })
    }
    @Test fun emptyFileHasAnEmptyCoverageMap() {
        val file = fixture("fat16-deleted.img"); val reader = reader()
        export(reader, file, entry(reader, file, "_MPTY.TXT")).use { recovered ->
            assertEquals(0, recovered.input.readBytes().size)
            assertTrue(recovered.report.ranges.isEmpty())
            assertEquals(hash(byteArrayOf()), recovered.report.sha256)
        }
    }
    @Test fun magicUsesSurvivingHeaderEvenWhenLaterDataIsMissing() {
        val file = fixture("safety/fat16-magic-partial.img"); val reader = reader()
        for ((suffix, mime, mismatch) in listOf(Triple("_ISMATCH.PNG", "application/pdf", true), Triple("_ATCH.PNG", "image/png", false))) {
            export(reader, file, entry(reader, file, suffix)).use { recovered ->
                val bytes = recovered.input.readBytes()
                assertEquals(mime, recovered.report.magicMime)
                assertEquals(mismatch, recovered.report.magicMismatch)
                assertEquals(if (mismatch) RecoveryGrade.POSSIBLY_BAD else RecoveryGrade.POSSIBLY_GOOD, recovered.report.ranges.first().grade)
                assertEquals(512, recovered.report.magicInspectedBytes)
                assertArrayEquals(ByteArray(512), bytes.copyOfRange(512, 1024))
                assertTrue(recovered.report.ranges.none { it.grade == RecoveryGrade.GOOD })
            }
        }
    }
    @Test fun packagedZipHeadersDoNotBecomeFalseMimeMismatches() {
        for (extension in listOf("APK", "JAR")) {
            val (memory, start) = synthetic(); val fs = FatFileSystem(memory)
            extension.toByteArray().copyInto(memory.bytes, fs.rootOffset.toInt() + 8)
            byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4).copyInto(memory.bytes, start.toInt())
            val candidate = FatDeletedFiles(FatFileSystem(memory)).entries().single()
            val reader = reader()
            reader.openPartialDeletedFatEntry("packaged.img", candidate.path, { memory.bytes.inputStream() }, device = memory).use { recovered ->
                assertEquals("application/zip", recovered.report.magicMime)
                assertEquals("application/zip", recovered.report.expectedContainerMime)
                assertEquals(false, recovered.report.magicMismatch)
                assertEquals(RecoveryGrade.POSSIBLY_GOOD, recovered.report.ranges.first().grade)
            }
        }
    }

    @Test fun compressedAndPartitionedPartialCopiesCarryReports() {
        for (name in listOf("safety/fat16-deleted.img.gz", "safety/mbr-fat16-deleted.img.bz2", "safety/gpt-fat16-deleted.img")) {
            val file = fixture(name); val before = hash(file.readBytes()); val reader = reader()
            export(reader, file, entry(reader, file, "_ELETE.TXT")).use { recovered ->
                assertEquals("Known deleted short-name file. Recover this text.\n", String(recovered.input.readBytes()))
                assertEquals(file.name, recovered.report.sourceImage)
                if ("mbr-" in name || "gpt-" in name) assertTrue(recovered.report.partitionOffset > 0)
            }
            assertEquals(before, hash(file.readBytes()))
        }
    }

    private class Memory(val bytes: ByteArray) : BlockDevice {
        override val size get() = bytes.size.toLong()
        override val capabilities = setOf(DeviceCapability.READ, DeviceCapability.SEEK)
        var fault: LongRange? = null
        var cancelAt: Long? = null
        var cancelled = false
        val reads = mutableListOf<Pair<Long, Int>>()
        override fun readAt(offset: Long, buffer: ByteArray, dstOffset: Int, length: Int): Int {
            reads += offset to length
            if (cancelAt == offset) cancelled = true
            if (fault?.contains(offset) == true) throw IOException("Injected bad sector")
            var n = minOf(length, bytes.size - offset.toInt())
            fault?.let { if (offset < it.first && offset + n > it.first) n = (it.first - offset).toInt() }
            if (n <= 0) return 0
            bytes.copyInto(buffer, dstOffset, offset.toInt(), offset.toInt() + n)
            return n
        }
        override fun writeAt(offset: Long, buffer: ByteArray, srcOffset: Int, length: Int) = error("Recovery must not write source")
        override fun close() {}
    }
    private fun synthetic(): Pair<Memory, Long> {
        val bytes = fixture("fat16-deleted.img").readBytes()
        bytes[13] = 2 // 1024-byte clusters, retaining valid FAT16 geometry.
        val memory = Memory(bytes); val fs = FatFileSystem(memory)
        bytes.fill(0, fs.rootOffset.toInt(), fs.rootOffset.toInt() + fs.rootEntryCount * 32)
        val record = ByteArray(32); "BROKEN  BIN".toByteArray().copyInto(record)
        record[0] = 0xe5.toByte(); record[11] = 32; record[26] = 40
        record[28] = 0; record[29] = 6 // 1536 bytes
        record.copyInto(bytes, fs.rootOffset.toInt())
        val data = fs.dataStartOffset + 38 * fs.clusterSize
        ByteArray(512) { 'A'.code.toByte() }.copyInto(bytes, data.toInt())
        ByteArray(512) { 'B'.code.toByte() }.copyInto(bytes, data.toInt() + 512)
        ByteArray(512) { 'C'.code.toByte() }.copyInto(bytes, data.toInt() + 1024)
        return memory to data
    }
    @Test fun badSectorDoesNotDiscardOtherReadableSectorsInTheCluster() {
        val (memory, start) = synthetic(); memory.fault = start until start + 512
        val entry = FatDeletedFiles(FatFileSystem(memory)).entries().single()
        val output = ByteArrayOutputStream(); val report = entry.partialExtractor!!(memory, output)
        assertArrayEquals(ByteArray(512) + ByteArray(512) { 'B'.code.toByte() } + ByteArray(512) { 'C'.code.toByte() }, output.toByteArray())
        assertEquals(512L, report.ranges.filter { it.grade == RecoveryGrade.UNAVAILABLE }.sumOf { it.length })
        assertEquals(1024L, report.ranges.filter { it.disposition == RecoveryDisposition.COPIED_UNVERIFIED }.sumOf { it.length })
    }
    @Test fun readablePrefixBeforeAnIoFailureIsRetainedAtByteOffsets() {
        val (memory, start) = synthetic(); memory.fault = start + 100 until start + 512
        val entry = FatDeletedFiles(FatFileSystem(memory)).entries().single()
        val output = ByteArrayOutputStream(); val report = entry.partialExtractor!!(memory, output)
        assertEquals(100L, report.ranges.first().length)
        assertArrayEquals(ByteArray(100) { 'A'.code.toByte() }, output.toByteArray().copyOf(100))
        assertArrayEquals(ByteArray(412), output.toByteArray().copyOfRange(100, 512))
        assertEquals(412L, report.ranges.filter { it.disposition == RecoveryDisposition.ZERO_FILLED }.sumOf { it.length })
    }
    @Test fun allocatedPayloadIsNotReadByThePartialExtractor() {
        val memory = Memory(fixture("safety/fat16-adversarial.img").readBytes()); val fs = FatFileSystem(memory)
        val candidate = FatDeletedFiles(fs).entries().single { it.path.endsWith("_LLOC.BIN") }
        val allocatedStart = fs.dataStartOffset + 48 * fs.clusterSize
        memory.reads.clear()
        candidate.partialExtractor!!(memory, ByteArrayOutputStream())
        assertTrue(memory.reads.none { (offset, count) -> offset < allocatedStart + fs.clusterSize && offset + count > allocatedStart })
    }
    @Test fun cancellationIsNotConvertedIntoMissingData() {
        val (memory, start) = synthetic(); memory.cancelAt = start
        val candidate = FatDeletedFiles(FatFileSystem(memory)).entries().single()
        assertThrows(InterruptedIOException::class.java) {
            RecoveryCancellation.withCancellation({ memory.cancelled }) { candidate.partialExtractor!!(memory, ByteArrayOutputStream()) }
        }
    }
    @Test fun fatBadClusterMarkerIsReportedAsUnavailableRatherThanProvenOverwrite() {
        val memory = Memory(fixture("fat16-deleted.img").readBytes()); val fs = FatFileSystem(memory)
        val candidate = FatDeletedFiles(fs).entries().single { it.path.endsWith("_ELETE.TXT") }
        val slot = candidate.path.substringAfter("deleted-").substringBefore('-').toInt(16)
        val cluster = (memory.bytes[slot + 26].toInt() and 255) or ((memory.bytes[slot + 27].toInt() and 255) shl 8)
        for (i in 0 until fs.numFats) {
            val offset = (fs.fatStartOffset + i * fs.sectorsPerFat * fs.bytesPerSector + cluster * 2).toInt()
            memory.bytes[offset] = 0xf7.toByte(); memory.bytes[offset + 1] = 0xff.toByte()
        }
        val updated = FatDeletedFiles(FatFileSystem(memory)).entries().single { it.path.endsWith("_ELETE.TXT") }
        val output = ByteArrayOutputStream(); val report = updated.partialExtractor!!(memory, output)
        assertArrayEquals(ByteArray(updated.sizeBytes!!.toInt()), output.toByteArray())
        assertEquals(RecoveryGrade.UNAVAILABLE, report.ranges.single().grade)
        assertTrue(report.ranges.single().reason.contains("marked_bad_cluster"))
    }
    @Test fun malformedReportsCannotClaimIncompleteOrOverlappingCoverage() {
        val range = RecoveryRange(1, 3, RecoveryGrade.POSSIBLY_GOOD, RecoveryDisposition.COPIED_UNVERIFIED, "test")
        assertThrows(IllegalArgumentException::class.java) { RecoveryReport("x", 4, "FAT16", listOf(range), hash(byteArrayOf()), "test") }
    }
}
