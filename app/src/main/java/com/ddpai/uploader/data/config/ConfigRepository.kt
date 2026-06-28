package com.ddpai.uploader.data.config

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ConfigRepository(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val securePrefs = EncryptedSharedPreferences.create(
        context,
        "secure_config",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val _config = MutableStateFlow(load())
    val config: StateFlow<AppConfig> = _config.asStateFlow()

    fun save(config: AppConfig) {
        securePrefs.edit()
            .putString("clientId", config.youtubeClientId)
            .putString("clientSecret", config.youtubeClientSecret)
            .putString("privacy", config.uploadPrivacy)
            .putBoolean("deleteAfterUpload", config.deleteAfterUpload)
            .putBoolean("wifiAutoStart", config.wifiAutoStart)
            .putString("gateway", config.dashcamGateway)
            .putInt("maxRetries", config.maxRetries)
            .apply()
        _config.value = config
    }

    private fun load(): AppConfig = AppConfig(
        youtubeClientId = securePrefs.getString("clientId", "") ?: "",
        youtubeClientSecret = securePrefs.getString("clientSecret", "") ?: "",
        uploadPrivacy = securePrefs.getString("privacy", "private") ?: "private",
        deleteAfterUpload = securePrefs.getBoolean("deleteAfterUpload", true),
        wifiAutoStart = securePrefs.getBoolean("wifiAutoStart", true),
        dashcamGateway = securePrefs.getString("gateway", "193.168.0.1") ?: "193.168.0.1",
        maxRetries = securePrefs.getInt("maxRetries", 5)
    )

    fun isConfigured(): Boolean = _config.value.youtubeClientId.isNotBlank()

    // OAuth token persistence (AppAuth AuthState serialized JSON)
    fun saveAuthState(json: String) {
        securePrefs.edit().putString("authState", json).apply()
    }

    fun loadAuthState(): String? = securePrefs.getString("authState", null)

    fun clearAuthState() {
        securePrefs.edit().remove("authState").apply()
    }
}
