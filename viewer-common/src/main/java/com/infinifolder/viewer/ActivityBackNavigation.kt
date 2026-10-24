package com.infinifolder.viewer

import android.app.Activity
import android.os.Build
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

/** Bridge modern system Back to the same action used by toolbar/legacy Back. */
object ActivityBackNavigation {
    fun register(activity: Activity, action: () -> Unit): java.io.Closeable {
        if (Build.VERSION.SDK_INT < 33) return java.io.Closeable { }
        val dispatcher = activity.onBackInvokedDispatcher
        val callback = OnBackInvokedCallback {
            com.infinifolder.storage.DiagnosticLog.record("navigation.back.system", "activity=${activity.javaClass.simpleName}")
            action()
        }
        dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        return java.io.Closeable { dispatcher.unregisterOnBackInvokedCallback(callback) }
    }
}
