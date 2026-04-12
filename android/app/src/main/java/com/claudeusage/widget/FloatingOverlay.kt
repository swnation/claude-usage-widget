package com.claudeusage.widget

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
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
 * 다른 앱 위에 떠 있는 작은 오버레이 — 5시간 세션 사용량 실시간 표시.
 * 싱글톤 — 앱 재시작 시 중복 생성 방지.
 */
class FloatingOverlay private constructor(private val context: Context) {

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

        // 드래그 이동
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        textView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX - (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(overlayView, params)
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
                val mode = DisplayMode.fromString(prefs.getString("display_mode", null))

                when (mode) {
                    DisplayMode.CLAUDE_ONLY -> updateClaudeOnly(prefs, textView)
                    DisplayMode.API_COST_ONLY -> updateApiCostOnly(prefs, textView)
                    DisplayMode.BOTH -> updateBoth(prefs, textView)
                }

                handler.postDelayed(this, 10000)
            }
        }
        handler.post(updateRunnable!!)
    }

    private fun updateClaudeOnly(prefs: android.content.SharedPreferences, textView: TextView) {
        val json = prefs.getString("last_usage", null) ?: return
        try {
            val usage = Gson().fromJson(json, PlanUsage::class.java)
            val pct = usage.session?.usedPercent?.toInt() ?: 0
            val emoji = when {
                pct >= 90 -> "🔴"
                pct >= 70 -> "🟡"
                else -> "🟢"
            }
            textView.text = "$emoji 세션 ${pct}%"
        } catch (_: Exception) {}
    }

    private fun updateApiCostOnly(prefs: android.content.SharedPreferences, textView: TextView) {
        val json = prefs.getString("last_api_cost", null) ?: return
        try {
            val cost = Gson().fromJson(json, ApiCostData::class.java)
            textView.text = "💰 ${cost.todayText()}"
        } catch (_: Exception) {}
    }

    private fun updateBoth(prefs: android.content.SharedPreferences, textView: TextView) {
        val usageJson = prefs.getString("last_usage", null)
        val costJson = prefs.getString("last_api_cost", null)

        val parts = mutableListOf<String>()
        if (usageJson != null) {
            try {
                val usage = Gson().fromJson(usageJson, PlanUsage::class.java)
                val pct = usage.session?.usedPercent?.toInt() ?: 0
                val emoji = when {
                    pct >= 90 -> "🔴"
                    pct >= 70 -> "🟡"
                    else -> "🟢"
                }
                parts.add("$emoji${pct}%")
            } catch (_: Exception) {}
        }
        if (costJson != null) {
            try {
                val cost = Gson().fromJson(costJson, ApiCostData::class.java)
                parts.add("💰${cost.todayText()}")
            } catch (_: Exception) {}
        }

        if (parts.isNotEmpty()) {
            textView.text = parts.joinToString(" ")
        }
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
