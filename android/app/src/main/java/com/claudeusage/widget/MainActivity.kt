package com.claudeusage.widget

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager

class MainActivity : AppCompatActivity() {

    private lateinit var repository: UsageRepository
    private var isServiceRunning = false

    // Usage display
    private lateinit var costText: TextView
    private lateinit var costBar: ProgressBar
    private lateinit var tokenText: TextView
    private lateinit var tokenBar: ProgressBar
    private lateinit var detailText: TextView
    private lateinit var statusText: TextView
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

    // Controls
    private lateinit var toggleButton: Button
    private lateinit var refreshButton: Button
    private lateinit var budgetInput: EditText
    private lateinit var tokenLimitInput: EditText
    private lateinit var refreshInput: EditText
    private lateinit var saveButton: Button

    // Manual tracking
    private lateinit var trackModelInput: EditText
    private lateinit var trackInputTokens: EditText
    private lateinit var trackOutputTokens: EditText
    private lateinit var trackButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        repository = UsageRepository(this)

        requestNotificationPermission()
        initViews()
        loadSettings()
        refreshDisplay()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100
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

        toggleButton = findViewById(R.id.toggleButton)
        refreshButton = findViewById(R.id.refreshButton)
        budgetInput = findViewById(R.id.budgetInput)
        tokenLimitInput = findViewById(R.id.tokenLimitInput)
        refreshInput = findViewById(R.id.refreshInput)
        saveButton = findViewById(R.id.saveButton)

        trackModelInput = findViewById(R.id.trackModelInput)
        trackInputTokens = findViewById(R.id.trackInputTokens)
        trackOutputTokens = findViewById(R.id.trackOutputTokens)
        trackButton = findViewById(R.id.trackButton)

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
            saveServiceState()
        }

        refreshButton.setOnClickListener { refreshDisplay() }

        saveButton.setOnClickListener {
            saveSettings()
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
            if (isServiceRunning) {
                UsageMonitorService.stop(this)
                UsageMonitorService.start(this)
            }
            refreshDisplay()
        }

        trackButton.setOnClickListener {
            val model = trackModelInput.text.toString().trim().ifEmpty { "claude-sonnet-4-6" }
            val inTok = trackInputTokens.text.toString().toLongOrNull() ?: 0
            val outTok = trackOutputTokens.text.toString().toLongOrNull() ?: 0

            if (inTok == 0L && outTok == 0L) {
                Toast.makeText(this, "Enter token counts", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            repository.trackUsage(model, inTok, outTok)
            trackInputTokens.text.clear()
            trackOutputTokens.text.clear()
            Toast.makeText(this, "Tracked!", Toast.LENGTH_SHORT).show()
            refreshDisplay()
        }
    }

    private fun loadSettings() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        budgetInput.setText(prefs.getString("monthly_budget", "100"))
        tokenLimitInput.setText(prefs.getString("monthly_token_limit", "10000000"))
        refreshInput.setText(prefs.getString("refresh_interval", "60"))

        isServiceRunning = prefs.getBoolean("service_running", false)
        if (isServiceRunning) {
            toggleButton.text = "Stop Monitoring"
        }
    }

    private fun saveSettings() {
        PreferenceManager.getDefaultSharedPreferences(this).edit()
            .putString("monthly_budget", budgetInput.text.toString().trim())
            .putString("monthly_token_limit", tokenLimitInput.text.toString().trim())
            .putString("refresh_interval", refreshInput.text.toString().trim())
            .apply()
    }

    private fun saveServiceState() {
        PreferenceManager.getDefaultSharedPreferences(this).edit()
            .putBoolean("service_running", isServiceRunning)
            .apply()
    }

    private fun refreshDisplay() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val budget = prefs.getString("monthly_budget", "100")?.toDoubleOrNull() ?: 100.0
        val tokenLimit = prefs.getString("monthly_token_limit", "10000000")?.toLongOrNull() ?: 10_000_000L

        val data = repository.aggregate(budget, tokenLimit)
        updateUI(data)
        statusText.text = "Entries: ${repository.getEntryCount()} │ Updated: ${data.lastUpdated.takeLast(13)}"
    }

    private fun updateUI(data: UsageData) {
        costText.text = data.costSummary()
        costBar.progress = data.budgetUsedPercent.toInt().coerceIn(0, 100)
        budgetPercentText.text = "${String.format("%.1f", data.budgetUsedPercent)}%"

        tokenText.text = data.tokenSummary()
        tokenBar.progress = data.tokensUsedPercent.toInt().coerceIn(0, 100)
        tokenPercentText.text = "${String.format("%.1f", data.tokensUsedPercent)}%"

        detailText.text = "📥 Input: ${data.formatTokens(data.inputTokens)}   📤 Output: ${data.formatTokens(data.outputTokens)}"

        updatePeriodRow(data, "5h", period5hCost, period5hDetail)
        updatePeriodRow(data, "daily", periodDailyCost, periodDailyDetail)
        updatePeriodRow(data, "weekly", periodWeeklyCost, periodWeeklyDetail)
        updatePeriodRow(data, "monthly", periodMonthlyCost, periodMonthlyDetail)

        val costColor = when {
            data.budgetUsedPercent >= 90 -> getColor(android.R.color.holo_red_light)
            data.budgetUsedPercent >= 75 -> getColor(android.R.color.holo_orange_light)
            else -> getColor(android.R.color.holo_green_light)
        }
        costBar.progressTintList = android.content.res.ColorStateList.valueOf(costColor)
        tokenBar.progressTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.purple_500))
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
