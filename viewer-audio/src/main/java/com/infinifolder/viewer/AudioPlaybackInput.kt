package com.infinifolder.viewer

import android.content.Context
import android.net.Uri
import com.infinifolder.audio.AudioProcessing
import com.infinifolder.audio.AudioWaveSource
import com.infinifolder.audio.decoder.UniversalAudioDecoder
import io.github.gretchycat.infinistream.*
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicBoolean

/** Host-owned encoded input/lease. Parsers and codec decisions remain in Sound. */
class AudioPlaybackInput private constructor(
    private var shared: SharedPositionalResource?,
    private var staged: MediaSourceAdapter.StagedSource?,
    private val stage: () -> MediaSourceAdapter.StagedSource
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val lock = Any()
    val metadata: StreamMetadata = shared?.metadata
        ?: FileSourceResource(staged?.file ?: throw IOException("No audio input")).metadata
    val file: File? get() = synchronized(lock) { staged?.file }
    val resource: SourceResource get() = synchronized(lock) {
        checkOpen()
        shared ?: FileSourceResource(staged?.file ?: throw IOException("No audio input"))
    }
    val access: String get() = synchronized(lock) { if (shared == null) "file" else "positional-source" }
    private fun checkOpen() {
        if (closed.get() || Thread.currentThread().isInterrupted) throw InterruptedIOException("Audio input closed or cancelled")
    }

    fun openWaveSource(output: File, fileOverride: File? = null, reportDiagnostics: Boolean = true,
        cancelled: () -> Boolean = { false }): AudioWaveSource {
        checkOpen()
        val local = fileOverride ?: file
        if (local != null) return UniversalAudioDecoder.openWaveSource(local, output, reportDiagnostics, cancelled)
        try { return UniversalAudioDecoder.openWaveSource(resource, output, reportDiagnostics, cancelled) }
        catch (cancel: InterruptedIOException) { throw cancel }
        catch (error: Exception) {
            checkOpen()
            if (cancelled()) throw InterruptedIOException("Audio input cancelled")
            // Preserve decoders/providers which work only with the existing staged-file route.
            com.infinifolder.storage.DiagnosticLog.record("audio.source.staging", "reason=direct-open-failed", error)
            val old = synchronized(lock) { shared.also { shared = null } }
            runCatching { old?.close() }.onFailure { close ->
                com.infinifolder.storage.DiagnosticLog.record("audio.source.close.warning", error = close)
            }
            val replacement = stage()
            synchronized(lock) {
                if (closed.get() || cancelled() || Thread.currentThread().isInterrupted) {
                    replacement.close()
                    throw InterruptedIOException("Audio staging cancelled")
                }
                staged = replacement
            }
            return UniversalAudioDecoder.openWaveSource(replacement.file, output, reportDiagnostics, cancelled)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            val owners = synchronized(lock) { (shared to staged).also { shared = null; staged = null } }
            try { owners.first?.close() } finally { owners.second?.close() }
        }
    }

    companion object {
        fun open(context: Context, uri: Uri, name: String, requiresFile: Boolean = false): AudioPlaybackInput {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Audio input cancelled")
            val stage = { MediaSourceAdapter.stage(context, uri, name) }
            if (requiresFile || MediaSourceAdapter.resolveLocalFile(context, uri)?.canRead() == true)
                return AudioPlaybackInput(null, stage(), stage)
            val source = MediaSourceAdapter.openPositionalSource(context, uri)
                ?: return AudioPlaybackInput(null, stage(), stage)
            return select(source, stage, AudioProcessing::requiresLocalFileForPlayback, name)
        }

        /** Also used by contract tests; unsupported capability is distinct from a read failure. */
        fun select(source: ByteSource, stage: () -> MediaSourceAdapter.StagedSource,
            needsFile: (SourceResource) -> Boolean, name: String = "audio"): AudioPlaybackInput {
            val info = try {
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Audio input cancelled")
                source.metadata
            } catch (error: Throwable) {
                try { source.close() } catch (close: Throwable) { if (close !== error) error.addSuppressed(close) }
                throw error
            }
            if (source !is PositionalReadable || info.length == null) {
                source.close()
                return AudioPlaybackInput(null, stage(), stage)
            }
            var shared: SharedPositionalResource? = null
            try {
                shared = SharedPositionalResource(source, info.copy(name = info.name ?: name))
                if (!needsFile(shared)) return AudioPlaybackInput(shared, null, stage)
            } catch (error: Throwable) {
                try { if (shared == null) source.close() else shared.close() }
                catch (close: Throwable) { if (close !== error) error.addSuppressed(close) }
                throw error
            }
            shared.close()
            return AudioPlaybackInput(null, stage(), stage)
        }
    }
}
