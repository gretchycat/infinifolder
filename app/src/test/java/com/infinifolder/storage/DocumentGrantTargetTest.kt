package com.infinifolder.storage

import org.junit.Assert.*
import org.junit.Test

class DocumentGrantTargetTest {
    private val termuxHome = "/data/data/com.termux/files/home"

    @Test fun termuxProviderGrantRetainsItsOpaqueHomeId() {
        assertTrue(matchesDocumentGrant("com.termux.documents", termuxHome, "com.termux.documents", termuxHome))
    }
    @Test fun sharedMediaDirectoryCannotStandInForTermuxProvider() {
        assertFalse(matchesDocumentGrant("com.termux.documents", termuxHome,
            "com.android.externalstorage.documents", "primary:Android/media/com.termux"))
    }
    @Test fun pickerMaySupplyAnUnknownRootIdOnlyForTheExpectedApp() {
        assertTrue(matchesDocumentGrant("com.termux.documents", null, "com.termux.documents", termuxHome))
        assertFalse(matchesDocumentGrant("com.termux.documents", null, "another.app.documents", termuxHome))
    }
    @Test fun differentDocumentDoesNotGrantTheRequestedRoot() {
        assertFalse(matchesDocumentGrant("com.termux.documents", termuxHome, "com.termux.documents", "$termuxHome/Documents"))
    }
}
