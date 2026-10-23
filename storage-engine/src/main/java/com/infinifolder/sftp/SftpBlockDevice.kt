package com.infinifolder.sftp

import com.infinifolder.archive.BlockDevice
import com.infinifolder.archive.DeviceCapability
import com.jcraft.jsch.ChannelSftp
import java.io.IOException

/**
 * Random-access BlockDevice backed by an SFTP remote file using JSch.
 * Reads are performed at specific offsets using ChannelSftp.get(..., skip).
 * Writes are disabled until the adapter has a verified non-truncating offset-write implementation.
 */
class SftpBlockDevice(
    private val clientPool: SftpClientPool,
    private val config: SftpConfig,
    private val credentials: SftpCredentials,
    val remotePath: String,
    override val isReadOnly: Boolean = true,
    initialSize: Long? = null
) : BlockDevice {

    init { require(isReadOnly) { "SFTP random writes are not supported by this adapter" } }

    private var channel: ChannelSftp? = null
    private var closed = false
    private var currentSize: Long = initialSize ?: fetchSize()

    private fun fetchSize(): Long = clientPool.withChannel(config, credentials) { channel ->
        channel.stat(remotePath).size
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

    @Synchronized override fun readAt(offset: Long, buffer: ByteArray, dstOffset: Int, length: Int): Int {
        if (offset < 0 || offset >= currentSize || length <= 0) return 0
        val toRead = length.toLong().coerceAtMost(currentSize - offset).toInt()

        check(!closed) { "SFTP range reader is closed" }
        return try {
            val reader = channel ?: clientPool.openReadChannel(config, credentials).also { channel = it }
            val stream = reader.get(remotePath, null, offset)
            stream.use { inStream ->
                var total = 0
                while (total < toRead) {
                    val r = inStream.read(buffer, dstOffset + total, toRead - total)
                    if (r <= 0) break
                    total += r
                }
                total
            }
        } catch (e: Exception) {
            channel?.disconnect()
            channel = null
            throw IOException("SFTP range read failed at offset $offset ($toRead bytes)", e)
        }
    }

    override fun writeAt(offset: Long, buffer: ByteArray, srcOffset: Int, length: Int) {
        throw IOException("SFTP random writes are not supported by this adapter")
    }

    override fun flush() {}

    @Synchronized override fun close() {
        closed = true
        channel?.disconnect()
        channel = null
    }
}
