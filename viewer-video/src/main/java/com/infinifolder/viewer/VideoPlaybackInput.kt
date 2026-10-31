package com.infinifolder.viewer

import android.content.Context
import android.net.Uri
import io.github.gretchycat.infinistream.*
import java.io.Closeable
import java.io.File
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicBoolean

/** Host owns the provider/file lease; Video owns native transport and decoding. */
class VideoPlaybackInput private constructor(val file: File?, val resource: SourceResource,
    val metadata: StreamMetadata, val access: String, private val owner: Closeable?) : Closeable {
    private val closed = AtomicBoolean(false)
    override fun close() { if (closed.compareAndSet(false, true)) owner?.close() }

    companion object {
        fun open(context: Context, uri: Uri): VideoPlaybackInput {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Video input cancelled")
            val app = context.applicationContext
            val logical = MediaSourceAdapter.resource(app, uri)
            val local = MediaSourceAdapter.resolveLocalFile(app, uri)?.takeIf { it.canRead() }
            if (local != null) {
                val lease = com.infinifolder.storage.DiskCache.get(app).pin(local)
                return VideoPlaybackInput(local, FileSourceResource(local), logical.metadata, "local-file", lease)
            }
            val positional = MediaSourceAdapter.openStreamingPositionalSource(app, uri)
            if (positional != null) {
                try {
                    if (positional is PositionalReadable && positional.metadata.length != null) {
                        val actual = positional.metadata.copy(content = logical.metadata.content,
                            name = logical.metadata.name, provenance = logical.metadata.provenance,
                            warnings = (logical.metadata.warnings + positional.metadata.warnings).distinct())
                        val shared = SharedPositionalResource(positional, actual)
                        return VideoPlaybackInput(null, shared, shared.metadata, "positional-source", shared)
                    }
                } catch (error: Throwable) {
                    try { positional.close() } catch (close: Throwable) { error.addSuppressed(close) }
                    throw error
                }
                positional.close()
            }
            // Reopenable sequential source: range seeks replay a prefix in bounded chunks.
            // Opening a stream is deferred until the native decoder actually requests bytes.
            return VideoPlaybackInput(null, logical, logical.metadata, "sequential-source", null)
        }
    }
}
