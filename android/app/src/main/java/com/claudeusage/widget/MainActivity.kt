package com.claudeusage.widget

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var webClient: ClaudeWebClient
    private lateinit var accumulator: UsageAccumulator
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var isServiceRunning = false

    private lateinit var statusText: TextView
    private lateinit var planNameText: TextView
    private lateinit var modelsContainer: LinearLayout
    private lateinit var toggleButton: Button
    private lateinit var refreshButton: Button
    private lateinit var sessionKeyInput: EditText
    private lateinit var refreshInput: EditText
    private lateinit var saveButton: Button
    private lateinit var sessionStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        accumulator = UsageAccumulator(this)

        requestNotificationPermission()
        initViews()
        loadSettings()
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
        modelsContainer = findViewById(R.id.modelsContainer)
        toggleButton = findViewById(R.id.toggleButton)
        refreshButton = findViewById(R.id.refreshButton)
        sessionKeyInput = findViewById(R.id.sessionKeyInput)
        refreshInput = findViewById(R.id.refreshInput)
        saveButton = findViewById(R.id.saveButton)
        sessionStatus = findViewById(R.id.sessionStatus)

        toggleButton.setOnClickListener {
            if (isServiceRunning) {
                UsageMonitorService.stop(this)
                isServiceRunning = false
                toggleButton.text = "모니터링 시작"
                statusText.text = "서비스 중지됨"
            } else {
                val key = sessionKeyInput.text.toString().trim()
                if (key.isEmpty()) {
                    Toast.makeText(this, "세션 키를 먼저 입력하세요", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                saveSettings()
                UsageMonitorService.start(this)
                isServiceRunning = true
                toggleButton.text = "모니터링 중지"
                statusText.text = "서비스 실행 중"
            }
            saveServiceState()
        }

        refreshButton.setOnClickListener { fetchAndDisplay() }

        saveButton.setOnClickListener {
            saveSettings()
            Toast.makeText(this, "저장됨", Toast.LENGTH_SHORT).show()
            if (isServiceRunning) {
                UsageMonitorService.stop(this)
                UsageMonitorService.start(this)
            }
        }
    }

    private fun loadSettings() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        sessionKeyInput.setText(prefs.getString("session_key", ""))
        refreshInput.setText(prefs.getString("refresh_interval", "120"))

        val key = prefs.getString("session_key", "") ?: ""
        webClient = ClaudeWebClient(key)

        isServiceRunning = prefs.getBoolean("service_running", false)
        toggleButton.text = if (isServiceRunning) "모니터링 중지" else "모니터링 시작"

        if (key.isNotEmpty()) fetchAndDisplay()
    }

    private fun saveSettings() {
        PreferenceManager.getDefaultSharedPreferences(this).edit()
            .putString("session_key", sessionKeyInput.text.toString().trim())
            .putString("refresh_interval", refreshInput.text.toString().trim())
            .apply()
        webClient.updateSessionKey(sessionKeyInput.text.toString().trim())
    }

    private fun saveServiceState() {
        PreferenceManager.getDefaultSharedPreferences(this).edit()
            .putBoolean("service_running", isServiceRunning)
            .apply()
    }

    private fun fetchAndDisplay() {
        statusText.text = "불러오는 중..."
        sessionStatus.text = "확인 중..."

        executor.execute {
            val result = webClient.fetchUsage()
            handler.post {
                result.onSuccess { usage ->
                    val enriched = enrichWithAccumulated(usage)
                    displayUsage(enriched)
                    statusText.text = "마지막 업데이트: ${usage.lastUpdated.takeLast(13)}"
                    sessionStatus.text = "✓ 연결됨"
                    sessionStatus.setTextColor(getColor(android.R.color.holo_green_light))
                }.onFailure { error ->
                    statusText.text = "오류: ${error.message}"
                    sessionStatus.text = "✗ ${error.message?.take(40)}"
                    sessionStatus.setTextColor(getColor(android.R.color.holo_red_light))
                }
            }
        }
    }

    private fun enrichWithAccumulated(usage: PlanUsage): PlanUsage {
        val enrichedModels = usage.models.map { model ->
            accumulator.update(model.modelName, model.used)
            val acc = accumulator.getAccumulated(model.modelName, model.used)
            model.copy(weeklyUsed = acc.weeklyMessages, totalUsed = acc.totalMessages)
        }
        return usage.copy(models = enrichedModels)
    }

    private fun displayUsage(usage: PlanUsage) {
        planNameText.text = "Claude ${usage.planName}"
        modelsContainer.removeAllViews()

        for (model in usage.models) {
            val card = layoutInflater.inflate(R.layout.item_model_usage, modelsContainer, false)

            val nameText = card.findViewById<TextView>(R.id.modelName)
            val percentText = card.findViewById<TextView>(R.id.modelPercent)
            val countText = card.findViewById<TextView>(R.id.modelCount)
            val bar = card.findViewById<ProgressBar>(R.id.modelBar)
            val remainingText = card.findViewById<TextView>(R.id.modelRemaining)
            val resetText = card.findViewById<TextView>(R.id.modelReset)
            val weeklyText = card.findViewById<TextView>(R.id.modelWeekly)
            val totalText = card.findViewById<TextView>(R.id.modelTotal)

            nameText.text = model.modelName
            percentText.text = model.percentText
            countText.text = "${model.used} / ${model.limit} 메시지"
            bar.max = 100
            bar.progress = model.usedPercent.toInt().coerceIn(0, 100)

            val color = when {
                model.usedPercent >= 90 -> getColor(android.R.color.holo_red_light)
                model.usedPercent >= 70 -> getColor(android.R.color.holo_orange_light)
                else -> getColor(android.R.color.holo_green_light)
            }
            bar.progressTintList = android.content.res.ColorStateList.valueOf(color)
            percentText.setTextColor(color)

            remainingText.text = model.remainingText()
            remainingText.setTextColor(color)
            resetText.text = model.formatResetTime()

            weeklyText.text = "주간: ${model.weeklyUsed}개"
            totalText.text = "총: ${model.totalUsed}개"

            modelsContainer.addView(card)
        }
    }
}
