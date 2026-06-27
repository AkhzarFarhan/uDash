package com.utility.dashcam.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.utility.dashcam.R
import com.utility.dashcam.data.local.AppDatabase
import com.utility.dashcam.data.local.DownloadStatus
import com.utility.dashcam.data.local.MergeStatus
import com.utility.dashcam.data.local.RawClipEntity
import com.utility.dashcam.data.local.UploadStatus
import com.utility.dashcam.util.ConfigStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DashcamIngestionService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var wifiManager: WifiManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val db by lazy { AppDatabase.getDatabase(this) }
    private val CHANNEL_ID = "dashcam_ingestion_channel"
    private val isIngesting = AtomicBoolean(false)
    private var dashcamOkHttpClient: OkHttpClient? = null

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        wifiManager = getSystemService(Context.WIFI_SERVICE) as WifiManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        setupDashcamNetwork()
    }

    private fun isConnectedToDashcamAp(): Boolean {
        val info = wifiManager.connectionInfo
        val ssid = info.ssid?.replace("\"", "") ?: return false
        if ("<unknown ssid>" == ssid) return false
        val prefix = ConfigStore.getDashcamSsidPrefix(this)
        if (prefix.isBlank()) return false
        return ssid.startsWith(prefix, ignoreCase = true)
    }

    private fun setupDashcamNetwork() {
        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!isConnectedToDashcamAp()) return
                if (!isIngesting.compareAndSet(false, true)) return
                // Reuse the OkHttpClient bound to the dashcam network
                val client = dashcamOkHttpClient ?: OkHttpClient.Builder()
                    .socketFactory(network.socketFactory)
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build().also { dashcamOkHttpClient = it }
                serviceScope.launch {
                    try {
                        executeCameraIngestion(client)
                    } finally {
                        isIngesting.set(false)
                    }
                }
            }
            override fun onLost(network: Network) { }
        }
        this.networkCallback = callback
        connectivityManager.requestNetwork(networkRequest, callback)
    }

    private suspend fun executeCameraIngestion(client: OkHttpClient) {
        try {
            val manifestUrl = "http://${ConfigStore.getDashcamIp(this)}/vcam/cmd.cgi?cmd=APP_PlaybackListReq"
            val request = Request.Builder().url(manifestUrl).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return
            val manifestText = response.body?.string() ?: return
            val clips = parseManifest(manifestText)
            db.rawClipDao().insertRawClips(clips)
            // Download only the clips that are PENDING (newly inserted or previously failed)
            val pending = db.rawClipDao().getRawClipsByStatus(DownloadStatus.PENDING)
            pending.forEach { clip -> downloadClip(client, clip) }
            checkAndTriggerMerge()
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun parseManifest(text: String): List<RawClipEntity> {
        val pattern = """(\d{8}_\d{6}_F\.mp4)""".toRegex()
        return pattern.findAll(text).map { matchResult ->
            val fileName = matchResult.groupValues[1]
            val dateStr = "${fileName.substring(0,4)}-${fileName.substring(4,6)}-${fileName.substring(6,8)}"
            val remoteUrl = "http://${ConfigStore.getDashcamIp(this)}/sd/normal/$fileName"
            RawClipEntity(fileName, dateStr, remoteUrl, null, 0L, DownloadStatus.PENDING)
        }.toList()
    }

    private suspend fun downloadClip(client: OkHttpClient, clip: RawClipEntity) {
        db.rawClipDao().updateDownloadStatus(clip.fileName, DownloadStatus.DOWNLOADING, null)
        val request = Request.Builder().url(clip.remoteUrl).build()
        try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body ?: throw IllegalStateException("Response body is null")
                val file = File(cacheDir, clip.fileName)
                // OkHttp returns -1 for unknown content length; only check storage if known
                val contentLength = body.contentLength()
                if (contentLength > 0) {
                    val requiredSpace = (contentLength * 1.5).toLong()
                    if (cacheDir.usableSpace < requiredSpace) {
                        throw IllegalStateException("Storage Exhaustion")
                    }
                }
                body.byteStream().use { input ->
                    FileOutputStream(file).use { output -> input.copyTo(output) }
                }
                // Verify file was actually written (guard against zero-byte writes)
                if (file.length() > 0L) {
                    db.rawClipDao().markDownloadCompleted(clip.fileName, file.absolutePath)
                } else {
                    file.delete()
                    db.rawClipDao().updateDownloadStatus(clip.fileName, DownloadStatus.FAILED, null)
                }
            } else {
                db.rawClipDao().updateDownloadStatus(clip.fileName, DownloadStatus.FAILED, null)
            }
        } catch (e: Exception) {
            db.rawClipDao().updateDownloadStatus(clip.fileName, DownloadStatus.FAILED, null)
        }
    }

    private suspend fun checkAndTriggerMerge() {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val today = sdf.format(java.util.Date())
        val completedDates = db.rawClipDao().getDistinctCompletedDates()
        for (dateStr in completedDates) {
            if (dateStr == today) continue // §6: block current date
            val nonCompleted = db.rawClipDao().countNonCompletedByDate(dateStr)
            if (nonCompleted > 0) continue
            val merge = db.dailyMergeDao().getDailyMerge(dateStr)
            val needsMerge = merge == null || merge.mergeStatus == MergeStatus.PENDING || merge.mergeStatus == MergeStatus.FAILED
            if (!needsMerge) continue
            val clips = db.rawClipDao().getRawClipsByDate(dateStr).first()
            if (clips.isEmpty()) continue
            if (merge == null) {
                db.dailyMergeDao().insertDailyMerge(
                    com.utility.dashcam.data.local.DailyMergeEntity(
                        dateString = dateStr, localMergedPath = null, totalSize = 0L,
                        mergeStatus = MergeStatus.PROCESSING, uploadStatus = UploadStatus.IDLE,
                        youtubeVideoId = null, lastAttemptTimestamp = 0L
                    )
                )
            } else {
                db.dailyMergeDao().updateMergeStatus(dateStr, MergeStatus.PROCESSING)
            }
            serviceScope.launch {
                FfmpegOrchestrator(this@DashcamIngestionService, db).processDailyMerge(dateStr, clips)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, getString(R.string.ingestion_service_channel_name), NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.ingestion_service_notification_title))
            .setContentText(getString(R.string.ingestion_service_notification_text))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        super.onDestroy()
        try { networkCallback?.let { connectivityManager.unregisterNetworkCallback(it) } } catch (e: Exception) {}
        serviceScope.cancel()
    }
    companion object { private const val NOTIFICATION_ID = 1001 }
}