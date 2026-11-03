package com.infinifolder

import android.net.TestUri
import org.junit.Assert.*
import org.junit.Test

class HomeItemRoutingTest {
    @Test fun resolvedWebLinksOpenTheirHandlerWithoutAnInAppBrowserStop() {
        for (url in listOf("https://example.com/path?token=a%2Fb#section", "http://example.com/", "HTTPS://example.com/")) {
            val uri = TestUri(url)
            assertTrue(HomeItemRouting.opensExternally(uri, false))
            assertEquals(url, uri.toString())
        }
    }
    @Test fun mailPhoneAndCustomSchemesUseTheirRegisteredExternalApp() {
        for (uri in listOf("mailto:user@example.com", "tel:+15551234567", "sms:+15551234567", "irc://example.com/room", "custom-app:some-target"))
            assertTrue(uri, HomeItemRouting.opensExternally(TestUri(uri), false))
    }
    @Test fun storageAndNativeProtocolTargetsRetainTheirExistingOpenPath() {
        for (uri in listOf("file:///path", "content://provider/document/1", "archive://source/entry", "apps://package",
            "container-links:/link.iflink", "partial-downloads:/file", "sftp://host/path", "ftp://host/path",
            "ftps://host/path", "ssh://host/path", "sshfs://host/path", "smb://host/share", "cifs://host/share",
            "webdav://host/path", "dav://host/path", "davs://host/path", "magnet:?xt=urn:btih:example", "torrent:example",
            "infinifolder-termux://run?command=echo"))
            assertFalse(uri, HomeItemRouting.opensExternally(TestUri(uri), false))
    }
    @Test fun anOwnedResourceIsNotDelegatedOutsideItsStorageProvider() {
        assertFalse(HomeItemRouting.opensExternally(TestUri("https://example.com/document"), true))
        assertFalse(HomeItemRouting.opensExternally(TestUri("file:///image.png"), true))
    }
}
