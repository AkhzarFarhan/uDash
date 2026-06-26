package com.utility.dashcam

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.utility.dashcam.service.DashcamIngestionService
import com.utility.dashcam.util.ConfigStore
import com.utility.dashcam.worker.YouTubeUploadWorker

/**
 * Main Dashboard Activity.
 * Provides a UI to configure all runtime parameters (persisted in EncryptedSharedPreferences via ConfigStore)
 * and controls for the ingestion service and upload worker.
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

    // Status
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        loadCurrentConfig()
        setupListeners()
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

        tvStatus = findViewById(R.id.tvStatus)

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
        btnSaveConfig.setOnClickListener {
            saveConfig()
        }

        btnStartIngestion.setOnClickListener {
            startIngestionService()
        }

        btnStopIngestion.setOnClickListener {
            stopIngestionService()
        }

        btnEnqueueUpload.setOnClickListener {
            enqueueUpload()
        }
    }

    private fun saveConfig() {
        ConfigStore.setDashcamIp(this, etDashcamIp.text.toString().trim())
        ConfigStore.setDashcamSsidPrefix(this, etDashcamSsid.text.toString().trim())
        ConfigStore.setHomeWifiSsid(this, etHomeSsid.text.toString().trim())
        ConfigStore.setYoutubePrivacy(this, actvYoutubePrivacy.text.toString().trim())
        ConfigStore.setOAuthClientId(this, etClientId.text.toString().trim())
        ConfigStore.setOAuthClientSecret(this, etClientSecret.text.toString().trim())
        ConfigStore.setOAuthRefreshToken(this, etRefreshToken.text.toString().trim())

        updateStatus("Configuration saved (encrypted)")
        Toast.makeText(this, "Configuration saved", Toast.LENGTH_SHORT).show()
    }

    private fun startIngestionService() {
        ConfigStore.setIngestionEnabled(this, true)
        val intent = Intent(this, DashcamIngestionService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        updateStatus("Ingestion service started")
        Toast.makeText(this, "Ingestion service started", Toast.LENGTH_SHORT).show()
    }

    private fun stopIngestionService() {
        ConfigStore.setIngestionEnabled(this, false)
        val intent = Intent(this, DashcamIngestionService::class.java)
        stopService(intent)
        updateStatus("Ingestion service stopped")
        Toast.makeText(this, "Ingestion service stopped", Toast.LENGTH_SHORT).show()
    }

    private fun enqueueUpload() {
        // Validate OAuth config exists
        if (ConfigStore.getOAuthClientId(this).isNullOrBlank()
            || ConfigStore.getOAuthClientSecret(this).isNullOrBlank()
            || ConfigStore.getOAuthRefreshToken(this).isNullOrBlank()) {
            updateStatus("Error: OAuth credentials not configured")
            Toast.makeText(this, "Please configure OAuth credentials first", Toast.LENGTH_LONG).show()
            return
        }

        YouTubeUploadWorker.enqueueUpload(this)
        updateStatus("Upload work enqueued (UNMETERED + charging constraints)")
        Toast.makeText(this, "Upload enqueued", Toast.LENGTH_SHORT).show()
    }

    private fun updateStatus(msg: String) {
        tvStatus.text = "Status: $msg"
    }
}