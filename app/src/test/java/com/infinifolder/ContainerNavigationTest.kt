package com.infinifolder

import com.infinifolder.storage.FileMagicDetector
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class ContainerNavigationTest {
    @Test
    fun romAndFirmwareBytesDoNotBecomeArchivesFromTheirFilenameOrMime() {
        val rom = ByteArray(4096) { 0x60.toByte() }.apply { this[1] = 0xea.toByte() }
        for (name in listOf("game.a26", "game.a52", "game.a78", "boot.img")) {
            var parsed = false
            assertFalse(
                ContainerNavigation.verifyContent(
                    false,
                    {
                        FileMagicDetector.detectFromStream(
                            name,
                            "application/x-arj",
                            rom.size.toLong(),
                        ) {
                            rom.inputStream()
                        }
                    },
                    { parsed = true },
                )
            )
            assertFalse("Must not invoke an archive reader for $name", parsed)
        }
    }

    @Test
    fun imageExtensionStillRequiresARealFilesystem() {
        assertFalse(
            ContainerNavigation.verifyContent(
                true,
                { null },
                { throw IOException("No filesystem") },
            )
        )
        // A raw filesystem may only be identifiable by its actual filesystem parser.
        assertTrue(ContainerNavigation.verifyContent(true, { null }, {}))
    }

    @Test
    fun realEmptyArchiveCanBeBrowsedButATruncatedArchiveCannot() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use {}
        val bytes = output.toByteArray()
        val file = java.io.File.createTempFile("container", ".zip")
        try {
            file.writeBytes(bytes)
            assertTrue(
                ContainerNavigation.verifyContent(
                    false,
                    { FileMagicDetector.detectFromBytes(bytes) },
                    { ZipFile(file).use { assertEquals(0, it.size()) } },
                )
            )
            val truncated = byteArrayOf(0x50, 0x4b, 3, 4, 20, 0, 0, 0)
            file.writeBytes(truncated)
            assertFalse(
                ContainerNavigation.verifyContent(
                    false,
                    { FileMagicDetector.detectFromBytes(truncated) },
                    { ZipFile(file).use { it.size() } },
                )
            )
        } finally {
            file.delete()
        }
    }
}
