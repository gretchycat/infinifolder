package com.infinifolder.overlay

import android.net.Uri
import com.infinifolder.TermuxCommandLink
import com.infinifolder.apps.AppsUri
import com.infinifolder.model.ResourceEntry
import com.infinifolder.storage.LinkKind
import com.infinifolder.storage.LinkResolver

/** Route a resolved target; neither the shortcut's location nor its label defines its type. */
internal object SelectionLinkRouting {
    enum class Action {
        DIRECTORY,
        ARCHIVE,
        FILE,
        URI_HANDLER,
    }

    fun action(uri: Uri, target: ResourceEntry?): Action =
        when {
            target == null || AppsUri.isAppUri(uri) || TermuxCommandLink.owns(uri) ->
                Action.URI_HANDLER
            target.metadata.isDirectory -> Action.DIRECTORY
            ArchiveNavigationPolicy.shouldBrowse(
                target.metadata.name,
                target.metadata.mimeType,
                false,
            ) -> Action.ARCHIVE
            else -> Action.FILE
        }

    fun selectedFile(
        original: ResourceEntry,
        target: ResourceEntry,
        returnLinkDocument: Boolean,
    ): ResourceEntry =
        if (returnLinkDocument && LinkResolver.kind(original.metadata) == LinkKind.SHORTCUT)
            original
        else target
}
