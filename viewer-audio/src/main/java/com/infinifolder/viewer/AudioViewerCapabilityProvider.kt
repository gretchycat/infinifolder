package com.infinifolder.viewer

import io.github.gretchycat.infinistream.*

class AudioViewerCapabilityProvider : CapabilityProvider {
    override val library="viewers"
    override fun handlers():List<CapabilityHandler> = listOf(LibraryViewerComponent("audio","com.infinifolder.viewer.AudioPlayerActivity","sound",setOf("audio"),setOf("AUDIO"),false))
}
