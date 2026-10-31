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

/** Folder discovery may arrive after a failed track has already been skipped. */
internal fun retainUnreadableState(previous: List<AudioTrack>, incoming: List<AudioTrack>): List<AudioTrack> {
    val failed = previous.filter { it.isUnreadable }.mapTo(mutableSetOf(), ::sourceKey)
    return incoming.map { if (it.isUnreadable || sourceKey(it) in failed) unreadableTrack(it) else it }
}

/** An actual successful retry also restores duplicate references to the same source slice. */
internal fun restoreReadableState(tracks: MutableList<AudioTrack>, played: AudioTrack, durationMs: Long) {
    val key = sourceKey(played)
    for (index in tracks.indices) {
        val track = tracks[index]
        if (track.isUnreadable && sourceKey(track) == key) {
            tracks[index] = track.copy(isUnreadable = false, durationMs = durationMs.coerceAtLeast(0))
        }
    }
}
