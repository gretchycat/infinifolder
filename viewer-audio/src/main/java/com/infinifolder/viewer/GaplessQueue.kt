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
