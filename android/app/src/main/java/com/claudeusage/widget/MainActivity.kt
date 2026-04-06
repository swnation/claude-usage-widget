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
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var isServiceRunning = false

    // UI elements
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
                toggleButton.text = "Start Monitoring"
                statusText.text = "Service stopped"
            } else {
                val key = sessionKeyInput.text.toString().trim()
                if (key.isEmpty()) {
                    Toast.makeText(this, "Enter session key first", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                saveSettings()
                UsageMonitorService.start(this)
                isServiceRunning = true
                toggleButton.text = "Stop Monitoring"
                statusText.text = "Service running"
            }
            saveServiceState()
        }

        refreshButton.setOnClickListener { fetchAndDisplay() }

        saveButton.setOnClickListener {
            saveSettings()
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
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
        if (isServiceRunning) {
            toggleButton.text = "Stop Monitoring"
        }

        if (key.isNotEmpty()) {
            fetchAndDisplay()
        }
    }

    private fun saveSettings() {
        val key = sessionKeyInput.text.toString().trim()
        PreferenceManager.getDefaultSharedPreferences(this).edit()
            .putString("session_key", key)
            .putString("refresh_interval", refreshInput.text.toString().trim())
            .apply()
        webClient.updateSessionKey(key)
    }

    private fun saveServiceState() {
        PreferenceManager.getDefaultSharedPreferences(this).edit()
            .putBoolean("service_running", isServiceRunning)
            .apply()
    }

    private fun fetchAndDisplay() {
        statusText.text = "Fetching..."
        sessionStatus.text = "Checking..."

        executor.execute {
            val result = webClient.fetchUsage()
            handler.post {
                result.onSuccess { usage ->
                    displayUsage(usage)
                    statusText.text = "Updated: ${usage.lastUpdated.takeLast(13)}"
                    sessionStatus.text = "✓ Connected"
                    sessionStatus.setTextColor(getColor(android.R.color.holo_green_light))
                }.onFailure { error ->
                    statusText.text = "Error: ${error.message}"
                    sessionStatus.text = "✗ ${error.message?.take(40)}"
                    sessionStatus.setTextColor(getColor(android.R.color.holo_red_light))
                }
            }
        }
    }

    private fun displayUsage(usage: PlanUsage) {
        planNameText.text = "Claude ${usage.planName}"

        modelsContainer.removeAllViews()

        for (model in usage.models) {
            val card = layoutInflater.inflate(R.layout.item_model_usage, modelsContainer, false)

            val nameText = card.findViewById<TextView>(R.id.modelName)
            val usageText = card.findViewById<TextView>(R.id.modelUsageText)
            val bar = card.findViewById<ProgressBar>(R.id.modelBar)
            val remainingText = card.findViewById<TextView>(R.id.modelRemaining)
            val resetText = card.findViewById<TextView>(R.id.modelReset)

            nameText.text = model.modelName
            usageText.text = "${model.percentText}  (${model.used}/${model.limit})"
            bar.max = 100
            bar.progress = model.usedPercent.toInt().coerceIn(0, 100)

            val color = when {
                model.usedPercent >= 90 -> getColor(android.R.color.holo_red_light)
                model.usedPercent >= 70 -> getColor(android.R.color.holo_orange_light)
                else -> getColor(android.R.color.holo_green_light)
            }
            bar.progressTintList = android.content.res.ColorStateList.valueOf(color)

            remainingText.text = model.remainingText()
            remainingText.setTextColor(color)

            if (model.resetsAt.isNotEmpty()) {
                try {
                    val resetInstant = java.time.Instant.parse(model.resetsAt)
                    val now = java.time.Instant.now()
                    val remaining = java.time.Duration.between(now, resetInstant)
                    val h = remaining.toHours()
                    val m = remaining.toMinutes() % 60
                    resetText.text = "Resets in ${if (h > 0) "${h}h " else ""}${m}m"
                } catch (e: Exception) {
                    resetText.text = ""
                }
            } else {
                resetText.text = ""
            }

            modelsContainer.addView(card)
        }
    }
}
