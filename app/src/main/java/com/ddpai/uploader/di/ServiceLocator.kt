package com.ddpai.uploader.di

import android.content.Context
import android.net.Network
import com.ddpai.uploader.data.db.AppDatabase
import com.ddpai.uploader.data.config.ConfigRepository
import com.ddpai.uploader.data.repo.FileRepository
import com.ddpai.uploader.data.repo.LogRepository
import com.ddpai.uploader.youtube.YouTubeAuthManager
import com.ddpai.uploader.pipeline.NotificationHelper
import com.ddpai.uploader.pipeline.ProgressBus
import java.io.File

class ServiceLocator private constructor(context: Context) {
    val db = AppDatabase.get(context)
    val log = LogRepository(db.logDao())
    val config = ConfigRepository(context)
    private val storageDir = File(context.getExternalFilesDir(null), "videos").apply { mkdirs() }
    val files = FileRepository(db.videoFileDao(), log, storageDir)
    val auth = YouTubeAuthManager(context, config)
    val notifications = NotificationHelper
    val progress = ProgressBus
    val activeNetworkType = kotlinx.coroutines.flow.MutableStateFlow(com.ddpai.uploader.network.NetworkType.NONE)
    private val ioErrors = kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
        log.e("ServiceLocator", "Background IO task failed: ${e.message}")
    }
    val io = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO + ioErrors
    )

    @Volatile var currentDashcamNetwork: Network? = null

    companion object {
        @Volatile private var I: ServiceLocator? = null
        fun get(context: Context): ServiceLocator =
            I ?: synchronized(this) { I ?: ServiceLocator(context.applicationContext).also { I = it } }
    }
}
