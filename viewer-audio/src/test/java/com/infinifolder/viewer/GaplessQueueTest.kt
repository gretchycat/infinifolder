package com.infinifolder.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GaplessQueueTest {
    @Test fun advancesWithoutRepeatingAtTheEnd() {
        assertEquals(1, gaplessNextIndex(0, 3, RepeatMode.NONE))
        assertEquals(2, gaplessNextIndex(1, 3, RepeatMode.NONE))
        assertNull(gaplessNextIndex(2, 3, RepeatMode.NONE))
        assertNull(gaplessNextIndex(0, 1, RepeatMode.NONE))
    }
    @Test fun respectsRepeatModes() {
        assertEquals(0, gaplessNextIndex(2, 3, RepeatMode.ALL))
        assertEquals(0, gaplessNextIndex(0, 1, RepeatMode.ALL))
        assertEquals(1, gaplessNextIndex(1, 3, RepeatMode.ONE))
    }
    @Test fun invalidPositionsHaveNoNextTrack() {
        assertNull(gaplessNextIndex(-1, 0, RepeatMode.ALL))
        assertNull(gaplessNextIndex(-1, 3, RepeatMode.ONE))
        assertNull(gaplessNextIndex(3, 3, RepeatMode.ALL))
    }
}
