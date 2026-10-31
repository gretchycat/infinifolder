package com.infinifolder.viewer

import com.infinifolder.archive.BlockDevice
import com.infinifolder.model.*
import com.infinifolder.storage.StorageProvider
import io.github.gretchycat.infinistream.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Proxy

class DocumentSourceTest {
    private val ref = ResourceRef("fixture", "opaque")
    private fun provider(seek: () -> ProviderResult<BlockDevice>, read: () -> ProviderResult<java.io.InputStream>): StorageProvider =
        Proxy.newProxyInstance(StorageProvider::class.java.classLoader, arrayOf(StorageProvider::class.java)) { _, method, _ ->
            when (method.name) {
                "openSeekable" -> seek()
                "openRead" -> read()
                else -> throw AssertionError("Unexpected provider request: ${method.name}")
            }
        } as StorageProvider

    @Test fun unsupportedPositionalAudioDoesNotStartASequentialDownload() {
        val provider = provider({ ProviderResult.Unsupported(ResourceCapability.SEEK) }, {
            throw AssertionError("Staging owns the single sequential download")
        })
        assertNull(MediaSourceAdapter.openPositionalSource(provider, ref, "fixture://audio"))
    }

    @Test fun positionalAudioFailurePreservesItsCauseWithoutSequentialFallback() {
        val cause = IOException("connection lost")
        val provider = provider({ ProviderResult.Failure("cannot open", cause) }, {
            throw AssertionError("A failed positional open is not Unsupported")
        })
        val error = assertThrows(IOException::class.java) { MediaSourceAdapter.openPositionalSource(provider, ref, "fixture://audio") }
        assertSame(cause, error.cause)
    }

    @Test fun documentSourceUsesActualPositionalReadsWithoutOpeningAStreamOrStat() {
        var closes = 0
        val device = object : BlockDevice {
            override val size = 9L * 1024 * 1024 * 1024
            override val isReadOnly = true
            override fun readAt(offset: Long, buffer: ByteArray, dstOffset: Int, length: Int): Int {
                repeat(length) { buffer[dstOffset + it] = 'x'.code.toByte() }; return length
            }
            override fun writeAt(offset: Long, buffer: ByteArray, srcOffset: Int, length: Int) { throw AssertionError() }
            override fun close() { closes++ }
        }
        val provider = provider({ ProviderResult.Success(device) }, { throw AssertionError("No sequential open") })
        MediaSourceAdapter.openDocumentSource(provider, ref, "fixture://original").use { source ->
            assertTrue(source is PositionalReadable)
            assertEquals("fixture://original", source.metadata.provenance.source)
            assertEquals(device.size, source.metadata.length)
            val buffer = ByteArray(8); assertEquals(8, (source as PositionalReadable).readAt(5L * 1024 * 1024 * 1024, buffer))
        }
        assertEquals(1, closes)
    }
    @Test fun onlyUnsupportedSeekMayFallBackAndArchiveReadsNeverRequestStaging() {
        var reads = 0
        val provider = provider({ ProviderResult.Unsupported(ResourceCapability.SEEK) }, {
            reads++; ProviderResult.Success("hello".byteInputStream())
        })
        MediaSourceAdapter.openDocumentSource(provider, ref, "fixture://original").use { assertFalse(it is PositionalReadable) }
        assertEquals(1, reads)
        val archive = provider({ throw AssertionError("Archive seek can stage the whole entry") }, { ProviderResult.Success("archive".byteInputStream()) })
        MediaSourceAdapter.openDocumentSource(archive, ref, "archive://original", allowPositional = false).use {
            assertEquals("archive://original", it.metadata.provenance.source)
        }
        val failed = provider({ ProviderResult.Failure("permission denied") }, { throw AssertionError("Failure is not Unsupported") })
        assertThrows(IOException::class.java) { MediaSourceAdapter.openDocumentSource(failed, ref, "fixture://original") }
    }

    @Test fun anAdapterConstructionFailureClosesTheOpenedDevice() {
        var closes = 0
        val device = object : BlockDevice {
            override val size: Long get() = throw IOException("size failed")
            override val isReadOnly = true
            override fun readAt(offset: Long, buffer: ByteArray, dstOffset: Int, length: Int) = 0
            override fun writeAt(offset: Long, buffer: ByteArray, srcOffset: Int, length: Int) { throw AssertionError() }
            override fun close() { closes++ }
        }
        val provider = provider({ ProviderResult.Success(device) }, { throw AssertionError("No fallback after failure") })
        assertThrows(IOException::class.java) { MediaSourceAdapter.openDocumentSource(provider, ref, "fixture://original") }
        assertEquals(1, closes)
    }
}
