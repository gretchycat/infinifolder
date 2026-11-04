package com.infinifolder.viewer

import org.junit.Assert.*
import org.junit.Test

class RawImageViewportSizeTest {
    @Test fun landscapeCgaViewportHasTheSelectedPixelAspect(){
        assertEquals(800 to 500,RawImageViewportSize.fit(320,200,800,600))
        assertEquals(960 to 600,RawImageViewportSize.fit(320,200,1000,600))
    }
    @Test fun portraitAndSquareGeometryFitBetweenTheMenus(){
        assertEquals(375 to 600,RawImageViewportSize.fit(200,320,800,600))
        assertEquals(600 to 600,RawImageViewportSize.fit(256,256,800,600))
        assertEquals(800 to 500,RawImageViewportSize.fit(320,200,800,500))
    }
    @Test fun arbitraryGeometryRetainsAspectWithinOnePixelOfRounding(){
        for(width in listOf(319,320,321,997,4096))for(height in listOf(197,200,641,4096)){
            val (w,h)=RawImageViewportSize.fit(width,height,801,603)
            assertTrue(w in 1..801&&h in 1..603)
            val scale=minOf(801.0/width,603.0/height)
            assertTrue(kotlin.math.abs(w-width*scale)<=.5)
            assertTrue(kotlin.math.abs(h-height*scale)<=.5)
        }
    }
    @Test fun temporaryZeroSpaceAndExtremelyThinImagesAreSafe(){
        assertEquals(0 to 0,RawImageViewportSize.fit(320,200,0,500))
        assertEquals(1 to 600,RawImageViewportSize.fit(1,4096,800,600))
        assertThrows(IllegalArgumentException::class.java){RawImageViewportSize.fit(0,200,800,600)}
    }
}
