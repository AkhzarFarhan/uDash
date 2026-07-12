package com.ddpai.uploader.pipeline

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ddpai.uploader.di.ServiceLocator

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val sl = ServiceLocator.get(context)
        when (sl.config.config.value.syncMode) {
            "BATTERY_SAVER" -> PipelineScheduler.enablePeriodicChecks(context)
            else -> WatcherService.start(context)
        }
    }
}
