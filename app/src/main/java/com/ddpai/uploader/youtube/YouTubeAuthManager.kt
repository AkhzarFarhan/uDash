package com.ddpai.uploader.youtube

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.ddpai.uploader.data.config.ConfigRepository
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.*
import kotlin.coroutines.resume

class YouTubeAuthManager(
    private val context: Context,
    private val configRepo: ConfigRepository
) {
    private val authService = AuthorizationService(context)
    private val serviceConfig = AuthorizationServiceConfiguration(
        Uri.parse("https://accounts.google.com/o/oauth2/v2/auth"),
        Uri.parse("https://oauth2.googleapis.com/token")
    )
    private val redirectUri = Uri.parse("com.ddpai.uploader:/oauth2redirect")
    private val scope = "https://www.googleapis.com/auth/youtube.upload"

    fun buildAuthIntent(): Intent {
        val clientId = configRepo.config.value.youtubeClientId
        val req = AuthorizationRequest.Builder(
            serviceConfig, clientId, ResponseTypeValues.CODE, redirectUri
        ).setScope(scope).build() // AppAuth adds PKCE automatically
        return authService.getAuthorizationRequestIntent(req)
    }

    suspend fun handleAuthResponse(data: Intent): Boolean = suspendCancellableCoroutine { cont ->
        val resp = AuthorizationResponse.fromIntent(data)
        if (resp == null) {
            cont.resume(false)
            return@suspendCancellableCoroutine
        }
        val tokenReq = resp.createTokenExchangeRequest()
        authService.performTokenRequest(tokenReq) { tokenResp, tokenEx ->
            if (tokenResp != null) {
                val authState = AuthState(resp, tokenResp, tokenEx)
                configRepo.saveAuthState(authState.jsonSerializeString())
                cont.resume(true)
            } else {
                cont.resume(false)
            }
        }
    }

    suspend fun freshAccessToken(): String? = suspendCancellableCoroutine { cont ->
        val json = configRepo.loadAuthState() ?: run {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        val state = AuthState.jsonDeserialize(json)
        state.performActionWithFreshTokens(authService) { token, _, _ ->
            if (token != null) {
                configRepo.saveAuthState(state.jsonSerializeString())
                cont.resume(token)
            } else {
                cont.resume(null)
            }
        }
    }

    fun isAuthorized(): Boolean =
        configRepo.loadAuthState()?.let { AuthState.jsonDeserialize(it).isAuthorized } ?: false

    fun signOut() {
        configRepo.clearAuthState()
    }
}
