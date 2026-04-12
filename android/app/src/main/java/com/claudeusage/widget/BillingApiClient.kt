package com.claudeusage.widget

import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 통합 Billing API 클라이언트.
 * Admin/Billing API를 호출하여 실제 청구 비용을 가져온다.
 * 오랑붕쌤 추정치와 병합하여 최종 ApiCostData를 생성한다.
 */
object BillingApiClient {

    data class BillingResult(
        val todayCost: Double,
        val monthCost: Double,
        val error: String? = null,
    )

    data class MergedCostResult(
        val costData: ApiCostData?,
        val error: String? = null,
    )

    /**
     * Anthropic Admin API로 실제 Claude 비용을 가져온다.
     * @return 이번 달 일별 비용 데이터
     */
    fun fetchAnthropicBilling(adminKey: String): BillingResult {
        try {
            val now = Instant.now()
            val kstNow = LocalDate.now(ZoneId.of("Asia/Seoul"))
            val monthStart = kstNow.withDayOfMonth(1)
            val startingAt = "${monthStart}T00:00:00Z"
            val endingAt = now.toString()

            val conn = URL(
                "https://api.anthropic.com/v1/organizations/cost_report" +
                "?starting_at=$startingAt&ending_at=$endingAt&bucket_width=1d"
            ).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("x-api-key", adminKey)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.readText() ?: ""
            conn.disconnect()

            if (code != 200) {
                val errMsg = try {
                    JsonParser.parseString(body).asJsonObject
                        .get("error")?.asJsonObject?.get("message")?.asString
                } catch (_: Exception) { null } ?: "HTTP $code"
                return BillingResult(0.0, 0.0, errMsg)
            }

            val json = JsonParser.parseString(body).asJsonObject
            var monthCents = 0.0
            var todayCents = 0.0
            val today = kstNow.toString()

            json.getAsJsonArray("data")?.forEach { bucket ->
                val bucketObj = bucket.asJsonObject
                var bucketTotal = 0.0
                bucketObj.getAsJsonArray("results")?.forEach { r ->
                    val amount = r.asJsonObject.get("amount")?.takeIf { it.isJsonPrimitive }?.asString ?: "0"
                    bucketTotal += amount.toDoubleOrNull() ?: 0.0
                }
                monthCents += bucketTotal

                // 오늘 날짜 매칭
                val startedAt = bucketObj.get("started_at")?.asString ?: ""
                if (startedAt.startsWith(today)) {
                    todayCents += bucketTotal
                }
            }

            return BillingResult(todayCents / 100.0, monthCents / 100.0)
        } catch (e: Exception) {
            return BillingResult(0.0, 0.0, e.message ?: "연결 실패")
        }
    }

    /**
     * OpenAI Billing API로 실제 GPT 비용을 가져온다.
     */
    fun fetchOpenAiBilling(apiKey: String): BillingResult {
        try {
            val now = Instant.now()
            val kstNow = LocalDate.now(ZoneId.of("Asia/Seoul"))
            val monthStart = kstNow.withDayOfMonth(1)
            val monthStartEpoch = monthStart.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
            val nowEpoch = now.epochSecond
            val today = kstNow.toString()

            val conn = URL(
                "https://api.openai.com/v1/organization/costs" +
                "?start_time=$monthStartEpoch&end_time=$nowEpoch&bucket_width=1d"
            ).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.readText() ?: ""
            conn.disconnect()

            if (code != 200) {
                val errMsg = try {
                    JsonParser.parseString(body).asJsonObject
                        .get("error")?.asJsonObject?.get("message")?.asString
                } catch (_: Exception) { null } ?: "HTTP $code"
                return BillingResult(0.0, 0.0, errMsg)
            }

            val json = JsonParser.parseString(body).asJsonObject
            var monthTotal = 0.0
            var todayTotal = 0.0

            json.getAsJsonArray("data")?.forEach { bucket ->
                val bucketObj = bucket.asJsonObject
                var bucketTotal = 0.0
                bucketObj.getAsJsonArray("results")?.forEach { r ->
                    bucketTotal += r.asJsonObject.getAsJsonObject("amount")
                        ?.get("value")?.takeIf { it.isJsonPrimitive }?.asDouble ?: 0.0
                }
                monthTotal += bucketTotal

                // 오늘 날짜 매칭 (epoch → date)
                val startTime = bucketObj.get("start_time")?.asLong
                if (startTime != null) {
                    val bucketDate = Instant.ofEpochSecond(startTime)
                        .atZone(ZoneId.of("Asia/Seoul")).toLocalDate().toString()
                    if (bucketDate == today) {
                        todayTotal += bucketTotal
                    }
                }
            }

            return BillingResult(todayTotal, monthTotal)
        } catch (e: Exception) {
            return BillingResult(0.0, 0.0, e.message ?: "연결 실패")
        }
    }

    /**
     * 모든 Billing API를 호출하고 오랑붕쌤 추정치와 병합한다.
     *
     * @param anthropicKey Anthropic Admin API 키 (null이면 스킵)
     * @param openaiKey OpenAI API 키 (null이면 스킵)
     * @param estimatedData 오랑붕쌤에서 가져온 추정 데이터 (null이면 스킵)
     * @param subscriptions 구독 목록
     */
    fun fetchAndMerge(
        anthropicKey: String?,
        openaiKey: String?,
        estimatedData: ApiCostData?,
        subscriptions: List<Subscription> = emptyList(),
    ): MergedCostResult {
        val billingResults = mutableMapOf<String, BillingResult>()
        val errors = mutableListOf<String>()

        // Billing API 호출
        if (!anthropicKey.isNullOrEmpty()) {
            val result = fetchAnthropicBilling(anthropicKey)
            billingResults["claude"] = result
            if (result.error != null) errors.add("Claude: ${result.error}")
        }

        if (!openaiKey.isNullOrEmpty()) {
            val result = fetchOpenAiBilling(openaiKey)
            billingResults["gpt"] = result
            if (result.error != null) errors.add("GPT: ${result.error}")
        }

        val hasBilling = billingResults.values.any { it.error == null }
        val hasEstimated = estimatedData != null

        // 둘 다 없으면 에러
        if (!hasBilling && !hasEstimated) {
            return MergedCostResult(null,
                if (errors.isNotEmpty()) errors.joinToString("; ")
                else "Billing API 키 또는 오랑붕쌤 연결이 필요합니다")
        }

        // 추정 데이터를 AI별 맵으로
        val estimatedByAI = mutableMapOf<String, Pair<Double, Double>>() // aiId → (today, month)
        estimatedData?.byAI?.forEach { ai ->
            estimatedByAI[ai.aiId] = Pair(ai.estimatedToday, ai.estimatedMonth)
        }

        // AI별 병합
        val allAiIds = (billingResults.keys + estimatedByAI.keys).toSet()
        var todayTotal = 0.0
        var monthTotal = 0.0
        var billingTodaySum = 0.0
        var billingMonthSum = 0.0
        var estimatedTodaySum = 0.0
        var estimatedMonthSum = 0.0

        val byAI = allAiIds.map { aiId ->
            val aiDef = AiDefs.find(aiId)
            val billing = billingResults[aiId]
            val estimated = estimatedByAI[aiId]

            val billingToday = if (billing != null && billing.error == null) billing.todayCost else null
            val billingMonth = if (billing != null && billing.error == null) billing.monthCost else null
            val estToday = estimated?.first ?: 0.0
            val estMonth = estimated?.second ?: 0.0

            // 합산: Billing 우선
            val effectiveToday = billingToday ?: estToday
            val effectiveMonth = billingMonth ?: estMonth
            todayTotal += effectiveToday
            monthTotal += effectiveMonth

            if (billingToday != null) billingTodaySum += billingToday
            if (billingMonth != null) billingMonthSum += billingMonth
            estimatedTodaySum += estToday
            estimatedMonthSum += estMonth

            AiCostBreakdown(
                aiId = aiId,
                name = aiDef?.name ?: aiId,
                color = aiDef?.color ?: "#888888",
                billingToday = billingToday,
                billingMonth = billingMonth,
                estimatedToday = estToday,
                estimatedMonth = estMonth,
            )
        }.sortedByDescending { it.monthCost }

        // 전체 소스 판단
        val source = when {
            hasBilling && hasEstimated -> CostSource.HYBRID
            hasBilling -> CostSource.BILLING
            else -> CostSource.ESTIMATED
        }

        val costData = ApiCostData(
            todayTotal = todayTotal,
            monthTotal = monthTotal,
            source = source,
            billingToday = if (hasBilling) billingTodaySum else null,
            billingMonth = if (hasBilling) billingMonthSum else null,
            estimatedToday = if (hasEstimated) estimatedTodaySum else null,
            estimatedMonth = if (hasEstimated) estimatedMonthSum else null,
            byAI = byAI,
            bySys = estimatedData?.bySys ?: emptyList(),
            subscriptions = subscriptions,
            lastUpdated = Instant.now().toString(),
            error = if (errors.isNotEmpty()) errors.joinToString("; ") else null,
        )

        return MergedCostResult(costData)
    }
}
