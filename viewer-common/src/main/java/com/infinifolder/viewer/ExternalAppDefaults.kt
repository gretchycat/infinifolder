package com.infinifolder.viewer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.infinifolder.mime.MimeRegistry
import com.infinifolder.model.ResourceRef
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** App-private launch preferences. Android's system associations are never changed. */
class ExternalAppDefaults(context: Context) {
    private val ownPackage = context.applicationContext.packageName
    data class Rule(val action: String, val scope: String, val key: String, val mime: String,
        val name: String, val component: ComponentName)
    private val prefs = context.applicationContext.getSharedPreferences("external_app_defaults", Context.MODE_PRIVATE)
    fun rules(): List<Rule> = runCatching {
        val array = JSONArray(prefs.getString("rules", "[]"))
        (0 until array.length()).mapNotNull { index ->
            val row = array.getJSONObject(index)
            val component = ComponentName.unflattenFromString(row.getString("component")) ?: return@mapNotNull null
            Rule(row.getString("action"), row.getString("scope"), row.getString("key"),
                row.getString("mime"), row.optString("name"), component)
        }
    }.getOrDefault(emptyList())
    private fun write(rules: List<Rule>) {
        val array = JSONArray()
        rules.forEach { row -> array.put(JSONObject().put("action", row.action).put("scope", row.scope)
            .put("key", row.key).put("mime", row.mime).put("name", row.name)
            .put("component", row.component.flattenToString())) }
        prefs.edit().putString("rules", array.toString()).apply()
    }
    fun remember(action: String, scope: String, sourceKey: String?, mime: String?, name: String, component: ComponentName) {
        require(action == Intent.ACTION_VIEW || action == Intent.ACTION_EDIT)
        require(scope == FILE || scope == TYPE)
        val normalized = MimeRegistry.normalize(mime)
        val key = if (scope == FILE) requireNotNull(sourceKey) else normalized
        synchronized(lock) {
            write(rules().filterNot { it.action == action &&
                ((it.scope == scope && it.key == key) ||
                    (scope == TYPE && it.scope == FILE && it.key == sourceKey)) } +
                Rule(action, scope, key, normalized, name, component))
        }
    }
    fun remove(rule: Rule) = synchronized(lock) { write(rules().filterNot { it == rule }) }
    fun candidates(action: String, sourceKey: String?, mime: String?): List<ComponentName> {
        val normalized = MimeRegistry.normalize(mime)
        val saved = rules().filter { it.action == action }
        return (saved.firstOrNull { it.scope == FILE && it.key == sourceKey }?.component?.let(::listOf).orEmpty() +
            saved.firstOrNull { it.scope == TYPE && it.key == normalized }?.component?.let(::listOf).orEmpty() +
            if (action == Intent.ACTION_EDIT) listOfNotNull(editor) else emptyList()).distinct()
    }
    /** External choices bypass the built-in route; saved internal choices stay on it. */
    fun hasOpenPreference(ref: ResourceRef, mime: String?): Boolean =
        candidates(Intent.ACTION_VIEW, sourceKey(ref), mime).firstOrNull()?.let { it.packageName != ownPackage } == true
    fun hasInternalOpenPreference(ref: ResourceRef, mime: String?): Boolean =
        candidates(Intent.ACTION_VIEW, sourceKey(ref), mime).firstOrNull()?.let { component ->
            component.packageName == ownPackage && ViewerComponents.registry.query(io.github.gretchycat.infinistream.Operations.VIEW)
                .any { it.handler.service(ViewerComponent::class.java)?.activityClassName == component.className }
        } == true
    var editor: ComponentName?
        get() = prefs.getString("editor", null)?.let(ComponentName::unflattenFromString)
        set(value) { prefs.edit().apply { if (value == null) remove("editor") else putString("editor", value.flattenToString()) }.apply() }
    companion object {
        const val FILE = "file"
        const val TYPE = "type"
        private val lock = Any()
        fun sourceKey(ref: ResourceRef): String = digest(ref.providerId + "\u0000" + ref.documentId)
        fun uriKey(uri: String): String = digest("uri\u0000$uri")
        private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
