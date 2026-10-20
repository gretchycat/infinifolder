package com.infinifolder

import android.net.Uri

internal const val CREATE_DIR_KEY_PREFIX = "create_dir:"

internal data class TreeNode(
    val key: String,
    val uri: Uri?,
    val name: String,
    val icon: String,
    val depth: Int,
    var onOpen: () -> Unit,
    var canExpand: Boolean,
    var ancestorContinues: List<Boolean> = emptyList(),
    var isLast: Boolean = true,
    var expanded: Boolean = false,
    var loading: Boolean = false,
    var loadId: Int = 0,
    val children: MutableList<TreeNode> = mutableListOf(),
    var onLongPress: (() -> Unit)? = null,
    var needsAccessBadge: Boolean = false,
    var onRequestAccess: (() -> Unit)? = null
)

internal fun isCreateDirectoryNode(node: TreeNode): Boolean =
    node.key.startsWith(CREATE_DIR_KEY_PREFIX)

/** A missing destination restriction must allow virtual nodes and folders awaiting a grant. */
fun <T> activateTreeItem(
    resource: T?, blockedDestination: T?, requiresAccess: Boolean,
    requestAccess: () -> Unit, select: () -> Unit
) {
    if (blockedDestination != null && resource == blockedDestination) return
    if (requiresAccess) requestAccess() else select()
}

/**
 * Handles tapping the expand/collapse disclosure ('+' / '−') on a tree node.
 * Opening a '+' (when [node] is collapsed) selects the node when [selectOnExpand] is enabled.
 */
internal fun onDisclosureClicked(
    node: TreeNode,
    selectOnExpand: Boolean,
    onSelect: (TreeNode) -> Unit,
    onToggle: (TreeNode) -> Unit
) {
    val openingPlus = !node.expanded
    if (openingPlus && selectOnExpand) {
        onSelect(node)
    }
    onToggle(node)
}

/**
 * Returns the visible children of [node], filtering out [excludedUri] and, when
 * [includeCreateDirectory] is true, appending a "Create directory" object as the last child.
 */
internal fun visibleChildrenForNode(
    node: TreeNode,
    excludedUri: Uri? = null,
    includeCreateDirectory: Boolean = false,
    onCreateDirectory: ((TreeNode, Uri) -> Unit)? = null
): List<TreeNode> {
    val filtered = node.children.filter { child ->
        excludedUri == null || child.uri != excludedUri
    }
    val parentUri = node.uri
    if (!includeCreateDirectory || parentUri == null || onCreateDirectory == null) {
        return filtered
    }
    val createNode = TreeNode(
        key = "$CREATE_DIR_KEY_PREFIX${node.key}",
        uri = null,
        name = "Create directory",
        icon = "📁",
        depth = node.depth + 1,
        onOpen = { onCreateDirectory(node, parentUri) },
        canExpand = false
    )
    return filtered + createNode
}

/**
 * Computes the visible children for an expanded [parentNode] and updates each child's
 * [TreeNode.isLast] and [TreeNode.ancestorContinues] flags so connection lines remain intact.
 */
internal fun prepareVisibleChildren(
    parentNode: TreeNode,
    parentAncestorFlags: List<Boolean>,
    excludedUri: Uri? = null,
    includeCreateDirectory: Boolean = false,
    onCreateDirectory: ((TreeNode, Uri) -> Unit)? = null
): List<TreeNode> {
    if (!parentNode.expanded) return emptyList()
    val children = visibleChildrenForNode(
        node = parentNode,
        excludedUri = excludedUri,
        includeCreateDirectory = includeCreateDirectory,
        onCreateDirectory = onCreateDirectory
    )
    val childAncestorFlags = parentAncestorFlags + !parentNode.isLast
    children.forEachIndexed { index, child ->
        child.isLast = index == children.lastIndex
        child.ancestorContinues = childAncestorFlags.drop(1)
    }
    return children
}

