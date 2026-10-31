package com.infinifolder.viewer

/** A bounded search keeps an all-unreadable queue from retrying forever. */
internal fun nextReadableTrack(tracks: List<AudioTrack>, current: Int, wrap: Boolean, direction: Int = 1): Int? {
    require(direction == 1 || direction == -1)
    if (tracks.isEmpty()) return null
    var index = current
    repeat(tracks.size) {
        index += direction
        if (index !in tracks.indices) {
            if (!wrap) return null
            index = if (direction > 0) 0 else tracks.lastIndex
        }
        if (!tracks[index].isUnreadable) return index
    }
    return null
}

internal fun unreadableTrack(track: AudioTrack): AudioTrack = track.copy(durationMs = 0, isUnreadable = true)

private data class QueueSourceKey(val uri: String, val start: Long, val length: Long)
private fun sourceKey(track: AudioTrack) = QueueSourceKey(track.uri.toString(), track.startByte, track.lengthBytes)

/** Folder discovery may arrive after playback/probing has enriched or rejected a source. */
internal fun retainUnreadableState(previous: List<AudioTrack>, incoming: List<AudioTrack>): List<AudioTrack> {
    val known = previous.associateBy(::sourceKey)
    return incoming.map { track ->
        val old = known[sourceKey(track)]
        if (track.isUnreadable || old?.isUnreadable == true) unreadableTrack(track)
        else if (old == null) track
        else track.copy(
            durationMs = track.durationMs.takeIf { it > 0 } ?: old.durationMs,
            title = if (track.title == track.fileName) old.title else track.title,
            artist = track.artist ?: old.artist, album = track.album ?: old.album,
            artwork = track.artwork ?: old.artwork,
            bitrateKbps = track.bitrateKbps ?: old.bitrateKbps,
            sampleRateHz = track.sampleRateHz ?: old.sampleRateHz,
            channels = track.channels ?: old.channels
        )
    }
}

/** An actual successful retry also restores duplicate references to the same source slice. */
internal fun restoreReadableState(tracks: MutableList<AudioTrack>, played: AudioTrack, durationMs: Long) {
    val key = sourceKey(played)
    for (index in tracks.indices) {
        val track = tracks[index]
        if (sourceKey(track) == key) {
            tracks[index] = track.copy(isUnreadable = false,
                durationMs = durationMs.takeIf { it > 0 } ?: track.durationMs.coerceAtLeast(0))
        }
    }
}
