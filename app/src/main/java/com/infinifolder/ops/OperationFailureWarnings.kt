package com.infinifolder.ops

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.widget.TextView
import com.infinifolder.MainActivity
import com.infinifolder.R
import com.infinifolder.storage.DiagnosticLog
import com.infinifolder.theme.ThemeManager

/** Main-thread queue shared by browser/viewer activities. Dismissing a host never
 * acknowledges its warning; an unread failure follows the next resumed activity.
 */
internal class OperationFailureWarnings {
    private val main = Handler(Looper.getMainLooper())
    private val pending = linkedMapOf<String, OperationSnapshot>()
    private var host: Activity? = null
    private var dialog: AlertDialog? = null

    fun enqueue(operation: OperationSnapshot) {
        if (operation.state != OperationState.FAILED || operation.id.startsWith("playback_")) return
        if (AppOperations.coordinator.snapshots().none { it.id == operation.id && it.state == OperationState.FAILED }) return
        pending[operation.id] = operation
        showNext()
    }

    fun resumed(activity: Activity) {
        if (host !== activity) detach()
        host = activity
        // Let the activity finish restoring its own content and dialogs first.
        main.post { if (host === activity) showNext() }
    }

    fun paused(activity: Activity) {
        if (host === activity) detach()
    }

    private fun detach() {
        host = null
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
        dialog = null
    }

    private fun showNext() {
        if (dialog != null) return
        val failures = AppOperations.coordinator.snapshots().filter { it.state == OperationState.FAILED }.map { it.id }.toSet()
        pending.keys.retainAll(failures)
        val activity = host?.takeUnless { it.isFinishing || it.isDestroyed } ?: return
        val operation = pending.values.firstOrNull() ?: return
        val theme = ThemeManager.activeTheme
        val context = ContextThemeWrapper(activity, if (theme.isDark) R.style.AppTheme_Dark else R.style.AppTheme)
        val rawReason = operation.message?.takeIf { it.isNotBlank() } ?: "No failure reason was reported."
        // Keep recovery and cleanup warnings intact. Full details remain in Activity.
        val needsFullDetail = listOf("cleanup failed", "may remain", "retained for inspection", "recovery report", "torrent").any {
            rawReason.contains(it, ignoreCase = true)
        }
        val retained = if (rawReason.contains("source retained", ignoreCase = true) ||
            rawReason.contains("sources retained", ignoreCase = true)) " Sources retained." else ""
        val reason = when {
            needsFullDetail -> rawReason
            rawReason.contains("already exists", ignoreCase = true) ||
                rawReason.contains("file exists", ignoreCase = true) -> "A file or folder already exists at the destination.$retained"
            rawReason.contains("no space left on device", ignoreCase = true) -> "Not enough storage space.$retained"
            else -> rawReason
        }
        val message = listOfNotNull(operation.label,
            operation.currentItem?.takeIf { it.isNotBlank() && it !in operation.label }?.let { "Item: $it" },
            operation.destination?.takeIf { it.isNotBlank() }?.let { "To: $it" }, reason).joinToString("\n\n")
        val warning = AlertDialog.Builder(context)
            .setTitle("File operation failed")
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setMessage(message)
            .setPositiveButton("OK") { _, _ -> pending.remove(operation.id) }
            .setNeutralButton("Activity") { _, _ ->
                pending.remove(operation.id)
                activity.startActivity(Intent(activity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("show_operations", true)
                })
            }
            .create()
        warning.setOnCancelListener { pending.remove(operation.id) }
        warning.setOnDismissListener {
            if (dialog === warning) dialog = null
            main.post { showNext() }
        }
        dialog = warning
        try {
            warning.show()
            warning.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(theme.surface))
            warning.findViewById<TextView>(android.R.id.message)?.apply {
                setTextColor(theme.textPrimary)
                setTextIsSelectable(true)
            }
            warning.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(theme.accent)
            warning.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(theme.accent)
            DiagnosticLog.record("operation.failure.warning.shown", "id=${operation.id} host=${activity.javaClass.simpleName}")
        } catch (error: android.view.WindowManager.BadTokenException) {
            warning.setOnDismissListener(null)
            warning.dismiss()
            dialog = null
            DiagnosticLog.record("operation.failure.warning.deferred", "id=${operation.id}", error)
        }
    }
}
