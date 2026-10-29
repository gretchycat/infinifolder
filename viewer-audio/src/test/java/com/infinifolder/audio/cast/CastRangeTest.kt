package com.infinifolder.audio.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CastRangeTest {
    @Test fun servesWholeAndPartialFiles() {
        assertEquals(0L..999L, parseRange(null, 1000))
        assertEquals(100L..199L, parseRange("bytes=100-199", 1000))
        assertEquals(900L..999L, parseRange("bytes=900-", 1000))
        assertEquals(950L..999L, parseRange("bytes=-50", 1000))
        assertEquals(900L..999L, parseRange("bytes=900-5000", 1000))
    }
    @Test fun rejectsUnsatisfiableAndMalformedRanges() {
        assertNull(parseRange("bytes=1000-", 1000))
        assertNull(parseRange("bytes=20-10", 1000))
        assertNull(parseRange("bytes=-0", 1000))
        assertNull(parseRange("bytes=0-1,5-6", 1000))
        assertNull(parseRange("bytes=abc-", 1000))
        assertNull(parseRange("bytes=0-", 0))
    }
}
