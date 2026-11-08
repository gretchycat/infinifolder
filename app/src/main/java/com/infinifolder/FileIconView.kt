package com.infinifolder

import com.infinifolder.theme.IconImageView
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.infinifolder.apps.AppsStorageProvider
import com.infinifolder.apps.AppsUri
import com.infinifolder.mime.MimeRegistry
import com.infinifolder.model.ResourceEntry
import com.infinifolder.model.ResourceMetadata
import com.infinifolder.preview.CustomIconStore
import com.infinifolder.storage.LocalStorageProvider
import com.infinifolder.theme.IconThemeManager

/** First-frame artwork for both file grids; ResourceIconResolver supplies the final icon. */
object FileIconView {
    fun fallback(meta: ResourceMetadata): String = if (meta.isDirectory) "📁" else
        MimeRegistry.fallbackIcon(MimeRegistry.effective(meta.name, meta.mimeType))

    /** No theme/store construction or source access is needed for the initial card. */
    fun createDefault(
        context: Context,
        entry: ResourceEntry,
        meta: ResourceMetadata,
        asGrid: Boolean,
        iconSizeDp: Int
    ): IconImageView = IconImageView(context).apply {
        scaleType=ImageView.ScaleType.FIT_CENTER
        val size=((if(asGrid)iconSizeDp else 44)*context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        setImageBitmap(when {
            AppsUri.isRoot(entry.uri) -> FileTypeIcons.installedApplications(size)
            meta.isDirectory -> FileTypeIcons.folder(size)
            else -> FileTypeIcons.bitmap(meta,size)
        })
        contentDescription="File icon"
        layoutParams=FrameLayout.LayoutParams(-1,-1,Gravity.CENTER)
    }

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
        // Put a complete local icon in the view immediately; selected-theme artwork and
        // directory covers can then replace it asynchronously without an empty first frame.
        return createDefault(context,entry,meta,asGrid,iconSizeDp).apply {
            val size=((if(asGrid)iconSizeDp else 44)*context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
            val hasTheme=!theme.getActiveThemeId().isNullOrBlank()
            val themed=when {
                AppsUri.isRoot(entry.uri) && hasTheme -> theme.peekPlaceIcon("installed_apps",size)
                hasTheme -> theme.peekFileIcon(meta,size)
                else -> null
            }
            if(themed!=null)setImageBitmap(themed)
        }
    }
}
