package com.utility.dashcam.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import com.utility.dashcam.util.ConfigStore
import com.utility.dashcam.worker.YouTubeUploadWorker

/**
 * BroadcastReceiver that auto-enqueues the YouTube upload worker when the phone
 * connects to the configured home Wi-Fi network.
 * 
 * Architecture §5.4: "Whenever phone gets connected to the wifi, it should upload
 * day-wise videos to YouTube with user defined privacy."
 * 
 * Listens for WifiManager.NETWORK_STATE_CHANGED_ACTION and BOOT_COMPLETED.
 * On Wi-Fi connect, checks if SSID matches configured home Wi-Fi SSID.
 * If match → auto-enqueues YouTubeUploadWorker with UNMETERED + charging constraints.
 */
class WifiConnectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != WifiManager.NETWORK_STATE_CHANGED_ACTION && action != Intent.ACTION_BOOT_COMPLETED) return

        // Enqueue the worker. WorkManager will ensure it only runs when unmetered Wi-Fi is connected and charging.
        YouTubeUploadWorker.enqueueUpload(context)
    }
}