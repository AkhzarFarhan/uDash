package com.ddpai.uploader.ui.files

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ddpai.uploader.data.db.entity.VideoFileEntity
import com.ddpai.uploader.data.model.FileStatus
import com.ddpai.uploader.di.ServiceLocator
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FileListViewModel(application: Application) : AndroidViewModel(application) {
    private val sl = ServiceLocator.get(application)
    private val filesRepo = sl.files

    val files: StateFlow<List<VideoFileEntity>> = filesRepo.observeAll().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    fun deleteLocal(fileName: String) {
        viewModelScope.launch {
            val entity = filesRepo.get(fileName) ?: return@launch
            filesRepo.fileFor(fileName).delete()
            filesRepo.update(
                entity.copy(
                    localPath = null,
                    status = FileStatus.DISCOVERED.name,
                    downloadedBytes = 0
                )
            )
            sl.log.i("FileListVM", "Deleted local file manually: $fileName")
        }
    }

    fun retryFile(fileName: String) {
        viewModelScope.launch {
            val entity = filesRepo.get(fileName) ?: return@launch
            val newStatus = if (entity.status == FileStatus.FAILED.name) {
                if (entity.localPath != null && filesRepo.fileFor(fileName).exists()) {
                    FileStatus.DOWNLOADED.name
                } else {
                    FileStatus.PENDING.name
                }
            } else {
                FileStatus.PENDING.name
            }
            filesRepo.update(entity.copy(status = newStatus, retryCount = 0, errorMessage = null))
            sl.log.i("FileListVM", "Manual retry triggered for $fileName (new status: $newStatus)")
        }
    }
}
