package com.infinifolder.viewer

import io.github.gretchycat.infinistream.*

class CsvViewerCapabilityProvider : CapabilityProvider {
    override val library="viewers"
    override fun handlers():List<CapabilityHandler> = listOf(LibraryViewerComponent("csv","com.infinifolder.viewer.CsvViewerActivity","scribe",setOf("spreadsheet"),setOf("SPREADSHEET"),true))
}
