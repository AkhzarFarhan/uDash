package com.utility.dashcam.util

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Provides configurable network endpoints and constants.
 */
object NetworkConfig {
    private const val PREFS_NAME = "network_config_prefs"
    private const val KEY_DASHCAM_IP = "dashcam_ip"
    private const val DEFAULT_DASHCAM_IP = "193.168.0.1"
    
    const val DASHCAM_SSID_PREFIX = "Dashcam_AP" // Example prefix
    const val HOME_WIFI_SSID = "MyHomeWiFi" // Should be user-configured

    fun getDashcamIp(context: Context): String {
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sharedPrefs.getString(KEY_DASHCAM_IP, DEFAULT_DASHCAM_IP) ?: DEFAULT_DASHCAM_IP
    }

    fun setDashcamIp(context: Context, ip: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DASHCAM_IP, ip)
            .apply()
    }
    
    fun getDashcamManifestUrl(context: Context): String {
        return "http://${getDashcamIp(context)}/vcam/cmd.cgi?cmd=APP_PlaybackListReq"
    }
}
