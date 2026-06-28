package com.utility.dashcam.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.utility.dashcam.R
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.utility.dashcam.data.local.AppDatabase
import com.utility.dashcam.data.local.DownloadStatus
import com.utility.dashcam.data.local.MergeStatus
import com.utility.dashcam.data.local.RawClipEntity
import com.utility.dashcam.data.local.UploadStatus
import com.utility.dashcam.util.ConfigStore
import com.utility.dashcam.util.LogStore
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
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.ExistingWorkPolicy
import com.utility.dashcam.worker.YouTubeUploadWorker

/**
 * Foreground Service for automated raw clip ingestion from DDPAI Dashcam Wi-Fi AP.
 * Configured as type dataSync for Android 14 compliance.
 */
class DashcamIngestionService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var wifiManager: WifiManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val db by lazy { AppDatabase.getDatabase(this) }
    private val CHANNEL_ID = "dashcam_ingestion_channel"
    private val isIngesting = AtomicBoolean(false)
    private val isMerging = AtomicBoolean(false)
    private var dashcamOkHttpClient: OkHttpClient? = null

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        wifiManager = getSystemService(Context.WIFI_SERVICE) as WifiManager
        createNotificationChannel()
        
        // Android 14 FGS compliance: pass FOREGROUND_SERVICE_TYPE_DATA_SYNC
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
        
        // Reset stuck processing/uploading merges and downloading clips on startup
        serviceScope.launch {
            db.rawClipDao().resetDownloadingClips()
            db.dailyMergeDao().resetProcessingMerges()
            db.dailyMergeDao().resetUploadingMerges()
            checkAndTriggerMerge()
            triggerYoutubeUpload()
        }

        setupDashcamNetwork()
    }

    private fun isConnectedToDashcamAp(): Boolean {
        val info = wifiManager.connectionInfo
        val ssid = info?.ssid?.replace("\"", "")
        val prefix = ConfigStore.getDashcamSsidPrefix(this)
        val configuredIp = ConfigStore.getDashcamIp(this)

        if (ssid != null && ssid != "<unknown ssid>" && ssid.isNotBlank()) {
            return ssid.startsWith(prefix, ignoreCase = true)
        } else {
            // Check DHCP gateway (fallback for background state where location is restricted)
            val dhcpInfo = wifiManager.dhcpInfo
            val gatewayIp = intToIp(dhcpInfo?.gateway ?: 0)
            return gatewayIp == configuredIp && gatewayIp != "0.0.0.0"
        }
    }

    private fun intToIp(i: Int): String {
        return (i and 0xFF).toString() + "." +
               ((i shr 8) and 0xFF) + "." +
               ((i shr 16) and 0xFF) + "." +
               ((i shr 24) and 0xFF)
    }

    private fun setupDashcamNetwork() {
        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                LogStore.log(this@DashcamIngestionService, "Ingestion", "Wi-Fi Network available: $network")
                if (!isConnectedToDashcamAp()) {
                    LogStore.log(this@DashcamIngestionService, "Ingestion", "SSID prefix mismatch. Ignoring network.")
                    serviceScope.launch {
                        checkAndTriggerMerge()
                        triggerYoutubeUpload()
                    }
                    return
                }
                if (!isIngesting.compareAndSet(false, true)) {
                    LogStore.log(this@DashcamIngestionService, "Ingestion", "Ingestion already in progress. Ignoring network.")
                    return
                }
                
                // Reuse OkHttpClient bound to the dashcam Wi-Fi interface
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

            override fun onLost(network: Network) {
                LogStore.log(this@DashcamIngestionService, "Ingestion", "Wi-Fi Network lost: $network")
                // Connection lost! Clean up database and trigger merge
                serviceScope.launch {
                    handleDashcamDisconnected()
                }
            }
        }
        
        this.networkCallback = callback
        LogStore.log(this, "Ingestion", "Registering network callback for Dashcam Wi-Fi AP...")
        connectivityManager.requestNetwork(networkRequest, callback)
    }

    private suspend fun handleDashcamDisconnected() {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val today = sdf.format(java.util.Date())
        val completedDates = db.rawClipDao().getDistinctCompletedDates()
        
        for (dateStr in completedDates) {
            if (dateStr == today) continue
            // Delete non-completed clips for historical dates so they don't block merging
            db.rawClipDao().deleteNonCompletedClipsByDate(dateStr)
        }
        
        // Trigger sequential merges
        checkAndTriggerMerge()
    }

    private suspend fun executeCameraIngestion(client: OkHttpClient) {
        try {
            val manifestUrl = "http://${ConfigStore.getDashcamIp(this)}/vcam/cmd.cgi?cmd=APP_PlaybackListReq"
            LogStore.log(this, "Ingestion", "Starting playback list download. Fetching manifest from: $manifestUrl")
            val request = Request.Builder().url(manifestUrl).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val errorMsg = "Manifest request failed: HTTP ${response.code}"
                LogStore.log(this, "Ingestion", errorMsg, isError = true)
                ConfigStore.setLastError(this, errorMsg)
                return
            }
            val manifestText = response.body?.string() ?: ""
            LogStore.log(this, "Ingestion", "Retrieved manifest (${manifestText.length} bytes)")
            LogStore.log(this, "Ingestion", "Raw Manifest Content:\n$manifestText")
            val clips = parseManifest(manifestText)
            LogStore.log(this, "Ingestion", "Parsed ${clips.size} clips from manifest.")
            if (clips.isNotEmpty()) {
                db.rawClipDao().insertRawClips(clips)
            }
            
            // Download PENDING and FAILED clips (retrying previously failed ones)
            val pending = db.rawClipDao().getClipsToDownload()
            LogStore.log(this, "Ingestion", "Found ${pending.size} pending/failed clips to download.")
            pending.forEachIndexed { index, clip ->
                downloadClip(client, clip, index + 1, pending.size)
            }
            
            checkAndTriggerMerge()
        } catch (e: Exception) { 
            e.printStackTrace()
            LogStore.log(this, "Ingestion", "Ingestion failed: ${e.message}", isError = true)
            ConfigStore.setLastError(this, "Ingestion failed: ${e.message}")
        }
    }

    private fun parseManifest(text: String): List<RawClipEntity> {
        val pattern = """(\d{8}[a-zA-Z0-9_\-]*?\.mp4)""".toRegex()
        return pattern.findAll(text).map { matchResult ->
            val fileName = matchResult.groupValues[1]
            val dateStr = "${fileName.substring(0,4)}-${fileName.substring(4,6)}-${fileName.substring(6,8)}"
            val remoteUrl = "http://${ConfigStore.getDashcamIp(this)}/$fileName"
            RawClipEntity(fileName, dateStr, remoteUrl, null, 0L, DownloadStatus.PENDING)
        }.toList()
    }

    private suspend fun downloadClip(client: OkHttpClient, clip: RawClipEntity, currentIdx: Int, totalIdx: Int) {
        db.rawClipDao().updateDownloadStatus(clip.fileName, DownloadStatus.DOWNLOADING)
        LogStore.log(this, "Ingestion", "[$currentIdx/$totalIdx] Starting download of ${clip.fileName} (${clip.remoteUrl})")
        val request = Request.Builder().url(clip.remoteUrl).build()
        try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body ?: throw IllegalStateException("Response body is null")
                val file = File(cacheDir, clip.fileName)
                val contentLength = body.contentLength()
                LogStore.log(this, "Ingestion", "[$currentIdx/$totalIdx] Downloading ${clip.fileName} (Size: ${contentLength / 1024} KB)")
                if (contentLength > 0) {
                    val requiredSpace = (contentLength * 1.5).toLong()
                    if (cacheDir.usableSpace < requiredSpace) {
                        throw IllegalStateException("Storage Exhaustion")
                    }
                }
                body.byteStream().use { input ->
                    FileOutputStream(file).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalBytesRead = 0L
                        var lastReportTime = 0L
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead
                            if (contentLength > 0) {
                                val now = System.currentTimeMillis()
                                if (now - lastReportTime > 1000) { // Report progress every 1 second
                                    val pct = (totalBytesRead * 100) / contentLength
                                    LogStore.log(this, "Ingestion", "[$currentIdx/$totalIdx] Downloading ${clip.fileName}: $pct% (${totalBytesRead / 1024 / 1024}MB/${contentLength / 1024 / 1024}MB)")
                                    lastReportTime = now
                                }
                            }
                        }
                    }
                }
                
                // Verify file was actually written, is > 1 MB, and can be successfully parsed by FFprobe (verifies moov atom and readability)
                var isValid = false
                if (file.exists() && file.length() > 1 * 1024 * 1024) {
                    try {
                        val session = FFprobeKit.execute(file.absolutePath)
                        if (ReturnCode.isSuccess(session.returnCode)) {
                            isValid = true
                        }
                    } catch (e: Exception) {
                        LogStore.log(this, "Ingestion", "Failed to probe download file ${clip.fileName}: ${e.message}")
                    }
                }

                if (isValid) {
                    db.rawClipDao().markDownloadCompleted(clip.fileName, file.absolutePath, file.length())
                    LogStore.log(this, "Ingestion", "[$currentIdx/$totalIdx] Download complete: ${clip.fileName} (${file.length() / 1024 / 1024} MB)")
                    ConfigStore.setLastError(this, null) // Clear error on success
                } else {
                    file.delete()
                    db.rawClipDao().markDownloadFailed(clip.fileName)
                    val errorMsg = "Download error: file ${clip.fileName} is too small, corrupted, or missing a valid moov atom."
                    LogStore.log(this, "Ingestion", errorMsg, isError = true)
                    ConfigStore.setLastError(this, errorMsg)
                }
            } else {
                db.rawClipDao().markDownloadFailed(clip.fileName)
                val errorMsg = "Download error: HTTP ${response.code} for ${clip.fileName}"
                LogStore.log(this, "Ingestion", errorMsg, isError = true)
                ConfigStore.setLastError(this, errorMsg)
            }
        } catch (e: Exception) {
            db.rawClipDao().markDownloadFailed(clip.fileName)
            val errorMsg = "Download error: ${e.message} for ${clip.fileName}"
            LogStore.log(this, "Ingestion", errorMsg, isError = true)
            ConfigStore.setLastError(this, errorMsg)
        }
    }

    private suspend fun checkAndTriggerMerge() {
        LogStore.log(this, "Ingestion", "[Merge Check] checkAndTriggerMerge() invoked.")
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val today = sdf.format(java.util.Date())
        LogStore.log(this, "Ingestion", "[Merge Check] Current date (today): $today")

        val completedDates = db.rawClipDao().getDistinctCompletedDates()
        LogStore.log(this, "Ingestion", "[Merge Check] Distinct completed dates in DB: $completedDates")

        // Block current date, merge closed historical days
        val datesToMerge = completedDates.filter { it != today }
        LogStore.log(this, "Ingestion", "[Merge Check] Dates to merge (excluding today): $datesToMerge")

        if (datesToMerge.isEmpty()) {
            LogStore.log(this, "Ingestion", "[Merge Check] No merge candidates found. Returning.")
            return
        }

        if (!isMerging.compareAndSet(false, true)) {
            LogStore.log(this, "Ingestion", "[Merge Check] Merging already in progress. Ignoring request.")
            return
        }

        serviceScope.launch {
            try {
                LogStore.log(this@DashcamIngestionService, "Ingestion", "[Merge Check] Started background merge coroutine.")
                val orchestrator = FfmpegOrchestrator(this@DashcamIngestionService, db)
                for (dateStr in datesToMerge) {
                    LogStore.log(this@DashcamIngestionService, "Ingestion", "[Merge Check] Processing date: $dateStr")

                    val countBeforePurge = withTimeout(5000) {
                        db.rawClipDao().countNonCompletedByDate(dateStr)
                    }
                    LogStore.log(this@DashcamIngestionService, "Ingestion", "[Merge Check] Date $dateStr: count of non-completed clips before purge: $countBeforePurge")

                    withTimeout(5000) {
                        db.rawClipDao().deleteNonCompletedClipsByDate(dateStr)
                    }
                    LogStore.log(this@DashcamIngestionService, "Ingestion", "[Merge Check] Date $dateStr: non-completed clips purged.")

                    val completedClips = withTimeout(5000) {
                        db.rawClipDao().getCompletedClipsByDate(dateStr)
                    }

                    // Validate physical files on disk to auto-heal corrupted/0-byte/HTML index clips
                    val validCompletedClips = mutableListOf<RawClipEntity>()
                    completedClips.forEach { clip ->
                        val path = clip.localFilePath
                        val file = if (path != null) File(path) else null
                        
                        var isValid = false
                        if (file != null && file.exists() && file.length() > 1 * 1024 * 1024) { // > 1 MB
                            try {
                                val session = FFprobeKit.execute(file.absolutePath)
                                if (ReturnCode.isSuccess(session.returnCode)) {
                                    isValid = true
                                }
                            } catch (e: Exception) {
                                LogStore.log(this@DashcamIngestionService, "Ingestion", "[Merge Check] Failed to probe clip ${clip.fileName}: ${e.message}")
                            }
                        }

                        if (isValid) {
                            validCompletedClips.add(clip)
                        } else {
                            LogStore.log(this@DashcamIngestionService, "Ingestion", "[Merge Check] Clip ${clip.fileName} is corrupted, too small, or not a valid MP4 (size: ${file?.length() ?: 0} bytes). Resetting status to PENDING.")
                            runBlocking {
                                withTimeout(5000) {
                                    db.rawClipDao().updateDownloadSuccess(clip.fileName, DownloadStatus.PENDING, null, 0L)
                                }
                            }
                            try { file?.delete() } catch (e: Exception) {}
                        }
                    }

                    LogStore.log(this@DashcamIngestionService, "Ingestion", "[Merge Check] Date $dateStr: completed clips count: ${completedClips.size}, valid: ${validCompletedClips.size}")

                    if (validCompletedClips.isEmpty()) {
                        LogStore.log(this@DashcamIngestionService, "Ingestion", "[Merge Check] Date $dateStr: No valid completed clips left. Skipping.")
                        continue
                    }

                    // Group clips chronologically into chunks of max 500 MB
                    val maxChunkSize = 500 * 1024 * 1024L
                    val chunks = mutableListOf<List<RawClipEntity>>()
                    var currentChunk = mutableListOf<RawClipEntity>()
                    var currentSize = 0L

                    validCompletedClips.forEach { clip ->
                        val size = clip.fileSize
                        if (currentSize + size > maxChunkSize && currentChunk.isNotEmpty()) {
                            chunks.add(currentChunk)
                            currentChunk = mutableListOf()
                            currentSize = 0L
                        }
                        currentChunk.add(clip)
                        currentSize += size
                    }
                    if (currentChunk.isNotEmpty()) {
                        chunks.add(currentChunk)
                    }

                    LogStore.log(this@DashcamIngestionService, "Ingestion", "[Merge Check] Date $dateStr: Split into ${chunks.size} chunks to merge.")

                    // Process chunks sequentially
                    chunks.forEachIndexed { index, chunkClips ->
                        val partNum = index + 1
                        val mergeId = if (chunks.size == 1) dateStr else "${dateStr}_part$partNum"

                        val merge = withTimeout(5000) {
                            db.dailyMergeDao().getDailyMerge(mergeId)
                        }
                        val needsMerge = merge == null || merge.mergeStatus == MergeStatus.PENDING || merge.mergeStatus == MergeStatus.FAILED
                        LogStore.log(this@DashcamIngestionService, "Ingestion", "[Merge Check] Merge $mergeId needsMerge: $needsMerge (current DB status: ${merge?.mergeStatus})")

                        if (!needsMerge) return@forEachIndexed

                        if (merge == null) {
                            LogStore.log(this@DashcamIngestionService, "Ingestion", "[Merge Check] Merge $mergeId not found in DB. Inserting.")
                            withTimeout(5000) {
                                db.dailyMergeDao().insertDailyMerge(
                                    com.utility.dashcam.data.local.DailyMergeEntity(
                                        mergeId = mergeId,
                                        dateString = dateStr,
                                        localMergedPath = null,
                                        totalSize = 0L,
                                        mergeStatus = MergeStatus.PROCESSING,
                                        uploadStatus = UploadStatus.IDLE,
                                        youtubeVideoId = null,
                                        lastAttemptTimestamp = 0L
                                    )
                                )
                            }
                        } else {
                            LogStore.log(this@DashcamIngestionService, "Ingestion", "[Merge Check] Merge $mergeId found. Updating status to PROCESSING.")
                            withTimeout(5000) {
                                db.dailyMergeDao().updateMergeStatus(mergeId, MergeStatus.PROCESSING)
                            }
                        }

                        LogStore.log(this@DashcamIngestionService, "Ingestion", "[Merge Check] Invoking FFmpeg orchestrator for $mergeId.")
                        orchestrator.processDailyMerge(mergeId, dateStr, chunkClips)
                    }

                    // Delete raw database entries if all splits for this date are successfully merged
                    val merges = withTimeout(5000) {
                        db.dailyMergeDao().getDailyMergesForDate(dateStr)
                    }
                    val allCompleted = merges.isNotEmpty() && merges.all { it.mergeStatus == MergeStatus.COMPLETED }
                    LogStore.log(this@DashcamIngestionService, "Ingestion", "[Merge Check] Date $dateStr: allCompleted=$allCompleted (merges size: ${merges.size})")
                    if (allCompleted) {
                        // Delete raw files from physical disk only when all merges for this date succeeded!
                        validCompletedClips.forEach { clip ->
                            clip.localFilePath?.let { path ->
                                val file = File(path)
                                if (file.exists()) {
                                    file.delete()
                                }
                            }
                        }
                        withTimeout(5000) {
                            db.dailyMergeDao().deleteRawClipsByDate(dateStr)
                        }
                        LogStore.log(this@DashcamIngestionService, "Ingestion", "[Merge Check] Date $dateStr: Raw clips deleted from database and physical disk.")
                    }
                }
            } catch (e: Exception) {
                LogStore.log(this@DashcamIngestionService, "Ingestion", "[Merge Check] Exception in merge loop: ${e.message}\n${e.stackTraceToString()}", isError = true)
            } finally {
                com.utility.dashcam.util.ConfigStore.setMergingStatus(this@DashcamIngestionService, "Idle")
                LogStore.log(this@DashcamIngestionService, "Ingestion", "[Merge Check] Finished background merge coroutine.")
                isMerging.set(false)
                triggerYoutubeUpload()
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

    private fun triggerYoutubeUpload() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()

        val uploadWorkRequest = OneTimeWorkRequestBuilder<YouTubeUploadWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "youtube_upload_work",
            ExistingWorkPolicy.REPLACE,
            uploadWorkRequest
        )
        LogStore.log(this, "Ingestion", "YouTube upload worker enqueued.")
    }
    
    override fun onDestroy() {
        isServiceRunning = false
        super.onDestroy()
        try { networkCallback?.let { connectivityManager.unregisterNetworkCallback(it) } } catch (e: Exception) {}
        
        // Clean up database and force-merge remaining completed clips on stop
        runBlocking {
            handleDashcamDisconnected()
        }
        serviceScope.cancel()
    }
    
    companion object { 
        private const val NOTIFICATION_ID = 1001 
        @Volatile
        var isServiceRunning = false
    }
}