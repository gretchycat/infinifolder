package com.infinifolder.audio.cast

import android.content.Context
import android.net.Uri
import com.infinifolder.audio.AudioWaveSource
import com.infinifolder.audio.PcmOutputOptions
import com.infinifolder.viewer.AudioPlaybackInput
import com.infinifolder.viewer.AudioTrack
import io.github.gretchycat.infinistream.*
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.Locale

/**
 * Receiver format policy and host-owned source leases. All PCM conversion is in Sound;
 * HTTP requests borrow positional views of the same encoded or decoded source.
 */
object AudioTranscoder {

    private val DIRECT_CAST_EXTENSIONS = setOf(
        "mp3", "m4a", "aac", "flac", "ogg", "oga", "wav"
    )

    fun isDirectlyPlayableByCast(uri: Uri, name: String?): Boolean {
        val ext = (name ?: uri.lastPathSegment ?: "").substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext in DIRECT_CAST_EXTENSIONS
    }

    fun getMimeType(uri: Uri, name: String?): String {
        val ext = (name ?: uri.lastPathSegment ?: "").substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (ext) {
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "flac" -> "audio/flac"
            "ogg", "oga" -> "audio/ogg"
            "wav" -> "audio/wav"
            else -> "audio/wav" // All incompatible/transcoded formats stream as standard PCM WAV
        }
    }

    internal fun open(context: Context, track: AudioTrack, cancelled: () -> Boolean): PreparedAudio {
        if (cancelled() || Thread.currentThread().isInterrupted) throw InterruptedIOException("Cast source cancelled")
        val direct = isDirectlyPlayableByCast(track.uri,track.fileName)
        val input = AudioPlaybackInput.open(context,track.uri,track.fileName,metadataOnly = direct)
        var wave: AudioWaveSource? = null
        var scratch: File? = null
        try {
            if (direct) return PreparedAudio(window(input.resource,track,getMimeType(track.uri,track.fileName)),input)
            scratch = File.createTempFile("cast_pcm_", ".wav", context.cacheDir)
            wave = input.openWaveSource(scratch,reportDiagnostics = false,cancelled = cancelled,
                outputOptions = PcmOutputOptions(bitsPerSample = 16,duplicateMono = true))
            // The former Cast conversion also preserved surround channels and source rate.
            return PreparedAudio(wave.borrowedResource(),input,wave,scratch)
        } catch (error: Throwable) {
            try { wave?.close() } finally { try { input.close() } finally { scratch?.delete() } }
            throw error
        }
    }

    internal class PreparedAudio(val resource: SourceResource, private val input: AudioPlaybackInput,
        private val wave: AudioWaveSource? = null, private val scratch: File? = null) : Closeable {
        override fun close() {
            try { wave?.close() } finally { try { input.close() } finally { scratch?.delete() } }
        }
    }

    /** Retains the existing encoded-byte slice policy for receiver-native formats. */
    private fun window(source: SourceResource, track: AudioTrack, mime: String): SourceResource {
        val original = source.metadata
        val total = original.length ?: throw IOException("Cast source length is unknown")
        val start = track.startByte.coerceIn(0,total)
        val available = total-start
        val size = if (track.lengthBytes > 0) minOf(track.lengthBytes,available) else available
        val description = original.copy(name = track.fileName,length = size,
            content = original.content.copy(kind = DataKind.ENCODED_AUDIO,mimeType = mime),
            ownership = Ownership.BORROWED,
            capabilities = setOf(Capability.READ,Capability.POSITIONAL_READ,Capability.KNOWN_LENGTH,Capability.REOPEN),
            provenance = if (start == 0L && size == total) original.provenance else original.provenance.copy(
                offset = start,length = size,checksums = emptyMap(),
                transformations = original.provenance.transformations + "audio.encoded-slice"),
            verification = if (start == 0L && size == total) original.verification else Verification.PROVISIONAL)
        return object : SourceResource {
            override val metadata = description
            override fun open(): ByteSource {
                val handle = source.open()
                val positional = handle as? PositionalReadable
                if (positional == null) { handle.close(); throw IOException("Cast source requires positional access") }
                return object : ByteSource, PositionalReadable {
                    override val metadata = description.copy(capabilities = description.capabilities-Capability.REOPEN)
                    private var cursor = 0L
                    private var closed = false
                    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                        requireRange(position,buffer,offset,length)
                        if (closed) throw IOException("Cast view is closed")
                        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Cast request cancelled")
                        if (length == 0) return 0
                        if (position >= size) return -1
                        val wanted = minOf(length.toLong(),size-position).toInt()
                        val count = positional.readAt(start+position,buffer,offset,wanted)
                        if (count <= 0 || count > wanted) throw EOFException("Cast source ended before its declared length")
                        return count
                    }
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                        readAt(cursor,buffer,offset,length).also { if (it > 0) cursor+=it }
                    override fun close() { if (!closed) { closed=true; handle.close() } }
                }
            }
        }
    }
}
