package com.infinifolder.preview

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ImageDecodersFixtureTest {

    private fun resolveImagesDir(): File {
        val candidates = listOf(
            File("filesystem-test-images/images"),
            File("../filesystem-test-images/images")
        )
        return candidates.first { it.isDirectory }
    }

    private fun decodeFixturePixels(fileName: String): ImageDecodeUtils.DecodedPixels? {
        val file = File(resolveImagesDir(), fileName)
        assertTrue("Missing fixture $fileName", file.isFile && file.length() > 0)
        val ext = file.extension.lowercase()
        val bytes = file.readBytes()
        ImageDecodeUtils.lastDecodedPixelsForTest = null

        when (ext) {
            "tga", "icb", "vda", "vst" -> TgaDecoder.decode(bytes.inputStream(), 256)
            "qoi", "ff" -> SimpleLosslessImageDecoder.decode(bytes, 256)
            "pbm", "pgm", "ppm", "pnm", "pam", "pfm" -> NetpbmDecoder.decode(bytes, 256)
            "xbm", "xpm", "xwd" -> X11ImageDecoder.decode(bytes, 256)
            "pcx", "dcx" -> PcxDecoder.decode(bytes, 256)
            "ora", "kra", "psd", "psb", "xcf", "ase", "aseprite" -> LayeredImageDecoder.decode(bytes, 256, ext)
            "tif", "tiff", "dng" -> TiffAndRawImageDecoder.decode(bytes, 256)
            "iff", "ilbm", "lbm", "neo", "pi1", "scr", "kla", "koa",
            "icns", "pntg", "mac", "pct", "pict", "ras", "sun", "sgi", "rgb",
            "pdb", "otb", "sixel" -> RetroImageDecoder.decode(bytes, 256, ext)
            "hdr", "exr", "dds", "ktx", "fits", "dcm" -> HdrAndScientificImageDecoder.decode(bytes, 256, ext)
            "wmf", "wmz", "ps", "eps", "ept", "ans" -> MetafileAndVectorImageDecoder.decode(bytes, 256, ext)
            "png" -> PureJavaPngFallback.decode(bytes, 256)
        }
        return ImageDecodeUtils.lastDecodedPixelsForTest
    }

    @Test
    fun allPureKotlinFormatDecodersDecodeGeneratedFixtures() {
        val fixtures = listOf(
            // Truevision TGA
            "icon.tga", "icon.icb", "icon.vda", "icon.vst",
            // Simple lossless
            "icon.qoi", "icon.ff",
            // Netpbm & PFM
            "icon.pbm", "icon.pgm", "icon.ppm", "icon.pnm", "icon.pam", "icon.pfm",
            // X11
            "icon.xbm", "icon.xpm", "icon.xwd",
            // PCX / DCX
            "icon.pcx", "icon.dcx",
            // Multi-layer & pixel art
            "icon.ora", "icon.kra", "icon.psd", "icon.psb", "icon.xcf", "icon.ase", "icon.aseprite",
            // TIFF & DNG
            "icon.tif", "icon.tiff", "icon.dng",
            // Retro & workstation
            "icon.iff", "icon.ilbm", "icon.lbm", "icon.neo", "icon.pi1",
            "icon.scr", "icon.kla", "icon.koa",
            "icon.icns", "icon.pntg", "icon.mac", "icon.pct", "icon.pict",
            "icon.ras", "icon.sun", "icon.sgi", "icon.rgb",
            "icon.pdb", "icon.otb", "icon.sixel",
            // HDR, GPU textures, scientific, medical
            "icon.hdr", "icon.exr", "icon.dds", "icon.ktx", "icon.fits", "icon.dcm",
            // Metafiles, PostScript/EPS, ANSI art
            "icon.wmf", "icon.wmz", "icon.ps", "icon.eps", "icon.ept", "icon.ans"
        )

        for (name in fixtures) {
            val decoded = decodeFixturePixels(name)
            assertNotNull("Failed to decode $name", decoded)
            assertTrue("Invalid dimensions for $name: ${decoded!!.width}x${decoded.height}", decoded.width > 0 && decoded.height > 0)
            val nonZeroPixels = decoded.pixels.count { (it and 0x00FFFFFF) != 0 }
            assertTrue("Decoded all-black image for $name", nonZeroPixels > 0)
        }
    }

    @Test
    fun jpeg2000HeaderDetectionRecognizesJp2AndJ2k() {
        val dir = resolveImagesDir()
        assertTrue(Jpeg2000Decoder.isJpeg2000(File(dir, "icon.jp2").readBytes()))
        assertTrue(Jpeg2000Decoder.isJpeg2000(File(dir, "icon.j2k").readBytes()))
    }

    @Test
    fun macPaintDecodesWithoutHorizontalShift() {
        val decoded = decodeFixturePixels("icon.pntg")!!
        // Top-left corner (row 0, col 0) of the MacPaint fixture is dark background (black = 0xFF000000)
        // and the last scanline (row 719) is also decoded without running out of stream bytes.
        assertTrue((decoded.pixels[0] and 0x00FFFFFF) == 0)
        assertTrue((decoded.pixels[(719 * 576) + 575] and 0x00FFFFFF) == 0)
    }
}
