package com.infinifolder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.infinifolder.storage.DiagnosticLog

/** Update launcher metadata immediately after installation, including offline shortcuts. */
class ShortcutUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val result = goAsync()
        val app = context.applicationContext
        Thread {
            try {
                HomeShortcuts.migratePinned(app)
            } catch (error: Exception) {
                DiagnosticLog.record("home.shortcut.migration-failed", error = error)
            } finally {
                result.finish()
            }
        }.start()
    }
}
