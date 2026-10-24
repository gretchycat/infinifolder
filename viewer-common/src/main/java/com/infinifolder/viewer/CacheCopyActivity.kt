package com.infinifolder.viewer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.infinifolder.model.ProviderResult
import com.infinifolder.ops.FileOperations
import com.infinifolder.storage.StorageRuntime
import java.util.concurrent.atomic.AtomicBoolean

/** Independent viewer-host fallback: stream a copy to a user-granted directory, outside the cache. */
class CacheCopyActivity : Activity() {
    private val cancelled = AtomicBoolean(false)
    private var copying = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.infinifolder.theme.ThemeManager.init(this)
        if (savedInstanceState?.getBoolean("copying") == true) {
            android.widget.Toast.makeText(this, "The copy was interrupted. The source was kept; choose Copy again to retry.", android.widget.Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (savedInstanceState == null) startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), 1)
    }
    @Deprecated("Legacy picker result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1 || resultCode != RESULT_OK || data?.data == null) { finish(); return }
        val target = data.data!!
        val source = intent.data ?: run { finish(); return }
        val theme = com.infinifolder.theme.ThemeManager.activeTheme
        val text = TextView(this).apply { text = "Preparing copy…"; setTextColor(theme.textPrimary); setPadding(24, 24, 24, 24) }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(theme.background); addView(text); addView(bar) }
        setContentView(layout)
        copying = true
        Thread({
            try {
                val provider = StorageRuntime.get(this).provider
                val sourceRef = provider.refForUri(source) ?: error("No provider owns the source")
                val entry = when (val stat = provider.stat(sourceRef)) {
                    is ProviderResult.Success -> stat.value
                    is ProviderResult.Failure -> throw java.io.IOException(stat.message, stat.cause)
                    else -> error("Cannot inspect source")
                }
                val warnings = mutableListOf<String>()
                var lastUi = 0L
                FileOperations.batchTransfer(listOf(entry), target, false, provider,
                    { provider.refForUri(it) ?: error("No provider owns destination") },
                    { update ->
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - lastUi > 200) {
                            lastUi = now
                            runOnUiThread { if (!isDestroyed) { text.text = listOfNotNull(update.currentItem, update.message,
                                android.text.format.Formatter.formatShortFileSize(this, update.currentItemBytes ?: update.bytes)).joinToString("\n")
                                val total = update.currentItemTotalBytes ?: update.totalBytes
                                bar.isIndeterminate = total == null || total <= 0
                                if (total != null && total > 0) bar.progress = ((update.currentItemBytes ?: update.bytes).toDouble() / total * 100).toInt().coerceIn(0, 100) } }
                        }
                    }, { cancelled.get() }, onWarning = { warnings.add(it) })
                runOnUiThread {
                    copying = false
                    if (!isDestroyed) CacheDialogs.builder(this).setTitle("Copy complete")
                        .setMessage(if (warnings.isEmpty()) "The copy is saved in the selected folder." else warnings.joinToString("\n"))
                        .setPositiveButton("Close") { _, _ -> finish() }.setOnCancelListener { finish() }.create().also { it.show(); CacheDialogs.style(it) }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    copying = false
                    if (!isDestroyed) CacheDialogs.builder(this).setTitle("Copy failed")
                        .setMessage(error.message).setPositiveButton("Close") { _, _ -> finish() }
                        .setOnCancelListener { finish() }.create().also { it.show(); CacheDialogs.style(it) }
                }
            }
        }, "CacheCopyFallback").start()
    }
    @Deprecated("Legacy Back")
    override fun onBackPressed() {
        if (!copying) { finish(); return }
        CacheDialogs.builder(this).setMessage("Cancel this copy?").setNegativeButton("Continue", null)
            .setPositiveButton("Cancel copy") { _, _ -> cancelled.set(true) }.create().also { it.show(); CacheDialogs.style(it) }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("copying", copying)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { if (isFinishing) cancelled.set(true); super.onDestroy() }
}
