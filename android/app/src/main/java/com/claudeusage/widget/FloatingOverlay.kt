package com.claudeusage.widget

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.preference.PreferenceManager
import com.google.gson.Gson

/**
 * 다른 앱 위에 떠 있는 작은 오버레이.
 * 4가지 정보량 모드 + 탭하면 앱 열기 + 드래그 이동.
 */
class FloatingOverlay private constructor(private val context: Context) {

    // 플로팅 오버레이 정보량 모드
    enum class OverlayMode {
        MINIMAL,  // 🟢 42%
        BASIC,    // 🟢 42% │ 주간 35%
        COST,     // 🟢 42% │ 💰$0.12
        FULL;     // 🟢 42% │ 주간 35% │ 💰$0.12

        fun next(): OverlayMode = entries[(ordinal + 1) % entries.size]

        companion object {
            fun fromString(s: String?): OverlayMode = try {
                valueOf(s ?: "MINIMAL")
            } catch (_: Exception) { MINIMAL }
        }
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null

    fun show() {
        if (overlayView != null) return
        if (!Settings.canDrawOverlays(context)) return

        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val textView = TextView(context).apply {
            text = "세션 --%"
            textSize = 13f
            setTextColor(0xFFe0e0e0.toInt())
            setBackgroundColor(0xCC1a1a2e.toInt())
            setPadding(24, 12, 24, 12)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 20
            y = 100
        }

        // 드래그 + 탭 구분
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        textView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) isDragging = true
                    if (isDragging) {
                        params.x = initialX - dx
                        params.y = initialY + dy
                        windowManager?.updateViewLayout(overlayView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // 탭 → 앱 열기
                        openApp()
                    }
                    true
                }
                else -> false
            }
        }

        overlayView = textView
        windowManager?.addView(textView, params)
        startUpdating(textView)
        saveState(true)
    }

    private fun openApp() {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("auto_refresh", true)
        }
        context.startActivity(intent)
    }

    fun hide() {
        updateRunnable?.let { handler.removeCallbacks(it) }
        updateRunnable = null
        overlayView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        overlayView = null
        saveState(false)
    }

    fun isShowing(): Boolean = overlayView != null

    private fun saveState(showing: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean("overlay_showing", showing).apply()
    }

    private fun startUpdating(textView: TextView) {
        updateRunnable = object : Runnable {
            override fun run() {
                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                val mode = OverlayMode.fromString(prefs.getString("overlay_mode", null))
                updateText(prefs, textView, mode)
                handler.postDelayed(this, 10000)
            }
        }
        handler.post(updateRunnable!!)
    }

    private fun updateText(prefs: android.content.SharedPreferences, textView: TextView, mode: OverlayMode) {
        val usageJson = prefs.getString("last_usage", null)
        val costJson = prefs.getString("last_api_cost", null)

        var sessionPct: Int? = null
        var weeklyPct: Int? = null
        var emoji = "⚪"

        if (usageJson != null) {
            try {
                val usage = Gson().fromJson(usageJson, PlanUsage::class.java)
                sessionPct = usage.session?.usedPercent?.toInt()
                weeklyPct = usage.weekly?.usedPercent?.toInt()
                emoji = when {
                    (sessionPct ?: 0) >= 90 -> "🔴"
                    (sessionPct ?: 0) >= 70 -> "🟡"
                    else -> "🟢"
                }
            } catch (_: Exception) {}
        }

        var todayCost: String? = null
        if (costJson != null) {
            try {
                val cost = Gson().fromJson(costJson, ApiCostData::class.java)
                todayCost = cost.todayText()
            } catch (_: Exception) {}
        }

        val parts = mutableListOf<String>()
        val pctStr = sessionPct?.let { "${it}%" } ?: "--%"

        when (mode) {
            OverlayMode.MINIMAL -> {
                parts.add("$emoji $pctStr")
            }
            OverlayMode.BASIC -> {
                parts.add("$emoji $pctStr")
                weeklyPct?.let { parts.add("주간 ${it}%") }
            }
            OverlayMode.COST -> {
                parts.add("$emoji $pctStr")
                todayCost?.let { parts.add("💰$it") }
            }
            OverlayMode.FULL -> {
                parts.add("$emoji $pctStr")
                weeklyPct?.let { parts.add("주간 ${it}%") }
                todayCost?.let { parts.add("💰$it") }
            }
        }

        textView.text = parts.joinToString(" │ ")
    }

    companion object {
        @Volatile
        private var instance: FloatingOverlay? = null

        fun getInstance(context: Context): FloatingOverlay {
            return instance ?: synchronized(this) {
                instance ?: FloatingOverlay(context.applicationContext).also { instance = it }
            }
        }

        fun wasShowing(context: Context): Boolean =
            PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean("overlay_showing", false)

        fun hasPermission(context: Context): Boolean = Settings.canDrawOverlays(context)

        fun requestPermission(context: Context) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }
}
