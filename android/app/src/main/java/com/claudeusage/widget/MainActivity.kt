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
import android.webkit.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager

/**
 * 메인 화면 — 숨겨진 WebView로 claude.ai 사용량 페이지를 스크래핑.
 * API를 직접 호출하지 않고, WebView로 실제 페이지를 로드하여 DOM에서 데이터 추출.
 */
class MainActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var isServiceRunning = false
    private var scrapeWebView: WebView? = null

    private lateinit var statusText: TextView
    private lateinit var planNameText: TextView
    private lateinit var usageContainer: LinearLayout
    private lateinit var toggleButton: Button
    private lateinit var refreshButton: Button
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
        requestNotificationPermission()
        initViews()
        loadSettings()
    }

    override fun onDestroy() {
        scrapeWebView?.destroy()
        super.onDestroy()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100
            )
        }
    }

    private fun initViews() {
        statusText = findViewById(R.id.statusText)
        planNameText = findViewById(R.id.planNameText)
        usageContainer = findViewById(R.id.modelsContainer)
        toggleButton = findViewById(R.id.toggleButton)
        refreshButton = findViewById(R.id.refreshButton)
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
                .remove("session_key")
                .remove("logged_in")
                .remove("last_usage")
                .apply()
            // WebView 쿠키도 삭제
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
                // 최신 사용량 데이터를 서비스에 전달
                fetchUsageViaScraping()
                UsageMonitorService.start(this)
                isServiceRunning = true
                toggleButton.text = "모니터링 중지"
                statusText.text = "서비스 실행 중"
            }
            saveServiceState()
        }

        refreshButton.setOnClickListener { fetchUsageViaScraping() }

        saveButton.setOnClickListener {
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putString("refresh_interval", refreshInput.text.toString().trim())
                .apply()
            Toast.makeText(this, "저장됨", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadSettings() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        refreshInput.setText(prefs.getString("refresh_interval", "120"))

        val loggedIn = prefs.getBoolean("logged_in", false)
        updateLoginUI(loggedIn)

        isServiceRunning = prefs.getBoolean("service_running", false)
        toggleButton.text = if (isServiceRunning) "모니터링 중지" else "모니터링 시작"

        // 저장된 사용량 데이터가 있으면 먼저 표시
        val lastUsage = prefs.getString("last_usage", null)
        if (lastUsage != null) {
            displayUsageFromJson(lastUsage)
        }

        if (loggedIn) fetchUsageViaScraping()
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

    /**
     * 숨겨진 WebView로 claude.ai 사용량 페이지를 로드하고 DOM을 스크래핑.
     * WebView는 LoginActivity와 같은 CookieManager를 공유하므로 인증됨.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun fetchUsageViaScraping() {
        statusText.text = "불러오는 중..."

        // 기존 WebView 정리
        scrapeWebView?.destroy()

        val wv = WebView(this).apply {
            // 화면에 안 보이게
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
                    // 페이지 로드 후 2초 대기 (React 렌더링 시간)
                    handler.postDelayed({ scrapeUsagePage(view) }, 3000)
                }
            }
        }

        scrapeWebView = wv
        // 레이아웃에 추가 (보이지 않음)
        (findViewById<View>(android.R.id.content) as? android.view.ViewGroup)?.addView(
            wv, ViewGroup.LayoutParams(400, 800)
        )

        // 설정 > 사용량 페이지 로드
        wv.loadUrl("https://claude.ai/settings/usage")
    }

    /**
     * 사용량 페이지의 DOM에서 데이터를 추출하는 JavaScript 실행.
     */
    private fun scrapeUsagePage(view: WebView?) {
        view?.evaluateJavascript("""
            (function() {
                // 페이지의 모든 텍스트에서 사용량 패턴 찾기
                const body = document.body ? document.body.innerText : '';
                const html = document.documentElement ? document.documentElement.innerHTML : '';

                // "XX% 사용됨" 패턴 찾기
                const percentMatches = body.match(/(\d+)%\s*사용됨/g) || [];

                // "X시간 Y분 후 재설정" 패턴 찾기
                const resetMatches = body.match(/[\d시간분\s]+후\s*재설정/g) ||
                                     body.match(/[가-힣]+\s+\d+:\d+\s+[가-힣]+에\s*재설정/g) || [];

                // aria-valuenow (프로그레스바 값) 찾기
                const progressBars = document.querySelectorAll('[role="progressbar"], progress, [aria-valuenow]');
                const barValues = [];
                progressBars.forEach(function(bar) {
                    barValues.push(bar.getAttribute('aria-valuenow') || bar.value || '');
                });

                // 섹션 제목 찾기 ("현재 세션", "주간 한도" 등)
                const headings = [];
                document.querySelectorAll('h1,h2,h3,h4,h5,h6,div,span,p').forEach(function(el) {
                    const t = (el.innerText || '').trim();
                    if (t.includes('세션') || t.includes('주간') || t.includes('한도') ||
                        t.includes('사용') || t.includes('재설정') || t.includes('%')) {
                        if (t.length < 100) headings.push(t);
                    }
                });

                return JSON.stringify({
                    url: window.location.href,
                    title: document.title,
                    percentMatches: percentMatches,
                    resetMatches: resetMatches,
                    barValues: barValues,
                    headings: headings.slice(0, 20),
                    bodyPreview: body.substring(0, 500)
                });
            })();
        """.trimIndent()) { result ->
            handleScrapeResult(result)
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
            val gson = com.google.gson.Gson()
            val json = gson.fromJson(raw, com.google.gson.JsonObject::class.java)

            val url = json?.get("url")?.asString ?: ""
            val percentMatches = json?.getAsJsonArray("percentMatches")
            val resetMatches = try { json?.getAsJsonArray("resetMatches") } catch (_: Exception) { null }
            val headings = try { json?.getAsJsonArray("headings") } catch (_: Exception) { null }
            val barValues = try { json?.getAsJsonArray("barValues") } catch (_: Exception) { null }
            val bodyPreview = json?.get("bodyPreview")?.asString ?: ""

            // 퍼센트 값 추출
            val percents = mutableListOf<Int>()
            percentMatches?.forEach {
                val match = Regex("(\\d+)%").find(it.asString)
                if (match != null) percents.add(match.groupValues[1].toInt())
            }

            val resets = mutableListOf<String>()
            resetMatches?.forEach { resets.add(it.asString) }

            // barValues에서도 퍼센트 추출 시도
            if (percents.isEmpty() && barValues != null) {
                barValues.forEach {
                    val v = it.asString.toIntOrNull()
                    if (v != null && v in 0..100) percents.add(v)
                }
            }

            if (percents.isNotEmpty()) {
                // 사용량 데이터 구성
                val sessionPercent = percents.getOrNull(0) ?: 0
                val weeklyPercent = percents.getOrNull(1)
                val sessionReset = resets.getOrNull(0) ?: ""
                val weeklyReset = resets.getOrNull(1) ?: ""

                val usage = PlanUsage(
                    planName = "Max",
                    session = UsageLimit("현재 세션", sessionPercent.toDouble(), sessionReset),
                    weekly = if (weeklyPercent != null)
                        UsageLimit("주간 한도", weeklyPercent.toDouble(), weeklyReset) else null,
                    lastUpdated = java.time.Instant.now().toString(),
                )

                displayUsage(usage)
                saveUsageData(usage)
                statusText.text = "마지막 업데이트: ${java.time.LocalTime.now().toString().take(5)}"
            } else {
                // 로그인 페이지로 리다이렉트됐을 수 있음
                if (url.contains("/login")) {
                    statusText.text = "세션 만료. 다시 로그인하세요."
                    updateLoginUI(false)
                } else {
                    statusText.text = "사용량을 찾을 수 없음. 페이지: ${bodyPreview.take(100)}"
                }
            }
        } catch (e: Exception) {
            statusText.text = "오류: ${e.message}"
        }

        // 스크래핑 WebView 정리
        scrapeWebView?.let {
            (it.parent as? android.view.ViewGroup)?.removeView(it)
            it.destroy()
        }
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
            .putString("last_usage", json)
            .apply()
    }

    private fun displayUsageFromJson(json: String) {
        try {
            val usage = com.google.gson.Gson().fromJson(json, PlanUsage::class.java)
            displayUsage(usage)
        } catch (_: Exception) {}
    }
}
