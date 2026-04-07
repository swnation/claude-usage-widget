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

/**
 * 포그라운드 서비스 — 상단 알림에 Claude 사용량 표시.
 * 숨겨진 WebView로 주기적으로 사용량 페이지를 스크래핑하여 알림 갱신.
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

    private lateinit var notificationManager: NotificationManager
    private val handler = Handler(Looper.getMainLooper())
    private var timer: Timer? = null
    private var scrapeWebView: WebView? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification(loadSavedUsage()))
        startPolling()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        timer?.cancel()
        timer = null
        handler.post {
            scrapeWebView?.destroy()
            scrapeWebView = null
        }
        super.onDestroy()
    }

    private fun createNotificationChannels() {
        NotificationChannel(CHANNEL_ID, "Claude 사용량", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Claude 플랜 사용량 표시"
            setShowBadge(false)
            notificationManager.createNotificationChannel(this)
        }
        NotificationChannel(ALERT_CHANNEL_ID, "Claude 사용량 알림", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "사용량 한도 근접 시 알림"
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
                    handler.post { scrapeUsage() }
                }
            }, 0, intervalMs)
        }
    }

    /**
     * 숨겨진 WebView로 사용량 페이지를 스크래핑.
     * 메인 스레드에서 실행되어야 함 (handler.post로 호출).
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun scrapeUsage() {
        // 기존 WebView 정리
        scrapeWebView?.destroy()

        val wv = WebView(this).apply {
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
                    handler.postDelayed({ extractUsageData(view) }, 3000)
                }
            }
        }

        scrapeWebView = wv
        wv.loadUrl("https://claude.ai/settings/usage")
    }

    private fun extractUsageData(view: WebView?) {
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
        """.trimIndent()) { result ->
            handleScrapeResult(result)
            // WebView 정리
            scrapeWebView?.destroy()
            scrapeWebView = null
        }
    }

    private fun handleScrapeResult(jsResult: String?) {
        val raw = jsResult?.trim()
            ?.removeSurrounding("\"")
            ?.replace("\\\"", "\"")
            ?.replace("\\\\", "\\")
            ?.replace("\\/", "/")
            ?.replace("\\n", "\n")
            ?: "{}"

        try {
            val gson = Gson()
            val json = gson.fromJson(raw, com.google.gson.JsonObject::class.java)

            val percents = mutableListOf<Int>()
            val percentMatches = json?.getAsJsonArray("percentMatches")
            percentMatches?.forEach {
                val match = Regex("(\\d+)%").find(it.asString)
                if (match != null) percents.add(match.groupValues[1].toInt())
            }

            // barValues에서도 시도
            if (percents.isEmpty()) {
                try {
                    json?.getAsJsonArray("barValues")?.forEach {
                        val v = it.asString.toIntOrNull()
                        if (v != null && v in 0..100) percents.add(v)
                    }
                } catch (_: Exception) {}
            }

            val resets = mutableListOf<String>()
            try {
                json?.getAsJsonArray("resetMatches")?.forEach { resets.add(it.asString) }
            } catch (_: Exception) {}

            if (percents.isNotEmpty()) {
                val usage = PlanUsage(
                    planName = "Max",
                    session = UsageLimit("현재 세션", percents[0].toDouble(), resets.getOrNull(0) ?: ""),
                    weekly = if (percents.size > 1)
                        UsageLimit("주간 한도", percents[1].toDouble(), resets.getOrNull(1) ?: "") else null,
                    lastUpdated = java.time.Instant.now().toString(),
                )

                // SharedPreferences에 저장 (앱에서도 읽을 수 있도록)
                PreferenceManager.getDefaultSharedPreferences(this).edit()
                    .putString("last_usage", gson.toJson(usage))
                    .apply()

                notificationManager.notify(NOTIFICATION_ID, buildNotification(usage))
            }
        } catch (_: Exception) {}
    }

    private fun loadSavedUsage(): PlanUsage? {
        val json = PreferenceManager.getDefaultSharedPreferences(this)
            .getString("last_usage", null) ?: return null
        return try { Gson().fromJson(json, PlanUsage::class.java) }
        catch (_: Exception) { null }
    }

    private fun buildNotification(usage: PlanUsage?): Notification {
        val openPI = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
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
                val progress = usage.session?.usedPercent?.toInt()?.coerceIn(0, 100) ?: 0
                builder.setContentTitle(usage.notificationTitle())
                    .setContentText(usage.notificationShort())
                    .setStyle(NotificationCompat.BigTextStyle().bigText(usage.notificationExpanded()))
                    .setProgress(100, progress, false)
            }
        }
        return builder.build()
    }
}
