package com.claudeusage.widget

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/**
 * Standalone local usage repository.
 * Stores usage events in a JSONL file on the device and aggregates by time window.
 * No server required.
 */
class UsageRepository(context: Context) {

    data class UsageEntry(
        val timestamp: String,
        val model: String,
        val input_tokens: Long,
        val output_tokens: Long,
        val cost_usd: Double,
    )

    private val logFile = File(context.filesDir, "usage_log.jsonl")
    private val gson = Gson()

    // Anthropic pricing per 1M tokens
    private val pricing = mapOf(
        "claude-opus-4-6" to Pair(15.0, 75.0),
        "claude-sonnet-4-6" to Pair(3.0, 15.0),
        "claude-haiku-4-5" to Pair(0.80, 4.0),
    )
    private val defaultPricing = Pair(3.0, 15.0)

    fun trackUsage(model: String, inputTokens: Long, outputTokens: Long) {
        val cost = calculateCost(model, inputTokens, outputTokens)
        val entry = UsageEntry(
            timestamp = Instant.now().toString(),
            model = model,
            input_tokens = inputTokens,
            output_tokens = outputTokens,
            cost_usd = cost,
        )
        logFile.appendText(gson.toJson(entry) + "\n")
    }

    fun aggregate(budgetUsd: Double, tokenLimit: Long): UsageData {
        val entries = loadLog()
        val now = ZonedDateTime.now(ZoneOffset.UTC)

        val cutoffs = mapOf(
            "5h" to now.minusHours(5).toInstant(),
            "daily" to now.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant(),
            "weekly" to now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant(),
            "monthly" to now.withDayOfMonth(1).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant(),
        )

        val periods = mutableMapOf<String, PeriodData>()
        for (key in cutoffs.keys) {
            periods[key] = PeriodData()
        }

        var totalInput = 0L
        var totalOutput = 0L
        var totalCost = 0.0
        val monthlyCutoff = cutoffs["monthly"]!!

        for (entry in entries) {
            val ts = try {
                Instant.parse(entry.timestamp)
            } catch (e: Exception) {
                continue
            }

            if (ts >= monthlyCutoff) {
                totalInput += entry.input_tokens
                totalOutput += entry.output_tokens
                totalCost += entry.cost_usd
            }

            for ((key, cutoff) in cutoffs) {
                if (ts >= cutoff) {
                    val old = periods[key]!!
                    periods[key] = PeriodData(
                        tokens = old.tokens + entry.input_tokens + entry.output_tokens,
                        costUsd = old.costUsd + entry.cost_usd,
                        inputTokens = old.inputTokens + entry.input_tokens,
                        outputTokens = old.outputTokens + entry.output_tokens,
                        requests = old.requests + 1,
                    )
                }
            }
        }

        val totalTokens = totalInput + totalOutput

        return UsageData(
            inputTokens = totalInput,
            outputTokens = totalOutput,
            totalTokens = totalTokens,
            estimatedCostUsd = totalCost,
            monthlyBudgetUsd = budgetUsd,
            monthlyTokenLimit = tokenLimit,
            budgetUsedPercent = if (budgetUsd > 0) (totalCost / budgetUsd) * 100 else 0.0,
            tokensUsedPercent = if (tokenLimit > 0) (totalTokens.toDouble() / tokenLimit) * 100 else 0.0,
            lastUpdated = Instant.now().toString(),
            periods = periods,
        )
    }

    fun cleanupOldLogs() {
        val cutoff = Instant.now().minusSeconds(35L * 24 * 3600)
        val entries = loadLog()
        val kept = entries.filter {
            try {
                Instant.parse(it.timestamp) >= cutoff
            } catch (e: Exception) {
                true
            }
        }
        logFile.writeText(kept.joinToString("\n") { gson.toJson(it) } + if (kept.isNotEmpty()) "\n" else "")
    }

    fun clearAll() {
        if (logFile.exists()) logFile.writeText("")
    }

    fun getEntryCount(): Int = loadLog().size

    private fun loadLog(): List<UsageEntry> {
        if (!logFile.exists()) return emptyList()
        return logFile.readLines()
            .filter { it.isNotBlank() }
            .mapNotNull {
                try {
                    gson.fromJson(it, UsageEntry::class.java)
                } catch (e: Exception) {
                    null
                }
            }
    }

    private fun calculateCost(model: String, inputTokens: Long, outputTokens: Long): Double {
        val (inPrice, outPrice) = pricing.entries
            .firstOrNull { model.contains(it.key) }?.value ?: defaultPricing
        return (inputTokens / 1_000_000.0) * inPrice + (outputTokens / 1_000_000.0) * outPrice
    }
}
