package com.claudeusage.widget

import android.graphics.Color

/**
 * .cskin JSON 파일 포맷 데이터 모델
 *
 * 파일 확장자: .cskin (내부는 JSON)
 * AI에게 전달할 스펙은 CLAUDE.md 또는 별도 문서에서 관리
 */
data class CustomSkinData(
    val name: String = "Custom",
    val author: String = "",
    val version: Int = 1,
    val overlay: OverlayStyle? = null,
    val app: AppStyle? = null,
    val widget: WidgetStyle? = null,
) {
    data class OverlayStyle(
        val background: Background? = null,
        val text: TextStyle? = null,
        val shape: ShapeStyle? = null,
        val padding: Padding? = null,
    )

    data class Background(
        val type: String = "gradient",     // "gradient", "solid", "image"
        val colors: List<String>? = null,  // hex colors for gradient/solid
        val direction: String = "tl_br",   // tl_br, top_bottom, left_right, bl_tr, radial
        val image: String? = null,         // base64 encoded PNG/JPG
        val opacity: Double = 0.92,
    )

    data class TextStyle(
        val color: String = "#e0e0e0",
        val shadow: Shadow? = null,
    )

    data class Shadow(
        val color: String = "#000000",
        val radius: Float = 0f,
        val dx: Float = 0f,
        val dy: Float = 0f,
    )

    data class ShapeStyle(
        val cornerRadius: Float = 16f,
        val border: Border? = null,
        val elevation: Float = 4f,
    )

    data class Border(
        val color: String = "#00000000",
        val width: Float = 0f,
    )

    data class Padding(
        val horizontal: Int = 24,
        val vertical: Int = 14,
    )

    data class AppStyle(
        val backgroundColor: String = "#1a1a2e",
        val sectionColor: String = "#16213e",
        val cardColor: String = "#22223a",
        val textColor: String = "#e0e0e0",
        val subtextColor: String = "#888899",
        val accentColor: String = "#c084fc",
        val isDark: Boolean = true,
    )

    data class WidgetStyle(
        val backgroundColor: String = "#1a1a2e",
        val opacity: Double = 0.87,
    )

    // ── 유틸리티 ──

    fun toAppSkinColors(): FloatingOverlay.AppSkinColors {
        val a = app ?: AppStyle()
        return FloatingOverlay.AppSkinColors(
            bgColor = parseColor(a.backgroundColor, 0xFF1a1a2e.toInt()),
            sectionBgColor = parseColor(a.sectionColor, 0xFF16213e.toInt()),
            cardBgColor = parseColor(a.cardColor, 0xFF22223a.toInt()),
            textColor = parseColor(a.textColor, 0xFFe0e0e0.toInt()),
            subtextColor = parseColor(a.subtextColor, 0xFF888899.toInt()),
            accentColor = parseColor(a.accentColor, 0xFFc084fc.toInt()),
            isDark = a.isDark,
        )
    }

    fun overlayGradientOrientation(): android.graphics.drawable.GradientDrawable.Orientation {
        val dir = overlay?.background?.direction ?: "tl_br"
        return when (dir) {
            "top_bottom" -> android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM
            "left_right" -> android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT
            "bl_tr" -> android.graphics.drawable.GradientDrawable.Orientation.BL_TR
            "tr_bl" -> android.graphics.drawable.GradientDrawable.Orientation.TR_BL
            "bottom_top" -> android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP
            "right_left" -> android.graphics.drawable.GradientDrawable.Orientation.RIGHT_LEFT
            else -> android.graphics.drawable.GradientDrawable.Orientation.TL_BR
        }
    }

    fun overlayBgColors(): IntArray {
        val colors = overlay?.background?.colors
        if (colors.isNullOrEmpty()) return intArrayOf(0xEB1a1a2e.toInt(), 0xEB16213e.toInt())
        val alpha = ((overlay?.background?.opacity ?: 0.92) * 255).toInt().coerceIn(0, 255)
        return colors.map { hex ->
            val c = parseColor(hex, 0xFF1a1a2e.toInt())
            (c and 0x00FFFFFF) or (alpha shl 24)
        }.toIntArray()
    }

    fun overlayTextColor(): Int {
        return parseColor(overlay?.text?.color ?: "#e0e0e0", 0xFFe0e0e0.toInt())
    }

    fun overlayCornerRadius(): Float = overlay?.shape?.cornerRadius ?: 16f
    fun overlayElevation(): Float = overlay?.shape?.elevation ?: 4f
    fun overlayPaddingH(): Int = overlay?.padding?.horizontal ?: 24
    fun overlayPaddingV(): Int = overlay?.padding?.vertical ?: 14

    fun overlayBgImage(): android.graphics.Bitmap? {
        val b64 = overlay?.background?.image ?: return null
        return try {
            val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) { null }
    }

    fun widgetBgColor(): Int {
        val w = widget ?: WidgetStyle()
        val c = parseColor(w.backgroundColor, 0xFF1a1a2e.toInt())
        val alpha = ((w.opacity) * 255).toInt().coerceIn(0, 255)
        return (c and 0x00FFFFFF) or (alpha shl 24)
    }

    companion object {
        fun parseColor(hex: String, fallback: Int): Int {
            return try { Color.parseColor(hex) } catch (_: Exception) { fallback }
        }

        fun fromJson(json: String): CustomSkinData? {
            return try {
                com.google.gson.Gson().fromJson(json, CustomSkinData::class.java)
            } catch (_: Exception) { null }
        }
    }
}
