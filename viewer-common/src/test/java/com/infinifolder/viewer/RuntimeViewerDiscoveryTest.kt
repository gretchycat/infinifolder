package com.infinifolder.viewer

import io.github.gretchycat.infinistream.*
import io.github.gretchycat.infiniscribe.*
import org.junit.Assert.*
import org.junit.Test

class RuntimeViewerDiscoveryTest {
    @Test fun libraryRegistrationChangesARealViewerProbeAndExecutesItsLibraryProcessor() {
        val viewer=LibraryViewerComponent("text","com.infinifolder.viewer.TextViewerActivity","scribe",setOf("text"),setOf("TEXT"))
        val resource=CapabilityResource(StreamMetadata(name="sample.newdocument",content=ContentType(mimeType="application/x-newdocument")))
        assertEquals(MatchConfidence.NONE,viewer.probe(CapabilityProbeContext(resource)).confidence)
        val handler=ProcessorCapabilityHandler(HandlerDescriptor("scribe.test.newdocument","scribe","Additional text encoding",
            listOf(FormatSupport(setOf("application/x-newdocument"),setOf("newdocument"))),setOf(Operations.DECODE),
            attributes=mapOf("presentation" to "text")),TextDecodeProcessor().registration())
        ScribeCapabilities.registry.register(handler).use {
            assertEquals(MatchConfidence.CANDIDATE,viewer.probe(CapabilityProbeContext(resource)).confidence)
            assertTrue(viewer.describe().formats.any {"newdocument" in it.extensions})
            val selected=ScribeCapabilities.registry.probe(resource,Operations.DECODE).single {it.descriptor.id==handler.descriptor.id}
            InputStreamSource("No application singleton".byteInputStream()).use {source->
                selected.handler.service(StreamProcessor::class.java)!!.open(mapOf("encoded" to listOf(source))).use {session->
                    assertEquals("No application singleton",(session.outputs.getValue("text").single() as TextDocumentHandle).document.text)
                }
            }
        }
        assertEquals(MatchConfidence.NONE,viewer.probe(CapabilityProbeContext(resource)).confidence)
    }
}
