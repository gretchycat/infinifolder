package com.infinifolder

import org.junit.Assert.assertEquals
import org.junit.Test

class TreeInteractionTest {
    @Test fun virtualRootAndLogicalStorageCanBeSelectedWithoutResourceUris() {
        val opened = mutableListOf<String>()
        listOf("Virtual root", "Internal shared", "Application public").forEach { label ->
            activateTreeItem<String>(null, null, false, { error("Unexpected permission request") }, { opened += label })
        }
        assertEquals(3, opened.size)
    }
    @Test fun lockedRowAndDisclosureRequestAccessEvenWithoutResourceUri() {
        var requests = 0
        // The row, icon, lock badge and locked disclosure all share this activation handler.
        repeat(4) { activateTreeItem<String>(null, null, true, { requests++ }, { error("Opened before grant") }) }
        assertEquals(4, requests)
    }
    @Test fun sourceParentRemainsBlockedInDestinationChooser() {
        activateTreeItem("source-parent", "source-parent", false, { error("Requested access") }, { error("Selected source parent") })
    }
    @Test fun virtualNodesAreNotBlockedByADestinationRestriction() {
        var opened = 0
        activateTreeItem(null, "source-parent", false, {}, { opened++ })
        assertEquals(1, opened)
    }
    @Test fun grantedFolderSelectsWithoutAnotherPermissionRequest() {
        var opened = 0
        activateTreeItem("folder", null, false, { error("Requested again") }, { opened++ })
        assertEquals(1, opened)
    }
}
