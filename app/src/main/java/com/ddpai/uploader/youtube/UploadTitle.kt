package com.ddpai.uploader.youtube

import com.ddpai.uploader.data.db.entity.VideoFileEntity
import java.text.SimpleDateFormat
import java.util.Locale

object UploadTitle {
    fun of(entity: VideoFileEntity): String {
        if (entity.kind != "MERGED" || !entity.fileName.startsWith("DRIVE_")) {
            return entity.fileName.removeSuffix(".mp4")
        }
        val stream = when {
            entity.fileName.contains("_F") -> "Front"
            entity.fileName.contains("_R") -> "Rear"
            else -> "Cam"
        }
        val stamp = if (entity.capturedAtEpoch > 0) {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(java.util.Date(entity.capturedAtEpoch))
        } else {
            entity.fileName.removeSuffix(".mp4")
        }
        return "Dashcam $stamp ($stream)"
    }
}
