package com.infinifolder.viewer

import io.github.gretchycat.infinistream.*

class EpubViewerCapabilityProvider : CapabilityProvider {
    override val library="viewers"
    override fun handlers():List<CapabilityHandler> = listOf(LibraryViewerComponent("epub","com.infinifolder.viewer.EpubViewerActivity","scribe",setOf("epub"),setOf("EPUB","EBOOK","DOCUMENT"),false))
}
