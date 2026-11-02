package com.infinifolder

import android.content.SharedPreferences
import android.net.Uri
import com.infinifolder.model.ResourceEntry
import com.infinifolder.model.ResourceMetadata
import com.infinifolder.model.ResourceRef
import com.infinifolder.storage.DiagnosticLog
import org.json.JSONArray
import org.json.JSONObject

internal data class PinnedContainer(val entry:ResourceEntry,val destination:Uri)

/** UI bookmarks only: restoring a pin neither opens its target nor grants storage access. */
internal class PinnedContainerPreferences(private val prefs:SharedPreferences) {
    fun load():List<PinnedContainer> {
        val array=runCatching {JSONArray(prefs.getString(KEY,"[]") ?: "[]")}
            .getOrElse {DiagnosticLog.record("browser.pins.restore-failed",error=it);return emptyList()}
        return (0 until array.length()).mapNotNull {index->
            runCatching {
                val json=array.getJSONObject(index)
                fun required(key:String)=json.getString(key).also {require(it.isNotBlank())}
                val uri=Uri.parse(required("uri"));val destination=Uri.parse(required("destination"))
                require(!uri.scheme.isNullOrBlank() && !destination.scheme.isNullOrBlank())
                val ref=ResourceRef(required("provider"),required("documentId"))
                val metadata=ResourceMetadata(required("name"),json.optString("mimeType","application/octet-stream"),
                    if(json.isNull("size"))null else json.optLong("size").takeIf {it>=0},
                    if(json.isNull("modified"))null else json.optLong("modified"),json.optBoolean("directory"),
                    canRead=json.optBoolean("canRead",true),canWrite=json.optBoolean("canWrite",true))
                PinnedContainer(ResourceEntry(ref,metadata,uri),destination)
            }.getOrElse {
                DiagnosticLog.record("browser.pins.entry-invalid","index=$index",it);null
            }
        }.distinctBy {it.destination.toString()}
    }

    fun save(pins:List<PinnedContainer>) {
        val array=JSONArray()
        pins.forEach {pin->
            val entry=pin.entry;val metadata=entry.metadata
            array.put(JSONObject().put("provider",entry.ref.providerId).put("documentId",entry.ref.documentId)
                .put("uri",entry.uri.toString()).put("destination",pin.destination.toString())
                .put("name",metadata.name).put("mimeType",metadata.mimeType).put("directory",metadata.isDirectory)
                .put("size",metadata.sizeBytes ?: JSONObject.NULL).put("modified",metadata.modifiedAtMillis ?: JSONObject.NULL)
                .put("canRead",metadata.canRead).put("canWrite",metadata.canWrite))
        }
        prefs.edit().putString(KEY,array.toString()).apply()
    }

    companion object {private const val KEY="pinned_containers_v1"}
}
