package com.claudeusage.widget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import java.util.Timer
import java.util.TimerTask

class UsageMonitorService : Service() {

    companion object {
        const val CHANNEL_ID = "claude_usage_channel"
        const val NOTIFICATION_ID = 1001
        const val ALERT_CHANNEL_ID = "claude_usage_alert"
        const val ACTION_STOP = "com.claudeusage.widget.STOP_SERVICE"

        fun start(context: Context) {
            val intent = Intent(context, UsageMonitorService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, UsageMonitorService::class.java)
            context.stopService(intent)
        }
    }

    private lateinit var apiClient: UsageApiClient
    private lateinit var notificationManager: NotificationManager
    private var timer: Timer? = null
    private var lastUsageData: UsageData? = null
    private val notifiedThresholds = mutableSetOf<Int>()

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannels()

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val serverUrl = prefs.getString("server_url", "http://10.0.2.2:8490") ?: "http://10.0.2.2:8490"
        apiClient = UsageApiClient(serverUrl)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification(null))
        startPolling()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        timer?.cancel()
        timer = null
        super.onDestroy()
    }

    private fun createNotificationChannels() {
        // Persistent status channel (silent)
        val statusChannel = NotificationChannel(
            CHANNEL_ID,
            "Claude Usage Status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows current Claude API usage"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(statusChannel)

        // Alert channel (with sound)
        val alertChannel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "Claude Usage Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts when usage thresholds are reached"
            enableVibration(true)
        }
        notificationManager.createNotificationChannel(alertChannel)
    }

    private fun startPolling() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val intervalMs = (prefs.getString("refresh_interval", "60")?.toLongOrNull() ?: 60) * 1000

        // Update server URL in case it changed
        val serverUrl = prefs.getString("server_url", "http://10.0.2.2:8490") ?: "http://10.0.2.2:8490"
        apiClient.updateServerUrl(serverUrl)

        timer?.cancel()
        timer = Timer().apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    fetchAndUpdate()
                }
            }, 0, intervalMs)
        }
    }

    private fun fetchAndUpdate() {
        val result = apiClient.fetchUsage()
        result.onSuccess { data ->
            lastUsageData = data
            notificationManager.notify(NOTIFICATION_ID, buildNotification(data))
            checkAlertThresholds(data)
        }.onFailure {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(null))
        }
    }

    private fun buildNotification(data: UsageData?): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, UsageMonitorService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .setContentIntent(pendingOpen)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", pendingStop)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (data != null) {
            val percent = data.budgetUsedPercent
            val emoji = when {
                percent >= 90 -> "🔴"
                percent >= 75 -> "🟡"
                else -> "🟢"
            }

            builder.setContentTitle("$emoji Claude: ${data.shortSummary()}")
                .setContentText("5h: ${data.periodSummary("5h")} │ Today: ${data.periodSummary("daily")}")
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(buildString {
                            append("💰 Monthly: ${data.costSummary()}\n")
                            append("📊 Tokens: ${data.tokenSummary()}\n")
                            append("─────────────────\n")
                            append("⏱ 5h: ${data.periodSummary("5h")}\n")
                            append("📅 Today: ${data.periodSummary("daily")}\n")
                            append("📆 Week: ${data.periodSummary("weekly")}\n")
                            append("📋 Month: ${data.periodSummary("monthly")}")
                        })
                )
                .setProgress(100, percent.toInt().coerceIn(0, 100), false)
        } else {
            builder.setContentTitle("Claude Usage Widget")
                .setContentText("Connecting to server...")
        }

        return builder.build()
    }

    private fun checkAlertThresholds(data: UsageData) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val alertsEnabled = prefs.getBoolean("alerts_enabled", true)
        if (!alertsEnabled) return

        val thresholds = listOf(50, 75, 90, 95)
        val percent = data.budgetUsedPercent

        for (threshold in thresholds) {
            if (percent >= threshold && threshold !in notifiedThresholds) {
                notifiedThresholds.add(threshold)
                sendAlertNotification(threshold, data)
            }
        }
    }

    private fun sendAlertNotification(threshold: Int, data: UsageData) {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, threshold, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val emoji = if (threshold >= 90) "🚨" else "⚠️"

        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("$emoji Claude Usage: ${threshold}% reached!")
            .setContentText("Cost: ${data.costSummary()}")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Budget ${data.budgetUsedPercent}% used\nCost: ${data.costSummary()}\nTokens: ${data.tokenSummary()}")
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        notificationManager.notify(threshold, notification)
    }
}
