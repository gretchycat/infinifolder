package com.infinifolder

import android.content.ComponentName
import android.content.Context
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Icon
import com.infinifolder.model.*
import com.infinifolder.storage.StorageRuntime
import com.infinifolder.theme.ThemeManager

object HomeShortcuts {
    /** Launcher routing must migrate without contacting the shortcut's storage backend. */
    fun migratePinned(context: Context) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val dispatcher = ComponentName(context, HomeItemActivity::class.java)
        val updates = manager.pinnedShortcuts.filter { it.id.startsWith("home_") }
            .filter { it.activity != dispatcher || it.intent?.component != dispatcher || !it.isEnabled }
            .mapNotNull { pin ->
                val uri = pin.intent?.data ?: return@mapNotNull null
                ShortcutInfo.Builder(context, pin.id).setActivity(dispatcher)
                    .setShortLabel(pin.shortLabel ?: "InfiniFolder item").setIntent(HomeItemActivity.intent(context, uri)).build()
            }
        if (updates.isNotEmpty()) {
            val updated = manager.updateShortcuts(updates)
            if (updated) manager.enableShortcuts(updates.map { it.id })
            com.infinifolder.storage.DiagnosticLog.record("home.shortcut.migrated", "count=${updates.size} accepted=$updated")
        }
    }

    /** Called on a worker: folder artwork, remote links and package icons may require I/O. */
    fun create(context: Context, entry: ResourceEntry, id: String = "home_${entry.uri.toString().hashCode().toUInt().toString(16)}", label: String? = null): ShortcutInfo {
        val resolved = ResourceIconResolver(context).resolve(entry, 192)
        val title = (label ?: resolved.label).ifBlank { "InfiniFolder item" }
        val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val artwork = resolved.bitmap
        if (artwork != null) {
            val scale = 176f / maxOf(artwork.width, artwork.height)
            val width = artwork.width * scale
            val height = artwork.height * scale
            canvas.drawBitmap(artwork, null, RectF((192-width)/2f, (192-height)/2f, (192+width)/2f, (192+height)/2f), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        } else {
            canvas.drawCircle(96f, 96f, 94f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ThemeManager.activeTheme.cardBackground })
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 112f; textAlign = Paint.Align.CENTER }
            canvas.drawText(resolved.fallback, 96f, 96f-(paint.ascent()+paint.descent())/2f, paint)
        }
        return ShortcutInfo.Builder(context, id).setShortLabel(title.take(40)).setLongLabel(title.take(100))
            .setActivity(ComponentName(context, HomeItemActivity::class.java))
            .setIcon(Icon.createWithBitmap(bitmap)).setIntent(HomeItemActivity.intent(context, entry.uri)).build()
    }

    /** Migrate existing pins to the transparent dispatcher and refresh their actual resource icons. */
    fun refreshPinned(context: Context) {
        runCatching {
        migratePinned(context)
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val provider = StorageRuntime.get(context).provider
        manager.pinnedShortcuts.filter { it.id.startsWith("home_") }.forEach { pinned ->
            runCatching {
                val uri = pinned.intent?.data ?: return@runCatching
                val ref = provider.refForUri(uri) ?: return@runCatching
                val entry = (provider.stat(ref) as? ProviderResult.Success)?.value ?: return@runCatching
                manager.updateShortcuts(listOf(create(context, entry, pinned.id, pinned.shortLabel.toString())))
            }.onFailure { com.infinifolder.storage.DiagnosticLog.record("home.shortcut.refresh-failed", error = it) }
        }
        }.onFailure { com.infinifolder.storage.DiagnosticLog.record("home.shortcut.refresh-failed", error = it) }
    }
}
