package com.utility.dashcam.network

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.utility.dashcam.BuildConfig
import com.utility.dashcam.util.ConfigStore
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Manages OAuth 2.0 token refresh using the offline refresh-token exchange strategy.
 *
 * Hybrid credential resolution (Path 3):
 *   1. ConfigStore (EncryptedSharedPreferences) -- populated by user via dashboard
 *   2. BuildConfig fields -- populated at build time via local.properties (developer fallback)
 *
 * Thread-safe: all refresh operations are synchronized to prevent thundering herd.
 */
object TokenManager {

    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"

    private var appContext: Context? = null

    @Volatile
    private var cachedAccessToken: String? = null

    @Volatile
    private var tokenExpiresAt: Long = 0L

    /**
     * Initialize with application context. Call once from MainActivity.onCreate().
     * Required for ConfigStore credential resolution.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Returns a valid access token, refreshing it if expired or missing.
     * Called by [TokenInterceptor] before each request.
     */
    fun getAccessToken(): String? {
        val now = System.currentTimeMillis()
        if (cachedAccessToken != null && now < tokenExpiresAt) {
            return cachedAccessToken
        }
        // Check ConfigStore (user may have connected via dashboard)
        val ctx = appContext
        if (ctx != null) {
            val storedToken = ConfigStore.getOAuthAccessToken(ctx)
            if (!storedToken.isNullOrBlank()) {
                cachedAccessToken = storedToken
                return storedToken
            }
        }
        return refreshAccessToken()
    }

    /**
     * Force-clears the cached token, typically after a 401 response.
     */
    fun invalidateToken() {
        cachedAccessToken = null
        tokenExpiresAt = 0L
        appContext?.let { ConfigStore.setOAuthAccessToken(it, "") }
    }

    /**
     * Performs the OAuth 2.0 refresh-token exchange.
     * Credential resolution order: ConfigStore first, BuildConfig fallback.
     */
    @Synchronized
    fun refreshAccessToken(): String? {
        // Double-check after acquiring lock
        val now = System.currentTimeMillis()
        if (cachedAccessToken != null && now < tokenExpiresAt) {
            return cachedAccessToken
        }

        // Resolve credentials: ConfigStore first, BuildConfig fallback
        val ctx = appContext
        val clientId = (if (ctx != null) ConfigStore.getOAuthClientId(ctx) else null)
            ?.takeUnless { it.isBlank() }
            ?: BuildConfig.YOUTUBE_CLIENT_ID
        val clientSecret = (if (ctx != null) ConfigStore.getOAuthClientSecret(ctx) else null)
            ?.takeUnless { it.isBlank() }
            ?: BuildConfig.YOUTUBE_CLIENT_SECRET
        val refreshToken = (if (ctx != null) ConfigStore.getOAuthRefreshToken(ctx) else null)
            ?.takeUnless { it.isBlank() }
            ?: BuildConfig.YOUTUBE_REFRESH_TOKEN

        if (clientId.isBlank() || clientSecret.isBlank() || refreshToken.isBlank()) {
            System.err.println("TokenManager: No YouTube OAuth credentials found")
            return null
        }

        val client = OkHttpClient()
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("refresh_token", refreshToken)
            .build()

        val request = Request.Builder()
            .url(TOKEN_URL)
            .post(body)
            .build()

        return try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                if (responseBody != null) {
                    val tokenResponse = Gson().fromJson(responseBody, TokenResponse::class.java)
                    cachedAccessToken = tokenResponse.accessToken
                    val expiresInMs = ((tokenResponse.expiresIn ?: 3600) - 60) * 1000L
                    tokenExpiresAt = System.currentTimeMillis() + expiresInMs
                    // Persist for cross-process availability
                    if (ctx != null && !cachedAccessToken.isNullOrBlank()) {
                        ConfigStore.setOAuthAccessToken(ctx, cachedAccessToken!!)
                    }
                    cachedAccessToken
                } else {
                    System.err.println("TokenManager: Empty response body from token endpoint")
                    null
                }
            } else {
                System.err.println("TokenManager: Token refresh failed with HTTP ${response.code}")
                null
            }
        } catch (e: Exception) {
            System.err.println("TokenManager: Token refresh exception: ${e.message}")
            null
        }
    }

    private data class TokenResponse(
        @SerializedName("access_token") val accessToken: String?,
        @SerializedName("expires_in") val expiresIn: Int?,
        @SerializedName("token_type") val tokenType: String?,
        @SerializedName("scope") val scope: String?
    )
}
