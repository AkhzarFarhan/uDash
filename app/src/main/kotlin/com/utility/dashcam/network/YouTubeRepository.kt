package com.utility.dashcam.network

import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Repository for YouTube Data API v3 access.
 *
 * Architecture:
 * - Uses Retrofit for type-safe HTTP calls
 * - [TokenInterceptor] automatically attaches OAuth2 Bearer token
 * - [TokenAuthenticator] handles 401 by refreshing the token and retrying
 * - All public methods return [Result<T>] to prevent runtime crashes from network errors
 *
 * Usage:
 * ```
 * val repository = YouTubeRepository()
 * val result = repository.getChannelDetails()
 * result.onSuccess { snippet -> Log.d("YT", "Channel: ${snippet.title}") }
 * result.onFailure { error -> Log.e("YT", "Failed: ${error.message}") }
 * ```
 */
class YouTubeRepository {

    private val apiService: YouTubeApiService by lazy {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(TokenInterceptor)
            .addInterceptor(loggingInterceptor)
            .authenticator(TokenAuthenticator)
            .build()

        Retrofit.Builder()
            .baseUrl("https://www.googleapis.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(YouTubeApiService::class.java)
    }

    /**
     * Fetches the authenticated user's YouTube channel snippet (title, description, thumbnails, etc.).
     *
     * Calls: GET /youtube/v3/channels?part=snippet,contentDetails,statistics&mine=true
     *
     * @return [Result.success] with [ChannelSnippet] on success,
     *         [Result.failure] with the exception on any error (network, parsing, auth, etc.).
     */
    suspend fun getChannelDetails(): Result<ChannelSnippet> = runCatching {
        val response = apiService.getChannelDetails()

        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string() ?: "Unknown error"
            throw YouTubeApiException(
                httpCode = response.code(),
                message = "YouTube API error ${response.code()}: $errorBody"
            )
        }

        val responseBody = response.body()
            ?: throw YouTubeApiException(httpCode = response.code(), message = "Empty response body")

        val json = responseBody.string()
        val channelResponse = Gson().fromJson(json, YouTubeChannelResponse::class.java)

        channelResponse?.items?.firstOrNull()?.snippet
            ?: throw YouTubeApiException(
                httpCode = response.code(),
                message = "No channel data returned. Ensure the authenticated account has a YouTube channel."
            )
    }

    /**
     * Custom exception for YouTube API errors, carrying the HTTP status code.
     */
    class YouTubeApiException(
        val httpCode: Int,
        override val message: String
    ) : Exception(message)
}
