package com.ddpai.uploader.data.repo

import com.ddpai.uploader.data.db.LogDao
import com.ddpai.uploader.data.db.entity.LogEntity
import com.ddpai.uploader.data.model.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

class LogRepository(private val dao: LogDao) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val insertCount = AtomicInteger(0)

    fun observe(limit: Int = 500) = dao.observeRecent(limit)

    private fun log(level: LogLevel, tag: String, msg: String, file: String? = null) {
        try {
            when (level) {
                LogLevel.DEBUG -> android.util.Log.d(tag, msg)
                LogLevel.INFO -> android.util.Log.i(tag, msg)
                LogLevel.WARN -> android.util.Log.w(tag, msg)
                LogLevel.ERROR -> android.util.Log.e(tag, msg)
                LogLevel.EXTREME -> android.util.Log.v(tag, "[EXTREME] $msg")
            }
        } catch (t: Throwable) {
            println("[$level] $tag: $msg")
        }
        scope.launch {
            try {
                dao.insert(LogEntity(level = level.name, tag = tag, message = msg, fileName = file))
                if (insertCount.incrementAndGet() % 100 == 0) {
                    dao.trimTo(5000)
                }
            } catch (t: Throwable) {
                println("[LOG_DB_ERROR] Failed to persist log: ${t.message}")
            }
        }
    }

    fun d(tag: String, m: String, f: String? = null) = log(LogLevel.DEBUG, tag, m, f)
    fun i(tag: String, m: String, f: String? = null) = log(LogLevel.INFO, tag, m, f)
    fun w(tag: String, m: String, f: String? = null) = log(LogLevel.WARN, tag, m, f)
    fun e(tag: String, m: String, f: String? = null) = log(LogLevel.ERROR, tag, m, f)
    fun x(tag: String, m: String, f: String? = null) = log(LogLevel.EXTREME, tag, m, f)

    suspend fun clear() {
        try {
            dao.clear()
        } catch (t: Throwable) {
            println("[LOG_DB_ERROR] Failed to clear logs: ${t.message}")
        }
    }
}
