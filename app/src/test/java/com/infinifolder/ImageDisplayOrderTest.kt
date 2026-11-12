package com.infinifolder

import android.net.TestUri
import com.infinifolder.model.*
import com.infinifolder.viewer.ImageNavigationOrder
import org.junit.Assert.*
import org.junit.Test

class ImageDisplayOrderTest {
    private fun entry(name: String, size: Long, modified: Long, mime: String = "image/png", directory: Boolean = false) =
        ResourceEntry(ResourceRef("test", name), ResourceMetadata(name,mime,size,modified,directory),TestUri("content://test/$name"))
    private val entries get() = listOf(entry("z.kra",5,30,"image/x-krita"),entry("a.ora",20,10,"image/openraster"),
        entry("m.svgz",10,20,"image/svg+xml"),entry(".hidden.png",100,100),entry("folder",0,0,directory=true),entry("notes.txt",200,200,"text/plain"))
    private fun names(key: String, descending: Boolean, hidden: Boolean = false) =
        ImageNavigationOrder.items(FileViewGrid.sortedEntries(entries,hidden,key,descending)).map { it.name }
    @Test fun imageQueueMatchesNameOrderInBothDirectionsAndOmitsHiddenAndOtherTypes() {
        assertEquals(listOf("a.ora","m.svgz","z.kra"),names("name",false))
        assertEquals(listOf("z.kra","m.svgz","a.ora"),names("name",true))
    }
    @Test fun sizeAndModifiedSortingReachTheViewerUnchanged() {
        assertEquals(listOf("z.kra","m.svgz","a.ora"),names("size",false))
        assertEquals(listOf("a.ora","m.svgz","z.kra"),names("size",true))
        assertEquals(listOf("z.kra","m.svgz","a.ora"),names("modified",true))
    }
    @Test fun hiddenImagesAreQueuedOnlyWhenDisplayed() {
        assertEquals(listOf(".hidden.png","a.ora","m.svgz","z.kra"),names("name",false,true))
    }
}
