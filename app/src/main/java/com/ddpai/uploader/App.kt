package com.ddpai.uploader

import android.app.Application
import com.ddpai.uploader.di.ServiceLocator
import com.ddpai.uploader.pipeline.NotificationHelper
import com.ddpai.uploader.pipeline.PipelineScheduler
import com.ddpai.uploader.pipeline.SyncController
import com.ddpai.uploader.pipeline.WatcherService

class App : Application() {
    lateinit var syncController: SyncController

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)
        val sl = ServiceLocator.get(this)
        syncController = SyncController(this, sl)
        syncController.start()

        when (sl.config.config.value.syncMode) {
            "BATTERY_SAVER" -> PipelineScheduler.enablePeriodicChecks(this)
            else -> WatcherService.start(this)
        }
    }
}
