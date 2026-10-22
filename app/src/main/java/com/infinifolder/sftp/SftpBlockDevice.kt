package com.infinifolder.sftp

import com.infinifolder.archive.BlockDevice
import com.infinifolder.archive.DeviceCapability
import com.jcraft.jsch.ChannelSftp
import java.io.IOException

/**
 * Random-access BlockDevice backed by an SFTP remote file using JSch.
 * Reads are performed at specific offsets using ChannelSftp.get(..., skip).
 * Writes are performed at specific offsets using ChannelSftp.put(..., ChannelSftp.RESUME, offset).
 */
class SftpBlockDevice(
    private val clientPool: SftpClientPool,
    private val config: SftpConfig,
    private val credentials: SftpCredentials,
    val remotePath: String,
    override val isReadOnly: Boolean = false,
    initialSize: Long? = null
) : BlockDevice {

    private var currentSize: Long = initialSize ?: fetchSize()

    private fun fetchSize(): Long = clientPool.withChannel(config, credentials) { channel ->
        try {
            channel.stat(remotePath).size
        } catch (_: Exception) {
            0L
        }
    }

    override val size: Long get() = currentSize

    override val capabilities: Set<DeviceCapability>
        get() = if (isReadOnly) {
            setOf(DeviceCapability.READ, DeviceCapability.SEEK)
        } else {
            setOf(
                DeviceCapability.READ,
                DeviceCapability.WRITE,
                DeviceCapability.FLUSH,
                DeviceCapability.SEEK
            )
        }

    override fun readAt(offset: Long, buffer: ByteArray, dstOffset: Int, length: Int): Int {
        if (offset < 0 || offset >= currentSize || length <= 0) return 0
        val toRead = length.toLong().coerceAtMost(currentSize - offset).toInt()

        return clientPool.withChannel(config, credentials) { channel ->
            val stream = channel.get(remotePath, null, offset)
            stream.use { inStream ->
                var total = 0
                while (total < toRead) {
                    val r = inStream.read(buffer, dstOffset + total, toRead - total)
                    if (r <= 0) break
                    total += r
                }
                total
            }
        }
    }

    override fun writeAt(offset: Long, buffer: ByteArray, srcOffset: Int, length: Int) {
        if (isReadOnly) throw IOException("SftpBlockDevice is read-only")
        if (offset < 0 || length < 0) throw IOException("Invalid offset or length (offset=$offset, length=$length)")

        clientPool.withChannel(config, credentials) { channel ->
            val out = channel.put(remotePath, null, ChannelSftp.RESUME, offset)
            out.use {
                it.write(buffer, srcOffset, length)
            }
        }
        if (offset + length > currentSize) {
            currentSize = offset + length
        }
    }

    override fun flush() {}

    override fun close() {}
}
