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
        val json = prefs.getString("last_usage", null)
        val usage = if (json != null) {
            try { Gson().fromJson(json, PlanUsage::class.java) }
            catch (_: Exception) { null }
        } else null

        if (usage != null) {
            // 세션
            val sessionPct = usage.session?.usedPercent?.toInt()?.coerceIn(0, 100) ?: 0
            views.setTextViewText(R.id.widgetSessionPercent, "${sessionPct}%")
            views.setProgressBar(R.id.widgetSessionBar, 100, sessionPct, false)
            views.setTextViewText(R.id.widgetSessionReset, usage.session?.resetTimeText() ?: "")

            // 주간
            val weeklyPct = usage.weekly?.usedPercent?.toInt()?.coerceIn(0, 100) ?: 0
            views.setTextViewText(R.id.widgetWeeklyPercent, "${weeklyPct}%")
            views.setProgressBar(R.id.widgetWeeklyBar, 100, weeklyPct, false)
            views.setTextViewText(R.id.widgetWeeklyReset, usage.weekly?.resetTimeText() ?: "")

            // 마지막 업데이트 시간
            val updateTime = try {
                val instant = java.time.Instant.parse(usage.lastUpdated)
                val localTime = instant.atZone(java.time.ZoneId.systemDefault()).toLocalTime()
                localTime.toString().take(5)
            } catch (_: Exception) { "" }
            views.setTextViewText(R.id.widgetUpdateTime, updateTime)
        } else {
            views.setTextViewText(R.id.widgetSessionPercent, "--%")
            views.setTextViewText(R.id.widgetWeeklyPercent, "--%")
            views.setTextViewText(R.id.widgetSessionReset, "")
            views.setTextViewText(R.id.widgetWeeklyReset, "")
            views.setTextViewText(R.id.widgetUpdateTime, "새로고침 필요")
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
}
