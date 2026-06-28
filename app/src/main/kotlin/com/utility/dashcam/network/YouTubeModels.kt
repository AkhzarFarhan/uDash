package com.utility.dashcam.network

import com.google.gson.annotations.SerializedName

/**
 * Gson data classes for the YouTube Data API v3 response.
 * All fields are nullable to prevent Gson deserialization crashes on missing/null JSON values.
 *
 * Endpoint: GET /youtube/v3/channels?part=snippet,contentDetails,statistics&mine=true
 */

data class YouTubeChannelResponse(
    @SerializedName("kind") val kind: String?,
    @SerializedName("etag") val etag: String?,
    @SerializedName("pageInfo") val pageInfo: PageInfo?,
    @SerializedName("items") val items: List<ChannelItem>?
)

data class PageInfo(
    @SerializedName("totalResults") val totalResults: Int?,
    @SerializedName("resultsPerPage") val resultsPerPage: Int?
)

data class ChannelItem(
    @SerializedName("kind") val kind: String?,
    @SerializedName("etag") val etag: String?,
    @SerializedName("id") val id: String?,
    @SerializedName("snippet") val snippet: ChannelSnippet?,
    @SerializedName("contentDetails") val contentDetails: ChannelContentDetails?,
    @SerializedName("statistics") val statistics: ChannelStatistics?
)

data class ChannelSnippet(
    @SerializedName("title") val title: String?,
    @SerializedName("description") val description: String?,
    @SerializedName("customUrl") val customUrl: String?,
    @SerializedName("publishedAt") val publishedAt: String?,
    @SerializedName("thumbnails") val thumbnails: Thumbnails?,
    @SerializedName("defaultLanguage") val defaultLanguage: String?,
    @SerializedName("country") val country: String?
)

data class Thumbnails(
    @SerializedName("default") val default: ThumbnailInfo?,
    @SerializedName("medium") val medium: ThumbnailInfo?,
    @SerializedName("high") val high: ThumbnailInfo?
)

data class ThumbnailInfo(
    @SerializedName("url") val url: String?,
    @SerializedName("width") val width: Int?,
    @SerializedName("height") val height: Int?
)

data class ChannelContentDetails(
    @SerializedName("relatedPlaylists") val relatedPlaylists: RelatedPlaylists?
)

data class RelatedPlaylists(
    @SerializedName("likes") val likes: String?,
    @SerializedName("uploads") val uploads: String?
)

data class ChannelStatistics(
    @SerializedName("viewCount") val viewCount: String?,
    @SerializedName("subscriberCount") val subscriberCount: String?,
    @SerializedName("hiddenSubscriberCount") val hiddenSubscriberCount: Boolean?,
    @SerializedName("videoCount") val videoCount: String?
)
