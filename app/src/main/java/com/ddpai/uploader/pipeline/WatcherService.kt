package com.ddpai.uploader.pipeline

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** Persistent foreground service that keeps the network callback alive so drives are never missed. */
class WatcherService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, NotificationHelper.CHANNEL)
            .setContentTitle("uDash")
            .setContentText("Watching for dashcam Wi-Fi")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, notification)
            }
            START_STICKY
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException when the process was started in the
            // background. Degrade gracefully instead of crashing; sync still runs via WorkManager triggers.
            stopSelf()
            START_NOT_STICKY
        }
    }

    companion object {
        private const val NOTIF_ID = 2001

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, WatcherService::class.java))
            } catch (e: Exception) {
                // Background foreground-service start disallowed (Android 12+): skip; the watcher will
                // start next time the app is opened, and WorkManager triggers keep sync working meanwhile.
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, WatcherService::class.java))
            } catch (e: Exception) {
                // ignore
            }
        }
    }
}
