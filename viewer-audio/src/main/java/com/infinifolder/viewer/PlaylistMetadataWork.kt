package com.infinifolder.viewer

/** Tracks attempted metadata probes independently of whether a duration was supplied. */
internal class PlaylistMetadataWork {
    private data class Key(val uri: String, val start: Long, val length: Long)
    private val attempted = mutableSetOf<Key>()

    private fun key(track: AudioTrack) = Key(track.uri.toString(), track.startByte, track.lengthBytes)

    fun next(tracks: List<AudioTrack>, currentIndex: Int): AudioTrack? {
        if (tracks.isEmpty()) return null
        val start = (currentIndex + 1).coerceIn(0, tracks.size)
        val ordered = tracks.drop(start) + tracks.take(start)
        return ordered.firstOrNull { attempted.add(key(it)) }
    }

    fun matches(a: AudioTrack, b: AudioTrack): Boolean = key(a) == key(b)

    /** Keep values supplied by the playing track if they changed during the probe. */
    fun merge(current: AudioTrack, before: AudioTrack, result: AudioTrack): AudioTrack {
        val segmented = before.startByte > 0 || before.lengthBytes > 0
        return current.copy(
            title = if (segmented || current.title != before.title) current.title else result.title,
            artist = if (current.artist != before.artist) current.artist else result.artist,
            album = if (current.album != before.album) current.album else result.album,
            durationMs = if (current.durationMs != before.durationMs || (segmented && current.durationMs > 0)) current.durationMs else result.durationMs,
            bitrateKbps = current.bitrateKbps ?: result.bitrateKbps,
            sampleRateHz = current.sampleRateHz ?: result.sampleRateHz,
            channels = current.channels ?: result.channels,
            isBitrateRelevant = result.isBitrateRelevant
        )
    }
}
