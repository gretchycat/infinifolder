package com.infinifolder

/** A missing destination restriction must allow virtual nodes and folders awaiting a grant. */
fun <T> activateTreeItem(
    resource: T?, blockedDestination: T?, requiresAccess: Boolean,
    requestAccess: () -> Unit, select: () -> Unit
) {
    if (blockedDestination != null && resource == blockedDestination) return
    if (requiresAccess) requestAccess() else select()
}
