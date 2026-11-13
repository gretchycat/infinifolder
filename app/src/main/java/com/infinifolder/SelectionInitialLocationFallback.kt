package com.infinifolder

/** Only a failed initial InfiniSelect location should be replaced by its virtual root. */
internal object SelectionInitialLocationFallback {
    fun shouldReturnToVirtualRoot(
        selectionMode: Boolean,
        resetRoot: Boolean,
        location: String,
        virtualRoot: String,
    ): Boolean = selectionMode && resetRoot && location != virtualRoot
}
