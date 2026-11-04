package com.infinifolder.viewer

import io.github.gretchycat.infinistream.*

class FontViewerCapabilityProvider : CapabilityProvider {
    override val library="viewers"
    override fun handlers():List<CapabilityHandler> = listOf(LibraryViewerComponent("font","com.infinifolder.viewer.FontViewerActivity","scribe",setOf("font"),setOf("FONT"),false))
}
