package com.infinifolder

/** A bounded CharSequence keeps pathological user regexes from occupying an I/O worker indefinitely. */
object FilenamePattern {
    fun matching(names: List<String>, regex: Regex): List<Boolean> {
        val deadline = System.nanoTime() + 2_000_000_000L
        var reads = 0
        fun checkBudget() {
            if ((reads++ and 255) == 0 && (System.nanoTime() > deadline || Thread.currentThread().isInterrupted))
                throw IllegalArgumentException("Pattern took too long. Use a simpler expression or a wildcard.")
        }
        fun guarded(text: CharSequence): CharSequence = object : CharSequence {
            override val length: Int get() { checkBudget(); return text.length }
            override fun get(index: Int): Char { checkBudget(); return text[index] }
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = guarded(text.subSequence(startIndex, endIndex))
            override fun toString(): String = text.toString()
        }
        return names.map { regex.matches(guarded(it)) }
    }
}
