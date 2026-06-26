package com.utility.dashcam.worker

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.services.youtube.YouTubeScopes

/**
 * Manages OAuth2 credentials using EncryptedSharedPreferences for secure storage.
 */
class YoutubeAuthManager(private val context: Context) {

    private val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

    private val sharedPreferences = EncryptedSharedPreferences.create(
        "youtube_auth_prefs",
        masterKeyAlias,
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getCredential(accountName: String? = null): GoogleAccountCredential {
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            listOf(YouTubeScopes.YOUTUBE_UPLOAD)
        )
        credential.selectedAccountName = accountName ?: sharedPreferences.getString("account_name", null)
        return credential
    }

    fun saveAccountName(accountName: String) {
        sharedPreferences.edit().putString("account_name", accountName).apply()
    }
}
