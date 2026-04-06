package com.claudeusage.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.preference.PreferenceManager

/** 부팅 시 자동으로 모니터링 서비스 재시작 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val autoStart = prefs.getBoolean("service_running", false)
            if (autoStart) {
                UsageMonitorService.start(context)
            }
        }
    }
}
