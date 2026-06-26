package com.utility.dashcam.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.utility.dashcam.R
import com.utility.dashcam.data.local.AppDatabase
import com.utility.dashcam.data.local.DownloadStatus
import com.utility.dashcam.data.local.RawClipEntity
import com.utility.dashcam.util.NetworkConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Foreground service that ingests raw video payloads from the dashcam AP.
 * Implements isolated network routing via ConnectivityManager socket binding.
 */
class DashcamIngestionService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var connectivityManager: ConnectivityManager
    private val db by lazy { AppDatabase.getDatabase(this) }

    private lateinit var orchestrator: FfmpegOrchestrator

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        orchestrator = FfmpegOrchestrator(this, db)
        startForeground(NOTIFICATION_ID, createNotification())
        setupDashcamNetwork()
    }

    private fun setupDashcamNetwork() {
        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        connectivityManager.requestNetwork(networkRequest, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Bind the app network stack explicitly to this Wi-Fi interface
                val boundSocketFactory = network.socketFactory
                val okHttpClient = OkHttpClient.Builder()
                    .socketFactory(boundSocketFactory)
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()

                serviceScope.launch {
                    executeCameraIngestion(okHttpClient)
                }
            }
        })
    }

    private suspend fun executeCameraIngestion(client: OkHttpClient) {
        try {
            // 1. Fetch Manifest
            val manifestUrl = NetworkConfig.getDashcamManifestUrl(this)
            val request = Request.Builder().url(manifestUrl).build()
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) return
            
            val manifestText = response.body?.string() ?: return
            val clips = parseManifest(manifestText)
            
            // 2. Diff and Insert
            db.dashcamDao().insertRawClips(clips)
            
            // 3. Download Pending
            val pending = db.dashcamDao().getRawClipsByStatus(DownloadStatus.PENDING)
            pending.forEach { clip ->
                downloadClip(client, clip)
            }
            
            // 4. Trigger Merge Check (Logic from architecture 5.2)
            checkAndTriggerMerge()
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun parseManifest(text: String): List<RawClipEntity> {
        // Mock implementation: Real logic depends on actual dashcam response format
        // Architecture 3.1: remoteUrl example: "http://193.168.0.1/sd/normal/..."
        return text.lineSequence()
            .filter { it.contains(".mp4") }
            .map { line ->
                val fileName = line.substringAfterLast("/")
                val dateString = fileName.substring(0, 4) + "-" + fileName.substring(4, 6) + "-" + fileName.substring(6, 8)
                RawClipEntity(
                    fileName = fileName,
                    dateString = dateString,
                    remoteUrl = line,
                    localFilePath = null,
                    fileSize = 0, // Should be parsed from manifest if available
                    downloadStatus = DownloadStatus.PENDING
                )
            }.toList()
    }

    private suspend fun downloadClip(client: OkHttpClient, clip: RawClipEntity) {
        db.dashcamDao().updateDownloadStatus(clip.fileName, DownloadStatus.DOWNLOADING, null)
        
        val request = Request.Builder().url(clip.remoteUrl).build()
        try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val file = File(cacheDir, clip.fileName)
                
                // Storage Exhaustion Guard (Architecture 6)
                val contentLength = response.body?.contentLength() ?: 0L
                if (cacheDir.usableSpace < (contentLength * 1.5).toLong()) {
                    throw Exception("Storage Exhaustion")
                }

                response.body?.byteStream()?.use { input ->
                    FileOutputStream(file).use { output ->
                        input.copyTo(output)
                    }
                }
                db.dashcamDao().updateDownloadStatus(clip.fileName, DownloadStatus.COMPLETED, file.absolutePath)
            } else {
                db.dashcamDao().updateDownloadStatus(clip.fileName, DownloadStatus.FAILED, null)
            }
        } catch (e: Exception) {
            db.dashcamDao().updateDownloadStatus(clip.fileName, DownloadStatus.FAILED, null)
        }
    }

    private suspend fun checkAndTriggerMerge() {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val today = sdf.format(java.util.Date())

        // Fetch all distinct dates that have clips
        // For simplicity, we'll check the last 7 days
        val calendar = java.util.Calendar.getInstance()
        for (i in 1..7) {
            calendar.add(java.util.Calendar.DAY_OF_YEAR, -1)
            val dateStr = sdf.format(calendar.time)

            val merge = db.dashcamDao().getDailyMerge(dateStr)
            if (merge == null || merge.mergeStatus == "PENDING" || merge.mergeStatus == "FAILED") {
                val clips = db.dashcamDao().getRawClipsByDate(dateStr).first()
                if (clips.isNotEmpty() && clips.all { it.downloadStatus == DownloadStatus.COMPLETED }) {
                    if (merge == null) {
                        db.dashcamDao().insertDailyMerge(com.utility.dashcam.data.local.DailyMergeEntity(
                            dateString = dateStr,
                            localMergedPath = null,
                            totalSize = 0,
                            mergeStatus = com.utility.dashcam.data.local.MergeStatus.PROCESSING,
                            uploadStatus = com.utility.dashcam.data.local.UploadStatus.IDLE,
                            youtubeVideoId = null,
                            lastAttemptTimestamp = 0
                        ))
                    }
                    orchestrator.processDailyMerge(dateStr, clips)
                }
            }
        }
    }

    private fun createNotification(): Notification {
        val channelId = getString(R.string.ingestion_service_channel_id)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                getString(R.string.ingestion_service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.ingestion_service_notification_title))
            .setContentText(getString(R.string.ingestion_service_notification_text))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
    }
}
