package com.infinifolder.storage

import android.net.TestUri
import com.infinifolder.model.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*

class LinkResolverTest {
    private fun entry(name: String, native: Boolean = false) = ResourceEntry(ResourceRef("test", name),
        ResourceMetadata(name, "application/octet-stream", 10, null, false, isSymbolicLink = native), TestUri("test://$name"))
    private class Backend(val entries: Map<String, ResourceEntry>, val targets: Map<String, String>) : StorageProvider {
        override val id = "test"
        override fun stat(ref: ResourceRef) = entries[ref.documentId]?.let { ProviderResult.Success(it) } ?: ProviderResult.Failure("Missing target")
        override fun resolveLink(ref: ResourceRef) = targets[ref.documentId]?.let { ProviderResult.Success(ResourceRef(id, it)) } ?: ProviderResult.Failure("Broken link")
        override fun openRead(ref: ResourceRef): ProviderResult<InputStream> = ProviderResult.Success(ByteArrayInputStream(byteArrayOf(0x4c,0,0,0)))
        override fun openWrite(ref: ResourceRef) = ProviderResult.Unsupported(ResourceCapability.WRITE)
        override fun list(parent: ResourceRef) = ProviderResult.Success(emptyList<ResourceEntry>())
        override fun createDirectory(parent: ResourceRef, name: String) = ProviderResult.Unsupported(ResourceCapability.CREATE)
        override fun createFile(parent: ResourceRef, name: String, mimeType: String) = ProviderResult.Unsupported(ResourceCapability.CREATE)
        override fun rename(ref: ResourceRef, name: String) = ProviderResult.Unsupported(ResourceCapability.RENAME)
        override fun delete(ref: ResourceRef) = ProviderResult.Unsupported(ResourceCapability.DELETE)
        override fun copy(source: ResourceRef, destination: ResourceRef) = ProviderResult.Unsupported(ResourceCapability.COPY)
        override fun move(source: ResourceRef, destination: ResourceRef) = ProviderResult.Unsupported(ResourceCapability.MOVE)
    }
    @Test fun bothLinkEncodingsShareOneCategoryButRetainTheirKind() {
        assertEquals(LinkKind.NATIVE, LinkResolver.kind(entry("alias", true).metadata))
        assertEquals(LinkKind.SHORTCUT, LinkResolver.kind(entry("shortcut.iflink").metadata))
        assertEquals(LinkKind.SHORTCUT, LinkResolver.kind(entry("legacy.lnk").metadata))
        assertNull(LinkResolver.kind(entry("plain.txt").metadata))
    }
    @Test fun nativeChainResolvesToTheSameResourceAsDirectOpening() {
        val first = entry("first", true)
        val second = entry("second", true)
        val target = entry("target.txt")
        val backend = Backend(mapOf("first" to first, "second" to second, "target.txt" to target), mapOf("first" to "second", "second" to "target.txt"))
        val result = LinkResolver.resolve(backend, first)
        assertTrue(result is ProviderResult.Success)
        assertEquals(target, (result as ProviderResult.Success).value.entry)
        assertEquals(listOf(LinkKind.NATIVE, LinkKind.NATIVE), result.value.kinds)
    }
    @Test fun cyclicLinksFailBeforeOpeningOrChangingData() {
        val first = entry("first", true)
        val second = entry("second", true)
        val backend = Backend(mapOf("first" to first, "second" to second), mapOf("first" to "second", "second" to "first"))
        val result = LinkResolver.resolve(backend, first)
        assertTrue(result is ProviderResult.Failure)
        assertTrue((result as ProviderResult.Failure).message.contains("Cyclic"))
    }
    @Test fun binaryWindowsLnkIsReportedAsUnsupportedEncoding() {
        val legacy = entry("windows.lnk")
        val result = LinkResolver.resolve(Backend(mapOf("windows.lnk" to legacy), emptyMap()), legacy)
        assertTrue(result is ProviderResult.Failure)
        assertTrue((result as ProviderResult.Failure).message.contains("Unsupported link-file encoding"))
    }
}
