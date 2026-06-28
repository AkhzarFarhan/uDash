package com.ddpai.uploader.ui.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.ddpai.uploader.di.ServiceLocator

class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val sl = ServiceLocator.get(application)
    private val filesRepo = sl.files

    suspend fun localPathFor(fileName: String): String? {
        return filesRepo.get(fileName)?.localPath
    }
}
