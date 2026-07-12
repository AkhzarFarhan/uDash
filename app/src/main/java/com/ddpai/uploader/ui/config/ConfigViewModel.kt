package com.ddpai.uploader.ui.config

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ddpai.uploader.data.config.AppConfig
import com.ddpai.uploader.di.ServiceLocator
import com.ddpai.uploader.network.BoundHttpClientFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

class ConfigViewModel(application: Application) : AndroidViewModel(application) {
    private val sl = ServiceLocator.get(application)
    val packageName: String = com.ddpai.uploader.util.SigningInfo.packageName(application)
    val signingSha1: String = com.ddpai.uploader.util.SigningInfo.signingSha1(application)
    private val repo = sl.config
    val auth = sl.auth
    private val logger = sl.log

    val config = repo.config

    private val _isAuthorized = MutableStateFlow(auth.isAuthorized())
    val isAuthorized: StateFlow<Boolean> = _isAuthorized.asStateFlow()

    private val _testStatus = MutableStateFlow<String?>(null)
    val testStatus: StateFlow<String?> = _testStatus.asStateFlow()

    fun save(newConfig: AppConfig) {
        repo.save(newConfig)
    }

    fun handleAuthResponse(data: android.content.Intent) {
        viewModelScope.launch {
            val success = auth.handleAuthResponse(data)
            _isAuthorized.value = success
            if (success) {
                logger.i("ConfigVM", "YouTube Authorization Successful")
            } else {
                logger.e("ConfigVM", "YouTube Authorization Failed")
            }
        }
    }

    fun signOut() {
        auth.signOut()
        _isAuthorized.value = false
        logger.i("ConfigVM", "Signed out from YouTube")
    }

    fun applySyncMode(mode: String) {
        val app = getApplication<Application>()
        repo.save(config.value.copy(syncMode = mode))
        if (mode == "BATTERY_SAVER") {
            com.ddpai.uploader.pipeline.WatcherService.stop(app)
            com.ddpai.uploader.pipeline.PipelineScheduler.enablePeriodicChecks(app)
        } else {
            com.ddpai.uploader.pipeline.PipelineScheduler.disablePeriodicChecks(app)
            com.ddpai.uploader.pipeline.WatcherService.start(app)
        }
        logger.i("ConfigVM", "Sync mode → $mode")
    }

    fun testConnection() {
        viewModelScope.launch {
            _testStatus.value = "Testing..."
            val network = sl.currentDashcamNetwork
            if (network == null) {
                _testStatus.value = "Fail: No dashcam network bound (make sure you are connected to the dashcam Wi-Fi)"
                return@launch
            }
            val gateway = config.value.dashcamGateway
            val base = "http://$gateway"
            val client = BoundHttpClientFactory.forNetwork(network)
            val req = Request.Builder().url("$base/vcam/cmd.cgi?cmd=getFileList").get().build()
            withContext(Dispatchers.IO) {
                try {
                    client.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            _testStatus.value = "Success! Dashcam listing reachable."
                            logger.i("ConfigVM", "Connection test: Success")
                        } else {
                            _testStatus.value = "Fail: HTTP ${resp.code}"
                            logger.w("ConfigVM", "Connection test failed: HTTP ${resp.code}")
                        }
                    }
                } catch (e: Exception) {
                    _testStatus.value = "Fail: ${e.message}"
                    logger.e("ConfigVM", "Connection test error: ${e.message}")
                }
            }
        }
    }
}
