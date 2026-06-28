package com.utility.dashcam.network

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Helper for exchanging an OAuth 2.0 authorization code for access + refresh tokens.
 * Used by the in-app "Connect YouTube" flow after Google Sign-In returns an auth code.
 *
 * Flow:
 *   1. User clicks "Connect YouTube" in dashboard
 *   2. Google Sign-In opens browser, user picks account + grants YouTube scope
 *   3. Sign-In returns an authorization code
 *   4. This helper exchanges the code for tokens via POST to Google's token endpoint
 *   5. Caller stores the refresh_token in ConfigStore
 */
object YouTubeOAuthHelper {

    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val REDIRECT_URI = "urn:ietf:wg:oauth:2.0:oob"

    /**
     * Data class holding the tokens returned from the authorization code exchange.
     */
    data class OAuthTokens(
        val accessToken: String?,
        val refreshToken: String?,
        val expiresIn: Int?,
        val tokenType: String?,
        val scope: String?
    )

    /**
     * Exchanges an authorization code for access + refresh tokens.
     *
     * @param authorizationCode The code returned by Google Sign-In authorization flow.
     * @param clientId The OAuth 2.0 Client ID (Web application type from Google Cloud Console).
     * @param clientSecret The OAuth 2.0 Client Secret.
     * @return [Result.success] with [OAuthTokens] on success, [Result.failure] on any error.
     */
    fun exchangeCodeForTokens(
        authorizationCode: String,
        clientId: String,
        clientSecret: String
    ): Result<OAuthTokens> = runCatching {
        val client = OkHttpClient()
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", authorizationCode)
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("redirect_uri", REDIRECT_URI)
            .build()

        val request = Request.Builder()
            .url(TOKEN_URL)
            .post(body)
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string()

        if (!response.isSuccessful) {
            throw OAuthExchangeException(
                httpCode = response.code,
                message = "Token exchange failed (${response.code}): ${responseBody ?: "no body"}"
            )
        }

        if (responseBody == null) {
            throw OAuthExchangeException(httpCode = response.code, message = "Empty response from token endpoint")
        }

        val parsed = Gson().fromJson(responseBody, TokenExchangeResponse::class.java)
        if (parsed.refreshToken.isNullOrBlank()) {
            throw OAuthExchangeException(
                httpCode = response.code,
                message = "No refresh_token returned. Ensure the OAuth client is 'Web application' type and 'offline' access was requested."
            )
        }

        OAuthTokens(
            accessToken = parsed.accessToken,
            refreshToken = parsed.refreshToken,
            expiresIn = parsed.expiresIn,
            tokenType = parsed.tokenType,
            scope = parsed.scope
        )
    }

    /**
     * Fetches the user's email address using the access token.
     * Calls Google's UserInfo endpoint.
     */
    fun fetchUserEmail(accessToken: String): Result<String> = runCatching {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url("https://www.googleapis.com/oauth2/v2/userinfo")
            .header("Authorization", "Bearer $accessToken")
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("Empty response")

        if (!response.isSuccessful) {
            throw Exception("UserInfo request failed: ${response.code}")
        }

        val parsed = Gson().fromJson(body, UserInfoResponse::class.java)
        parsed.email ?: throw Exception("No email in response")
    }

    class OAuthExchangeException(val httpCode: Int, override val message: String) : Exception(message)

    private data class TokenExchangeResponse(
        @SerializedName("access_token") val accessToken: String?,
        @SerializedName("refresh_token") val refreshToken: String?,
        @SerializedName("expires_in") val expiresIn: Int?,
        @SerializedName("token_type") val tokenType: String?,
        @SerializedName("scope") val scope: String?
    )

    private data class UserInfoResponse(
        @SerializedName("email") val email: String?,
        @SerializedName("name") val name: String?,
        @SerializedName("picture") val picture: String?
    )
}
