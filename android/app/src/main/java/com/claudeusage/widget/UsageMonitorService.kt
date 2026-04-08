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
import com.google.gson.Gson
import java.util.Timer
import java.util.TimerTask

class UsageMonitorService : Service() {

    companion object {
        const val CHANNEL_ID = "claude_usage_channel"
        const val NOTIFICATION_ID = 1001
        const val ALERT_CHANNEL_ID = "claude_usage_alert"
        const val ALERT_NOTIFICATION_ID = 2001
        const val ACTION_STOP = "com.claudeusage.widget.STOP_SERVICE"
        const val ACTION_NOTIFY_UPDATE = "com.claudeusage.widget.NOTIFY_UPDATE"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, UsageMonitorService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, UsageMonitorService::class.java))
        }
    }

    private lateinit var notificationManager: NotificationManager
    private var timer: Timer? = null
    private var sessionAlertSent = false
    private var weeklyAlertSent = false

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // 서비스 상태 저장
                PreferenceManager.getDefaultSharedPreferences(this).edit()
                    .putBoolean("service_running", false).apply()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_NOTIFY_UPDATE -> {
                val usage = loadSavedUsage()
                notificationManager.notify(NOTIFICATION_ID, buildNotification(usage))
                checkAlerts(usage)
                return START_STICKY
            }
        }
        startForeground(NOTIFICATION_ID, buildNotification(loadSavedUsage()))
        startPolling()
        return START_STICKY  // 시스템이 서비스를 종료해도 자동 재시작
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        timer?.cancel()
        timer = null
        // 서비스가 예기치 않게 종료되면 재시작
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (prefs.getBoolean("service_running", false)) {
            // 시스템이 죽인 경우 → 재시작 예약
            try {
                val restartIntent = Intent(this, UsageMonitorService::class.java)
                startForegroundService(restartIntent)
            } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 앱이 최근 앱에서 제거되어도 서비스 유지
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (prefs.getBoolean("service_running", false)) {
            try {
                val restartIntent = Intent(this, UsageMonitorService::class.java)
                startForegroundService(restartIntent)
            } catch (_: Exception) {}
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun createNotificationChannels() {
        NotificationChannel(CHANNEL_ID, "Claude 사용량", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Claude 플랜 사용량 표시"
            setShowBadge(false)
            notificationManager.createNotificationChannel(this)
        }
        NotificationChannel(ALERT_CHANNEL_ID, "Claude 사용량 경고", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "사용량 한도 근접 시 경고"
            enableVibration(true)
            notificationManager.createNotificationChannel(this)
        }
    }

    private fun startPolling() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val intervalMs = (prefs.getString("refresh_interval", "120")?.toLongOrNull() ?: 120) * 1000

        timer?.cancel()
        timer = Timer().apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    val usage = loadSavedUsage()
                    notificationManager.notify(NOTIFICATION_ID, buildNotification(usage))
                    checkAlerts(usage)
                }
            }, intervalMs, intervalMs)
        }
    }

    private fun checkAlerts(usage: PlanUsage?) {
        if (usage == null) return

        usage.session?.let {
            if (it.usedPercent >= 80 && !sessionAlertSent) {
                sessionAlertSent = true
                sendAlert("세션 ${it.percentText} 사용됨", "한도에 가까워지고 있습니다. ${it.resetTimeText()}")
            }
            if (it.usedPercent < 20) sessionAlertSent = false
        }

        usage.weekly?.let {
            if (it.usedPercent >= 80 && !weeklyAlertSent) {
                weeklyAlertSent = true
                sendAlert("주간 한도 ${it.percentText} 사용됨", "주간 사용량이 한도에 가까워지고 있습니다.")
            }
            if (it.usedPercent < 20) weeklyAlertSent = false
        }

        if (usage.error != null && usage.error.contains("만료")) {
            sendAlert("세션 만료", "다시 로그인이 필요합니다.")
        }
    }

    private fun sendAlert(title: String, text: String) {
        val openPI = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("⚠️ $title")
            .setContentText(text)
            .setContentIntent(openPI)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        notificationManager.notify(ALERT_NOTIFICATION_ID, notification)
    }

    private fun loadSavedUsage(): PlanUsage? {
        val json = PreferenceManager.getDefaultSharedPreferences(this)
            .getString("last_usage", null) ?: return null
        return try { Gson().fromJson(json, PlanUsage::class.java) }
        catch (_: Exception) { null }
    }

    private fun buildNotification(usage: PlanUsage?): Notification {
        val openPI = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("auto_refresh", true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val stopPI = PendingIntent.getService(this, 1,
            Intent(this, UsageMonitorService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .setContentIntent(openPI)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "중지", stopPI)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        when {
            usage == null -> {
                builder.setContentTitle("Claude 사용량")
                    .setContentText("앱에서 새로고침하세요")
            }
            usage.error != null -> {
                builder.setContentTitle("⚠️ Claude 사용량")
                    .setContentText(usage.error)
            }
            else -> {
                val sessionPct = usage.session?.usedPercent?.toInt()?.coerceIn(0, 100) ?: 0
                val colorRes = when {
                    sessionPct >= 90 -> android.R.color.holo_red_light
                    sessionPct >= 70 -> android.R.color.holo_orange_light
                    else -> android.R.color.holo_green_light
                }

                builder.setContentTitle(usage.notificationTitle())
                    .setContentText(usage.notificationShort())
                    .setStyle(NotificationCompat.BigTextStyle().bigText(usage.notificationExpanded()))
                    .setProgress(100, sessionPct, false)
                    .setColor(getColor(colorRes))
                    .setColorized(true)
            }
        }
        return builder.build()
    }
}
