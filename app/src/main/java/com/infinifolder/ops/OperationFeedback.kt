package com.infinifolder.ops

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.infinifolder.MainActivity
import com.infinifolder.storage.DiagnosticLog
import java.util.concurrent.Executors

/** One process-wide completion observer; viewer activities count as being in the app too. */
object OperationFeedback {
    private const val CHANNEL = "infinifolder_completed_operations"
    private val main = Handler(Looper.getMainLooper())
    private val notifications = Executors.newSingleThreadExecutor { task ->
        Thread(task, "OperationCompletionNotifications").apply { isDaemon = true }
    }
    private val listeners = mutableSetOf<(OperationSnapshot) -> Unit>()
    @Volatile private var foreground = false
    private var installed = false

    fun addListener(listener: (OperationSnapshot) -> Unit) { listeners.add(listener) }
    fun removeListener(listener: (OperationSnapshot) -> Unit) { listeners.remove(listener) }

    fun install(application: Application) {
        if (installed) return
        installed = true
        val started = mutableSetOf<Activity>()
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                started.add(activity)
                foreground = true
            }
            override fun onActivityStopped(activity: Activity) {
                started.remove(activity)
                foreground = started.isNotEmpty() || activity.isChangingConfigurations
            }
            override fun onActivityDestroyed(activity: Activity) { started.remove(activity) }
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
        })
        // Seed history so recreating an activity never announces old completed jobs.
        val announced = AppOperations.coordinator.snapshots().filter { it.isTerminal() }.map { it.id }.toMutableSet()
        AppOperations.coordinator.addListener { snapshots ->
            val finished = synchronized(announced) {
                snapshots.filter { !it.id.startsWith("playback_") && it.isTerminal() && announced.add(it.id) }
            }
            for (op in finished) {
                DiagnosticLog.record("operation.completion.dispatch", "id=${op.id} state=${op.state} foreground=$foreground label=${op.label}")
                if (foreground) {
                    main.post { listeners.toList().forEach { it(op) } }
                } else if (op.state != OperationState.CANCELLED) {
                    notifications.execute { postNotification(application, op) }
                }
            }
        }
    }

    private fun OperationSnapshot.isTerminal() =
        state == OperationState.COMPLETE || state == OperationState.FAILED || state == OperationState.CANCELLED

    private fun postNotification(application: Application, op: OperationSnapshot) {
        try {
            if (Build.VERSION.SDK_INT >= 33 && application.checkSelfPermission(
                    android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                DiagnosticLog.record("operation.completion.notification.skipped", "Notification permission not granted: ${op.label}")
                return
            }
            val manager = NotificationManagerCompat.from(application)
            if (Build.VERSION.SDK_INT >= 26) {
                manager.createNotificationChannel(NotificationChannel(CHANNEL, "Completed file operations",
                    NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Results of file operations completed while InfiniFolder is in the background"
                })
            }
            if (!manager.areNotificationsEnabled() || (Build.VERSION.SDK_INT >= 26 &&
                    manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE)) {
                DiagnosticLog.record("operation.completion.notification.skipped", "Notifications disabled: ${op.label}")
                return
            }
            val failed = op.state == OperationState.FAILED
            val result = when {
                failed -> op.message ?: "Operation failed. Open InfiniFolder for details."
                op.warningCount > 0 -> "Finished with ${op.warningCount} warning(s). Open InfiniFolder to review."
                else -> "Completed"
            }
            val click = PendingIntent.getActivity(application, 2002,
                Intent(application, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(application, CHANNEL)
                .setSmallIcon(if (failed) android.R.drawable.stat_notify_error else android.R.drawable.stat_sys_download_done)
                .setContentTitle(op.label)
                .setContentText(result)
                .setStyle(NotificationCompat.BigTextStyle().bigText(result))
                .setContentIntent(click)
                .setAutoCancel(true)
                .setGroup("infinifolder_completed_operations")
                .build()
            // Separate tag/id from the foreground service, whose progress notification is removed at the end.
            manager.notify(op.id, 2002, notification)
            DiagnosticLog.record("operation.completion.notification", "${op.label}: $result")
        } catch (e: Exception) {
            DiagnosticLog.record("operation.completion.notification.failed", op.label, e)
        }
    }
}
