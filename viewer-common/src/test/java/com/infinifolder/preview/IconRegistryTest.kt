package com.infinifolder.preview

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IconRegistryTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun newImagesDefaultToMimeIconsAndTextNeverBecomesAnImage() {
        val registry=IconRegistry.at(temp.root)
        for(mime in listOf("image/png","image/jpeg","image/svg+xml","image/x-ansi","text/x-diz")) {
            assertTrue(mime,registry.mime(mime).isImage)
            assertTrue(mime,registry.mime(mime).overrideImageIcon)
        }
        assertFalse(registry.mime("text/plain").isImage)
    }
    @Test fun allFieldsAndUnknownDataSurviveReopening() {
        val file=temp.newFile("icon_registry.json")
        file.writeText("""{"vendor":{"keep":42},"mime":{"image/png":{"aliases":["image/x-png"],"isImage":true,"extensionNote":"keep"}}}""")
        val registry=IconRegistry(file)
        val record=registry.mime("image/png").copy(extensions=setOf("png","image.png"),iconName="image-special",overrideIcon="uri_icons/custom.png",overrideImageIcon=false)
        registry.save(record)
        assertEquals(record,IconRegistry(file).mime("image/png"))
        val persisted=JSONObject(file.readText())
        assertEquals(42,persisted.getJSONObject("vendor").getInt("keep"))
        assertEquals("image/x-png",persisted.getJSONObject("mime").getJSONObject("image/png").getJSONArray("aliases").getString(0))
        assertEquals("keep",persisted.getJSONObject("mime").getJSONObject("image/png").getString("extensionNote"))
    }
    @Test fun explicitAssociationsWinAndMultipleExplicitCandidatesRemainAmbiguous() {
        val registry=IconRegistry.at(temp.root)
        registry.save(registry.mime("application/custom").copy(extensions=setOf("txt","tar.custom")))
        assertEquals(setOf("application/custom"),registry.candidates("notes.txt"))
        assertEquals(setOf("application/custom"),registry.candidates("backup.tar.custom"))
        registry.save(registry.mime("application/other").copy(extensions=setOf("txt")))
        assertEquals(setOf("application/custom","application/other"),registry.candidates("notes.txt"))
    }
    @Test fun checkboxIsRetainedWhenImageCapabilityIsDisabled() {
        val registry=IconRegistry.at(temp.root)
        registry.save(registry.mime("image/png").copy(isImage=false,overrideImageIcon=false))
        val reopened=IconRegistry.at(temp.root)
        assertFalse(reopened.mime("image/png").isImage)
        assertFalse(reopened.mime("image/png").overrideImageIcon)
        assertFalse(IconPolicy.steps(reopened.mime("image/png")).contains(IconPolicy.Step.THUMBNAIL))
    }
    @Test fun otherInstancesObserveUpdatesAndRevisionAdvances() {
        val first=IconRegistry.at(temp.root);val other=IconRegistry.at(temp.root)
        val before=other.revision
        first.save(first.mime("image/png").copy(overrideImageIcon=false))
        assertTrue(other.revision>before);assertFalse(other.mime("image/png").overrideImageIcon)
        first.save(first.mime("image/png").copy(overrideImageIcon=true))
        assertTrue(other.mime("image/png").overrideImageIcon)
    }
    @Test fun unreadableRegistryIsNotOverwritten() {
        val file=temp.newFile("broken.json");file.writeText("unfinished {")
        val registry=IconRegistry(file)
        assertTrue(registry.mime("image/png").overrideImageIcon)
        assertNotNull(registry.readError)
        try {registry.save(registry.mime("image/png"));fail("Must preserve corrupt file for recovery")}catch(_:IllegalStateException){}
        assertEquals("unfinished {",file.readText())
    }
    @Test fun clearingOverrideDoesNotRemoveOtherFields() {
        val registry=IconRegistry.at(temp.root)
        val record=registry.mime("application/pdf").copy(iconName="my-document",overrideIcon="uri_icons/pdf.png")
        registry.save(record);registry.save(record.copy(overrideIcon=null))
        assertEquals(record.copy(overrideIcon=null),IconRegistry.at(temp.root).mime("application/pdf"))
    }
}
