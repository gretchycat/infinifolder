package com.infinifolder

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import com.infinifolder.apps.AppsUri
import com.infinifolder.archive.ArchiveFormats
import com.infinifolder.archive.ArchiveUri
import com.infinifolder.model.*
import com.infinifolder.storage.*
import com.infinifolder.overlay.FloatingContainerService

/** Transparent shortcut dispatcher: container launches never construct the full browser UI. */
class HomeItemActivity : Activity() {
    private var pendingContainer: Uri? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.infinifolder.theme.ThemeManager.init(applicationContext)
        DiagnosticLog.record("home.dispatch.created", "action=${intent.action} task=$taskId root=$isTaskRoot restored=${savedInstanceState != null}")
        pendingContainer = savedInstanceState?.getString("container")?.let(Uri::parse)
        if (pendingContainer != null) return
        val uri = intent.data
        if (uri == null) {
            // The ordinary application icon opens the browser; resource pins stay
            // in this independent transparent task until their destination is ready.
            startActivity(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
            overridePendingTransition(0, 0)
            return
        }
        Thread {
            try {
                val provider = StorageRuntime.get(this).provider
                val ref = provider.refForUri(uri)
                val stat = ref?.let { provider.stat(it) }
                if (stat is ProviderResult.Failure) throw java.io.IOException(stat.message)
                var entry = (stat as? ProviderResult.Success)?.value
                var target = uri
                if (entry != null && LinkResolver.kind(entry.metadata) != null) {
                    when (val link = LinkResolver.resolve(provider, entry)) {
                        is ProviderResult.Success -> { entry = link.value.entry; target = link.value.uri }
                        is ProviderResult.Failure -> throw java.io.IOException(link.message)
                        else -> Unit
                    }
                }
                val result = entry
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    try {
                    when {
                        AppsUri.isAppUri(target) -> {
                            val launch = AppsUri.getPackageName(target)?.let(packageManager::getLaunchIntentForPackage)
                            if (launch != null) startActivity(launch)
                            else AppsUri.getPackageName(target)?.let {
                                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$it")))
                            }
                            finish()
                        }
                        result != null && (result.metadata.isDirectory ||
                            (!ArchiveFormats.isPackagedFormat(result.metadata.name, result.metadata.mimeType) &&
                                (ArchiveFormats.isArchive(result.metadata.name, result.metadata.mimeType) ||
                                    ArchiveFormats.isDiscImage(result.metadata.name, result.metadata.mimeType)))) -> {
                            val container = if (result.metadata.isDirectory) result.uri else ArchiveUri.toArchiveUri(result.uri)
                            openContainer(container)
                        }
                        else -> openFull(target)
                    }
                    } catch (error: Exception) {
                        DiagnosticLog.record("home.shortcut.launch-failed", error = error)
                        Toast.makeText(this, error.message ?: "Cannot open shortcut", Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
                HomeShortcuts.refreshPinned(applicationContext)
            } catch (error: Exception) {
                DiagnosticLog.record("home.shortcut.failed", error = error)
                runOnUiThread {
                    Toast.makeText(this, error.message ?: "Cannot open shortcut", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }.start()
    }

    private fun openContainer(uri: Uri) {
        if (Settings.canDrawOverlays(this)) {
            DiagnosticLog.record("home.dispatch.overlay", "task=$taskId uri=$uri")
            startService(FloatingContainerService.intent(this, uri))
            finish()
            overridePendingTransition(0, 0)
        } else {
            pendingContainer = uri
            val theme = if (com.infinifolder.theme.ThemeManager.activeTheme.isDark) R.style.AppTheme_Dark else R.style.AppTheme
            android.app.AlertDialog.Builder(android.view.ContextThemeWrapper(this, theme)).setTitle("Display over other apps")
                .setMessage("Allow InfiniFolder to open this container in a floating window?")
                .setPositiveButton("Allow") { _, _ ->
                    startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")), 1)
                }.setNegativeButton("Open in InfiniFolder") { _, _ -> openFull(uri) }
                .setOnCancelListener { finish() }.show()
        }
    }
    private fun openFull(uri: Uri) {
        startActivity(Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_HOME_ITEM_IN_APP).setData(uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("container", pendingContainer?.toString())
    }
    @Deprecated("Android Activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        pendingContainer?.let { if (Settings.canDrawOverlays(this)) openContainer(it) else openFull(it) }
    }
    companion object {
        fun intent(context: Context, uri: Uri) = Intent(context, HomeItemActivity::class.java)
            .setAction(MainActivity.ACTION_HOME_ITEM).setData(uri)
    }
}
