package com.claudeusage.widget

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
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

    // ── 스킨 정의 ──
    data class SkinStyle(
        val bgStartColor: Int,
        val bgEndColor: Int,
        val textColor: Int,
        val cornerRadius: Float,
    )

    val SKIN_STYLES = mapOf(
        "default" to SkinStyle(0xEB1a1a2e.toInt(), 0xEB16213e.toInt(), 0xFFe0e0e0.toInt(), 16f),
        "spring" to SkinStyle(0xF0ffe4eb.toInt(), 0xF0fff5f5.toInt(), 0xFF5c3d4e.toInt(), 32f),
        "summer" to SkinStyle(0xF0c8ebff.toInt(), 0xF0e8f4f8.toInt(), 0xFF1a4a5e.toInt(), 28f),
        "autumn" to SkinStyle(0xF0fae6c8.toInt(), 0xF0faf5ef.toInt(), 0xFF5a3e1e.toInt(), 28f),
        "winter" to SkinStyle(0xF0dcebff.toInt(), 0xF0eef2f7.toInt(), 0xFF2c3e5a.toInt(), 28f),
        "fluffy-pink" to SkinStyle(0xF5ffdcf0.toInt(), 0xF5fff0f6.toInt(), 0xFF6e3050.toInt(), 40f),
        "fluffy-purple" to SkinStyle(0xF5e6d7ff.toInt(), 0xF5f5f0ff.toInt(), 0xFF3e2060.toInt(), 40f),
        "fluffy-mint" to SkinStyle(0xF5c8f5e6.toInt(), 0xF5f0faf7.toInt(), 0xFF1a4a3a.toInt(), 40f),
        "fluffy-yellow" to SkinStyle(0xF5fff5b4.toInt(), 0xF5fffde8.toInt(), 0xFF5a4a10.toInt(), 40f),
        "fluffy-sky" to SkinStyle(0xF5d2e6ff.toInt(), 0xF5f0f6ff.toInt(), 0xFF1a3060.toInt(), 40f),
    )

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
            setPadding(24, 14, 24, 14)
        }

        overlayView = textView

        // 스킨 적용 (custom 포함)
        updateSkin()

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

    fun updateSkin() {
        val tv = overlayView as? TextView ?: return
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val skinId = prefs.getString("skin", "default") ?: "default"

        if (skinId == "custom") {
            // 커스텀 스킨: 사진 배경
            val uriStr = prefs.getString("custom_skin_uri", null)
            if (uriStr != null) {
                try {
                    val uri = Uri.parse(uriStr)
                    val stream = context.contentResolver.openInputStream(uri)
                    val bitmap = android.graphics.BitmapFactory.decodeStream(stream)
                    stream?.close()
                    if (bitmap != null) {
                        val drawable = android.graphics.drawable.BitmapDrawable(context.resources, bitmap)
                        drawable.alpha = 220
                        tv.background = drawable
                        tv.setTextColor(0xFFffffff.toInt())
                        tv.setShadowLayer(4f, 1f, 1f, 0xFF000000.toInt())
                        tv.elevation = 6f
                        return
                    }
                } catch (_: Exception) {}
            }
            // fallback to default
            val skin = SKIN_STYLES["default"]!!
            tv.background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(skin.bgStartColor, skin.bgEndColor)
            ).apply { cornerRadius = skin.cornerRadius }
            tv.setTextColor(skin.textColor)
            tv.setShadowLayer(0f, 0f, 0f, 0)
            tv.elevation = 4f
        } else {
            val skin = SKIN_STYLES[skinId] ?: SKIN_STYLES["default"]!!
            tv.background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(skin.bgStartColor, skin.bgEndColor)
            ).apply { cornerRadius = skin.cornerRadius }
            tv.setTextColor(skin.textColor)
            tv.setShadowLayer(0f, 0f, 0f, 0)
            tv.elevation = if (skinId.startsWith("fluffy")) 8f else 4f
        }
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
