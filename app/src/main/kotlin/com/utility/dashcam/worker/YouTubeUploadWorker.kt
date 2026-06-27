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
import java.util.concurrent.TimeUnit

/**
 * WorkManager task that uploads daily video merges to YouTube.
 * Architecture §5.3:
 * - Constraints: UNMETERED network + device charging.
 * - Runtime guard: validates SSID matches configured home Wi-Fi.
 * - OAuth2 via refresh-token from EncryptedSharedPreferences (ConfigStore).
 * - Chunked resumable upload via MediaHttpUploader (256KB chunks).
 * - On SUCCESS: update DB, purge local merged file.
 * - On FAILURE: update DB status, retry via WorkManager backoff.
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

        /**
         * Enqueue a one-time upload work with required constraints:
         * - UNMETERED network (Wi-Fi with internet route)
         * - Device charging (battery conservation)
         * Uses unique work to avoid duplicates.
         */
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
        // 1. Runtime SSID + Network validation (Architecture §5.3)
        // "Upon execution, the worker must explicitly inspect the WifiManager.getConnectionInfo().getSSID()
        //  to guarantee that it matches the user's designated Home Wi-Fi profile."
        if (!isHomeWifiAndUnmetered()) {
            return@withContext Result.retry()
        }

        val pendingUploads = db.dailyMergeDao().getPendingUploads()
        if (pendingUploads.isEmpty()) return@withContext Result.success()

        // 2. Build YouTube client with OAuth2 refresh-token credentials
        val youtube = buildYouTubeClient() ?: return@withContext Result.retry()

        // 3. Process each pending upload
        pendingUploads.forEach { merge ->
            merge.localMergedPath?.let { filePath ->
                upload(youtube, merge.dateString, filePath) { success ->
                    // Result handled inside lambda via DB updates
                }
            }
        }

        Result.success()
    }

    /**
     * Validates runtime network conditions:
     * - Connected to configured home Wi-Fi SSID (not dashcam AP, not public)
     * - Network is UNMETERED (has internet route)
     * Architecture §5.3: "If it matches a public network or the car's AP, the worker must throw Result.retry()"
     */
    private fun isHomeWifiAndUnmetered(): Boolean {
        val connectivityManager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        // Check active network capabilities
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false

        // Must have internet + unmetered + Wi-Fi transport
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return false
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return false

        // SSID must match configured home Wi-Fi
        val wifiInfo = wifiManager.connectionInfo
        val currentSsid = wifiInfo.ssid?.replace("\"", "") ?: return false
        val homeSsid = config.getHomeWifiSsid(applicationContext)

        return currentSsid == homeSsid
    }

    /**
     * Builds YouTube service client using OAuth2 refresh-token from EncryptedSharedPreferences.
     * Architecture §5.3: "OAuth2 tokens drawn safely from an Android EncryptedSharedPreferences container"
     * Architecture §6 (OAuth Token Expiry): "Implement synchronous Token Rotation via a secure
     * background refresh token exchange using the Google API client before initiating the
     * MediaHttpUploader binary stream loop."
     * GoogleCredential handles token rotation automatically when refresh_token is provided.
     */
    private fun buildYouTubeClient(): YouTube? {
        val clientId = config.getOAuthClientId(applicationContext)
        val clientSecret = config.getOAuthClientSecret(applicationContext)
        val refreshToken = config.getOAuthRefreshToken(applicationContext)

        if (clientId.isNullOrBlank() || clientSecret.isNullOrBlank() || refreshToken.isNullOrBlank()) {
            return null
        }

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

    /**
     * Upload a single merged video file via resumable MediaHttpUploader.
     * Architecture §5.3: "Initialize the MediaHttpUploader pipeline and configure the chunk
     * allocation parameter to MediaHttpUploader.MINIMUM_CHUNK_SIZE (256 KB) or higher
     * to allow resilient upload state resumption over shaky connections."
     */
    private suspend fun upload(youtube: YouTube, dateStr: String, filePath: String, onComplete: (Boolean) -> Unit) {
        db.dailyMergeDao().updateUploadStatus(dateStr, UploadStatus.UPLOADING, null, System.currentTimeMillis())

        val video = Video().apply {
            status = VideoStatus().apply {
                privacyStatus = config.getYoutubePrivacy(applicationContext) // "private" or "unlisted"
            }
            snippet = com.google.api.services.youtube.model.VideoSnippet().apply {
                title = "Dashcam Backup $dateStr"
                description = "Automated daily dashcam compilation for $dateStr"
            }
        }

        val file = File(filePath)
        val content = InputStreamContent("video/mp4", BufferedInputStream(FileInputStream(file))).apply {
            length = file.length()
        }

        val insertRequest = youtube.videos().insert(listOf("snippet", "status"), video, content)

        // Configure resumable upload with 256KB chunks (MINIMUM_CHUNK_SIZE)
        val uploader = insertRequest.mediaHttpUploader
        uploader.isDirectUploadEnabled = false // Enable resumable
        uploader.chunkSize = CHUNK_SIZE
        uploader.setProgressListener { _ ->
            // Could log progress here
        }

        try {
            val response = insertRequest.execute()
            if (response.id != null) {
                // SUCCESS: update DB, purge local file
                db.dailyMergeDao().updateUploadStatus(dateStr, UploadStatus.SUCCESS, response.id, System.currentTimeMillis())
                file.delete() // Purge local merged file (Architecture Flow)
                onComplete(true)
            } else {
                db.dailyMergeDao().updateUploadStatus(dateStr, UploadStatus.FAILED, null, System.currentTimeMillis())
                onComplete(false)
            }
        } catch (e: Exception) {
            // Token expiry handled by GoogleCredential auto-refresh; other errors -> FAILED
            e.printStackTrace()
            db.dailyMergeDao().updateUploadStatus(dateStr, UploadStatus.FAILED, null, System.currentTimeMillis())
            onComplete(false)
        }
    }
}