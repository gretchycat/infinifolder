package com.infinifolder.viewer

import io.github.gretchycat.infinistream.*

class HexViewerCapabilityProvider : CapabilityProvider {
    override val library="viewers"
    override fun handlers():List<CapabilityHandler> = listOf(LibraryViewerComponent("hex","com.infinifolder.viewer.HexViewerActivity",
        "scribe",setOf("hex"),setOf("UNSUPPORTED"),true,
        directFormats=listOf(FormatSupport(setOf("application/octet-stream","application/binary","application/x-binary","binary/octet-stream")))))
}
