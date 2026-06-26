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
import androidx.core.app.NotificationManagerCompat
import com.utility.dashcam.R
import com.utility.dashcam.data.local.AppDatabase
import com.utility.dashcam.data.local.DownloadStatus
import com.utility.dashcam.data.local.RawClipEntity
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

/**
 * Foreground service that ingests raw video payloads from the dashcam AP.
 * Architecture §5.1:
 * - Runs as a foreground service with persistent notification (prevents OOM kill).
 * - Binds to the Wi-Fi transport layer via ConnectivityManager to isolate traffic
 *   to the dashcam gateway (193.168.0.1) without disrupting cellular routing.
 * - SSID guard: only activates when connected to the dashcam AP.
 * - Downloads missing clips via bound socket, persists state in Room.
 * - Triggers FFmpeg concat when a historical day's clips are all COMPLETED.
 */
class DashcamIngestionService : Service() {

    // Coroutine scope tied to service lifecycle; cancelled in onDestroy
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var wifiManager: WifiManager
    private lateinit var networkCallback: ConnectivityManager.NetworkCallback
    private val db by lazy { AppDatabase.getDatabase(this) }

    // Notification
    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "dashcam_ingestion_channel"

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        wifiManager = getSystemService(Context.WIFI_SERVICE) as WifiManager

        // Create notification channel BEFORE startForeground (Android 8+ requirement)
        createNotificationChannel()

        // Start foreground with a valid notification to avoid ANR / crash
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        // Initialize network callback for bound Wi-Fi socket
        setupDashcamNetwork()
    }

    /**
     * SSID guard (Architecture §5.1): only proceed if we're on the dashcam AP.
     * This avoids binding the socket factory to a random Wi-Fi network.
     */
    private fun isConnectedToDashcamAp(): Boolean {
        val info = wifiManager.connectionInfo
        val ssid = info.ssid?.replace("\"", "") ?: return false
        val prefix = ConfigStore.getDashcamSsidPrefix(this)
        return ssid.startsWith(prefix, ignoreCase = true)
    }

    /**
     * Registers a NetworkCallback that binds an OkHttpClient to the dashcam Wi-Fi interface.
     * Architecture §5.1: "Bind the app network stack explicitly to this Wi-Fi interface".
     */
    private fun setupDashcamNetwork() {
        // Only proceed if currently on dashcam AP; otherwise the service stays idle
        if (!isConnectedToDashcamAp()) {
            // Optionally stop self or wait for broadcast; for now just log
            return
        }

        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
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

            override fun onLost(network: Network) {
                // Network lost (e.g., car turned off) — ingestion will retry on next SSID match
            }
        }

        connectivityManager.requestNetwork(networkRequest, networkCallback)
    }

    /**
     * Core ingestion pipeline:
     * 1. Fetch camera file manifest via bound socket.
     * 2. Parse manifest → RawClipEntity list (YYYYMMDD_HHMMSS_F.mp4 format).
     * 3. Diff against Room DB; insert PENDING entries.
     * 4. Download each PENDING clip via streaming to cacheDir.
     *    - Storage exhaustion guard (Architecture §6): 1.5x contentLength check.
     *    - On IOException / mid-download disconnect → mark FAILED/PENDING for retry.
     * 5. After all clips for a closed date are COMPLETED, trigger merge check.
     */
    private suspend fun executeCameraIngestion(client: OkHttpClient) {
        try {
            // 1. Fetch Manifest
            val manifestUrl = "http://${ConfigStore.getDashcamIp(this)}/vcam/cmd.cgi?cmd=APP_PlaybackListReq"
            val request = Request.Builder().url(manifestUrl).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) return
            val manifestText = response.body?.string() ?: return

            // 2. Parse Manifest (DDPAI format: lines containing "YYYYMMDD_HHMMSS_F.mp4")
            val clips = parseManifest(manifestText)

            // 3. Diff & Insert
            db.rawClipDao().insertRawClips(clips)

            // 4. Download Pending Clips
            val pending = db.rawClipDao().getRawClipsByStatus(DownloadStatus.PENDING)
            pending.forEach { clip ->
                downloadClip(client, clip)
            }

            // 5. Trigger Merge Check for historical dates (not today)
            checkAndTriggerMerge()

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Parse DDPAI manifest text response.
     * Expected filename format: "YYYYMMDD_HHMMSS_F.mp4"
     * Returns list of RawClipEntity with dateString = "YYYY-MM-DD".
     */
    private fun parseManifest(text: String): List<RawClipEntity> {
        val pattern = """(\d{8}_\d{6}_F\.mp4)""".toRegex()
        return pattern.findAll(text).map { matchResult ->
            val fileName = matchResult.groupValues[1]
            val dateStr = "${fileName.substring(0,4)}-${fileName.substring(4,6)}-${fileName.substring(6,8)}"
            val remoteUrl = "http://${ConfigStore.getDashcamIp(this)}/sd/normal/$fileName"
            RawClipEntity(
                fileName = fileName,
                dateString = dateStr,
                remoteUrl = remoteUrl,
                localFilePath = null,
                fileSize = 0L, // Unknown from manifest; could be parsed if present
                downloadStatus = DownloadStatus.PENDING
            )
        }.toList()
    }

    /**
     * Streaming download to internal cache directory.
     * Architecture §5.1: "Download missing binary blocks using standard streaming chunks".
     * Architecture §6 (Storage Exhaustion): check usableSpace >= 1.5 * contentLength.
     */
    private suspend fun downloadClip(client: OkHttpClient, clip: RawClipEntity) {
        db.rawClipDao().updateDownloadStatus(clip.fileName, DownloadStatus.DOWNLOADING, null)

        val request = Request.Builder().url(clip.remoteUrl).build()
        try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val file = File(cacheDir, clip.fileName)

                // Storage Exhaustion Guard (Architecture §6)
                val contentLength = response.body?.contentLength() ?: 0L
                if (cacheDir.usableSpace < (contentLength * 1.5).toLong()) {
                    // High-priority system notification would go here; for now throw to mark FAILED
                    throw IllegalStateException("Storage Exhaustion: insufficient space for ${clip.fileName}")
                }

                response.body?.byteStream()?.use { input ->
                    FileOutputStream(file).use { output ->
                        input.copyTo(output)
                    }
                }
                db.rawClipDao().markDownloadCompleted(clip.fileName, file.absolutePath)
            } else {
                db.rawClipDao().updateDownloadStatus(clip.fileName, DownloadStatus.FAILED, null)
            }
        } catch (e: Exception) {
            // Mid-download disconnection (Architecture §6): persist FAILED/PENDING for retry
            db.rawClipDao().updateDownloadStatus(clip.fileName, DownloadStatus.FAILED, null)
        }
    }

    /**
     * Architecture §5.2 trigger strategy:
     * "Invoked immediately when the Ingestion Engine marks all identified records
     *  for a closed historical date string as COMPLETED."
     *
     * Architecture §6 (Current-Date Intersection):
     * "The ingestion script evaluates and blocks the compilation of files matching
     *  the exact current system date (T0). It only compiles a date partition if the
     *  date has closed, or after a clean camera disconnection broadcast is caught."
     *
     * Implementation: iterate last 7 days, skip today; for each date where all clips
     * are COMPLETED and merge is PENDING/FAILED/MISSING, create DailyMergeEntity with
     * PROCESSING and hand off to FfmpegOrchestrator.
     */
    private suspend fun checkAndTriggerMerge() {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val today = sdf.format(java.util.Date())

        // Check last 7 days (could be extended / made dynamic)
        val cal = java.util.Calendar.getInstance()
        for (i in 1..7) {
            cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
            val dateStr = sdf.format(cal.time)

            val merge = db.dailyMergeDao().getDailyMerge(dateStr)
            val needsMerge = merge == null ||
                merge.mergeStatus == "PENDING" ||
                merge.mergeStatus == "FAILED"

            if (needsMerge) {
                val clips = db.rawClipDao().getRawClipsByDate(dateStr).first()
                if (clips.isNotEmpty() && clips.all { it.downloadStatus == DownloadStatus.COMPLETED }) {
                    // Create or update merge entity to PROCESSING
                    val mergeEntity = merge ?: com.utility.dashcam.data.local.DailyMergeEntity(
                        dateString = dateStr,
                        localMergedPath = null,
                        totalSize = 0L,
                        mergeStatus = com.utility.dashcam.data.local.MergeStatus.PROCESSING,
                        uploadStatus = com.utility.dashcam.data.local.UploadStatus.IDLE,
                        youtubeVideoId = null,
                        lastAttemptTimestamp = 0L
                    )
                    if (merge == null) {
                        db.dailyMergeDao().insertDailyMerge(mergeEntity.copy(mergeStatus = com.utility.dashcam.data.local.MergeStatus.PROCESSING))
                    } else {
                        db.dailyMergeDao().updateMergeStatus(dateStr, com.utility.dashcam.data.local.MergeStatus.PROCESSING)
                    }

                    // Hand off to orchestrator (suspend function) in serviceScope
                    serviceScope.launch {
                        val orchestrator = FfmpegOrchestrator(this@DashcamIngestionService, db)
                        orchestrator.processDailyMerge(dateStr, clips)
                    }
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.ingestion_service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = getString(R.string.ingestion_service_channel_name)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.ingestion_service_notification_title))
            .setContentText(getString(R.string.ingestion_service_notification_text))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true) // Persistent foreground notification
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        // Clean up network callback to avoid leaks
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            // Ignore if already unregistered
        }
        serviceScope.cancel()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
    }
}