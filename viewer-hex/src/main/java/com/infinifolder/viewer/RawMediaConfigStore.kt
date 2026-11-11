package com.infinifolder.viewer

import android.content.Context
import android.graphics.Bitmap
import com.infinifolder.audio.model.RawAudioExplorer
import com.infinifolder.model.*
import com.infinifolder.preview.RawImageExplorer
import com.infinifolder.preview.RawImagePalettes
import com.infinifolder.storage.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Exact sibling-name lookup through the storage provider. Cached absence avoids per-draw I/O. */
object RawMediaConfigStore {
    data class Record(val kind: String, val image: RawImageExplorer.Config? = null, val audio: RawAudioExplorer.Config? = null,
        val smoothScaling:Boolean=false)
    private val serial=java.util.concurrent.atomic.AtomicLong()
    private class State(val sidecar: ResourceEntry?, var parsed: Boolean = false, var record: Record? = null,
        val serial:Long=RawMediaConfigStore.serial.incrementAndGet(),val locallyWritten:Boolean=false)
    private val index = object:LinkedHashMap<String,State>(128,.75f,true){
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,State>?)=size>8192
    }
    @Volatile var savedRevision=0L
        private set
    private fun name(entry: ResourceEntry) = ".${entry.metadata.name}.meta"
    private fun stamp(entry: ResourceEntry?) = entry?.let { "${it.uri}:${it.metadata.sizeBytes}:${it.metadata.modifiedAtMillis}" } ?: "none"
    @Synchronized fun observe(entries: List<ResourceEntry>, complete: Boolean = true, fresh:Boolean=true) {
        val names = entries.associateBy { it.metadata.name }
        entries.filterNot { it.metadata.isDirectory }.forEach { entry ->
            val sidecar = names[name(entry)]?.takeUnless { it.metadata.isDirectory }
            if (sidecar != null || complete) {
                val old = index.get(entry.uri.toString())
                if(old?.locallyWritten==true&&!fresh)return@forEach
                if (old == null || stamp(old.sidecar) != stamp(sidecar)) index.put(entry.uri.toString(), State(sidecar))
            }
        }
    }
    @Synchronized fun revision(entry: ResourceEntry) = index.get(entry.uri.toString())?.let {
        if(it.sidecar==null) "none" else "${stamp(it.sidecar)}:${it.serial}"
    } ?: "unknown"
    @Synchronized fun mightHave(entry: ResourceEntry) = index.get(entry.uri.toString())?.let { it.sidecar != null } ?: true
    /** Presence selects the byte explorer even if metadata cannot be parsed; only valid records enable PIC/WAVE. */
    fun hasSidecar(context:Context,entry:ResourceEntry):Boolean =
        state(ViewerStorage.get(context).provider,entry,discover=true)?.sidecar!=null
    fun load(context: Context, entry: ResourceEntry, discover:Boolean=true): Record? = load(ViewerStorage.get(context).provider,entry,discover)
    private fun state(provider:StorageProvider,entry:ResourceEntry,discover:Boolean):State? {
        if (entry.metadata.isDirectory) return null
        var state = synchronized(this) { index.get(entry.uri.toString()) }
        if (state == null) {
            if(!discover)return null
            val parent = (provider.parent(entry.ref) as? ProviderResult.Success)?.value ?: return null
            val result = provider.list(parent)
            if (result is ProviderResult.Success) observe(result.value, !result.isLoading)
            state = synchronized(this) { index.get(entry.uri.toString()) } ?: return null
        }
        return state
    }
    fun load(provider:StorageProvider, entry:ResourceEntry, discover:Boolean=true):Record? {
        val state=state(provider,entry,discover)?:return null
        val sidecar=state.sidecar?:return null
        synchronized(state) {
            if (state.parsed) return state.record
            state.record = runCatching { parse(read(provider,sidecar), entry.metadata.name) }.getOrNull()
            state.parsed = true
            return state.record
        }
    }
    fun load(context: Context, uri: android.net.Uri, name: String): Record? {
        val provider = ViewerStorage.get(context).provider
        val ref = provider.refForUri(uri) ?: return null
        val entry = (provider.stat(ref) as? ProviderResult.Success)?.value ?: return null
        return load(context, entry)
    }
    private fun read(provider: StorageProvider, entry: ResourceEntry)=JSONObject(String(readBytes(provider,entry),Charsets.UTF_8))
    private fun readBytes(provider: StorageProvider, entry: ResourceEntry):ByteArray {
        val input = required(provider.openRead(entry.ref))
        val bytes = input.use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(4096)
            while (true) {
                val n = stream.read(chunk)
                if (n < 0) break
                if (n == 0) throw IOException("Metadata stream made no progress")
                require(output.size() + n <= 32768) { "Raw media metadata exceeds 32 KiB" }
                output.write(chunk, 0, n)
            }
            output.toByteArray()
        }
        return bytes
    }
    fun imageJson(c: RawImageExplorer.Config): JSONObject = JSONObject().put("offset",c.offset).put("width",c.width).put("height",c.height)
        .put("bits",c.bits).put("palette",c.palette.name).put("compression",c.compression.name).put("stride",c.stride)
        .put("littleEndian",c.littleEndian).put("bgr",c.bgr).put("bottomUp",c.bottomUp).put("alpha",c.alpha)
        .also { if(c.filePalette!=null)it.put("filePalette",JSONArray(c.filePalette)) }
    fun image(c: JSONObject) = RawImageExplorer.Config(c.optLong("offset",0),c.getInt("width"),c.getInt("height"),c.getInt("bits"),
        RawImagePalettes.Preset.valueOf(c.optString("palette","MONO")), RawImageExplorer.Compression.valueOf(c.optString("compression","RAW")),
        c.optInt("stride",0),c.optBoolean("littleEndian",true),c.optBoolean("bgr",false),c.optBoolean("bottomUp",false),c.optBoolean("alpha",false),
        c.optJSONArray("filePalette")?.let { values -> require(values.length() in 1..256); List(values.length()) { values.getInt(it) } }).also { it.validate() }
    fun audioJson(c: RawAudioExplorer.Config): JSONObject = JSONObject().put("offset",c.offset).put("sampleRate",c.sampleRate)
        .put("channels",c.channels).put("encoding",c.encoding.name).put("littleEndian",c.littleEndian).put("frames",c.frames)
        .also { if(c.byteLength!=null)it.put("byteLength",c.byteLength) }
    fun audio(c: JSONObject) = RawAudioExplorer.Config(c.optLong("offset",0),c.getInt("sampleRate"),c.getInt("channels"),
        RawAudioExplorer.Encoding.valueOf(c.getString("encoding")),c.optBoolean("littleEndian",true),c.optInt("frames",16384),
        if(c.has("byteLength"))c.getLong("byteLength") else null).also { it.validate() }
    private fun parse(json: JSONObject, source: String): Record {
        require(json.getString("format") == "infinifolder.raw-media" && json.getInt("version") == 1 && json.getString("sourceName") == source) { "Not raw media metadata for this file" }
        return when(json.getString("kind")) {
            "image" -> Record("image", image=image(json.getJSONObject("image")),smoothScaling=json.optBoolean("smoothScaling",false))
            "audio" -> Record("audio", audio=audio(json.getJSONObject("audio")))
            else -> throw IOException("Unsupported raw media metadata kind")
        }
    }
    fun save(context: Context, source: ResourceEntry, record: Record) {
        save(ViewerStorage.get(context).provider,source,record,File(context.filesDir,"save_recovery"))
        context.getSharedPreferences("browser",Context.MODE_PRIVATE).edit().putLong("raw_media_metadata_revision",savedRevision).apply()
    }
    fun save(provider:StorageProvider,source:ResourceEntry,record:Record,recoveryRoot:File){
        require(!source.metadata.isDirectory) { "Raw media metadata requires a file" }
        record.image?.validate(); record.audio?.validate()
        require(record.kind == "image" && record.image != null || record.kind == "audio" && record.audio != null)
        val parent = required(provider.parent(source.ref)) ?: throw IOException("This location has no writable parent")
        val entries = StorageSafety.complete(provider.list(parent))
        val existing = entries.firstOrNull { it.metadata.name == name(source) }
        val previousBytes=existing?.let{readBytes(provider,it)}
        val previous=previousBytes?.let{JSONObject(String(it,Charsets.UTF_8)).also{json->parse(json,source.metadata.name)}}
        val json = previous ?: JSONObject()
        json.put("format","infinifolder.raw-media").put("version",1).put("sourceName",source.metadata.name).put("kind",record.kind)
        record.image?.let { json.put("image",imageJson(it)) }; record.audio?.let { json.put("audio",audioJson(it)) }
        if(record.kind=="image")json.put("smoothScaling",record.smoothScaling)
        val bytes = json.toString(2).toByteArray(Charsets.UTF_8)
        require(bytes.size<=32768) { "Raw media metadata exceeds 32 KiB" }
        val target = existing ?: required(provider.createFile(parent,name(source),"application/json"))
        try {
            require(target.metadata.name == name(source)) { "Storage changed the metadata filename" }
            val expected = previousBytes?.let(ContentRevision::of)
            required(ContentReplacement.replace(provider,target.ref,bytes,recoveryRoot,expected))
            val refreshed = (provider.stat(target.ref) as? ProviderResult.Success)?.value ?: target.copy(metadata=target.metadata.copy(sizeBytes=bytes.size.toLong(),modifiedAtMillis=System.currentTimeMillis()))
            synchronized(this) { index.put(source.uri.toString(),State(refreshed,true,record,locallyWritten=true));savedRevision=serial.incrementAndGet() }
        } catch(error:Exception) { if(existing==null)provider.delete(target.ref); throw error }
    }
    private fun <T> required(result: ProviderResult<T>): T = when(result) {
        is ProviderResult.Success -> result.value
        is ProviderResult.Failure -> throw IOException(result.message,result.cause)
        is ProviderResult.Unsupported -> throw IOException("Storage does not support ${result.capability}")
    }
}

object RawMediaRendering {
    fun raster(document: io.github.gretchycat.infiniscribe.HexDocument, config: RawImageExplorer.Config, target: Int = 0): RawImageExplorer.Raster {
        return raster(document,config,target,thumbnail=false)
    }
    private fun raster(document:io.github.gretchycat.infiniscribe.HexDocument,config:RawImageExplorer.Config,target:Int,
        thumbnail:Boolean):RawImageExplorer.Raster {
        config.validate()
        require(config.offset <= document.length) { "Image offset is outside the file" }
        val count = if(config.compression==RawImageExplorer.Compression.RAW)config.byteCount else minOf(RawImageExplorer.MAX_BYTES.toLong(),document.length-config.offset)
        require(count <= document.length-config.offset) { "Not enough bytes for this image" }
        val bytes=document.read(config.offset,count.toInt())
        return if(thumbnail)RawImageExplorer.decodeThumbnail(bytes,config,target)else RawImageExplorer.decode(bytes,config,target)
    }
    fun image(document: io.github.gretchycat.infiniscribe.HexDocument, config: RawImageExplorer.Config, target: Int = 0): Bitmap {
        val raster = raster(document,config,target)
        return Bitmap.createBitmap(raster.pixels,raster.width,raster.height,Bitmap.Config.ARGB_8888)
    }
    /** Borrowed thumbnail input: never stage/download an entire file just to show its first image. */
    fun raster(source:io.github.gretchycat.infinistream.ByteSource,config:RawImageExplorer.Config,target:Int):RawImageExplorer.Raster {
        return raster(source,config,target,thumbnail=false)
    }
    private fun raster(source:io.github.gretchycat.infinistream.ByteSource,config:RawImageExplorer.Config,target:Int,
        thumbnail:Boolean):RawImageExplorer.Raster {
        config.validate()
        val length=source.metadata.length
        if(source is io.github.gretchycat.infinistream.PositionalReadable && length!=null)
            return raster(io.github.gretchycat.infiniscribe.HexDocument(source,length),config,target,thumbnail)
        // A sequential provider cannot cheaply reach arbitrary offsets in a large backing file.
        require(config.offset<=RawImageExplorer.MAX_BYTES) { "Thumbnail offset requires random access" }
        val scratch=ByteArray(8192)
        var remaining=config.offset
        fun read(buffer:ByteArray,offset:Int,count:Int):Int {
            io.github.gretchycat.infinistream.CancellationToken.NONE.check()
            val n=source.read(buffer,offset,count)
            require(n==-1 || n in 1..count) { "Image stream made no progress" }
            return n
        }
        while(remaining>0){val n=read(scratch,0,minOf(remaining,scratch.size.toLong()).toInt());require(n>0){"Image offset is outside the file"};remaining-=n}
        val count=if(config.compression==RawImageExplorer.Compression.RAW)config.byteCount.toInt() else RawImageExplorer.MAX_BYTES
        val bytes=ByteArray(count);var at=0
        while(at<count){val n=read(bytes,at,minOf(65536,count-at));if(n<0)break;at+=n}
        val data=if(at==count)bytes else bytes.copyOf(at)
        return if(thumbnail)RawImageExplorer.decodeThumbnail(data,config,target)else RawImageExplorer.decode(data,config,target)
    }
    fun thumbnail(context: Context, entry: ResourceEntry, record: RawMediaConfigStore.Record, size: Int): Bitmap? {
        val config = record.image ?: return null
        val source=MediaSourceAdapter.openStreamingPositionalSource(context,entry.uri) ?: MediaSourceAdapter.openSource(context,entry.uri)
        val raster=source.use {raster(it,config,size,thumbnail=true)}
        val bitmap=Bitmap.createBitmap(raster.pixels,raster.width,raster.height,Bitmap.Config.ARGB_8888)
        if(raster.width!=raster.height || raster.width==size)return bitmap
        // Upscale small squares here so native FIT_CENTER consumers share the same filtering.
        return Bitmap.createScaledBitmap(bitmap,size,size,record.smoothScaling).also {if(it!==bitmap)bitmap.recycle()}
    }
}
