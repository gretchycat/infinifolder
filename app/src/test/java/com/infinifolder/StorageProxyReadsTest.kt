package com.infinifolder

import com.infinifolder.archive.BlockDevice
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class StorageProxyReadsTest {
    private class Device(private val data: ByteArray, private val chunk: Int) : BlockDevice {
        override val size get() = data.size.toLong()
        override val isReadOnly = true
        override fun readAt(offset: Long, buffer: ByteArray, dstOffset: Int, length: Int): Int {
            val count = minOf(chunk, length, (size - offset).coerceAtLeast(0).toInt())
            data.copyInto(buffer, dstOffset, offset.toInt(), offset.toInt() + count)
            return count
        }
        override fun writeAt(offset: Long, buffer: ByteArray, srcOffset: Int, length: Int) = throw IOException("read only")
        override fun flush() {}
        override fun close() {}
    }
    @Test fun partialBackendReadsFillTheAndroidRequest() {
        val bytes = ByteArray(12) { it.toByte() }
        val output = ByteArray(8)
        assertEquals(8, StorageProxyReads.read(Device(bytes, 2), 3, 8, output))
        assertArrayEquals(bytes.copyOfRange(3, 11), output)
    }
    @Test fun endOfFileReturnsOnlyRemainingBytes() {
        val output = ByteArray(8)
        assertEquals(2, StorageProxyReads.read(Device(byteArrayOf(1, 2, 3), 2), 1, 8, output))
        assertEquals(0, StorageProxyReads.read(Device(byteArrayOf(1), 1), 5, 8, output))
    }
    @Test(expected = IOException::class) fun stalledReadDoesNotMasqueradeAsEndOfFile() {
        StorageProxyReads.read(Device(byteArrayOf(1), 0), 0, 1, ByteArray(1))
    }
    @Test(expected = IOException::class) fun invalidRangeFailsBeforeReading() {
        StorageProxyReads.read(Device(byteArrayOf(1), 1), -1, 1, ByteArray(1))
    }
}
