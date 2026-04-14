package com.claudeusage.widget

import android.content.Context
import java.io.File

/**
 * 로컬에 저장된 스킨 관리.
 * 저장 경로: filesDir/skins/{id}/
 *   - skin.json (cskin JSON)
 *   - bg.png (앱 배경, 선택)
 *   - overlay_bg.png (오버레이 배경, 선택)
 */
object SkinManager {

    data class SavedSkin(
        val id: String,
        val name: String,
        val dirPath: String,
        val hasAppBg: Boolean,
        val hasOverlayBg: Boolean,
    )

    private fun skinsDir(context: Context): File {
        val dir = File(context.filesDir, "skins")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 현재 로드된 스킨을 로컬에 저장 */
    fun saveCurrent(context: Context): String? {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val json = prefs.getString("custom_skin_json", null) ?: return null
        val skinData = CustomSkinData.fromJson(json) ?: return null

        val id = skinData.name.replace(Regex("[^a-zA-Z0-9가-힣_-]"), "_")
            .take(30) + "_" + System.currentTimeMillis().toString().takeLast(6)
        val dir = File(skinsDir(context), id)
        dir.mkdirs()

        // JSON 저장
        File(dir, "skin.json").writeText(json)

        // 앱 배경 복사
        val bgPath = prefs.getString("cskin_bg_path", null)
        if (bgPath != null) {
            val src = File(bgPath)
            if (src.exists()) src.copyTo(File(dir, "bg.png"), overwrite = true)
        }

        // 오버레이 배경 복사
        val overlayBgPath = prefs.getString("cskin_overlay_bg_path", null)
        if (overlayBgPath != null) {
            val src = File(overlayBgPath)
            if (src.exists()) src.copyTo(File(dir, "overlay_bg.png"), overwrite = true)
        }

        return id
    }

    /** 저장된 스킨 목록 */
    fun listSaved(context: Context): List<SavedSkin> {
        val dir = skinsDir(context)
        if (!dir.exists()) return emptyList()

        return dir.listFiles()?.filter { it.isDirectory }?.mapNotNull { skinDir ->
            val jsonFile = File(skinDir, "skin.json")
            if (!jsonFile.exists()) return@mapNotNull null
            val json = jsonFile.readText()
            val skinData = CustomSkinData.fromJson(json) ?: return@mapNotNull null
            SavedSkin(
                id = skinDir.name,
                name = skinData.name,
                dirPath = skinDir.absolutePath,
                hasAppBg = File(skinDir, "bg.png").exists(),
                hasOverlayBg = File(skinDir, "overlay_bg.png").exists(),
            )
        }?.sortedByDescending { it.id } ?: emptyList()
    }

    /** 저장된 스킨 로드 → SharedPreferences에 적용 */
    fun load(context: Context, savedSkin: SavedSkin) {
        val dir = File(savedSkin.dirPath)
        val json = File(dir, "skin.json").readText()
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val editor = prefs.edit()
            .putString("skin", "custom-file")
            .putString("custom_skin_json", json)

        // 배경 이미지 복사 → 활성 위치로
        val bgSrc = File(dir, "bg.png")
        if (bgSrc.exists()) {
            val dest = File(context.filesDir, "cskin_bg.png")
            bgSrc.copyTo(dest, overwrite = true)
            editor.putString("cskin_bg_path", dest.absolutePath)
        } else {
            editor.remove("cskin_bg_path")
        }

        val overlayBgSrc = File(dir, "overlay_bg.png")
        if (overlayBgSrc.exists()) {
            val dest = File(context.filesDir, "cskin_overlay_bg.png")
            overlayBgSrc.copyTo(dest, overwrite = true)
            editor.putString("cskin_overlay_bg_path", dest.absolutePath)
        } else {
            editor.remove("cskin_overlay_bg_path")
        }

        editor.apply()
    }

    /** 저장된 스킨 삭제 */
    fun delete(context: Context, savedSkin: SavedSkin) {
        val dir = File(savedSkin.dirPath)
        dir.deleteRecursively()
    }
}
