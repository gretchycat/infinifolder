package com.infinifolder.viewer

import io.github.gretchycat.infinistream.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.*

class AudioPlaybackInputTest {
    @get:Rule val temporary = TemporaryFolder()
    private class Input(private val length: Long? = 6) : ByteSource, PositionalReadable {
        override val metadata = StreamMetadata(name = "song.flac", length = length,
            provenance = Provenance(source = "sftp:test/song.flac"), warnings = listOf("hint"))
        var closed = 0
        var reads = 0
        override fun read(buffer: ByteArray, offset: Int, length: Int) = readAt(0, buffer, offset, length)
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            reads++; val n = minOf(length, 6 - position.toInt())
            "abcdef".toByteArray().copyInto(buffer, offset, position.toInt(), position.toInt() + n)
            return n
        }
        override fun close() { closed++ }
    }
    @Test fun positionalPlaybackKeepsOneOwnerAndNeverStagesTheEncodedSource() {
        val input = Input()
        val session = AudioPlaybackInput.select(input, { error("Unexpected staging") }, { resource ->
            resource.open().use { view -> (view as PositionalReadable).readAt(2, ByteArray(1)) }; false
        })
        assertNull(session.file); assertEquals("positional-source", session.access)
        assertEquals("sftp:test/song.flac", session.resource.metadata.provenance.source)
        assertEquals(listOf("hint"), session.resource.metadata.warnings)
        assertEquals(1, input.reads); assertEquals(0, input.closed)
        val a = session.resource.open(); val b = session.resource.open(); a.close()
        assertEquals(1, b.read(ByteArray(1))); assertEquals(0, input.closed)
        val snapshot = session.metadata
        session.close(); session.close(); assertEquals(1, input.closed)
        assertSame(snapshot, session.metadata)
        assertThrows(IOException::class.java) { b.read(ByteArray(1)) }
        assertThrows(IOException::class.java) { session.resource }
    }
    @Test fun sequentialSourceStagesAndKeepsTheFileLeaseUntilClosed() {
        var sourceClosed = 0; var staged = 0; var leaseClosed = 0
        val source = object : ByteSource {
            override val metadata = StreamMetadata()
            override fun read(buffer: ByteArray, offset: Int, length: Int) = -1
            override fun close() { sourceClosed++ }
        }
        val file = temporary.newFile("audio.mp3")
        AudioPlaybackInput.select(source, {
            staged++; assertEquals(1, sourceClosed)
            MediaSourceAdapter.StagedSource(file, Closeable { leaseClosed++ })
        }, { error("Cannot probe sequential source as positional") }).use { session ->
            assertEquals(file, session.file); assertEquals("file", session.access)
            assertEquals(0, leaseClosed)
        }
        assertEquals(1, staged); assertEquals(1, leaseClosed)
    }
    @Test fun unknownLengthIsExplicitlyStaged() {
        val input = Input(null); var staged = 0
        AudioPlaybackInput.select(input, { staged++; MediaSourceAdapter.StagedSource(temporary.newFile()) }, { error("No known length") }).close()
        assertEquals(1, input.closed); assertEquals(1, staged); assertEquals(0, input.reads)
    }
    @Test fun libraryCanRequireAFileForCompanionResources() {
        val input = Input(); val file = temporary.newFile("module.it")
        AudioPlaybackInput.select(input, { assertEquals(1, input.closed); MediaSourceAdapter.StagedSource(file) }, { true }).use {
            assertEquals(file, it.file)
        }
        assertEquals(1, input.closed)
    }
    @Test fun cancelledPolicyNeverStagesAndClosesTheOpenedHandle() {
        val input = Input()
        assertThrows(InterruptedIOException::class.java) {
            AudioPlaybackInput.select(input, { error("Cancelled source must not stage") }, { throw InterruptedIOException("cancelled") })
        }
        assertEquals(1, input.closed)
    }
    @Test fun policyErrorsPropagateWithoutLeakingTheSource() {
        val input = Input()
        assertEquals("network failure", assertThrows(IOException::class.java) {
            AudioPlaybackInput.select(input, { error("Do not hide failures") }, { throw IOException("network failure") })
        }.message)
        assertEquals(1, input.closed)
    }
    @Test fun stagingFailureKeepsItsCauseAndClosesTheOriginalSource() {
        val input = Input(null)
        assertEquals("full cache", assertThrows(IOException::class.java) {
            AudioPlaybackInput.select(input, { throw IOException("full cache") }, { false })
        }.message)
        assertEquals(1, input.closed)
    }
    @Test fun failedMetadataDiscoveryClosesTheHandleAndKeepsTheOriginalError() {
        var closes = 0
        val problem = IOException("metadata failed")
        val source = object : ByteSource {
            override val metadata: StreamMetadata get() = throw problem
            override fun read(buffer: ByteArray, offset: Int, length: Int) = -1
            override fun close() { closes++; throw IOException("close failed") }
        }
        val error = assertThrows(IOException::class.java) {
            AudioPlaybackInput.select(source, { error("No staging after metadata failure") }, { false })
        }
        assertSame(problem, error); assertEquals(1, closes)
        assertEquals("close failed", error.suppressed.single().message)
    }
    @Test fun alreadyInterruptedSelectionClosesWithoutProbingOrStaging() {
        val input = Input()
        Thread.currentThread().interrupt()
        try { assertThrows(InterruptedIOException::class.java) {
            AudioPlaybackInput.select(input, { error("No stage after cancellation") }, { error("No probe after cancellation") })
        } } finally { Thread.interrupted() }
        assertEquals(1, input.closed); assertEquals(0, input.reads)
    }

}
