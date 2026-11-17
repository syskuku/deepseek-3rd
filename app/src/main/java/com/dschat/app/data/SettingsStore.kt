package com.dschat.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * 本机设置：API Key（加密存储）、深度思考/智能搜索开关、主题。
 * 与官方 demo 的 localStorage 设置项对应；加密失败时降级为普通 SharedPreferences。
 */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = try {
        val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            "dschat_settings",
            masterKey,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (_: Throwable) {
        context.getSharedPreferences("dschat_settings_fallback", Context.MODE_PRIVATE)
    }

    var apiKey: String?
        get() = prefs.getString(KEY_API, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_API, value?.trim()).apply()

    var thinking: Boolean
        get() = prefs.getBoolean(KEY_THINKING, false)
        set(value) = prefs.edit().putBoolean(KEY_THINKING, value).apply()

    var search: Boolean
        get() = prefs.getBoolean(KEY_SEARCH, false)
        set(value) = prefs.edit().putBoolean(KEY_SEARCH, value).apply()

    /** light / dark / system */
    var theme: String
        get() = prefs.getString(KEY_THEME, "system") ?: "system"
        set(value) = prefs.edit().putString(KEY_THEME, value).apply()

    companion object {
        private const val KEY_API = "api_key"
        private const val KEY_THINKING = "thinking"
        private const val KEY_SEARCH = "search"
        private const val KEY_THEME = "theme"
    }
}