package com.claudeusage.widget

import android.content.Context
import android.content.SharedPreferences
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/**
 * Tracks accumulated usage across 5-hour window resets.
 *
 * The claude.ai API only returns current 5h window usage.
 * This class detects when the window resets (usage drops) and
 * accumulates the peak usage from each completed window.
 *
 * Provides: 5시간 (current window), 주간 (this week), 총 (this month).
 */
class UsageAccumulator(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("usage_accumulator", Context.MODE_PRIVATE)

    data class AccumulatedUsage(
        val weeklyMessages: Int,
        val totalMessages: Int,  // this month
    )

    /**
     * Update with fresh data from the API. Detects resets and accumulates.
     * Call this every time new usage data is fetched.
     */
    fun update(modelName: String, currentUsed: Int) {
        val key = modelName.lowercase()
        val lastUsed = prefs.getInt("${key}_last_used", 0)
        val lastTimestamp = prefs.getLong("${key}_last_ts", 0)

        val now = System.currentTimeMillis()

        // Detect reset: current usage is less than last known → window reset happened
        if (currentUsed < lastUsed && lastUsed > 0) {
            // Previous window completed with 'lastUsed' messages
            val weeklyAcc = prefs.getInt("${key}_weekly", 0)
            val totalAcc = prefs.getInt("${key}_total", 0)
            prefs.edit()
                .putInt("${key}_weekly", weeklyAcc + lastUsed)
                .putInt("${key}_total", totalAcc + lastUsed)
                .apply()
        }

        prefs.edit()
            .putInt("${key}_last_used", currentUsed)
            .putLong("${key}_last_ts", now)
            .apply()

        // Auto-reset weekly on Monday 00:00 UTC
        cleanupIfNewPeriod()
    }

    /**
     * Get accumulated usage for a model.
     * Weekly/total include completed windows + current window.
     */
    fun getAccumulated(modelName: String, currentUsed: Int): AccumulatedUsage {
        val key = modelName.lowercase()
        val weeklyAcc = prefs.getInt("${key}_weekly", 0)
        val totalAcc = prefs.getInt("${key}_total", 0)

        return AccumulatedUsage(
            weeklyMessages = weeklyAcc + currentUsed,
            totalMessages = totalAcc + currentUsed,
        )
    }

    private fun cleanupIfNewPeriod() {
        val lastCleanup = prefs.getLong("last_cleanup", 0)
        val now = ZonedDateTime.now(ZoneOffset.UTC)

        // Weekly reset: Monday 00:00 UTC
        val weekStart = now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        if (lastCleanup < weekStart) {
            // Reset weekly counters for all models
            val editor = prefs.edit()
            for (key in prefs.all.keys) {
                if (key.endsWith("_weekly")) {
                    editor.putInt(key, 0)
                }
            }
            editor.putLong("last_cleanup", System.currentTimeMillis())

            // Monthly reset: 1st of month
            val monthStart = now.withDayOfMonth(1)
                .toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val lastMonthlyReset = prefs.getLong("last_monthly_reset", 0)
            if (lastMonthlyReset < monthStart) {
                for (key in prefs.all.keys) {
                    if (key.endsWith("_total")) {
                        editor.putInt(key, 0)
                    }
                }
                editor.putLong("last_monthly_reset", System.currentTimeMillis())
            }

            editor.apply()
        }
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
