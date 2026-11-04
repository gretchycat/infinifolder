package com.infinifolder.viewer

import io.github.gretchycat.infinistream.*

class PdfViewerCapabilityProvider : CapabilityProvider {
    override val library="viewers"
    override fun handlers():List<CapabilityHandler> = listOf(LibraryViewerComponent("pdf","com.infinifolder.viewer.PdfViewerActivity","scribe",setOf("pdf"),setOf("PDF"),false))
}
