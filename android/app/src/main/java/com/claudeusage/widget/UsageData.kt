package com.claudeusage.widget

// ── 표시 모드 ��─
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

// ── 비용 소스 구분 ──
enum class CostSource {
    BILLING,    // Admin/Billing API 실제 청구액
    ESTIMATED,  // 오랑붕쌤 등 추정치
    HYBRID,     // Billing + 추정 혼합
}

// ── AI별 비용 (Billing 중심) ──
data class AiCostBreakdown(
    val aiId: String,
    val name: String,
    val color: String,
    // Billing API 실제 비용 (있으면)
    val billingToday: Double? = null,
    val billingMonth: Double? = null,
    // 오랑붕쌤 추정 비용 (있으면)
    val estimatedToday: Double = 0.0,
    val estimatedMonth: Double = 0.0,
) {
    /** 표시용: Billing 우선, 없으면 추정치 */
    val todayCost: Double get() = billingToday ?: estimatedToday
    val monthCost: Double get() = billingMonth ?: estimatedMonth

    val source: CostSource get() = when {
        billingMonth != null && estimatedMonth > 0 -> CostSource.HYBRID
        billingMonth != null -> CostSource.BILLING
        else -> CostSource.ESTIMATED
    }

    /** Billing vs 추정 차이 (둘 다 있을 때만) */
    val monthDiff: Double? get() = if (billingMonth != null && estimatedMonth > 0)
        billingMonth - estimatedMonth else null
}

// ── 시스템별 비용 (오랑붕쌤 상세) ──
data class SystemCost(
    val systemName: String,
    val todayCost: Double = 0.0,
    val monthCost: Double = 0.0,
)

// ── 구독 정보 (향후 확장용) ──
data class Subscription(
    val aiId: String,
    val planName: String,        // "Max", "Pro", "Plus" 등
    val monthlyFee: Double,      // 월 구독료 (USD)
    val currency: String = "USD",
    val billingCycle: String = "monthly", // monthly, yearly
    val isActive: Boolean = true,
)

// ── API 요금 데이터 (Billing 중심) ──
data class ApiCostData(
    // 합산 비용 (Billing 우선)
    val todayTotal: Double = 0.0,
    val monthTotal: Double = 0.0,
    // 비용 소스
    val source: CostSource = CostSource.ESTIMATED,
    // Billing API 원본 (있��면)
    val billingToday: Double? = null,
    val billingMonth: Double? = null,
    // 오랑붕쌤 추정 원본 (있으면)
    val estimatedToday: Double? = null,
    val estimatedMonth: Double? = null,
    // AI별 상세
    val byAI: List<AiCostBreakdown> = emptyList(),
    // 시스템별 상세 (오랑붕쌤에서)
    val bySys: List<SystemCost> = emptyList(),
    // 구독 정보
    val subscriptions: List<Subscription> = emptyList(),
    val lastUpdated: String = "",
    val error: String? = null,
) {
    fun todayText(): String = "$${String.format("%.4f", todayTotal)}"
    fun monthText(): String = "$${String.format("%.4f", monthTotal)}"
    fun todayKrw(): String = "≈${String.format("%,d", (todayTotal * 1450).toLong())}원"
    fun monthKrw(): String = "≈${String.format("%,d", (monthTotal * 1450).toLong())}원"

    /** 구독료 포함 총 비용 */
    val monthTotalWithSubs: Double get() =
        monthTotal + subscriptions.filter { it.isActive }.sumOf { it.monthlyFee }

    fun monthWithSubsText(): String = "$${String.format("%.2f", monthTotalWithSubs)}"
    fun monthWithSubsKrw(): String = "≈${String.format("%,d", (monthTotalWithSubs * 1450).toLong())}원"

    /** 소스 라벨 */
    fun sourceLabel(): String = when (source) {
        CostSource.BILLING -> "실제 청구"
        CostSource.ESTIMATED -> "추정"
        CostSource.HYBRID -> "실제+추정"
    }

    fun shortText(): String {
        val parts = mutableListOf<String>()
        parts.add("오늘 ${todayText()}")
        parts.add("이번달 ${monthText()}")
        return parts.joinToString(" │ ")
    }

    fun notificationTitle(): String {
        val srcTag = if (source == CostSource.BILLING) "" else " (추정)"
        return "💰 오늘 ${todayText()} │ 이번��� ${monthText()}$srcTag"
    }

    fun notificationExpanded(): String = buildString {
        // 비용 소스 표시
        append("${sourceLabel()}: 오늘 ${todayText()} / 이번 달 ${monthText()}\n")

        // Billing vs 추정 비교 (둘 다 있으면)
        if (billingMonth != null && estimatedMonth != null && estimatedMonth > 0) {
            append("  실제: $${String.format("%.4f", billingMonth)}")
            append(" vs ���정: $${String.format("%.4f", estimatedMonth)}\n")
            val diff = billingMonth - estimatedMonth
            val sign = if (diff >= 0) "+" else ""
            append("  차이: $sign$${String.format("%.4f", diff)}\n")
        }

        // AI별 상세
        if (byAI.isNotEmpty()) {
            append("\nAI별 이번 달:\n")
            byAI.filter { it.monthCost > 0 }.forEach { ai ->
                val costStr = "$${String.format("%.4f", ai.monthCost)}"
                val srcStr = when (ai.source) {
                    CostSource.BILLING -> " ✓"
                    CostSource.HYBRID -> {
                        val diff = ai.monthDiff
                        if (diff != null) {
                            val sign = if (diff >= 0) "+" else ""
                            " (추정대비 $sign$${String.format("%.4f", diff)})"
                        } else ""
                    }
                    CostSource.ESTIMATED -> " ~"
                }
                append("  ${ai.name}: $costStr$srcStr\n")
            }
        }

        // 구독 정보
        if (subscriptions.isNotEmpty()) {
            append("\n구독:\n")
            subscriptions.filter { it.isActive }.forEach { sub ->
                val aiDef = AiDefs.find(sub.aiId)
                val name = aiDef?.name ?: sub.aiId
                append("  $name ${sub.planName}: $${String.format("%.0f", sub.monthlyFee)}/월\n")
            }
            append("  총 (API+구독): ${monthWithSubsText()}\n")
        }
    }
}

// ── AI 정의 ──
object AiDefs {
    data class AiDef(
        val id: String,
        val name: String,
        val color: String,
        val usageUrl: String,       // 비용 확인 사이트
        val hasBillingApi: Boolean,  // Billing API 지원 여부
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
            append("\n💰 API 요금 (${it.sourceLabel()})\n")
            append("오늘: ${it.todayText()} (${it.todayKrw()})\n")
            append("이번 달: ${it.monthText()} (${it.monthKrw()})")
            if (it.subscriptions.isNotEmpty()) {
                append("\n구독 포함: ${it.monthWithSubsText()}")
            }
        }
    }
}
