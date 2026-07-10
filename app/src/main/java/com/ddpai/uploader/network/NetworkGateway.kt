package com.ddpai.uploader.network

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import java.net.Inet4Address

/** Extracts the IPv4 default-route gateway (dotted string) for a given network link. */
object NetworkGateway {
    fun extract(cm: ConnectivityManager, network: Network): String? {
        val lp: LinkProperties = cm.getLinkProperties(network) ?: return null
        lp.routes.forEach { r ->
            val gw = r.gateway
            if (r.isDefaultRoute && gw is Inet4Address) {
                return gw.hostAddress
            }
        }
        return null
    }
}
