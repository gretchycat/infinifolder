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
    private var readCount = 0L
    private var bytesReturned = 0L
    private var lastReportMs = 0L

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
        val started = android.os.SystemClock.uptimeMillis()
        var stage = "opening-channel"
        var total = 0
        return try {
            // Each range closes early. Large streaming read-ahead queues must
            // otherwise be drained on every close, even for a tiny metadata read.
            val reader = channel ?: clientPool.openReadChannel(config, credentials, bulkRequests = 1).also { channel = it }
            stage = "opening-range"
            val stream = reader.get(remotePath, null, offset)
            stream.use { inStream ->
                stage = "reading-range"
                while (total < toRead) {
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("SFTP range read interrupted")
                    val r = inStream.read(buffer, dstOffset + total, toRead - total)
                    if (r <= 0) break
                    total += r
                }
                stage = "closing-range"
                total
            }.also {
                readCount++
                bytesReturned += it
                val now = android.os.SystemClock.uptimeMillis()
                if (now - started >= 1000L || now - lastReportMs >= 5000L) {
                    lastReportMs = now
                    com.infinifolder.storage.DiagnosticLog.record(
                        "sftp.range.progress", "reader=${System.identityHashCode(this)} offset=$offset requested=$toRead returned=$it elapsedMs=${now - started} reads=$readCount bytes=$bytesReturned bulkRequests=1"
                    )
                }
            }
        } catch (e: Exception) {
            val causes = generateSequence(e as Throwable) { it.cause }.take(4)
                .joinToString(" -> ") { "${it.javaClass.simpleName}: ${it.message}" }
            com.infinifolder.storage.DiagnosticLog.record(
                "sftp.range.failed", "reader=${System.identityHashCode(this)} stage=$stage offset=$offset requested=$toRead returned=$total elapsedMs=${android.os.SystemClock.uptimeMillis() - started} reads=$readCount bytes=$bytesReturned causes=$causes", e
            )
            channel?.disconnect()
            channel = null
            throw IOException("SFTP range read failed during $stage at offset $offset ($toRead bytes): ${e.message}", e)
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
