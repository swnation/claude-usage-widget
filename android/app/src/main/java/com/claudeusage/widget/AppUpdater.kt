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
            try {
                val url = URL("https://api.github.com/repos/$GITHUB_REPO/releases/latest")
                val conn = url.openConnection() as HttpURLConnection
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                if (conn.responseCode != 200) return@execute

                val body = conn.inputStream.bufferedReader().readText()
                val json = Gson().fromJson(body, JsonObject::class.java)

                val tagName = json.get("tag_name")?.asString ?: return@execute
                val latestVersion = tagName.removePrefix("v")
                val releaseNotes = json.get("body")?.asString ?: ""
                val currentVersion = getCurrentVersion()

                var apkUrl: String? = null
                json.getAsJsonArray("assets")?.forEach { asset ->
                    val assetObj = asset.asJsonObject
                    val name = assetObj.get("name")?.asString ?: ""
                    if (name.endsWith(".apk")) {
                        apkUrl = assetObj.get("browser_download_url")?.asString
                    }
                }

                if (apkUrl == null || !isNewerVersion(latestVersion, currentVersion)) return@execute

                val downloadUrl = apkUrl!!
                activity.runOnUiThread {
                    showUpdateDialog(latestVersion, releaseNotes, downloadUrl)
                }
            } catch (_: Exception) {}
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
