package com.infinifolder.preview

import com.infinifolder.model.ResourceMetadata
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageFormatsTest {
    private fun file(name: String, mime: String) = ResourceMetadata(name, mime, 1L, 0L, false)

    @Test fun imageMimeTypesArePreviewable() {
        assertTrue(ImageFormats.isImage(file("photo", "image/jpeg")))
        assertTrue(ImageFormats.isImage(file("anything.bin", "IMAGE/AVIF")))
        assertTrue(ImageFormats.isImage(file("vector", "image/svg+xml")))
        assertTrue(ImageFormats.isImage(file("modern", "image/jxl")))
        assertTrue(ImageFormats.isImage(file("game_texture", "image/x-tga")))
    }

    @Test fun genericMimeTypesFallBackToExtension() {
        listOf(
            "a.HEIC", "b.heif", "c.avif", "d.dng", "e.CR2", "f.webp", "g.ico", "h.wbmp", "i.apng",
            "j.svg", "k.svgz", "l.jxl", "m.tga", "n.icb", "o.vda", "p.vst"
        ).forEach {
            assertTrue(it, ImageFormats.isImage(file(it, "application/octet-stream")))
        }
    }

    @Test fun nonImagesAndDirectoriesAreNotPreviewable() {
        assertFalse(ImageFormats.isImage(file("notes.txt", "text/plain")))
        assertFalse(ImageFormats.isImage(file("archive", "application/octet-stream")))
        assertFalse(ImageFormats.isImage(file("music.mp3", "audio/mpeg")))
        assertFalse(ImageFormats.isImage(ResourceMetadata("pics.png", "vnd.android.document/directory", null, 0L, true)))
    }
}
