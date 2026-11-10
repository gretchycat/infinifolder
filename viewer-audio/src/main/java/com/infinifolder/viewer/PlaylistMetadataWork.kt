package com.infinifolder.viewer

/** Tracks attempted metadata probes independently of whether a duration was supplied. */
internal class PlaylistMetadataWork {
    private data class Key(val uri: String, val start: Long, val length: Long)
    private val attempted = mutableSetOf<Key>()
    private var priority = emptySet<Key>()

    private fun key(track: AudioTrack) = Key(track.uri.toString(), track.startByte, track.lengthBytes)

    @Synchronized
    fun next(tracks: List<AudioTrack>, currentIndex: Int): AudioTrack? {
        if (tracks.isEmpty()) return null
        val start = if (currentIndex in tracks.indices) (currentIndex + 1) % tracks.size else 0
        val ordered = List(tracks.size) { offset -> tracks[(start + offset) % tracks.size] }
        for (track in ordered) {
            if (key(track) !in priority) continue
            if (!attempted.add(key(track))) continue
            if (track.isUnreadable) continue
            if (track.mediaKind != PlaylistMediaKind.AUDIO) continue
            return track
        }
        for (track in ordered) {
            if (key(track) in priority || !attempted.add(key(track))) continue
            if (track.isUnreadable) continue
            if (track.mediaKind != PlaylistMediaKind.AUDIO) continue
            return track
        }
        return null
    }

    @Synchronized
    fun prioritize(tracks: List<AudioTrack>) {
        priority = tracks.mapTo(mutableSetOf(), ::key)
    }

    @Synchronized
    fun markAttempted(track: AudioTrack) { attempted.add(key(track)) }

    fun matches(a: AudioTrack, b: AudioTrack): Boolean = key(a) == key(b)

    /** Keep values supplied by the playing track if they changed during the probe. */
    fun merge(current: AudioTrack, before: AudioTrack, result: AudioTrack): AudioTrack {
        val segmented = before.startByte > 0 || before.lengthBytes > 0
        return current.copy(
            title = if (segmented || current.title != before.title) current.title else result.title,
            artist = if (current.artist != before.artist) current.artist else result.artist,
            album = if (current.album != before.album) current.album else result.album,
            artwork = if (current.artwork != before.artwork) current.artwork else result.artwork ?: current.artwork,
            directoryCoverUri = if (current.directoryCoverUri != before.directoryCoverUri) current.directoryCoverUri
                else result.directoryCoverUri ?: current.directoryCoverUri,
            directoryUri = if (current.directoryUri != before.directoryUri) current.directoryUri
                else result.directoryUri ?: current.directoryUri,
            directoryName = if (current.directoryName != before.directoryName) current.directoryName
                else result.directoryName ?: current.directoryName,
            directoryArtwork = if (current.directoryArtwork != before.directoryArtwork) current.directoryArtwork
                else result.directoryArtwork ?: current.directoryArtwork,
            directoryArtworkMetadataResolved = current.directoryArtworkMetadataResolved ||
                result.directoryArtworkMetadataResolved,
            durationMs = if (current.isUnreadable) 0 else if (current.durationMs != before.durationMs ||
                ((segmented || result.durationMs <= 0) && current.durationMs > 0)) current.durationMs else result.durationMs,
            bitrateKbps = current.bitrateKbps ?: result.bitrateKbps,
            sampleRateHz = current.sampleRateHz ?: result.sampleRateHz,
            channels = current.channels ?: result.channels,
            isBitrateRelevant = result.isBitrateRelevant
        )
    }
}
