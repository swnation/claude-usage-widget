package com.claudeusage.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.preference.PreferenceManager
import com.google.gson.Gson

class UsageWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.claudeusage.widget.WIDGET_REFRESH"

        fun updateAll(context: Context) {
            val intent = Intent(context, UsageWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            }
            val ids = AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, UsageWidgetProvider::class.java))
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            context.sendBroadcast(intent)
        }

        // 색상 상수
        private const val COLOR_GREEN = 0xFF4ade80.toInt()
        private const val COLOR_YELLOW = 0xFFfbbf24.toInt()
        private const val COLOR_RED = 0xFFf87171.toInt()
        private const val COLOR_PURPLE = 0xFFc084fc.toInt()

        fun percentColor(pct: Int): Int = when {
            pct >= 90 -> COLOR_RED
            pct >= 70 -> COLOR_YELLOW
            else -> COLOR_GREEN
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            val openIntent = Intent(context, MainActivity::class.java).apply {
                putExtra("auto_refresh", true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            context.startActivity(openIntent)
        }
        super.onReceive(context, intent)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (widgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, widgetId)
        }
    }

    private fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        widgetId: Int
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_usage)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val mode = DisplayMode.fromString(prefs.getString("display_mode", null))

        val json = prefs.getString("last_usage", null)
        val loggedIn = prefs.getBoolean("logged_in", false)
        val usage = if (json != null) {
            try { Gson().fromJson(json, PlanUsage::class.java) }
            catch (_: Exception) { null }
        } else null

        val costJson = prefs.getString("last_api_cost", null)
        val cost = if (costJson != null) {
            try { Gson().fromJson(costJson, ApiCostData::class.java) }
            catch (_: Exception) { null }
        } else null

        // ── 스킨 적용 ──
        val skin = try { FloatingOverlay.getAppColors(context) } catch (_: Exception) { null }
        if (skin != null) {
            val bgAlpha = 0xDD000000.toInt()
            val skinBg = (skin.bgColor and 0x00FFFFFF) or bgAlpha
            views.setInt(R.id.widgetRoot, "setBackgroundColor", skinBg)
            views.setTextColor(R.id.widgetPlanName, skin.accentColor)
            views.setTextColor(R.id.widgetSessionLabel, skin.subtextColor)
            views.setTextColor(R.id.widgetWeeklyLabel, skin.subtextColor)
            views.setTextColor(R.id.widgetUpdateTime, skin.subtextColor)
            views.setTextColor(R.id.widgetSessionReset, skin.subtextColor)
            views.setTextColor(R.id.widgetWeeklyReset, skin.subtextColor)
        }

        val skinFinal = skin ?: FloatingOverlay.AppSkinColors(
            0xFF1a1a2e.toInt(), 0xFF16213e.toInt(), 0xFF22223a.toInt(),
            0xFFe0e0e0.toInt(), 0xFF888899.toInt(), 0xFFc084fc.toInt(), true)
        when (mode) {
            DisplayMode.CLAUDE_ONLY -> renderClaudeOnly(views, usage, loggedIn, skinFinal)
            DisplayMode.API_COST_ONLY -> renderApiCostOnly(views, cost, skinFinal)
            DisplayMode.BOTH -> renderBoth(views, usage, cost, loggedIn, skinFinal)
        }

        // 위젯 탭 → 앱 열기
        val openIntent = Intent(context, MainActivity::class.java).apply {
            putExtra("auto_refresh", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPI = PendingIntent.getActivity(context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(R.id.widgetRoot, openPI)

        // 새로고침 버튼
        val refreshIntent = Intent(context, UsageWidgetProvider::class.java).apply {
            action = ACTION_REFRESH
        }
        val refreshPI = PendingIntent.getBroadcast(context, 1, refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(R.id.widgetRefreshBtn, refreshPI)

        appWidgetManager.updateAppWidget(widgetId, views)
    }

    private fun renderClaudeOnly(views: RemoteViews, usage: PlanUsage?, loggedIn: Boolean, skin: FloatingOverlay.AppSkinColors) {
        val hasData = usage != null && usage.session != null
        views.setTextViewText(R.id.widgetStatus, if (hasData) "🟢" else if (loggedIn) "🟡" else "🔴")
        views.setTextViewText(R.id.widgetPlanName,
            if (usage != null) "Claude ${usage.planName}"
            else if (!loggedIn) "로그인 필요"
            else "Claude Max"
        )
        views.setTextViewText(R.id.widgetSessionLabel, "세션")
        views.setTextViewText(R.id.widgetWeeklyLabel, "주간")

        if (usage != null) {
            val sessionPct = usage.session?.usedPercent?.toInt()?.coerceIn(0, 100) ?: 0
            views.setTextViewText(R.id.widgetSessionPercent, "${sessionPct}%")
            views.setTextColor(R.id.widgetSessionPercent, percentColor(sessionPct))
            views.setProgressBar(R.id.widgetSessionBar, 100, sessionPct, false)
            views.setTextViewText(R.id.widgetSessionReset, usage.session?.resetTimeText() ?: "")

            val weeklyPct = usage.weekly?.usedPercent?.toInt()?.coerceIn(0, 100) ?: 0
            views.setTextViewText(R.id.widgetWeeklyPercent, "${weeklyPct}%")
            views.setTextColor(R.id.widgetWeeklyPercent, skin.accentColor)
            views.setProgressBar(R.id.widgetWeeklyBar, 100, weeklyPct, false)
            views.setTextViewText(R.id.widgetWeeklyReset, usage.weekly?.resetTimeText() ?: "")

            views.setTextViewText(R.id.widgetUpdateTime, formatUpdateTime(usage.lastUpdated))
        } else {
            setDefaultValues(views, loggedIn, skin)
        }
    }

    private fun renderApiCostOnly(views: RemoteViews, cost: ApiCostData?, skin: FloatingOverlay.AppSkinColors) {
        views.setTextViewText(R.id.widgetStatus, if (cost != null) "💰" else "🟡")
        views.setTextViewText(R.id.widgetPlanName, "API 요금")
        views.setTextViewText(R.id.widgetSessionLabel, "오늘")
        views.setTextViewText(R.id.widgetWeeklyLabel, "이번달")

        if (cost != null) {
            views.setTextViewText(R.id.widgetSessionPercent, cost.todayText())
            views.setTextColor(R.id.widgetSessionPercent, COLOR_GREEN)
            views.setProgressBar(R.id.widgetSessionBar, 100, 0, false)
            views.setTextViewText(R.id.widgetSessionReset, cost.todayKrw())

            views.setTextViewText(R.id.widgetWeeklyPercent, cost.monthText())
            views.setTextColor(R.id.widgetWeeklyPercent, skin.accentColor)
            views.setProgressBar(R.id.widgetWeeklyBar, 100, 0, false)
            views.setTextViewText(R.id.widgetWeeklyReset, cost.monthKrw())

            views.setTextViewText(R.id.widgetUpdateTime, formatUpdateTime(cost.lastUpdated))
        } else {
            views.setTextViewText(R.id.widgetSessionPercent, "--")
            views.setTextColor(R.id.widgetSessionPercent, COLOR_GREEN)
            views.setTextViewText(R.id.widgetWeeklyPercent, "--")
            views.setTextColor(R.id.widgetWeeklyPercent, skin.accentColor)
            views.setTextViewText(R.id.widgetSessionReset, "")
            views.setTextViewText(R.id.widgetWeeklyReset, "")
            views.setTextViewText(R.id.widgetUpdateTime, "로딩 중")
        }
    }

    private fun renderBoth(views: RemoteViews, usage: PlanUsage?, cost: ApiCostData?, loggedIn: Boolean, skin: FloatingOverlay.AppSkinColors) {
        val hasData = usage != null && usage.session != null
        views.setTextViewText(R.id.widgetStatus,
            if (hasData && cost != null) "🟢" else if (hasData || cost != null) "🟡" else "🔴")
        views.setTextViewText(R.id.widgetPlanName,
            if (usage != null) "Claude ${usage.planName}" + (cost?.let { " + 💰" } ?: "")
            else "Claude + API 요금"
        )
        views.setTextViewText(R.id.widgetSessionLabel, "세션")
        views.setTextViewText(R.id.widgetWeeklyLabel, "요금")

        if (usage != null) {
            val sessionPct = usage.session?.usedPercent?.toInt()?.coerceIn(0, 100) ?: 0
            views.setTextViewText(R.id.widgetSessionPercent, "${sessionPct}%")
            views.setTextColor(R.id.widgetSessionPercent, percentColor(sessionPct))
            views.setProgressBar(R.id.widgetSessionBar, 100, sessionPct, false)
            views.setTextViewText(R.id.widgetSessionReset, usage.session?.resetTimeText() ?: "")
        } else {
            views.setTextViewText(R.id.widgetSessionPercent, "--%")
            views.setTextColor(R.id.widgetSessionPercent, COLOR_GREEN)
            views.setProgressBar(R.id.widgetSessionBar, 100, 0, false)
            views.setTextViewText(R.id.widgetSessionReset, "")
        }

        if (cost != null) {
            views.setTextViewText(R.id.widgetWeeklyPercent, cost.todayText())
            views.setTextColor(R.id.widgetWeeklyPercent, skin.accentColor)
            views.setProgressBar(R.id.widgetWeeklyBar, 100, 0, false)
            views.setTextViewText(R.id.widgetWeeklyReset, cost.todayKrw())
        } else {
            views.setTextViewText(R.id.widgetWeeklyPercent, "--")
            views.setTextColor(R.id.widgetWeeklyPercent, skin.accentColor)
            views.setProgressBar(R.id.widgetWeeklyBar, 100, 0, false)
            views.setTextViewText(R.id.widgetWeeklyReset, "")
        }

        val lastUpdated = usage?.lastUpdated ?: cost?.lastUpdated ?: ""
        views.setTextViewText(R.id.widgetUpdateTime, formatUpdateTime(lastUpdated))
    }

    private fun setDefaultValues(views: RemoteViews, loggedIn: Boolean, skin: FloatingOverlay.AppSkinColors) {
        views.setTextViewText(R.id.widgetSessionPercent, "--%")
        views.setTextColor(R.id.widgetSessionPercent, COLOR_GREEN)
        views.setTextViewText(R.id.widgetWeeklyPercent, "--%")
        views.setTextColor(R.id.widgetWeeklyPercent, skin.accentColor)
        views.setTextViewText(R.id.widgetSessionReset, "")
        views.setTextViewText(R.id.widgetWeeklyReset, "")
        views.setTextViewText(R.id.widgetUpdateTime, if (!loggedIn) "" else "새로고침")
    }

    private fun formatUpdateTime(isoTime: String): String {
        return try {
            val instant = java.time.Instant.parse(isoTime)
            val localTime = instant.atZone(java.time.ZoneId.systemDefault()).toLocalTime()
            localTime.toString().take(5)
        } catch (_: Exception) { "" }
    }
}
