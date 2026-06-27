package com.utility.dashcam.worker

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import com.google.api.client.http.InputStreamContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.youtube.YouTube
import com.google.api.services.youtube.model.Video
import com.google.api.services.youtube.model.VideoStatus
import com.utility.dashcam.data.local.AppDatabase
import com.utility.dashcam.data.local.UploadStatus
import com.utility.dashcam.util.ConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream

class YouTubeUploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val db = AppDatabase.getDatabase(context)
    private val config = ConfigStore

    companion object {
        const val WORK_NAME = "YouTubeUploadWorker"
        private const val CHUNK_SIZE = 256 * 1024 // 256 KB = MediaHttpUploader.MINIMUM_CHUNK_SIZE

        fun enqueueUpload(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresCharging(true)
                .build()
            val request = OneTimeWorkRequest.Builder(YouTubeUploadWorker::class.java)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, androidx.work.ExistingWorkPolicy.REPLACE, request)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!isHomeWifiAndUnmetered()) return@withContext Result.retry()
        val pendingUploads = db.dailyMergeDao().getPendingUploads()
        if (pendingUploads.isEmpty()) return@withContext Result.success()
        val youtube = buildYouTubeClient() ?: return@withContext Result.retry()
        pendingUploads.forEach { merge ->
            merge.localMergedPath?.let { filePath ->
                upload(youtube, merge.dateString, filePath)
            }
        }
        Result.success()
    }

    private fun isHomeWifiAndUnmetered(): Boolean {
        val connectivityManager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return false
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return false
        val wifiInfo = wifiManager.connectionInfo
        val currentSsid = wifiInfo.ssid?.replace("\"", "") ?: return false
        val homeSsid = config.getHomeWifiSsid(applicationContext)
        return currentSsid == homeSsid
    }

    private fun buildYouTubeClient(): YouTube? {
        val clientId = config.getOAuthClientId(applicationContext)
        val clientSecret = config.getOAuthClientSecret(applicationContext)
        val refreshToken = config.getOAuthRefreshToken(applicationContext)
        if (clientId.isNullOrBlank() || clientSecret.isNullOrBlank() || refreshToken.isNullOrBlank()) return null
        val credential = GoogleCredential.Builder()
            .setTransport(NetHttpTransport())
            .setJsonFactory(GsonFactory.getDefaultInstance())
            .setClientSecrets(clientId!!, clientSecret!!)
            .build()
            .setRefreshToken(refreshToken!!)
        return YouTube.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential)
            .setApplicationName("uDash")
            .build()
    }

    private suspend fun upload(youtube: YouTube, dateStr: String, filePath: String) {
        db.dailyMergeDao().updateUploadStatus(dateStr, UploadStatus.UPLOADING, null, System.currentTimeMillis())
        val video = Video().apply {
            status = VideoStatus().apply { privacyStatus = config.getYoutubePrivacy(applicationContext) }
            snippet = com.google.api.services.youtube.model.VideoSnippet().apply {
                title = "Dashcam Backup $dateStr"
                description = "Automated daily dashcam compilation for $dateStr"
            }
        }
        val file = File(filePath)
        val content = InputStreamContent("video/mp4", BufferedInputStream(FileInputStream(file))).apply { length = file.length() }
        val insertRequest = youtube.videos().insert(listOf("snippet", "status"), video, content)
        val uploader = insertRequest.mediaHttpUploader
        uploader.isDirectUploadEnabled = false
        uploader.chunkSize = CHUNK_SIZE
        try {
            val response = insertRequest.execute()
            if (response.id != null) {
                db.dailyMergeDao().updateUploadStatus(dateStr, UploadStatus.SUCCESS, response.id, System.currentTimeMillis())
                db.dailyMergeDao().clearLocalMergedPath(dateStr)
                file.delete()
            } else {
                db.dailyMergeDao().updateUploadStatus(dateStr, UploadStatus.FAILED, null, System.currentTimeMillis())
            }
        } catch (e: Exception) {
            e.printStackTrace()
            db.dailyMergeDao().updateUploadStatus(dateStr, UploadStatus.FAILED, null, System.currentTimeMillis())
        }
    }
}