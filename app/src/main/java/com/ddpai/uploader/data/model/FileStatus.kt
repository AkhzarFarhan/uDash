package com.ddpai.uploader.data.model

enum class FileStatus {
    DISCOVERED,   // seen in dashcam listing, not yet downloaded
    DOWNLOADING,  // download in progress
    DOWNLOADED,   // on disk, integrity-verified, awaiting upload
    UPLOADING,    // upload in progress
    UPLOADED,     // uploaded to YouTube; local file deleted
    MERGED,       // segment consumed into a merged drive file; local segment deleted
    PENDING,      // reset state after a recoverable failure (re-attempt download)
    FAILED        // permanent failure after max retries (visible to user)
}
