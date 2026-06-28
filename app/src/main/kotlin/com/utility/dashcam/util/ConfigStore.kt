package com.utility.dashcam.util

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * EncryptedSharedPreferences-backed configuration store.
 * All dashboard-configurable values are stored here encrypted at rest.
 * Keys mirror the architecture spec: dashcam IP, SSIDs, YouTube OAuth, privacy, etc.
 */
object ConfigStore {

    private const val PREFS_NAME = "udash_config_encrypted"
    private const val MASTER_KEY_ALIAS = "udash_master_key"

    // Keys for all configurable values
    private const val KEY_DASHCAM_IP = "dashcam_ip"
    private const val KEY_DASHCAM_SSID_PREFIX = "dashcam_ssid_prefix"
    private const val KEY_HOME_WIFI_SSID = "home_wifi_ssid"
    private const val KEY_YOUTUBE_PRIVACY = "youtube_privacy"
    private const val KEY_OAUTH_CLIENT_ID = "oauth_client_id"
    private const val KEY_OAUTH_CLIENT_SECRET = "oauth_client_secret"
    private const val KEY_OAUTH_REFRESH_TOKEN = "oauth_refresh_token"
    private const val KEY_OAUTH_ACCOUNT_NAME = "oauth_account_name"
    private const val KEY_OAUTH_ACCESS_TOKEN = "oauth_access_token"
    private const val KEY_INGESTION_ENABLED = "ingestion_enabled"

    // Defaults per Architecture spec
    private const val DEFAULT_DASHCAM_IP = "193.168.0.1"
    private const val DEFAULT_DASHCAM_SSID_PREFIX = "Dashcam_AP"
    private const val DEFAULT_HOME_WIFI_SSID = "MyHomeWiFi"
    private const val DEFAULT_YOUTUBE_PRIVACY = "private" // "private" or "unlisted"
    private const val DEFAULT_INGESTION_ENABLED = false

    @Volatile
    private var prefs: android.content.SharedPreferences? = null

    @Synchronized
    private fun init(context: Context): android.content.SharedPreferences {
        if (prefs == null) {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            prefs = EncryptedSharedPreferences.create(
                PREFS_NAME,
                masterKeyAlias,
                context.applicationContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
        return prefs!!
    }

    // --- Dashcam Network Config ---

    fun getDashcamIp(context: Context): String {
        return init(context).getString(KEY_DASHCAM_IP, DEFAULT_DASHCAM_IP) ?: DEFAULT_DASHCAM_IP
    }

    fun setDashcamIp(context: Context, ip: String) {
        init(context).edit().putString(KEY_DASHCAM_IP, ip).apply()
    }

    fun getDashcamSsidPrefix(context: Context): String {
        return init(context).getString(KEY_DASHCAM_SSID_PREFIX, DEFAULT_DASHCAM_SSID_PREFIX) ?: DEFAULT_DASHCAM_SSID_PREFIX
    }

    fun setDashcamSsidPrefix(context: Context, prefix: String) {
        init(context).edit().putString(KEY_DASHCAM_SSID_PREFIX, prefix).apply()
    }

    fun getHomeWifiSsid(context: Context): String {
        return init(context).getString(KEY_HOME_WIFI_SSID, DEFAULT_HOME_WIFI_SSID) ?: DEFAULT_HOME_WIFI_SSID
    }

    fun setHomeWifiSsid(context: Context, ssid: String) {
        init(context).edit().putString(KEY_HOME_WIFI_SSID, ssid).apply()
    }

    fun getDashcamManifestUrl(context: Context): String {
        return "http://${getDashcamIp(context)}/vcam/cmd.cgi?cmd=APP_PlaybackListReq"
    }

    // --- YouTube Upload Config ---

    fun getYoutubePrivacy(context: Context): String {
        val v = init(context).getString(KEY_YOUTUBE_PRIVACY, DEFAULT_YOUTUBE_PRIVACY) ?: DEFAULT_YOUTUBE_PRIVACY
        return if (v == "private" || v == "unlisted" || v == "public") v else DEFAULT_YOUTUBE_PRIVACY
    }

    fun setYoutubePrivacy(context: Context, privacy: String) {
        init(context).edit().putString(KEY_YOUTUBE_PRIVACY, privacy).apply()
    }

    // --- OAuth2 Refresh-token credentials (headless background upload) ---
    // Architecture §5.3: "OAuth2 tokens drawn safely from an Android EncryptedSharedPreferences container"
    // We store client_id, client_secret, refresh_token, and account_name for token rotation.

    fun getOAuthClientId(context: Context): String? {
        return init(context).getString(KEY_OAUTH_CLIENT_ID, null)
    }

    fun setOAuthClientId(context: Context, clientId: String) {
        init(context).edit().putString(KEY_OAUTH_CLIENT_ID, clientId).apply()
    }

    fun getOAuthClientSecret(context: Context): String? {
        return init(context).getString(KEY_OAUTH_CLIENT_SECRET, null)
    }

    fun setOAuthClientSecret(context: Context, secret: String) {
        init(context).edit().putString(KEY_OAUTH_CLIENT_SECRET, secret).apply()
    }

    fun getOAuthRefreshToken(context: Context): String? {
        return init(context).getString(KEY_OAUTH_REFRESH_TOKEN, null)
    }

    fun setOAuthRefreshToken(context: Context, token: String) {
        init(context).edit().putString(KEY_OAUTH_REFRESH_TOKEN, token).apply()
    }

    fun getOAuthAccountName(context: Context): String? {
        return init(context).getString(KEY_OAUTH_ACCOUNT_NAME, null)
    }

    fun setOAuthAccountName(context: Context, name: String) {
        init(context).edit().putString(KEY_OAUTH_ACCOUNT_NAME, name).apply()
    }

    fun getOAuthAccessToken(context: Context): String? {
        return init(context).getString(KEY_OAUTH_ACCESS_TOKEN, null)
    }

    fun setOAuthAccessToken(context: Context, token: String) {
        init(context).edit().putString(KEY_OAUTH_ACCESS_TOKEN, token).apply()
    }

    /**
     * Pre-initialize ConfigStore with application context.
     * Call once at startup so TokenManager can resolve credentials without passing Context.
     */
    fun initWith(context: Context) {
        init(context.applicationContext)
    }

    // --- Service Control ---

    fun isIngestionEnabled(context: Context): Boolean {
        return init(context).getBoolean(KEY_INGESTION_ENABLED, DEFAULT_INGESTION_ENABLED)
    }

    fun setIngestionEnabled(context: Context, enabled: Boolean) {
        init(context).edit().putBoolean(KEY_INGESTION_ENABLED, enabled).apply()
    }

    // --- Error Logging ---
    private const val KEY_LAST_ERROR = "last_error"

    fun getLastError(context: Context): String? {
        return init(context).getString(KEY_LAST_ERROR, null)
    }

    fun setLastError(context: Context, error: String?) {
        init(context).edit().putString(KEY_LAST_ERROR, error).apply()
    }

    fun registerListener(context: Context, listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {
        init(context).registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(context: Context, listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {
        init(context).unregisterOnSharedPreferenceChangeListener(listener)
    }
}