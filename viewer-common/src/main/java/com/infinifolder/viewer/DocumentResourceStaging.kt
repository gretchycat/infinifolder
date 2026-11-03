package com.infinifolder.viewer

import android.content.Context
import android.net.Uri
import com.infinifolder.archive.VaultZipExtraction
import com.infinifolder.storage.DiskCache
import io.github.gretchycat.infiniscribe.*
import io.github.gretchycat.infinistream.*
import java.io.File
import java.io.IOException

/** Host lease adapter shared by the reflow reader and workbook viewer. Vault
 * owns archive expansion; Scribe receives borrowed resource handles/output. */
class DocumentResourceStaging private constructor(val source:MediaSourceAdapter.StagedSource,
    val lease:DiskCache.Lease,val directory:File,val resources:DocumentResources):AutoCloseable {
    val renderDirectory=File(directory,"render-${java.util.UUID.randomUUID()}").apply {check(mkdirs())}
    val output=DocumentOutput {path ->
        val parts=path.split('/')
        require(path.isNotBlank() && !path.startsWith('/') && !path.contains('\\') && !path.contains('\u0000') && parts.none {it==".." || it=="."}) {"Unsafe document resource path"}
        val target=File(renderDirectory,path)
        require(target.canonicalFile.toPath().startsWith(renderDirectory.canonicalFile.toPath()))
        OutputStreamSink(lease.output(target))
    }
    fun complete()=lease.complete()
    override fun close(){try{lease.close()}finally{source.close()}}
    companion object {
        fun open(context:Context,uri:Uri,name:String,mime:String?,expand:Boolean=false,flatXml:Boolean=false,
            cancellation:CancellationToken=CancellationToken.NONE):DocumentResourceStaging {
            val source=MediaSourceAdapter.stage(context,uri,name)
            var lease:DiskCache.Lease?=null
            try {
                cancellation.check()
                if(source.file.length()>64L*1024*1024)throw IOException("Document exceeds the 64 MiB reader limit")
                lease=DiskCache.get(context).allocate("document-resources")
                val directory=lease.file
                if(!directory.mkdirs() && !directory.isDirectory)throw IOException("Cannot prepare document resources")
                val metadata=StreamMetadata(name=name,content=ContentType(mimeType=mime),provenance=Provenance(source=uri.toString()))
                if(expand)VaultZipExtraction.extract(source.file,directory,{lease.output(it)})
                cancellation.check()
                val resources=if(flatXml)object:DocumentResources {
                    override val metadata=metadata
                    override fun source(path:String)=if(path=="content.xml")FileSourceResource(source.file) else null
                    override fun paths()=sequenceOf("content.xml")
                    override fun close()=Unit
                } else DirectoryDocumentResources(directory,metadata)
                return DocumentResourceStaging(source,lease,directory,resources)
            }catch(error:Throwable){lease?.close();source.close();throw error}
        }
    }
}
