package com.claudeusage.widget

/**
 * Claude Max/Pro 플랜 사용량 데이터.
 * claude.ai 설정 > 사용량에 표시되는 정보를 모델링.
 */
data class ModelUsage(
    val modelName: String,       // "Opus", "Sonnet", "Haiku"
    val modelTier: String,       // API 내부 tier 이름
    val used: Int,               // 현재 5시간 윈도우 사용 메시지 수
    val limit: Int,              // 5시간 윈도우 메시지 한도
    val resetsAt: String,        // 리셋 시각 (ISO 8601)
    val windowHours: Int = 5,
    // 누적 데이터 (UsageAccumulator에서 제공)
    val weeklyUsed: Int = 0,     // 이번 주 누적 메시지 수
    val totalUsed: Int = 0,      // 이번 달 누적 메시지 수
) {
    val remaining: Int get() = (limit - used).coerceAtLeast(0)
    val usedPercent: Double get() = if (limit > 0) (used.toDouble() / limit) * 100 else 0.0
    val isNearLimit: Boolean get() = usedPercent >= 80
    val isAtLimit: Boolean get() = used >= limit

    val percentText: String get() = "${String.format("%.0f", usedPercent)}%"

    fun remainingText(): String = when {
        isAtLimit -> "한도 도달"
        remaining <= 5 -> "${remaining}개 남음!"
        else -> "${remaining}개 남음"
    }

    fun formatResetTime(): String {
        if (resetsAt.isEmpty()) return ""
        return try {
            val resetInstant = java.time.Instant.parse(resetsAt)
            val now = java.time.Instant.now()
            val dur = java.time.Duration.between(now, resetInstant)
            if (dur.isNegative) "곧 초기화"
            else {
                val h = dur.toHours()
                val m = dur.toMinutes() % 60
                if (h > 0) "${h}시간 ${m}분 후 초기화" else "${m}분 후 초기화"
            }
        } catch (e: Exception) { "" }
    }
}

data class PlanUsage(
    val planName: String = "Max",
    val models: List<ModelUsage> = emptyList(),
    val lastUpdated: String = "",
    val error: String? = null,
) {
    fun getModel(name: String): ModelUsage? =
        models.find { it.modelName.equals(name, ignoreCase = true) }

    // --- 알림 텍스트 ---

    fun notificationTitle(): String {
        val opus = getModel("Opus")
        return if (opus != null) {
            val emoji = when {
                opus.usedPercent >= 90 -> "🔴"
                opus.usedPercent >= 70 -> "🟡"
                else -> "🟢"
            }
            "$emoji Claude $planName │ Opus ${opus.percentText}"
        } else {
            "Claude $planName"
        }
    }

    fun notificationShort(): String {
        return models.joinToString(" │ ") { "${it.modelName} ${it.percentText}" }
    }

    fun notificationExpanded(): String = buildString {
        append("── 5시간 ──\n")
        for (m in models) {
            val bar = progressBar(m.usedPercent)
            append("${m.modelName} $bar ${m.percentText} (${m.used}/${m.limit})\n")
        }

        // 주간
        val weeklyModels = models.filter { it.weeklyUsed > 0 }
        if (weeklyModels.isNotEmpty()) {
            append("── 주간 ──\n")
            for (m in weeklyModels) {
                append("${m.modelName}: ${m.weeklyUsed}개 메시지\n")
            }
        }

        // 총 사용량
        val totalModels = models.filter { it.totalUsed > 0 }
        if (totalModels.isNotEmpty()) {
            append("── 총 사용량 ──\n")
            for (m in totalModels) {
                append("${m.modelName}: ${m.totalUsed}개 메시지\n")
            }
        }

        val firstReset = models.filter { it.resetsAt.isNotEmpty() }.minByOrNull { it.resetsAt }
        if (firstReset != null) {
            append("⏱ ${firstReset.formatResetTime()}")
        }
    }

    private fun progressBar(percent: Double): String {
        val filled = (percent / 10).toInt().coerceIn(0, 10)
        return "▓".repeat(filled) + "░".repeat(10 - filled)
    }
}
