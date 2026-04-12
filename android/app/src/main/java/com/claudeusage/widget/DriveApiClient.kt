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
        val kstNow = java.time.ZonedDateTime.now(java.time.ZoneId.systemDefault())
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
                estimatedToday = costs[0],
                estimatedMonth = costs[1],
            )
        }.sortedByDescending { it.monthCost }

        val costData = ApiCostData(
            todayTotal = todayTotal,
            monthTotal = monthTotal,
            source = CostSource.ESTIMATED,
            estimatedToday = todayTotal,
            estimatedMonth = monthTotal,
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
            val code = conn.responseCode
            if (code == 401) throw Exception("401 Unauthorized")
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.readText() ?: ""
            if (code !in 200..299) return null
            JsonParser.parseString(body).asJsonObject
        } finally {
            conn.disconnect()
        }
    }

    // ── Admin 키 Drive 백업/복원 ──
    private const val KEYS_FOLDER = "Claude Usage Widget"
    private const val KEYS_FILE = "admin_keys_backup.json"

    /**
     * 암호화된 Admin 키를 Drive에 저장한다.
     * @param encryptedData PIN으로 암호화된 키 데이터 (Base64 문자열)
     */
    fun saveKeysToDrive(token: String, encryptedData: String): Boolean {
        return try {
            val folderId = getOrCreateFolder(token, KEYS_FOLDER)
            val content = """{"encrypted":"$encryptedData","updatedAt":"${java.time.Instant.now()}"}"""

            // 기존 파일 있으면 업데이트, 없으면 생성
            val existingId = searchFile(token, KEYS_FILE, folderId)
            if (existingId != null) {
                httpPatch(
                    "https://www.googleapis.com/upload/drive/v3/files/$existingId?uploadType=media",
                    token, content
                )
            } else {
                httpPostMultipart(token, KEYS_FILE, folderId, content)
            }
            true
        } catch (_: Exception) { false }
    }

    /**
     * Drive에서 암호화된 Admin 키를 불러온다.
     * @return 암호화된 키 데이터 (Base64) 또는 null
     */
    fun loadKeysFromDrive(token: String): String? {
        return try {
            val folderId = searchFolder(token, KEYS_FOLDER) ?: return null
            val fileId = searchFile(token, KEYS_FILE, folderId) ?: return null
            val json = readFile(token, fileId) ?: return null
            json.get("encrypted")?.asString
        } catch (_: Exception) { null }
    }

    private fun getOrCreateFolder(token: String, name: String): String {
        val existing = searchFolder(token, name)
        if (existing != null) return existing

        val meta = """{"name":"$name","mimeType":"application/vnd.google-apps.folder"}"""
        val conn = URL("https://www.googleapis.com/drive/v3/files?fields=id").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.outputStream.write(meta.toByteArray())
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.readText() ?: ""
        conn.disconnect()
        if (conn.responseCode !in 200..299) throw Exception("Folder create failed: ${conn.responseCode}")
        return JsonParser.parseString(body).asJsonObject.get("id").asString
    }

    private fun httpPatch(urlStr: String, token: String, content: String) {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "PATCH"
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.outputStream.write(content.toByteArray())
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        stream?.bufferedReader()?.readText()
        conn.disconnect()
        if (code !in 200..299) throw Exception("Drive PATCH failed: $code")
    }

    private fun httpPostMultipart(token: String, fileName: String, parentId: String, content: String) {
        val boundary = "widget_bound_x7"
        val meta = """{"name":"$fileName","parents":["$parentId"],"mimeType":"application/json"}"""
        val body = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n--$boundary\r\nContent-Type: application/json\r\n\r\n$content\r\n--$boundary--"

        val conn = URL("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id")
            .openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
        conn.doOutput = true
        conn.outputStream.write(body.toByteArray())
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        stream?.bufferedReader()?.readText()
        conn.disconnect()
        if (code !in 200..299) throw Exception("Drive POST failed: $code")
    }
}
