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
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val db = com.ddpai.uploader.data.db.AppDatabase.get(this)
                val logDao = db.logDao()
                val logEntity = com.ddpai.uploader.data.db.entity.LogEntity(
                    level = com.ddpai.uploader.data.model.LogLevel.ERROR.name,
                    tag = "CRASH",
                    message = "Uncaught Exception in thread ${thread.name}: ${throwable.message}\n${throwable.stackTraceToString()}"
                )
                val t = Thread {
                    kotlinx.coroutines.runBlocking {
                        try {
                            logDao.insert(logEntity)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
                t.start()
                t.join(2000)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }

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
