package com.infinifolder.audio.cast

import android.content.Context
import android.net.Uri
import com.infinifolder.audio.AudioProcessing
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
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Receiver format policy and host-owned source leases. All PCM conversion is in Sound;
 * HTTP requests borrow positional views of the same encoded or decoded source.
 */
object AudioTranscoder {

    private const val CAST_SAMPLE_RATE = 48_000
    private const val CAST_CHANNELS = 2
    private const val CAST_BITS = 16
    private const val RIFF_MAX_BYTES = 0xffff_ffffL - 36L

    internal data class TimelineEntry(
        val track: AudioTrack,
        val startFrame: Long,
        val frameCount: Long,
        val startMs: Long,
        val durationMs: Long,
        val sourceOrdinal: Int,
        val playlistIndex: Int = -1
    )

    internal class PreparedContinuousAudio(
        val resource: SourceResource,
        val timeline: List<TimelineEntry>,
        val skipped: List<AudioTrack>,
        private val owners: List<Closeable>
    ) : Closeable {
        val durationMs: Long get() = timeline.lastOrNull()?.let { it.startMs + it.durationMs } ?: 0L
        override fun close() { owners.asReversed().forEach { runCatching { it.close() } } }
    }

    internal class ContinuousStreamTooLargeException : IOException("Continuous Cast PCM exceeds the RIFF WAVE size limit")

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
        val rawHint = track.decoderExtension != null
        val direct = !rawHint && isDirectlyPlayableByCast(track.uri,track.fileName)
        val input = AudioPlaybackInput.open(context,track.uri,track.fileName,
            requiresFile = rawHint, metadataOnly = direct)
        var wave: AudioWaveSource? = null
        var scratch: File? = null
        var sourceSlice: File? = null
        try {
            if (direct) return PreparedAudio(window(input.resource,track,getMimeType(track.uri,track.fileName)),input)
            scratch = File.createTempFile("cast_pcm_", ".wav", context.cacheDir)
            wave = if (rawHint) {
                val staged = input.file ?: throw IOException("Raw image audio requires a staged file")
                val sliced = slice(staged,track,context.cacheDir)
                sourceSlice = sliced
                AudioProcessing.openWaveSource(sliced,scratch,
                    PcmOutputOptions(bitsPerSample = 16,duplicateMono = true),cancelled)
            } else input.openWaveSource(scratch,reportDiagnostics = false,cancelled = cancelled,
                outputOptions = PcmOutputOptions(bitsPerSample = 16,duplicateMono = true))
            // The former Cast conversion also preserved surround channels and source rate.
            return PreparedAudio(wave.borrowedResource(),input,wave,scratch,sourceSlice)
        } catch (error: Throwable) {
            try { wave?.close() } finally { try { input.close() } finally { scratch?.delete(); sourceSlice?.delete() } }
            throw error
        }
    }

    /** Opens every decodable playlist item as normalized, exact-frame PCM and exposes one virtual WAVE. */
    internal fun openContinuous(context: Context, tracks: List<AudioTrack>, cancelled: () -> Boolean): PreparedContinuousAudio {
        val segments = mutableListOf<ContinuousSegment>()
        val timeline = mutableListOf<TimelineEntry>()
        val skipped = mutableListOf<AudioTrack>()
        var nextFrame = 0L
        val output = PcmOutputOptions(bitsPerSample = CAST_BITS, sampleRate = CAST_SAMPLE_RATE, channels = CAST_CHANNELS)
        try {
            for ((ordinal, track) in tracks.withIndex()) {
                if (cancelled() || Thread.currentThread().isInterrupted) throw InterruptedIOException("Continuous Cast preparation cancelled")
                if (track.isUnreadable) { skipped += track; continue }
                var prepared: PreparedPcmTrack? = null
                try {
                    prepared = openPcmTrack(context, track, cancelled)
                    val frames = prepared.frames
                    if (frames <= 0) throw IOException("Decoder produced no PCM frames")
                    if (prepared.wave.preparation.format.sampleRate != CAST_SAMPLE_RATE ||
                        prepared.wave.preparation.format.channels != CAST_CHANNELS || prepared.wave.preparation.format.bitsPerSample != CAST_BITS)
                        throw IOException("Sound could not normalize the decoded PCM format")
                    val dataBytes = frames * CAST_CHANNELS * (CAST_BITS / 8)
                    if (dataBytes > RIFF_MAX_BYTES || dataBytes > RIFF_MAX_BYTES - segments.sumOf { it.dataBytes })
                        throw ContinuousStreamTooLargeException()
                    val startMs = nextFrame * 1000L / CAST_SAMPLE_RATE
                    val duration = frames * 1000L / CAST_SAMPLE_RATE
                    timeline += TimelineEntry(track, nextFrame, frames, startMs, duration, ordinal)
                    val keepWarm = segments.size < 2
                    segments += ContinuousSegment(track, nextFrame * CAST_CHANNELS * (CAST_BITS / 8), frames,
                        open = { openPcmTrack(context, track) }, initial = prepared.takeIf { keepWarm })
                    nextFrame += frames
                    if (!keepWarm) prepared.close()
                    prepared = null
                } catch (cancel: InterruptedIOException) {
                    throw cancel
                } catch (tooLarge: ContinuousStreamTooLargeException) {
                    throw tooLarge
                } catch (error: Exception) {
                    skipped += track
                    com.infinifolder.storage.DiagnosticLog.record("cast.continuous.track.skipped", "file=${track.fileName}", error)
                } finally {
                    runCatching { prepared?.close() }
                }
            }
            if (segments.isEmpty()) throw IOException("No playlist tracks could be decoded by Sound")
            val dataLength = nextFrame * CAST_CHANNELS * (CAST_BITS / 8)
            val resource = ContinuousWavResource(segments, dataLength)
            return PreparedContinuousAudio(resource, timeline.toList(), skipped.toList(), listOf(resource))
        } catch (error: Throwable) {
            segments.forEach { runCatching { it.closeInitial() } }
            throw error
        }
    }

    private data class ContinuousSegment(
        val track: AudioTrack,
        val startByte: Long,
        val frames: Long,
        val open: () -> PreparedPcmTrack,
        var initial: PreparedPcmTrack? = null
    ) {
        val dataBytes: Long get() = frames * CAST_CHANNELS * (CAST_BITS / 8)
        fun openSource(): PreparedPcmTrack = initial.also { initial = null } ?: open()
        fun closeInitial() { initial?.close(); initial = null }
    }

    private class PreparedPcmTrack(
        val wave: AudioWaveSource,
        private val input: AudioPlaybackInput,
        private val slice: File?,
        private val scratch: File?,
        val frames: Long
    ) : Closeable {
        override fun close() {
            try { wave.close() } finally {
                try { input.close() } finally { slice?.delete(); scratch?.delete() }
            }
        }
    }

    private fun openPcmTrack(context: Context, track: AudioTrack,
        cancelled: () -> Boolean = { Thread.currentThread().isInterrupted }): PreparedPcmTrack {
        val hasByteRange = track.startByte > 0 || track.lengthBytes > 0
        val needsFile = hasByteRange || track.decoderExtension != null
        val input = AudioPlaybackInput.open(context, track.uri, track.fileName, requiresFile = needsFile)
        var slice: File? = null
        var scratch: File? = null
        var wave: AudioWaveSource? = null
        try {
            scratch = File.createTempFile("cast_pcm_", ".wav", context.cacheDir)
            wave = if (needsFile) {
                val staged = input.file ?: throw IOException("Byte-range audio input requires a staged file")
                slice = slice(staged, track, context.cacheDir)
                AudioProcessing.openWaveSource(slice, scratch, PcmOutputOptions(
                    bitsPerSample = CAST_BITS, sampleRate = CAST_SAMPLE_RATE, channels = CAST_CHANNELS), cancelled)
            } else input.openWaveSource(scratch, reportDiagnostics = false, cancelled = cancelled,
                outputOptions = PcmOutputOptions(bitsPerSample = CAST_BITS, sampleRate = CAST_SAMPLE_RATE, channels = CAST_CHANNELS))
            val frames = wave.preparation.format.totalFrames ?: throw IOException("Decoder did not provide an exact PCM frame count")
            if (frames <= 0) throw IOException("Decoder produced no PCM frames")
            return PreparedPcmTrack(wave, input, slice, scratch, frames)
        } catch (error: Throwable) {
            try { wave?.close() } finally { try { input.close() } finally { slice?.delete(); scratch?.delete() } }
            throw error
        }
    }

    private class ContinuousWavResource(private val segments: List<ContinuousSegment>, dataLength: Long) : SourceResource, Closeable {
        private val length = 44L + dataLength
        private val lock = Any()
        private val openSegments = LinkedHashMap<Int, CachedSegment>(4, 0.75f, true)
        private val scheduled = mutableSetOf<Int>()
        private var closed = false
        private val prewarm = java.util.concurrent.ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            java.util.concurrent.ArrayBlockingQueue(2), { task -> Thread(task, "CastPcmPrewarm").apply { isDaemon = true } },
            java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy())

        private class CachedSegment(val source: PreparedPcmTrack, var active: Int = 0, var retired: Boolean = false)
        override val metadata = StreamMetadata(
            content = ContentType(DataKind.ENCODED_AUDIO, "audio/wav", "RIFF-WAVE-PCM", "signed-int16", "little"),
            name = "continuous-playlist.wav", length = length, finiteness = Finiteness.FINITE,
            capabilities = setOf(Capability.READ, Capability.POSITIONAL_READ, Capability.KNOWN_LENGTH, Capability.REOPEN),
            ownership = Ownership.BORROWED, concurrency = Concurrency.POSITIONAL_READS,
            attributes = mapOf("sampleRate" to CAST_SAMPLE_RATE.toString(), "channels" to CAST_CHANNELS.toString(),
                "bitsPerSample" to CAST_BITS.toString(), "continuous" to "true"))

        override fun open(): ByteSource = object : ByteSource, PositionalReadable {
            override val metadata = this@ContinuousWavResource.metadata.copy(capabilities = this@ContinuousWavResource.metadata.capabilities - Capability.REOPEN)
            private var cursor = 0L
            private var closed = false
            private val header = wavHeader(length - 44L)

            override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                requireRange(position, buffer, offset, length)
                if (closed) throw IOException("Continuous Cast source is closed")
                if (length == 0) return 0
                if (position >= this@ContinuousWavResource.length) return -1
                var absolute = position
                var target = offset
                var remaining = minOf(length.toLong(), this@ContinuousWavResource.length - position).toInt()
                val wanted = remaining
                while (remaining > 0) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Continuous Cast read cancelled")
                    if (absolute < 44) {
                        val count = minOf(remaining, 44 - absolute.toInt())
                        System.arraycopy(header, absolute.toInt(), buffer, target, count)
                        absolute += count; target += count; remaining -= count
                    } else {
                        val dataPosition = absolute - 44
                        val segmentIndex = segments.indexOfFirst { dataPosition >= it.startByte && dataPosition < it.startByte + it.dataBytes }
                        if (segmentIndex < 0) throw IOException("Continuous Cast timeline has a PCM gap")
                        val segment = segments[segmentIndex]
                        val segmentOffset = dataPosition - segment.startByte
                        val count = minOf(remaining.toLong(), segment.dataBytes - segmentOffset).toInt()
                        val cached = acquire(segmentIndex)
                        val read = try { cached.source.wave.readAt(44 + segmentOffset, buffer, target, count) }
                            finally { release(cached) }
                        if (read <= 0 || read > count) throw IOException("Continuous Cast decoder ended before its declared frame count")
                        prewarm(segmentIndex + 1)
                        absolute += read; target += read; remaining -= read
                    }
                }
                return wanted
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                readAt(cursor, buffer, offset, length).also { if (it > 0) cursor += it }

            override fun close() { closed = true }
        }

        private fun acquire(index: Int): CachedSegment {
            val retiring = mutableListOf<PreparedPcmTrack>()
            val selected = synchronized(lock) {
                if (closed) throw IOException("Continuous Cast source is closed")
                val entry = openSegments[index] ?: CachedSegment(segments[index].openSource()).also { openSegments[index] = it }
                entry.active++
                trim(retiring, keep = index)
                entry
            }
            retiring.forEach { runCatching { it.close() } }
            return selected
        }

        private fun prewarm(index: Int) {
            if (index !in segments.indices) return
            synchronized(lock) {
                if (closed || index in openSegments || !scheduled.add(index)) return
            }
            try {
                prewarm.execute {
                    val retiring = mutableListOf<PreparedPcmTrack>()
                    try {
                        synchronized(lock) {
                            if (!closed && index !in openSegments) openSegments[index] = CachedSegment(segments[index].openSource())
                            trim(retiring, keep = index)
                        }
                    } catch (error: Exception) {
                        com.infinifolder.storage.DiagnosticLog.record("cast.continuous.prewarm.failed", "track=${segments[index].track.fileName}", error)
                    } finally {
                        synchronized(lock) { scheduled.remove(index) }
                        retiring.forEach { runCatching { it.close() } }
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                synchronized(lock) { scheduled.remove(index) }
            }
        }

        private fun release(entry: CachedSegment) {
            val retiring = mutableListOf<PreparedPcmTrack>()
            synchronized(lock) { entry.active--; trim(retiring, keep = openSegments.entries.lastOrNull()?.key ?: -1) }
            retiring.forEach { runCatching { it.close() } }
        }

        private fun trim(retiring: MutableList<PreparedPcmTrack>, keep: Int) {
            while (openSegments.size > 3) {
                val victim = openSegments.entries.firstOrNull { it.key != keep && it.value.active == 0 } ?: break
                openSegments.remove(victim.key)
                victim.value.retired = true
                retiring += victim.value.source
            }
        }

        override fun close() {
            prewarm.shutdownNow()
            val sources = synchronized(lock) {
                if (closed) return
                closed = true
                openSegments.values.map { it.source }.also { openSegments.clear(); scheduled.clear() }
            }
            sources.forEach { runCatching { it.close() } }
            segments.forEach { it.closeInitial() }
        }
    }

    private fun wavHeader(dataBytes: Long): ByteArray {
        val bytes = java.io.ByteArrayOutputStream(44)
        fun le16(value: Int) { bytes.write(value and 255); bytes.write(value ushr 8 and 255) }
        fun le32(value: Long) { repeat(4) { bytes.write((value ushr (it * 8)).toInt() and 255) } }
        bytes.write("RIFF".toByteArray(Charsets.US_ASCII)); le32(36 + dataBytes)
        bytes.write("WAVEfmt ".toByteArray(Charsets.US_ASCII)); le32(16); le16(1); le16(CAST_CHANNELS)
        le32(CAST_SAMPLE_RATE.toLong()); le32(CAST_SAMPLE_RATE.toLong() * CAST_CHANNELS * CAST_BITS / 8)
        le16(CAST_CHANNELS * CAST_BITS / 8); le16(CAST_BITS); bytes.write("data".toByteArray(Charsets.US_ASCII)); le32(dataBytes)
        return bytes.toByteArray()
    }

    private fun slice(source: File, track: AudioTrack, directory: File): File {
        val total = source.length()
        val start = track.startByte.coerceIn(0, total)
        val available = total - start
        val count = if (track.lengthBytes > 0) minOf(track.lengthBytes, available) else available
        if (count <= 0) throw IOException("Audio byte range is empty")
        val extension = track.decoderExtension?.trimStart('.')?.takeIf { it.isNotBlank() }
            ?: track.fileName.substringAfterLast('.', "").takeIf { it.isNotBlank() }
            ?: "bin"
        val suffix = if (extension.startsWith('.')) extension else ".$extension"
        val output = File.createTempFile("cast_slice_", suffix, directory)
        try {
            RandomAccessFile(source, "r").use { input ->
                RandomAccessFile(output, "rw").use { target ->
                    input.seek(start)
                    val buffer = ByteArray(64 * 1024)
                    var remaining = count
                    while (remaining > 0) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Audio byte-range copy cancelled")
                        val wanted = minOf(buffer.size.toLong(), remaining).toInt()
                        val read = input.read(buffer, 0, wanted)
                        if (read <= 0) throw EOFException("Audio byte range ended before its declared length")
                        target.write(buffer, 0, read)
                        remaining -= read
                    }
                }
            }
            return output
        } catch (error: Throwable) { output.delete(); throw error }
    }

    internal class PreparedAudio(val resource: SourceResource, private val input: AudioPlaybackInput,
        private val wave: AudioWaveSource? = null, private val scratch: File? = null,
        private val sourceSlice: File? = null) : Closeable {
        override fun close() {
            try { wave?.close() } finally { try { input.close() } finally { scratch?.delete(); sourceSlice?.delete() } }
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
