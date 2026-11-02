package com.infinifolder

import android.content.Context
import android.util.LruCache
import com.infinifolder.archive.ArchiveFormats
import com.infinifolder.archive.ArchiveUri
import com.infinifolder.model.*
import com.infinifolder.storage.*

/** Real container listing proves Browse archive works; this does not choose a MIME identity. */
class ArchiveIconCapabilities(context: Context) {
    private val runtime = StorageRuntime.get(context.applicationContext)
    data class Result(val browsable: Boolean, val magicMime: String? = null)
    private data class Cached(val result: Result, val time: Long)
    private val cache = LruCache<String, Cached>(256)
    fun inspect(entry: ResourceEntry): Result {
        if(entry.metadata.isDirectory) return Result(false)
        val key = "${entry.uri}:${entry.metadata.name}:${entry.metadata.sizeBytes}:${entry.metadata.modifiedAtMillis}"
        cache.get(key)?.takeIf { System.currentTimeMillis()-it.time < 30000 }?.let { return it.result }
        val result = IconWork.bounded(2500,lane="archive:${entry.ref.providerId}") {
            val magic = FileMagicDetector.detectFromStream {
                (runtime.provider.openRead(entry.ref) as? ProviderResult.Success)?.value ?: error("Source inaccessible")
            }
            val candidate = ArchiveFormats.isArchive(entry.metadata.name, entry.metadata.mimeType) ||
                magic?.isArchiveOrDiscImage == true || (magic != null && ArchiveFormats.isArchive("", magic.mimeType))
            val browsable = if(candidate) {
                val uri = ArchiveUri.toArchiveUri(entry.uri)
                val ref = runtime.archives.refForUri(uri)
                ref != null && (runtime.archives.list(ref) as? ProviderResult.Success)?.let { catalog ->
                    val positiveMagic=magic?.isArchiveOrDiscImage==true || (magic!=null && ArchiveFormats.isArchive("",magic.mimeType))
                    !catalog.isLoading && (catalog.value.isNotEmpty() || positiveMagic || ArchiveFormats.isPlaylist(entry.metadata.name) ||
                        (magic==null && (entry.metadata.sizeBytes ?: 0)>=512))
                } == true
            } else false
            Result(browsable, magic?.mimeType)
        } ?: Result(false)
        cache.put(key, Cached(result, System.currentTimeMillis()))
        return result
    }
    fun clear() = cache.evictAll()
}
