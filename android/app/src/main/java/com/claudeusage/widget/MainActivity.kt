package com.claudeusage.widget

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var apiClient: UsageApiClient
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var isServiceRunning = false

    // UI elements
    private lateinit var costText: TextView
    private lateinit var costBar: ProgressBar
    private lateinit var tokenText: TextView
    private lateinit var tokenBar: ProgressBar
    private lateinit var detailText: TextView
    private lateinit var statusText: TextView
    private lateinit var toggleButton: Button
    private lateinit var serverUrlInput: EditText
    private lateinit var refreshInput: EditText
    private lateinit var saveButton: Button
    private lateinit var budgetPercentText: TextView
    private lateinit var tokenPercentText: TextView

    // Period views
    private lateinit var period5hCost: TextView
    private lateinit var period5hDetail: TextView
    private lateinit var periodDailyCost: TextView
    private lateinit var periodDailyDetail: TextView
    private lateinit var periodWeeklyCost: TextView
    private lateinit var periodWeeklyDetail: TextView
    private lateinit var periodMonthlyCost: TextView
    private lateinit var periodMonthlyDetail: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        requestNotificationPermission()
        initViews()
        loadSettings()
        refreshUsageDisplay()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    100
                )
            }
        }
    }

    private fun initViews() {
        costText = findViewById(R.id.costText)
        costBar = findViewById(R.id.costBar)
        tokenText = findViewById(R.id.tokenText)
        tokenBar = findViewById(R.id.tokenBar)
        detailText = findViewById(R.id.detailText)
        statusText = findViewById(R.id.statusText)
        toggleButton = findViewById(R.id.toggleButton)
        serverUrlInput = findViewById(R.id.serverUrlInput)
        refreshInput = findViewById(R.id.refreshInput)
        saveButton = findViewById(R.id.saveButton)
        budgetPercentText = findViewById(R.id.budgetPercentText)
        tokenPercentText = findViewById(R.id.tokenPercentText)

        period5hCost = findViewById(R.id.period5hCost)
        period5hDetail = findViewById(R.id.period5hDetail)
        periodDailyCost = findViewById(R.id.periodDailyCost)
        periodDailyDetail = findViewById(R.id.periodDailyDetail)
        periodWeeklyCost = findViewById(R.id.periodWeeklyCost)
        periodWeeklyDetail = findViewById(R.id.periodWeeklyDetail)
        periodMonthlyCost = findViewById(R.id.periodMonthlyCost)
        periodMonthlyDetail = findViewById(R.id.periodMonthlyDetail)

        toggleButton.setOnClickListener {
            if (isServiceRunning) {
                UsageMonitorService.stop(this)
                isServiceRunning = false
                toggleButton.text = "Start Monitoring"
                statusText.text = "Service stopped"
            } else {
                UsageMonitorService.start(this)
                isServiceRunning = true
                toggleButton.text = "Stop Monitoring"
                statusText.text = "Service running"
            }
        }

        saveButton.setOnClickListener {
            saveSettings()
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
            // Restart service if running
            if (isServiceRunning) {
                UsageMonitorService.stop(this)
                UsageMonitorService.start(this)
            }
        }

        findViewById<Button>(R.id.refreshButton).setOnClickListener {
            refreshUsageDisplay()
        }
    }

    private fun loadSettings() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val serverUrl = prefs.getString("server_url", "http://10.0.2.2:8490") ?: "http://10.0.2.2:8490"
        val refreshInterval = prefs.getString("refresh_interval", "60") ?: "60"

        serverUrlInput.setText(serverUrl)
        refreshInput.setText(refreshInterval)

        apiClient = UsageApiClient(serverUrl)

        // Auto-start service
        isServiceRunning = prefs.getBoolean("service_running", false)
        if (isServiceRunning) {
            toggleButton.text = "Stop Monitoring"
        }
    }

    private fun saveSettings() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val serverUrl = serverUrlInput.text.toString().trim()
        val refreshInterval = refreshInput.text.toString().trim()

        prefs.edit()
            .putString("server_url", serverUrl)
            .putString("refresh_interval", refreshInterval)
            .putBoolean("service_running", isServiceRunning)
            .apply()

        apiClient.updateServerUrl(serverUrl)
    }

    private fun refreshUsageDisplay() {
        statusText.text = "Fetching..."

        executor.execute {
            val result = apiClient.fetchUsage()
            handler.post {
                result.onSuccess { data ->
                    updateUI(data)
                    statusText.text = "Last updated: ${data.lastUpdated.takeLast(8)}"
                }.onFailure { e ->
                    statusText.text = "Error: ${e.message}"
                }
            }
        }
    }

    private fun updateUI(data: UsageData) {
        costText.text = data.costSummary()
        costBar.progress = data.budgetUsedPercent.toInt().coerceIn(0, 100)
        budgetPercentText.text = "${String.format("%.1f", data.budgetUsedPercent)}%"

        tokenText.text = data.tokenSummary()
        tokenBar.progress = data.tokensUsedPercent.toInt().coerceIn(0, 100)
        tokenPercentText.text = "${String.format("%.1f", data.tokensUsedPercent)}%"

        detailText.text = "📥 Input: ${data.formatTokens(data.inputTokens)}   📤 Output: ${data.formatTokens(data.outputTokens)}"

        // Period breakdown
        updatePeriodRow(data, "5h", period5hCost, period5hDetail)
        updatePeriodRow(data, "daily", periodDailyCost, periodDailyDetail)
        updatePeriodRow(data, "weekly", periodWeeklyCost, periodWeeklyDetail)
        updatePeriodRow(data, "monthly", periodMonthlyCost, periodMonthlyDetail)

        // Color progress bars based on usage
        val costColor = when {
            data.budgetUsedPercent >= 90 -> getColor(android.R.color.holo_red_light)
            data.budgetUsedPercent >= 75 -> getColor(android.R.color.holo_orange_light)
            else -> getColor(android.R.color.holo_green_light)
        }
        costBar.progressTintList = android.content.res.ColorStateList.valueOf(costColor)
        tokenBar.progressTintList = android.content.res.ColorStateList.valueOf(
            getColor(R.color.purple_500)
        )
    }

    private fun updatePeriodRow(data: UsageData, key: String, costView: TextView, detailView: TextView) {
        val p = data.periods[key]
        if (p != null) {
            costView.text = data.formatCost(p.costUsd)
            detailView.text = "${data.formatTokens(p.tokens)} tok │ ${p.requests} reqs"
        } else {
            costView.text = "$0.00"
            detailView.text = "No data"
        }
    }
}
