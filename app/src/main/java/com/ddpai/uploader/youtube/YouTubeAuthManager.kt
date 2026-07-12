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
        )
            .setScope(scope)
            // prompt=consent forces Google to (re)issue a refresh token; it is a first-class OAuth
            // param, so it MUST go through setPromptValues, not setAdditionalParameters (AppAuth rejects it there).
            .setPromptValues("consent")
            // access_type=offline (a Google extension) requests the refresh token; valid as an additional param.
            .setAdditionalParameters(mapOf("access_type" to "offline"))
            .build() // AppAuth adds PKCE automatically
        return authService.getAuthorizationRequestIntent(req)
    }

    suspend fun handleAuthResponse(data: Intent): Boolean = suspendCancellableCoroutine { cont ->
        val resp = AuthorizationResponse.fromIntent(data)
        if (resp == null) {
            cont.resume(false)
            return@suspendCancellableCoroutine
        }
        val tokenReq = resp.createTokenExchangeRequest()
        val secret = configRepo.config.value.youtubeClientSecret
        // Declared as the SAM interface type (NOT a function-typed val) so it can be passed to
        // both Java overloads of performTokenRequest — Kotlin SAM conversion only applies here.
        val onToken = AuthorizationService.TokenResponseCallback { tokenResp, tokenEx ->
            if (tokenResp != null) {
                val authState = AuthState(resp, tokenResp, tokenEx)
                configRepo.saveAuthState(authState.jsonSerializeString())
                configRepo.setNeedsReauth(false)
                cont.resume(true)
            } else {
                cont.resume(false)
            }
        }
        if (secret.isNotBlank()) {
            authService.performTokenRequest(tokenReq, ClientSecretPost(secret), onToken)
        } else {
            authService.performTokenRequest(tokenReq, onToken)
        }
    }

    suspend fun freshAccessToken(): String? = suspendCancellableCoroutine { cont ->
        val json = configRepo.loadAuthState() ?: run {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        val state = AuthState.jsonDeserialize(json)
        val secret = configRepo.config.value.youtubeClientSecret
        val action = net.openid.appauth.AuthState.AuthStateAction { token, _, ex ->
            if (token != null) {
                configRepo.saveAuthState(state.jsonSerializeString())
                configRepo.setNeedsReauth(false)   // a successful refresh means the session is fine
                cont.resume(token)
            } else {
                // Genuine auth failure — token revoked/invalid_grant (TYPE_OAUTH_TOKEN_ERROR) OR no
                // refresh token available (TYPE_OAUTH_AUTHORIZATION_ERROR, synthesized locally) — needs
                // re-auth. Transient network/IO/JSON failures are TYPE_GENERAL_ERROR and must NOT latch.
                if (ex != null && ex.type != net.openid.appauth.AuthorizationException.TYPE_GENERAL_ERROR) {
                    configRepo.setNeedsReauth(true)
                }
                cont.resume(null)
            }
        }
        if (secret.isNotBlank()) {
            state.performActionWithFreshTokens(
                authService,
                net.openid.appauth.ClientSecretPost(secret),
                action
            )
        } else {
            state.performActionWithFreshTokens(authService, action)
        }
    }

    fun isAuthorized(): Boolean =
        configRepo.loadAuthState()?.let { AuthState.jsonDeserialize(it).isAuthorized } ?: false

    fun signOut() {
        configRepo.clearAuthState()
    }
}
