package com.infinifolder.preview

import android.content.ContentResolver
import android.graphics.Bitmap
import io.github.gretchycat.infinistream.*

/** Optional cross-library extractors feed encoded images directly to Canvas. Presentation policy stays outside. */
object LibraryThumbnails {
    fun render(resolver:ContentResolver,source:SourceResource,targetPixels:Int):Bitmap? {
        val registry=RuntimeCapabilities.installed()
        val ids=registry.descriptors().filter {Operations.THUMBNAIL in it.operations && it.attributes["encodedImageExtractor"]=="true"}.map{it.id}.toSet()
        if(ids.isEmpty())return null
        val context=CapabilityProbeContext(CapabilityResource(source.metadata,source=source),
            ProbeBudget(maxNanos=2_000_000_000),CancellationToken{Thread.currentThread().isInterrupted})
        for(entry in registry.query(Operations.THUMBNAIL).filter{it.descriptor.id in ids}) {
            val extractor=entry.handler.service(EncodedImageExtractor::class.java)?:continue
            try {
                if(extractor.probe(context).confidence==MatchConfidence.NONE)continue
                source.open().use {input->extractor.extract(input,targetPixels,context.cancellation)?.use {image->
                    CanvasStreams.decode(resolver,image.resource,targetPixels)?.use {frame->return frame.takeBitmap()}
                }}
            }catch(cancel:java.io.InterruptedIOException){throw cancel}catch(_:Exception){}catch(_:LinkageError){}
        }
        return null
    }
}
