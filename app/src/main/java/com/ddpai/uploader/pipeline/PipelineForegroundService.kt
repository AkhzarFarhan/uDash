package com.ddpai.uploader.pipeline

import android.app.Service
import android.content.Intent
import android.os.IBinder

class PipelineForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
