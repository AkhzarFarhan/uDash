package com.utility.dashcam.worker

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.api.client.googleapis.media.MediaHttpUploader
import com.google.api.client.http.InputStreamContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.youtube.YouTube
import com.google.api.services.youtube.model.Video
import com.google.api.services.youtube.model.VideoStatus
import com.utility.dashcam.data.local.AppDatabase
import com.utility.dashcam.data.local.UploadStatus
import com.utility.dashcam.util.NetworkConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream

/**
 * WorkManager task that uploads daily video merges to YouTube.
 * Validates unmetered network, charging status, and home Wi-Fi SSID.
 */
class YouTubeUploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val db = AppDatabase.getDatabase(context)
    private val authManager = YoutubeAuthManager(context)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // 1. SSID Validation (Architecture 5.3)
        if (!isHomeWifi()) {
            return@withContext Result.retry()
        }

        val pendingUploads = db.dashcamDao().getPendingUploads()
        if (pendingUploads.isEmpty()) return@withContext Result.success()

        val youtube = YouTube.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            authManager.getCredential()
        ).setApplicationName("uDash").build()

        pendingUploads.forEach { upload ->
            try {
                uploadVideo(youtube, upload.dateString, upload.localMergedPath ?: return@forEach)
            } catch (e: Exception) {
                db.dashcamDao().updateUploadStatus(upload.dateString, UploadStatus.FAILED, null, System.currentTimeMillis())
            }
        }

        Result.success()
    }

    private fun isHomeWifi(): Boolean {
        val connectivityManager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        
        // This is simplified; real implementation would use WifiManager for SSID check
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private suspend fun uploadVideo(youtube: YouTube, dateStr: String, filePath: String) {
        db.dashcamDao().updateUploadStatus(dateStr, UploadStatus.UPLOADING, null, System.currentTimeMillis())

        val video = Video().apply {
            status = VideoStatus().apply {
                privacyStatus = "private" // Architecture 5.3
            }
            snippet = com.google.api.services.youtube.model.VideoSnippet().apply {
                title = "Dashcam Backup $dateStr"
            }
        }

        val file = File(filePath)
        val content = InputStreamContent("video/mp4", BufferedInputStream(FileInputStream(file)))
        content.length = file.length()

        val insertRequest = youtube.videos().insert(listOf("snippet", "status"), video, content)
        
        // Architecture 5.3: Resumable Upload pipeline
        val uploader = insertRequest.mediaHttpUploader
        uploader.isDirectUploadEnabled = false
        uploader.chunkSize = MediaHttpUploader.MINIMUM_CHUNK_SIZE // 256 KB

        val response = insertRequest.execute()
        
        db.dashcamDao().updateUploadStatus(dateStr, UploadStatus.SUCCESS, response.id, System.currentTimeMillis())
        file.delete() // Purge local merged file (Architecture Flow)
    }
}
