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
 * 추정치와 병합하여 최종 ApiCostData를 생성한다.
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
            val localNow = LocalDate.now(ZoneId.systemDefault())
            val monthStart = localNow.withDayOfMonth(1)
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
            val today = localNow.toString()

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
            val localNow = LocalDate.now(ZoneId.systemDefault())
            val monthStart = localNow.withDayOfMonth(1)
            val monthStartEpoch = monthStart.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
            val nowEpoch = now.epochSecond
            val today = localNow.toString()

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
                        .atZone(ZoneId.systemDefault()).toLocalDate().toString()
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
     * xAI Management API로 Grok 사용량/비용을 가져온다.
     * POST 방식, JSON body로 분석 조건 전달.
     */
    fun fetchGrokBilling(managementKey: String, teamId: String): BillingResult {
        try {
            val localNow = LocalDate.now(ZoneId.systemDefault())
            val monthStart = localNow.withDayOfMonth(1)
            val today = localNow.toString()
            val startTime = "$monthStart 00:00:00"
            val endTime = "${localNow} 23:59:59"

            val requestBody = com.google.gson.JsonObject().apply {
                add("analyticsRequest", com.google.gson.JsonObject().apply {
                    add("timeRange", com.google.gson.JsonObject().apply {
                        addProperty("startTime", startTime)
                        addProperty("endTime", endTime)
                        addProperty("timezone", "Etc/GMT")
                    })
                    addProperty("timeUnit", "TIME_UNIT_DAY")
                    add("values", com.google.gson.JsonArray().apply {
                        add(com.google.gson.JsonObject().apply {
                            addProperty("name", "usd")
                            addProperty("aggregation", "AGGREGATION_SUM")
                        })
                    })
                    add("groupBy", com.google.gson.JsonArray().apply { add("description") })
                    add("filters", com.google.gson.JsonArray())
                })
            }

            val conn = URL(
                "https://management-api.x.ai/v1/billing/teams/$teamId/usage"
            ).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $managementKey")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.doOutput = true
            conn.outputStream.write(requestBody.toString().toByteArray())

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.readText() ?: ""
            conn.disconnect()

            if (code != 200) {
                val errMsg = try {
                    val errJson = JsonParser.parseString(body).asJsonObject
                    errJson.get("error")?.asString
                        ?: errJson.get("message")?.asString
                        ?: errJson.get("detail")?.asString
                } catch (_: Exception) { null } ?: "HTTP $code: ${body.take(200)}"
                return BillingResult(0.0, 0.0, errMsg)
            }

            val json = JsonParser.parseString(body).asJsonObject
            var monthTotal = 0.0
            var todayTotal = 0.0

            // timeSeries 배열에서 모든 모델의 일별 비용 합산
            json.getAsJsonArray("timeSeries")?.forEach { series ->
                series.asJsonObject.getAsJsonArray("dataPoints")?.forEach { dp ->
                    val dpObj = dp.asJsonObject
                    val values = dpObj.getAsJsonArray("values")
                    val cost = values?.get(0)?.asDouble ?: 0.0
                    monthTotal += cost

                    val timestamp = dpObj.get("timestamp")?.asString ?: ""
                    if (timestamp.startsWith(today)) {
                        todayTotal += cost
                    }
                }
            }

            return BillingResult(todayTotal, monthTotal)
        } catch (e: Exception) {
            return BillingResult(0.0, 0.0, e.message ?: "xAI 연결 실패")
        }
    }

    /**
     * 서비스 계정 JSON으로 OAuth2 액세스 토큰을 생성한다.
     */
    private fun getServiceAccountToken(serviceAccountJson: String): String? {
        try {
            val sa = JsonParser.parseString(serviceAccountJson).asJsonObject
            val clientEmail = sa.get("client_email")?.asString ?: return null
            val privateKeyPem = sa.get("private_key")?.asString ?: return null
            val tokenUri = sa.get("token_uri")?.asString ?: "https://oauth2.googleapis.com/token"

            val now = System.currentTimeMillis() / 1000
            val b64Flags = android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
            val header = android.util.Base64.encodeToString(
                """{"alg":"RS256","typ":"JWT"}""".toByteArray(), b64Flags)
            val claims = android.util.Base64.encodeToString(
                """{"iss":"$clientEmail","scope":"https://www.googleapis.com/auth/bigquery.readonly","aud":"$tokenUri","iat":$now,"exp":${now + 3600}}""".toByteArray(), b64Flags)
            val signInput = "$header.$claims"

            val keyPem = privateKeyPem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\\n", "").replace("\n", "").trim()
            val keyBytes = android.util.Base64.decode(keyPem, android.util.Base64.DEFAULT)
            val keySpec = java.security.spec.PKCS8EncodedKeySpec(keyBytes)
            val privateKey = java.security.KeyFactory.getInstance("RSA").generatePrivate(keySpec)
            val sig = java.security.Signature.getInstance("SHA256withRSA").run {
                initSign(privateKey)
                update(signInput.toByteArray())
                sign()
            }
            val jwt = "$signInput." + android.util.Base64.encodeToString(sig, b64Flags)

            val conn = URL(tokenUri).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.doOutput = true
            conn.outputStream.write("grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=$jwt".toByteArray())
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
            conn.disconnect()
            if (code != 200) return null
            return JsonParser.parseString(body).asJsonObject.get("access_token")?.asString
        } catch (_: Exception) { return null }
    }

    /**
     * Google Cloud BigQuery를 통해 Gemini 실제 비용을 가져온다.
     * 서비스 계정 JSON 파일로 인증.
     */
    fun fetchGeminiBilling(
        serviceAccountJson: String,
        projectId: String,
        datasetId: String,
        tableId: String,
    ): BillingResult {
        try {
            val accessToken = getServiceAccountToken(serviceAccountJson)
                ?: return BillingResult(0.0, 0.0, "서비스 계정 인증 실패. JSON 파일을 확인하세요.")

            val localNow = LocalDate.now(ZoneId.systemDefault())
            val monthStart = localNow.withDayOfMonth(1)
            val today = localNow.toString()

            val query = """
                SELECT
                  DATE(usage_start_time) as date,
                  SUM(cost) + SUM(IFNULL((SELECT SUM(c.amount) FROM UNNEST(credits) c), 0)) as net_cost
                FROM `$projectId.$datasetId.$tableId`
                WHERE (service.description = 'Gemini API'
                       OR service.description = 'Vertex AI API'
                       OR service.description = 'Generative Language API')
                  AND DATE(usage_start_time) >= '$monthStart'
                GROUP BY date
                ORDER BY date
            """.trimIndent()

            val requestBody = com.google.gson.JsonObject().apply {
                addProperty("query", query)
                addProperty("useLegacySql", false)
                addProperty("maxResults", 100)
            }

            val conn = URL(
                "https://bigquery.googleapis.com/bigquery/v2/projects/$projectId/queries"
            ).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $accessToken")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 30000
            conn.readTimeout = 30000
            conn.doOutput = true
            conn.outputStream.write(requestBody.toString().toByteArray())

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.readText() ?: ""
            conn.disconnect()

            if (code != 200) {
                val errMsg = try {
                    JsonParser.parseString(body).asJsonObject
                        .getAsJsonObject("error")?.get("message")?.asString
                } catch (_: Exception) { null } ?: "HTTP $code"
                return BillingResult(0.0, 0.0, errMsg)
            }

            val json = JsonParser.parseString(body).asJsonObject
            var monthTotal = 0.0
            var todayTotal = 0.0

            json.getAsJsonArray("rows")?.forEach { row ->
                val fields = row.asJsonObject.getAsJsonArray("f")
                val date = fields[0].asJsonObject.get("v")?.asString ?: ""
                val cost = fields[1].asJsonObject.get("v")?.asString?.toDoubleOrNull() ?: 0.0
                monthTotal += cost
                if (date == today) {
                    todayTotal = cost
                }
            }

            return BillingResult(todayTotal, monthTotal)
        } catch (e: Exception) {
            return BillingResult(0.0, 0.0, e.message ?: "BigQuery 연결 실패")
        }
    }

    /**
     * 모든 Billing API를 호출하고 추정치와 병합한다.
     *
     * @param anthropicKey Anthropic Admin API 키 (null이면 스킵)
     * @param openaiKey OpenAI API 키 (null이면 스킵)
     * @param geminiConfig GCP BigQuery 설정 (null이면 스킵)
     * @param estimatedData 추정 데이터 (null이면 스킵)
     * @param subscriptions 구독 목록
     */
    data class GeminiConfig(
        val serviceAccountJson: String,
        val projectId: String,
        val datasetId: String,
        val tableId: String,
    )

    data class GrokConfig(
        val managementKey: String,
        val teamId: String,
    )

    fun fetchAndMerge(
        anthropicKey: String?,
        openaiKey: String?,
        geminiConfig: GeminiConfig? = null,
        grokConfig: GrokConfig? = null,
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

        // Gemini: BigQuery 빌링 조회
        if (geminiConfig != null) {
            val result = fetchGeminiBilling(
                geminiConfig.serviceAccountJson, geminiConfig.projectId,
                geminiConfig.datasetId, geminiConfig.tableId
            )
            billingResults["gemini"] = result
            if (result.error != null) errors.add("Gemini: ${result.error}")
        }

        // Grok: xAI Management API
        if (grokConfig != null) {
            val result = fetchGrokBilling(grokConfig.managementKey, grokConfig.teamId)
            billingResults["grok"] = result
            if (result.error != null) errors.add("Grok: ${result.error}")
        }

        val hasBilling = billingResults.values.any { it.error == null }
        val hasEstimated = estimatedData != null

        // 둘 다 없으면 에러
        if (!hasBilling && !hasEstimated) {
            return MergedCostResult(null,
                if (errors.isNotEmpty()) errors.joinToString("; ")
                else "Billing API 키가 필요합니다")
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
