package com.ddpai.uploader.pipeline

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo

object NotificationHelper {
    const val CHANNEL = "ddpai_pipeline"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "Dashcam Pipeline", NotificationManager.IMPORTANCE_LOW)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun build(context: Context, text: String) =
        NotificationCompat.Builder(context, CHANNEL)
            .setContentTitle("DDPAI Uploader")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()

    fun downloadForegroundInfo(context: Context, text: String): ForegroundInfo {
        val n = build(context, text)
        return if (Build.VERSION.SDK_INT >= 34) {
            ForegroundInfo(1001, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(1001, n)
        }
    }

    fun uploadForegroundInfo(context: Context, text: String): ForegroundInfo {
        val n = build(context, text)
        return if (Build.VERSION.SDK_INT >= 34) {
            ForegroundInfo(1002, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(1002, n)
        }
    }
}
