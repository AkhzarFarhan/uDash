package com.utility.dashcam.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.utility.dashcam.service.DashcamIngestionService

/**
 * Worker that runs temporarily in the foreground to transition the app into a foreground state,
 * bypassing Android 12+ background start restrictions to launch DashcamIngestionService.
 */
class DashcamIngestionStartWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val CHANNEL_ID = "dashcam_ingestion_channel"
    private val NOTIFICATION_ID = 1001

    override suspend fun doWork(): Result {
        val serviceIntent = Intent(applicationContext, DashcamIngestionService::class.java)
        
        try {
            // Put the worker into foreground state to gain FGS start privilege
            setForeground(getForegroundInfo())
            
            // Now start the DashcamIngestionService. This is allowed because the app is currently
            // running a foreground component (this worker).
            ContextCompat.startForegroundService(applicationContext, serviceIntent)
        } catch (e: Exception) {
            e.printStackTrace()
            return Result.failure()
        }
        
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        createNotificationChannel()
        
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Dashcam Ingestion Active")
            .setContentText("Initializing ingestion service...")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Dashcam Ingestion Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
