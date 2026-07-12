package com.ddpai.uploader.ui.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ddpai.uploader.data.db.entity.LogEntity
import com.ddpai.uploader.data.model.FileStatus
import com.ddpai.uploader.di.ServiceLocator
import com.ddpai.uploader.network.NetworkType
import com.ddpai.uploader.pipeline.PipelineScheduler
import com.ddpai.uploader.pipeline.ProgressBus
import kotlinx.coroutines.flow.*

data class DashUiState(
    val networkType: NetworkType = NetworkType.NONE,
    val progress: ProgressBus.Progress? = null,
    val discovered: Int = 0,
    val downloaded: Int = 0,
    val uploading: Int = 0,
    val uploaded: Int = 0,
    val failed: Int = 0,
    val isAuthorized: Boolean = false,
    val isConfigured: Boolean = false,
    val recentLogs: List<LogEntity> = emptyList(),
    val quotaPausedUntil: Long = 0L,
    val needsReauth: Boolean = false,
    val merged: Int = 0,
    val syncMode: String = "PERSISTENT"
)

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val sl = ServiceLocator.get(application)

    val uiState: StateFlow<DashUiState> = combine(
        sl.activeNetworkType,
        sl.progress.state,
        sl.files.observeCount(FileStatus.DISCOVERED),
        sl.files.observeCount(FileStatus.DOWNLOADED),
        sl.files.observeCount(FileStatus.UPLOADING),
        sl.files.observeCount(FileStatus.UPLOADED),
        sl.files.observeCount(FileStatus.FAILED),
        sl.log.observe(8),
        sl.config.runtimeState,
        sl.files.observeCount(FileStatus.MERGED)
    ) { args ->
        val networkType = args[0] as NetworkType
        val progress = args[1] as ProgressBus.Progress?
        val discovered = args[2] as Int
        val downloaded = args[3] as Int
        val uploading = args[4] as Int
        val uploaded = args[5] as Int
        val failed = args[6] as Int
        @Suppress("UNCHECKED_CAST")
        val recentLogs = args[7] as List<LogEntity>
        val runtime = args[8] as com.ddpai.uploader.data.config.ConfigRepository.RuntimeState
        val merged = args[9] as Int

        DashUiState(
            networkType = networkType,
            progress = progress,
            discovered = discovered,
            downloaded = downloaded,
            uploading = uploading,
            uploaded = uploaded,
            failed = failed,
            isAuthorized = sl.auth.isAuthorized(),
            isConfigured = sl.config.isConfigured(),
            recentLogs = recentLogs,
            quotaPausedUntil = runtime.quotaPausedUntil,
            needsReauth = runtime.needsReauth,
            merged = merged,
            syncMode = sl.config.config.value.syncMode
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DashUiState()
    )

    fun scanNow() {
        PipelineScheduler.enqueueDownload(getApplication())
    }

    fun uploadNow() {
        PipelineScheduler.enqueueMergeThenUpload(getApplication())
    }
}
