package com.ddpai.uploader.network

import android.net.Network
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object BoundHttpClientFactory {
    /** OkHttp client whose sockets are forced onto the given (dashcam) network. */
    fun forNetwork(network: Network, callTimeoutSec: Long = 600): OkHttpClient =
        OkHttpClient.Builder()
            .socketFactory(network.socketFactory)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(callTimeoutSec, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

    /** Plain client for YouTube (uses default/internet network). */
    fun default(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
}
