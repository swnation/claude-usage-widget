package com.claudeusage.widget

import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Fetches Claude Max/Pro plan usage from claude.ai's internal API.
 * Requires the user's sessionKey cookie from claude.ai.
 *
 * Flow:
 * 1. GET /api/bootstrap → get organization ID
 * 2. GET /api/organizations/{orgId}/rate_limits → get per-model usage
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

    /**
     * Fetch plan usage. Returns PlanUsage with model-level limits.
     */
    fun fetchUsage(): Result<PlanUsage> {
        if (sessionKey.isBlank()) {
            return Result.failure(Exception("Session key not set"))
        }

        // Step 1: Get org ID
        val orgId = cachedOrgId ?: run {
            val id = fetchOrgId().getOrElse { return Result.failure(it) }
            cachedOrgId = id
            id
        }

        // Step 2: Get rate limits / usage
        return fetchRateLimits(orgId)
    }

    private fun fetchOrgId(): Result<String> {
        val request = buildRequest("/api/bootstrap")
        return try {
            client.newCall(request).execute().use { response ->
                if (response.code == 401 || response.code == 403) {
                    return Result.failure(Exception("Session expired. Re-login to claude.ai and update cookie."))
                }
                if (!response.isSuccessful) {
                    return Result.failure(Exception("Bootstrap failed: HTTP ${response.code}"))
                }

                val body = response.body?.string() ?: return Result.failure(Exception("Empty response"))
                val json = gson.fromJson(body, JsonObject::class.java)

                // Try to extract org ID from various possible response structures
                val orgId = json.getAsJsonArray("account")
                    ?.firstOrNull()?.asJsonObject
                    ?.get("organization")?.asJsonObject
                    ?.get("uuid")?.asString
                    ?: json.get("organization_uuid")?.asString
                    ?: json.getAsJsonArray("memberships")
                        ?.firstOrNull()?.asJsonObject
                        ?.get("organization")?.asJsonObject
                        ?.get("uuid")?.asString

                if (orgId != null) {
                    Result.success(orgId)
                } else {
                    Result.failure(Exception("Could not find organization ID in response"))
                }
            }
        } catch (e: Exception) {
            Result.failure(Exception("Network error: ${e.message}"))
        }
    }

    private fun fetchRateLimits(orgId: String): Result<PlanUsage> {
        val request = buildRequest("/api/organizations/$orgId/rate_limits")
        return try {
            client.newCall(request).execute().use { response ->
                if (response.code == 401 || response.code == 403) {
                    cachedOrgId = null
                    return Result.failure(Exception("Session expired"))
                }
                if (!response.isSuccessful) {
                    return Result.failure(Exception("Rate limits failed: HTTP ${response.code}"))
                }

                val body = response.body?.string() ?: return Result.failure(Exception("Empty response"))
                parseRateLimits(body)
            }
        } catch (e: Exception) {
            Result.failure(Exception("Network error: ${e.message}"))
        }
    }

    private fun parseRateLimits(body: String): Result<PlanUsage> {
        return try {
            val json = gson.fromJson(body, JsonObject::class.java)
            val models = mutableListOf<ModelUsage>()

            // The response might be a direct object or have a "rate_limits" array
            val limitsArray = json.getAsJsonArray("rate_limits")
                ?: json.getAsJsonArray("data")

            if (limitsArray != null) {
                for (element in limitsArray) {
                    val obj = element.asJsonObject
                    val tierName = obj.get("model_tier")?.asString
                        ?: obj.get("tier")?.asString
                        ?: obj.get("model")?.asString
                        ?: continue

                    val displayName = tierToDisplayName(tierName)
                    val used = obj.get("current_usage")?.asInt
                        ?: obj.get("used")?.asInt
                        ?: obj.get("messages_used")?.asInt
                        ?: 0
                    val limit = obj.get("message_limit")?.asInt
                        ?: obj.get("limit")?.asInt
                        ?: obj.get("messages_limit")?.asInt
                        ?: 0
                    val resetsAt = obj.get("resets_at")?.asString
                        ?: obj.get("reset_at")?.asString
                        ?: ""
                    val windowSec = obj.get("window_seconds")?.asInt
                    val windowHours = if (windowSec != null) windowSec / 3600 else 5

                    models.add(
                        ModelUsage(
                            modelName = displayName,
                            modelTier = tierName,
                            used = used,
                            limit = limit,
                            resetsAt = resetsAt,
                            windowHours = windowHours,
                        )
                    )
                }
            } else {
                // Try parsing as a flat object with model keys
                for (key in json.keySet()) {
                    val value = json.get(key)
                    if (value != null && value.isJsonObject) {
                        val obj = value.asJsonObject
                        val used = obj.get("current_usage")?.asInt
                            ?: obj.get("used")?.asInt ?: continue
                        val limit = obj.get("message_limit")?.asInt
                            ?: obj.get("limit")?.asInt ?: continue
                        val resetsAt = obj.get("resets_at")?.asString ?: ""

                        models.add(
                            ModelUsage(
                                modelName = tierToDisplayName(key),
                                modelTier = key,
                                used = used,
                                limit = limit,
                                resetsAt = resetsAt,
                            )
                        )
                    }
                }
            }

            // Sort: Opus first, then Sonnet, then Haiku
            val order = listOf("opus", "sonnet", "haiku")
            models.sortBy { m ->
                order.indexOfFirst { m.modelName.lowercase().contains(it) }
                    .let { if (it == -1) 99 else it }
            }

            val planName = detectPlanName(json)

            Result.success(
                PlanUsage(
                    planName = planName,
                    models = models,
                    lastUpdated = Instant.now().toString(),
                )
            )
        } catch (e: Exception) {
            Result.failure(Exception("Failed to parse usage: ${e.message}"))
        }
    }

    private fun tierToDisplayName(tier: String): String {
        val lower = tier.lowercase()
        return when {
            "opus" in lower -> "Opus"
            "sonnet" in lower -> "Sonnet"
            "haiku" in lower -> "Haiku"
            else -> tier.replaceFirstChar { it.uppercase() }
        }
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

    /**
     * Quick check if the session is valid.
     */
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
        // Support both raw sessionKey value and full cookie string
        val cookieValue = if (sessionKey.contains("sessionKey=")) {
            sessionKey
        } else {
            "sessionKey=$sessionKey"
        }

        return Request.Builder()
            .url("$BASE_URL$path")
            .header("Cookie", cookieValue)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android) ClaudeUsageWidget/1.0")
            .header("Accept", "application/json")
            .get()
            .build()
    }
}
