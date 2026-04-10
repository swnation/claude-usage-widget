package com.claudeusage.widget

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 첫 실행 시 필요한 권한을 순서대로 안내.
 * 1. 알림 권한
 * 2. 배터리 최적화 제외
 * 3. 다른 앱 위에 표시 (오버레이)
 * 4. 앱 설치 권한
 */
class PermissionSetup(private val activity: AppCompatActivity) {

    fun checkAndRequest(onComplete: () -> Unit) {
        val missing = getMissingPermissions()
        if (missing.isEmpty()) {
            onComplete()
            return
        }

        showSetupDialog(missing, onComplete)
    }

    private fun getMissingPermissions(): List<PermissionItem> {
        val list = mutableListOf<PermissionItem>()

        // 1. 알림 권한
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            list.add(PermissionItem("알림 권한", "사용량 알림을 표시하기 위해 필요합니다") {
                ActivityCompat.requestPermissions(
                    activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            })
        }

        // 2. 배터리 최적화 제외
        val pm = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(activity.packageName)) {
            list.add(PermissionItem("배터리 최적화 제외", "백그라운드에서 안정적으로 작동하기 위해 필요합니다") {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${activity.packageName}")
                    }
                    activity.startActivity(intent)
                } catch (_: Exception) {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    activity.startActivity(intent)
                }
            })
        }

        // 3. 오버레이 권한
        if (!Settings.canDrawOverlays(activity)) {
            list.add(PermissionItem("다른 앱 위에 표시", "플로팅 오버레이를 사용하기 위해 필요합니다") {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                activity.startActivity(intent)
            })
        }

        // 4. 앱 설치 권한
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            list.add(PermissionItem("앱 설치 허용", "자동 업데이트를 위해 필요합니다") {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                activity.startActivity(intent)
            })
        }

        return list
    }

    private fun showSetupDialog(items: List<PermissionItem>, onComplete: () -> Unit) {
        val names = items.joinToString("\n") { "• ${it.name}: ${it.description}" }

        AlertDialog.Builder(activity)
            .setTitle("권한 설정 (${items.size}개)")
            .setMessage("앱이 정상 작동하려면 다음 권한이 필요합니다:\n\n$names")
            .setPositiveButton("설정하기") { _, _ ->
                requestSequentially(items, 0, onComplete)
            }
            .setNegativeButton("나중에") { _, _ ->
                onComplete()
            }
            .setCancelable(false)
            .show()
    }

    private fun requestSequentially(items: List<PermissionItem>, index: Int, onComplete: () -> Unit) {
        if (index >= items.size) {
            Toast.makeText(activity, "권한 설정 완료!", Toast.LENGTH_SHORT).show()
            onComplete()
            return
        }

        val item = items[index]
        AlertDialog.Builder(activity)
            .setTitle("${index + 1}/${items.size}: ${item.name}")
            .setMessage(item.description)
            .setPositiveButton("설정") { _, _ ->
                item.action()
                // 3초 후 다음 권한으로 (설정 화면에서 돌아올 시간)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    requestSequentially(items, index + 1, onComplete)
                }, 500)
            }
            .setNegativeButton("건너뛰기") { _, _ ->
                requestSequentially(items, index + 1, onComplete)
            }
            .show()
    }

    data class PermissionItem(
        val name: String,
        val description: String,
        val action: () -> Unit,
    )

    companion object {
        fun isFirstRun(context: Context): Boolean {
            val prefs = context.getSharedPreferences("setup", Context.MODE_PRIVATE)
            return !prefs.getBoolean("setup_done", false)
        }

        fun markSetupDone(context: Context) {
            context.getSharedPreferences("setup", Context.MODE_PRIVATE).edit()
                .putBoolean("setup_done", true).apply()
        }
    }
}
