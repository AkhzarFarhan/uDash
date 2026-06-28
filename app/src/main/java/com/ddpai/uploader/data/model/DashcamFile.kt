package com.ddpai.uploader.data.model

data class DashcamFile(
    val fileName: String,
    val sizeBytes: Long,
    val capturedAtEpoch: Long
)
