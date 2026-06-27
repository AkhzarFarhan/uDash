package com.utility.dashcam.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import com.utility.dashcam.service.DashcamIngestionService
import com.utility.dashcam.util.ConfigStore

/**
 * BroadcastReceiver that auto-starts the ingestion service when the phone
 * connects to the dashcam's Wi-Fi Access Point.
 * 
 * Architecture §5.1: "Ingestion should be automatically started upon connection
 * with car's dash camera. The connection detection should happen automatically."
 * 
 * Listens for WifiManager.NETWORK_STATE_CHANGED_ACTION and BOOT_COMPLETED.
 * On Wi-Fi connect, checks if SSID matches configured dashcam prefix.
 * If match → auto-starts DashcamIngestionService as foreground service.
 */
class DashcamConnectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != WifiManager.NETWORK_STATE_CHANGED_ACTION && action != Intent.ACTION_BOOT_COMPLETED) return

        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = wifiManager.connectionInfo
        val ssid = info.ssid?.replace("\"", "") ?: return
        if ("<unknown ssid>" == ssid) return
        val prefix = ConfigStore.getDashcamSsidPrefix(context)
        if (prefix.isBlank()) return
        if (ssid.startsWith(prefix, ignoreCase = true)) {
            val serviceIntent = Intent(context, DashcamIngestionService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }
}