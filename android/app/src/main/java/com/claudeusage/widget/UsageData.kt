package com.claudeusage.widget

import com.google.gson.annotations.SerializedName

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
) {
    fun formatTokens(n: Long): String = when {
        n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
        n >= 1_000 -> String.format("%.1fK", n / 1_000.0)
        else -> n.toString()
    }

    fun costSummary(): String =
        "$${String.format("%.2f", estimatedCostUsd)} / $${String.format("%.2f", monthlyBudgetUsd)}"

    fun tokenSummary(): String =
        "${formatTokens(totalTokens)} / ${formatTokens(monthlyTokenLimit)}"

    fun detailSummary(): String =
        "In: ${formatTokens(inputTokens)} | Out: ${formatTokens(outputTokens)}"

    fun shortSummary(): String =
        "$${String.format("%.2f", estimatedCostUsd)} (${String.format("%.1f", budgetUsedPercent)}%)"
}
