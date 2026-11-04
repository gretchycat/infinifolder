package com.infinifolder.viewer

import android.net.TestUri
import com.infinifolder.audio.model.RawAudioExplorer
import com.infinifolder.model.*
import com.infinifolder.preview.RawImageExplorer
import com.infinifolder.preview.RawImagePalettes
import com.infinifolder.storage.StorageProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The provider reads and writes real temporary files, including guarded content replacement. */
class RawMediaConfigStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private class DiskProvider : StorageProvider {
        override val id = "raw-fixture"
        var reads = 0
        var listings = 0
        var failReplacement = false
        var loading = false
        private fun file(ref: ResourceRef) = File(ref.documentId)
        fun ref(file: File) = ResourceRef(id, file.absolutePath)
        fun entry(file: File) = ResourceEntry(ref(file), ResourceMetadata(file.name,
            if(file.isDirectory) "inode/directory" else "application/octet-stream",
            file.length(),file.lastModified(),file.isDirectory),TestUri(file.toURI().toString()))
        override fun parent(ref: ResourceRef) = ProviderResult.Success(file(ref).parentFile?.let(::ref))
        override fun list(parent: ResourceRef): ProviderResult<List<ResourceEntry>> {
            listings++
            return ProviderResult.Success(checkNotNull(file(parent).listFiles()).sortedBy { it.name }.map(::entry), isLoading=loading)
        }
        override fun stat(ref: ResourceRef): ProviderResult<ResourceEntry> =
            if(file(ref).exists()) ProviderResult.Success(entry(file(ref))) else ProviderResult.Failure("Missing file")
        override fun openRead(ref: ResourceRef): ProviderResult<InputStream> {
            reads++
            return ProviderResult.Success(file(ref).inputStream())
        }
        override fun replaceContent(ref: ResourceRef, data: ByteArray): ProviderResult<Unit> {
            if(failReplacement) return ProviderResult.Failure("Read-only test location")
            val target=file(ref)
            val staged=File.createTempFile("raw-sidecar-", ".tmp",target.parentFile)
            try {
                staged.writeBytes(data)
                Files.move(staged.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE)
            } finally { staged.delete() }
            return ProviderResult.Success(Unit)
        }
        override fun createFile(parent: ResourceRef, name: String, mimeType: String): ProviderResult<ResourceEntry> {
            val target=File(file(parent),name)
            return if(target.createNewFile()) ProviderResult.Success(entry(target)) else ProviderResult.Failure("Already exists")
        }
        override fun delete(ref: ResourceRef): ProviderResult<Unit> =
            if(file(ref).delete()) ProviderResult.Success(Unit) else ProviderResult.Failure("Cannot delete")
        override fun openWrite(ref: ResourceRef): ProviderResult<OutputStream> = error("Native replacement must handle the save")
        override fun createDirectory(parent: ResourceRef,name:String):ProviderResult<ResourceEntry> = error("Unused")
        override fun rename(ref: ResourceRef,name:String):ProviderResult<ResourceEntry> = error("Unused")
        override fun copy(source:ResourceRef,destination:ResourceRef):ProviderResult<ResourceRef> = error("Unused")
        override fun move(source:ResourceRef,destination:ResourceRef):ProviderResult<ResourceRef> = error("Unused")
    }
    private class Fixture(val provider:DiskProvider,val source:File,val recovery:File) {
        val entry get()=provider.entry(source)
        val sidecar get()=File(source.parentFile,".${source.name}.meta")
        fun observe(fresh:Boolean=true,complete:Boolean=true) {
            RawMediaConfigStore.observe(source.parentFile!!.listFiles()!!.map(provider::entry),complete,fresh)
        }
        fun save(record:RawMediaConfigStore.Record) = RawMediaConfigStore.save(provider,entry,record,recovery)
        fun load(discover:Boolean=true)=RawMediaConfigStore.load(provider,entry,discover)
    }
    private fun fixture():Fixture {
        val root=temporary.newFolder()
        val source=File(root,"sample.bin").apply { writeBytes(byteArrayOf(0,1,2,3,4,5)) }
        return Fixture(DiskProvider(),source,File(root,"recovery"))
    }
    private fun image()=RawMediaConfigStore.Record("image",image=RawImageExplorer.Config(width=8,height=6,bits=6,
        palette=RawImagePalettes.Preset.EGA,compression=RawImageExplorer.Compression.LZW,stride=12,
        littleEndian=false,bgr=true,bottomUp=true,alpha=true,filePalette=listOf(0xff112233.toInt(),0xffaabbcc.toInt())))
    private fun audio()=RawMediaConfigStore.Record("audio",audio=RawAudioExplorer.Config(offset=2,sampleRate=48000,channels=2,
        encoding=RawAudioExplorer.Encoding.F32,littleEndian=false,frames=8192,byteLength=4))
    private fun metadata(record:RawMediaConfigStore.Record):JSONObject = JSONObject()
        .put("format","infinifolder.raw-media").put("version",1).put("sourceName","sample.bin").put("kind",record.kind)
        .also { json-> record.image?.let{json.put("image",RawMediaConfigStore.imageJson(it))};record.audio?.let{json.put("audio",RawMediaConfigStore.audioJson(it))} }

    @Test fun savesExactHiddenSiblingAndReloadsAllImageFieldsFromDisk(){
        val f=fixture();val original=f.source.readBytes();val record=image()
        f.save(record)
        assertTrue(f.sidecar.isFile)
        assertArrayEquals(original,f.source.readBytes())
        assertEquals("sample.bin",JSONObject(f.sidecar.readText()).getString("sourceName"))
        // Force a fresh metadata stamp so load actually parses the written file, bypassing the saved record.
        f.sidecar.setLastModified(f.sidecar.lastModified()+2000);f.observe()
        assertEquals(record,f.load());assertEquals(1,f.provider.reads)
    }
    @Test fun audioMetadataRoundTripsActualFileAndPreservesUnknownFields(){
        val f=fixture()
        f.sidecar.writeText(metadata(image()).put("userNotes","keep me").put("future",JSONObject().put("x",7)).toString())
        f.save(audio())
        val json=JSONObject(f.sidecar.readText())
        assertEquals("keep me",json.getString("userNotes"));assertEquals(7,json.getJSONObject("future").getInt("x"))
        assertEquals("audio",json.getString("kind"))
        assertEquals(audio().audio,RawMediaConfigStore.audio(json.getJSONObject("audio")))
        f.sidecar.setLastModified(f.sidecar.lastModified()+2000);f.observe();assertEquals(audio(),f.load())
    }
    @Test fun staleListingCannotEraseNewlySavedMetadataButFreshRemovalDoes(){
        val f=fixture();val old=listOf(f.entry)
        RawMediaConfigStore.observe(old)
        assertFalse(RawMediaConfigStore.mightHave(f.entry))
        val before=RawMediaConfigStore.revision(f.entry)
        f.save(image())
        val saved=RawMediaConfigStore.revision(f.entry)
        assertNotEquals(before,saved)
        RawMediaConfigStore.observe(old,fresh=false)
        assertEquals(saved,RawMediaConfigStore.revision(f.entry));assertEquals(image(),f.load())
        assertTrue(f.sidecar.delete());f.observe()
        assertFalse(RawMediaConfigStore.mightHave(f.entry));assertNull(f.load())
        assertNotEquals(saved,RawMediaConfigStore.revision(f.entry))
    }
    @Test fun metadataChangesInvalidateParsedRecordAndAbsenceAvoidsRepeatedStorageReads(){
        val f=fixture();f.observe();assertNull(f.load());assertNull(f.load())
        assertEquals(0,f.provider.reads);assertEquals(0,f.provider.listings)
        f.sidecar.writeText(metadata(image()).toString());f.observe();assertEquals(image(),f.load())
        val revision=RawMediaConfigStore.revision(f.entry)
        f.sidecar.writeText(metadata(audio()).toString());f.sidecar.setLastModified(f.sidecar.lastModified()+2000)
        f.observe();assertNotEquals(revision,RawMediaConfigStore.revision(f.entry));assertEquals(audio(),f.load())
        assertEquals(2,f.provider.reads)
    }
    @Test fun incompleteListingDoesNotCacheMissingSidecar(){
        val f=fixture();f.provider.loading=true
        assertNull(f.load());assertTrue(RawMediaConfigStore.mightHave(f.entry))
        f.sidecar.writeText(metadata(image()).toString());f.provider.loading=false
        assertEquals(image(),f.load());assertEquals(2,f.provider.listings)
    }
    @Test fun refusesToOverwriteUnownedOrMalformedMetadata(){
        for(text in listOf("{\"userMetadata\":true}","not json",metadata(image()).put("sourceName","other.bin").toString())){
            val f=fixture();f.sidecar.writeText(text)
            assertThrows(Exception::class.java){f.save(image())}
            assertEquals(text,f.sidecar.readText())
            f.observe();assertNull(f.load())
        }
    }
    @Test fun failedNewSaveRemovesEmptySidecarAndFailedReplacementPreservesExistingFile(){
        val f=fixture();f.provider.failReplacement=true
        assertThrows(IOException::class.java){f.save(image())};assertFalse(f.sidecar.exists())
        val original=metadata(image()).toString();f.sidecar.writeText(original)
        assertThrows(IOException::class.java){f.save(audio())};assertEquals(original,f.sidecar.readText())
        assertArrayEquals(byteArrayOf(0,1,2,3,4,5),f.source.readBytes())
    }
    @Test fun oversizedMetadataAndUnsupportedVersionHaveSafeFallback(){
        val f=fixture();f.sidecar.writeText(metadata(image()).put("notes","x".repeat(33000)).toString());f.observe()
        assertNull(f.load());assertThrows(IllegalArgumentException::class.java){f.save(image())}
        f.sidecar.writeText(metadata(image()).put("version",2).toString());f.sidecar.setLastModified(f.sidecar.lastModified()+2000);f.observe()
        assertNull(f.load());assertThrows(IllegalArgumentException::class.java){f.save(image())}
    }
    @Test fun phaseAndDepthChoicesSurviveJsonAndInvalidRangesAreRejected(){
        for(phase in RawImagePalettes.Preset.entries){
            val c=RawImageExplorer.Config(bits=6,palette=phase)
            assertEquals(c,RawMediaConfigStore.image(RawMediaConfigStore.imageJson(c)))
        }
        assertThrows(IllegalArgumentException::class.java){RawMediaConfigStore.image(JSONObject().put("width",4096).put("height",4096).put("bits",8))}
        assertThrows(IllegalArgumentException::class.java){RawAudioExplorer.Config(offset=Long.MAX_VALUE,byteLength=1).validate()}
    }
    @Test fun savedConfigRendersActualSourceBytesAndReflectsBufferedEdits(){
        val f=fixture()
        val config=RawImageExplorer.Config(width=3,height=2,bits=8)
        f.save(RawMediaConfigStore.Record("image",image=config))
        f.sidecar.setLastModified(f.sidecar.lastModified()+2000);f.observe()
        val loaded=checkNotNull(f.load()?.image)
        java.io.RandomAccessFile(f.source,"r").use{input->
            val reader=object:io.github.gretchycat.infinistream.PositionalReadable {
                override fun readAt(position:Long,buffer:ByteArray,offset:Int,length:Int):Int {
                    input.seek(position);return input.read(buffer,offset,length)
                }
            }
            val document=io.github.gretchycat.infiniscribe.HexDocument(reader,f.source.length())
            val raster=RawMediaRendering.raster(document,loaded)
            assertEquals(3,raster.width);assertEquals(2,raster.height)
            assertArrayEquals(IntArray(6){0xff000000.toInt() or (it*0x010101)},raster.pixels)
            document.overwrite(4,byteArrayOf(127))
            assertEquals(0xff7f7f7f.toInt(),RawMediaRendering.raster(document,loaded).pixels[4])
            assertEquals(4,f.source.readBytes()[4].toInt()) // Preview edits are buffered, never silently saved.
            assertEquals(2,RawMediaRendering.raster(document,loaded,target=2).width)
        }
    }
    @Test fun sequentialThumbnailReadsOnlyRequestedWindowAndBoundsCostlyOffsets(){
        val f=fixture()
        java.io.RandomAccessFile(f.source,"rw").use{it.setLength(100L*1024*1024)}
        f.source.inputStream().use{input->
            val source=io.github.gretchycat.infinistream.InputStreamSource(input,ownsInput=false)
            val raster=RawMediaRendering.raster(source,RawImageExplorer.Config(offset=2,width=2,height=2,bits=8),2)
            assertArrayEquals(intArrayOf(0xff020202.toInt(),0xff030303.toInt(),0xff040404.toInt(),0xff050505.toInt()),raster.pixels)
            assertEquals(6L,input.channel.position())
            assertThrows(IllegalArgumentException::class.java){RawMediaRendering.raster(source,
                RawImageExplorer.Config(offset=RawImageExplorer.MAX_BYTES+1L,width=2,height=2),2)}
            assertEquals(6L,input.channel.position())
        }
    }
    @Test fun smoothScalingPreferenceSurvivesMetadataSaveAndReopen(){
        val f=fixture();val record=image().copy(smoothScaling=true)
        f.save(record)
        assertTrue(JSONObject(f.sidecar.readText()).getBoolean("smoothScaling"))
        f.sidecar.setLastModified(f.sidecar.lastModified()+2000);f.observe()
        assertEquals(record,f.load())
        f.save(record.copy(smoothScaling=false))
        assertFalse(JSONObject(f.sidecar.readText()).getBoolean("smoothScaling"))
    }
}
