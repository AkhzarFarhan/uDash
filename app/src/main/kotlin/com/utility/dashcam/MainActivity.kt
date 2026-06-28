package com.utility.dashcam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.app.Dialog
import android.widget.VideoView
import android.widget.MediaController
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.utility.dashcam.data.local.AppDatabase
import com.utility.dashcam.data.local.DownloadStatus
import com.utility.dashcam.data.local.MergeStatus
import com.utility.dashcam.data.local.UploadStatus
import com.utility.dashcam.service.DashcamIngestionService
import com.utility.dashcam.util.ConfigStore
import com.utility.dashcam.worker.YouTubeUploadWorker
import com.utility.dashcam.network.TokenManager
import com.utility.dashcam.network.YouTubeOAuthHelper
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.api.services.youtube.YouTubeScopes
import com.google.android.gms.common.api.Scope
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.*
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * Main Dashboard Activity - Material 3 redesign.
 * Provides UI to configure all runtime parameters (persisted in EncryptedSharedPreferences via ConfigStore)
 * and controls for the ingestion service and upload worker.
 * Observes Room DB via Flow for live pipeline status.
 */
class MainActivity : AppCompatActivity() {

    // Config fields
    private lateinit var etDashcamIp: EditText
    private lateinit var etDashcamSsid: EditText
    private lateinit var etHomeSsid: EditText
    private lateinit var actvYoutubePrivacy: AutoCompleteTextView
    private lateinit var etClientId: EditText
    private lateinit var etClientSecret: EditText
    private lateinit var etRefreshToken: EditText

    // Connect YouTube (in-app OAuth)
    private lateinit var btnConnectYoutube: Button
    private lateinit var tvYoutubeStatus: TextView
    private lateinit var layoutYoutubeHeader: LinearLayout
    private lateinit var layoutYoutubeContent: LinearLayout
    private lateinit var ivYoutubeExpandArrow: ImageView

    // Action buttons
    private lateinit var btnSaveConfig: Button
    private lateinit var btnStartIngestion: Button
    private lateinit var btnStopIngestion: Button
    private lateinit var btnEnqueueUpload: Button

    // Status displays
    private lateinit var tvRawClipsCount: TextView
    private lateinit var tvLogConsole: TextView
    private lateinit var btnClearLogs: Button
    private lateinit var scrollLogConsole: android.widget.ScrollView
    private lateinit var tvPendingMergesCount: TextView
    private lateinit var tvPendingUploadsCount: TextView
    private lateinit var tvCompletedUploadsCount: TextView
    private lateinit var tvCurrentStatus: TextView
    private lateinit var tvServiceStatus: TextView
    private lateinit var tvDownloadPath: TextView
    private lateinit var tvMergingDetails: TextView
    private lateinit var tvUploadDetails: TextView
    private lateinit var tvBuildInfo: TextView
    private lateinit var cardError: com.google.android.material.card.MaterialCardView
    private lateinit var tvErrorText: TextView
    private lateinit var containerVideos: LinearLayout
    private lateinit var tvNoVideos: TextView

    private val db by lazy { AppDatabase.getDatabase(this) }

    private val permissionRequestCode = 1001
    private var statusUpdateJob: Job? = null
    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS
        )
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    } else {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    }

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(com.google.android.gms.common.api.ApiException::class.java)
            val authCode = account?.serverAuthCode
            if (authCode != null) {
                handleAuthCode(authCode)
            } else {
                Toast.makeText(this, getString(R.string.youtube_connect_error, "No auth code"), Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.youtube_connect_error, e.message ?: "Unknown"), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Setup global crash handler to catch and display crashes in the UI
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, exception ->
            val stackTrace = exception.stackTraceToString()
            ConfigStore.setLastError(this, "Crash! ${exception.javaClass.simpleName}: ${exception.message}\n$stackTrace")
            defaultHandler?.uncaughtException(thread, exception)
        }

        setContentView(R.layout.activity_main)

        TokenManager.init(this)
        ConfigStore.initWith(this)

        requestPermissionsIfNeeded()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                    Toast.makeText(this, "Please grant All Files Access to write logs to your Download folder", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        startActivity(intent)
                    } catch (ex: Exception) {
                        ex.printStackTrace()
                    }
                }
            }
        }
        initViews()
        loadCurrentConfig()
        setupListeners()
        observePipelineStatus()

        // Load persisted logs and register LogStore listener
        tvLogConsole.text = com.utility.dashcam.util.LogStore.loadLogs(this)
        scrollLogConsole.post {
            scrollLogConsole.fullScroll(android.view.View.FOCUS_DOWN)
        }
        com.utility.dashcam.util.LogStore.setListener { logs ->
            runOnUiThread {
                tvLogConsole.text = logs
                scrollLogConsole.post {
                    scrollLogConsole.fullScroll(android.view.View.FOCUS_DOWN)
                }
            }
        }
    }

    private val configListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "last_error" || key == "merging_status" || key == "uploading_status") {
            runOnUiThread {
                updateErrorCard()
                updateStatusDetails()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ConfigStore.registerListener(this, configListener)
        updateErrorCard()
        updateStatusDetails()
        refreshIngestedVideos()
        statusUpdateJob = lifecycleScope.launch {
            while (isActive) {
                updateStatusDetails()
                delay(1000)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        ConfigStore.unregisterListener(this, configListener)
        statusUpdateJob?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        com.utility.dashcam.util.LogStore.setListener(null)
    }

    private fun updateErrorCard() {
        val lastError = ConfigStore.getLastError(this)
        if (!lastError.isNullOrBlank()) {
            tvErrorText.text = lastError
            cardError.visibility = android.view.View.VISIBLE
        } else {
            cardError.visibility = android.view.View.GONE
        }
    }

    private fun updateStatusDetails() {
        val mergeStatusStr = ConfigStore.getMergingStatus(this)
        val uploadStatusStr = ConfigStore.getUploadingStatus(this)
        val isRunning = com.utility.dashcam.service.DashcamIngestionService.isServiceRunning
        tvServiceStatus.text = "Ingestion Service: ${if (isRunning) "Running" else "Stopped"}"
        tvMergingDetails.text = "Merging Details: $mergeStatusStr"
        tvUploadDetails.text = "Upload Details: $uploadStatusStr"
    }

    private fun requestPermissionsIfNeeded() {
        val missingPermissions = requiredPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), permissionRequestCode)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequestCode) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (!allGranted) {
                Toast.makeText(this, "Some permissions denied - auto-detection may not work", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun initViews() {
        etDashcamIp = findViewById(R.id.etDashcamIp)
        etDashcamSsid = findViewById(R.id.etDashcamSsid)
        etHomeSsid = findViewById(R.id.etHomeSsid)
        actvYoutubePrivacy = findViewById(R.id.actvYoutubePrivacy)
        etClientId = findViewById(R.id.etClientId)
        etClientSecret = findViewById(R.id.etClientSecret)
        etRefreshToken = findViewById(R.id.etRefreshToken)

        layoutYoutubeHeader = findViewById(R.id.layoutYoutubeHeader)
        layoutYoutubeContent = findViewById(R.id.layoutYoutubeContent)
        ivYoutubeExpandArrow = findViewById(R.id.ivYoutubeExpandArrow)

        tvLogConsole = findViewById(R.id.tvLogConsole)
        btnClearLogs = findViewById(R.id.btnClearLogs)
        scrollLogConsole = findViewById(R.id.scrollLogConsole)

        btnConnectYoutube = findViewById(R.id.btnConnectYoutube)
        tvYoutubeStatus = findViewById(R.id.tvYoutubeStatus)

        btnSaveConfig = findViewById(R.id.btnSaveConfig)
        btnStartIngestion = findViewById(R.id.btnStartIngestion)
        btnStopIngestion = findViewById(R.id.btnStopIngestion)
        btnEnqueueUpload = findViewById(R.id.btnEnqueueUpload)

        tvRawClipsCount = findViewById(R.id.tvRawClipsCount)
        tvPendingMergesCount = findViewById(R.id.tvPendingMergesCount)
        tvPendingUploadsCount = findViewById(R.id.tvPendingUploadsCount)
        tvCompletedUploadsCount = findViewById(R.id.tvCompletedUploadsCount)
        tvCurrentStatus = findViewById(R.id.tvCurrentStatus)
        tvServiceStatus = findViewById(R.id.tvServiceStatus)
        tvDownloadPath = findViewById(R.id.tvDownloadPath)
        tvMergingDetails = findViewById(R.id.tvMergingDetails)
        tvUploadDetails = findViewById(R.id.tvUploadDetails)
        tvBuildInfo = findViewById(R.id.tvBuildInfo)
        cardError = findViewById(R.id.cardError)
        tvErrorText = findViewById(R.id.tvErrorText)
        containerVideos = findViewById(R.id.containerVideos)
        tvNoVideos = findViewById(R.id.tvNoVideos)

        // Show cache directory path
        tvDownloadPath.text = "Raw Clips Path: ${cacheDir.absolutePath}"

        // Set build and version info dynamically
        try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                packageManager.getPackageInfo(packageName, 0)
            }
            val versionName = packageInfo.versionName ?: "1.0.1"
            tvBuildInfo.text = "Version $versionName (Build: ${BuildConfig.BUILD_TIME})"
        } catch (e: Exception) {
            tvBuildInfo.text = "Version 1.0.1 (Build: ${BuildConfig.BUILD_TIME})"
        }

        // Privacy dropdown adapter
        val privacyOptions = arrayOf("private", "unlisted", "public")
        actvYoutubePrivacy.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, privacyOptions))
        actvYoutubePrivacy.setText(privacyOptions[0], false) // default
    }

    private fun loadCurrentConfig() {
        etDashcamIp.setText(ConfigStore.getDashcamIp(this))
        etDashcamSsid.setText(ConfigStore.getDashcamSsidPrefix(this))
        etHomeSsid.setText(ConfigStore.getHomeWifiSsid(this))
        actvYoutubePrivacy.setText(ConfigStore.getYoutubePrivacy(this), false)
        ConfigStore.getOAuthClientId(this)?.let { etClientId.setText(it) }
        ConfigStore.getOAuthClientSecret(this)?.let { etClientSecret.setText(it) }
        ConfigStore.getOAuthRefreshToken(this)?.let { etRefreshToken.setText(it) }
    }

    private fun setupListeners() {
        btnConnectYoutube.setOnClickListener { startGoogleSignIn() }
        updateYoutubeConnectionStatus()

        layoutYoutubeHeader.setOnClickListener {
            if (layoutYoutubeContent.visibility == android.view.View.VISIBLE) {
                layoutYoutubeContent.visibility = android.view.View.GONE
                ivYoutubeExpandArrow.setImageResource(android.R.drawable.arrow_down_float)
            } else {
                layoutYoutubeContent.visibility = android.view.View.VISIBLE
                ivYoutubeExpandArrow.setImageResource(android.R.drawable.arrow_up_float)
            }
        }

        btnClearLogs.setOnClickListener {
            com.utility.dashcam.util.LogStore.clear(this)
        }

        // Allow inner ScrollView for log console to scroll correctly inside outer ScrollView
        scrollLogConsole.setOnTouchListener { v, event ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            false
        }

        btnSaveConfig.setOnClickListener { saveConfig() }
        btnStartIngestion.setOnClickListener { startIngestionService() }
        btnStopIngestion.setOnClickListener { stopIngestionService() }
        btnEnqueueUpload.setOnClickListener { enqueueUpload() }
    }

    private fun saveConfig() {
        ConfigStore.setDashcamIp(this, etDashcamIp.text.toString().trim())
        ConfigStore.setDashcamSsidPrefix(this, etDashcamSsid.text.toString().trim())
        ConfigStore.setHomeWifiSsid(this, etHomeSsid.text.toString().trim())
        ConfigStore.setYoutubePrivacy(this, actvYoutubePrivacy.text.toString().trim())
        ConfigStore.setOAuthClientId(this, etClientId.text.toString().trim())
        ConfigStore.setOAuthClientSecret(this, etClientSecret.text.toString().trim())
        ConfigStore.setOAuthRefreshToken(this, etRefreshToken.text.toString().trim())

        Toast.makeText(this, "Configuration saved (encrypted)", Toast.LENGTH_SHORT).show()
    }

    private fun startIngestionService() {
        ConfigStore.setIngestionEnabled(this, true)
        val intent = Intent(this, DashcamIngestionService::class.java)
        startForegroundService(intent)
        Toast.makeText(this, "Ingestion service started", Toast.LENGTH_SHORT).show()
    }

    private fun startGoogleSignIn() {
        val clientId = etClientId.text.toString().trim()
        val clientSecret = etClientSecret.text.toString().trim()
        if (clientId.isBlank() || clientSecret.isBlank()) {
            Toast.makeText(this, getString(R.string.youtube_enter_credentials_first), Toast.LENGTH_LONG).show()
            return
        }
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestServerAuthCode(clientId, true)
            .requestEmail()
            .requestScopes(Scope(YouTubeScopes.YOUTUBE_UPLOAD), Scope(YouTubeScopes.YOUTUBE_READONLY))
            .build()
        val googleSignInClient = GoogleSignIn.getClient(this, gso)
        googleSignInClient.signOut().addOnCompleteListener {
            googleSignInLauncher.launch(googleSignInClient.signInIntent)
        }
    }

    private fun handleAuthCode(authCode: String) {
        val clientId = etClientId.text.toString().trim()
        val clientSecret = etClientSecret.text.toString().trim()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                YouTubeOAuthHelper.exchangeCodeForTokens(authCode, clientId, clientSecret)
            }
            result.onSuccess { tokens ->
                ConfigStore.setOAuthClientId(this@MainActivity, clientId)
                ConfigStore.setOAuthClientSecret(this@MainActivity, clientSecret)
                ConfigStore.setOAuthRefreshToken(this@MainActivity, tokens.refreshToken!!)
                if (!tokens.accessToken.isNullOrBlank()) {
                    ConfigStore.setOAuthAccessToken(this@MainActivity, tokens.accessToken)
                }
                val emailResult = withContext(Dispatchers.IO) {
                    tokens.accessToken?.let { YouTubeOAuthHelper.fetchUserEmail(it) }
                }
                val email = emailResult?.getOrNull() ?: "Connected"
                ConfigStore.setOAuthAccountName(this@MainActivity, email)
                etRefreshToken.setText(tokens.refreshToken)
                updateYoutubeConnectionStatus()
                TokenManager.invalidateToken()
                Toast.makeText(this@MainActivity, getString(R.string.youtube_connect_success), Toast.LENGTH_SHORT).show()
            }
            result.onFailure { error ->
                val msg = when (error) {
                    is YouTubeOAuthHelper.OAuthExchangeException -> "${'$'}{error.httpCode}: ${'$'}{error.message}"
                    else -> error.message ?: "Unknown error"
                }
                Toast.makeText(this@MainActivity, getString(R.string.youtube_connect_error, msg), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateYoutubeConnectionStatus() {
        val accountName = ConfigStore.getOAuthAccountName(this)
        val refreshToken = ConfigStore.getOAuthRefreshToken(this)
        if (!accountName.isNullOrBlank() && !refreshToken.isNullOrBlank()) {
            tvYoutubeStatus.text = getString(R.string.youtube_connected, accountName)
            btnConnectYoutube.text = getString(R.string.btn_disconnect_youtube)
            btnConnectYoutube.setOnClickListener {
                ConfigStore.setOAuthClientId(this, "")
                ConfigStore.setOAuthClientSecret(this, "")
                ConfigStore.setOAuthRefreshToken(this, "")
                ConfigStore.setOAuthAccessToken(this, "")
                ConfigStore.setOAuthAccountName(this, "")
                etClientId.text.clear()
                etClientSecret.text.clear()
                etRefreshToken.text.clear()
                TokenManager.invalidateToken()
                updateYoutubeConnectionStatus()
            }
        } else {
            tvYoutubeStatus.text = getString(R.string.youtube_not_connected)
            btnConnectYoutube.text = getString(R.string.btn_connect_youtube)
            btnConnectYoutube.setOnClickListener { startGoogleSignIn() }
        }
    }

    private fun stopIngestionService() {
        ConfigStore.setIngestionEnabled(this, false)
        val intent = Intent(this, DashcamIngestionService::class.java)
        stopService(intent)
        Toast.makeText(this, "Ingestion service stopped", Toast.LENGTH_SHORT).show()
    }

    private fun enqueueUpload() {
        // Validate OAuth config exists
        if (ConfigStore.getOAuthClientId(this).isNullOrBlank()
            || ConfigStore.getOAuthClientSecret(this).isNullOrBlank()
            || ConfigStore.getOAuthRefreshToken(this).isNullOrBlank()) {
            Toast.makeText(this, "Please configure OAuth credentials first", Toast.LENGTH_LONG).show()
            return
        }

        YouTubeUploadWorker.enqueueUpload(this)
        Toast.makeText(this, "Upload enqueued (UNMETERED Wi-Fi constraint)", Toast.LENGTH_SHORT).show()
    }

    /**
     * Observe Room DB via Flow for live pipeline status.
     * Combines multiple queries into a single UI update.
     */
    private fun observePipelineStatus() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val rawClipsFlow = db.rawClipDao().getRawClipsByStatusFlow(DownloadStatus.COMPLETED)
                    .map { it.size }
                    .distinctUntilChanged()

                val pendingMergesFlow = db.dailyMergeDao().getAllMerges()
                    .map { merges ->
                        merges.count { it.mergeStatus == MergeStatus.PENDING || it.mergeStatus == MergeStatus.PROCESSING }
                    }
                    .distinctUntilChanged()

                val pendingUploadsFlow = db.dailyMergeDao().getAllMerges()
                    .map { merges ->
                        merges.count { it.uploadStatus == UploadStatus.IDLE || it.uploadStatus == UploadStatus.UPLOADING || it.uploadStatus == UploadStatus.FAILED }
                    }
                    .distinctUntilChanged()

                val completedUploadsFlow = db.dailyMergeDao().getAllMerges()
                    .map { merges ->
                        merges.count { it.uploadStatus == UploadStatus.SUCCESS }
                    }
                    .distinctUntilChanged()

                combine(rawClipsFlow, pendingMergesFlow, pendingUploadsFlow, completedUploadsFlow) { raw, pendingM, pendingU, completedU ->
                    listOf(raw, pendingM, pendingU, completedU)
                }.collect { (rawCount, pendingMergeCount, pendingUploadCount, completedUploadCount) ->
                    tvRawClipsCount.text = rawCount.toString()
                    tvPendingMergesCount.text = pendingMergeCount.toString()
                    tvPendingUploadsCount.text = pendingUploadCount.toString()
                    tvCompletedUploadsCount.text = completedUploadCount.toString()

                    // Update current activity status text
                    val statusText = when {
                        pendingMergeCount > 0 -> getString(R.string.status_merging)
                        pendingUploadCount > 0 -> getString(R.string.status_uploading)
                        rawCount > 0 -> getString(R.string.status_ingesting)
                        else -> getString(R.string.status_idle)
                    }
                    tvCurrentStatus.text = statusText
                    
                    refreshIngestedVideos()
                }
            }
        }
    }

    private fun refreshIngestedVideos() {
        val mergedDir = filesDir
        val files = mergedDir.listFiles { _, name -> name.startsWith("merge_") && name.endsWith(".mp4") }
        
        runOnUiThread {
            containerVideos.removeAllViews()
            if (files.isNullOrEmpty()) {
                tvNoVideos.visibility = android.view.View.VISIBLE
            } else {
                tvNoVideos.visibility = android.view.View.GONE
                // Sort files by last modified (newest first)
                files.sortByDescending { it.lastModified() }
                files.forEach { file ->
                    val itemView = layoutInflater.inflate(R.layout.item_ingested_video, containerVideos, false)
                    val tvVideoName = itemView.findViewById<TextView>(R.id.tvVideoName)
                    val tvVideoSize = itemView.findViewById<TextView>(R.id.tvVideoSize)
                    val btnPlayVideo = itemView.findViewById<Button>(R.id.btnPlayVideo)
                    
                    tvVideoName.text = file.name
                    val sizeInMb = file.length() / (1024 * 1024L)
                    tvVideoSize.text = "$sizeInMb MB"
                    
                    btnPlayVideo.setOnClickListener {
                        playVideo(file)
                    }
                    containerVideos.addView(itemView)
                }
            }
        }
    }

    private fun playVideo(file: File) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(R.layout.dialog_video_player)
        
        val videoView = dialog.findViewById<VideoView>(R.id.dialogVideoView)
        val tvPlayerTitle = dialog.findViewById<TextView>(R.id.tvPlayerTitle)
        val btnClosePlayer = dialog.findViewById<ImageButton>(R.id.btnClosePlayer)
        
        tvPlayerTitle.text = file.name
        videoView.setVideoPath(file.absolutePath)
        
        val mediaController = MediaController(this)
        mediaController.setAnchorView(videoView)
        videoView.setMediaController(mediaController)
        
        btnClosePlayer.setOnClickListener {
            dialog.dismiss()
        }
        
        dialog.setOnDismissListener {
            videoView.stopPlayback()
        }
        
        videoView.setOnPreparedListener {
            videoView.start()
        }
        
        dialog.show()
    }
}