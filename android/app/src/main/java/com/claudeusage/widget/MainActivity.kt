package com.claudeusage.widget

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager

class MainActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var isServiceRunning = false
    private var scrapeWebView: WebView? = null
    private var autoRefreshRunnable: Runnable? = null
    private var floatingOverlay: FloatingOverlay? = null

    private lateinit var statusText: TextView
    private lateinit var planNameText: TextView
    private lateinit var usageContainer: LinearLayout
    private lateinit var toggleButton: Button
    private lateinit var refreshButton: Button
    private lateinit var overlayButton: Button
    private lateinit var loginButton: Button
    private lateinit var logoutButton: Button
    private lateinit var loginStatus: TextView
    private lateinit var refreshInput: EditText
    private lateinit var saveButton: Button

    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == LoginActivity.RESULT_LOGGED_IN) {
            updateLoginUI(true)
            fetchUsageViaScraping()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initViews()
        loadSettings()

        // 첫 실행 시 권한 설정
        if (PermissionSetup.isFirstRun(this)) {
            PermissionSetup(this).checkAndRequest {
                PermissionSetup.markSetupDone(this)
                handleAutoRefreshIntent(intent)
                AppUpdater(this).checkForUpdate()
            }
        } else {
            handleAutoRefreshIntent(intent)
            AppUpdater(this).checkForUpdate()
        }
    }

    override fun onResume() {
        super.onResume()
        val loggedIn = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean("logged_in", false)
        if (loggedIn) {
            fetchUsageViaScraping()
            startAutoRefresh()
        }
        // 프로세스 재시작 후 오버레이 복원
        floatingOverlay = FloatingOverlay.getInstance(applicationContext)
        if (FloatingOverlay.wasShowing(this) && !floatingOverlay!!.isShowing()
            && FloatingOverlay.hasPermission(this)) {
            floatingOverlay!!.show()
        }
        updateOverlayButton()
    }

    override fun onPause() {
        super.onPause()
        stopAutoRefresh()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleAutoRefreshIntent(intent)
    }

    private fun handleAutoRefreshIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("auto_refresh", false) == true) {
            intent.removeExtra("auto_refresh")
            val loggedIn = PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean("logged_in", false)
            if (loggedIn) fetchUsageViaScraping()
        }
    }

    @Deprecated("Use OnBackPressedCallback")
    override fun onBackPressed() { moveTaskToBack(true) }

    override fun onDestroy() {
        stopAutoRefresh()
        scrapeWebView?.destroy()
        super.onDestroy()
    }

    private fun initViews() {
        statusText = findViewById(R.id.statusText)
        planNameText = findViewById(R.id.planNameText)
        usageContainer = findViewById(R.id.modelsContainer)
        toggleButton = findViewById(R.id.toggleButton)
        refreshButton = findViewById(R.id.refreshButton)
        overlayButton = findViewById(R.id.overlayButton)
        loginButton = findViewById(R.id.loginButton)
        logoutButton = findViewById(R.id.logoutButton)
        loginStatus = findViewById(R.id.loginStatus)
        refreshInput = findViewById(R.id.refreshInput)
        saveButton = findViewById(R.id.saveButton)

        loginButton.setOnClickListener {
            loginLauncher.launch(Intent(this, LoginActivity::class.java))
        }
        logoutButton.setOnClickListener {
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                .remove("session_key").remove("logged_in").remove("last_usage").apply()
            CookieManager.getInstance().removeAllCookies(null)
            updateLoginUI(false)
            if (isServiceRunning) {
                UsageMonitorService.stop(this)
                isServiceRunning = false
                toggleButton.text = "모니터링 시작"
                saveServiceState()
            }
            usageContainer.removeAllViews()
            planNameText.text = ""
            statusText.text = "로그아웃됨"
        }
        toggleButton.setOnClickListener {
            val loggedIn = PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean("logged_in", false)
            if (!loggedIn) {
                Toast.makeText(this, "먼저 로그인하세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (isServiceRunning) {
                UsageMonitorService.stop(this)
                isServiceRunning = false
                toggleButton.text = "모니터링 시작"
                statusText.text = "서비스 중지됨"
            } else {
                fetchUsageViaScraping()
                UsageMonitorService.start(this)
                isServiceRunning = true
                toggleButton.text = "모니터링 중지"
                statusText.text = "서비스 실행 중"
            }
            saveServiceState()
        }
        refreshButton.setOnClickListener { fetchUsageViaScraping() }
        overlayButton.setOnClickListener {
            floatingOverlay = FloatingOverlay.getInstance(applicationContext)
            if (floatingOverlay!!.isShowing()) {
                floatingOverlay!!.hide()
            } else {
                if (!FloatingOverlay.hasPermission(this)) {
                    FloatingOverlay.requestPermission(this)
                    Toast.makeText(this, "권한 허용 후 다시 눌러주세요", Toast.LENGTH_SHORT).show()
                } else {
                    floatingOverlay!!.show()
                }
            }
            updateOverlayButton()
        }
        saveButton.setOnClickListener {
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putString("refresh_interval", refreshInput.text.toString().trim()).apply()
            Toast.makeText(this, "저장됨", Toast.LENGTH_SHORT).show()
            startAutoRefresh()
        }
    }

    private fun updateOverlayButton() {
        val showing = floatingOverlay?.isShowing() == true || FloatingOverlay.wasShowing(this)
        overlayButton.text = if (showing) "플로팅 오버레이 끄기" else "플로팅 오버레이 켜기"
    }

    private fun loadSettings() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        refreshInput.setText(prefs.getString("refresh_interval", "120"))
        val loggedIn = prefs.getBoolean("logged_in", false)
        updateLoginUI(loggedIn)
        isServiceRunning = prefs.getBoolean("service_running", false)
        toggleButton.text = if (isServiceRunning) "모니터링 중지" else "모니터링 시작"
        val lastUsage = prefs.getString("last_usage", null)
        if (lastUsage != null) displayUsageFromJson(lastUsage)
    }

    private fun startAutoRefresh() {
        stopAutoRefresh()
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val intervalMs = (prefs.getString("refresh_interval", "120")?.toLongOrNull() ?: 120) * 1000
        if (intervalMs < 30000) return
        autoRefreshRunnable = object : Runnable {
            override fun run() {
                if (PreferenceManager.getDefaultSharedPreferences(this@MainActivity)
                        .getBoolean("logged_in", false)) fetchUsageViaScraping()
                handler.postDelayed(this, intervalMs)
            }
        }
        handler.postDelayed(autoRefreshRunnable!!, intervalMs)
    }

    private fun stopAutoRefresh() {
        autoRefreshRunnable?.let { handler.removeCallbacks(it) }
        autoRefreshRunnable = null
    }

    private fun updateLoginUI(loggedIn: Boolean) {
        if (loggedIn) {
            loginButton.visibility = View.GONE
            logoutButton.visibility = View.VISIBLE
            loginStatus.text = "✓ 로그인됨"
            loginStatus.setTextColor(getColor(android.R.color.holo_green_light))
        } else {
            loginButton.visibility = View.VISIBLE
            logoutButton.visibility = View.GONE
            loginStatus.text = "로그인이 필요합니다"
            loginStatus.setTextColor(getColor(android.R.color.holo_red_light))
        }
    }

    private fun saveServiceState() {
        PreferenceManager.getDefaultSharedPreferences(this).edit()
            .putBoolean("service_running", isServiceRunning).apply()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun fetchUsageViaScraping() {
        statusText.text = "불러오는 중..."
        try {
            scrapeWebView?.let {
                (it.parent as? android.view.ViewGroup)?.removeView(it)
                it.destroy()
            }
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
                        handler.postDelayed({ scrapeUsagePage(view) }, 3000)
                    }
                }
            }
            scrapeWebView = wv
            (findViewById<View>(android.R.id.content) as? android.view.ViewGroup)?.addView(
                wv, ViewGroup.LayoutParams(400, 800))
            wv.loadUrl("https://claude.ai/settings/usage")
        } catch (e: Exception) {
            statusText.text = "스크래핑 오류: ${e.message}"
        }
    }

    private fun scrapeUsagePage(view: WebView?) {
        view?.evaluateJavascript("""
            (function() {
                const body = document.body ? document.body.innerText : '';
                const percentMatches = body.match(/(\d+)%\s*사용됨/g) || [];
                const allResets = [];
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
        val raw = jsResult?.trim()
            ?.removeSurrounding("\"")
            ?.replace("\\\"", "\"")
            ?.replace("\\\\", "\\")
            ?.replace("\\/", "/")
            ?.replace("\\n", "\n")
            ?: "{}"
        try {
            val gson = com.google.gson.Gson()
            val json = gson.fromJson(raw, com.google.gson.JsonObject::class.java)
            val url = json?.get("url")?.asString ?: ""
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
                displayUsage(usage)
                saveUsageData(usage)
                statusText.text = "마지막 업데이트: ${java.time.LocalTime.now().toString().take(5)}"
            } else {
                if (url.contains("/login")) {
                    statusText.text = "세션 만료. 다시 로그인하세요."
                    updateLoginUI(false)
                } else {
                    statusText.text = "사용량을 찾을 수 없음. 새로고침을 다시 눌러주세요."
                }
            }
        } catch (e: Exception) {
            statusText.text = "오류: ${e.message}"
        }
        try { scrapeWebView?.let {
            (it.parent as? android.view.ViewGroup)?.removeView(it)
            it.destroy()
        } } catch (_: Exception) {}
        scrapeWebView = null
    }

    private fun displayUsage(usage: PlanUsage) {
        planNameText.text = "Claude ${usage.planName}"
        usageContainer.removeAllViews()
        usage.session?.let { addUsageSection(it) }
        usage.weekly?.let { addUsageSection(it) }
    }

    private fun addUsageSection(limit: UsageLimit) {
        val card = layoutInflater.inflate(R.layout.item_model_usage, usageContainer, false)
        card.findViewById<TextView>(R.id.sectionTitle).text = limit.label
        val bar = card.findViewById<ProgressBar>(R.id.usageBar)
        bar.max = 100
        bar.progress = limit.usedPercent.toInt().coerceIn(0, 100)
        val barColor = when {
            limit.usedPercent >= 90 -> getColor(android.R.color.holo_red_light)
            limit.usedPercent >= 70 -> getColor(android.R.color.holo_orange_light)
            else -> getColor(android.R.color.holo_green_light)
        }
        bar.progressTintList = android.content.res.ColorStateList.valueOf(barColor)
        card.findViewById<TextView>(R.id.usagePercent).text = limit.statusText()
        card.findViewById<TextView>(R.id.resetTime).text = limit.resetTimeText()
        usageContainer.addView(card)
    }

    private fun saveUsageData(usage: PlanUsage) {
        val json = com.google.gson.Gson().toJson(usage)
        PreferenceManager.getDefaultSharedPreferences(this).edit()
            .putString("last_usage", json).apply()
        if (isServiceRunning) {
            try { startService(Intent(this, UsageMonitorService::class.java).apply {
                action = "com.claudeusage.widget.NOTIFY_UPDATE"
            }) } catch (_: Exception) {}
        }
        UsageWidgetProvider.updateAll(this)
    }

    private fun displayUsageFromJson(json: String) {
        try {
            val usage = com.google.gson.Gson().fromJson(json, PlanUsage::class.java)
            displayUsage(usage)
        } catch (_: Exception) {}
    }
}
