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
 * 포그라운드 서비스 - 상단 알림창에 Claude 사용량을 상시 표시.
 * claude.ai에서 직접 가져오며 서버 필요 없음.
 */
class UsageMonitorService : Service() {

    companion object {
        const val CHANNEL_ID = "claude_usage_channel"
        const val NOTIFICATION_ID = 1001
        const val ALERT_CHANNEL_ID = "claude_usage_alert"
        const val ACTION_STOP = "com.claudeusage.widget.STOP_SERVICE"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, UsageMonitorService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, UsageMonitorService::class.java))
        }
    }

    private lateinit var webClient: ClaudeWebClient
    private lateinit var accumulator: UsageAccumulator
    private lateinit var notificationManager: NotificationManager
    private var timer: Timer? = null
    private val notifiedModels = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannels()

        accumulator = UsageAccumulator(this)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val sessionKey = prefs.getString("session_key", "") ?: ""
        webClient = ClaudeWebClient(sessionKey)
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
            // 누적기 업데이트 + 주간/총 데이터 병합
            val enriched = enrichWithAccumulated(usage)
            notificationManager.notify(NOTIFICATION_ID, buildNotification(enriched))
            checkAlerts(enriched)
        }.onFailure { error ->
            val errorUsage = PlanUsage(error = error.message)
            notificationManager.notify(NOTIFICATION_ID, buildNotification(errorUsage))
        }
    }

    private fun enrichWithAccumulated(usage: PlanUsage): PlanUsage {
        val enrichedModels = usage.models.map { model ->
            accumulator.update(model.modelName, model.used)
            val acc = accumulator.getAccumulated(model.modelName, model.used)
            model.copy(
                weeklyUsed = acc.weeklyMessages,
                totalUsed = acc.totalMessages,
            )
        }
        return usage.copy(models = enrichedModels)
    }

    private fun buildNotification(usage: PlanUsage?): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = Intent(this, UsageMonitorService::class.java).apply { action = ACTION_STOP }
        val pendingStop = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .setContentIntent(pendingOpen)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "중지", pendingStop)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        when {
            usage == null -> {
                builder.setContentTitle("Claude 사용량")
                    .setContentText("시작 중...")
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
        val title = if (model.isAtLimit) {
            "$emoji ${model.modelName} 한도 도달!"
        } else {
            "$emoji ${model.modelName}: ${model.remaining}개 남음"
        }

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
