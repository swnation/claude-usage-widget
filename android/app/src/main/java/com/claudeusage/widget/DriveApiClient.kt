package com.claudeusage.widget

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Google Drive API 직접 호출 유틸리티.
 * Google Drive OAuth 토큰으로 Admin 키 백업/복원 등을 수행한다.
 */
object DriveApiClient {

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
     * 암호화된 Admin 키 + 부가 설정을 Drive에 저장한다.
     * @param encryptedData PIN으로 암호화된 키 데이터 (없으면 빈 문자열, 설정만 백업 가능)
     * @param extraConfig Gemini/Grok 등 부가 설정 JSON
     */
    fun saveKeysToDrive(token: String, encryptedData: String, extraConfig: JsonObject? = null): Boolean {
        return try {
            val folderId = getOrCreateFolder(token, KEYS_FOLDER)

            // 기존 내용 보존 (설정만 업데이트할 때 키를 날리지 않도록)
            val existingId = searchFile(token, KEYS_FILE, folderId)
            val existingEncrypted = if (encryptedData.isEmpty() && existingId != null) {
                readFile(token, existingId)?.get("encrypted")?.asString ?: ""
            } else encryptedData

            val root = JsonObject().apply {
                addProperty("encrypted", existingEncrypted)
                addProperty("updatedAt", java.time.Instant.now().toString())
                if (extraConfig != null) add("config", extraConfig)
            }
            val content = Gson().toJson(root)

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

    data class DriveKeyData(
        val encrypted: String?,
        val config: JsonObject? = null,
    )

    /**
     * Drive에서 암호화된 Admin 키 + 부가 설정을 불러온다.
     */
    fun loadKeysAndConfigFromDrive(token: String): DriveKeyData? {
        return try {
            val folderId = searchFolder(token, KEYS_FOLDER) ?: return null
            val fileId = searchFile(token, KEYS_FILE, folderId) ?: return null
            val json = readFile(token, fileId) ?: return null
            DriveKeyData(
                encrypted = json.get("encrypted")?.asString,
                config = json.getAsJsonObject("config"),
            )
        } catch (_: Exception) { null }
    }

    /** 하위 호환: 암호화 키만 반환 */
    fun loadKeysFromDrive(token: String): String? {
        return loadKeysAndConfigFromDrive(token)?.encrypted
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
