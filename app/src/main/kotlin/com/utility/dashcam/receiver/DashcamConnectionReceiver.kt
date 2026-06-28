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

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val prefix = ConfigStore.getDashcamSsidPrefix(context)
        val configuredIp = ConfigStore.getDashcamIp(context)

        var isConnected = false
        val info = wifiManager.connectionInfo
        val ssid = info?.ssid?.replace("\"", "")

        if (ssid != null && ssid != "<unknown ssid>" && ssid.isNotBlank()) {
            if (ssid.startsWith(prefix, ignoreCase = true)) {
                isConnected = true
            }
        } else {
            // Fallback: Check DHCP gateway IP (works in background without location permissions)
            val dhcpInfo = wifiManager.dhcpInfo
            val gatewayIp = intToIp(dhcpInfo?.gateway ?: 0)
            if (gatewayIp == configuredIp && gatewayIp != "0.0.0.0") {
                isConnected = true
            }
        }

        if (isConnected) {
            val request = androidx.work.OneTimeWorkRequest.Builder(
                com.utility.dashcam.worker.DashcamIngestionStartWorker::class.java
            ).build()
            androidx.work.WorkManager.getInstance(context).enqueue(request)
        }
    }

    private fun intToIp(i: Int): String {
        return (i and 0xFF).toString() + "." +
               ((i shr 8) and 0xFF) + "." +
               ((i shr 16) and 0xFF) + "." +
               ((i shr 24) and 0xFF)
    }
}