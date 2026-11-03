package com.infinifolder.viewer

import com.infinifolder.mime.MimeRegistry
import com.infinifolder.preview.IconIdentityResolver
import com.infinifolder.preview.IconRegistry
import io.github.gretchycat.infiniscribe.FontDocuments
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FontViewerRoutingTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun nativeFontsReachTheReadOnlyFontActivityThroughTheSharedCatalog() {
        for (name in listOf("sample.ttf", "sample.otf", "collection.ttc", "collection.OTC")) {
            val type = MediaTypeDetector.detect(name, "application/octet-stream")
            assertEquals(MediaType.FONT, type)
            assertEquals("com.infinifolder.viewer.FontViewerActivity", ViewerCatalog.activity(type))
            assertTrue(MediaTypeDetector.isViewableInternally(name)); assertFalse(MediaTypeDetector.isEditable(name))
        }
        assertEquals(MediaType.FONT, MediaTypeDetector.detect("opaque", "application/x-font-ttf"))
        assertEquals(MediaType.FONT, MediaTypeDetector.detect("opaque", "application/font-sfnt"))
    }
    @Test fun unsupportedFontContainersAreNotRoutedToTheFontRenderer() {
        for (ext in listOf("woff", "woff2", "fon", "fnt", "psf", "pfa", "pfb"))
            assertNotEquals(MediaType.FONT, MediaTypeDetector.detect("font.$ext", "font/$ext"))
    }
    @Test fun genesisIdentityAndExplicitUserMappingsSurviveMisleadingSuppliedAudioMime() {
        val registry = IconRegistry.at(temp.root); val resolver = IconIdentityResolver(registry)
        assertEquals("application/x-genesis-rom", resolver.resolve("game.gen", "audio/mpeg") {
            error("Known ROM association must not enter the weak MPEG magic probe")
        }.mimeType)
        assertEquals(MediaType.UNSUPPORTED, MediaTypeDetector.detect("game.gen", MimeRegistry.guess("game.gen")))
        registry.save(registry.mime("application/custom-rom").copy(extensions = setOf("gen")))
        assertEquals("application/custom-rom", resolver.resolve("game.gen", "audio/mpeg") {
            error("Explicit user association must win")
        }.mimeType)
    }
    @Test fun realBundledFontIsInspectedAndRoutedWithoutChangingItsBytes() {
        val relative = "libraries/libinfinicanvas/canvas-android/src/main/assets/fonts/cp437.ttf"
        val font = generateSequence(File(".").absoluteFile) { it.parentFile }
            .map { File(it, relative) }.firstOrNull { it.isFile }
        assertNotNull("Required bundled font fixture is missing", font)
        val file = checkNotNull(font); val before = file.readBytes()
        val doc = FontDocuments.inspect(file)
        assertEquals("TrueType", doc.format); assertTrue(doc.faces.isNotEmpty())
        val face = doc.faces.first()
        assertFalse(face.family.isNullOrBlank()); assertTrue((face.glyphCount ?: 0) >= 256)
        assertTrue((face.unitsPerEm ?: 0) in 16..16384)
        assertEquals(MediaType.FONT, MediaTypeDetector.detect(file.name, MimeRegistry.guess(file.name)))
        assertArrayEquals(before, file.readBytes())
    }
}
