package com.infinifolder

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionInitialLocationFallbackTest {
    private val root = "container-links:///"

    @Test
    fun failedInitialInfiniSelectLocationReturnsToVirtualRoot() {
        assertTrue(SelectionInitialLocationFallback.shouldReturnToVirtualRoot(
            selectionMode = true,
            resetRoot = true,
            location = "content://com.termux.documents/document/%2Fmissing%2Ffolder",
            virtualRoot = root,
        ))
    }

    @Test
    fun validRootAndLaterNavigationAreNotReset() {
        assertFalse(SelectionInitialLocationFallback.shouldReturnToVirtualRoot(
            selectionMode = true,
            resetRoot = true,
            location = root,
            virtualRoot = root,
        ))
        assertFalse(SelectionInitialLocationFallback.shouldReturnToVirtualRoot(
            selectionMode = true,
            resetRoot = false,
            location = "content://provider/document/folder",
            virtualRoot = root,
        ))
        assertFalse(SelectionInitialLocationFallback.shouldReturnToVirtualRoot(
            selectionMode = false,
            resetRoot = true,
            location = "content://provider/document/folder",
            virtualRoot = root,
        ))
    }
}
