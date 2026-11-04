package com.infinifolder.viewer

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.infinifolder.model.ResourceEntry
import com.infinifolder.mime.MimeRegistry

/** Common document launch contract; compatibility extras keep existing consumers interoperable. */
object ViewerLaunch {
    fun intent(context: Context, entry: ResourceEntry, type: MediaType, parent: Uri? = null, edit: Boolean = false): Intent? {
        val activity = ViewerComponents.resolve(entry,edit,type)?.activityClassName ?: ViewerCatalog.activity(type)
            ?: ViewerComponents.resolve(entry,edit)?.activityClassName ?: return null
        return create(context,entry,activity,parent,edit)
    }
    fun intentForResource(context:Context,entry:ResourceEntry,parent:Uri?=null,edit:Boolean=false):Intent? {
        val component=ViewerComponents.resolve(entry,edit)?:return null
        return create(context,entry,component.activityClassName,parent,edit)
    }
    fun intentForKind(context:Context,entry:ResourceEntry,kind:String,edit:Boolean=false):Intent? {
        val component=ViewerComponents.registry.query(if(edit)io.github.gretchycat.infinistream.Operations.EDIT else io.github.gretchycat.infinistream.Operations.VIEW)
            .firstOrNull {it.descriptor.attributes["viewerKind"]==kind}?.handler?.service(ViewerComponent::class.java)?:return null
        return create(context,entry,component.activityClassName,null,edit)
    }
    private fun create(context:Context,entry:ResourceEntry,activity:String,parent:Uri?,edit:Boolean):Intent {
        return Intent(if (edit) Intent.ACTION_EDIT else Intent.ACTION_VIEW).setClassName(context.packageName, activity).apply {
            setDataAndType(entry.uri, MimeRegistry.effective(entry.metadata.name, entry.metadata.mimeType))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra("uri", entry.uri.toString()); putExtra("extra_uri", entry.uri.toString())
            putExtra("displayName", entry.metadata.name); putExtra("extra_name", entry.metadata.name)
            putExtra("mimeType", this.type); putExtra("extra_mime", this.type)
            putExtra("edit", edit)
            parent?.let { putExtra("parentUri", it.toString()); putExtra("extra_parent_uri", it.toString()) }
            clipData = android.content.ClipData.newRawUri(entry.metadata.name, entry.uri)
        }
    }
    fun requestsEdit(intent: Intent) = intent.action == Intent.ACTION_EDIT || intent.getBooleanExtra("edit", false)
}
