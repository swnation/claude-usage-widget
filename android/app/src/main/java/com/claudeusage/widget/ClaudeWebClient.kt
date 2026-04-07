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
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val gson = Gson()
    private var cachedOrgId: String? = null

    fun updateSessionKey(key: String) {
        sessionKey = key.trim()
    }

    /** org ID를 외부에서 직접 설정 (LoginActivity에서 JS로 가져온 값) */
    fun setOrgId(orgId: String) {
        cachedOrgId = orgId
    }

    fun fetchUsage(): Result<PlanUsage> {
        if (sessionKey.isBlank()) {
            return Result.failure(Exception("로그인이 필요합니다"))
        }

        val orgId = cachedOrgId
            ?: return Result.failure(Exception("org ID 없음. 다시 로그인하세요."))

        return fetchRateLimits(orgId)
    }

    private fun fetchOrgId(): Result<String> {
        // 방법 1: /api/organizations 직접 호출
        try {
            val orgRequest = buildRequest("/api/organizations")
            client.newCall(orgRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val orgId = parseOrgIdFromOrganizations(body)
                    if (orgId != null) return Result.success(orgId)
                }
            }
        } catch (_: Exception) {}

        // 방법 2: /api/bootstrap에서 추출
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
                    ?: return Result.failure(Exception("JSON 파싱 실패"))

                val orgId = findOrgId(json)

                if (orgId != null) Result.success(orgId)
                else {
                    val debug = json.keySet().joinToString(", ") { key ->
                        val el = json.get(key)
                        val type = when {
                            el == null || el.isJsonNull -> "null"
                            el.isJsonObject -> "obj(${el.asJsonObject.keySet().take(5)})"
                            el.isJsonArray -> "arr[${el.asJsonArray.size()}]"
                            el.isJsonPrimitive -> "\"${el.asString.take(30)}\""
                            else -> el.javaClass.simpleName
                        }
                        "$key=$type"
                    }
                    Result.failure(Exception("org ID 못 찾음. $debug"))
                }
            }
        } catch (e: Exception) {
            Result.failure(Exception("네트워크 오류: ${e.message}"))
        }
    }

    /** /api/organizations 응답에서 org ID 추출 */
    private fun parseOrgIdFromOrganizations(body: String): String? {
        return try {
            val element = gson.fromJson(body, JsonElement::class.java)
            if (element.isJsonArray) {
                // 배열이면 첫 번째 org의 uuid
                element.asJsonArray.firstOrNull()?.let { safeObject(it) }?.let {
                    safeString(it, "uuid") ?: safeString(it, "id")
                }
            } else if (element.isJsonObject) {
                val json = element.asJsonObject
                // 직접 uuid
                safeString(json, "uuid") ?: safeString(json, "id")
                // 또는 data 배열 안에
                ?: safeArray(json, "data")?.firstOrNull()?.let { safeObject(it) }?.let {
                    safeString(it, "uuid") ?: safeString(it, "id")
                }
            } else null
        } catch (_: Exception) { null }
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

    private fun parseRateLimits(body: String): Result<PlanUsage> {
        return try {
            val json = gson.fromJson(body, JsonObject::class.java)
                ?: return Result.failure(Exception("JSON 파싱 실패"))

            var sessionLimit: UsageLimit? = null
            var weeklyLimit: UsageLimit? = null

            val planName = detectPlanName(json)

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
                        val obj = safeObject(element) ?: continue
                        val limit = parseUsageFromObj(obj) ?: continue

                        val windowSec = safeLong(obj, "window_seconds")
                            ?: safeLong(obj, "window")
                            ?: safeLong(obj, "interval_seconds")

                        val type = safeString(obj, "type")?.lowercase()
                            ?: safeString(obj, "limit_type")?.lowercase()
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
                                if (sessionLimit == null) sessionLimit = limit.copy(label = "현재 세션")
                                else if (weeklyLimit == null) weeklyLimit = limit.copy(label = "주간 한도")
                            }
                        }
                    }
                }
            }

            // 방법 3: 최상위 필드에서 직접 퍼센트 추출
            if (sessionLimit == null) {
                val pct = safeDouble(json, "session_usage_percent")
                    ?: safeDouble(json, "current_usage_percent")
                    ?: safeDouble(json, "usage_percent")
                if (pct != null) {
                    val reset = safeString(json, "session_resets_at")
                        ?: safeString(json, "resets_at") ?: ""
                    sessionLimit = UsageLimit("현재 세션", pct, reset)
                }
            }

            if (weeklyLimit == null) {
                val pct = safeDouble(json, "weekly_usage_percent")
                    ?: safeDouble(json, "weekly_percent")
                if (pct != null) {
                    val reset = safeString(json, "weekly_resets_at") ?: ""
                    weeklyLimit = UsageLimit("주간 한도", pct, reset)
                }
            }

            Result.success(
                PlanUsage(
                    planName = planName,
                    session = sessionLimit,
                    weekly = weeklyLimit,
                    lastUpdated = Instant.now().toString(),
                    // 디버깅: 아무 데이터도 파싱 못 했으면 응답 키 표시
                    error = if (sessionLimit == null && weeklyLimit == null)
                        "응답에서 사용량을 찾을 수 없습니다. 키: ${json.keySet()}" else null,
                )
            )
        } catch (e: Exception) {
            Result.failure(Exception("파싱 오류: ${e.message}"))
        }
    }

    // === 안전한 JSON 접근 헬퍼 ===

    private fun safeString(obj: JsonObject, key: String): String? {
        val el = obj.get(key) ?: return null
        if (el.isJsonNull) return null
        return try { el.asString } catch (e: Exception) { null }
    }

    private fun safeDouble(obj: JsonObject, key: String): Double? {
        val el = obj.get(key) ?: return null
        if (el.isJsonNull) return null
        return try { el.asDouble } catch (e: Exception) { null }
    }

    private fun safeLong(obj: JsonObject, key: String): Long? {
        val el = obj.get(key) ?: return null
        if (el.isJsonNull) return null
        return try { el.asLong } catch (e: Exception) { null }
    }

    private fun safeArray(json: JsonObject, key: String): JsonArray? {
        val el = json.get(key) ?: return null
        if (el.isJsonNull) return null
        return if (el.isJsonArray) el.asJsonArray else null
    }

    private fun safeObject(element: JsonElement?): JsonObject? {
        if (element == null || element.isJsonNull) return null
        return if (element.isJsonObject) element.asJsonObject else null
    }

    // === 파싱 로직 ===

    private fun tryParseLimit(json: JsonObject, key: String, label: String): UsageLimit? {
        val el = json.get(key) ?: return null
        if (el.isJsonNull || !el.isJsonObject) return null
        return parseUsageFromObj(el.asJsonObject)?.copy(label = label)
    }

    private fun parseUsageFromObj(obj: JsonObject): UsageLimit? {
        val percent = safeDouble(obj, "percent_used")
            ?: safeDouble(obj, "usage_percent")
            ?: safeDouble(obj, "percent")

        if (percent != null) {
            val reset = safeString(obj, "resets_at")
                ?: safeString(obj, "reset_at") ?: ""
            return UsageLimit("", percent, reset)
        }

        val used = safeDouble(obj, "current_usage")
            ?: safeDouble(obj, "used")
            ?: safeDouble(obj, "messages_used")
        val limit = safeDouble(obj, "limit")
            ?: safeDouble(obj, "message_limit")
            ?: safeDouble(obj, "messages_limit")

        if (used != null && limit != null && limit > 0) {
            val pct = (used / limit) * 100
            val reset = safeString(obj, "resets_at")
                ?: safeString(obj, "reset_at") ?: ""
            return UsageLimit("", pct, reset)
        }

        return null
    }

    private fun detectPlanName(json: JsonObject): String {
        val plan = safeString(json, "plan")
            ?: safeString(json, "plan_name")
            ?: safeString(json, "subscription_type")
        return when {
            plan != null && plan.lowercase().contains("max") -> "Max"
            plan != null && plan.lowercase().contains("pro") -> "Pro"
            plan != null -> plan.replaceFirstChar { it.uppercase() }
            else -> "Max"
        }
    }

    /** 다양한 bootstrap 응답 구조에서 org ID를 찾음 */
    private fun findOrgId(json: JsonObject): String? {
        // account가 객체인 경우 (실제 응답 구조)
        safeObject(json.get("account"))?.let { account ->
            // account.memberships[].organization.uuid
            safeArray(account, "memberships")?.forEach { elem ->
                val obj = safeObject(elem) ?: return@forEach
                val orgUuid = safeObject(obj.get("organization"))?.let { safeString(it, "uuid") }
                if (orgUuid != null) return orgUuid
            }
            // account.uuid 또는 account.organization_uuid
            safeString(account, "organization_uuid")?.let { return it }
            safeString(account, "uuid")?.let { return it }

            // account 내부 모든 객체에서 uuid 탐색
            for (key in account.keySet()) {
                val child = safeObject(account.get(key)) ?: continue
                safeString(child, "uuid")?.let { return it }
            }
        }

        // account가 배열인 경우
        safeArray(json, "account")?.forEach { elem ->
            val obj = safeObject(elem) ?: return@forEach
            val orgUuid = safeObject(obj.get("organization"))?.let { safeString(it, "uuid") }
            if (orgUuid != null) return orgUuid
        }

        // statsig.user에서 찾기
        safeObject(json.get("statsig"))?.let { statsig ->
            safeObject(statsig.get("user"))?.let { user ->
                safeString(user, "organizationID")?.let { return it }
                safeString(user, "organization_id")?.let { return it }
                safeString(user, "orgID")?.let { return it }
                safeString(user, "userID")?.let { /* userID는 org가 아님, 스킵 */ }
                // custom 필드
                safeObject(user.get("custom"))?.let { custom ->
                    safeString(custom, "organizationID")?.let { return it }
                    safeString(custom, "organization_id")?.let { return it }
                }
            }
        }

        // growthbook.user에서 찾기
        safeObject(json.get("growthbook"))?.let { gb ->
            safeObject(gb.get("user"))?.let { user ->
                safeString(user, "organizationID")?.let { return it }
                safeString(user, "organization_id")?.let { return it }
            }
        }

        // 직접 필드
        safeString(json, "organization_uuid")?.let { return it }

        // memberships 배열 (최상위)
        safeArray(json, "memberships")?.forEach { elem ->
            val obj = safeObject(elem) ?: return@forEach
            val orgUuid = safeObject(obj.get("organization"))?.let { safeString(it, "uuid") }
            if (orgUuid != null) return orgUuid
        }

        // organizations 배열
        safeArray(json, "organizations")?.forEach { elem ->
            val obj = safeObject(elem) ?: return@forEach
            val uuid = safeString(obj, "uuid") ?: safeString(obj, "id")
            if (uuid != null) return uuid
        }

        // 최상위 uuid/id
        safeString(json, "uuid")?.let { return it }
        safeString(json, "id")?.let { return it }

        // 모든 최상위 키를 순회하며 uuid가 있는 객체 찾기
        for (key in json.keySet()) {
            val obj = safeObject(json.get(key)) ?: continue
            safeString(obj, "uuid")?.let { return it }
            // 한 단계 더 깊이
            for (childKey in obj.keySet()) {
                val child = safeObject(obj.get(childKey)) ?: continue
                safeString(child, "uuid")?.let { return it }
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
        val cookieValue = when {
            sessionKey.contains("sessionKey=") -> sessionKey
            sessionKey.contains(";") -> sessionKey
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
