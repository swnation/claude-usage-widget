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
import android.webkit.WebResourceError
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
        private const val SCRAPE_TIMEOUT_MS = 30_000L

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
    private val mainHandler = Handler(Looper.getMainLooper())
    private var timer: Timer? = null
    private var scrapeWebView: WebView? = null
    private var isScraping = false
    private var scrapeTimeoutRunnable: Runnable? = null
    private var scrapeDelayedRunnable: Runnable? = null
    private var sessionAlertSent = false
    private var weeklyAlertSent = false
    // 오랑붕쌤 Drive API
    private var isObsFetching = false

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
        scrapeDelayedRunnable?.let { mainHandler.removeCallbacks(it) }
        scrapeDelayedRunnable = null
        try { scrapeWebView?.destroy() } catch (_: Exception) {}
        scrapeWebView = null
        isObsFetching = false
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
                    scrapeDelayedRunnable?.let { mainHandler.removeCallbacks(it) }
                    val runnable = Runnable { scrapeAndUpdate(view) }
                    scrapeDelayedRunnable = runnable
                    mainHandler.postDelayed(runnable, 3000)
                }
                override fun onReceivedError(
                    view: WebView, request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    super.onReceivedError(view, request, error)
                    if (request.isForMainFrame) finishScraping()
                }
            }
        }
        scrapeWebView = wv
        return wv
    }

    private fun fetchUsageInBackground() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val mode = DisplayMode.fromString(prefs.getString("display_mode", null))

        // Claude 스크래핑 (CLAUDE_ONLY 또는 BOTH)
        if (mode != DisplayMode.API_COST_ONLY && !isScraping) {
            val loggedIn = prefs.getBoolean("logged_in", false)
            if (loggedIn) {
                isScraping = true
                startScrapeTimeout()
                try {
                    getOrCreateWebView().loadUrl("https://claude.ai/settings/usage")
                } catch (_: Exception) {
                    finishScraping()
                }
            }
        }

        // 오랑붕쌤 스크래핑 (API_COST_ONLY 또는 BOTH, 로그인된 경우만)
        if (mode != DisplayMode.CLAUDE_ONLY && prefs.getBoolean("obs_logged_in", false)) {
            fetchObsCostInBackground()
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
        scrapeDelayedRunnable?.let { mainHandler.removeCallbacks(it) }
        scrapeDelayedRunnable = null
        isScraping = false
    }

    private fun scrapeAndUpdate(view: WebView?) {
        if (!isScraping) return
        view?.evaluateJavascript("""
            (function() {
                var body = document.body ? document.body.innerText : '';
                var url = window.location.href;
                var sessionIdx = body.indexOf('현재 세션');
                var weeklyIdx = body.indexOf('주간 한도');

                function extract(text) {
                    var pct = text.match(/(\d+)%\s*사용됨/);
                    var reset = text.match(/\d+시간[\s\d]*분?\s*후\s*재설정/) ||
                                text.match(/.{1,20}에\s*재설정/);
                    return { percent: pct ? parseInt(pct[1]) : -1,
                             reset: reset ? reset[0].trim() : '' };
                }

                var session = null, weekly = null;
                if (sessionIdx >= 0) {
                    session = extract(body.substring(sessionIdx,
                        weeklyIdx >= 0 ? weeklyIdx : body.length));
                }
                if (weeklyIdx >= 0) {
                    weekly = extract(body.substring(weeklyIdx));
                }

                var barValues = [];
                document.querySelectorAll('[role="progressbar"], progress, [aria-valuenow]').forEach(function(bar) {
                    barValues.push(bar.getAttribute('aria-valuenow') || bar.value || '');
                });

                return JSON.stringify({ url: url, session: session,
                    weekly: weekly, barValues: barValues });
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

            val sessionObj = try { json?.getAsJsonObject("session") } catch (_: Exception) { null }
            val weeklyObj = try { json?.getAsJsonObject("weekly") } catch (_: Exception) { null }
            val barValues = try { json?.getAsJsonArray("barValues") } catch (_: Exception) { null }

            var sessionPct = sessionObj?.get("percent")?.asInt ?: -1
            var weeklyPct = weeklyObj?.get("percent")?.asInt ?: -1
            val sessionReset = sessionObj?.get("reset")?.asString ?: ""
            val weeklyReset = weeklyObj?.get("reset")?.asString ?: ""

            // fallback: progressbar
            if (sessionPct < 0 && barValues != null && barValues.size() > 0) {
                val v = barValues[0].asString.toIntOrNull()
                if (v != null && v in 0..100) sessionPct = v
            }
            if (weeklyPct < 0 && barValues != null && barValues.size() > 1) {
                val v = barValues[1].asString.toIntOrNull()
                if (v != null && v in 0..100) weeklyPct = v
            }

            if (sessionPct >= 0) {
                val usage = PlanUsage(
                    planName = "Max",
                    session = UsageLimit("현재 세션", sessionPct.toDouble(), sessionReset),
                    weekly = if (weeklyPct >= 0)
                        UsageLimit("주간 한도", weeklyPct.toDouble(), weeklyReset) else null,
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

    // ── 비용 데이터 통합 fetch (Billing 우선, Drive 보조) ──
    private fun fetchObsCostInBackground() {
        if (isObsFetching) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val mode = DisplayMode.fromString(prefs.getString("display_mode", null))
        if (mode == DisplayMode.CLAUDE_ONLY) return

        isObsFetching = true
        Thread {
            try {
                // 1) 오랑붕쌤 추정 데이터 (Drive)
                var estimatedData: ApiCostData? = null
                val token = prefs.getString("google_oauth_token", null)
                if (!token.isNullOrEmpty()) {
                    val driveResult = DriveApiClient.fetchCostFromDrive(token)
                    if (driveResult.tokenExpired) {
                        prefs.edit().putBoolean("obs_logged_in", false)
                            .remove("google_oauth_token").apply()
                    } else {
                        estimatedData = driveResult.costData
                    }
                }

                // 2) Billing API 실제 비용 + 병합
                val anthropicKey = prefs.getString("anthropic_admin_key", null)
                val openaiKey = prefs.getString("openai_admin_key", null)
                val hasBillingKeys = !anthropicKey.isNullOrEmpty() || !openaiKey.isNullOrEmpty()

                // 구독 정보 로드
                val subsJson = prefs.getString("subscriptions", null)
                val subscriptions = if (subsJson != null) {
                    try { Gson().fromJson(subsJson, Array<Subscription>::class.java).toList() }
                    catch (_: Exception) { emptyList() }
                } else emptyList<Subscription>()

                val costData = if (hasBillingKeys) {
                    // Billing API 호출 → 추정치와 병합
                    val merged = BillingApiClient.fetchAndMerge(
                        anthropicKey = anthropicKey,
                        openaiKey = openaiKey,
                        estimatedData = estimatedData,
                        subscriptions = subscriptions,
                    )
                    merged.costData
                } else {
                    // Billing 키 없으면 추정 데이터만 사용
                    estimatedData?.copy(subscriptions = subscriptions)
                }

                if (costData != null) {
                    val costJson = Gson().toJson(costData)
                    prefs.edit().putString("last_api_cost", costJson).apply()

                    mainHandler.post {
                        val usage = loadSavedUsage()
                        notificationManager.notify(NOTIFICATION_ID, buildNotification(usage))
                        UsageWidgetProvider.updateAll(this)
                    }
                }
            } catch (_: Exception) {}
            isObsFetching = false
        }.start()
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

    private fun loadSavedCost(): ApiCostData? {
        val json = PreferenceManager.getDefaultSharedPreferences(this)
            .getString("last_api_cost", null) ?: return null
        return try { Gson().fromJson(json, ApiCostData::class.java) }
        catch (_: Exception) { null }
    }

    private fun buildNotification(usage: PlanUsage?): Notification {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val mode = DisplayMode.fromString(prefs.getString("display_mode", null))
        val cost = loadSavedCost()

        val openPI = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("auto_refresh", true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopPI = PendingIntent.getService(this, 1,
            Intent(this, UsageMonitorService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        // 스킨 강조색 적용
        val skin = try { FloatingOverlay.getAppColors(this) } catch (_: Exception) { null }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .setContentIntent(openPI)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "중지", stopPI)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(skin?.accentColor ?: 0xFFc084fc.toInt())

        when (mode) {
            DisplayMode.CLAUDE_ONLY -> {
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
            }
            DisplayMode.API_COST_ONLY -> {
                if (cost != null) {
                    builder.setContentTitle(cost.notificationTitle())
                        .setContentText(cost.shortText())
                        .setStyle(NotificationCompat.BigTextStyle().bigText(cost.notificationExpanded()))
                } else {
                    builder.setContentTitle("💰 API 요금")
                        .setContentText("오랑붕쌤 데이터 로딩 중...")
                }
            }
            DisplayMode.BOTH -> {
                if (usage != null && usage.error == null) {
                    val sessionPct = usage.session?.usedPercent?.toInt()?.coerceIn(0, 100) ?: 0
                    builder.setContentTitle(usage.combinedNotificationTitle(cost))
                        .setContentText(usage.notificationShort() +
                            (cost?.let { " │ 💰${it.todayText()}" } ?: ""))
                        .setStyle(NotificationCompat.BigTextStyle()
                            .bigText(usage.combinedNotificationExpanded(cost)))
                        .setProgress(100, sessionPct, false)
                } else {
                    builder.setContentTitle("Claude 사용량 + API 요금")
                        .setContentText("데이터 로딩 중...")
                }
            }
        }
        return builder.build()
    }
}
