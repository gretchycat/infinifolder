package com.infinifolder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Looper
import android.util.LruCache
import com.infinifolder.shortcut.WebMetadataResolver
import com.infinifolder.shortcut.PublicIconUrls
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

    private val workers=java.util.concurrent.ThreadPoolExecutor(2,2,30,java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(32))
    private val listeners=ConcurrentHashMap<String,java.util.concurrent.ConcurrentLinkedQueue<()->Unit>>()
    private val active=ConcurrentHashMap<String, Boolean>()
    fun pending(target:String):Boolean = PublicIconUrls.origin(target)?.let {active.containsKey(it)} == true
    fun request(context:Context,target:String,onReady:()->Unit):Bitmap? {
        val origin=PublicIconUrls.origin(target) ?: return null
        val key=MessageDigest.getInstance("SHA-256").digest(origin.toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it.toInt() and 255)}
        val now=System.currentTimeMillis()
        memory.get(key)?.takeIf {now<it.expires}?.let {return it.bitmap}
        val disk=File(context.cacheDir,"web_link_icons/$key.png")
        if(attempts[key]?.let {now-it<RETRY_MS}==true && !disk.isFile)return null
        val callbacks=listeners.getOrPut(origin) {java.util.concurrent.ConcurrentLinkedQueue()}
        if(callbacks.size<64)callbacks.add(onReady)
        if(active.putIfAbsent(origin,true)==null) {
            try {workers.execute {
                try {favicon(context,origin)} finally {
                    active.remove(origin);listeners.remove(origin)?.forEach {runCatching {it()}}
                }
            }} catch (_:java.util.concurrent.RejectedExecutionException) {
                active.remove(origin);listeners.remove(origin);attempts[key]=now
            }
        }
        return null
    }
    fun favicon(context: Context, target: String): Bitmap? {
        val url = PublicIconUrls.origin(target) ?: return null
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
        val bitmap = runCatching { WebMetadataResolver.fetchPublicFavicon(url, 128) }.getOrNull() ?: return null
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
