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
 */
class FloatingOverlay(private val context: Context) {

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
    }

    fun hide() {
        updateRunnable?.let { handler.removeCallbacks(it) }
        updateRunnable = null
        overlayView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        overlayView = null
    }

    fun isShowing(): Boolean = overlayView != null

    private fun startUpdating(textView: TextView) {
        updateRunnable = object : Runnable {
            override fun run() {
                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                val json = prefs.getString("last_usage", null)
                if (json != null) {
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
                handler.postDelayed(this, 10000) // 10초마다 갱신
            }
        }
        handler.post(updateRunnable!!)
    }

    companion object {
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
