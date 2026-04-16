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
 * 고정 크기 (300dp × 44dp) + 이미지 배경 + 사용자 글씨 색.
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

    // 앱 전체 스킨 색상 (기본 다크만 유지)
    data class AppSkinColors(
        val bgColor: Int,
        val sectionBgColor: Int,
        val cardBgColor: Int,
        val textColor: Int,
        val subtextColor: Int,
        val accentColor: Int,
        val isDark: Boolean,
    )

    companion object {
        // 오버레이 고정 크기 (dp)
        const val OVERLAY_WIDTH_DP = 300
        const val OVERLAY_HEIGHT_DP = 44

        // 기본 다크 색상
        private val DEFAULT_APP_COLORS = AppSkinColors(
            0xFF1a1a2e.toInt(), 0xFF16213e.toInt(), 0xFF22223a.toInt(),
            0xFFe0e0e0.toInt(), 0xFF888899.toInt(), 0xFFc084fc.toInt(), true
        )

        @Volatile
        private var instance: FloatingOverlay? = null

        fun getInstance(context: Context): FloatingOverlay {
            return instance ?: synchronized(this) {
                instance ?: FloatingOverlay(context.applicationContext).also { instance = it }
            }
        }

        /** 앱 전체 색상 (기본 다크) */
        fun getAppColors(context: Context): AppSkinColors {
            return try {
                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                val skinId = prefs.getString("skin", "default") ?: "default"
                if (skinId == "custom-file") {
                    val json = prefs.getString("custom_skin_json", null)
                    val skinData = json?.let { CustomSkinData.fromJson(it) }
                    if (skinData != null) return skinData.toAppSkinColors()
                }
                DEFAULT_APP_COLORS
            } catch (_: Exception) {
                DEFAULT_APP_COLORS
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

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null

    fun show() {
        if (overlayView != null) return
        if (!Settings.canDrawOverlays(context)) return

        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val dp = context.resources.displayMetrics.density
        val widthPx = (OVERLAY_WIDTH_DP * dp).toInt()
        val heightPx = (OVERLAY_HEIGHT_DP * dp).toInt()

        val textView = TextView(context).apply {
            text = "세션 --%"
            textSize = 13f
            gravity = Gravity.CENTER
        }

        overlayView = textView
        updateSkin()

        val params = WindowManager.LayoutParams(
            widthPx,
            heightPx,
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
                    if (!isDragging) openApp()
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
        try { applySkinInternal(tv, prefs) } catch (_: Exception) { applyDefaultSkin(tv, prefs) }
    }

    private fun applySkinInternal(tv: TextView, prefs: android.content.SharedPreferences) {
        // 1. 오버레이 이미지 확인
        val overlayImagePath = prefs.getString("overlay_image_path", null)
        if (overlayImagePath != null) {
            val bitmap = android.graphics.BitmapFactory.decodeFile(overlayImagePath)
            if (bitmap != null) {
                val drawable = android.graphics.drawable.BitmapDrawable(context.resources, bitmap)
                tv.background = drawable
                tv.setTextColor(0xFFe0e0e0.toInt())
                applyCustomTextColor(prefs, tv)
                tv.setShadowLayer(0f, 0f, 0f, 0)
                tv.elevation = 0f
                return
            }
        }

        // 2. .cskin 파일 스킨 (고급, 하위 호환)
        val skinId = prefs.getString("skin", "default") ?: "default"
        if (skinId == "custom-file") {
            val json = prefs.getString("overlay_custom_skin_json", null)
                ?: prefs.getString("custom_skin_json", null)
            val skinData = json?.let { CustomSkinData.fromJson(it) }
            if (skinData != null) {
                val bgPath = prefs.getString("overlay_cskin_overlay_bg_path", null)
                    ?: prefs.getString("overlay_cskin_bg_path", null)
                    ?: prefs.getString("cskin_overlay_bg_path", null)
                    ?: prefs.getString("cskin_bg_path", null)
                val fileBitmap = bgPath?.let { android.graphics.BitmapFactory.decodeFile(it) }
                val bgImage = fileBitmap ?: skinData.overlayBgImage()
                val isImageType = fileBitmap != null || (skinData.overlay?.background?.type == "image" && bgImage != null)
                if (isImageType && bgImage != null) {
                    val drawable = android.graphics.drawable.BitmapDrawable(context.resources, bgImage)
                    val opacity = skinData.overlay?.background?.opacity ?: 0.92
                    drawable.alpha = (opacity * 255).toInt().coerceIn(0, 255)
                    tv.background = drawable
                } else if (skinData.isSolidBackground()) {
                    tv.background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        setColor(skinData.overlaySolidColor())
                        cornerRadius = skinData.overlayCornerRadius()
                    }
                } else {
                    tv.background = GradientDrawable(
                        skinData.overlayGradientOrientation(),
                        skinData.overlayBgColors()
                    ).apply { cornerRadius = skinData.overlayCornerRadius() }
                }
                tv.setTextColor(skinData.overlayTextColor())
                applyCustomTextColor(prefs, tv)
                tv.elevation = skinData.overlayElevation()
                val shadow = skinData.overlay?.text?.shadow
                if (shadow != null && shadow.radius > 0) {
                    tv.setShadowLayer(shadow.radius, shadow.dx, shadow.dy,
                        CustomSkinData.parseColor(shadow.color, 0xFF000000.toInt()))
                } else {
                    tv.setShadowLayer(0f, 0f, 0f, 0)
                }
                return
            }
        }

        // 3. 기본 다크
        applyDefaultSkin(tv, prefs)
    }

    private fun applyCustomTextColor(prefs: android.content.SharedPreferences, tv: TextView) {
        val custom = prefs.getString("overlay_text_color", null) ?: return
        try { tv.setTextColor(Color.parseColor(custom)) } catch (_: Exception) {}
    }

    private fun applyDefaultSkin(tv: TextView, prefs: android.content.SharedPreferences? = null) {
        tv.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xEB1a1a2e.toInt(), 0xEB16213e.toInt())
        ).apply { cornerRadius = 16f }
        tv.setTextColor(0xFFe0e0e0.toInt())
        if (prefs != null) {
            applyCustomTextColor(prefs, tv)
        } else {
            val p = PreferenceManager.getDefaultSharedPreferences(context)
            applyCustomTextColor(p, tv)
        }
        tv.setShadowLayer(0f, 0f, 0f, 0)
        tv.elevation = 4f
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
            OverlayMode.MINIMAL -> parts.add("$emoji $pctStr")
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
}
