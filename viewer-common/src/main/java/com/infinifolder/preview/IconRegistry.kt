package com.infinifolder.preview

import com.infinifolder.mime.MimeRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Persistent icon/association settings. Unknown JSON fields survive every update. */
class IconRegistry(private val file: File) {
    data class MimeRecord(val mimeType: String, val extensions: Set<String>, val iconName: String,
        val overrideIcon: String? = null, val isImage: Boolean = false, val overrideImageIcon: Boolean = !isImage)
    data class UriRule(val id: String, val exactUri: String? = null, val scheme: String = "",
        val authority: String = "", val pathPrefix: String = "", val iconName: String = "",
        val overrideIcon: String? = null)
    private val lock = locks.getOrPut(file.absolutePath) { Any() }
    private var loadedStamp = ""
    private var root = JSONObject()
    private var associationIndex:Map<String,Set<String>>?=null
    var readError:String?=null
        private set
    private fun load() {
        val stamp = if(file.isFile) "${java.nio.file.Files.getLastModifiedTime(file.toPath())}:${file.length()}" else "missing"
        if (stamp == loadedStamp) return
        associationIndex=null
        readError=null
        root = if (file.isFile) runCatching {JSONObject(file.readText())}.getOrElse {
            readError="Cannot read saved icon registry: ${it.message}";JSONObject()
        } else JSONObject()
        loadedStamp = stamp
        // One-time user-requested switch of existing image entries to content icons.
        // Only the image-mode field changes; later individual edits remain authoritative.
        if (readError == null && !root.optBoolean("imageContentIconsEnabled", false)) {
            val records = recordsJson()
            for (key in (builtins.keys + records.keys().asSequence().toList()).distinct()) {
                val saved = records.optJSONObject(key)
                val renderable = if (saved?.has("isImage") == true) saved.optBoolean("isImage") else builtins[key]?.isImage == true
                if (renderable || key.startsWith("image/")) {
                    val entry = saved ?: JSONObject()
                    entry.put("overrideImageIcon", false)
                    records.put(key, entry)
                }
            }
            root.put("imageContentIconsEnabled", true)
            persist()
        }
        // User-requested book-cover defaults. Keep associations, artwork overrides
        // and other MIME fields intact; subsequent individual edits remain saved.
        if (readError == null && !root.optBoolean("bookCoverIconsEnabled", false)) {
            val records = recordsJson()
            for (key in bookCoverMimeTypes) {
                val entry = records.optJSONObject(key) ?: JSONObject()
                entry.put("isImage", true)
                entry.put("overrideImageIcon", false)
                records.put(key, entry)
            }
            root.put("bookCoverIconsEnabled", true)
            persist()
        }
    }
    val revision: Long get() = synchronized(lock) { load(); root.optLong("revision", 0) }
    private fun recordsJson() = root.optJSONObject("mime") ?: JSONObject().also { root.put("mime", it) }
    fun mime(mime: String): MimeRecord = synchronized(lock) {
        load(); readMimeRecord(MimeRegistry.normalize(mime))
    }
    private fun readMimeRecord(key:String):MimeRecord {
        val built=builtins[key]
        val json = recordsJson().optJSONObject(key)
        val renderable = if (json?.has("isImage") == true) json.optBoolean("isImage") else built?.isImage ?: false
        return MimeRecord(key, json?.optJSONArray("extensions")?.strings()?.toSet() ?: built?.extensions.orEmpty(),
            json?.optString("iconName", built?.iconName ?: defaultIcon(key)) ?: built?.iconName ?: defaultIcon(key),
            json?.optionalString("overrideIcon"),
            renderable,
            if (json?.has("overrideImageIcon") == true) json.optBoolean("overrideImageIcon") else !(key.startsWith("image/") || renderable))
    }
    fun rendererExtension(mime:String):String? = rendererExtension(mime,"")
    fun rendererExtension(mime:String,name:String):String? {
        val supported=builtins[MimeRegistry.normalize(mime)]?.extensions
            ?.filter {it in ImageFormats.decodableExtensions}.orEmpty()
        // Shared MIME types can contain incompatible encodings (PI1 versus compressed PC1).
        val actual=ImageFormats.extension(name)
        return actual.takeIf {it in supported} ?: supported.sorted().firstOrNull()
    }
    fun mimeTypes(): List<String> = synchronized(lock) {
        load(); (builtins.keys + recordsJson().keys().asSequence().toList()).sorted()
    }
    fun candidates(name: String): Set<String> = synchronized(lock) {
        load()
        val index=associationIndex ?: run {
            val defaults=mutableMapOf<String,MutableSet<String>>()
            val explicit=mutableMapOf<String,MutableSet<String>>()
            for(key in (builtins.keys+recordsJson().keys().asSequence().toList()).distinct()) {
                val record=readMimeRecord(key)
                val target=if(recordsJson().optJSONObject(key)?.has("extensions")==true)explicit else defaults
                for(extension in record.extensions)target.getOrPut(extension.lowercase(Locale.ROOT)){linkedSetOf()}.add(record.mimeType)
            }
            (defaults+explicit).mapValues {it.value.toSet()}.also {associationIndex=it}
        }
        val lower=name.lowercase(Locale.ROOT)
        var dot=lower.indexOf('.')
        while(dot>=0) {
            index[lower.substring(dot+1)]?.let {return@synchronized it}
            dot=lower.indexOf('.',dot+1)
        }
        emptySet()
    }
    fun save(record: MimeRecord) = synchronized(lock) {
        load(); val key = MimeRegistry.normalize(record.mimeType)
        require(key.matches(Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+"))) { "Enter a valid MIME type" }
        val json = recordsJson().optJSONObject(key) ?: JSONObject()
        json.put("extensions", JSONArray(record.extensions.map { it.lowercase(Locale.ROOT).trim().trimStart('.') }.filter { it.isNotEmpty() }.distinct().sorted()))
        json.put("iconName", record.iconName.trim()); json.put("overrideIcon", record.overrideIcon ?: JSONObject.NULL)
        json.put("isImage", record.isImage); json.put("overrideImageIcon", record.overrideImageIcon)
        recordsJson().put(key, json); persist()
    }
    fun uriRules(): List<UriRule> = synchronized(lock) {
        load(); val rules = root.optJSONArray("uriRules") ?: return@synchronized emptyList()
        (0 until rules.length()).mapNotNull { i -> rules.optJSONObject(i)?.let { j ->
            UriRule(j.optString("id"), j.optionalString("exactUri"),
                j.optString("scheme"), j.optString("authority"), j.optString("pathPrefix"),
                j.optString("iconName"), j.optionalString("overrideIcon")) } }
    }
    fun save(rule: UriRule) = synchronized(lock) {
        load(); require(rule.id.isNotBlank())
        val scheme=rule.scheme.trim().lowercase(Locale.ROOT)
        require(scheme.isBlank() || scheme.matches(Regex("[a-z][a-z0-9+.-]*"))) {"Enter a valid URI scheme"}
        require(rule.pathPrefix.isBlank() || rule.pathPrefix.startsWith('/')) {"A path prefix must start with /"}
        if(rule.authority.isNotBlank())require(URI("${scheme.ifBlank {"https"}}://${rule.authority}/").rawAuthority!=null) {"Enter a valid authority"}
        val rules = root.optJSONArray("uriRules") ?: JSONArray()
        var index = (0 until rules.length()).firstOrNull { rules.optJSONObject(it)?.optString("id") == rule.id }
        val json = index?.let { rules.optJSONObject(it) } ?: JSONObject()
        if (index == null) index = rules.length()
        json.put("id", rule.id); json.put("exactUri", rule.exactUri?.let(::normalizeUri) ?: JSONObject.NULL)
        json.put("scheme", scheme); json.put("authority", rule.authority)
        json.put("pathPrefix", rule.pathPrefix); json.put("iconName", rule.iconName)
        json.put("overrideIcon", rule.overrideIcon ?: JSONObject.NULL)
        rules.put(index, json); root.put("uriRules", rules); persist()
    }
    fun removeUriRule(id: String) = synchronized(lock) {
        load(); val original = root.optJSONArray("uriRules") ?: JSONArray()
        root.put("uriRules", JSONArray((0 until original.length()).mapNotNull { original.optJSONObject(it) }.filter { it.optString("id") != id })); persist()
    }
    fun matchUri(raw: String): UriRule? {
        val normalized = normalizeUri(raw)
        val uri = URI(normalized)
        val matches = uriRules().filter {rule->rule.overrideIcon!=null || rule.iconName.isNotBlank()}.filter { rule ->
            if (rule.exactUri != null) normalizeUri(rule.exactUri) == normalized else
                (rule.scheme.isBlank() || rule.scheme.equals(uri.scheme, true)) &&
                (rule.authority.isBlank() || normalizeAuthority(rule.authority, uri.scheme ?: "https") == uri.rawAuthority) &&
                (rule.pathPrefix.isBlank() || (uri.rawPath.orEmpty().startsWith(rule.pathPrefix) &&
                    (rule.pathPrefix.endsWith('/') || uri.rawPath.orEmpty().length == rule.pathPrefix.length || uri.rawPath.orEmpty().getOrNull(rule.pathPrefix.length) == '/')))
        }
        return matches.sortedWith(compareByDescending<UriRule> { it.exactUri != null }
            .thenByDescending { it.authority.isNotBlank() }.thenByDescending { it.scheme.isNotBlank() }
            .thenByDescending { it.pathPrefix.length }.thenBy { it.id }).firstOrNull()
    }
    private fun persist() {
        associationIndex=null
        check(readError==null) {readError ?: "Cannot overwrite unreadable registry"}
        root.put("version",maxOf(1,root.optInt("version",1))); root.put("revision", root.optLong("revision") + 1)
        file.parentFile?.mkdirs()
        val temp = File.createTempFile("icon-registry-", ".json", file.parentFile)
        try {
            temp.outputStream().use { it.write(root.toString(2).toByteArray()); it.fd.sync() }
            check(temp.renameTo(file)) { "Cannot save icon registry" }
            loadedStamp = ""
            listeners.filter {it.first==file.absolutePath}.forEach {runCatching {it.second()}}
        } finally { temp.delete() }
    }
    companion object {
        private val bookCoverMimeTypes = setOf("application/epub+zip", "application/pdf", "application/x-mobipocket-ebook", "application/vnd.amazon.ebook", "application/vnd.amazon.mobi8-ebook", "application/x-fictionbook+xml")
        private val listeners=java.util.concurrent.CopyOnWriteArrayList<Pair<String,()->Unit>>()
        fun listen(base:File,listener:()->Unit):AutoCloseable {
            val item=File(base,"icon_registry.json").absolutePath to listener
            listeners.add(item);return AutoCloseable {listeners.remove(item)}
        }
        private val locks = ConcurrentHashMap<String, Any>()
        fun at(base: File) = IconRegistry(File(base, "icon_registry.json"))
        private fun JSONObject.optionalString(key:String):String? =
            (opt(key) as? String)?.takeIf {it.isNotBlank() && it!="null"}
        private fun JSONArray.strings() = (0 until length()).map { getString(it) }
        private fun defaultIcon(mime: String) = MimeRegistry.iconCandidates(mime).first()
        private val builtins: Map<String, MimeRecord> by lazy {
            val associations = MimeRegistry.associations().toMutableMap()
            // Display identities for text-image types; the storage/decoder contracts stay intact.
            associations.putAll(mapOf("diz" to "text/x-diz", "asc" to "text/x-ascii-art", "nfo" to "text/x-nfo"))
            val imageExtensions = ImageFormats.decodableExtensions + setOf("ansi", "asc", "diz", "nfo")
            for (ext in imageExtensions) if (associations[ext] == null || associations[ext] == "application/octet-stream")
                associations[ext] = "image/x-$ext"
            associations.entries.groupBy({ MimeRegistry.normalize(it.value) }, { it.key }).mapValues { (mime, ext) ->
                MimeRecord(mime, ext.toSet(), defaultIcon(mime), isImage = mime in bookCoverMimeTypes || mime=="application/vnd.android.package-archive" || ext.any { it in imageExtensions })
            }
        }
        fun normalizeUri(raw: String): String {
            val uri = URI(raw.trim()); val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: error("URI needs a scheme")
            if (uri.isOpaque) return "$scheme:${uri.rawSchemeSpecificPart}" + (uri.rawFragment?.let { "#$it" } ?: "")
            return buildString {
                append(scheme); append(':')
                uri.rawAuthority?.let { append("//"); append(normalizeAuthority(it, scheme)) }
                append(uri.rawPath.orEmpty()); uri.rawQuery?.let { append('?'); append(it) }; uri.rawFragment?.let { append('#'); append(it) }
            }
        }
        private fun normalizeAuthority(authority: String, scheme: String): String {
            val parsed = URI("$scheme://$authority/")
            val host = parsed.host ?: return authority
            val normalizedHost=if(host.contains('%'))host.substringBefore('%').lowercase(Locale.ROOT)+"%"+host.substringAfter('%') else host.lowercase(Locale.ROOT)
            return (parsed.rawUserInfo?.let { "$it@" } ?: "") + normalizedHost +
                (if (parsed.port >= 0) ":${parsed.port}" else "")
        }
    }
}
