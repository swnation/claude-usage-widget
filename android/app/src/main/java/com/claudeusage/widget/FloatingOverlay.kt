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

    // 앱 전체 스킨 색상 (Activity, Widget, Notification)
    data class AppSkinColors(
        val bgColor: Int,         // 메인 배경
        val sectionBgColor: Int,  // 섹션 헤더/바디 배경
        val cardBgColor: Int,     // 카드/입력 배경
        val textColor: Int,       // 주요 텍스트
        val subtextColor: Int,    // 보조 텍스트
        val accentColor: Int,     // 강조색 (버튼, 하이라이트)
        val isDark: Boolean,      // 다크 테마 여부
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

    val APP_SKIN_COLORS = mapOf(
        "default" to AppSkinColors(0xFF1a1a2e.toInt(), 0xFF16213e.toInt(), 0xFF22223a.toInt(), 0xFFe0e0e0.toInt(), 0xFF888899.toInt(), 0xFFc084fc.toInt(), true),
        "spring" to AppSkinColors(0xFFffe4eb.toInt(), 0xFFffd6e0.toInt(), 0xFFfff0f3.toInt(), 0xFF5c3d4e.toInt(), 0xFF9a7a8a.toInt(), 0xFFd4608a.toInt(), false),
        "summer" to AppSkinColors(0xFFc8ebff.toInt(), 0xFFb0d8f0.toInt(), 0xFFe0f2ff.toInt(), 0xFF1a4a5e.toInt(), 0xFF5a8a9e.toInt(), 0xFF2a8ab8.toInt(), false),
        "autumn" to AppSkinColors(0xFFfae6c8.toInt(), 0xFFf0d4a8.toInt(), 0xFFfff5e8.toInt(), 0xFF5a3e1e.toInt(), 0xFF9a7e5e.toInt(), 0xFFc07030.toInt(), false),
        "winter" to AppSkinColors(0xFFdcebff.toInt(), 0xFFc8d8f0.toInt(), 0xFFeef2f7.toInt(), 0xFF2c3e5a.toInt(), 0xFF6a7a9a.toInt(), 0xFF5a7ab5.toInt(), false),
        "fluffy-pink" to AppSkinColors(0xFFffdcf0.toInt(), 0xFFffc8e4.toInt(), 0xFFfff0f6.toInt(), 0xFF6e3050.toInt(), 0xFFa06888.toInt(), 0xFFd05090.toInt(), false),
        "fluffy-purple" to AppSkinColors(0xFFe6d7ff.toInt(), 0xFFd4c0f8.toInt(), 0xFFf5f0ff.toInt(), 0xFF3e2060.toInt(), 0xFF7a60a0.toInt(), 0xFF8050c0.toInt(), false),
        "fluffy-mint" to AppSkinColors(0xFFc8f5e6.toInt(), 0xFFa8e8d0.toInt(), 0xFFf0faf7.toInt(), 0xFF1a4a3a.toInt(), 0xFF5a8a7a.toInt(), 0xFF30a070.toInt(), false),
        "fluffy-yellow" to AppSkinColors(0xFFfff5b4.toInt(), 0xFFf0e890.toInt(), 0xFFfffde8.toInt(), 0xFF5a4a10.toInt(), 0xFF9a8a40.toInt(), 0xFFb0a020.toInt(), false),
        "fluffy-sky" to AppSkinColors(0xFFd2e6ff.toInt(), 0xFFb8d4f8.toInt(), 0xFFf0f6ff.toInt(), 0xFF1a3060.toInt(), 0xFF5a7098.toInt(), 0xFF4070c0.toInt(), false),
        "custom" to AppSkinColors(0xFF1a1a2e.toInt(), 0xFF16213e.toInt(), 0xFF22223a.toInt(), 0xFFe0e0e0.toInt(), 0xFF888899.toInt(), 0xFFc084fc.toInt(), true),
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
        try { applySkinInternal(tv) } catch (_: Exception) { applyDefaultSkin(tv) }
    }

    private fun applySkinInternal(tv: TextView) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val skinId = prefs.getString("skin", "default") ?: "default"

        if (skinId == "custom-file") {
            // 파일 스킨 (.cskin)
            val json = prefs.getString("custom_skin_json", null)
            val skinData = json?.let { CustomSkinData.fromJson(it) }
            if (skinData != null) {
                // 배경: 별도 이미지 파일 → base64 이미지 → 단색 → 그라데이션
                // 오버레이 전용 배경 > 공용 배경
                val bgPath = prefs.getString("cskin_overlay_bg_path", null)
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
                    // 단색 배경
                    tv.background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        setColor(skinData.overlaySolidColor())
                        cornerRadius = skinData.overlayCornerRadius()
                        skinData.overlay?.shape?.border?.let { b ->
                            if (b.width > 0) {
                                setStroke(b.width.toInt(), CustomSkinData.parseColor(b.color, 0))
                            }
                        }
                    }
                } else {
                    // 그라데이션 배경
                    tv.background = GradientDrawable(
                        skinData.overlayGradientOrientation(),
                        skinData.overlayBgColors()
                    ).apply {
                        cornerRadius = skinData.overlayCornerRadius()
                        skinData.overlay?.shape?.border?.let { b ->
                            if (b.width > 0) {
                                setStroke(b.width.toInt(), CustomSkinData.parseColor(b.color, 0))
                            }
                        }
                    }
                }
                tv.setTextColor(skinData.overlayTextColor())
                tv.setPadding(skinData.overlayPaddingH(), skinData.overlayPaddingV(),
                    skinData.overlayPaddingH(), skinData.overlayPaddingV())
                tv.elevation = skinData.overlayElevation()
                // 텍스트 그림자
                val shadow = skinData.overlay?.text?.shadow
                if (shadow != null && shadow.radius > 0) {
                    tv.setShadowLayer(shadow.radius, shadow.dx, shadow.dy,
                        CustomSkinData.parseColor(shadow.color, 0xFF000000.toInt()))
                } else {
                    tv.setShadowLayer(0f, 0f, 0f, 0)
                }
                return
            }
            // fallback
            applyDefaultSkin(tv)
        } else if (skinId == "custom") {
            // 커스텀 스킨: 사진 배경 (내부 저장소 파일)
            val path = prefs.getString("custom_skin_path", null)
            if (path != null) {
                val bitmap = android.graphics.BitmapFactory.decodeFile(path)
                if (bitmap != null) {
                    val drawable = android.graphics.drawable.BitmapDrawable(context.resources, bitmap)
                    drawable.alpha = 220
                    tv.background = drawable
                    tv.setTextColor(0xFFffffff.toInt())
                    tv.setShadowLayer(4f, 1f, 1f, 0xFF000000.toInt())
                    tv.elevation = 6f
                    return
                }
            }
            applyDefaultSkin(tv)
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

    private fun applyDefaultSkin(tv: TextView) {
        val skin = SKIN_STYLES["default"]!!
        tv.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(skin.bgStartColor, skin.bgEndColor)
        ).apply { cornerRadius = skin.cornerRadius }
        tv.setTextColor(skin.textColor)
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

        /** 현재 선택된 스킨의 앱 전체 색상을 반환 */
        fun getAppColors(context: Context): AppSkinColors {
            return try {
                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                val skinId = prefs.getString("skin", "default") ?: "default"
                if (skinId == "custom-file") {
                    val json = prefs.getString("custom_skin_json", null)
                    val skinData = json?.let { CustomSkinData.fromJson(it) }
                    if (skinData != null) return skinData.toAppSkinColors()
                }
                val overlay = getInstance(context)
                overlay.APP_SKIN_COLORS[skinId] ?: overlay.APP_SKIN_COLORS["default"]!!
            } catch (_: Exception) {
                // 기본 다크 스킨 폴백
                AppSkinColors(0xFF1a1a2e.toInt(), 0xFF16213e.toInt(), 0xFF22223a.toInt(),
                    0xFFe0e0e0.toInt(), 0xFF888899.toInt(), 0xFFc084fc.toInt(), true)
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
