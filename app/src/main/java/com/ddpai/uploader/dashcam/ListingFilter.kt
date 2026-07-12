package com.ddpai.uploader.dashcam

import com.ddpai.uploader.data.model.DashcamFile

/**
 * The dashcam is always writing the newest segment of each active stream (front `_F`, rear `_R`, or
 * combined `_0060`); those in-progress files have no moov atom yet. Drop the newest file of EACH
 * stream from a listing so we do not repeatedly fail-and-retry an in-progress recording — each
 * reappears, complete, on the next scan. A dual-camera dashcam cuts a front and a rear segment with
 * the same capture time, so both must be excluded, not just one.
 */
object ListingFilter {
    private val STREAM_RE = Regex("""_(0060|F|R)\.mp4$""", RegexOption.IGNORE_CASE)

    fun excludeNewest(files: List<DashcamFile>): List<DashcamFile> {
        if (files.isEmpty()) return files
        val newest = files
            .groupBy { streamKeyOf(it.fileName) }
            .values
            .mapNotNull { group -> group.maxByOrNull { it.capturedAtEpoch }?.fileName }
            .toSet()
        return files.filter { it.fileName !in newest }
    }

    private fun streamKeyOf(fileName: String): String =
        STREAM_RE.find(fileName)?.groupValues?.get(1)?.uppercase() ?: fileName
}
