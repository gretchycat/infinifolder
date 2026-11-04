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
        // Use only cached selected-theme artwork on the first frame. A cold theme load
        // reserves the slot until its worker finishes instead of flashing built-in artwork.
        return IconImageView(context).apply {
            scaleType=ImageView.ScaleType.FIT_CENTER
            val size=((if(asGrid)iconSizeDp else 44)*context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
            setImageBitmap(if(AppsUri.isRoot(entry.uri)) {
                if(!theme.getActiveThemeId().isNullOrBlank())theme.peekPlaceIcon("installed_apps",size)
                else FileTypeIcons.installedApplications(size)
            } else if(!theme.getActiveThemeId().isNullOrBlank())theme.peekFileIcon(meta,size)
                else if(meta.isDirectory)FileTypeIcons.folder(size) else FileTypeIcons.bitmap(meta,size))
            contentDescription="Loading file icon"
            layoutParams=FrameLayout.LayoutParams(-1,-1,Gravity.CENTER)
        }
    }
}
