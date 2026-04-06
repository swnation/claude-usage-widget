package com.claudeusage.widget

import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class UsageApiClient(private var serverUrl: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    fun updateServerUrl(url: String) {
        serverUrl = url.trimEnd('/')
    }

    fun fetchUsage(): Result<UsageData> {
        return try {
            val request = Request.Builder()
                .url("$serverUrl/api/usage")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return Result.failure(Exception("Empty response"))
                    val data = gson.fromJson(body, UsageData::class.java)
                    Result.success(data)
                } else {
                    Result.failure(Exception("HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun checkHealth(): Boolean {
        return try {
            val request = Request.Builder()
                .url("$serverUrl/api/health")
                .get()
                .build()

            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }
}
