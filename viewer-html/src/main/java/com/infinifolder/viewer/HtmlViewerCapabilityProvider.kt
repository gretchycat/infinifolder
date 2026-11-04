package com.infinifolder.viewer

import io.github.gretchycat.infinistream.*

class HtmlViewerCapabilityProvider : CapabilityProvider {
    override val library="viewers"
    override fun handlers():List<CapabilityHandler> = listOf(LibraryViewerComponent("html","com.infinifolder.viewer.HtmlViewerActivity","scribe",setOf("html"),setOf("HTML"),true))
}
