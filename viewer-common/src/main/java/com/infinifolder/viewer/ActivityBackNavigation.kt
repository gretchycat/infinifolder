package com.infinifolder.viewer

import android.app.Activity
import android.os.Build
import android.os.SystemClock
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

/** Bridge modern system Back to the same action used by toolbar/legacy Back. */
object ActivityBackNavigation {
    fun register(activity: Activity, action: () -> Unit): java.io.Closeable {
        val activityName = activity.javaClass.simpleName
        if (Build.VERSION.SDK_INT < 33) {
            com.infinifolder.storage.DiagnosticLog.record(
                "navigation.back.registration", "activity=$activityName route=legacy"
            )
            return java.io.Closeable {
                com.infinifolder.storage.DiagnosticLog.record(
                    "navigation.back.registration", "activity=$activityName state=closed"
                )
            }
        }
        val dispatcher = activity.onBackInvokedDispatcher
        val callback = OnBackInvokedCallback {
            val startedAt = SystemClock.uptimeMillis()
            com.infinifolder.storage.DiagnosticLog.record(
                "navigation.back.dispatch", "activity=$activityName source=system state=begin"
            )
            try {
                action()
            } catch (error: Throwable) {
                com.infinifolder.storage.DiagnosticLog.record(
                    "navigation.back.dispatch", "activity=$activityName source=system state=failed", error
                )
                throw error
            } finally {
                com.infinifolder.storage.DiagnosticLog.record(
                    "navigation.back.dispatch",
                    "activity=$activityName source=system state=returned elapsedMs=${SystemClock.uptimeMillis() - startedAt} finishing=${activity.isFinishing} destroyed=${activity.isDestroyed}"
                )
            }
        }
        dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        com.infinifolder.storage.DiagnosticLog.record(
            "navigation.back.registration", "activity=$activityName state=registered route=system"
        )
        var closed = false
        return java.io.Closeable {
            if (!closed) {
                closed = true
                dispatcher.unregisterOnBackInvokedCallback(callback)
                com.infinifolder.storage.DiagnosticLog.record(
                    "navigation.back.registration", "activity=$activityName state=closed route=system"
                )
            }
        }
    }
}
