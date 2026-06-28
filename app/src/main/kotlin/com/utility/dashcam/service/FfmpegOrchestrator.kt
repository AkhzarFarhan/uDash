package com.utility.dashcam.service

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.utility.dashcam.data.local.AppDatabase
import com.utility.dashcam.data.local.RawClipEntity
import com.utility.dashcam.util.LogStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
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
    suspend fun processDailyMerge(mergeId: String, dateStr: String, clips: List<RawClipEntity>) = withContext(Dispatchers.IO + NonCancellable) {
        LogStore.log(context, "FFmpeg", "Starting daily merge $mergeId for date $dateStr (${clips.size} clips)")
        com.utility.dashcam.util.ConfigStore.setMergingStatus(context, "Merging $mergeId (${clips.size} clips)")
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
        val manifestContent = manifestFile.readText()
        LogStore.log(context, "FFmpeg", "Generated manifest for $mergeId:\n$manifestContent")

        // 2. Prepare Output file in filesDir
        val outputFile = File(context.filesDir, "merge_$mergeId.mp4")

        // 3. Execute FFmpeg Command (stream-copy concat) using array args to prevent path-parsing errors
        val args = arrayOf("-f", "concat", "-safe", "0", "-i", manifestFile.absolutePath, "-c", "copy", "-y", outputFile.absolutePath)

        var errorMsg = ""
        // Bridge callback to coroutine using CompletableDeferred
        val deferred = CompletableDeferred<Boolean>()

        FFmpegKit.executeWithArgumentsAsync(args) { session ->
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

        // 4. Save state in Room DB (deleting raw physical files is delegated to the parent service after all splits succeed)
        if (success) {
            LogStore.log(context, "FFmpeg", "Merge $mergeId completed successfully. File size: ${outputFile.length() / 1024 / 1024} MB")
            com.utility.dashcam.util.ConfigStore.setMergingStatus(context, "Completed $mergeId successfully (${outputFile.length() / 1024 / 1024} MB)")
            database.dailyMergeDao().completeMergeAndPurgeRaw(
                mergeId = mergeId,
                dateString = dateStr,
                mergedPath = outputFile.absolutePath,
                totalSize = outputFile.length()
            )
            com.utility.dashcam.util.ConfigStore.setLastError(context, null) // Clear error on success
        } else {
            LogStore.log(context, "FFmpeg", "Merge $mergeId failed: $errorMsg", isError = true)
            com.utility.dashcam.util.ConfigStore.setMergingStatus(context, "Failed $mergeId: $errorMsg")
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