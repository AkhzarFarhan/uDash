package com.ddpai.uploader.ui.logs

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ddpai.uploader.data.db.entity.LogEntity
import com.ddpai.uploader.di.ServiceLocator
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogConsoleViewModel(application: Application) : AndroidViewModel(application) {
    private val sl = ServiceLocator.get(application)
    private val logRepo = sl.log

    private val _filterLevel = MutableStateFlow<String?>(null)
    val filterLevel: StateFlow<String?> = _filterLevel.asStateFlow()

    val logs: StateFlow<List<LogEntity>> = combine(
        logRepo.observe(500),
        _filterLevel
    ) { logList, filter ->
        if (filter == null) {
            logList
        } else {
            logList.filter { it.level == filter }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    fun setFilterLevel(level: String?) {
        _filterLevel.value = level
    }

    fun clearLogs() {
        viewModelScope.launch {
            logRepo.clear()
        }
    }

    fun copyLogsToClipboard() {
        val context = getApplication<Application>().applicationContext
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        val text = logs.value.joinToString("\n") { log ->
            "${sdf.format(Date(log.timestamp))} [${log.level}] ${log.tag}: ${log.message}"
        }
        val clip = ClipData.newPlainText("uDash Logs", text)
        clipboard.setPrimaryClip(clip)
        logRepo.i("LogConsoleVM", "Copied logs to clipboard")
    }
}
