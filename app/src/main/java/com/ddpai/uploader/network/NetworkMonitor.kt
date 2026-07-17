package com.ddpai.uploader.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.ddpai.uploader.data.config.ConfigRepository
import com.ddpai.uploader.data.repo.LogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Request
import java.util.concurrent.TimeUnit

class NetworkMonitor(
    private val context: Context,
    private val configRepo: ConfigRepository,
    private val logger: LogRepository,
    private val onNetwork: (NetworkType, Network?) -> Unit
) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = evaluate(network)
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = evaluate(network)
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = evaluate(network)
            override fun onLost(network: Network) {
                logger.i("NetworkMonitor", "Wi-Fi network lost")
                onNetwork(NetworkType.NONE, null)
            }
        }
        callback = cb
        cm.registerNetworkCallback(request, cb)
    }

    fun stop() {
        callback?.let { cm.unregisterNetworkCallback(it) }
        callback = null
    }

    private fun evaluate(network: Network) {
        val caps = cm.getNetworkCapabilities(network) ?: return
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            onNetwork(NetworkType.OTHER, null)
            return
        }
        val gatewayConfigured = configRepo.config.value.dashcamGateway
        val homeGatewayConfigured = configRepo.config.value.homeWifiGateway
        val gatewayIp = NetworkGateway.extract(cm, network, context)
        logger.d("NetworkMonitor", "evaluate: gatewayExtracted=$gatewayIp, gatewayConfigured=$gatewayConfigured, homeGatewayConfigured=$homeGatewayConfigured")

        when {
            gatewayIp == gatewayConfigured -> {
                logger.i("NetworkMonitor", "Dashcam AP confirmed via gateway match ($gatewayIp)")
                onNetwork(NetworkType.DASHCAM_AP, network)
            }
            homeGatewayConfigured.isNotBlank() && gatewayIp == homeGatewayConfigured -> {
                logger.i("NetworkMonitor", "Home Wi-Fi confirmed via gateway match ($gatewayIp)")
                onNetwork(NetworkType.HOME_WIFI, network)
            }
            homeGatewayConfigured.isBlank() && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> {
                logger.d("NetworkMonitor", "Classified as HOME_WIFI (validated, gateway=$gatewayIp)")
                onNetwork(NetworkType.HOME_WIFI, network)
            }
            else -> {
                // Gateway extraction returned null or mismatched, AND this WiFi has no internet.
                // This is likely the dashcam AP where gateway extraction failed (no default route,
                // DHCP server address unavailable on this API level, WifiManager fallback failed).
                // Attempt a quick HTTP probe to confirm before giving up.
                logger.w("NetworkMonitor",
                    "Unvalidated Wi-Fi, gateway=$gatewayIp ≠ $gatewayConfigured → probing $gatewayConfigured")
                scope.launch {
                    val isDashcam = probeDashcam(network, gatewayConfigured)
                    if (isDashcam) {
                        logger.i("NetworkMonitor", "Dashcam AP confirmed via HTTP probe to $gatewayConfigured")
                        onNetwork(NetworkType.DASHCAM_AP, network)
                    } else {
                        logger.d("NetworkMonitor", "HTTP probe to $gatewayConfigured failed; classified as OTHER")
                        onNetwork(NetworkType.OTHER, null)
                    }
                }
            }
        }
    }

    /**
     * Quick connectivity probe: attempts a short HTTP GET to the dashcam gateway.
     * If the gateway responds at all (any HTTP status), it's the dashcam.
     */
    private fun probeDashcam(network: Network, gateway: String): Boolean {
        return try {
            val client = BoundHttpClientFactory.forNetwork(network, callTimeoutSec = 5)
            val req = Request.Builder()
                .url("http://$gateway/")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                logger.d("NetworkMonitor", "Probe http://$gateway/ → HTTP ${resp.code}")
                true  // Any response means the dashcam is there
            }
        } catch (e: Exception) {
            logger.d("NetworkMonitor", "Probe http://$gateway/ failed: ${e.message}")
            false
        }
    }
}
