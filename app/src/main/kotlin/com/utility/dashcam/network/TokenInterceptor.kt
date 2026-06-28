package com.utility.dashcam.network

import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * OkHttp Interceptor that attaches the OAuth 2.0 Bearer token to every outgoing request.
 * Skips the token-refresh endpoint itself to avoid circular dependencies.
 */
object TokenInterceptor : Interceptor {

    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // Do not attach Authorization header to the token refresh endpoint
        if (originalRequest.url.toString() == TOKEN_URL) {
            return chain.proceed(originalRequest)
        }

        val accessToken = TokenManager.getAccessToken()
            ?: return chain.proceed(originalRequest) // Proceed without auth; server will return 401

        val authenticatedRequest = originalRequest.newBuilder()
            .header("Authorization", "Bearer $accessToken")
            .build()

        return chain.proceed(authenticatedRequest)
    }
}

/**
 * OkHttp Authenticator that handles HTTP 401 (Unauthorized) responses
 * by refreshing the OAuth 2.0 access token and retrying the request.
 *
 * Prevents infinite retry loops by checking if the request already carried
 * an Authorization header that was tried with the current token.
 */
object TokenAuthenticator : Authenticator {

    private const val MAX_RETRY = 1

    override fun authenticate(route: Route?, response: Response): Request? {
        // Prevent infinite retry loops: only retry once per request
        val retryCount = response.request.tag(RetryCountTag::class.java)?.count ?: 0
        if (retryCount >= MAX_RETRY) {
            return null // Give up — caller will receive the 401
        }

        // Only handle 401 Unauthorized
        if (response.code != 401) {
            return null
        }

        // Synchronize token refresh to prevent thundering herd
        synchronized(this) {
            TokenManager.invalidateToken()
            val newToken = TokenManager.refreshAccessToken() ?: return null

            return response.request.newBuilder()
                .header("Authorization", "Bearer $newToken")
                .tag(RetryCountTag(retryCount + 1))
                .build()
        }
    }
}

/**
 * Simple tag class to track retry attempts on a per-request basis.
 */
data class RetryCountTag(val count: Int)
