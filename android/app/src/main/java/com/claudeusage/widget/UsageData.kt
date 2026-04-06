package com.claudeusage.widget

import com.google.gson.annotations.SerializedName

data class PeriodData(
    @SerializedName("tokens") val tokens: Long = 0,
    @SerializedName("cost_usd") val costUsd: Double = 0.0,
    @SerializedName("input_tokens") val inputTokens: Long = 0,
    @SerializedName("output_tokens") val outputTokens: Long = 0,
    @SerializedName("requests") val requests: Int = 0,
)

data class UsageData(
    @SerializedName("input_tokens") val inputTokens: Long = 0,
    @SerializedName("output_tokens") val outputTokens: Long = 0,
    @SerializedName("total_tokens") val totalTokens: Long = 0,
    @SerializedName("estimated_cost_usd") val estimatedCostUsd: Double = 0.0,
    @SerializedName("monthly_budget_usd") val monthlyBudgetUsd: Double = 100.0,
    @SerializedName("monthly_token_limit") val monthlyTokenLimit: Long = 10_000_000,
    @SerializedName("budget_used_percent") val budgetUsedPercent: Double = 0.0,
    @SerializedName("tokens_used_percent") val tokensUsedPercent: Double = 0.0,
    @SerializedName("last_updated") val lastUpdated: String = "",
    @SerializedName("periods") val periods: Map<String, PeriodData> = emptyMap(),
) {
    fun formatTokens(n: Long): String = when {
        n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
        n >= 1_000 -> String.format("%.1fK", n / 1_000.0)
        else -> n.toString()
    }

    fun formatCost(v: Double): String = "$${String.format("%.2f", v)}"

    fun costSummary(): String =
        "${formatCost(estimatedCostUsd)} / ${formatCost(monthlyBudgetUsd)}"

    fun tokenSummary(): String =
        "${formatTokens(totalTokens)} / ${formatTokens(monthlyTokenLimit)}"

    fun detailSummary(): String =
        "In: ${formatTokens(inputTokens)} | Out: ${formatTokens(outputTokens)}"

    fun shortSummary(): String =
        "${formatCost(estimatedCostUsd)} (${String.format("%.1f", budgetUsedPercent)}%)"

    fun periodSummary(key: String): String {
        val p = periods[key] ?: return "No data"
        return "${formatCost(p.costUsd)} │ ${formatTokens(p.tokens)} tok │ ${p.requests} reqs"
    }

    fun periodNotificationText(): String {
        val h5 = periods["5h"]
        val daily = periods["daily"]
        val weekly = periods["weekly"]
        return buildString {
            if (h5 != null) append("5h: ${formatCost(h5.costUsd)} (${h5.requests}r)")
            if (daily != null) append(" │ Today: ${formatCost(daily.costUsd)} (${daily.requests}r)")
            if (weekly != null) append("\nWeek: ${formatCost(weekly.costUsd)} (${weekly.requests}r)")
        }
    }
}
