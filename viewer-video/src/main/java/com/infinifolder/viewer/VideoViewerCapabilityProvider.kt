package com.infinifolder.viewer

import io.github.gretchycat.infinistream.*

class VideoViewerCapabilityProvider : CapabilityProvider {
    override val library="viewers"
    override fun handlers():List<CapabilityHandler> = listOf(LibraryViewerComponent("video","com.infinifolder.viewer.VideoPlayerActivity","video",setOf("video"),setOf("VIDEO"),false))
}
