package com.infinifolder

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.infinifolder.apps.AppsStorageProvider
import com.infinifolder.apps.AppsUri
import com.infinifolder.archive.ArchiveFormats
import com.infinifolder.mime.MimeRegistry
import com.infinifolder.model.ResourceEntry
import com.infinifolder.model.ResourceMetadata
import com.infinifolder.preview.CustomIconStore
import com.infinifolder.preview.ImageFormats
import com.infinifolder.storage.LocalStorageProvider
import com.infinifolder.theme.IconThemeManager

/** Shared MIME/custom/theme/APK icon selection used by both file grids. */
object FileIconView {
    fun fallback(meta: ResourceMetadata): String = if (meta.isDirectory) "📁" else
        MimeRegistry.fallbackIcon(MimeRegistry.effective(meta.name, meta.mimeType))

    fun create(
        context: Context,
        entry: ResourceEntry,
        meta: ResourceMetadata,
        asGrid: Boolean,
        iconSizeDp: Int,
        icons: CustomIconStore,
        theme: IconThemeManager,
        local: LocalStorageProvider,
        apps: AppsStorageProvider,
        mimePreview: ((String, String) -> View)? = null,
        imagePreview: (() -> View)? = null
    ): View {
        // A visible card's worker supplies the authoritative result from ResourceIconResolver.
        // This is only a cheap first-frame placeholder, with no theme/content/storage lookup.
        return ImageView(context).apply {
            scaleType=ImageView.ScaleType.FIT_CENTER
            setImageBitmap(if(meta.isDirectory)FileTypeIcons.folder(((if(asGrid)iconSizeDp else 44)*context.resources.displayMetrics.density).toInt())
                else FileTypeIcons.bitmap(meta,((if(asGrid)iconSizeDp else 44)*context.resources.displayMetrics.density).toInt()))
            contentDescription="Loading file icon"
            layoutParams=FrameLayout.LayoutParams(-1,-1,Gravity.CENTER)
        }
    }
}
