package com.claudeusage.widget

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * claude.ai 내부 API에서 사용량 데이터를 가져옴.
 * 클로드 앱의 설정 > 사용량 화면과 동일한 데이터:
 * - 현재 세션 (5시간 윈도우)
 * - 주간 한도
 */
class ClaudeWebClient(private var sessionKey: String) {

    companion object {
        private const val BASE_URL = "https://claude.ai"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    private val gson = Gson()
    private var cachedOrgId: String? = null

    fun updateSessionKey(key: String) {
        sessionKey = key.trim()
        cachedOrgId = null
    }

    fun fetchUsage(): Result<PlanUsage> {
        if (sessionKey.isBlank()) {
            return Result.failure(Exception("로그인이 필요합니다"))
        }

        val orgId = cachedOrgId ?: run {
            val id = fetchOrgId().getOrElse { return Result.failure(it) }
            cachedOrgId = id
            id
        }

        return fetchRateLimits(orgId)
    }

    private fun fetchOrgId(): Result<String> {
        val request = buildRequest("/api/bootstrap")
        return try {
            client.newCall(request).execute().use { response ->
                if (response.code == 401 || response.code == 403) {
                    return Result.failure(Exception("세션 만료. 다시 로그인하세요."))
                }
                if (!response.isSuccessful) {
                    return Result.failure(Exception("Bootstrap 실패: HTTP ${response.code}"))
                }

                val body = response.body?.string()
                    ?: return Result.failure(Exception("빈 응답"))
                val json = gson.fromJson(body, JsonObject::class.java)

                val orgId = findOrgId(json)

                if (orgId != null) Result.success(orgId)
                else Result.failure(Exception("조직 ID를 찾을 수 없습니다. 응답 키: ${json.keySet()}"))
            }
        } catch (e: Exception) {
            Result.failure(Exception("네트워크 오류: ${e.message}"))
        }
    }

    private fun fetchRateLimits(orgId: String): Result<PlanUsage> {
        val request = buildRequest("/api/organizations/$orgId/rate_limits")
        return try {
            client.newCall(request).execute().use { response ->
                if (response.code == 401 || response.code == 403) {
                    cachedOrgId = null
                    return Result.failure(Exception("세션 만료"))
                }
                if (!response.isSuccessful) {
                    return Result.failure(Exception("사용량 조회 실패: HTTP ${response.code}"))
                }

                val body = response.body?.string()
                    ?: return Result.failure(Exception("빈 응답"))
                parseRateLimits(body)
            }
        } catch (e: Exception) {
            Result.failure(Exception("네트워크 오류: ${e.message}"))
        }
    }

    /**
     * API 응답을 파싱하여 현재 세션 + 주간 한도를 추출.
     * 응답 구조가 정확히 알려지지 않아 여러 가능한 형태를 시도.
     */
    private fun parseRateLimits(body: String): Result<PlanUsage> {
        return try {
            val json = gson.fromJson(body, JsonObject::class.java)

            var sessionLimit: UsageLimit? = null
            var weeklyLimit: UsageLimit? = null
            var planName = "Max"

            // 플랜 이름 감지
            planName = detectPlanName(json)

            // 방법 1: 직접적인 session/weekly 필드
            sessionLimit = tryParseLimit(json, "session", "현재 세션")
                ?: tryParseLimit(json, "current_session", "현재 세션")
                ?: tryParseLimit(json, "short_term", "현재 세션")

            weeklyLimit = tryParseLimit(json, "weekly", "주간 한도")
                ?: tryParseLimit(json, "long_term", "주간 한도")
                ?: tryParseLimit(json, "weekly_limit", "주간 한도")

            // 방법 2: rate_limits 배열에서 window 크기로 구분
            if (sessionLimit == null || weeklyLimit == null) {
                val limitsArray = safeArray(json, "rate_limits")
                    ?: safeArray(json, "data")
                    ?: safeArray(json, "limits")

                if (limitsArray != null) {
                    for (element in limitsArray) {
                        val obj = element.asJsonObject
                        val limit = parseUsageFromObj(obj) ?: continue

                        val windowSec = obj.get("window_seconds")?.asLong
                            ?: obj.get("window")?.asLong
                            ?: obj.get("interval_seconds")?.asLong

                        val type = obj.get("type")?.asString?.lowercase()
                            ?: obj.get("limit_type")?.asString?.lowercase()
                            ?: ""

                        when {
                            type.contains("session") || type.contains("short") ||
                            (windowSec != null && windowSec <= 18000) -> {
                                if (sessionLimit == null) sessionLimit = limit.copy(label = "현재 세션")
                            }
                            type.contains("week") || type.contains("long") ||
                            (windowSec != null && windowSec > 18000) -> {
                                if (weeklyLimit == null) weeklyLimit = limit.copy(label = "주간 한도")
                            }
                            else -> {
                                // 첫 번째를 세션, 두 번째를 주간으로
                                if (sessionLimit == null) sessionLimit = limit.copy(label = "현재 세션")
                                else if (weeklyLimit == null) weeklyLimit = limit.copy(label = "주간 한도")
                            }
                        }
                    }
                }
            }

            // 방법 3: 최상위 필드에서 직접 퍼센트 추출
            if (sessionLimit == null) {
                val pct = json.get("session_usage_percent")?.asDouble
                    ?: json.get("current_usage_percent")?.asDouble
                    ?: json.get("usage_percent")?.asDouble
                val reset = json.get("session_resets_at")?.asString
                    ?: json.get("resets_at")?.asString ?: ""
                if (pct != null) {
                    sessionLimit = UsageLimit("현재 세션", pct, reset)
                }
            }

            if (weeklyLimit == null) {
                val pct = json.get("weekly_usage_percent")?.asDouble
                    ?: json.get("weekly_percent")?.asDouble
                val reset = json.get("weekly_resets_at")?.asString ?: ""
                if (pct != null) {
                    weeklyLimit = UsageLimit("주간 한도", pct, reset)
                }
            }

            Result.success(
                PlanUsage(
                    planName = planName,
                    session = sessionLimit,
                    weekly = weeklyLimit,
                    lastUpdated = Instant.now().toString(),
                )
            )
        } catch (e: Exception) {
            Result.failure(Exception("파싱 오류: ${e.message}"))
        }
    }

    private fun tryParseLimit(json: JsonObject, key: String, label: String): UsageLimit? {
        val obj = json.getAsJsonObject(key) ?: return null
        return parseUsageFromObj(obj)?.copy(label = label)
    }

    private fun parseUsageFromObj(obj: JsonObject): UsageLimit? {
        // 퍼센트 직접 제공
        val percent = obj.get("percent_used")?.asDouble
            ?: obj.get("usage_percent")?.asDouble
            ?: obj.get("percent")?.asDouble

        if (percent != null) {
            val reset = obj.get("resets_at")?.asString
                ?: obj.get("reset_at")?.asString ?: ""
            return UsageLimit("", percent, reset)
        }

        // used/limit에서 계산
        val used = obj.get("current_usage")?.asDouble
            ?: obj.get("used")?.asDouble
            ?: obj.get("messages_used")?.asDouble
        val limit = obj.get("limit")?.asDouble
            ?: obj.get("message_limit")?.asDouble
            ?: obj.get("messages_limit")?.asDouble

        if (used != null && limit != null && limit > 0) {
            val pct = (used / limit) * 100
            val reset = obj.get("resets_at")?.asString
                ?: obj.get("reset_at")?.asString ?: ""
            return UsageLimit("", pct, reset)
        }

        return null
    }

    private fun detectPlanName(json: JsonObject): String {
        val plan = json.get("plan")?.asString
            ?: json.get("plan_name")?.asString
            ?: json.get("subscription_type")?.asString
        return when {
            plan != null && plan.lowercase().contains("max") -> "Max"
            plan != null && plan.lowercase().contains("pro") -> "Pro"
            plan != null -> plan.replaceFirstChar { it.uppercase() }
            else -> "Max"
        }
    }

    /** JsonObject에서 안전하게 JsonArray를 꺼냄 (null/JsonNull 방지) */
    private fun safeArray(json: JsonObject, key: String): JsonArray? {
        val element = json.get(key) ?: return null
        return if (element.isJsonArray) element.asJsonArray else null
    }

    /** JsonObject에서 안전하게 JsonObject를 꺼냄 */
    private fun safeObject(element: JsonElement?): JsonObject? {
        if (element == null || element.isJsonNull) return null
        return if (element.isJsonObject) element.asJsonObject else null
    }

    /** 다양한 bootstrap 응답 구조에서 org ID를 찾음 */
    private fun findOrgId(json: JsonObject): String? {
        // account 배열
        safeArray(json, "account")?.forEach { elem ->
            val obj = safeObject(elem) ?: return@forEach
            val orgUuid = safeObject(obj.get("organization"))?.get("uuid")?.asString
            if (orgUuid != null) return orgUuid
        }

        // 직접 필드
        json.get("organization_uuid")?.let {
            if (!it.isJsonNull) return it.asString
        }

        // memberships 배열
        safeArray(json, "memberships")?.forEach { elem ->
            val obj = safeObject(elem) ?: return@forEach
            val orgUuid = safeObject(obj.get("organization"))?.get("uuid")?.asString
            if (orgUuid != null) return orgUuid
        }

        // organizations 배열
        safeArray(json, "organizations")?.forEach { elem ->
            val obj = safeObject(elem) ?: return@forEach
            val uuid = obj.get("uuid")?.asString ?: obj.get("id")?.asString
            if (uuid != null) return uuid
        }

        // 최상위에서 uuid/id 직접 탐색
        json.get("uuid")?.let { if (!it.isJsonNull) return it.asString }
        json.get("id")?.let { if (!it.isJsonNull) return it.asString }

        // 모든 키를 순회하며 uuid 포함된 객체 찾기
        for (key in json.keySet()) {
            val value = json.get(key)
            if (value != null && value.isJsonObject) {
                val obj = value.asJsonObject
                val uuid = obj.get("uuid")?.let { if (!it.isJsonNull) it.asString else null }
                if (uuid != null) return uuid
            }
        }

        return null
    }

    fun checkSession(): Boolean {
        if (sessionKey.isBlank()) return false
        val request = buildRequest("/api/bootstrap")
        return try {
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    private fun buildRequest(path: String): Request {
        // 전체 쿠키 문자열이면 그대로 사용, 아니면 sessionKey= 접두어 추가
        val cookieValue = when {
            sessionKey.contains("sessionKey=") -> sessionKey
            sessionKey.contains(";") -> sessionKey  // 전체 쿠키 문자열
            else -> "sessionKey=$sessionKey"
        }

        return Request.Builder()
            .url("$BASE_URL$path")
            .header("Cookie", cookieValue)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
            .header("Accept", "application/json")
            .get()
            .build()
    }
}
