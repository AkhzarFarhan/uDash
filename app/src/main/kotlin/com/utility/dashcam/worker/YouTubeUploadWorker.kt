package com.utility.dashcam.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.ForegroundInfo
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

/**
 * Foreground CoroutineWorker for uploading daily video merges to YouTube.
 * Constrained to run on unmetered Wi-Fi and when device is charging.
 */
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
        try {
            // Set to foreground to allow SSID reading and run long-running uploads
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (!isHomeWifiAndUnmetered()) return@withContext Result.retry()
        val pendingUploads = db.dailyMergeDao().getPendingUploads()
        if (pendingUploads.isEmpty()) return@withContext Result.success()
        val youtube = buildYouTubeClient()
        if (youtube == null) {
            config.setLastError(applicationContext, "YouTube Connection Error: Check dashboard OAuth credentials")
            return@withContext Result.retry()
        }
        
        pendingUploads.forEach { merge ->
            merge.localMergedPath?.let { filePath ->
                upload(youtube, merge.mergeId, merge.dateString, filePath)
            }
        }
        Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(applicationContext, "youtube_upload_channel")
            .setContentTitle("Uploading Dashcam Backup")
            .setContentText("Uploading daily videos to YouTube...")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(2002, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(2002, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "youtube_upload_channel",
                "YouTube Upload Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun isHomeWifiAndUnmetered(): Boolean {
        val connectivityManager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return false
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return false
        
        // Extract SSID from NetworkCapabilities (allowed in foreground state with location permissions)
        val wifiInfo = capabilities.transportInfo as? WifiInfo
        var currentSsid = wifiInfo?.ssid?.replace("\"", "")
        
        if (currentSsid == null || currentSsid == "<unknown ssid>" || currentSsid.isBlank()) {
            val legacyWifiInfo = wifiManager.connectionInfo
            currentSsid = legacyWifiInfo?.ssid?.replace("\"", "")
        }
        
        if (currentSsid == null || currentSsid == "<unknown ssid>" || currentSsid.isBlank()) return false
        val homeSsid = config.getHomeWifiSsid(applicationContext)
        if (homeSsid.isBlank()) return false
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
            .setClientSecrets(clientId, clientSecret)
            .build()
            .setRefreshToken(refreshToken)
            
        return YouTube.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential)
            .setApplicationName("uDash")
            .build()
    }

    private suspend fun upload(youtube: YouTube, mergeId: String, dateStr: String, filePath: String) {
        db.dailyMergeDao().updateUploadStatus(mergeId, UploadStatus.UPLOADING, null, System.currentTimeMillis())
        val video = Video().apply {
            status = VideoStatus().apply { privacyStatus = config.getYoutubePrivacy(applicationContext) }
            snippet = com.google.api.services.youtube.model.VideoSnippet().apply {
                title = "Dashcam Backup $mergeId"
                description = "Automated daily dashcam compilation for $dateStr (Part: $mergeId)"
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
                db.dailyMergeDao().updateUploadStatus(mergeId, UploadStatus.SUCCESS, response.id, System.currentTimeMillis())
                db.dailyMergeDao().clearLocalMergedPath(mergeId)
                file.delete()
                config.setLastError(applicationContext, null) // Clear error on success
            } else {
                db.dailyMergeDao().updateUploadStatus(mergeId, UploadStatus.FAILED, null, System.currentTimeMillis())
                config.setLastError(applicationContext, "Upload failed for $mergeId: empty video ID returned")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            db.dailyMergeDao().updateUploadStatus(mergeId, UploadStatus.FAILED, null, System.currentTimeMillis())
            config.setLastError(applicationContext, "Upload failed for $mergeId: ${e.message}")
        }
    }
}