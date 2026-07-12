package com.ddpai.uploader.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "video_files")
data class VideoFileEntity(
    @PrimaryKey val fileName: String,
    val remoteUrl: String,
    val localPath: String?,
    val status: String,
    val sizeBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val youtubeVideoId: String? = null,
    val uploadSessionUrl: String? = null,
    val retryCount: Int = 0,
    val errorMessage: String? = null,
    val capturedAtEpoch: Long = 0L,
    @ColumnInfo(defaultValue = "SEGMENT") val kind: String = "SEGMENT",  // SEGMENT | MERGED
    val mergedInto: String? = null,      // for consumed segments: the merged output's fileName
    val discoveredAtEpoch: Long = System.currentTimeMillis(),
    val updatedAtEpoch: Long = System.currentTimeMillis()
)
