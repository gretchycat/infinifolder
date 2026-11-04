package com.infinifolder.viewer

import com.infinifolder.model.ResourceEntry
import io.github.gretchycat.infinistream.*

/** Android presentation extension; parsing/decoding stays in the indicated library services. */
typealias ViewerComponent = io.github.gretchycat.infinistream.ViewerComponent

open class LibraryViewerComponent(
    id:String, override val activityClassName:String, private val contentLibrary:String,
    private val presentations:Set<String>, mediaTypes:Set<String>, private val editable:Boolean=false,
    private val directFormats:List<FormatSupport> = emptyList(), priority:Int=0
) : ViewerComponent {
    override val descriptor=HandlerDescriptor("viewer.$id","viewers",id,emptyList(),
        if(editable)setOf(Operations.VIEW,Operations.EDIT)else setOf(Operations.VIEW),
        limitations=listOf("Source permissions, completeness and the selected library handler's limits apply"),priority=priority,
        attributes=mapOf("activity" to activityClassName,"mediaTypes" to mediaTypes.joinToString(","),"formatLibrary" to contentLibrary,"viewerKind" to id))
    private fun content()=RuntimeCapabilities.library(contentLibrary)
    @Volatile private var resolvedDescription:Pair<Long,HandlerDescriptor>?=null
    override fun describe():HandlerDescriptor {
        val registry=content()
        resolvedDescription?.takeIf {it.first==registry.revision}?.let {return it.second}
        val result=descriptor.copy(formats=directFormats+registry.descriptors()
            .filter {it.attributes["presentation"] in presentations && it.operations.any {op ->op in setOf(Operations.DECODE,Operations.VIEW,Operations.PLAY)}}.flatMap {it.formats})
        resolvedDescription=registry.revision to result
        return result
    }
    override fun availability():HandlerAvailability {
        if(android.os.Build.VERSION.SDK_INT<26)return HandlerAvailability(AvailabilityState.UNAVAILABLE,"Android Activity runtime unavailable")
        Class.forName(activityClassName,false,javaClass.classLoader)
        return HandlerAvailability(AvailabilityState.CONDITIONAL,"Viewer installed; matching library handler and resource constraints must be satisfied")
    }
    override fun probe(context:CapabilityProbeContext):HandlerProbe {
        if(context.resource.source==null) {
            val matches=content().descriptors().filter {it.attributes["presentation"] in presentations &&
                it.operations.any {op ->op in setOf(Operations.DECODE,Operations.VIEW,Operations.PLAY)} && it.formats.any {format->format.score(context.resource)>0}}
            if(matches.isEmpty() && directFormats.none {it.score(context.resource)>0})return HandlerProbe(MatchConfidence.NONE,emptySet())
            val canEdit=editable&&(presentations.any{it in setOf("text","html","markdown","image","hex")} || matches.any{it.id=="scribe.table" && Operations.EDIT in it.operations})
            return HandlerProbe(MatchConfidence.CANDIDATE,if(canEdit)descriptor.operations else descriptor.operations-Operations.EDIT,
                "Metadata-only presentation match; actual backend/source is validated by the viewer")
        }

        val delegated=content().probe(context)
            .filter { it.descriptor.attributes["presentation"] in presentations && it.operations.any {op ->op in setOf(Operations.DECODE,Operations.VIEW,Operations.PLAY)} }
        val direct=directFormats.any { it.score(context.resource)>0 }
        val best=delegated.maxByOrNull { it.probe?.confidence?.ordinal?:0 }
        if(best==null&&!direct)return HandlerProbe(MatchConfidence.NONE,emptySet())
        val canEdit=editable && (presentations.any {it in setOf("text","html","markdown","image","hex")} || delegated.any { it.descriptor.id=="scribe.table" && Operations.EDIT in it.operations })
        return HandlerProbe(best?.probe?.confidence?:MatchConfidence.CANDIDATE,if(canEdit)descriptor.operations else descriptor.operations-Operations.EDIT,
            "Viewer delegates to a registered library handler",mapOf("contentHandler" to (best?.descriptor?.id?:"direct presentation")))
    }
}

object ViewerComponents {
    val registry get()=RuntimeCapabilities.library("viewers")
    fun resource(entry:ResourceEntry)=CapabilityResource(StreamMetadata(name=entry.metadata.name,
        content=ContentType(mimeType=com.infinifolder.mime.MimeRegistry.effective(entry.metadata.name,entry.metadata.mimeType))),entry.uri.toString())
    private data class ResolutionKey(val metadata:StreamMetadata,val uri:String?,val edit:Boolean,val mediaType:MediaType?)
    private data class Resolution(val revision:Long,val component:ViewerComponent?)
    private val resolved=object:LinkedHashMap<ResolutionKey,Resolution>(64,.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<ResolutionKey,Resolution>?)=size>512
    }
    fun resolve(resource:CapabilityResource,edit:Boolean=false,mediaType:MediaType?=null):ViewerComponent? {
        val key=ResolutionKey(resource.metadata,resource.uri,edit,mediaType)
        if(resource.source==null)synchronized(resolved) {resolved[key]?.takeIf {it.revision==registry.revision}?.let {return it.component}}
        val result=registry.probe(resource,if(edit)Operations.EDIT else Operations.VIEW)
            .filter {mediaType==null || mediaType.name in it.descriptor.attributes["mediaTypes"].orEmpty().split(',')}
            .firstNotNullOfOrNull {it.handler.service(ViewerComponent::class.java)}
        if(resource.source==null)synchronized(resolved){resolved[key]=Resolution(registry.revision,result)}
        return result
    }
    fun resolve(entry:ResourceEntry,edit:Boolean=false,mediaType:MediaType?=null)=resolve(resource(entry),edit,mediaType)
    fun canView(entry:ResourceEntry)=!entry.metadata.isDirectory&&resolve(entry)!=null
}
