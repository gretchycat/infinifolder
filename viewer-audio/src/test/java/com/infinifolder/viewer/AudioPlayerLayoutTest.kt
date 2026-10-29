package com.infinifolder.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioPlayerLayoutTest {
    @Test fun usesPhoneLayoutsBelowTabletWidth() {
        assertEquals(1, audioPlayerColumnCount(599, landscape = false))
        assertEquals(2, audioPlayerColumnCount(599, landscape = true))
    }

    @Test fun usesTabletLayoutsAtAndAboveTabletWidth() {
        assertEquals(2, audioPlayerColumnCount(600, landscape = false))
        assertEquals(3, audioPlayerColumnCount(600, landscape = true))
        assertEquals(3, audioPlayerColumnCount(800, landscape = true))
    }
}
