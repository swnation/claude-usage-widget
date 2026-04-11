package com.claudeusage.widget

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Google Drive API 직접 호출 유틸리티.
 * 오랑붕쌤에서 얻은 OAuth 토큰으로 마스터 JSON 파일의 usage_data를 읽는다.
 */
object DriveApiClient {

    // 오랑붕쌤 도메인 정의 (folder → masterFile)
    val OBS_DOMAINS = listOf(
        "Orangi Migraine" to "orangi_master.json",
        "Orangi Mental" to "orangi_mental_master.json",
        "Orangi Health" to "orangi_health_master.json",
        "Bung Mental" to "bung_mental_master.json",
        "Bung Health" to "bung_health_master.json",
        "Bungruki Pregnancy" to "bungruki_master.json",
    )

    data class DriveCostResult(
        val costData: ApiCostData?,
        val error: String? = null,
        val tokenExpired: Boolean = false,
    )

    /**
     * Google Drive에서 모든 도메인의 usage_data를 읽어서 비용을 집계한다.
     * 네트워크 호출이므로 반드시 백그라운드 스레드에서 실행할 것.
     */
    fun fetchCostFromDrive(token: String): DriveCostResult {
        val kstNow = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Seoul"))
        val today = kstNow.toLocalDate().toString()
        val month = today.substring(0, 7)

        var todayTotal = 0.0
        var monthTotal = 0.0
        val byAI = mutableMapOf<String, DoubleArray>() // aiId → [today, month]
        var anySuccess = false

        for ((folderName, masterFileName) in OBS_DOMAINS) {
            try {
                // 1) 폴더 찾기
                val folderId = searchFolder(token, folderName) ?: continue

                // 2) 마스터 파일 찾기
                val fileId = searchFile(token, masterFileName, folderId) ?: continue

                // 3) 파일 내용 읽기
                val master = readFile(token, fileId) ?: continue

                // 4) usage_data 파싱
                val usageData = try { master.getAsJsonObject("usage_data") }
                    catch (_: Exception) { null } ?: continue

                anySuccess = true

                for (dateEntry in usageData.entrySet()) {
                    val date = dateEntry.key
                    val aiMap = try { dateEntry.value.asJsonObject } catch (_: Exception) { continue }

                    for (aiEntry in aiMap.entrySet()) {
                        val aiId = aiEntry.key
                        val info = try { aiEntry.value.asJsonObject } catch (_: Exception) { continue }
                        val cost = info.get("cost")?.asDouble ?: 0.0

                        if (!byAI.containsKey(aiId)) byAI[aiId] = doubleArrayOf(0.0, 0.0)
                        val arr = byAI[aiId]!!

                        if (date == today) {
                            todayTotal += cost
                            arr[0] += cost
                        }
                        if (date.startsWith(month)) {
                            monthTotal += cost
                            arr[1] += cost
                        }
                    }
                }
            } catch (e: Exception) {
                if (e.message?.contains("401") == true) {
                    return DriveCostResult(null, "토큰 만료", tokenExpired = true)
                }
                // 개별 도메인 실패는 무시하고 계속
            }
        }

        if (!anySuccess) {
            return DriveCostResult(null, "Drive에서 데이터를 찾을 수 없음")
        }

        val breakdowns = byAI.map { (aiId, costs) ->
            val aiDef = AiDefs.find(aiId)
            AiCostBreakdown(
                aiId = aiId,
                name = aiDef?.name ?: aiId,
                color = aiDef?.color ?: "#888888",
                todayCost = costs[0],
                monthCost = costs[1],
            )
        }.sortedByDescending { it.monthCost }

        val costData = ApiCostData(
            todayTotal = todayTotal,
            monthTotal = monthTotal,
            byAI = breakdowns,
            bySys = listOf(SystemCost("오랑붕쌤", todayTotal, monthTotal)),
            lastUpdated = java.time.Instant.now().toString(),
        )

        return DriveCostResult(costData)
    }

    private fun searchFolder(token: String, folderName: String): String? {
        val q = URLEncoder.encode(
            "name='$folderName' and mimeType='application/vnd.google-apps.folder' and trashed=false",
            "UTF-8"
        )
        val url = "https://www.googleapis.com/drive/v3/files?q=$q&fields=files(id)"
        val json = httpGet(url, token) ?: return null
        val files = json.getAsJsonArray("files")
        return if (files != null && files.size() > 0) files[0].asJsonObject.get("id").asString
        else null
    }

    private fun searchFile(token: String, fileName: String, parentId: String): String? {
        val q = URLEncoder.encode(
            "name='$fileName' and '$parentId' in parents and trashed=false",
            "UTF-8"
        )
        val url = "https://www.googleapis.com/drive/v3/files?q=$q&fields=files(id)"
        val json = httpGet(url, token) ?: return null
        val files = json.getAsJsonArray("files")
        return if (files != null && files.size() > 0) files[0].asJsonObject.get("id").asString
        else null
    }

    private fun readFile(token: String, fileId: String): JsonObject? {
        val url = "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"
        return httpGet(url, token)
    }

    private fun httpGet(urlStr: String, token: String): JsonObject? {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.connectTimeout = 15000
        conn.readTimeout = 15000

        return try {
            if (conn.responseCode == 401) {
                throw Exception("401 Unauthorized")
            }
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().readText()
            JsonParser.parseString(body).asJsonObject
        } finally {
            conn.disconnect()
        }
    }
}
