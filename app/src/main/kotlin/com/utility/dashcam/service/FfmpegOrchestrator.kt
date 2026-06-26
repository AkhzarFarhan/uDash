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
import java.util.concurrent.CancellationException

/**
 * Orchestrates the concatenation of raw video clips using FFmpegKit's stream-copy demuxer.
 * Architecture §5.2:
 * - Generates a concat manifest (filelist.txt) with absolute paths.
 * - Executes: `-f concat -safe 0 -i <manifest> -c copy -y <output>`
 *   - `-c copy` = stream-copy (zero re-encoding, low thermal/battery).
 *   - `-safe 0` allows absolute paths in the manifest.
 * - On success: atomic DB transaction (completeMergeAndPurgeRaw).
 * - On failure: mark merge FAILED.
 */
class FfmpegOrchestrator(
    private val context: Context,
    private val database: AppDatabase
) {

    /**
     * Process daily merge for a given date and its completed clips.
     * Returns when the FFmpeg operation completes (success or failure).
     * Uses suspendCancellableCoroutine to bridge FFmpegKit's callback to coroutines.
     */
    suspend fun processDailyMerge(dateStr: String, clips: List<RawClipEntity>) = withContext(Dispatchers.IO) {
        // 1. Generate Manifest (filelist.txt) with absolute paths, one per line: file '/absolute/path'
        val manifestFile = File(context.cacheDir, "manifest_$dateStr.txt")
        manifestFile.bufferedWriter().use { writer ->
            clips.forEach { clip ->
                clip.localFilePath?.let { path ->
                    // FFmpeg concat demuxer requires single-quoted absolute paths
                    writer.write("file '$path'\n")
                }
            }
        }

        // 2. Prepare Output file in filesDir (persists across cache clears, accessible to upload worker)
        val outputFile = File(context.filesDir, "merge_$dateStr.mp4")

        // 3. Execute FFmpeg Command (stream-copy concat)
        // Architecture §5.2: "-f concat -safe 0 -i ${manifestFile.absolutePath} -c copy ${outputFile.absolutePath}"
        val cmd = "-f concat -safe 0 -i ${manifestFile.absolutePath} -c copy -y ${outputFile.absolutePath}"

        // Bridge callback to coroutine using CompletableDeferred
        val deferred = CompletableDeferred<Boolean>()

        FFmpegKit.executeAsync(cmd) { session ->
            val returnCode = session.returnCode
            if (ReturnCode.isSuccess(returnCode)) {
                deferred.complete(true)
            } else {
                // Log FFmpeg output for diagnostics
                val output = session.output ?: "no output"
                System.err.println("FFmpeg concat failed for $dateStr: $output")
                deferred.complete(false)
            }
        }

        val success = deferred.await()

        // 4. Atomic DB Transaction on result
        if (success) {
            database.dailyMergeDao().completeMergeAndPurgeRaw(
                dateString = dateStr,
                mergedPath = outputFile.absolutePath,
                totalSize = outputFile.length(),
                rawClipDao = database.rawClipDao()
            )
        } else {
            database.dailyMergeDao().markMergeFailed(dateStr)
        }
        manifestFile.delete() // Clean up manifest file
    }

    /**
     * Architecture §6 (Current-Date Intersection):
     * "The ingestion script evaluates and blocks the compilation of files matching
     *  the exact current system date (T0)."
     * Returns true if dateStr is a historical (closed) date, false if it's today.
     * Uses 'yyyy-MM-dd' (year-of-era) NOT 'YYYY' (week-year) — critical bug fix.
     */
    fun isHistoricalDate(dateStr: String): Boolean {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val currentDateStr = sdf.format(java.util.Date())
        return dateStr != currentDateStr
    }
}