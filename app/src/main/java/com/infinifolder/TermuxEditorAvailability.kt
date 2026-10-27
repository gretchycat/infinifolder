package com.infinifolder

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.infinifolder.storage.DiagnosticLog
import java.security.MessageDigest

/** Background probes run only a fixed command-v check, never the user's command. */
object TermuxEditorAvailability {
    enum class State { AVAILABLE, MISSING, UNKNOWN }
    private const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    private const val TTL = 60_000L
    private val commandPattern = Regex("^([A-Za-z0-9_./+\\-]+)(?:\\s|$)")
    fun executable(command: String): String? = commandPattern.find(command.trim())?.groupValues?.get(1)
    @Volatile private var lastProbe = 0L
    private fun preferences(context: Context) = context.getSharedPreferences("termux_editor_availability", Context.MODE_PRIVATE)
    fun key(command: String): String = MessageDigest.getInstance("SHA-256").digest(command.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    fun state(context: Context, command: String): State {
        if (!TermuxAvailability.installed(context)) return State.UNKNOWN
        if (context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) return State.UNKNOWN
        return when (preferences(context).getString(key(command), null)) {
            "available" -> State.AVAILABLE
            "missing" -> State.MISSING
            else -> State.UNKNOWN
        }
    }
    fun canCheck(context: Context) = TermuxAvailability.installed(context) &&
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    @Synchronized fun invalidate(context: Context) {
        preferences(context).edit().clear().apply()
        lastProbe = 0L
        TermuxAvailability.refresh(context)
    }

    @Synchronized fun probe(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && lastProbe != 0L && now - lastProbe < TTL) return
        lastProbe = now
        val rules = TermuxAssociations(app).list().distinctBy { it.command }
        val prefs = preferences(app)
        val generation = java.util.UUID.randomUUID().toString()
        val clear = prefs.edit().putString("generation", generation)
        rules.forEach { clear.remove(key(it.command)) }
        clear.apply()
        TermuxAvailability.refresh(app)
        if (!canCheck(app)) return
        for (rule in rules) {
            val executable = executable(rule.command) ?: continue
            val identity = key(rule.command)
            val result = Intent(app, TermuxProbeReceiver::class.java).setAction("com.infinifolder.TERMUX_PROBE")
                .setData(Uri.Builder().scheme("if-termux-probe").authority("editor").appendPath(generation).appendPath(identity).build())
            val flags = PendingIntent.FLAG_ONE_SHOT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getBroadcast(app, 0, result, flags)
            val request = Intent("com.termux.RUN_COMMAND").setClassName("com.termux", "com.termux.app.RunCommandService")
                .putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("--noprofile", "--norc", "-c", "command -v -- \"\$1\" >/dev/null 2>&1", "infinifolder-probe", executable))
                .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pending)
            try {
                if (app.startService(request) == null) pending.cancel()
            } catch (error: Exception) {
                pending.cancel()
                DiagnosticLog.record("termux.editor.probe-failed", "reason=${error.javaClass.simpleName}")
            }
        }
    }
    fun record(context: Context, generation: String, id: String, result: android.os.Bundle?) {
        if (preferences(context).getString("generation", null) != generation) return
        if (TermuxAssociations(context).list().none { key(it.command) == id }) return
        val err = result?.getInt("err", -1)
        val exit = result?.getInt("exitCode", -1)
        val value = if (err == -1 && exit == 0) "available" else if (err == -1 && exit == 1) "missing" else null
        val edit = preferences(context).edit()
        if (value == null) edit.remove(id) else edit.putString(id, value)
        edit.apply()
        DiagnosticLog.record("termux.editor.probe", "state=${value ?: "unknown"} error=$err exit=$exit")
        TermuxAvailability.refresh(context)
    }
    fun markMissing(context: Context, id: String) {
        if (TermuxAssociations(context).list().none { key(it.command) == id }) return
        preferences(context).edit().putString(id, "missing").apply()
        TermuxAvailability.refresh(context)
    }
}

class TermuxProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.data?.lastPathSegment ?: return
        val generation = intent.data?.pathSegments?.firstOrNull() ?: return
        TermuxEditorAvailability.record(context.applicationContext, generation, id, intent.getBundleExtra("result"))
    }
}
