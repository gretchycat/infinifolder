package com.infinifolder.viewer

/** Presentation-only bounded window. Units are characters or grid rows, never source byte guesses. */
class ScrollWindow<T>(private val capacity: Int, private val key: (T) -> Long, private val units: (T) -> Int) {
    init { require(capacity >= 2) }
    private val entries = mutableListOf<T>()
    val pages: List<T> get() = entries.toList()
    val size get() = entries.size
    val totalUnits get() = entries.sumOf(units)
    fun reset(page: T) { require(units(page) >= 0); entries.clear(); entries += page }
    fun clear() = entries.clear()
    fun canAppend(firstVisibleUnit: Int) = entries.size < capacity || units(entries.first()) == 0 || firstVisibleUnit >= units(entries.first())
    fun canPrepend(lastVisibleUnit: Int) = entries.size < capacity || units(entries.last()) == 0 || lastVisibleUnit < totalUnits - units(entries.last())
    data class Shift(val addedBefore: Int = 0, val removedBefore: Int = 0) {
        fun anchor(position: Int) = (position + addedBefore - removedBefore).coerceAtLeast(0)
    }
    /** Capture the latest visible range when applying an async result, not when starting its read. */
    fun append(page: T, firstVisibleUnit: Int): Shift? {
        require(units(page) >= 0)
        if (entries.any { key(it) == key(page) } || !canAppend(firstVisibleUnit)) return null
        val removed = if (entries.size == capacity) units(entries.removeAt(0)) else 0
        entries += page
        return Shift(removedBefore = removed)
    }
    fun prepend(page: T, lastVisibleUnit: Int): Shift? {
        require(units(page) >= 0)
        if (entries.any { key(it) == key(page) } || !canPrepend(lastVisibleUnit)) return null
        if (entries.size == capacity) entries.removeAt(entries.lastIndex)
        entries.add(0, page)
        return Shift(addedBefore = units(page))
    }
}
