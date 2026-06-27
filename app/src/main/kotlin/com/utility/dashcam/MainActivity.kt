package com.utility.dashcam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

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

    // Action buttons
    private lateinit var btnSaveConfig: Button
    private lateinit var btnStartIngestion: Button
    private lateinit var btnStopIngestion: Button
    private lateinit var btnEnqueueUpload: Button

    // Status displays
    private lateinit var tvRawClipsCount: TextView
    private lateinit var tvPendingMergesCount: TextView
    private lateinit var tvPendingUploadsCount: TextView
    private lateinit var tvCompletedUploadsCount: TextView
    private lateinit var tvCurrentStatus: TextView

    private val db by lazy { AppDatabase.getDatabase(this) }

    private val permissionRequestCode = 1001
    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        requestPermissionsIfNeeded()
        initViews()
        loadCurrentConfig()
        setupListeners()
        observePipelineStatus()
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

        btnSaveConfig = findViewById(R.id.btnSaveConfig)
        btnStartIngestion = findViewById(R.id.btnStartIngestion)
        btnStopIngestion = findViewById(R.id.btnStopIngestion)
        btnEnqueueUpload = findViewById(R.id.btnEnqueueUpload)

        tvRawClipsCount = findViewById(R.id.tvRawClipsCount)
        tvPendingMergesCount = findViewById(R.id.tvPendingMergesCount)
        tvPendingUploadsCount = findViewById(R.id.tvPendingUploadsCount)
        tvCompletedUploadsCount = findViewById(R.id.tvCompletedUploadsCount)
        tvCurrentStatus = findViewById(R.id.tvCurrentStatus)

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
        Toast.makeText(this, "Upload enqueued (UNMETERED + charging constraints)", Toast.LENGTH_SHORT).show()
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
                }
            }
        }
    }
}