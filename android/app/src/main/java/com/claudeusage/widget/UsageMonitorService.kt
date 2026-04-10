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
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import com.google.gson.Gson
import com.google.gson.JsonObject
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

    private companion object Timeout {
        const val SCRAPE_TIMEOUT_MS = 30_000L  // 30초 타임아웃
    }

    private lateinit var notificationManager: NotificationManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var timer: Timer? = null
    private var scrapeWebView: WebView? = null
    private var isScraping = false
    private var scrapeTimeoutRunnable: Runnable? = null
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
        cancelScrapeTimeout()
        try { scrapeWebView?.destroy() } catch (_: Exception) {}
        scrapeWebView = null
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
                    mainHandler.post { fetchUsageInBackground() }
                }
            }, intervalMs.coerceAtLeast(30000), intervalMs.coerceAtLeast(30000))
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun getOrCreateWebView(): WebView {
        scrapeWebView?.let { return it }
        val wv = WebView(this).apply {
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
                    mainHandler.postDelayed({ scrapeAndUpdate(view) }, 3000)
                }
                override fun onReceivedError(view: WebView?, errorCode: Int,
                    description: String?, failingUrl: String?) {
                    super.onReceivedError(view, errorCode, description, failingUrl)
                    finishScraping()
                }
            }
        }
        scrapeWebView = wv
        return wv
    }

    private fun fetchUsageInBackground() {
        if (isScraping) return
        val loggedIn = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean("logged_in", false)
        if (!loggedIn) return

        isScraping = true
        startScrapeTimeout()
        try {
            getOrCreateWebView().loadUrl("https://claude.ai/settings/usage")
        } catch (_: Exception) {
            finishScraping()
        }
    }

    private fun startScrapeTimeout() {
        cancelScrapeTimeout()
        scrapeTimeoutRunnable = Runnable { finishScraping() }
        mainHandler.postDelayed(scrapeTimeoutRunnable!!, SCRAPE_TIMEOUT_MS)
    }

    private fun cancelScrapeTimeout() {
        scrapeTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        scrapeTimeoutRunnable = null
    }

    private fun finishScraping() {
        cancelScrapeTimeout()
        isScraping = false
    }

    private fun scrapeAndUpdate(view: WebView?) {
        view?.evaluateJavascript("""
            (function() {
                var body = document.body ? document.body.innerText : '';
                var percentMatches = body.match(/(\d+)%\s*사용됨/g) || [];
                var allResets = [];
                var r1 = body.match(/\d+시간[\s\d]*분?\s*후\s*재설정/g);
                if (r1) r1.forEach(function(m) { allResets.push(m); });
                var r2 = body.match(/.{1,20}에\s*재설정/g);
                if (r2) r2.forEach(function(m) {
                    var t = m.trim();
                    if (t.length > 3 && allResets.indexOf(t) === -1 && !t.match(/^\d+시간/)) {
                        allResets.push(t);
                    }
                });
                var barValues = [];
                document.querySelectorAll('[role="progressbar"], progress, [aria-valuenow]').forEach(function(bar) {
                    barValues.push(bar.getAttribute('aria-valuenow') || bar.value || '');
                });
                return JSON.stringify({
                    url: window.location.href,
                    percentMatches: percentMatches,
                    resetMatches: allResets,
                    barValues: barValues
                });
            })();
        """.trimIndent()) { result -> handleScrapeResult(result) }
    }

    private fun handleScrapeResult(jsResult: String?) {
        try {
            val raw = jsResult?.trim()
                ?.removeSurrounding("\"")
                ?.replace("\\\"", "\"")
                ?.replace("\\\\", "\\")
                ?.replace("\\/", "/")
                ?.replace("\\n", "\n")
                ?: "{}"
            val gson = Gson()
            val json = gson.fromJson(raw, JsonObject::class.java)
            val percentMatches = json?.getAsJsonArray("percentMatches")
            val resetMatches = try { json?.getAsJsonArray("resetMatches") } catch (_: Exception) { null }
            val barValues = try { json?.getAsJsonArray("barValues") } catch (_: Exception) { null }

            val percents = mutableListOf<Int>()
            percentMatches?.forEach {
                val match = Regex("(\\d+)%").find(it.asString)
                if (match != null) percents.add(match.groupValues[1].toInt())
            }
            val resets = mutableListOf<String>()
            resetMatches?.forEach { resets.add(it.asString) }

            if (percents.isEmpty() && barValues != null) {
                barValues.forEach {
                    val v = it.asString.toIntOrNull()
                    if (v != null && v in 0..100) percents.add(v)
                }
            }

            if (percents.isNotEmpty()) {
                val usage = PlanUsage(
                    planName = "Max",
                    session = UsageLimit("현재 세션", percents[0].toDouble(), resets.getOrNull(0) ?: ""),
                    weekly = if (percents.size > 1)
                        UsageLimit("주간 한도", percents[1].toDouble(), resets.getOrNull(1) ?: "") else null,
                    lastUpdated = java.time.Instant.now().toString(),
                )
                val usageJson = gson.toJson(usage)
                PreferenceManager.getDefaultSharedPreferences(this).edit()
                    .putString("last_usage", usageJson).apply()
                notificationManager.notify(NOTIFICATION_ID, buildNotification(usage))
                checkAlerts(usage)
                UsageWidgetProvider.updateAll(this)
            }
        } catch (_: Exception) {}

        finishScraping()
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
                builder.setContentTitle(usage.notificationTitle())
                    .setContentText(usage.notificationShort())
                    .setStyle(NotificationCompat.BigTextStyle().bigText(usage.notificationExpanded()))
                    .setProgress(100, sessionPct, false)
            }
        }
        return builder.build()
    }
}
