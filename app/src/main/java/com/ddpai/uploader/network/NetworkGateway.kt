package com.ddpai.uploader.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import java.net.Inet4Address
import java.util.Locale

/**
 * Extracts the IPv4 gateway (dotted string) for [network]. Restores the original NetworkMonitor's
 * dual detection and adds a per-network improvement:
 *  1. Default-route gateway from LinkProperties (present on most APs, including no-internet ones).
 *  2. (API 30+) LinkProperties.dhcpServerAddress — for the dashcam the DHCP server IP *is* the
 *     gateway (193.168.0.1). Read from THIS network's own link, so it is correct even when the
 *     dashcam Wi-Fi is not the system default (e.g. cellular active in a vehicle) and can never be
 *     misattributed while iterating multiple networks.
 *  3. Legacy WifiManager.dhcpInfo gateway (the original fallback). This is Wi-Fi-specific — it
 *     reflects the phone's Wi-Fi connection regardless of which network is the system default — so
 *     it also works in-vehicle. Requires a [context].
 */
object NetworkGateway {
    fun extract(cm: ConnectivityManager, network: Network, context: Context? = null): String? {
        val lp = cm.getLinkProperties(network)
        lp?.routes?.forEach { r ->
            val gw = r.gateway
            if (r.isDefaultRoute && gw is Inet4Address) {
                return gw.hostAddress
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val dhcpServer = lp?.dhcpServerAddress
            if (dhcpServer is Inet4Address && !dhcpServer.isAnyLocalAddress) {
                return dhcpServer.hostAddress
            }
        }
        if (context != null) {
            try {
                val wifi = context.getSystemService(WifiManager::class.java)
                @Suppress("DEPRECATION")
                val g = wifi?.dhcpInfo?.gateway ?: return null
                if (g != 0) {
                    return String.format(
                        Locale.US, "%d.%d.%d.%d",
                        g and 0xff, g shr 8 and 0xff, g shr 16 and 0xff, g shr 24 and 0xff
                    )
                }
            } catch (e: Exception) {
                return null
            }
        }
        return null
    }
}
