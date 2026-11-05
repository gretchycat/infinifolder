package com.infinifolder.viewer

import io.github.gretchycat.infinistream.*

/** Legacy MindNode Touch plist mind maps, rendered from the map's own node positions. */
class MindNodeTouchCapabilityProvider : CapabilityProvider {
    override val library = "viewers"
    override fun handlers(): List<CapabilityHandler> = listOf(
        MindNodeTouchViewerComponent
    )
}

private object MindNodeTouchViewerComponent : ViewerComponent {
    override val activityClassName = "com.infinifolder.viewer.MindNodeTouchViewerActivity"
    override val descriptor = HandlerDescriptor(
        id = "viewer.mindnode-touch", library = "viewers", title = "MindNode Touch mind map",
        formats = listOf(FormatSupport(
            mimeTypes = setOf("application/x-mindnode-touch"), extensions = setOf("mindnodetouch")
        )),
        operations = setOf(Operations.VIEW), priority = 100,
        limitations = listOf("Reads XML property-list MindNode Touch files; binary property lists are not supported"),
        attributes = mapOf("activity" to activityClassName, "mediaTypes" to "DOCUMENT", "viewerKind" to "mindmap")
    )
}
