package com.infinifolder.mime

import com.infinifolder.archive.ArchiveFormats
import com.infinifolder.model.*
import org.junit.Assert.*
import org.junit.Test

class MimeRegistryTest {
    @Test fun applicationIdentitySurvivesGenericZipMime() {
        val cases = mapOf("app.apk" to "application/vnd.android.package-archive", "game.love" to "application/x-love-game",
            "book.epub" to "application/epub+zip", "document.docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "sheet.xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "notes.odt" to "application/vnd.oasis.opendocument.text")
        for ((name, expected) in cases) {
            assertEquals(expected, MimeRegistry.effective(name, "application/zip", "application/zip"))
            assertTrue(ArchiveFormats.isArchive(name, expected))
            assertTrue(ArchiveFormats.isPackagedFormat(name, expected))
            assertFalse(ArchiveFormats.isDefaultBrowsableArchive(name, expected))
            assertEquals("zip", ArchiveFormats.containerExtension(name))
            assertEquals(expected.replace('/', '-'), MimeRegistry.iconCandidates(expected).first())
        }
    }
    @Test fun aliasesAndMimeParametersAreCanonical() {
        assertEquals("application/zip", MimeRegistry.normalize("APPLICATION/X-ZIP-COMPRESSED; version=1"))
        assertEquals("application/java-archive", MimeRegistry.normalize("application/x-java-archive"))
        assertEquals("application/xml", MimeRegistry.normalize("text/xml; charset=utf-8"))
    }
    @Test fun directoryNamesCannotBecomePackagedFiles() {
        assertEquals("vnd.android.document/directory", MimeRegistry.effective("folder.apk", "vnd.android.document/directory"))
    }
    @Test fun logicalTypesAreIndependentOfBackendTables() {
        assertEquals("text/markdown", MimeRegistry.effective("readme.md", "text/plain"))
        assertEquals("text/csv", MimeRegistry.effective("table.csv", "application/octet-stream"))
        assertEquals("application/json", MimeRegistry.effective("data.json", "text/plain"))
        assertEquals("application/octet-stream", MimeRegistry.effective("unknown.unknown", "application/octet-stream"))
    }
    @Test fun capabilitiesDoNotInventMutationsForReadOnlyBackends() {
        val entry = ResourceMetadata("App", "application/vnd.android.package-archive", 10, null, false, canWrite = true)
        val capabilities = ResourceCapabilities.from(entry, BackendFeatures(readOnly = true, creation = CreationGuarantee.UNSUPPORTED))
        assertEquals(Support.UNSUPPORTED, capabilities[ResourceCapability.WRITE])
        assertEquals(Support.UNSUPPORTED, capabilities[ResourceCapability.MOVE])
        assertEquals(Support.UNKNOWN, capabilities[ResourceCapability.SEEK])
    }
    @Test fun providerFlagsRemainMoreSpecificThanPermissionBooleans() {
        val metadata = ResourceMetadata("document", "text/plain", 10, null, false, canWrite = true,
            operationHints = mapOf(ResourceCapability.DELETE to Support.UNSUPPORTED, ResourceCapability.RENAME to Support.SUPPORTED))
        val capabilities = ResourceCapabilities.from(metadata, BackendFeatures())
        assertEquals(Support.UNSUPPORTED, capabilities[ResourceCapability.DELETE])
        assertEquals(Support.SUPPORTED, capabilities[ResourceCapability.RENAME])
    }
    @Test fun realMediaContainerExtensionsHaveExplicitMimeTypes() {
        assertEquals("application/vnd.rn-realmedia", MimeRegistry.guess("movie.rm"))
        assertEquals("application/vnd.rn-realmedia-vbr", MimeRegistry.guess("movie.RMVB"))
        assertEquals("audio/x-pn-realaudio", MimeRegistry.guess("sound.ra"))
    }

}
