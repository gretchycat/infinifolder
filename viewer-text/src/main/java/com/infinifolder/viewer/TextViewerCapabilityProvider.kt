package com.infinifolder.viewer

import io.github.gretchycat.infinistream.*

class TextViewerCapabilityProvider : CapabilityProvider {
    override val library="viewers"
    override fun handlers():List<CapabilityHandler> = listOf(LibraryViewerComponent("text","com.infinifolder.viewer.TextViewerActivity","scribe",setOf("text"),setOf("TEXT"),true,priority=5))
}
