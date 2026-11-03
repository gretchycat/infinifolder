package com.infinifolder

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import com.infinifolder.archive.ArchiveUri
import com.infinifolder.model.*
import com.infinifolder.preview.CachedImageCodec
import com.infinifolder.preview.IconBitmapUsability
import com.infinifolder.preview.ThumbnailDecoder
import com.infinifolder.storage.DiskCache
import com.infinifolder.storage.StorageRuntime
import com.infinifolder.viewer.MediaSourceAdapter
import io.github.gretchycat.infiniscribe.*
import io.github.gretchycat.infiniscribe.android.PdfDocuments
import io.github.gretchycat.infiniscribe.mobi.MobiDocuments
import io.github.gretchycat.infinistream.*
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.security.MessageDigest

/** Presentation/cache policy only. Scribe reads book metadata/pages, Vault opens
 * package members, Canvas decodes cover art, and Store owns source access/cache. */
internal class BookArtworkResolver(context: Context, private val runtime: StorageRuntime) {
    private val app = context.applicationContext
    private val disk by lazy { DiskCache.get(app) }
    private data class Cached(val bitmap: Bitmap?, val expires: Long)
    private val memory = object : LruCache<String, Cached>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Cached) = value.bitmap?.allocationByteCount ?: 1
    }
    fun clear() = memory.evictAll()

    fun resolve(entry: ResourceEntry, size: Int): Bitmap? {
        if (entry.metadata.mimeType !in MIME_TYPES) return null
        val unknownRevision = if (entry.metadata.modifiedAtMillis == null) System.currentTimeMillis() / 30000 else 0
        val identity = "${entry.ref}:${entry.uri}:${entry.metadata.name}:${entry.metadata.mimeType}:" +
            "${entry.metadata.sizeBytes}:${entry.metadata.modifiedAtMillis}:$unknownRevision:$size"
        val key = "book-cover-v2:" + MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        memory.get(key)?.takeIf { System.currentTimeMillis() < it.expires }?.let { return it.bitmap }
        val bitmap = IconWork.bounded(3000, lane = "book-cover:${entry.ref.providerId}") {
            runCatching {
                disk.acquire(key)?.use { lease ->
                    if (lease.file.length() <= MAX_BYTES) CachedImageCodec.decode(lease.file.readBytes(), size)
                        ?.frame?.takeBitmap()?.let { return@bounded it }
                }
            }
            val budget = ReadBudget()
            val decoded = when (entry.metadata.mimeType) {
                "application/epub+zip" -> epub(entry, size, budget)
                "application/pdf" -> pdf(entry, size, budget)
                "application/x-mobipocket-ebook", "application/vnd.amazon.ebook", "application/vnd.amazon.mobi8-ebook" ->
                    withLocalSource(entry,budget) {file -> MobiDocuments.cover(file,CancellationToken {budget.check();false})?.let {decodeCover(it,size)}}
                "application/x-fictionbook+xml" -> {
                    val source=object:SourceResource {
                        override val metadata=StreamMetadata()
                        override fun open()=InputStreamSource(budget.wrap((runtime.provider.openRead(entry.ref) as? ProviderResult.Success)?.value ?: throw IOException("Book source unavailable")))
                    }
                    FictionBooks.cover(source,CancellationToken {budget.check();false})?.let {decodeCover(it,size)}
                }
                else -> null
            }?.takeIf(IconBitmapUsability::visible)
            if (decoded != null && !Thread.currentThread().isInterrupted) runCatching {
                disk.allocate("book-cover.png").use { lease ->
                    lease.output().use { check(decoded.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    lease.complete(key)
                }
            }
            decoded
        }
        if (!Thread.currentThread().isInterrupted) memory.put(key, Cached(bitmap,
            System.currentTimeMillis() + if (bitmap == null || entry.metadata.modifiedAtMillis == null) 30000 else 300000))
        return bitmap
    }

    private fun epub(entry: ResourceEntry, size: Int, budget: ReadBudget): Bitmap? {
        fun resourceUri(path: String) = ArchiveUri.build(entry.uri, path)
        fun bookSource(path: String): SourceResource? {
            // Paths here are already normalized by Scribe, not URI references to decode again.
            if (path.isEmpty() || path.startsWith('/') || path.contains('\\') ||
                path.contains('\u0000') || path.split('/').any { it == "." || it == ".." }) return null
            val uri = resourceUri(path)
            val ref = runtime.provider.refForUri(uri) ?: return null
            return object : SourceResource {
                override val metadata = StreamMetadata(provenance = Provenance(source = uri.toString()))
                override fun open(): ByteSource = InputStreamSource(budget.wrap(
                    (runtime.provider.openRead(ref) as? ProviderResult.Success)?.value
                        ?: throw IOException("Book resource unavailable")))
            }
        }
        val resources = object : DocumentResources {
            override val metadata = StreamMetadata(name = entry.metadata.name)
            override fun paths() = emptySequence<String>() // Valid EPUBs locate their OPF via container.xml.
            override fun source(path: String) = bookSource(path)
            override fun close() = Unit
        }
        val covers = EpubCovers.candidates(resources, CancellationToken { budget.check(); false },
            EpubReadLimits(maxXmlBytes = 1024 * 1024, maxXmlEvents = 30000,
                maxXmlText = 1024 * 1024, maxXmlDepth = 64, maxResourcePaths = 4096))
        val decoder = ThumbnailDecoder(app.contentResolver) { uri ->
            runtime.provider.refForUri(uri)?.let { ref ->
                (runtime.provider.openRead(ref) as? ProviderResult.Success)?.value?.let(budget::wrap)
            }
        }
        for (cover in covers.take(4)) {
            budget.check()
            decoder.decode(resourceUri(cover.path), size)?.takeIf(IconBitmapUsability::visible)?.let { return it }
        }
        return null
    }

    private fun pdf(entry: ResourceEntry, size: Int, budget: ReadBudget): Bitmap? {
        fun render(file: java.io.File): Bitmap? {
            budget.check()
            return PdfDocuments.open(file).use { document ->
                if (document.pageCount == 0) null
                else document.renderPage(0, size, size).use { it.takeBitmap() }
            }
        }
        return withLocalSource(entry,budget,::render)
    }
    private fun decodeCover(bytes:ByteArray,size:Int):Bitmap? = CachedImageCodec.decode(bytes,size)?.frame?.takeBitmap()
        ?: ThumbnailDecoder(app.contentResolver) {java.io.ByteArrayInputStream(bytes)}.decode(Uri.parse("book-cover://embedded/image"),size)

    private fun withLocalSource(entry:ResourceEntry,budget:ReadBudget,read:(java.io.File)->Bitmap?):Bitmap? {
        // Real local books need no encoded copy; pin managed sources for the read lifetime.
        val local = MediaSourceAdapter.resolveLocalFile(app, entry.uri)?.takeIf { it.isFile && it.canRead() }
        if (local != null) return disk.pin(local).use { read(local) }
        if ((entry.metadata.sizeBytes ?: 0) > MAX_BYTES) return null
        // Seekable-file backends stage only small provider books for an icon.
        // The viewer can still open larger books normally.
        return disk.stage("book-icon.bin", entry.metadata.sizeBytes) {
            budget.wrap((runtime.provider.openRead(entry.ref) as? ProviderResult.Success)?.value
                ?: throw IOException("Book source unavailable"))
        }.use { read(it.file) }
    }

    private class ReadBudget {
        private val deadline = System.nanoTime() + 2_800_000_000L
        private var remaining = MAX_BYTES
        fun check() {
            if (Thread.currentThread().isInterrupted || System.nanoTime() > deadline || remaining < 0)
                throw InterruptedIOException("Book icon read budget reached")
        }
        fun wrap(input: InputStream): InputStream = object : FilterInputStream(input) {
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
            }
            override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                check()
                val read = super.read(bytes, offset, minOf(count.toLong(), remaining + 1).toInt())
                if (read > 0) remaining -= read
                check()
                return read
            }
        }
    }

    companion object {
        val MIME_TYPES = setOf("application/epub+zip", "application/pdf", "application/x-mobipocket-ebook", "application/vnd.amazon.ebook", "application/vnd.amazon.mobi8-ebook", "application/x-fictionbook+xml")
        private const val MAX_BYTES = 16L * 1024 * 1024
    }
}
