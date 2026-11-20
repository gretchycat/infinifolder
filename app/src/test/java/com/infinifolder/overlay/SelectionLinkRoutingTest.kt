package com.infinifolder.overlay

import android.net.TestUri
import com.infinifolder.model.ResourceEntry
import com.infinifolder.model.ResourceMetadata
import com.infinifolder.model.ResourceRef
import org.junit.Assert.*
import org.junit.Test

class SelectionLinkRoutingTest {
    private fun entry(
        uri: String,
        name: String,
        directory: Boolean = false,
        mime: String = "application/octet-stream",
        provider: String = "local",
    ) =
        ResourceEntry(
            ResourceRef(provider, uri),
            ResourceMetadata(name, mime, null, null, directory),
            TestUri(uri),
        )

    @Test
    fun directoryTargetsNavigateRegardlessOfSchemeOrShortcutLocation() {
        for (uri in
            listOf(
                "file:///folder",
                "smb://connection/share",
                "sftp://connection/roms",
                "content://provider/tree/42",
            )) {
            val target = entry(uri, "Folder", directory = true)
            assertEquals(
                SelectionLinkRouting.Action.DIRECTORY,
                SelectionLinkRouting.action(target.uri, target),
            )
        }
    }

    @Test
    fun linkedArchivesUseTargetMetadataEvenWithAnUnrelatedShortcutLabel() {
        val target =
            entry("sftp://connection/library/roms.zip", "roms.zip", mime = "application/zip")
        assertEquals(
            SelectionLinkRouting.Action.ARCHIVE,
            SelectionLinkRouting.action(target.uri, target),
        )
    }

    @Test
    fun arbitraryUrisAreDispatchedWithoutRequiringAReadableStorageEntry() {
        for (uri in
            listOf(
                "https://example.com/library",
                "mailto:user@example.com",
                "tel:+15551234567",
                "custom-app:resource/42",
                "infinifolder-termux://run?command=echo",
            )) {
            assertEquals(
                SelectionLinkRouting.Action.URI_HANDLER,
                SelectionLinkRouting.action(TestUri(uri), null),
            )
        }
    }

    @Test
    fun applicationTargetsUseFoldersDispatcherInsteadOfBeingReturnedAsFiles() {
        val target = entry("apps://package/org.example.game", "Application")
        assertEquals(
            SelectionLinkRouting.Action.URI_HANDLER,
            SelectionLinkRouting.action(target.uri, target),
        )
    }

    @Test
    fun regularGameTargetsCanReturnTheOriginalLinkForProfileMetadata() {
        val original =
            entry(
                "file:///any/folder/Game.iflink",
                "Game.iflink",
                mime = "application/vnd.infinifolder.shortcut+json",
            )
        val target = entry("smb://connection/roms/Game.gen", "Game.gen", provider = "smb")
        assertEquals(
            SelectionLinkRouting.Action.FILE,
            SelectionLinkRouting.action(target.uri, target),
        )
        assertSame(original, SelectionLinkRouting.selectedFile(original, target, true))
        assertSame(target, SelectionLinkRouting.selectedFile(original, target, false))
    }
}
