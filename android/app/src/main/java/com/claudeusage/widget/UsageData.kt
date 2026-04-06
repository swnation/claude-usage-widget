package com.claudeusage.widget

/**
 * Claude Max plan usage data.
 * Represents the usage limits shown in claude.ai Settings > Usage.
 */
data class ModelUsage(
    val modelName: String,       // e.g. "Opus", "Sonnet", "Haiku"
    val modelTier: String,       // e.g. "claude_pro_opus", "claude_pro_sonnet"
    val used: Int,               // messages used in current window
    val limit: Int,              // message limit per window
    val resetsAt: String,        // ISO timestamp of next reset
    val windowHours: Int = 5,    // reset window (typically 5 hours)
) {
    val remaining: Int get() = (limit - used).coerceAtLeast(0)
    val usedPercent: Double get() = if (limit > 0) (used.toDouble() / limit) * 100 else 0.0
    val isNearLimit: Boolean get() = usedPercent >= 80
    val isAtLimit: Boolean get() = used >= limit

    fun shortSummary(): String = "$used/$limit"

    fun remainingText(): String = when {
        isAtLimit -> "Limit reached"
        remaining <= 5 -> "$remaining left!"
        else -> "$remaining left"
    }
}

data class PlanUsage(
    val planName: String = "Max",       // "Pro", "Max", "Free", etc.
    val models: List<ModelUsage> = emptyList(),
    val lastUpdated: String = "",
    val error: String? = null,
) {
    fun getModel(name: String): ModelUsage? =
        models.find { it.modelName.equals(name, ignoreCase = true) }

    fun notificationTitle(): String {
        val opus = getModel("Opus")
        return if (opus != null) {
            val emoji = when {
                opus.usedPercent >= 90 -> "🔴"
                opus.usedPercent >= 70 -> "🟡"
                else -> "🟢"
            }
            "$emoji Claude $planName: Opus ${opus.shortSummary()}"
        } else {
            "Claude $planName"
        }
    }

    fun notificationShort(): String {
        return models.joinToString(" │ ") { "${it.modelName} ${it.shortSummary()}" }
    }

    fun notificationExpanded(): String = buildString {
        for (m in models) {
            val bar = progressBar(m.usedPercent)
            append("${m.modelName}: ${m.shortSummary()} $bar ${m.remainingText()}\n")
        }
        val firstReset = models.minByOrNull { it.resetsAt }
        if (firstReset != null && firstReset.resetsAt.isNotEmpty()) {
            append("⏱ Resets: ${formatResetTime(firstReset.resetsAt)}")
        }
    }

    private fun progressBar(percent: Double): String {
        val filled = (percent / 10).toInt().coerceIn(0, 10)
        return "▓".repeat(filled) + "░".repeat(10 - filled)
    }

    private fun formatResetTime(iso: String): String {
        return try {
            val resetInstant = java.time.Instant.parse(iso)
            val now = java.time.Instant.now()
            val remaining = java.time.Duration.between(now, resetInstant)
            if (remaining.isNegative) "Soon"
            else {
                val h = remaining.toHours()
                val m = remaining.toMinutes() % 60
                if (h > 0) "${h}h ${m}m" else "${m}m"
            }
        } catch (e: Exception) {
            iso.takeLast(8)
        }
    }
}
