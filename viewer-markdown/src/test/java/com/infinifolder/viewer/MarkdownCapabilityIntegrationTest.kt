package com.infinifolder.viewer

import io.github.gretchycat.infiniscribe.ScribeCapabilities
import io.github.gretchycat.infiniscribe.TextDocumentHandle
import io.github.gretchycat.infinistream.*
import org.junit.Assert.*
import org.junit.Test

class MarkdownCapabilityIntegrationTest {
    @Test fun installedComponentUsesLinkedScribeServicesWithoutStartingTheApplication() {
        val metadata=StreamMetadata(name="notes.md",content=ContentType(mimeType="text/markdown"),capabilities=setOf(Capability.READ))
        val resource=CapabilityResource(metadata)
        val component=ViewerComponents.registry.query(Operations.VIEW,includeUnavailable=true).single {it.descriptor.id=="viewer.markdown"}
        // JVM validation covers discovery/processing; Android Activity/WebView display remains a device check.
        assertEquals(AvailabilityState.UNAVAILABLE,component.availability.state)
        assertEquals(MatchConfidence.CANDIDATE,component.handler.probe(CapabilityProbeContext(resource)).confidence)
        val selected=ScribeCapabilities.registry.probe(resource,Operations.DECODE).first()
        assertEquals("scribe.markdown",selected.descriptor.id)
        InputStreamSource("# Library pipeline\n\n**Direct rendering**".byteInputStream(),metadata).use {source->
            selected.handler.service(StreamProcessor::class.java)!!.open(mapOf("encoded" to listOf(source))).use {session->
                val document=(session.outputs.getValue("text").single() as TextDocumentHandle).document
                val html=MarkdownRenderer.renderToHtml(document.text)
                assertTrue(html.contains("<h1>Library pipeline</h1>"))
                assertTrue(html.contains("<strong>Direct rendering</strong>"))
                assertFalse(document.hasMore)
            }
        }
    }
}
