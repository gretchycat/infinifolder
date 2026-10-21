package com.infinifolder.archive

import java.io.Closeable
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Low-level random-access block abstraction allowing transparent read and write
 * across raw files, partition slices, and virtual machine disk containers
 * (VDI, QCOW2, VMDK, VHD, VHDX).
 */
interface BlockDevice : Closeable {
    val size: Long
    val isReadOnly: Boolean

    @Throws(IOException::class)
    fun readAt(offset: Long, buffer: ByteArray, dstOffset: Int = 0, length: Int = buffer.size): Int

    @Throws(IOException::class)
    fun writeAt(offset: Long, buffer: ByteArray, srcOffset: Int = 0, length: Int = buffer.size)

    fun flush() {}
}

fun BlockDevice.readBytes(offset: Long, length: Int): ByteArray {
    if (length <= 0 || offset >= size) return ByteArray(0)
    val toRead = length.toLong().coerceAtMost(size - offset).toInt()
    val buf = ByteArray(toRead)
    var total = 0
    while (total < toRead) {
        val read = readAt(offset + total, buf, total, toRead - total)
        if (read <= 0) break
        total += read
    }
    return if (total == toRead) buf else buf.copyOf(total)
}

fun BlockDevice.writeBytes(offset: Long, data: ByteArray) {
    if (data.isNotEmpty()) {
        writeAt(offset, data, 0, data.size)
    }
}

/**
 * Standard BlockDevice backed directly by a RandomAccessFile.
 */
class FileBlockDevice(
    private val raf: RandomAccessFile,
    override val isReadOnly: Boolean = false,
    override val size: Long = raf.length()
) : BlockDevice {

    override fun readAt(offset: Long, buffer: ByteArray, dstOffset: Int, length: Int): Int {
        if (offset < 0 || offset >= size || length <= 0) return 0
        val toRead = length.toLong().coerceAtMost(size - offset).toInt()
        synchronized(raf) {
            raf.seek(offset)
            val bytesRead = raf.read(buffer, dstOffset, toRead)
            return if (bytesRead < 0) 0 else bytesRead
        }
    }

    override fun writeAt(offset: Long, buffer: ByteArray, srcOffset: Int, length: Int) {
        if (isReadOnly) throw IOException("BlockDevice is read-only")
        if (offset < 0 || length < 0) throw IOException("Invalid offset or length (offset=$offset, length=$length)")
        synchronized(raf) {
            raf.seek(offset)
            raf.write(buffer, srcOffset, length)
        }
    }

    override fun flush() {
        synchronized(raf) {
            try {
                raf.channel.force(true)
            } catch (_: Exception) {}
        }
    }

    override fun close() {
        // raf is closed by its caller / owner
    }
}

/**
 * Sub-slice of a parent BlockDevice (e.g. an MBR or GPT partition).
 */
class PartitionSliceBlockDevice(
    val parent: BlockDevice,
    val startOffset: Long,
    override val size: Long
) : BlockDevice {

    override val isReadOnly: Boolean get() = parent.isReadOnly

    override fun readAt(offset: Long, buffer: ByteArray, dstOffset: Int, length: Int): Int {
        if (offset < 0 || offset >= size || length <= 0) return 0
        val toRead = length.toLong().coerceAtMost(size - offset).toInt()
        return parent.readAt(startOffset + offset, buffer, dstOffset, toRead)
    }

    override fun writeAt(offset: Long, buffer: ByteArray, srcOffset: Int, length: Int) {
        if (offset < 0 || offset + length > size) {
            throw IOException("Write out of partition bounds (offset=$offset, len=$length, partSize=$size)")
        }
        parent.writeAt(startOffset + offset, buffer, srcOffset, length)
    }

    override fun flush() = parent.flush()

    override fun close() {
        // Slices do not close the parent device
    }
}
