package com.infinifolder.audio

import com.infinifolder.audio.engine.PcmMediaDataSource
import io.github.gretchycat.infinistream.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PcmMediaDataSourceTest {
    @Test fun outputReadsTheLinkedPcmSourceAtTheRequestedPosition() {
        var closes = 0
        var lastPosition = -1L
        val source = object : ByteSource, PositionalReadable {
            override val metadata = StreamMetadata(ContentType(DataKind.ENCODED_AUDIO, "audio/wav"), length = 400L)
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = error("No shared cursor")
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                lastPosition = position
                if (length == 0) return 0
                if (position >= 400) return -1
                buffer[offset] = 42
                return 1 // Legal short reads must pass through unchanged.
            }
            override fun close() { closes++ }
        }
        val output = PcmMediaDataSource(source)
        assertEquals(400L, output.getSize())
        val bytes = ByteArray(10)
        assertEquals(1, output.readAt(123, bytes, 3, 7))
        assertEquals(123L, lastPosition)
        assertEquals(42, bytes[3].toInt())
        assertEquals(-1, output.readAt(400, bytes, 0, 10))
        assertEquals(0, output.readAt(400, bytes, 0, 0))
        output.close()
        assertEquals(1, closes)
    }

    @Test fun outputDoesNotTurnSourceFailuresIntoEof() {
        val failure = IOException("Provider read failed")
        val source = object : ByteSource, PositionalReadable {
            override val metadata = StreamMetadata(ContentType(DataKind.ENCODED_AUDIO, "audio/wav"), length = 100)
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw failure
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int = throw failure
            override fun close() {}
        }
        assertSame(failure, assertThrows(IOException::class.java) { PcmMediaDataSource(source).readAt(0, ByteArray(10), 0, 10) })
    }
}
