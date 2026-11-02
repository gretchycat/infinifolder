package com.infinifolder.preview

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UriIconRegistryTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun exactThenAuthoritySchemeAndPathRules() {
        val registry=IconRegistry.at(temp.root)
        registry.save(IconRegistry.UriRule("scheme",scheme="https",iconName="web-browser"))
        registry.save(IconRegistry.UriRule("host",scheme="https",authority="EXAMPLE.org",iconName="network-server"))
        registry.save(IconRegistry.UriRule("path",scheme="https",authority="example.org",pathPrefix="/docs",iconName="folder-documents"))
        registry.save(IconRegistry.UriRule("exact",exactUri="HTTPS://EXAMPLE.ORG/docs/a?token=A",iconName="document-special"))
        val reopened=IconRegistry.at(temp.root)
        assertEquals("exact",reopened.matchUri("https://example.org/docs/a?token=A")?.id)
        assertEquals("path",reopened.matchUri("https://example.org/docs/b?token=B")?.id)
        assertEquals("host",reopened.matchUri("https://example.org/docs-other")?.id)
        assertEquals("scheme",reopened.matchUri("https://other.org/docs")?.id)
        assertNull(reopened.matchUri("http://other.org/docs"))
    }
    @Test fun normalizationDoesNotCollapseMeaningfulComponents() {
        assertEquals("https://example.org:443/A%2Fb?q=A#Top",IconRegistry.normalizeUri("HTTPS://EXAMPLE.ORG:443/A%2Fb?q=A#Top"))
        assertNotEquals(IconRegistry.normalizeUri("https://host/A"),IconRegistry.normalizeUri("https://host/a"))
        assertNotEquals(IconRegistry.normalizeUri("https://host:443/"),IconRegistry.normalizeUri("https://host/"))
        assertNotEquals(IconRegistry.normalizeUri("https://host/%2F"),IconRegistry.normalizeUri("https://host//"))
        assertEquals("mailto:User@Example.org",IconRegistry.normalizeUri("MAILTO:User@Example.org"))
    }
    @Test fun deterministicTieBreakAndDeletePersist() {
        val registry=IconRegistry.at(temp.root)
        registry.save(IconRegistry.UriRule("z",scheme="magnet",iconName="z"))
        registry.save(IconRegistry.UriRule("a",scheme="magnet",iconName="a"))
        assertEquals("a",registry.matchUri("magnet:?xt=urn:btih:abc")?.id)
        registry.removeUriRule("a")
        assertEquals("z",IconRegistry.at(temp.root).matchUri("magnet:?xt=urn:btih:abc")?.id)
    }
}
