package com.ddpai.uploader.pipeline

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.ddpai.uploader.di.ServiceLocator
import com.ddpai.uploader.network.DashcamNetworkResolver
import com.ddpai.uploader.network.NetworkMonitor
import com.ddpai.uploader.network.NetworkType

/** Central place that classifies the active network and enqueues the right pipeline work. */
class SyncController(private val context: Context, private val sl: ServiceLocator) {
    private var lastType: NetworkType? = null
    private val monitor = NetworkMonitor(context, sl.config) { type, network -> onNetwork(type, network) }

    fun start() = monitor.start()

    fun onNetwork(type: NetworkType, network: Network?) {
        sl.activeNetworkType.value = type
        if (type == lastType) return   // debounce: only act on transitions
        lastType = type
        when (type) {
            NetworkType.DASHCAM_AP -> {
                sl.currentDashcamNetwork = network
                sl.log.i("SyncController", "Dashcam AP detected")
                if (sl.config.config.value.wifiAutoStart) PipelineScheduler.enqueueDownload(context)
            }
            NetworkType.HOME_WIFI -> {
                sl.currentDashcamNetwork = null
                sl.log.i("SyncController", "Home Wi-Fi detected")
                if (sl.config.config.value.wifiAutoStart) PipelineScheduler.enqueueMergeThenUpload(context)
            }
            else -> sl.currentDashcamNetwork = null
        }
    }

    /** Used by SyncCheckWorker (battery-saver): classify current network without a callback. */
    fun classifyAndEnqueue() {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val gateway = sl.config.config.value.dashcamGateway
        val dashcam = DashcamNetworkResolver(cm, context).resolve(gateway)
        if (dashcam != null) {
            sl.currentDashcamNetwork = dashcam
            if (sl.config.config.value.wifiAutoStart) PipelineScheduler.enqueueDownload(context)
            return
        }
        val active = cm.activeNetwork ?: return
        val caps = cm.getNetworkCapabilities(active) ?: return
        val validated = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val wifi = caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
        if (wifi && validated && sl.config.config.value.wifiAutoStart) {
            PipelineScheduler.enqueueMergeThenUpload(context)
        }
    }
}
