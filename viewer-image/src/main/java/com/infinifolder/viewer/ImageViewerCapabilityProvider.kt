package com.infinifolder.viewer

import io.github.gretchycat.infinistream.*

class ImageViewerCapabilityProvider : CapabilityProvider {
    override val library="viewers"
    override fun handlers():List<CapabilityHandler> = listOf(LibraryViewerComponent("image","com.infinifolder.viewer.ImageViewerActivity","canvas",setOf("image"),setOf("IMAGE"),true))
}
