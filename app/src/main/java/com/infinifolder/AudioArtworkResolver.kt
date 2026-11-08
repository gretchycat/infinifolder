package com.infinifolder

import android.graphics.Bitmap
import android.content.Context
import android.net.Uri
import android.util.LruCache
import com.infinifolder.archive.ArchiveUri
import com.infinifolder.model.*
import com.infinifolder.preview.CachedImageCodec
import com.infinifolder.preview.DirectoryIconResolver
import com.infinifolder.preview.IconBitmapUsability
import com.infinifolder.preview.ThumbnailDecoder
import com.infinifolder.storage.*
import java.io.InterruptedIOException
import java.security.MessageDigest

/** Audio tag parsing belongs to Sound; source access stays behind the storage providers. */
internal class AudioArtworkResolver(context:Context,private val runtime:StorageRuntime,private val decoder:ThumbnailDecoder) {
    private val app=context.applicationContext
    private val disk by lazy {DiskCache.get(app)}
    data class Artwork(val bitmap:Bitmap,val source:String)
    data class Result(val artwork:Artwork?=null,val pending:Boolean=false)
    private data class Cached(val bitmap:Bitmap?,val expires:Long)
    private data class Folder(val uri:Uri,val candidates:List<ResourceEntry>,val observedAt:Long,val expires:Long,val pending:Boolean=false)
    private val embedded=bitmapCache()
    private val covers=bitmapCache()
    private val folders=LruCache<String,Folder>(256)

    fun clear() {embedded.evictAll();covers.evictAll();folders.evictAll()}

    fun resolve(entry:ResourceEntry,size:Int):Result {
        embedded(entry,size)?.let {return Result(Artwork(it,"audio-embedded-art"))}
        if(Thread.currentThread().isInterrupted)return Result()
        val folder=folder(entry) ?: return Result()
        for(candidate in folder.candidates) {
            if(Thread.currentThread().isInterrupted)return Result(pending=folder.pending)
            val key="${candidate.ref}:${candidate.uri}:${candidate.metadata.sizeBytes}:" +
                "${candidate.metadata.modifiedAtMillis ?: folder.observedAt}:$size"
            val saved=covers.get(key)?.takeIf {System.currentTimeMillis()<it.expires}
            val bitmap=if(saved!=null)saved.bitmap else {
                val decoded=IconWork.bounded(1500,lane="audio-cover:${candidate.ref.providerId}") {
                    readDisk("cover:$key",size) ?: decoder.decode(candidate.uri,size)?.takeIf(IconBitmapUsability::visible)
                        ?.also {writeDisk("cover:$key",it)}
                }
                covers.put(key,Cached(decoded,System.currentTimeMillis()+if(decoded==null)30000 else 300000))
                decoded
            }
            if(bitmap!=null)return Result(Artwork(bitmap,"audio-directory-cover"),folder.pending)
        }
        return Result(pending=folder.pending)
    }

    private fun embedded(entry:ResourceEntry,size:Int):Bitmap? {
        val key="${entry.ref}:${entry.uri}:${entry.metadata.name}:${entry.metadata.sizeBytes}:${entry.metadata.modifiedAtMillis}:$size"
        embedded.get(key)?.takeIf {System.currentTimeMillis()<it.expires}?.let {return it.bitmap}
        val started = android.os.SystemClock.uptimeMillis()
        DiagnosticLog.record("artwork.audio-embedded.start",
            "name=${entry.metadata.name} provider=${entry.ref.providerId} mime=${entry.metadata.mimeType} size=${entry.metadata.sizeBytes}")
        val bitmap=IconWork.bounded(1500,lane="audio-tags:${entry.ref.providerId}") {
            val diskKey="embedded:$key:"+if(entry.metadata.modifiedAtMillis==null)System.currentTimeMillis()/30000 else "known"
            readDisk(diskKey,size)?.let {return@bounded it}
            // Native archive slices are fine. Never extract a whole compressed song for an icon.
            val source=if(ArchiveUri.isArchiveUri(entry.uri)) {
                val result=runtime.archives.openStreamingSeekable(entry.ref)
                val device=(result as? ProviderResult.Success)?.value ?: return@bounded null
                BlockDeviceSource(device)
            } else ProviderStreams.openPositional(runtime.provider,entry.ref,entry.metadata)
            source.use {
                val extractor=com.infinifolder.audio.SoundCapabilities.registry.query("extract-image")
                    .firstNotNullOfOrNull {it.handler.service(io.github.gretchycat.infinistream.EncodedImageExtractor::class.java)}
                    ?: return@bounded null
                extractor.extract(source,size,io.github.gretchycat.infinistream.CancellationToken {Thread.currentThread().isInterrupted})?.use {image ->
                    val bytes=image.resource.open().use {io.github.gretchycat.infinistream.readBytes(it,8*1024*1024)}
                    CachedImageCodec.decode(bytes,size)?.frame?.takeBitmap()?.takeIf(IconBitmapUsability::visible)
                        ?.also {writeDisk(diskKey,it)}
                }
            }
        }
        embedded.put(key,Cached(bitmap,System.currentTimeMillis()+if(bitmap==null || entry.metadata.modifiedAtMillis==null)30000 else 300000))
        DiagnosticLog.record("artwork.audio-embedded.complete",
            "name=${entry.metadata.name} provider=${entry.ref.providerId} found=${bitmap != null} elapsedMs=${android.os.SystemClock.uptimeMillis() - started}")
        return bitmap
    }

    private fun diskKey(key:String):String {
        val digest=MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        val hex="0123456789abcdef"
        return "audio-art-v1:"+buildString(64) {for(byte in digest) {
            append(hex[(byte.toInt() ushr 4) and 15]);append(hex[byte.toInt() and 15])
        }}
    }

    private fun readDisk(key:String,size:Int):Bitmap? = runCatching {
        disk.acquire(diskKey(key))?.use {lease->
            if(lease.file.length()>8*1024*1024)return@use null
            CachedImageCodec.decode(lease.file.readBytes(),size)
                ?.frame?.takeBitmap()?.takeIf(IconBitmapUsability::visible)
        }
    }.getOrNull()

    private fun writeDisk(key:String,bitmap:Bitmap) {
        val signature=diskKey(key)
        if(!pendingWrites.add(signature))return
        try {
            persistence.execute {
                try {
                    disk.allocate("audio-art.png").use {lease->
                        lease.output().use {check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
                        lease.complete(signature)
                    }
                } catch(_:Exception) {} // Optional disposable art obeys the existing shared cache budget.
                finally {pendingWrites.remove(signature)}
            }
        } catch(_:java.util.concurrent.RejectedExecutionException) {pendingWrites.remove(signature)}
    }

    private fun folder(entry:ResourceEntry):Folder? {
        val knownUri=ResourceIconSidecars.parent(entry)
        val parent=knownUri?.let(runtime.provider::refForUri) ?: IconWork.bounded(500,lane="audio-parent:${entry.ref.providerId}") {
            (runtime.provider.parent(entry.ref) as? ProviderResult.Success)?.value
        } ?: return null
        val key=parent.toString()
        val now=System.currentTimeMillis()
        val saved=folders.get(key)
        val uri=knownUri ?: saved?.uri ?: IconWork.bounded(500,lane="audio-parent:${entry.ref.providerId}") {
            (runtime.provider.stat(parent) as? ProviderResult.Success)?.value?.uri
        } ?: return null
        var observed=ResourceIconSidecars.directory(uri)
        if(saved!=null && now<saved.expires && (observed==null || observed.observedAt==saved.observedAt))return saved
        if(observed==null || !observed.complete || now-observed.observedAt>30000) {
            val listed=IconWork.bounded(1000,lane="audio-directory:${parent.providerId}") {
                runtime.provider.list(parent)
            } as? ProviderResult.Success ?: return null
            ResourceIconSidecars.observe(listed.value,uri,complete=!listed.isLoading)
            observed=ResourceIconSidecars.directory(uri)
        }
        val observation=observed ?: return null
        val caseSensitive=IconWork.bounded(300,lane="audio-parent:${parent.providerId}") {
            (runtime.provider.fileSystemInfo(parent) as? ProviderResult.Success)?.value?.caseSensitive
        } ?: (parent.providerId!="smb")
        val candidates=DirectoryIconResolver.candidates(observation.candidates,caseSensitive)
            .filter {DirectoryIconResolver.priority(it.metadata.name)==0}
        // Async directories can publish more candidates later. Display any valid cover
        // already available, but don't store a partial listing as a final result.
        return Folder(uri,candidates,observation.observedAt,now+30000,!observation.complete)
            .also {if(!it.pending)folders.put(key,it)}
    }

    companion object {
        private val pendingWrites=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        private val persistence=java.util.concurrent.ThreadPoolExecutor(1,1,30,java.util.concurrent.TimeUnit.SECONDS,
            java.util.concurrent.ArrayBlockingQueue<Runnable>(32),
            java.util.concurrent.ThreadFactory {Thread(it,"AudioArtworkCacheWrite").apply {isDaemon=true}},
            java.util.concurrent.ThreadPoolExecutor.AbortPolicy()).apply {allowCoreThreadTimeOut(true)}
        private fun bitmapCache()=object:LruCache<String,Cached>(8*1024*1024) {
            override fun sizeOf(key:String,value:Cached)=value.bitmap?.allocationByteCount ?: 512
        }
        /** Memory-only dependency for the listing icon cache; new/changed covers invalidate tracks. */
        fun coverRevision(entry:ResourceEntry):String {
            val parent=ResourceIconSidecars.parent(entry) ?: return ""
            val folder=ResourceIconSidecars.directory(parent) ?: return parent.toString()
            return "$parent:"+folder.candidates.filter {DirectoryIconResolver.priority(it.metadata.name)==0}.joinToString {
                "${it.uri}:${it.metadata.sizeBytes}:${it.metadata.modifiedAtMillis ?: folder.observedAt}"
            }
        }
    }
}
