package com.claudeusage.widget

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class AppUpdater(private val activity: Activity) {

    companion object {
        private const val TAG = "AppUpdater"
        const val GITHUB_REPO = "swnation/claude-usage-widget"
    }

    private val executor = Executors.newSingleThreadExecutor()

    /** 앱의 실제 versionName을 읽음 (하드코딩 X) */
    private fun getCurrentVersion(): String {
        return try {
            activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "0.0.0"
        } catch (_: Exception) { "0.0.0" }
    }

    fun checkForUpdate() {
        executor.execute {
            var conn: HttpURLConnection? = null
            try {
                val url = URL("https://api.github.com/repos/$GITHUB_REPO/releases/latest")
                conn = url.openConnection() as HttpURLConnection
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "claude-usage-widget-android")
                conn.connectTimeout = 15000
                conn.readTimeout = 15000

                val responseCode = conn.responseCode
                if (responseCode != 200) {
                    val errorBody = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
                    Log.e(TAG, "GitHub API 응답 $responseCode: ${errorBody?.take(200)}")
                    if (responseCode == 403) {
                        activity.runOnUiThread {
                            Toast.makeText(activity, "업데이트 확인 실패: API 제한 (잠시 후 재시도)", Toast.LENGTH_SHORT).show()
                        }
                    }
                    return@execute
                }

                val body = conn.inputStream.bufferedReader().readText()
                val json = try {
                    Gson().fromJson(body, JsonObject::class.java)
                } catch (e: Exception) {
                    Log.e(TAG, "JSON 파싱 실패: ${e.message}")
                    activity.runOnUiThread {
                        Toast.makeText(activity, "업데이트 확인 실패: 응답 파싱 오류", Toast.LENGTH_SHORT).show()
                    }
                    return@execute
                }

                val tagName = json.get("tag_name")?.asString
                if (tagName == null) {
                    Log.e(TAG, "tag_name 없음")
                    return@execute
                }
                val latestVersion = tagName.removePrefix("v")
                val releaseNotes = json.get("body")?.asString ?: ""
                val currentVersion = getCurrentVersion()

                Log.d(TAG, "현재=$currentVersion, 최신=$latestVersion")

                if (!isNewerVersion(latestVersion, currentVersion)) {
                    Log.d(TAG, "최신 버전 사용 중")
                    return@execute
                }

                var apkUrl: String? = null
                val assets = json.getAsJsonArray("assets")
                if (assets == null || assets.size() == 0) {
                    Log.e(TAG, "릴리즈에 assets 없음 (빌드 진행 중?)")
                    activity.runOnUiThread {
                        Toast.makeText(activity, "새 버전 v$latestVersion 감지 (빌드 중, 잠시 후 재시도)", Toast.LENGTH_SHORT).show()
                    }
                    return@execute
                }

                assets.forEach { asset ->
                    val assetObj = asset.asJsonObject
                    val name = assetObj.get("name")?.asString ?: ""
                    if (name.endsWith(".apk")) {
                        apkUrl = assetObj.get("browser_download_url")?.asString
                    }
                }

                if (apkUrl == null) {
                    Log.e(TAG, "APK 파일을 찾을 수 없음. assets: ${assets.size()}개")
                    activity.runOnUiThread {
                        Toast.makeText(activity, "업데이트 실패: APK 파일 없음", Toast.LENGTH_SHORT).show()
                    }
                    return@execute
                }

                val downloadUrl = apkUrl!!
                activity.runOnUiThread {
                    showUpdateDialog(latestVersion, releaseNotes, downloadUrl)
                }
            } catch (e: java.net.UnknownHostException) {
                Log.e(TAG, "네트워크 연결 실패: ${e.message}")
            } catch (e: java.net.SocketTimeoutException) {
                Log.e(TAG, "연결 타임아웃: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "업데이트 확인 실패: ${e.message}", e)
                activity.runOnUiThread {
                    Toast.makeText(activity, "업데이트 확인 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun isNewerVersion(latest: String, current: String): Boolean {
        val l = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val c = current.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(l.size, c.size)) {
            val lv = l.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (lv > cv) return true
            if (lv < cv) return false
        }
        return false
    }

    private fun showUpdateDialog(version: String, notes: String, downloadUrl: String) {
        // 설치 권한 없으면 먼저 요청
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()) {
            requestInstallPermission(downloadUrl, version, notes)
            return
        }

        AlertDialog.Builder(activity)
            .setTitle("새 버전: v$version")
            .setMessage(notes.ifEmpty { "새 버전이 있습니다." })
            .setPositiveButton("업데이트") { _, _ -> downloadAndInstall(downloadUrl) }
            .setNegativeButton("나중에", null)
            .show()
    }

    private fun requestInstallPermission(downloadUrl: String, version: String, notes: String) {
        AlertDialog.Builder(activity)
            .setTitle("설치 권한 필요")
            .setMessage("앱 업데이트를 위해 '출처를 알 수 없는 앱 설치' 권한이 필요합니다.")
            .setPositiveButton("설정으로 이동") { _, _ ->
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                activity.startActivity(intent)
            }
            .setNegativeButton("나중에", null)
            .show()
    }

    private fun downloadAndInstall(downloadUrl: String) {
        Toast.makeText(activity, "다운로드 중...", Toast.LENGTH_SHORT).show()

        val file = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "claude-usage-widget.apk"
        )
        if (file.exists()) file.delete()

        val request = DownloadManager.Request(Uri.parse(downloadUrl))
            .setTitle("Claude 사용량 업데이트")
            .setDescription("새 버전을 다운로드하고 있습니다")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "claude-usage-widget.apk")

        val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = dm.enqueue(request)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    activity.unregisterReceiver(this)
                    installApk(file)
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED)
        } else {
            activity.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }

    private fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        activity.startActivity(intent)
    }
}
