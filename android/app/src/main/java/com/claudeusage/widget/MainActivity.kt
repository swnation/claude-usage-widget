package com.claudeusage.widget

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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
    // Admin API (동적)
    private lateinit var billingKeysContainer: LinearLayout
    private lateinit var adminCostText: TextView

    // 스킨 데이터
    private data class SkinInfo(val id: String, val label: String, val emoji: String, val startColor: Int, val endColor: Int)
    private val SKINS = listOf(
        SkinInfo("default", "기본", "🌙", 0xFF1a1a2e.toInt(), 0xFF16213e.toInt()),
        SkinInfo("spring", "봄", "🌸", 0xFFffe4eb.toInt(), 0xFFfff5f5.toInt()),
        SkinInfo("summer", "여름", "🌊", 0xFFc8ebff.toInt(), 0xFFe8f4f8.toInt()),
        SkinInfo("autumn", "가을", "🍂", 0xFFfae6c8.toInt(), 0xFFfaf5ef.toInt()),
        SkinInfo("winter", "겨울", "❄️", 0xFFdcebff.toInt(), 0xFFeef2f7.toInt()),
        SkinInfo("fluffy-pink", "몽글핑크", "🩷", 0xFFffdcf0.toInt(), 0xFFfff0f6.toInt()),
        SkinInfo("fluffy-purple", "몽글퍼플", "💜", 0xFFe6d7ff.toInt(), 0xFFf5f0ff.toInt()),
        SkinInfo("fluffy-mint", "몽글민트", "💚", 0xFFc8f5e6.toInt(), 0xFFf0faf7.toInt()),
        SkinInfo("fluffy-yellow", "몽글옐로", "💛", 0xFFfff5b4.toInt(), 0xFFfffde8.toInt()),
        SkinInfo("fluffy-sky", "몽글스카이", "🩵", 0xFFd2e6ff.toInt(), 0xFFf0f6ff.toInt()),
    )

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

    // 커스텀 스킨 사진 선택
    private val customSkinPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                // 사진을 앱 내부 저장소에 복사 (URI 권한 문제 방지)
                val inStream = contentResolver.openInputStream(uri) ?: return@registerForActivityResult
                val file = java.io.File(filesDir, "custom_skin_bg.png")
                file.outputStream().use { out -> inStream.copyTo(out) }
                inStream.close()

                val prefs = PreferenceManager.getDefaultSharedPreferences(this)
                prefs.edit()
                    .putString("skin", "custom")
                    .putString("custom_skin_path", file.absolutePath)
                    .apply()
                setupSkinSelector()
                applySkin()
                FloatingOverlay.getInstance(applicationContext).updateSkin()
                UsageWidgetProvider.updateAll(this)
                Toast.makeText(this, "커스텀 스킨 적용!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "사진 로드 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 커스텀 스킨 파일(.cskin) 선택
    private val cskinFilePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val stream = contentResolver.openInputStream(uri)
                val json = stream?.bufferedReader()?.readText() ?: return@registerForActivityResult
                stream.close()
                val skinData = CustomSkinData.fromJson(json)
                if (skinData == null) {
                    Toast.makeText(this, "스킨 파일 파싱 실패", Toast.LENGTH_SHORT).show()
                    return@registerForActivityResult
                }
                val prefs = PreferenceManager.getDefaultSharedPreferences(this)
                prefs.edit()
                    .putString("skin", "custom-file")
                    .putString("custom_skin_json", json)
                    .apply()
                setupSkinSelector()
                applySkin()
                FloatingOverlay.getInstance(applicationContext).updateSkin()
                UsageWidgetProvider.updateAll(this)
                Toast.makeText(this, "${skinData.name} 스킨 적용!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "파일 읽기 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
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
        // Billing API (동적)
        billingKeysContainer = findViewById(R.id.billingKeysContainer)
        adminCostText = findViewById(R.id.adminCostText)

        // ── 앱 스킨 적용 ──
        applySkin()

        // ── 접이식 섹션 ──
        setupAccordion(R.id.sectionOverlayHeader, R.id.sectionOverlayBody)
        setupAccordion(R.id.sectionBillingHeader, R.id.sectionBillingBody)
        setupAccordion(R.id.sectionSkinHeader, R.id.sectionSkinBody)
        setupAccordion(R.id.sectionSettingsHeader, R.id.sectionSettingsBody)

        // ── 스킨 그리드 ──
        setupSkinSelector()

        // ── 동적 Billing 키 ──
        setupBillingKeys()
        findViewById<Button>(R.id.addBillingKeyButton).setOnClickListener { showAddBillingKeyDialog() }

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

        // 플로팅 정보량 모드 스피너
        val overlayModeSpinner = findViewById<android.widget.Spinner>(R.id.overlayModeSpinner)
        val overlayModes = arrayOf(
            "최소 (세션 %)",
            "기본 (세션 + 주간)",
            "비용 (세션 + 요금)",
            "전체 (세션 + 주간 + 요금)"
        )
        overlayModeSpinner.adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, overlayModes
        )
        // 저장된 모드 복원
        val savedOverlayMode = PreferenceManager.getDefaultSharedPreferences(this)
            .getString("overlay_mode", "MINIMAL")
        overlayModeSpinner.setSelection(
            FloatingOverlay.OverlayMode.fromString(savedOverlayMode).ordinal
        )
        overlayModeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val selected = FloatingOverlay.OverlayMode.entries[pos]
                PreferenceManager.getDefaultSharedPreferences(this@MainActivity).edit()
                    .putString("overlay_mode", selected.name).apply()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
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

        // Admin API 키 복원/백업
        findViewById<Button>(R.id.adminKeyRestore).setOnClickListener { restoreKeysFromDrive() }
        findViewById<Button>(R.id.adminKeyBackup).setOnClickListener { backupKeysToDrive() }

        // Gemini BigQuery 설정
        val gcpProjectInput = findViewById<EditText>(R.id.gcpProjectIdInput)
        val gcpDatasetInput = findViewById<EditText>(R.id.gcpDatasetIdInput)
        val gcpTableInput = findViewById<EditText>(R.id.gcpTableIdInput)
        val gcpPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        gcpProjectInput.setText(gcpPrefs.getString("gcp_project_id", ""))
        gcpDatasetInput.setText(gcpPrefs.getString("gcp_dataset_id", ""))
        gcpTableInput.setText(gcpPrefs.getString("gcp_table_id", ""))
        findViewById<Button>(R.id.gcpSaveButton).setOnClickListener {
            val pId = gcpProjectInput.text.toString().trim()
            val dId = gcpDatasetInput.text.toString().trim()
            val tId = gcpTableInput.text.toString().trim()
            if (pId.isEmpty() || dId.isEmpty() || tId.isEmpty()) {
                Toast.makeText(this, "모든 필드를 입력하세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            gcpPrefs.edit()
                .putString("gcp_project_id", pId)
                .putString("gcp_dataset_id", dId)
                .putString("gcp_table_id", tId)
                .apply()
            Toast.makeText(this, "Gemini Billing 설정 저장됨", Toast.LENGTH_SHORT).show()
            if (gcpPrefs.getString("google_oauth_token", null) != null) {
                fetchAdminCosts()
            } else {
                Toast.makeText(this, "오랑붕쌤 연결 후 사용 가능합니다", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── 앱 스킨 적용 ──
    private fun applySkin() {
        try {
            val skin = FloatingOverlay.getAppColors(this)

            // .cskin 파일의 배경 이미지 확인
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val skinId = prefs.getString("skin", "default") ?: "default"
            var sectionBgColor = skin.sectionBgColor

            val scrollView = findViewById<android.widget.ScrollView>(R.id.mainScrollView)
            if (skinId == "custom-file") {
                val json = prefs.getString("custom_skin_json", null)
                val skinData = json?.let { CustomSkinData.fromJson(it) }
                val bgBitmap = skinData?.appBackgroundBitmap()
                if (bgBitmap != null) {
                    val drawable = android.graphics.drawable.BitmapDrawable(resources, bgBitmap)
                    drawable.gravity = android.view.Gravity.FILL
                    scrollView?.background = drawable
                    sectionBgColor = skinData.sectionColorWithOpacity()
                } else {
                    scrollView?.setBackgroundColor(skin.bgColor)
                }
            } else if (skinId == "custom") {
                // 사진 스킨: 앱 배경에도 사진 적용
                val path = prefs.getString("custom_skin_path", null)
                val bitmap = path?.let { android.graphics.BitmapFactory.decodeFile(it) }
                if (bitmap != null) {
                    val drawable = android.graphics.drawable.BitmapDrawable(resources, bitmap)
                    drawable.gravity = android.view.Gravity.FILL
                    scrollView?.background = drawable
                    // 섹션 반투명 (사진이 비쳐 보이도록)
                    sectionBgColor = (skin.sectionBgColor and 0x00FFFFFF) or 0xCC000000.toInt()
                } else {
                    scrollView?.setBackgroundColor(skin.bgColor)
                }
            } else {
                scrollView?.setBackgroundColor(skin.bgColor)
            }

            // 헤더 텍스트
            planNameText.setTextColor(skin.subtextColor)

            // 섹션 헤더 & 바디 배경/텍스트
            val headers = listOf(
                R.id.sectionOverlayHeader, R.id.sectionBillingHeader,
                R.id.sectionSkinHeader, R.id.sectionSettingsHeader
            )
            val bodies = listOf(
                R.id.sectionOverlayBody, R.id.sectionBillingBody,
                R.id.sectionSkinBody, R.id.sectionSettingsBody
            )
            headers.forEach { id ->
                findViewById<TextView>(id)?.apply {
                    setBackgroundColor(sectionBgColor)
                    setTextColor(skin.accentColor)
                }
            }
            bodies.forEach { id ->
                findViewById<LinearLayout>(id)?.setBackgroundColor(sectionBgColor)
            }

            // 상태 텍스트
            statusText.setTextColor(skin.subtextColor)

            // 버튼 강조색
            val accentTint = android.content.res.ColorStateList.valueOf(skin.accentColor)
            toggleButton.backgroundTintList = accentTint
            overlayButton.setTextColor(skin.accentColor)
            saveButton.setTextColor(skin.accentColor)
            loginButton.backgroundTintList = accentTint

            // 카드 배경 (API 요금 섹션)
            costByAiContainer.setBackgroundColor(skin.cardBgColor)

            // 라디오 버튼 텍스트 & 틴트
            for (i in 0 until modeRadioGroup.childCount) {
                (modeRadioGroup.getChildAt(i) as? RadioButton)?.apply {
                    setTextColor(skin.textColor)
                    buttonTintList = accentTint
                }
            }

            // 갱신 주기 입력 텍스트
            refreshInput.setTextColor(skin.textColor)
            refreshInput.setHintTextColor(skin.subtextColor)

            // 밝은 스킨: 비용 카드 내부 텍스트도 어둡게
            if (!skin.isDark) {
                costTodayText.setTextColor(skin.accentColor)
                costMonthText.setTextColor(skin.accentColor)
            }
        } catch (_: Exception) {
            // 스킨 적용 실패해도 앱 실행은 유지
        }
    }

    // ── 접이식 섹션 토글 ──
    private fun setupAccordion(headerId: Int, bodyId: Int) {
        val header = findViewById<TextView>(headerId)
        val body = findViewById<LinearLayout>(bodyId)
        header.setOnClickListener {
            val visible = body.visibility == View.VISIBLE
            body.visibility = if (visible) View.GONE else View.VISIBLE
            header.text = (if (visible) "▸ " else "▾ ") + header.text.toString().removePrefix("▸ ").removePrefix("▾ ")
        }
    }

    // ── 스킨 선택 ──
    private fun setupSkinSelector() {
        val container = findViewById<LinearLayout>(R.id.skinContainer)
        container.removeAllViews()
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val currentSkin = prefs.getString("skin", "default") ?: "default"

        SKINS.forEach { skin ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setPadding(8, 8, 8, 8)
                val size = (64 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = (8 * resources.displayMetrics.density).toInt()
                }
            }
            val preview = View(this).apply {
                val s = (48 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(s, s)
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(skin.startColor, skin.endColor)
                ).apply {
                    cornerRadius = if (skin.id.startsWith("fluffy")) 24f * resources.displayMetrics.density
                        else 12f * resources.displayMetrics.density
                }
                if (currentSkin == skin.id) {
                    foreground = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 12f * resources.displayMetrics.density
                        setStroke((2 * resources.displayMetrics.density).toInt(), 0xFFc084fc.toInt())
                    }
                }
            }
            val label = TextView(this).apply {
                text = "${skin.emoji}\n${skin.label}"
                textSize = 9f
                setTextColor(0xFFe0e0e0.toInt())
                gravity = android.view.Gravity.CENTER
            }
            item.addView(preview)
            item.addView(label)
            item.setOnClickListener {
                prefs.edit().putString("skin", skin.id).apply()
                setupSkinSelector() // refresh
                applySkin() // 앱 화면 스킨
                FloatingOverlay.getInstance(applicationContext).updateSkin() // 오버레이 스킨
                UsageWidgetProvider.updateAll(this) // 위젯 스킨
                Toast.makeText(this, "${skin.label} 스킨 적용!", Toast.LENGTH_SHORT).show()
            }
            container.addView(item)
        }

        // ── 커스텀 스킨 (사진) ──
        val customItem = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(8, 8, 8, 8)
            val size = (64 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (8 * resources.displayMetrics.density).toInt()
            }
        }
        val customPreview = if (currentSkin == "custom") {
            // 저장된 사진 미리보기
            val path = prefs.getString("custom_skin_path", null)
            if (path != null) {
                try {
                    val bitmap = android.graphics.BitmapFactory.decodeFile(path)
                    if (bitmap != null) {
                        ImageView(this).apply {
                            val s = (48 * resources.displayMetrics.density).toInt()
                            layoutParams = LinearLayout.LayoutParams(s, s)
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            setImageBitmap(bitmap)
                            clipToOutline = true
                            outlineProvider = object : android.view.ViewOutlineProvider() {
                                override fun getOutline(view: View, outline: android.graphics.Outline) {
                                    outline.setRoundRect(0, 0, view.width, view.height,
                                        12f * resources.displayMetrics.density)
                                }
                            }
                            foreground = GradientDrawable().apply {
                                shape = GradientDrawable.RECTANGLE
                                cornerRadius = 12f * resources.displayMetrics.density
                                setStroke((2 * resources.displayMetrics.density).toInt(), 0xFFc084fc.toInt())
                            }
                        }
                    } else null
                } catch (_: Exception) { null }
            } else null
        } else null

        val customPreviewFinal = customPreview ?: View(this).apply {
            val s = (48 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(s, s)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f * resources.displayMetrics.density
                setStroke((2 * resources.displayMetrics.density).toInt(), 0xFF888899.toInt())
                setColor(0xFF22223a.toInt())
            }
            if (currentSkin == "custom") {
                foreground = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 12f * resources.displayMetrics.density
                    setStroke((2 * resources.displayMetrics.density).toInt(), 0xFFc084fc.toInt())
                }
            }
        }
        val customLabel = TextView(this).apply {
            text = "📷\n커스텀"
            textSize = 9f
            setTextColor(0xFFe0e0e0.toInt())
            gravity = android.view.Gravity.CENTER
        }
        customItem.addView(customPreviewFinal)
        customItem.addView(customLabel)
        customItem.setOnClickListener {
            customSkinPicker.launch(arrayOf("image/*"))
        }
        container.addView(customItem)

        // ── 파일 스킨 (.cskin) ──
        val fileItem = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(8, 8, 8, 8)
            val size = (64 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (8 * resources.displayMetrics.density).toInt()
            }
        }
        val fileSkinName = if (currentSkin == "custom-file") {
            val json = prefs.getString("custom_skin_json", null)
            json?.let { CustomSkinData.fromJson(it)?.name } ?: "파일"
        } else "파일"
        val filePreview = View(this).apply {
            val s = (48 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(s, s)
            if (currentSkin == "custom-file") {
                val json = prefs.getString("custom_skin_json", null)
                val skinData = json?.let { CustomSkinData.fromJson(it) }
                if (skinData != null) {
                    val colors = skinData.overlayBgColors()
                    background = GradientDrawable(
                        skinData.overlayGradientOrientation(), colors
                    ).apply {
                        cornerRadius = skinData.overlayCornerRadius() * resources.displayMetrics.density
                    }
                } else {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 12f * resources.displayMetrics.density
                        setStroke((2 * resources.displayMetrics.density).toInt(), 0xFF888899.toInt())
                        setColor(0xFF22223a.toInt())
                    }
                }
                foreground = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 12f * resources.displayMetrics.density
                    setStroke((2 * resources.displayMetrics.density).toInt(), 0xFFc084fc.toInt())
                }
            } else {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 12f * resources.displayMetrics.density
                    setStroke((2 * resources.displayMetrics.density).toInt(), 0xFF888899.toInt())
                    setColor(0xFF22223a.toInt())
                }
            }
        }
        val fileLabel = TextView(this).apply {
            text = "📄\n$fileSkinName"
            textSize = 9f
            setTextColor(0xFFe0e0e0.toInt())
            gravity = android.view.Gravity.CENTER
        }
        fileItem.addView(filePreview)
        fileItem.addView(fileLabel)
        fileItem.setOnClickListener {
            cskinFilePicker.launch(arrayOf("application/json", "*/*"))
        }
        container.addView(fileItem)
    }

    // ── 동적 Billing 키 ──
    private val BILLING_AI_OPTIONS = listOf(
        Triple("anthropic", "Claude", "#c96442"),
        Triple("openai", "GPT", "#10a37f"),
        Triple("gemini", "Gemini", "#4285f4"),
        Triple("grok", "Grok", "#1DA1F2"),
        Triple("perplexity", "Perplexity", "#20808d"),
    )

    private fun setupBillingKeys() {
        billingKeysContainer.removeAllViews()
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        // 기존 저장된 키들 표시
        for ((keyId, label, color) in BILLING_AI_OPTIONS) {
            val prefKey = "${keyId}_admin_key"
            val savedKey = prefs.getString(prefKey, "") ?: ""
            if (savedKey.isEmpty()) continue

            addBillingKeyRow(keyId, label, color, "****")
        }
    }

    private fun addBillingKeyRow(keyId: String, label: String, colorHex: String, value: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (4 * resources.displayMetrics.density).toInt() }
        }
        val nameView = TextView(this).apply {
            text = label
            setTextColor(try { Color.parseColor(colorHex) } catch (_: Exception) { 0xFFaaaaaa.toInt() })
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(
                (60 * resources.displayMetrics.density).toInt(), (40 * resources.displayMetrics.density).toInt()
            )
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val input = EditText(this).apply {
            setText(value)
            setTextColor(0xFFe0e0e0.toInt())
            setHintTextColor(0xFF555566.toInt())
            hint = "API key..."
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            textSize = 11f
            setPadding(16, 8, 16, 8)
            setBackgroundResource(R.drawable.input_background)
            layoutParams = LinearLayout.LayoutParams(0, (40 * resources.displayMetrics.density).toInt(), 1f).apply {
                marginEnd = (4 * resources.displayMetrics.density).toInt()
            }
        }
        val saveBtn = Button(this).apply {
            text = "저장"
            textSize = 11f
            setTextColor(0xFFc084fc.toInt())
            setBackgroundColor(0xFF2a2a4a.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, (40 * resources.displayMetrics.density).toInt()
            )
            setOnClickListener {
                val key = input.text.toString().trim()
                if (key == "****" || key.isEmpty()) return@setOnClickListener
                promptPinAndSaveKeys(keyId, key)
            }
        }
        row.addView(nameView)
        row.addView(input)
        row.addView(saveBtn)
        billingKeysContainer.addView(row)
    }

    private fun showAddBillingKeyDialog() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        // 아직 추가 안 된 AI만 보여주기
        val available = BILLING_AI_OPTIONS.filter { (keyId, _, _) ->
            val saved = prefs.getString("${keyId}_admin_key", "") ?: ""
            saved.isEmpty()
        }
        if (available.isEmpty()) {
            Toast.makeText(this, "모든 AI 키가 이미 등록됨", Toast.LENGTH_SHORT).show()
            return
        }
        val names = available.map { it.second }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Billing API 키 추가")
            .setItems(names) { _, which ->
                val (keyId, label, color) = available[which]
                addBillingKeyRow(keyId, label, color, "")
            }
            .show()
    }

    private fun backupKeysToDrive() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val token = prefs.getString("google_oauth_token", null)
        if (token.isNullOrEmpty()) {
            Toast.makeText(this, "오랑붕쌤 연결이 필요합니다", Toast.LENGTH_SHORT).show()
            return
        }
        val encrypted = prefs.getString("admin_keys_encrypted", null)
        if (encrypted.isNullOrEmpty()) {
            Toast.makeText(this, "먼저 키를 저장하세요 (PIN 설정 필요)", Toast.LENGTH_SHORT).show()
            return
        }
        val statusView = findViewById<TextView>(R.id.adminKeyStatus)
        statusView.text = "☁️ Drive 백업 중..."
        statusView.setTextColor(0xFF888899.toInt())

        lifecycleScope.launch(Dispatchers.IO) {
            val ok = DriveApiClient.saveKeysToDrive(token, encrypted)
            withContext(Dispatchers.Main) {
                if (ok) {
                    statusView.text = "✅ Drive 백업 완료!"
                    statusView.setTextColor(0xFF10a37f.toInt())
                } else {
                    statusView.text = "❌ Drive 백업 실패"
                    statusView.setTextColor(0xFFf87171.toInt())
                }
            }
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

    private fun buildGeminiConfig(prefs: android.content.SharedPreferences): BillingApiClient.GeminiConfig? {
        val token = prefs.getString("google_oauth_token", null) ?: return null
        val projectId = prefs.getString("gcp_project_id", null) ?: return null
        val datasetId = prefs.getString("gcp_dataset_id", null) ?: return null
        val tableId = prefs.getString("gcp_table_id", null) ?: return null
        if (projectId.isEmpty() || datasetId.isEmpty() || tableId.isEmpty()) return null
        return BillingApiClient.GeminiConfig(token, projectId, datasetId, tableId)
    }

    private fun fetchAdminCosts() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val anthropicKey = prefs.getString("anthropic_admin_key", "") ?: ""
        val openaiKey = prefs.getString("openai_admin_key", "") ?: ""

        if (anthropicKey.isEmpty() && openaiKey.isEmpty()) return

        // 오랑붕쌤 추정치 로드
        val costJson = prefs.getString("last_api_cost", null)
        val estimated = if (costJson != null) {
            try { com.google.gson.Gson().fromJson(costJson, ApiCostData::class.java) }
            catch (_: Exception) { null }
        } else null

        // 구독 정보 로드
        val subsJson = prefs.getString("subscriptions", null)
        val subscriptions = if (subsJson != null) {
            try { com.google.gson.Gson().fromJson(subsJson, Array<Subscription>::class.java).toList() }
            catch (_: Exception) { emptyList() }
        } else emptyList<Subscription>()

        adminCostText.text = "Billing API 조회 중..."

        lifecycleScope.launch(Dispatchers.IO) {
            val result = BillingApiClient.fetchAndMerge(
                anthropicKey = anthropicKey.ifEmpty { null },
                openaiKey = openaiKey.ifEmpty { null },
                geminiConfig = buildGeminiConfig(prefs),
                estimatedData = estimated,
                subscriptions = subscriptions,
            )

            withContext(Dispatchers.Main) {
                val costData = result.costData
                if (costData != null) {
                    // 병합된 데이터로 UI 업데이트
                    displayCostData(costData)
                    prefs.edit().putString("last_api_cost",
                        com.google.gson.Gson().toJson(costData)).apply()

                    // Admin 비교 텍스트 생성
                    val lines = mutableListOf<String>()
                    lines.add("소스: ${costData.sourceLabel()}")
                    costData.byAI.filter { it.monthCost > 0 }.forEach { ai ->
                        val costStr = "$${String.format("%.4f", ai.monthCost)}"
                        when (ai.source) {
                            CostSource.BILLING -> lines.add("${ai.name}: $costStr ✓실제")
                            CostSource.HYBRID -> {
                                lines.add("${ai.name}: $costStr ✓실제")
                                val diff = ai.monthDiff
                                if (diff != null) {
                                    val sign = if (diff >= 0) "+" else ""
                                    lines.add("  추정대비 $sign$${String.format("%.4f", diff)}")
                                }
                            }
                            CostSource.ESTIMATED -> lines.add("${ai.name}: $costStr ~추정")
                        }
                    }
                    if (costData.error != null) {
                        lines.add("")
                        lines.add("⚠ ${costData.error}")
                    }
                    adminCostText.text = lines.joinToString("\n")
                } else {
                    adminCostText.text = result.error ?: "비용 데이터 없음"
                }
            }
        }
    }

    // ── Admin 키 암호화 저장/복원 ──
    private fun promptPinAndSaveKeys(type: String, key: String? = null) {
        val actualKey = key ?: return
        if (actualKey == "****" || actualKey.isEmpty()) return

        val pinInput = EditText(this).apply {
            hint = "PIN (4자리 이상)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setPadding(48, 24, 48, 24)
        }

        AlertDialog.Builder(this)
            .setTitle("🔐 Admin 키 암호화")
            .setMessage("키를 암호화할 PIN을 입력하세요")
            .setView(pinInput)
            .setPositiveButton("저장") { _, _ ->
                val pin = pinInput.text.toString()
                if (pin.length < 4) {
                    Toast.makeText(this, "PIN은 4자리 이상이어야 합니다", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                saveAdminKeyEncrypted(type, actualKey, pin)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun saveAdminKeyEncrypted(type: String, key: String, pin: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        // 기존 저장된 키들로부터 합쳐서 암호화
        val keys = com.google.gson.JsonObject()
        for ((keyId, _, _) in BILLING_AI_OPTIONS) {
            val existing = prefs.getString("${keyId}_admin_key", "") ?: ""
            if (existing.isNotEmpty()) keys.addProperty(keyId, existing)
        }

        // 새 키로 덮어쓰기
        keys.addProperty(type, key)
        val keysStr = keys.toString()

        // 암호화
        val encrypted = KeyEncryption.encrypt(keysStr, pin)

        // 로컬 저장
        val editor = prefs.edit()
            .putString("admin_keys_encrypted", encrypted)
            .remove("admin_keys_plain")
        for (entry in keys.entrySet()) {
            editor.putString("${entry.key}_admin_key", entry.value.asString)
        }
        editor.apply()

        Toast.makeText(this, "🔐 Admin 키 암호화 저장됨", Toast.LENGTH_SHORT).show()

        // Drive 백업 (Google 토큰 있으면)
        val token = prefs.getString("google_oauth_token", null)
        if (!token.isNullOrEmpty()) {
            lifecycleScope.launch(Dispatchers.IO) {
                val ok = DriveApiClient.saveKeysToDrive(token, encrypted)
                withContext(Dispatchers.Main) {
                    if (ok) Toast.makeText(this@MainActivity, "☁️ Drive 백업 완료", Toast.LENGTH_SHORT).show()
                }
            }
        }

        if (keys.has("anthropic") || keys.has("openai")) fetchAdminCosts()
    }

    private fun restoreKeysFromDrive() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val token = prefs.getString("google_oauth_token", null)
        if (token.isNullOrEmpty()) {
            Toast.makeText(this, "오랑붕쌤 연결이 필요합니다", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Drive에서 키 불러오는 중...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            val encrypted = DriveApiClient.loadKeysFromDrive(token)
            withContext(Dispatchers.Main) {
                if (encrypted == null) {
                    Toast.makeText(this@MainActivity, "Drive에 백업된 키가 없습니다", Toast.LENGTH_SHORT).show()
                    return@withContext
                }

                // PIN 입력 받기
                val pinInput = EditText(this@MainActivity).apply {
                    hint = "백업 시 설정한 PIN"
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
                    setPadding(48, 24, 48, 24)
                }

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("🔓 키 복원")
                    .setMessage("암호화 해제 PIN을 입력하세요")
                    .setView(pinInput)
                    .setPositiveButton("복원") { _, _ ->
                        val pin = pinInput.text.toString()
                        val decrypted = KeyEncryption.decrypt(encrypted, pin)
                        if (decrypted == null) {
                            Toast.makeText(this@MainActivity, "❌ PIN이 틀립니다", Toast.LENGTH_SHORT).show()
                            return@setPositiveButton
                        }
                        try {
                            val keys = com.google.gson.JsonParser.parseString(decrypted).asJsonObject
                            val editor = prefs.edit().putString("admin_keys_encrypted", encrypted)
                            var anyKey = false
                            for (entry in keys.entrySet()) {
                                editor.putString("${entry.key}_admin_key", entry.value.asString)
                                anyKey = true
                            }
                            editor.apply()

                            setupBillingKeys() // UI 갱신
                            Toast.makeText(this@MainActivity, "🔓 키 복원 완료", Toast.LENGTH_SHORT).show()
                            if (anyKey) fetchAdminCosts()
                        } catch (_: Exception) {
                            Toast.makeText(this@MainActivity, "키 데이터 파싱 실패", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("취소", null)
                    .show()
            }
        }
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

        // 소스 라벨 표시
        val sourceRow = TextView(this).apply {
            text = "소스: ${cost.sourceLabel()}"
            setTextColor(0xFF888899.toInt())
            textSize = 10f
            setPadding(8, 0, 8, 4)
        }
        costByAiContainer.addView(sourceRow)

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
            // AI 이름 + 소스 표시
            val sourceTag = when (ai.source) {
                CostSource.BILLING -> " ✓"
                CostSource.HYBRID -> " ✓"
                CostSource.ESTIMATED -> " ~"
            }
            val name = TextView(this).apply {
                text = "${ai.name}$sourceTag"
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

        // 구독 정보 표시
        if (cost.subscriptions.isNotEmpty()) {
            val subHeader = TextView(this).apply {
                text = "구독"
                setTextColor(0xFF888899.toInt())
                textSize = 10f
                setPadding(8, 12, 8, 4)
            }
            costByAiContainer.addView(subHeader)

            cost.subscriptions.filter { it.isActive }.forEach { sub ->
                val aiDef = AiDefs.find(sub.aiId)
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(8, 4, 8, 4)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                val subName = TextView(this).apply {
                    text = "${aiDef?.name ?: sub.aiId} ${sub.planName}"
                    setTextColor(try { Color.parseColor(aiDef?.color ?: "#888") } catch (_: Exception) { 0xFFaaaaaa.toInt() })
                    textSize = 11f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val subCost = TextView(this).apply {
                    text = "$${String.format("%.0f", sub.monthlyFee)}/mo"
                    setTextColor(0xFFe0e0e0.toInt())
                    textSize = 11f
                }
                row.addView(subName)
                row.addView(subCost)
                costByAiContainer.addView(row)
            }

            // 총합 (API + 구독)
            val totalRow = TextView(this).apply {
                text = "총 (API+구독): ${cost.monthWithSubsText()} (${cost.monthWithSubsKrw()})"
                setTextColor(0xFFc084fc.toInt())
                textSize = 11f
                setPadding(8, 8, 8, 0)
            }
            costByAiContainer.addView(totalRow)
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

        // Billing API 키 (동적)
        setupBillingKeys()
        val hasAnyKey = BILLING_AI_OPTIONS.any { (keyId, _, _) ->
            (prefs.getString("${keyId}_admin_key", "") ?: "").isNotEmpty()
        }
        if (hasAnyKey) fetchAdminCosts()

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

        // 오랑붕쌤 비용 데이터는 연결되어 있으면 항상 가져오기 (모드 무관)
        val obsLoggedIn = prefs.getBoolean("obs_logged_in", false)
        if (obsLoggedIn) {
            fetchObsCost()
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

    // ── 비용 데이터 통합 fetch (Billing 우선, Drive 보조) ──
    private fun fetchObsCost() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        val token = prefs.getString("google_oauth_token", null)
        val anthropicKey = prefs.getString("anthropic_admin_key", "") ?: ""
        val openaiKey = prefs.getString("openai_admin_key", "") ?: ""
        val hasGeminiConfig = buildGeminiConfig(prefs) != null
        val hasBillingKeys = anthropicKey.isNotEmpty() || openaiKey.isNotEmpty() || hasGeminiConfig
        val hasToken = !token.isNullOrEmpty()

        if (!hasBillingKeys && !hasToken) return

        statusText.text = if (hasBillingKeys) "Billing API 조회 중..." else "Drive에서 비용 데이터 조회 중..."

        lifecycleScope.launch(Dispatchers.IO) {
            // 1) 오랑붕쌤 추정 데이터 (Drive)
            var estimatedData: ApiCostData? = null
            if (hasToken) {
                val driveResult = DriveApiClient.fetchCostFromDrive(token!!)
                if (driveResult.tokenExpired) {
                    withContext(Dispatchers.Main) {
                        prefs.edit().putBoolean("obs_logged_in", false)
                            .remove("google_oauth_token").apply()
                        updateObsUI(false)
                    }
                } else {
                    estimatedData = driveResult.costData
                }
            }

            // 2) 구독 정보
            val subsJson = prefs.getString("subscriptions", null)
            val subscriptions = if (subsJson != null) {
                try { com.google.gson.Gson().fromJson(subsJson, Array<Subscription>::class.java).toList() }
                catch (_: Exception) { emptyList() }
            } else emptyList<Subscription>()

            // 3) Billing 있으면 병합, 없으면 추정만
            val costData = if (hasBillingKeys) {
                val merged = BillingApiClient.fetchAndMerge(
                    anthropicKey = anthropicKey.ifEmpty { null },
                    openaiKey = openaiKey.ifEmpty { null },
                    geminiConfig = buildGeminiConfig(prefs),
                    estimatedData = estimatedData,
                    subscriptions = subscriptions,
                )
                merged.costData
            } else {
                estimatedData?.copy(subscriptions = subscriptions)
            }

            withContext(Dispatchers.Main) {
                if (costData != null) {
                    displayCostData(costData)
                    prefs.edit().putString("last_api_cost",
                        com.google.gson.Gson().toJson(costData)).apply()
                    UsageWidgetProvider.updateAll(this@MainActivity)

                    // Admin 비교 텍스트도 업데이트
                    if (hasBillingKeys) {
                        val lines = mutableListOf<String>()
                        lines.add("소스: ${costData.sourceLabel()}")
                        costData.byAI.filter { it.monthCost > 0 }.forEach { ai ->
                            val tag = when (ai.source) {
                                CostSource.BILLING -> "✓실제"
                                CostSource.HYBRID -> "✓실제"
                                CostSource.ESTIMATED -> "~추정"
                            }
                            lines.add("${ai.name}: $${String.format("%.4f", ai.monthCost)} $tag")
                            ai.monthDiff?.let { diff ->
                                val sign = if (diff >= 0) "+" else ""
                                lines.add("  추정대비 $sign$${String.format("%.4f", diff)}")
                            }
                        }
                        if (costData.error != null) lines.add("⚠ ${costData.error}")
                        adminCostText.text = lines.joinToString("\n")
                    }

                    statusText.text = "마지막 업데이트: ${java.time.LocalTime.now().toString().take(5)}"
                } else if (estimatedData == null && !hasBillingKeys) {
                    statusText.text = "비용 데이터 없음"
                }
            }
        }
    }
}
