package com.utility.dashcam.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Tracks individual 1-minute video segments discovered on the dashcam file system.
 */
@Entity(
    tableName = "raw_clips",
    indices = [Index(value = ["dateString"]), Index(value = ["downloadStatus"])]
)
data class RawClipEntity(
    @PrimaryKey val fileName: String,      // Format: "YYYYMMDD_HHMMSS_F.mp4"
    val dateString: String,                // Format: "YYYY-MM-DD" (Logical Partition)
    val remoteUrl: String,                 // Example: "http://193.168.0.1/sd/normal/..."
    val localFilePath: String?,            // Path inside app's internal cache directory
    val fileSize: Long,                    // Byte length for storage verification
    val downloadStatus: String             // Enums: PENDING, DOWNLOADING, COMPLETED, FAILED
)

object DownloadStatus {
    const val PENDING = "PENDING"
    const val DOWNLOADING = "DOWNLOADING"
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
}
