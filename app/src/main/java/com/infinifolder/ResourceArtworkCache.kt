package com.infinifolder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.infinifolder.apps.AppsUri
import com.infinifolder.model.*
import com.infinifolder.storage.DiagnosticLog
import com.infinifolder.storage.StorageRuntime
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Artwork lives beside the resource, as .icon-<full filename>.png. No private PNG index. */
class ResourceArtworkCache(context: Context) {
    private val app = context.applicationContext
    private val runtime = StorageRuntime.get(app)
    // Provenance only: lets refresh distinguish our generated PNG from a user's replacement.
    private val provenance = app.getSharedPreferences("generated_icon_provenance", Context.MODE_PRIVATE)
    data class Saved(
        val bitmap: Bitmap,
        val signature: String,
        val fetched: Long,
        val generated: Boolean = true,
        val sidecarStamp: String? = null,
        val persisted: Boolean = false,
        val sourcePixels: Int = maxOf(bitmap.width, bitmap.height),
        val program: Boolean = false
    )

    private fun sidecar(entry: ResourceEntry): ResourceEntry? {
        ResourceIconSidecars.find(entry)?.let { return it }
        // Direct exact-name lookup for local standalone shortcuts; never enumerate the parent.
        if (runtime.local.owns(entry.uri)) {
            val file = runtime.local.fileFor(entry.uri)
            val sibling = file.parentFile?.let { java.io.File(it, iconName(entry)) }
            if (sibling != null && sibling.isFile) return runtime.local.entry(sibling).also {
                ResourceIconSidecars.record(entry, it)
            }
        }
        return null
    }

    private fun bytes(entry: ResourceEntry): ByteArray {
        val input = (runtime.provider.openRead(entry.ref) as? ProviderResult.Success)?.value
            ?: throw IOException("Cannot read icon ${entry.metadata.name}")
        return input.use {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                if (output.size() + count > 8 * 1024 * 1024) throw IOException("Icon PNG exceeds 8 MiB")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    fun load(entry: ResourceEntry, size: Int): Saved? {
        val key = key(entry, size)
        val sidecar = sidecar(entry)
        val stamp = stamp(sidecar)
        memory.get(key)?.let { if (it.sidecarStamp == stamp) return it }
        if (sidecar == null) return null
        val started = System.nanoTime()
        return runCatching {
            val bytes = bytes(sidecar)
            val readFinished = System.nanoTime()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val target = size.coerceAtLeast(1).toLong() * 2
            val options = BitmapFactory.Options().apply {
                // Android initializes this field to zero; decoding treats zero as
                // one, but our sampling arithmetic must explicitly start at one.
                inSampleSize = 1
                while (bounds.outWidth.toLong() / inSampleSize > target ||
                    bounds.outHeight.toLong() / inSampleSize > target) inSampleSize *= 2
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
            val maxSide = maxOf(decoded.width, decoded.height)
            val targetSide = size.coerceAtLeast(1)
            val bitmap = if (maxSide > targetSide) {
                Bitmap.createScaledBitmap(decoded,
                    (decoded.width.toLong() * targetSide / maxSide).toInt().coerceAtLeast(1),
                    (decoded.height.toLong() * targetSide / maxSide).toInt().coerceAtLeast(1), true)
                    .also { if (it !== decoded) decoded.recycle() }
            } else decoded
            val decodedAt = System.nanoTime()
            val p = sidecar.uri.toString()
            val generated = provenance.getString("$p:source", null) == entry.uri.toString() &&
                provenance.getString("$p:sha256", null) == digest(bytes)
            Saved(bitmap, if (generated) provenance.getString("$p:signature", "").orEmpty() else "custom:$stamp",
                if (generated) provenance.getLong("$p:fetched", 0) else System.currentTimeMillis(),
                generated, stamp, persisted = true, sourcePixels = maxOf(bounds.outWidth, bounds.outHeight),
                program = generated && provenance.getBoolean("$p:program", false)).also {
                    memory.put(key, it)
                    DiagnosticLog.record("artwork.cache.loaded", "name=${sidecar.metadata.name} bytes=${bytes.size} pixels=${bitmap.width}x${bitmap.height} elapsedMs=${(System.nanoTime() - started) / 1_000_000} readMs=${(readFinished - started) / 1_000_000} decodeMs=${(decodedAt - readFinished) / 1_000_000} provenanceMs=${(System.nanoTime() - decodedAt) / 1_000_000} provider=${entry.ref.providerId}")
                }
        }.onFailure { DiagnosticLog.record("artwork.cache.read-failed", "name=${iconName(entry)}", it) }.getOrNull()
    }

    fun invalidate(entry: ResourceEntry, size: Int) { memory.remove(key(entry, size)) }

    /** Called only after display. Source writes are exclusive and never overwrite custom artwork. */
    fun save(entry: ResourceEntry, size: Int, bitmap: Bitmap, signature: String, program: Boolean) {
        if (!program) return
        val key = key(entry, size)
        val now = System.currentTimeMillis()
        val existing = ResourceIconSidecars.find(entry)
        memory.put(key, Saved(bitmap, signature, now, sidecarStamp = stamp(existing), program = true))
        // Never generate cache files for icon files themselves.
        val name = entry.metadata.name.lowercase(java.util.Locale.ROOT)
        if (AppsUri.isAppsUri(entry.uri) || name.startsWith(".icon-") ||
            com.infinifolder.preview.DirectoryIconResolver.isExplicitIconFileName(name) ||
            !pending.add(entry.uri.toString())) return
        try {
            persistence.execute {
                var created: ResourceEntry? = null
                try {
                    val png = ByteArrayOutputStream().also {
                        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) throw IOException("PNG encoding failed")
                    }.toByteArray()
                    val provider = runtime.provider
                    val current = sidecar(entry)
                    val published = if (current != null) {
                        val p = current.uri.toString()
                        val originalBytes = bytes(current)
                        if (provenance.getString("$p:source", null) != entry.uri.toString() ||
                            provenance.getString("$p:sha256", null) != digest(originalBytes)) {
                            DiagnosticLog.record("artwork.cache.custom-preserved", "name=${current.metadata.name}")
                            return@execute
                        }
                        val originalSize = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size, originalSize)
                        if (provenance.getString("$p:signature", null) == signature &&
                            maxOf(originalSize.outWidth, originalSize.outHeight) > maxOf(bitmap.width, bitmap.height)) {
                            // A small floating-header image must not downgrade the full grid's cache.
                            memory.put(key, Saved(bitmap, signature, now, sidecarStamp = stamp(current), persisted = true,
                                sourcePixels = maxOf(originalSize.outWidth, originalSize.outHeight), program = true))
                            return@execute
                        }
                        when (val result = provider.replaceContent(current.ref, png)) {
                            is ProviderResult.Success -> current.copy(metadata = current.metadata.copy(sizeBytes = png.size.toLong(), modifiedAtMillis = now))
                            is ProviderResult.Failure -> throw IOException(result.message, result.cause)
                            is ProviderResult.Unsupported -> {
                                DiagnosticLog.record("artwork.cache.refresh-unsupported", "name=${current.metadata.name}")
                                return@execute
                            }
                        }
                    } else {
                        val parent = ResourceIconSidecars.parent(entry)?.let(provider::refForUri)
                            ?: (provider.parent(entry.ref) as? ProviderResult.Success)?.value
                            ?: throw IOException("No writable parent for icon cache")
                        val target = when (val result = provider.createFile(parent, iconName(entry), "image/png")) {
                            is ProviderResult.Success -> result.value.also { created = it }
                            is ProviderResult.Failure -> throw IOException(result.message, result.cause)
                            is ProviderResult.Unsupported -> throw IOException("Location does not support sibling icon files")
                        }
                        if (target.metadata.name != iconName(entry)) throw IOException("Storage changed the exact icon filename")
                        val output = when (val result = provider.openWrite(target.ref)) {
                            is ProviderResult.Success -> result.value
                            is ProviderResult.Failure -> throw IOException(result.message, result.cause)
                            is ProviderResult.Unsupported -> throw IOException("Icon cache is not writable")
                        }
                        output.use { it.write(png) }
                        target.copy(metadata = target.metadata.copy(sizeBytes = png.size.toLong(), modifiedAtMillis = now))
                    }
                    val p = published.uri.toString()
                    provenance.edit().putString("$p:source", entry.uri.toString())
                        .putString("$p:sha256", digest(png)).putString("$p:signature", signature)
                        .putLong("$p:fetched", now).putBoolean("$p:program", true).apply()
                    ResourceIconSidecars.record(entry, published)
                    memory.put(key, Saved(bitmap, signature, now, sidecarStamp = stamp(published), persisted = true, program = true))
                    DiagnosticLog.record("artwork.cache.saved", "name=${published.metadata.name} bytes=${png.size} provider=${published.ref.providerId}")
                } catch (error: Exception) {
                    created?.let { partial ->
                        val cleanup = runtime.provider.delete(partial.ref)
                        if (cleanup !is ProviderResult.Success) DiagnosticLog.record("artwork.cache.cleanup-failed", "name=${partial.metadata.name}")
                    }
                    DiagnosticLog.record("artwork.cache.write-failed", "name=${iconName(entry)} provider=${entry.ref.providerId}", error)
                } finally { pending.remove(entry.uri.toString()) }
            }
        } catch (error: java.util.concurrent.RejectedExecutionException) {
            pending.remove(entry.uri.toString())
            DiagnosticLog.record("artwork.cache.write-deferred", "name=${iconName(entry)}", error)
        }
    }

    companion object {
        val reads = java.util.concurrent.Executors.newFixedThreadPool(2) { task -> Thread(task, "ArtworkCacheRead").apply { isDaemon = true } }
        val appSources = java.util.concurrent.Executors.newFixedThreadPool(2) { task -> Thread(task, "ApplicationArtwork").apply { isDaemon = true } }
        private val pending = ConcurrentHashMap.newKeySet<String>()
        private val persistence = java.util.concurrent.ThreadPoolExecutor(
            1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
            java.util.concurrent.ArrayBlockingQueue<Runnable>(32),
            java.util.concurrent.ThreadFactory { task -> Thread(task, "ArtworkCacheWrite").apply { isDaemon = true } },
            java.util.concurrent.ThreadPoolExecutor.AbortPolicy()
        )
        private val memory = object : android.util.LruCache<String, Saved>(8 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Saved) = value.bitmap.byteCount
        }
        private fun key(entry: ResourceEntry, size: Int) = "${entry.uri}:$size"
        private fun stamp(entry: ResourceEntry?) = entry?.let { "${it.uri}:${it.metadata.sizeBytes}:${it.metadata.modifiedAtMillis}" }
        private fun digest(bytes: ByteArray): String {
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            val hex = "0123456789abcdef"
            return CharArray(hash.size * 2).also { output ->
                hash.forEachIndexed { index, byte ->
                    val value = byte.toInt() and 255
                    output[index * 2] = hex[value ushr 4]
                    output[index * 2 + 1] = hex[value and 15]
                }
            }.concatToString()
        }
        fun iconName(entry: ResourceEntry) = ".icon-${entry.metadata.name}.png"
        fun peekSaved(entry: ResourceEntry, size: Int): Saved? = memory.get(key(entry, size))
        fun peek(entry: ResourceEntry, size: Int): Bitmap? = peekSaved(entry, size)?.bitmap
        fun fresh(saved: Saved?, signature: String, minimumPixels: Int = 0): Boolean = saved != null &&
            saved.sourcePixels >= minimumPixels && saved.signature == signature &&
            System.currentTimeMillis() - saved.fetched in 0 until 60 * 60 * 1000L
    }
}

/** Listing-time exact-name index: no additional directory scan for icon discovery. */
object ResourceIconSidecars {
    private val observed = android.util.LruCache<String, Boolean>(4096)
    @Synchronized fun hasObserved(entry: ResourceEntry): Boolean = observed.get(entry.uri.toString()) == true
    private val sidecars = android.util.LruCache<String, ResourceEntry>(4096)
    @Synchronized fun find(entry: ResourceEntry): ResourceEntry? = sidecars.get(entry.uri.toString())
    private val parents = android.util.LruCache<String, Uri>(4096)
    @Synchronized fun parent(entry: ResourceEntry): Uri? = parents.get(entry.uri.toString())
    @Synchronized fun record(entry: ResourceEntry, icon: ResourceEntry) { sidecars.put(entry.uri.toString(), icon) }
    data class DirectoryArtwork(val entry: ResourceEntry?, val observedAt: Long)
    private val directoryArtwork = android.util.LruCache<String, DirectoryArtwork>(1024)
    @Synchronized fun directory(entry: ResourceEntry): DirectoryArtwork? = directoryArtwork.get(entry.uri.toString())
        ?.takeIf { System.currentTimeMillis() - it.observedAt in 0 until 60 * 60 * 1000L }
    @Synchronized fun observe(entries: List<ResourceEntry>, parent: Uri? = null) {
        if (parent != null) directoryArtwork.put(parent.toString(), DirectoryArtwork(
            com.infinifolder.preview.DirectoryIconResolver.findBestIcon(entries), System.currentTimeMillis()))
        val named = entries.associateBy { it.metadata.name }
        entries.forEach { entry ->
            observed.put(entry.uri.toString(), true)
            if (parent != null) parents.put(entry.uri.toString(), parent)
            val sidecar = named[ResourceArtworkCache.iconName(entry)]?.takeUnless { it.metadata.isDirectory }
            if (sidecar == null) sidecars.remove(entry.uri.toString()) else sidecars.put(entry.uri.toString(), sidecar)
        }
    }
}
