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

/**
 * 포그라운드 서비스 — 상단 알림에 Claude 사용량 표시.
 * WebView 없음 — SharedPreferences의 last_usage만 읽어서 알림 갱신.
 * 데이터 갱신은 앱(MainActivity)의 onResume에서 자동 수행.
 */
class UsageMonitorService : Service() {

    companion object {
        const val CHANNEL_ID = "claude_usage_channel"
        const val NOTIFICATION_ID = 1001
        const val ALERT_CHANNEL_ID = "claude_usage_alert"
        const val ALERT_NOTIFICATION_ID = 2001
        const val ACTION_STOP = "com.claudeusage.widget.STOP_SERVICE"
        const val ACTION_NOTIFY_UPDATE = "com.claudeusage.widget.NOTIFY_UPDATE"

        fun start(context: Context) {
            try {
                PreferenceManager.getDefaultSharedPreferences(context).edit()
                    .putBoolean("service_running", true).commit()
                context.startForegroundService(Intent(context, UsageMonitorService::class.java))
            } catch (_: Exception) {}
        }

        fun stop(context: Context) {
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean("service_running", false).commit()
            try { context.stopService(Intent(context, UsageMonitorService::class.java)) }
            catch (_: Exception) {}
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
                PreferenceManager.getDefaultSharedPreferences(this).edit()
                    .putBoolean("service_running", false).commit()
                notificationManager.cancel(NOTIFICATION_ID)
                stopForeground(STOP_FOREGROUND_REMOVE)
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
        try {
            startForeground(NOTIFICATION_ID, buildNotification(loadSavedUsage()))
        } catch (_: Exception) {}
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
            }, intervalMs.coerceAtLeast(30000), intervalMs.coerceAtLeast(30000))
        }
    }

    private fun checkAlerts(usage: PlanUsage?) {
        if (usage == null) return
        usage.session?.let {
            if (it.usedPercent >= 80 && !sessionAlertSent) {
                sessionAlertSent = true
                sendAlert("세션 ${it.percentText} 사용됨", "한도에 가까워지고 있습니다.")
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
