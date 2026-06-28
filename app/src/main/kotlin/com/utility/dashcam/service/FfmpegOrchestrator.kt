package com.utility.dashcam.service

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.utility.dashcam.data.local.AppDatabase
import com.utility.dashcam.data.local.RawClipEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Orchestrates the concatenation of raw video clips using FFmpegKit's stream-copy demuxer.
 * Merges chunks of clips sequentially and deletes raw clips from storage upon success.
 */
class FfmpegOrchestrator(
    private val context: Context,
    private val database: AppDatabase
) {

    /**
     * Process daily merge for a given mergeId and its completed clips.
     * Returns when the FFmpeg operation completes (success or failure).
     */
    suspend fun processDailyMerge(mergeId: String, dateStr: String, clips: List<RawClipEntity>) = withContext(Dispatchers.IO) {
        // 1. Generate Manifest (filelist.txt) with absolute paths
        val manifestFile = File(context.cacheDir, "manifest_$mergeId.txt")
        manifestFile.bufferedWriter().use { writer ->
            clips.forEach { clip ->
                clip.localFilePath?.let { path ->
                    // FFmpeg concat demuxer requires single-quoted absolute paths
                    writer.write("file '$path'\n")
                }
            }
        }

        // 2. Prepare Output file in filesDir
        val outputFile = File(context.filesDir, "merge_$mergeId.mp4")

        // 3. Execute FFmpeg Command (stream-copy concat)
        val cmd = "-f concat -safe 0 -i ${manifestFile.absolutePath} -c copy -y ${outputFile.absolutePath}"

        var errorMsg = ""
        // Bridge callback to coroutine using CompletableDeferred
        val deferred = CompletableDeferred<Boolean>()

        FFmpegKit.executeAsync(cmd) { session ->
            val returnCode = session.returnCode
            if (ReturnCode.isSuccess(returnCode)) {
                deferred.complete(true)
            } else {
                // Log FFmpeg output for diagnostics
                val output = session.output ?: "no output"
                errorMsg = output
                System.err.println("FFmpeg concat failed for $mergeId: $output")
                deferred.complete(false)
            }
        }

        val success = deferred.await()

        // 4. Clean up raw files and save state in Room DB
        if (success) {
            // Delete raw local cached files to prevent storage leak
            clips.forEach { clip ->
                clip.localFilePath?.let { path ->
                    val file = File(path)
                    if (file.exists()) {
                        file.delete()
                    }
                }
            }
            database.dailyMergeDao().completeMergeAndPurgeRaw(
                mergeId = mergeId,
                dateString = dateStr,
                mergedPath = outputFile.absolutePath,
                totalSize = outputFile.length()
            )
            com.utility.dashcam.util.ConfigStore.setLastError(context, null) // Clear error on success
        } else {
            database.dailyMergeDao().markMergeFailed(mergeId)
            com.utility.dashcam.util.ConfigStore.setLastError(context, "Merge $mergeId failed: $errorMsg")
        }
        manifestFile.delete() // Clean up manifest file
    }

    /**
     * Returns true if dateStr is a historical (closed) date, false if it's today.
     */
    fun isHistoricalDate(dateStr: String): Boolean {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val currentDateStr = sdf.format(java.util.Date())
        return dateStr != currentDateStr
    }
}