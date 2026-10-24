package com.infinifolder.ops

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.infinifolder.MainActivity

class OperationService : Service() {

    companion object {
        const val CHANNEL_ID = "infinifolder_operations_channel"
        const val NOTIFICATION_ID = 2001
        const val ACTION_CANCEL = "com.infinifolder.ops.ACTION_CANCEL"
        const val EXTRA_OP_ID = "extra_op_id"

        fun start(context: Context) {
            val hasFileOps = AppOperations.coordinator.snapshots().any {
                (it.state == OperationState.RUNNING || it.state == OperationState.QUEUED) &&
                !it.id.startsWith("playback_")
            }
            if (!hasFileOps) return

            val intent = Intent(context, OperationService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {}
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, OperationService::class.java))
            } catch (_: Exception) {}
        }
    }

    private lateinit var notificationManager: NotificationManager
    private var lastNotificationAt = 0L
    private var lastNotificationOperation: String? = null
    private val listener: (List<OperationSnapshot>) -> Unit = { snapshots ->
        val active = snapshots.firstOrNull {
            (it.state == OperationState.RUNNING || it.state == OperationState.QUEUED) &&
            !it.id.startsWith("playback_")
        }
        if (active == null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        } else {
            val now = android.os.SystemClock.uptimeMillis()
            if (lastNotificationOperation != active.id || now - lastNotificationAt >= 250L) {
                lastNotificationAt = now
                lastNotificationOperation = active.id
                updateNotification(active)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannel()
        AppOperations.coordinator.addListener(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            val opId = intent.getStringExtra(EXTRA_OP_ID)
            if (opId != null) {
                AppOperations.coordinator.cancel(opId)
            } else {
                AppOperations.coordinator.snapshots().forEach {
                    if (it.state == OperationState.RUNNING || it.state == OperationState.QUEUED) {
                        AppOperations.coordinator.cancel(it.id)
                    }
                }
            }
        }
        val active = AppOperations.coordinator.snapshots().firstOrNull {
            (it.state == OperationState.RUNNING || it.state == OperationState.QUEUED) &&
            !it.id.startsWith("playback_")
        }
        if (active != null) {
            val notification = buildNotification(active)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            // Must satisfy foreground service contract before stopping if started via startForegroundService
            val placeholder = buildFallbackNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, placeholder, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, placeholder)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        AppOperations.coordinator.removeListener(listener)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "File Operations",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress of file transfers, archives, and deletions"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(op: OperationSnapshot): android.app.Notification {
        val clickIntent = Intent(this, MainActivity::class.java).apply {
            this.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingClick = PendingIntent.getActivity(
            this,
            0,
            clickIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, OperationService::class.java).apply {
            action = ACTION_CANCEL
            putExtra(EXTRA_OP_ID, op.id)
        }
        val pendingCancel = PendingIntent.getService(
            this,
            1,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val rate = op.formatRate()
        val rateText = if (rate.isNotEmpty()) " · $rate" else ""
        val contentText = when {
            op.currentItem != null -> "${op.currentItem}$rateText"
            (op.totalBytes ?: 0L) > 0 ->
                "${android.text.format.Formatter.formatShortFileSize(this, op.bytes)} / ${android.text.format.Formatter.formatShortFileSize(this, op.totalBytes ?: 0L)}$rateText"
            op.totalItems > 0 -> "${op.itemsProcessed} / ${op.totalItems} items$rateText"
            else -> "In progress$rateText"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(op.label)
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pendingClick)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", pendingCancel)

        val percent = op.progressPercent
        if (percent >= 0) {
            builder.setProgress(100, percent, false)
        } else {
            builder.setProgress(0, 0, true)
        }

        return builder.build()
    }

    private fun updateNotification(op: OperationSnapshot) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(op))
    }

    private fun buildFallbackNotification(): android.app.Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("File Operations")
            .setContentText("Completing operations")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .build()
    }
}
