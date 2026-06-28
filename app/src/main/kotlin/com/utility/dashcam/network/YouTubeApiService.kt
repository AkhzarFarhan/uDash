package com.utility.dashcam.network

import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.Response

/**
 * Retrofit service interface for the YouTube Data API v3.
 *
 * Base URL: https://www.googleapis.com/
 * Authentication: Handled automatically by [TokenInterceptor] + [TokenAuthenticator].
 *
 * All methods return raw [ResponseBody] to allow flexible error handling
 * in [YouTubeRepository] with Result<T> wrapping.
 */
interface YouTubeApiService {

    /**
     * Retrieves the authenticated user's YouTube channel details.
     *
     * API: GET /youtube/v3/channels
     * Required scope: https://www.googleapis.com/auth/youtube.readonly
     *
     * @param part Comma-separated list of resource parts to include.
     * @param mine Set to true to retrieve the authenticated user's channel.
     * @return Raw response body containing the JSON channel resource.
     */
    @GET("youtube/v3/channels")
    suspend fun getChannelDetails(
        @Query("part") part: String = "snippet,contentDetails,statistics",
        @Query("mine") mine: Boolean = true
    ): Response<ResponseBody>
}
