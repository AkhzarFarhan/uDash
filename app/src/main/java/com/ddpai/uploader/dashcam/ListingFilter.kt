package com.ddpai.uploader.dashcam

import com.ddpai.uploader.data.model.DashcamFile

/**
 * The dashcam is always writing the newest segment; it never has a moov atom yet. Drop the single
 * newest file (by capture time) from a listing so we do not repeatedly fail-and-retry an in-progress
 * recording. It reappears — complete — on the next scan.
 */
object ListingFilter {
    fun excludeNewest(files: List<DashcamFile>): List<DashcamFile> {
        if (files.isEmpty()) return files
        val newest = files.maxByOrNull { it.capturedAtEpoch } ?: return files
        return files.filter { it.fileName != newest.fileName }
    }
}
