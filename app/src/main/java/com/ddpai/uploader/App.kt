package com.ddpai.uploader

import android.app.Application
import com.ddpai.uploader.network.NetworkMonitor
import com.ddpai.uploader.network.NetworkType
import com.ddpai.uploader.di.ServiceLocator
import com.ddpai.uploader.pipeline.PipelineScheduler
import com.ddpai.uploader.pipeline.NotificationHelper

class App : Application() {
    lateinit var networkMonitor: NetworkMonitor

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)
        val sl = ServiceLocator.get(this)
        networkMonitor = NetworkMonitor(this, sl.config) { type, network ->
            sl.activeNetworkType.value = type
            when (type) {
                NetworkType.DASHCAM_AP -> {
                    sl.currentDashcamNetwork = network
                    sl.log.i("App", "Dashcam AP detected; enqueue download")
                    if (sl.config.config.value.wifiAutoStart) {
                        PipelineScheduler.enqueueDownload(this)
                    }
                }
                NetworkType.HOME_WIFI -> {
                    sl.currentDashcamNetwork = null
                    sl.log.i("App", "Home Wi-Fi detected; enqueue upload")
                    if (sl.config.config.value.wifiAutoStart) {
                        PipelineScheduler.enqueueUpload(this)
                    }
                }
                else -> {
                    sl.currentDashcamNetwork = null
                }
            }
        }
        networkMonitor.start()
    }
}
