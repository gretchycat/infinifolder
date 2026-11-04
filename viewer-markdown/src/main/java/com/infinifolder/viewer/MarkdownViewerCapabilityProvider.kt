package com.infinifolder.viewer

import io.github.gretchycat.infinistream.*

class MarkdownViewerCapabilityProvider : CapabilityProvider {
    override val library="viewers"
    override fun handlers():List<CapabilityHandler> = listOf(LibraryViewerComponent("markdown","com.infinifolder.viewer.MarkdownViewerActivity","scribe",setOf("markdown"),setOf("MARKDOWN"),true))
}
