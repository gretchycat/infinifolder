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
        val defaultArchive = !AppsUri.isAppUri(entry.uri) && ArchiveFormats.isDefaultBrowsableArchive(meta.name, meta.mimeType)
        val mimeKey = CustomIconStore.resolveMimeKey(meta)
        val sidePx = ((if (asGrid) iconSizeDp else 44) * context.resources.displayMetrics.density).toInt()
        val fallback = if (defaultArchive) "📦" else fallback(meta)
        fun bitmapView(bitmap: android.graphics.Bitmap) = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(bitmap)
            layoutParams = FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)
        }
        ResourceArtworkCache.peek(entry, sidePx)?.let { return bitmapView(it) }
        if (icons.hasMimeIcon(mimeKey)) {
            mimePreview?.let { return it(mimeKey, fallback) }
            icons.getMimeIconBitmap(mimeKey)?.let { return bitmapView(it) }
        }
        if (theme.hasActiveTheme()) theme.resolveFileIcon(meta, sidePx)?.let { return bitmapView(it) }
        if (MimeRegistry.effective(meta.name, meta.mimeType) == "application/vnd.android.package-archive" && local.owns(entry.uri)) {
            apps.getApkFileIconBitmap(local.fileFor(entry.uri).absolutePath, sidePx)?.let { return bitmapView(it) }
        }
        if (!defaultArchive && ImageFormats.isImage(meta)) imagePreview?.let { return it() }
        return TextView(context).apply {
            text = fallback
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, if (asGrid) iconSizeDp * .72f else 28f)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
    }
}
