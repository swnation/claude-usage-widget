package com.claudeusage.widget

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.webkit.*
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
            try { context.startForegroundService(Intent(context, UsageMonitorService::class.java)) }
            catch (_: Exception) {}
        }

        fun stop(context: Context) {
            // 먼저 플래그를 동기적으로 저장
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean("service_running", false).commit()
            context.stopService(Intent(context, UsageMonitorService::class.java))
        }
    }

    private lateinit var notificationManager: NotificationManager
    private val handler = Handler(Looper.getMainLooper())
    private var timer: Timer? = null
    private var scrapeWebView: WebView? = null
    private var sessionAlertSent = false
    private var weeklyAlertSent = false
    private var isScraping = false
    private var isStopping = false

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                isStopping = true
                timer?.cancel()
                timer = null
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
        handler.post {
            try { scrapeWebView?.destroy() } catch (_: Exception) {}
            scrapeWebView = null
        }
        // 의도적 종료가 아닌 경우에만 재시작
        if (!isStopping) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            if (prefs.getBoolean("service_running", false)) {
                try { startForegroundService(Intent(this, UsageMonitorService::class.java)) }
                catch (_: Exception) {}
            }
        }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!isStopping) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            if (prefs.getBoolean("service_running", false)) {
                try { startForegroundService(Intent(this, UsageMonitorService::class.java)) }
                catch (_: Exception) {}
            }
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
                    if (!isStopping) handler.post { scrapeUsage() }
                }
            }, 0, intervalMs.coerceAtLeast(30000))
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun scrapeUsage() {
        if (isScraping || isStopping) return
        isScraping = true

        try {
            scrapeWebView?.destroy()
            scrapeWebView = null

            val wv = WebView(applicationContext).apply {
                visibility = View.GONE
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = LoginActivity.CHROME_UA
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?, request: WebResourceRequest?
                    ): WebResourceResponse? {
                        request?.requestHeaders?.remove("X-Requested-With")
                        return super.shouldInterceptRequest(view, request)
                    }
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        handler.postDelayed({ extractData(view) }, 3000)
                    }
                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?,
                        error: WebResourceError?) {
                        super.onReceivedError(view, request, error)
                        cleanupScrape()
                    }
                }
            }

            scrapeWebView = wv
            wv.loadUrl("https://claude.ai/settings/usage")

            handler.postDelayed({
                if (isScraping) cleanupScrape()
            }, 30000)
        } catch (_: Exception) {
            cleanupScrape()
            val usage = loadSavedUsage()
            if (usage != null) notificationManager.notify(NOTIFICATION_ID, buildNotification(usage))
        }
    }

    private fun extractData(view: WebView?) {
        view?.evaluateJavascript("""
            (function() {
                const body = document.body ? document.body.innerText : '';
                const percentMatches = body.match(/(\d+)%\s*사용됨/g) || [];
                const resetMatches = body.match(/[\d시간분\s]+후\s*재설정/g) ||
                                     body.match(/[가-힣]+\s+\d+:\d+\s+[가-힣]+에\s*재설정/g) || [];
                const barValues = [];
                document.querySelectorAll('[role="progressbar"], progress, [aria-valuenow]').forEach(function(bar) {
                    barValues.push(bar.getAttribute('aria-valuenow') || bar.value || '');
                });
                return JSON.stringify({
                    url: window.location.href,
                    percentMatches: percentMatches,
                    resetMatches: resetMatches,
                    barValues: barValues
                });
            })();
        """.trimIndent()) { result -> handleResult(result) }
    }

    private fun handleResult(jsResult: String?) {
        try {
            val raw = jsResult?.trim()
                ?.removeSurrounding("\"")
                ?.replace("\\\"", "\"")
                ?.replace("\\\\", "\\")
                ?.replace("\\/", "/")
                ?: "{}"

            val gson = Gson()
            val json = gson.fromJson(raw, com.google.gson.JsonObject::class.java)

            val percents = mutableListOf<Int>()
            json?.getAsJsonArray("percentMatches")?.forEach {
                val match = Regex("(\\d+)%").find(it.asString)
                if (match != null) percents.add(match.groupValues[1].toInt())
            }

            if (percents.isEmpty()) {
                try { json?.getAsJsonArray("barValues")?.forEach {
                    val v = it.asString.toIntOrNull()
                    if (v != null && v in 0..100) percents.add(v)
                } } catch (_: Exception) {}
            }

            val resets = mutableListOf<String>()
            try { json?.getAsJsonArray("resetMatches")?.forEach {
                resets.add(it.asString)
            } } catch (_: Exception) {}

            if (percents.isNotEmpty()) {
                val usage = PlanUsage(
                    planName = "Max",
                    session = UsageLimit("현재 세션", percents[0].toDouble(), resets.getOrNull(0) ?: ""),
                    weekly = if (percents.size > 1)
                        UsageLimit("주간 한도", percents[1].toDouble(), resets.getOrNull(1) ?: "") else null,
                    lastUpdated = java.time.Instant.now().toString(),
                )

                PreferenceManager.getDefaultSharedPreferences(this).edit()
                    .putString("last_usage", gson.toJson(usage)).apply()

                if (!isStopping) {
                    notificationManager.notify(NOTIFICATION_ID, buildNotification(usage))
                    checkAlerts(usage)
                }
                try { UsageWidgetProvider.updateAll(this) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        cleanupScrape()
    }

    private fun cleanupScrape() {
        isScraping = false
        try {
            scrapeWebView?.stopLoading()
            scrapeWebView?.destroy()
        } catch (_: Exception) {}
        scrapeWebView = null
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
                    .setContentText("불러오는 중...")
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
