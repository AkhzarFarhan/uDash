package com.ddpai.uploader.dashcam

import com.ddpai.uploader.data.model.DashcamFile
import okhttp3.OkHttpClient
import okhttp3.Request

interface DashcamProtocol {
    suspend fun handshake(client: OkHttpClient, base: String): Boolean
    suspend fun listFiles(client: OkHttpClient, base: String): List<DashcamFile>
    fun buildDownloadRequest(base: String, fileName: String, rangeHeader: String?): Request
}
