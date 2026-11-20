package com.infinifolder

import android.content.Context
import android.view.View
import com.infinifolder.model.ResourceEntry

/** Folder diagnostics adapter for the shared browser viewport. */
internal class VirtualizedEntrySurface(
    context: Context,
    entries: List<ResourceEntry>,
    columns: Int,
    cellHeightPx: Int,
    makeCard: (ResourceEntry) -> View,
    onCardRemoved: (ResourceEntry, View) -> Unit,
) :
    io.github.gretchycat.infiniselect.browser.VirtualizedEntrySurface<ResourceEntry>(
        context,
        entries,
        columns,
        cellHeightPx,
        makeCard,
        onCardRemoved,
        {
            "name=${it.metadata.name} provider=${it.ref.providerId} mime=${it.metadata.mimeType} size=${it.metadata.sizeBytes}"
        },
        { event, detail, error ->
            com.infinifolder.storage.DiagnosticLog.record(event, detail, error)
        },
    )
