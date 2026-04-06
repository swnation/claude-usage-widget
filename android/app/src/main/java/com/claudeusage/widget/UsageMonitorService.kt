package com.claudeusage.widget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import java.util.Timer
import java.util.TimerTask

/**
 * 포그라운드 서비스 - 상단 알림창에 Claude 사용량 상시 표시.
 * 항상 claude.ai에서 실시간으로 가져옴. 로컬 저장 없음.
 */
class UsageMonitorService : Service() {

    companion object {
        const val CHANNEL_ID = "claude_usage_channel"
        const val NOTIFICATION_ID = 1001
        const val ALERT_CHANNEL_ID = "claude_usage_alert"
        const val ACTION_STOP = "com.claudeusage.widget.STOP_SERVICE"
        const val ACTION_REFRESH = "com.claudeusage.widget.REFRESH"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, UsageMonitorService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, UsageMonitorService::class.java))
        }
    }

    private lateinit var webClient: ClaudeWebClient
    private lateinit var notificationManager: NotificationManager
    private var timer: Timer? = null
    private val notifiedModels = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannels()

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        webClient = ClaudeWebClient(prefs.getString("session_key", "") ?: "")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> {
                // 새로고침 버튼 눌렀을 때 즉시 업데이트
                Thread { refreshNotification() }.start()
                return START_STICKY
            }
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
        NotificationChannel(
            CHANNEL_ID, "Claude 사용량", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Claude 플랜 사용량 표시"
            setShowBadge(false)
            notificationManager.createNotificationChannel(this)
        }

        NotificationChannel(
            ALERT_CHANNEL_ID, "Claude 사용량 알림", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "사용량 한도 근접 시 알림"
            enableVibration(true)
            notificationManager.createNotificationChannel(this)
        }
    }

    private fun startPolling() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val intervalMs = (prefs.getString("refresh_interval", "120")?.toLongOrNull() ?: 120) * 1000
        webClient.updateSessionKey(prefs.getString("session_key", "") ?: "")

        timer?.cancel()
        timer = Timer().apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() { refreshNotification() }
            }, 0, intervalMs)
        }
    }

    private fun refreshNotification() {
        val result = webClient.fetchUsage()
        result.onSuccess { usage ->
            notificationManager.notify(NOTIFICATION_ID, buildNotification(usage))
            checkAlerts(usage)
        }.onFailure { error ->
            notificationManager.notify(NOTIFICATION_ID, buildNotification(PlanUsage(error = error.message)))
        }
    }

    private fun buildNotification(usage: PlanUsage?): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // 중지 버튼
        val stopIntent = Intent(this, UsageMonitorService::class.java).apply { action = ACTION_STOP }
        val pendingStop = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // 새로고침 버튼
        val refreshIntent = Intent(this, UsageMonitorService::class.java).apply { action = ACTION_REFRESH }
        val pendingRefresh = PendingIntent.getService(
            this, 2, refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .setContentIntent(pendingOpen)
            .addAction(android.R.drawable.ic_menu_rotate, "새로고침", pendingRefresh)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "중지", pendingStop)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        when {
            usage == null -> {
                builder.setContentTitle("Claude 사용량")
                    .setContentText("불러오는 중...")
            }
            usage.error != null -> {
                builder.setContentTitle("⚠️ Claude 사용량")
                    .setContentText(usage.error)
            }
            else -> {
                val opus = usage.getModel("Opus")
                val progress = opus?.usedPercent?.toInt()?.coerceIn(0, 100) ?: 0

                builder.setContentTitle(usage.notificationTitle())
                    .setContentText(usage.notificationShort())
                    .setStyle(
                        NotificationCompat.BigTextStyle()
                            .bigText(usage.notificationExpanded())
                    )
                    .setProgress(100, progress, false)
            }
        }
        return builder.build()
    }

    private fun checkAlerts(usage: PlanUsage) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (!prefs.getBoolean("alerts_enabled", true)) return

        for (model in usage.models) {
            if (model.usedPercent >= 80 && model.modelName !in notifiedModels) {
                notifiedModels.add(model.modelName)
                sendAlert(model)
            }
        }
    }

    private fun sendAlert(model: ModelUsage) {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, model.modelName.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val emoji = if (model.isAtLimit) "🚨" else "⚠️"
        val title = if (model.isAtLimit) "$emoji ${model.modelName} 한도 도달!"
                    else "$emoji ${model.modelName}: ${model.remaining}개 남음"

        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText("${model.used}/${model.limit} 사용 (${model.percentText})")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        notificationManager.notify(model.modelName.hashCode() + 1000, notification)
    }
}
