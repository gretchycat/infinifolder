package com.infinifolder

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.widget.Toast
import com.infinifolder.theme.ThemeManager

/** Portable shortcut payload; command text is only sent to Termux after a user opens the link. */
object TermuxCommandLink {
    private const val SCHEME = "infinifolder-termux"
    private const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    const val SUFFIX = ".termux.iflink"
    fun uri(command: String, workingDirectory: String?): Uri = Uri.Builder()
        .scheme(SCHEME).authority("run").appendQueryParameter("command", command).apply {
            workingDirectory?.takeIf { it.isNotBlank() }?.let { appendQueryParameter("cwd", it) }
        }.build()
    fun owns(uri: Uri) = uri.scheme.equals(SCHEME, true) && uri.authority == "run"

    fun launch(activity: Activity, uri: Uri, finished: () -> Unit = {}) {
        val command = runCatching { uri.getQueryParameter("command") }.getOrNull()
        val cwd = runCatching { uri.getQueryParameter("cwd") }.getOrNull()
        if (!owns(uri) || command.isNullOrBlank() || (cwd != null && !cwd.startsWith('/'))) {
            Toast.makeText(activity, "Invalid Termux command link", Toast.LENGTH_LONG).show()
            finished()
            return
        }
        start(activity, arrayOf("-lc", command), cwd, null) { finished() }
    }

    fun start(activity: Activity, arguments: Array<String>, cwd: String?, result: PendingIntent?, done: (Boolean) -> Unit) {
        val installed = try { activity.packageManager.getApplicationInfo("com.termux", 0); true }
            catch (_: PackageManager.NameNotFoundException) { false }
        if (!installed) {
            Toast.makeText(activity, "Termux is not installed", Toast.LENGTH_LONG).show()
            done(false)
            return
        }
        if (activity.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            val theme = if (ThemeManager.activeTheme.isDark) R.style.AppTheme_Dark else R.style.AppTheme
            android.app.AlertDialog.Builder(ContextThemeWrapper(activity, theme))
                .setTitle("Allow Termux commands")
                .setMessage("In InfiniFolder's app permissions, allow ‘Run commands in Termux environment’. In Termux, set allow-external-apps = true in ~/.termux/termux.properties. Then open this link again.")
                .setPositiveButton("App settings") { _, _ ->
                    activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))
                }.setNegativeButton("Close", null)
                .setOnDismissListener { done(false) }.show()
            return
        }
        try {
            val request = Intent("com.termux.RUN_COMMAND").setClassName("com.termux", "com.termux.app.RunCommandService")
                .putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arguments)
                .putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
                .putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            if (!cwd.isNullOrBlank()) request.putExtra("com.termux.RUN_COMMAND_WORKDIR", cwd)
            if (result != null) request.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", result)
            if (activity.startService(request) == null) throw IllegalStateException("Termux command service is unavailable")
            done(true)
        } catch (error: Exception) {
            Toast.makeText(activity, "Could not start Termux: ${error.message}", Toast.LENGTH_LONG).show()
            done(false)
        }
    }
}
