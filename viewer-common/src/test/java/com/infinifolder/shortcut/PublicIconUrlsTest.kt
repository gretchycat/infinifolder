package com.infinifolder.shortcut

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class PublicIconUrlsTest {
    @Test fun originDropsTokensAndPathAndRejectsUserCredentials() {
        assertEquals("https://example.org:8443/",PublicIconUrls.origin("https://EXAMPLE.org:8443/private/login?secret=token#key"))
        assertNull(PublicIconUrls.origin("https://user:password@example.org/private"))
        assertNull(PublicIconUrls.origin("file:///etc/passwd"))
    }
    @Test fun addressGuardRejectsPrivateLocalAndMappedAddresses() {
        for(ip in listOf("127.0.0.1","10.1.2.3","192.168.1.1","172.16.1.1","169.254.1.2","100.64.1.2","0.0.0.0","::1","fc00::1","fe80::1","::ffff:127.0.0.1"))
            assertFalse(ip,PublicIconUrls.publicAddress(InetAddress.getByName(ip)))
        assertTrue(PublicIconUrls.publicAddress(InetAddress.getByName("8.8.8.8")))
        assertTrue(PublicIconUrls.publicAddress(InetAddress.getByName("2606:4700:4700::1111")))
    }
    @Test fun redirectDestinationsMustPassSchemeAndAllAddressChecks() {
        val public=InetAddress.getByName("8.8.8.8");val private=InetAddress.getByName("192.168.0.1")
        assertTrue(PublicIconUrls.allowed("https://public.test/icon.png") {arrayOf(public)})
        assertFalse(PublicIconUrls.allowed("https://mixed.test/icon.png") {arrayOf(public,private)})
        assertFalse(PublicIconUrls.allowed("http://private.test/icon.png") {arrayOf(private)})
        assertFalse(PublicIconUrls.allowed("content://provider/icon") {error("Must not resolve non-HTTP schemes")})
    }
}
