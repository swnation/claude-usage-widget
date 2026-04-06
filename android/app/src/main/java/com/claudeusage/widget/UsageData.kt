package com.claudeusage.widget

/**
 * Claude 앱 사용량 화면과 동일한 데이터 구조.
 *
 * - 현재 세션: 5시간 윈도우 사용량 (%)
 * - 주간 한도: 모든 모델 통합 주간 사용량 (%)
 */
data class UsageLimit(
    val label: String,           // "현재 세션", "주간 한도" 등
    val usedPercent: Double,     // 0~100
    val resetsAt: String,        // ISO 8601 또는 표시용 문자열
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
            resetsAt  // 파싱 실패 시 원본 그대로
        }
    }

    fun statusText(): String = "${percentText} 사용됨"
}

data class PlanUsage(
    val planName: String = "Max",
    val session: UsageLimit? = null,    // 현재 세션 (5시간)
    val weekly: UsageLimit? = null,     // 주간 한도
    val lastUpdated: String = "",
    val error: String? = null,
) {
    fun notificationTitle(): String {
        val s = session ?: return "Claude $planName"
        val emoji = when {
            s.usedPercent >= 90 -> "🔴"
            s.usedPercent >= 70 -> "🟡"
            else -> "🟢"
        }
        return "$emoji Claude $planName │ ${s.percentText}"
    }

    fun notificationShort(): String {
        val parts = mutableListOf<String>()
        session?.let { parts.add("세션 ${it.percentText}") }
        weekly?.let { parts.add("주간 ${it.percentText}") }
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
}
