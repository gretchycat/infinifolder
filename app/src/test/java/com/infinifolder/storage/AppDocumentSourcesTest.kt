package com.infinifolder.storage

import android.net.TestUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDocumentSourcesTest {

    @Test
    fun testIsAppAuthority() {
        assertFalse(AppDocumentSources.isAppAuthority("com.android.externalstorage.documents"))
        assertFalse(AppDocumentSources.isAppAuthority("com.android.providers.downloads.documents"))
        assertFalse(AppDocumentSources.isAppAuthority("com.android.providers.media.documents"))
        assertFalse(AppDocumentSources.isAppAuthority("installed"))
        assertFalse(AppDocumentSources.isAppAuthority("category"))
        assertFalse(AppDocumentSources.isAppAuthority("package"))
        assertFalse(AppDocumentSources.isAppAuthority(null as String?))

        assertTrue(AppDocumentSources.isAppAuthority("com.termux.documents"))
        assertTrue(AppDocumentSources.isAppAuthority("com.foobar.provider"))

        val termuxUri = TestUri("content://com.termux.documents/tree/home")
        assertTrue(AppDocumentSources.isAppAuthority(termuxUri))

        val localUri = TestUri("content://com.android.externalstorage.documents/tree/primary")
        assertFalse(AppDocumentSources.isAppAuthority(localUri))
    }

    @Test
    fun testRecordAndGetProgramName() {
        AppDocumentSources.recordProgramName("com.termux.documents", "Termux Monet")
        AppDocumentSources.recordProgramName("com.acode.documents", "Acode")

        val termuxUri = TestUri("content://com.termux.documents/tree/home/document/projects")
        val acodeUri = TestUri("content://com.acode.documents/tree/workspace")

        // Passing null context safely relies on the recorded cache
        // If not in cache and context is null, it returns null without crashing
        assertEquals("Termux Monet", AppDocumentSources.getProgramName(null as android.content.Context?, termuxUri))
        assertEquals("Acode", AppDocumentSources.getProgramName(null as android.content.Context?, acodeUri))

        val systemUri = TestUri("content://com.android.externalstorage.documents/tree/primary")
        assertNull(AppDocumentSources.getProgramName(null as android.content.Context?, systemUri))
    }
}
