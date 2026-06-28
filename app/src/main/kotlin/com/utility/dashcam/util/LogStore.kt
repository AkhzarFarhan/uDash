package com.utility.dashcam.util

import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object LogStore {
    private val telegramScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient()
    private val logs = mutableListOf<String>()
    private var listener: ((String) -> Unit)? = null

    private fun getLogFile(context: Context): File {
        var logDir = context.applicationContext.getExternalFilesDir(null) ?: context.applicationContext.filesDir
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    logDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                }
            } else {
                val hasPermission = ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                if (hasPermission) {
                    logDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return File(logDir, "udash_pipeline_logs.txt")
    }

    fun log(context: Context, tag: String, message: String, isError: Boolean = false) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(java.util.Date())
        val formattedLine = "[$timestamp] [$tag] $message"
        
        synchronized(logs) {
            logs.add(formattedLine)
            if (logs.size > 1000) logs.removeAt(0)
        }
        
        val fullLogs = getFormattedLogs()
        listener?.invoke(fullLogs)
        
        // Save to SharedPreferences so it persists across recreations
        val prefs = context.applicationContext.getSharedPreferences("udash_logs", Context.MODE_PRIVATE)
        prefs.edit().putString("console_logs", fullLogs).apply()

        // Append to the local persistent log file on the device
        try {
            val logFile = getLogFile(context)
            if (logFile.exists() && logFile.length() > 5 * 1024 * 1024) { // 5MB limit
                logFile.writeText("--- Log truncated due to size limit ---\n")
            }
            val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(java.util.Date())
            val fileLine = "[$dateTimeFormat] [$tag] $message"
            logFile.appendText(fileLine + "\n")
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Send log line to Telegram asynchronously
        telegramScope.launch {
            try {
                val urlencoded = java.net.URLEncoder.encode(formattedLine, "UTF-8")
                val url = "https://api.telegram.org/bot<BOT_ID>/sendMessage?chat_id=-<CHAT_ID>&text=$urlencoded"
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    response.body?.string()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun getFormattedLogs(): String {
        return synchronized(logs) { logs.joinToString("\n") }
    }

    fun loadLogs(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences("udash_logs", Context.MODE_PRIVATE)
        val loaded = prefs.getString("console_logs", "") ?: ""
        synchronized(logs) {
            logs.clear()
            if (loaded.isNotBlank()) {
                logs.addAll(loaded.split("\n"))
            }
        }
        return loaded
    }

    fun clear(context: Context) {
        synchronized(logs) { logs.clear() }
        context.applicationContext.getSharedPreferences("udash_logs", Context.MODE_PRIVATE).edit().clear().apply()
        
        // Also clear the persistent log file
        try {
            val logFile = getLogFile(context)
            if (logFile.exists()) {
                logFile.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        listener?.invoke("")
    }

    fun setListener(l: ((String) -> Unit)?) {
        listener = l
    }
}
