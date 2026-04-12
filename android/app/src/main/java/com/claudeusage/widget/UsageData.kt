package com.claudeusage.widget

// ── 표시 모드 ──
enum class DisplayMode {
    CLAUDE_ONLY,    // 현재와 동일 (세션 %, 주간 %)
    API_COST_ONLY,  // 오늘 요금 + 이번 달 총 요금 (AI별)
    BOTH;           // 세션 % + 요금 합쳐서

    companion object {
        fun fromString(s: String?): DisplayMode = when (s) {
            "API_COST_ONLY" -> API_COST_ONLY
            "BOTH" -> BOTH
            else -> CLAUDE_ONLY
        }
    }
}

// ── AI별 비용 ──
data class AiCostBreakdown(
    val aiId: String,
    val name: String,
    val color: String,
    val todayCost: Double = 0.0,
    val monthCost: Double = 0.0,
    val actualMonthCost: Double? = null, // Admin API 실제 비용 (있으면)
) {
    val hasDiff: Boolean get() = actualMonthCost != null
    val monthDiff: Double? get() = actualMonthCost?.let { it - monthCost }
}

// ── 시스템별 비용 ──
data class SystemCost(
    val systemName: String,
    val todayCost: Double = 0.0,
    val monthCost: Double = 0.0,
)

// ── API 요금 데이터 ──
data class ApiCostData(
    val todayTotal: Double = 0.0,       // 추정 합계
    val monthTotal: Double = 0.0,       // 추정 합계
    val actualToday: Double? = null,    // Admin API 실제 (Claude+GPT)
    val actualMonth: Double? = null,    // Admin API 실제 (Claude+GPT)
    val byAI: List<AiCostBreakdown> = emptyList(),
    val bySys: List<SystemCost> = emptyList(),
    val lastUpdated: String = "",
    val error: String? = null,
) {
    fun todayText(): String = "$${String.format("%.4f", todayTotal)}"
    fun monthText(): String = "$${String.format("%.4f", monthTotal)}"
    fun todayKrw(): String = "≈${String.format("%,d", (todayTotal * 1450).toLong())}원"
    fun monthKrw(): String = "≈${String.format("%,d", (monthTotal * 1450).toLong())}원"

    fun shortText(): String {
        val parts = mutableListOf<String>()
        parts.add("오늘 ${todayText()}")
        parts.add("이번달 ${monthText()}")
        return parts.joinToString(" │ ")
    }

    fun notificationTitle(): String {
        val todayStr = if (actualToday != null) "$${String.format("%.4f", actualToday)}"
            else todayText()
        return "💰 오늘 $todayStr │ 이번달 ${monthText()}"
    }

    fun notificationExpanded(): String = buildString {
        append("추정: 오늘 ${todayText()} / 이번 달 ${monthText()}\n")
        if (actualMonth != null) {
            append("실제 (Admin API): $${String.format("%.4f", actualMonth)}\n")
            val diff = actualMonth - monthTotal
            val sign = if (diff >= 0) "+" else ""
            append("차이: $sign$${String.format("%.4f", diff)}\n")
        }
        if (byAI.isNotEmpty()) {
            append("\nAI별 이번 달:\n")
            byAI.filter { it.monthCost > 0 || (it.actualMonthCost ?: 0.0) > 0 }.forEach {
                val est = "$${String.format("%.4f", it.monthCost)}"
                val actual = it.actualMonthCost?.let { a -> " (실제: $${String.format("%.4f", a)})" } ?: ""
                append("  ${it.name}: $est$actual\n")
            }
        }
    }
}

// ── AI 정의 (오랑붕쌤과 동일) ──
object AiDefs {
    data class AiDef(
        val id: String,
        val name: String,
        val color: String,
        val usageUrl: String,       // 비용 확인 사이트
        val hasAdminApi: Boolean,    // Admin API 지원 여부
    )

    val ALL = listOf(
        AiDef("gpt", "GPT", "#10a37f",
            "https://platform.openai.com/usage", true),
        AiDef("claude", "Claude", "#c96442",
            "https://console.anthropic.com/settings/billing", true),
        AiDef("gemini", "Gemini", "#4285f4",
            "https://aistudio.google.com/apikey", false),
        AiDef("grok", "Grok", "#1DA1F2",
            "https://console.x.ai/", false),
        AiDef("perp", "Perplexity", "#20808d",
            "https://www.perplexity.ai/settings/api", false),
    )

    fun find(id: String): AiDef? = ALL.find { it.id == id }
}

data class UsageLimit(
    val label: String,
    val usedPercent: Double,
    val resetsAt: String,
) {
    val isNearLimit: Boolean get() = usedPercent >= 70
    val isAtLimit: Boolean get() = usedPercent >= 95

    val percentText: String get() = "${String.format("%.0f", usedPercent)}%"

    fun resetTimeText(): String {
        if (resetsAt.isEmpty()) return ""
        return try {
            val resetInstant = java.time.Instant.parse(resetsAt)
            val now = java.time.Instant.now()
            val dur = java.time.Duration.between(now, resetInstant)
            if (dur.isNegative) "곧 재설정"
            else {
                val days = dur.toDays()
                val h = dur.toHours() % 24
                val m = dur.toMinutes() % 60
                when {
                    days > 0 -> "${days}일 ${h}시간 후 재설정"
                    h > 0 -> "${h}시간 ${m}분 후 재설정"
                    else -> "${m}분 후 재설정"
                }
            }
        } catch (e: Exception) {
            resetsAt
        }
    }

    fun statusText(): String = "${percentText} 사용됨"
}

data class PlanUsage(
    val planName: String = "Max",
    val session: UsageLimit? = null,
    val weekly: UsageLimit? = null,
    val lastUpdated: String = "",
    val error: String? = null,
) {
    fun notificationTitle(): String {
        val s = session
        val emoji = when {
            s != null && s.usedPercent >= 90 -> "🔴"
            s != null && s.usedPercent >= 70 -> "🟡"
            else -> "🟢"
        }
        val parts = mutableListOf<String>()
        s?.let { parts.add("세션 ${it.percentText}") }
        weekly?.let { parts.add("주간 ${it.percentText}") }
        return "$emoji ${parts.joinToString(" │ ")}"
    }

    fun notificationShort(): String {
        val parts = mutableListOf<String>()
        session?.let { parts.add("세션 ${it.percentText}") }
        weekly?.let { parts.add("주간 ${it.percentText}") }
        // 주간 리셋 시간 추가
        weekly?.let {
            val reset = it.resetTimeText()
            if (reset.isNotEmpty()) parts.add(reset)
        }
        return parts.joinToString(" │ ")
    }

    fun notificationExpanded(): String = buildString {
        session?.let {
            append("현재 세션\n")
            append("${progressBar(it.usedPercent)} ${it.statusText()}\n")
            val reset = it.resetTimeText()
            if (reset.isNotEmpty()) append("⏱ $reset\n")
        }
        weekly?.let {
            append("\n주간 한도\n")
            append("${progressBar(it.usedPercent)} ${it.statusText()}\n")
            val reset = it.resetTimeText()
            if (reset.isNotEmpty()) append("⏱ $reset")
        }
    }

    private fun progressBar(percent: Double): String {
        val filled = (percent / 5).toInt().coerceIn(0, 20)
        return "▓".repeat(filled) + "░".repeat(20 - filled)
    }

    // BOTH 모드용: 세션 + 요금 합친 알림
    fun combinedNotificationTitle(cost: ApiCostData?): String {
        val s = session
        val emoji = when {
            s != null && s.usedPercent >= 90 -> "🔴"
            s != null && s.usedPercent >= 70 -> "🟡"
            else -> "🟢"
        }
        val parts = mutableListOf<String>()
        s?.let { parts.add("세션 ${it.percentText}") }
        cost?.let { parts.add("💰${it.todayText()}") }
        return "$emoji ${parts.joinToString(" │ ")}"
    }

    fun combinedNotificationExpanded(cost: ApiCostData?): String = buildString {
        session?.let {
            append("현재 세션\n")
            append("${progressBar(it.usedPercent)} ${it.statusText()}\n")
            val reset = it.resetTimeText()
            if (reset.isNotEmpty()) append("⏱ $reset\n")
        }
        weekly?.let {
            append("\n주간 한도\n")
            append("${progressBar(it.usedPercent)} ${it.statusText()}\n")
            val reset = it.resetTimeText()
            if (reset.isNotEmpty()) append("⏱ $reset\n")
        }
        cost?.let {
            append("\n💰 API 요금\n")
            append("오늘: ${it.todayText()} (${it.todayKrw()})\n")
            append("이번 달: ${it.monthText()} (${it.monthKrw()})")
        }
    }
}
