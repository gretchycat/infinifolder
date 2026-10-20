package com.infinifolder

import android.net.TestUri
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun openingPlusSelectsNodeAndExpandsInTargetBrowser() {
        val uri = TestUri("file:///storage/emulated/0/Music")
        val node = TreeNode("music", uri, "Music", "📁", 2, {}, canExpand = true, expanded = false)
        var selectedNode: TreeNode? = null

        onDisclosureClicked(
            node = node,
            selectOnExpand = true,
            onSelect = { selectedNode = it },
            onToggle = { it.expanded = !it.expanded }
        )

        assertEquals(node, selectedNode)
        assertTrue(node.expanded)

        // Closing the minus ('−') collapses without re-triggering selection
        selectedNode = null
        onDisclosureClicked(
            node = node,
            selectOnExpand = true,
            onSelect = { selectedNode = it },
            onToggle = { it.expanded = !it.expanded }
        )

        assertEquals(null, selectedNode)
        assertFalse(node.expanded)
    }

    @Test fun selectedDirectoryHasCreateDirectoryAsLastChildWithMaintainedConnectionLines() {
        val parentUri = TestUri("file:///storage/emulated/0/Download")
        val child1Uri = TestUri("file:///storage/emulated/0/Download/Docs")
        val child2Uri = TestUri("file:///storage/emulated/0/Download/Sub")
        val grandChildUri = TestUri("file:///storage/emulated/0/Download/Sub/Nested")

        val parent = TreeNode(
            key = "download",
            uri = parentUri,
            name = "Download",
            icon = "📁",
            depth = 2,
            onOpen = {},
            canExpand = true,
            isLast = false,
            expanded = true
        )
        val child1 = TreeNode("docs", child1Uri, "Docs", "📁", 3, {}, canExpand = true)
        val child2 = TreeNode("sub", child2Uri, "Sub", "📁", 3, {}, canExpand = true, expanded = true)
        val grandChild = TreeNode("nested", grandChildUri, "Nested", "📁", 4, {}, canExpand = true)
        child2.children.add(grandChild)
        parent.children.addAll(listOf(child1, child2))

        // Ancestor flags reaching parent: [virtualRoot=false, internalShared=true]
        val parentAncestorFlags = listOf(false, true)
        var createTriggeredFor: Uri? = null

        val visibleChildren = prepareVisibleChildren(
            parentNode = parent,
            parentAncestorFlags = parentAncestorFlags,
            includeCreateDirectory = true,
            onCreateDirectory = { _, uri -> createTriggeredFor = uri }
        )

        assertEquals(3, visibleChildren.size)
        assertEquals("Docs", visibleChildren[0].name)
        assertFalse(visibleChildren[0].isLast)
        assertEquals(listOf(true, true), visibleChildren[0].ancestorContinues)

        assertEquals("Sub", visibleChildren[1].name)
        // Because "Create directory" follows "Sub", "Sub" is NOT the last child
        assertFalse(visibleChildren[1].isLast)
        assertEquals(listOf(true, true), visibleChildren[1].ancestorContinues)

        // Nested child inside "Sub" must continue the vertical branch line for "Sub" -> "Create directory"
        val grandChildren = prepareVisibleChildren(
            parentNode = child2,
            parentAncestorFlags = parentAncestorFlags + !parent.isLast,
            includeCreateDirectory = false
        )
        assertEquals(1, grandChildren.size)
        assertTrue(grandChildren[0].isLast)
        assertEquals(listOf(true, true, true), grandChildren[0].ancestorContinues)

        // Last child of the selected directory is the "Create directory" object
        val createNode = visibleChildren[2]
        assertTrue(isCreateDirectoryNode(createNode))
        assertEquals("Create directory", createNode.name)
        assertEquals(3, createNode.depth)
        assertTrue(createNode.isLast)
        assertEquals(listOf(true, true), createNode.ancestorContinues)

        createNode.onOpen()
        assertEquals(parentUri, createTriggeredFor)
    }
}
