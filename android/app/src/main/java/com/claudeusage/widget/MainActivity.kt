package com.claudeusage.widget

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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
    // 모드 & 비용
    private lateinit var modeRadioGroup: RadioGroup
    private lateinit var costSection: LinearLayout
    private lateinit var costTodayText: TextView
    private lateinit var costTodayKrw: TextView
    private lateinit var costMonthText: TextView
    private lateinit var costMonthKrw: TextView
    private lateinit var costByAiContainer: LinearLayout
    // 오랑붕쌤 연결
    private lateinit var obsStatus: TextView
    private lateinit var obsConnectButton: Button
    // Admin API
    private lateinit var adminKeyInput: EditText
    private lateinit var adminKeySave: Button
    private lateinit var openaiKeyInput: EditText
    private lateinit var openaiKeySave: Button
    private lateinit var adminCostText: TextView

    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == LoginActivity.RESULT_LOGGED_IN) {
            updateLoginUI(true)
            fetchUsageViaScraping()
        }
    }

    private val obsLoginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == ObsLoginActivity.RESULT_LOGGED_IN) {
            updateObsUI(true)
            fetchObsCost()
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
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val loggedIn = prefs.getBoolean("logged_in", false)
        val mode = DisplayMode.fromString(prefs.getString("display_mode", null))
        if (loggedIn || mode == DisplayMode.API_COST_ONLY) {
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
        // 모드 & 비용
        modeRadioGroup = findViewById(R.id.modeRadioGroup)
        costSection = findViewById(R.id.costSection)
        costTodayText = findViewById(R.id.costTodayText)
        costTodayKrw = findViewById(R.id.costTodayKrw)
        costMonthText = findViewById(R.id.costMonthText)
        costMonthKrw = findViewById(R.id.costMonthKrw)
        costByAiContainer = findViewById(R.id.costByAiContainer)
        // 오랑붕쌤 연결
        obsStatus = findViewById(R.id.obsStatus)
        obsConnectButton = findViewById(R.id.obsConnectButton)
        // Admin API
        adminKeyInput = findViewById(R.id.adminKeyInput)
        adminKeySave = findViewById(R.id.adminKeySave)
        openaiKeyInput = findViewById(R.id.openaiKeyInput)
        openaiKeySave = findViewById(R.id.openaiKeySave)
        adminCostText = findViewById(R.id.adminCostText)

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
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val mode = DisplayMode.fromString(prefs.getString("display_mode", null))
            val loggedIn = prefs.getBoolean("logged_in", false)
            if (!loggedIn && mode != DisplayMode.API_COST_ONLY) {
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
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            // 모드 저장
            val mode = when (modeRadioGroup.checkedRadioButtonId) {
                R.id.modeApiCostOnly -> DisplayMode.API_COST_ONLY
                R.id.modeBoth -> DisplayMode.BOTH
                else -> DisplayMode.CLAUDE_ONLY
            }
            prefs.edit()
                .putString("refresh_interval", refreshInput.text.toString().trim())
                .putString("display_mode", mode.name)
                .apply()
            Toast.makeText(this, "저장됨", Toast.LENGTH_SHORT).show()
            updateCostSectionVisibility()
            startAutoRefresh()
        }

        // 모드 변경 시 즉시 UI 반영
        modeRadioGroup.setOnCheckedChangeListener { _, _ ->
            updateCostSectionVisibility()
        }

        // 오랑붕쌤 연결
        obsConnectButton.setOnClickListener {
            obsLoginLauncher.launch(Intent(this, ObsLoginActivity::class.java))
        }

        // Admin API 키 저장
        adminKeySave.setOnClickListener {
            val key = adminKeyInput.text.toString().trim()
            if (key == "****") return@setOnClickListener
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putString("anthropic_admin_key", key).apply()
            Toast.makeText(this, if (key.isNotEmpty()) "Claude Admin 키 저장됨" else "삭제됨",
                Toast.LENGTH_SHORT).show()
            if (key.isNotEmpty()) fetchAdminCosts()
        }
        openaiKeySave.setOnClickListener {
            val key = openaiKeyInput.text.toString().trim()
            if (key == "****") return@setOnClickListener
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putString("openai_admin_key", key).apply()
            Toast.makeText(this, if (key.isNotEmpty()) "OpenAI Admin 키 저장됨" else "삭제됨",
                Toast.LENGTH_SHORT).show()
            if (key.isNotEmpty()) fetchAdminCosts()
        }
    }

    private fun updateOverlayButton() {
        val showing = floatingOverlay?.isShowing() == true || FloatingOverlay.wasShowing(this)
        overlayButton.text = if (showing) "플로팅 오버레이 끄기" else "플로팅 오버레이 켜기"
    }

    private fun updateObsUI(connected: Boolean) {
        if (connected) {
            obsStatus.text = "오랑붕쌤: ✓ 연결됨"
            obsStatus.setTextColor(getColor(android.R.color.holo_green_light))
            obsConnectButton.text = "재연결"
        } else {
            obsStatus.text = "오랑붕쌤: 연결 안됨"
            obsStatus.setTextColor(0xFF888899.toInt())
            obsConnectButton.text = "오랑붕쌤 연결"
        }
    }

    private fun fetchAdminCosts() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val anthropicKey = prefs.getString("anthropic_admin_key", "") ?: ""
        val openaiKey = prefs.getString("openai_admin_key", "") ?: ""

        if (anthropicKey.isEmpty() && openaiKey.isEmpty()) return

        adminCostText.text = "Admin API 조회 중..."

        Thread {
            val results = mutableListOf<String>()
            var claudeActual: Double? = null
            var gptActual: Double? = null

            // Anthropic Admin API
            if (anthropicKey.isNotEmpty()) {
                try {
                    val url = java.net.URL("https://api.anthropic.com/v1/organizations/cost_report")
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("x-api-key", anthropicKey)
                    conn.setRequestProperty("anthropic-version", "2023-06-01")
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000

                    if (conn.responseCode == 200) {
                        val body = conn.inputStream.bufferedReader().readText()
                        val json = com.google.gson.JsonParser.parseString(body).asJsonObject
                        claudeActual = json.get("total_cost")?.asDouble
                        if (claudeActual != null) {
                            results.add("Claude 실제: $${String.format("%.4f", claudeActual)}")
                        }
                    } else {
                        results.add("Claude: 오류 ${conn.responseCode}")
                    }
                    conn.disconnect()
                } catch (e: Exception) {
                    results.add("Claude: 연결 실패")
                }
            }

            // OpenAI Admin API
            if (openaiKey.isNotEmpty()) {
                try {
                    // 이번 달 시작일
                    val monthStart = java.time.LocalDate.now().withDayOfMonth(1).toString()
                    val tomorrow = java.time.LocalDate.now().plusDays(1).toString()
                    val url = java.net.URL(
                        "https://api.openai.com/v1/organization/costs?start_date=$monthStart&end_date=$tomorrow"
                    )
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("Authorization", "Bearer $openaiKey")
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000

                    if (conn.responseCode == 200) {
                        val body = conn.inputStream.bufferedReader().readText()
                        val json = com.google.gson.JsonParser.parseString(body).asJsonObject
                        // OpenAI cost API returns { data: [{ results: [{ amount: { value: X }}] }] }
                        val dataArr = json.getAsJsonArray("data")
                        var totalCost = 0.0
                        dataArr?.forEach { bucket ->
                            val results2 = bucket.asJsonObject.getAsJsonArray("results")
                            results2?.forEach { r ->
                                val amount = r.asJsonObject.getAsJsonObject("amount")
                                totalCost += amount?.get("value")?.asDouble ?: 0.0
                            }
                        }
                        gptActual = totalCost
                        results.add("GPT 실제: $${String.format("%.4f", gptActual)}")
                    } else {
                        results.add("GPT: 오류 ${conn.responseCode}")
                    }
                    conn.disconnect()
                } catch (e: Exception) {
                    results.add("GPT: 연결 실패")
                }
            }

            // 추정치와 비교
            handler.post {
                val costJson = PreferenceManager.getDefaultSharedPreferences(this)
                    .getString("last_api_cost", null)
                val estimatedClaude = if (costJson != null) {
                    try {
                        val cost = com.google.gson.Gson().fromJson(costJson, ApiCostData::class.java)
                        cost.byAI.find { it.aiId == "claude" }?.monthCost ?: 0.0
                    } catch (_: Exception) { 0.0 }
                } else 0.0
                val estimatedGpt = if (costJson != null) {
                    try {
                        val cost = com.google.gson.Gson().fromJson(costJson, ApiCostData::class.java)
                        cost.byAI.find { it.aiId == "gpt" }?.monthCost ?: 0.0
                    } catch (_: Exception) { 0.0 }
                } else 0.0

                val lines = mutableListOf<String>()
                lines.addAll(results)

                if (claudeActual != null && estimatedClaude > 0) {
                    val diff = claudeActual - estimatedClaude
                    val sign = if (diff >= 0) "+" else ""
                    lines.add("  Claude 차이: $sign$${String.format("%.4f", diff)} (추정 $${String.format("%.4f", estimatedClaude)})")
                }
                if (gptActual != null && estimatedGpt > 0) {
                    val diff = gptActual - estimatedGpt
                    val sign = if (diff >= 0) "+" else ""
                    lines.add("  GPT 차이: $sign$${String.format("%.4f", diff)} (추정 $${String.format("%.4f", estimatedGpt)})")
                }

                adminCostText.text = lines.joinToString("\n")

                // 실제 비용 저장 (위젯/알림에서 사용)
                prefs.edit()
                    .apply {
                        if (claudeActual != null) putFloat("actual_claude_month", claudeActual.toFloat())
                        if (gptActual != null) putFloat("actual_gpt_month", gptActual.toFloat())
                    }
                    .apply()
            }
        }.start()
    }

    private fun updateCostSectionVisibility() {
        val mode = when (modeRadioGroup.checkedRadioButtonId) {
            R.id.modeApiCostOnly -> DisplayMode.API_COST_ONLY
            R.id.modeBoth -> DisplayMode.BOTH
            else -> DisplayMode.CLAUDE_ONLY
        }
        costSection.visibility = if (mode != DisplayMode.CLAUDE_ONLY) View.VISIBLE else View.GONE
        usageContainer.visibility = if (mode != DisplayMode.API_COST_ONLY) View.VISIBLE else View.GONE
    }

    private fun displayCostData(cost: ApiCostData) {
        costTodayText.text = cost.todayText()
        costTodayKrw.text = cost.todayKrw()
        costMonthText.text = cost.monthText()
        costMonthKrw.text = cost.monthKrw()

        costByAiContainer.removeAllViews()
        cost.byAI.filter { it.monthCost > 0 }.forEach { ai ->
            val aiDef = AiDefs.find(ai.aiId)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(8, 6, 8, 6)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            // 색상 점
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(16, 16).apply { marginEnd = 12 }
                try { setBackgroundColor(Color.parseColor(ai.color)) } catch (_: Exception) {}
            }
            // AI 이름 (클릭 → 비용 확인 사이트)
            val name = TextView(this).apply {
                text = ai.name
                setTextColor(try { Color.parseColor(ai.color) } catch (_: Exception) { 0xFFaaaaaa.toInt() })
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                if (aiDef?.usageUrl != null) {
                    paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                    setOnClickListener {
                        startActivity(Intent(Intent.ACTION_VIEW,
                            android.net.Uri.parse(aiDef.usageUrl)))
                    }
                }
            }
            // 이번달 비용
            val monthCost = TextView(this).apply {
                text = "$${String.format("%.4f", ai.monthCost)}"
                setTextColor(0xFFe0e0e0.toInt())
                textSize = 12f
            }
            row.addView(dot)
            row.addView(name)
            row.addView(monthCost)
            costByAiContainer.addView(row)
        }
    }

    private fun loadSettings() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        refreshInput.setText(prefs.getString("refresh_interval", "120"))
        val loggedIn = prefs.getBoolean("logged_in", false)
        updateLoginUI(loggedIn)
        isServiceRunning = prefs.getBoolean("service_running", false)
        toggleButton.text = if (isServiceRunning) "모니터링 중지" else "모니터링 시작"

        // 모드 복원
        val mode = DisplayMode.fromString(prefs.getString("display_mode", null))
        when (mode) {
            DisplayMode.CLAUDE_ONLY -> modeRadioGroup.check(R.id.modeClaudeOnly)
            DisplayMode.API_COST_ONLY -> modeRadioGroup.check(R.id.modeApiCostOnly)
            DisplayMode.BOTH -> modeRadioGroup.check(R.id.modeBoth)
        }
        updateCostSectionVisibility()

        // 오랑붕쌤 상태
        val obsLoggedIn = prefs.getBoolean("obs_logged_in", false)
        updateObsUI(obsLoggedIn)

        // Admin API 키
        val anthropicKey = prefs.getString("anthropic_admin_key", "")
        if (!anthropicKey.isNullOrEmpty()) adminKeyInput.setText("****")
        val openaiKey = prefs.getString("openai_admin_key", "")
        if (!openaiKey.isNullOrEmpty()) openaiKeyInput.setText("****")
        if (!anthropicKey.isNullOrEmpty() || !openaiKey.isNullOrEmpty()) fetchAdminCosts()

        val lastUsage = prefs.getString("last_usage", null)
        if (lastUsage != null) displayUsageFromJson(lastUsage)

        // 비용 데이터 복원
        val costJson = prefs.getString("last_api_cost", null)
        if (costJson != null) {
            try {
                val cost = com.google.gson.Gson().fromJson(costJson, ApiCostData::class.java)
                displayCostData(cost)
            } catch (_: Exception) {}
        }
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
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val mode = DisplayMode.fromString(prefs.getString("display_mode", null))

        // Claude 스크래핑 (CLAUDE_ONLY 또는 BOTH)
        if (mode != DisplayMode.API_COST_ONLY) {
            try {
                scrapeWebView?.let {
                    (it.parent as? ViewGroup)?.removeView(it)
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
                (findViewById<View>(android.R.id.content) as? ViewGroup)?.addView(
                    wv, ViewGroup.LayoutParams(400, 800))
                wv.loadUrl("https://claude.ai/settings/usage")
            } catch (e: Exception) {
                statusText.text = "스크래핑 오류: ${e.message}"
            }
        }

        // 오랑붕쌤 비용 스크래핑 (API_COST_ONLY 또는 BOTH, 로그인된 경우만)
        if (mode != DisplayMode.CLAUDE_ONLY) {
            val obsLoggedIn = prefs.getBoolean("obs_logged_in", false)
            if (obsLoggedIn) {
                fetchObsCost()
            } else {
                statusText.text = "오랑붕쌤 연결이 필요합니다"
            }
        }
    }

    private fun scrapeUsagePage(view: WebView?) {
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

    // ── 오랑붕쌤 Drive API 직접 호출 ──
    private fun fetchObsCost() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val mode = DisplayMode.fromString(prefs.getString("display_mode", null))
        if (mode == DisplayMode.CLAUDE_ONLY) return

        val token = prefs.getString("google_oauth_token", null)
        if (token.isNullOrEmpty()) {
            statusText.text = "오랑붕쌤 연결이 필요합니다"
            return
        }

        statusText.text = "Drive에서 비용 데이터 조회 중..."

        Thread {
            val result = DriveApiClient.fetchCostFromDrive(token)

            handler.post {
                if (result.tokenExpired) {
                    prefs.edit().putBoolean("obs_logged_in", false)
                        .remove("google_oauth_token").apply()
                    updateObsUI(false)
                    statusText.text = "토큰 만료 — 오랑붕쌤 재연결 필요"
                    return@post
                }

                if (result.error != null) {
                    statusText.text = "Drive 오류: ${result.error}"
                    return@post
                }

                val costData = result.costData ?: return@post
                displayCostData(costData)
                prefs.edit().putString("last_api_cost",
                    com.google.gson.Gson().toJson(costData)).apply()
                UsageWidgetProvider.updateAll(this)
                statusText.text = "마지막 업데이트: ${java.time.LocalTime.now().toString().take(5)}"
            }
        }.start()
    }
}
