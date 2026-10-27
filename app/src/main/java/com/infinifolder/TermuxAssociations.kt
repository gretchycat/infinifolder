package com.infinifolder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** User-defined suffix rules. Program text is trusted user configuration. */
class TermuxAssociations(context: Context) {
    data class Rule(val suffix: String, val command: String)
    private val prefs = context.getSharedPreferences("termux_associations", Context.MODE_PRIVATE)
    fun list(): List<Rule> {
        val raw = prefs.getString("rules", null) ?: return listOf(Rule(".py", "nvim"))
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.getJSONObject(i)
                val suffix = obj.optString("suffix").trim().lowercase(Locale.ROOT)
                val command = obj.optString("command").trim()
                if (suffix.startsWith('.') && suffix.length > 1 && command.isNotEmpty()) Rule(suffix, command) else null
            }
        }.getOrDefault(emptyList())
    }
    fun save(rules: List<Rule>) {
        val array = JSONArray()
        rules.forEach { array.put(JSONObject().put("suffix", it.suffix).put("command", it.command)) }
        prefs.edit().putString("rules", array.toString()).apply()
    }
    fun find(name: String): Rule? = list().filter { name.endsWith(it.suffix, ignoreCase = true) }
        .maxByOrNull { it.suffix.length }
}
