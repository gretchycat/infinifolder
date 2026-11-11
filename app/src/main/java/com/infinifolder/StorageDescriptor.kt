package com.infinifolder

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import com.infinifolder.archive.DeviceCapability
import com.infinifolder.model.*
import com.infinifolder.storage.*
import java.io.FileNotFoundException
import java.util.concurrent.Semaphore

/** Owns each backend handle until its Android descriptor is released. Never stages to emulate seeking. */
internal object StorageDescriptor {
    private val slots = Semaphore(32)
    fun open(context: Context, provider: StorageProvider, ref: ResourceRef, mode: String,
        opened: ProviderResult<com.infinifolder.archive.BlockDevice>? = null): ParcelFileDescriptor {
        require(mode in setOf("r", "w", "wt", "rw", "rwt")) { "Unsupported descriptor mode: $mode" }
        val writable = mode != "r"
        when (val result = validateSeekableResult(opened ?: provider.openNativeSeekable(ref, writable), writable)) {
            is ProviderResult.Success -> {
                val device = result.value
                if (mode in setOf("w", "wt") && DeviceCapability.TRUNCATE !in device.capabilities) {
                    device.close()
                    return pipe(provider, ref, writable)
                }
                if (!slots.tryAcquire()) { device.close(); throw FileNotFoundException("Too many open storage handles") }
                val thread = HandlerThread("StorageDescriptor").apply { start() }
                try {
                    if (mode in setOf("w", "wt", "rwt")) {
                        if (DeviceCapability.TRUNCATE !in device.capabilities)
                            throw FileNotFoundException("This source cannot truncate through a random-access handle")
                        device.truncate(0)
                    }
                    val access = if (writable) ParcelFileDescriptor.MODE_READ_WRITE else ParcelFileDescriptor.MODE_READ_ONLY
                    return context.getSystemService(StorageManager::class.java).openProxyFileDescriptor(access,
                        object : ProxyFileDescriptorCallback() {
                            override fun onGetSize(): Long = io { device.size }
                            override fun onRead(offset: Long, size: Int, data: ByteArray): Int = io {
                                StorageProxyReads.read(device, offset, size, data)
                            }
                            override fun onWrite(offset: Long, size: Int, data: ByteArray): Int = io {
                                if (!writable) throw ErrnoException("write", OsConstants.EBADF)
                                device.writeAt(offset, data, 0, size)
                                size
                            }
                            override fun onFsync() { io { if (writable) device.flush() } }
                            override fun onRelease() {
                                try { device.close() }
                                catch (error: Exception) { DiagnosticLog.record("storage.descriptor.close-failed", error = error) }
                                finally { thread.quitSafely(); slots.release() }
                            }
                        }, Handler(thread.looper))
                } catch (error: Throwable) {
                    try { device.close() } catch (close: Throwable) { error.addSuppressed(close) }
                    thread.quitSafely(); slots.release(); throw error
                }
            }
            is ProviderResult.Failure -> throw FileNotFoundException(result.message).apply { initCause(result.cause) }
            is ProviderResult.Unsupported -> {
                if (mode in setOf("rw", "rwt")) throw FileNotFoundException("Random access is unsupported by this source")
                return pipe(provider, ref, writable)
            }
        }
    }

    private fun pipe(provider: StorageProvider, ref: ResourceRef, writable: Boolean): ParcelFileDescriptor {
        if (!slots.tryAcquire()) throw FileNotFoundException("Too many open storage streams")
        val pair = try { ParcelFileDescriptor.createReliablePipe() } catch (error: Throwable) { slots.release(); throw error }
        val worker = if (writable) pair[0] else pair[1]
        try {
            Thread({
                try {
                    if (writable) {
                        val input = ParcelFileDescriptor.AutoCloseInputStream(worker)
                        when (val result = provider.openWrite(ref)) {
                                is ProviderResult.Success -> result.value.use { output -> input.copyTo(output); worker.checkError() }
                                is ProviderResult.Failure -> throw java.io.IOException(result.message, result.cause)
                                is ProviderResult.Unsupported -> throw java.io.IOException("Writing is unsupported")
                        }
                    } else {
                        val output = ParcelFileDescriptor.AutoCloseOutputStream(worker)
                        when (val result = provider.openRead(ref)) {
                                is ProviderResult.Success -> result.value.use { input -> input.copyTo(output) }
                                is ProviderResult.Failure -> throw java.io.IOException(result.message, result.cause)
                                is ProviderResult.Unsupported -> throw java.io.IOException("Reading is unsupported")
                        }
                    }
                } catch (error: Exception) {
                    runCatching { worker.closeWithError(error.message ?: "Storage I/O failed") }
                } finally { runCatching { worker.close() }; slots.release() }
            }, "StorageStream").apply { isDaemon = true; start() }
        } catch (error: Throwable) { pair.forEach { runCatching { it.close() } }; slots.release(); throw error }
        return if (writable) pair[1] else pair[0]
    }
    private inline fun <T> io(work: () -> T): T = try { work() }
    catch (error: ErrnoException) { throw error }
    catch (error: Exception) { throw ErrnoException("storage", OsConstants.EIO, error) }
}
