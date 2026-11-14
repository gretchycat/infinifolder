package com.infinifolder

import org.json.JSONArray
import org.json.JSONObject

/** A validated, persistable floating-selector breadcrumb route. */
internal data class SelectionBreadcrumb(val uri: String, val name: String)

internal object SelectionBreadcrumbState {
    private const val MAX_CRUMBS = 128
    private const val MAX_FIELD_LENGTH = 16_384

    fun encode(startUri: String, crumbs: List<SelectionBreadcrumb>, virtualRoot: String): String? {
        if (startUri.isBlank() || !isValidRoute(crumbs, virtualRoot)) return null
        return runCatching {
            JSONObject()
                .put("version", 1)
                .put("startUri", startUri)
                .put("crumbs", JSONArray().apply {
                    crumbs.forEach { put(JSONObject().put("uri", it.uri).put("name", it.name)) }
                })
                .toString()
        }.getOrNull()
    }

    fun decode(value: String?, expectedStartUri: String, virtualRoot: String): List<SelectionBreadcrumb>? {
        if (value.isNullOrBlank() || expectedStartUri.isBlank()) return null
        return runCatching {
            val json = JSONObject(value)
            require(json.optInt("version", -1) == 1)
            require(json.getString("startUri") == expectedStartUri)
            val array = json.getJSONArray("crumbs")
            require(array.length() in 1..MAX_CRUMBS)
            val crumbs = (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                SelectionBreadcrumb(item.getString("uri"), item.getString("name"))
            }
            require(isValidRoute(crumbs, virtualRoot))
            crumbs
        }.getOrNull()
    }

    private fun isValidRoute(crumbs: List<SelectionBreadcrumb>, virtualRoot: String): Boolean =
        crumbs.size in 1..MAX_CRUMBS &&
            crumbs.firstOrNull()?.uri == virtualRoot &&
            crumbs.all { it.uri.isNotBlank() && it.name.isNotBlank() &&
                it.uri.length <= MAX_FIELD_LENGTH && it.name.length <= MAX_FIELD_LENGTH }
}

/** Any broken non-root selection route must recover to the virtual root. */
internal object SelectionLocationFallback {
    fun shouldReturnToVirtualRoot(selectionMode: Boolean, location: String, virtualRoot: String): Boolean =
        selectionMode && location != virtualRoot
}
