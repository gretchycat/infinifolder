package com.infinifolder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Looper
import android.util.LruCache
import com.infinifolder.shortcut.WebMetadataResolver
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Favicons use app cache only; link files and remote storage are never rewritten. */
object WebLinkIcons {
    private const val FRESH_MS = 24 * 60 * 60 * 1000L
    private const val RETRY_MS = 10 * 60 * 1000L
    private data class Saved(val bitmap: Bitmap, val expires: Long)
    private val memory = object : LruCache<String, Saved>(2 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Saved) = value.bitmap.allocationByteCount
    }
    private val attempts = ConcurrentHashMap<String, Long>()

    fun favicon(context: Context, target: String): Bitmap? {
        val url = WebMetadataResolver.normalizeInputUri(target)
        if (!WebMetadataResolver.isWebUri(url)) return null
        val key = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val now = System.currentTimeMillis()
        memory.get(key)?.takeIf { now < it.expires }?.let { return it.bitmap }
        // Cache reads and network work belong to the visible-card worker.
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val folder = File(context.cacheDir, "web_link_icons")
        val file = File(folder, "$key.png")
        if (file.isFile && file.length() in 1..262144 && now - file.lastModified() < FRESH_MS) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth in 1..128 && bounds.outHeight in 1..128) {
                BitmapFactory.decodeFile(file.absolutePath)?.let {
                    memory.put(key, Saved(it, file.lastModified() + FRESH_MS)); return it
                }
            }
        }
        val previous = attempts.putIfAbsent(key, now)
        if (previous != null && (now - previous < RETRY_MS || !attempts.replace(key, previous, now))) return null
        if (attempts.size > 256) attempts.entries.sortedBy { it.value }.take((attempts.size - 256).coerceAtLeast(0))
            .forEach { attempts.remove(it.key, it.value) }
        val bitmap = runCatching { WebMetadataResolver.fetchWebMetadata(url, 128).faviconBitmap }.getOrNull() ?: return null
        memory.put(key, Saved(bitmap, now + FRESH_MS))
        runCatching {
            folder.mkdirs()
            val temp = File.createTempFile("favicon-", ".png", folder)
            try {
                temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                if (!temp.renameTo(file)) temp.delete()
            } finally { temp.delete() }
            folder.listFiles()?.filter { it.extension == "png" }?.sortedByDescending { it.lastModified() }
                ?.drop(128)?.forEach { it.delete() }
        }
        return bitmap
    }
}
