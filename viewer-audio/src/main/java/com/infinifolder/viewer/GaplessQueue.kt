package com.infinifolder.viewer

/** The next automatic track, as distinct from a manual skip. */
internal fun gaplessNextIndex(current: Int, count: Int, repeat: RepeatMode): Int? {
    if (current !in 0 until count) return null
    return when (repeat) {
        RepeatMode.ONE -> current
        RepeatMode.ALL -> (current + 1) % count
        RepeatMode.NONE -> (current + 1).takeIf { it < count }
    }
}

/** The next automatic Cast item, skipping entries already known to be unreadable. */
internal data class CastTrackKey(val uri: String, val start: Long, val length: Long)
internal fun castTrackKey(track: AudioTrack) = CastTrackKey(track.uri.toString(), track.startByte, track.lengthBytes)

internal fun nextCastTrackIndex(tracks: List<AudioTrack>, current: Int, repeat: RepeatMode,
    failures: Set<CastTrackKey> = emptySet()): Int? {
    val eligible = tracks.map {
        if (castTrackKey(it) in failures || it.mediaKind != PlaylistMediaKind.AUDIO) it.copy(isUnreadable = true) else it
    }
    return when (repeat) {
        RepeatMode.ONE -> current.takeIf { it in eligible.indices && !eligible[it].isUnreadable }
        RepeatMode.ALL -> nextReadableTrack(eligible, current, true)
        RepeatMode.NONE -> nextReadableTrack(eligible, current, false)
    }
}
