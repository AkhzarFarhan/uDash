package com.ddpai.uploader.pipeline

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ProgressBus {
    data class Progress(val fileName: String, val current: Long, val total: Long, val kind: String)
    private val _state = MutableStateFlow<Progress?>(null)
    val state: StateFlow<Progress?> = _state.asStateFlow()

    fun updateDownload(name: String, cur: Long, total: Long) {
        _state.value = Progress(name, cur, total, "download")
    }

    fun updateUpload(name: String, cur: Long, total: Long) {
        _state.value = Progress(name, cur, total, "upload")
    }

    fun clear() {
        _state.value = null
    }
}
