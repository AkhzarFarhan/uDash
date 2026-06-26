package com.utility.dashcam.service

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.utility.dashcam.data.local.AppDatabase
import com.utility.dashcam.data.local.RawClipEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Orchestrates the concatenation of raw video clips using FFmpeg Kit's stream-copy.
 */
class FfmpegOrchestrator(
    private val context: Context,
    private val database: AppDatabase
) {

    suspend fun processDailyMerge(dateStr: String, clips: List<RawClipEntity>) = withContext(Dispatchers.IO) {
        // 1. Generate Manifest (filelist.txt)
        val manifestFile = File(context.cacheDir, "manifest_$dateStr.txt")
        manifestFile.bufferedWriter().use { writer ->
            clips.forEach { clip ->
                clip.localFilePath?.let { path ->
                    writer.write("file '$path'\n")
                }
            }
        }

        // 2. Prepare Output
        val outputFile = File(context.filesDir, "merge_$dateStr.mp4")
        
        // 3. Execute FFmpeg Command
        // Architecture 5.2: "-f concat -safe 0 -i ${manifestFile.absolutePath} -c copy ${outputFile.absolutePath}"
        val cmd = "-f concat -safe 0 -i ${manifestFile.absolutePath} -c copy -y ${outputFile.absolutePath}"
        
        FFmpegKit.executeAsync(cmd) { session ->
            val returnCode = session.returnCode
            if (ReturnCode.isSuccess(returnCode)) {
                // 4. Atomic Transactional Switch (Architecture 5.2)
                kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                    database.dashcamDao().completeMerge(
                        dateString = dateStr,
                        mergedPath = outputFile.absolutePath,
                        totalSize = outputFile.length()
                    )
                    manifestFile.delete()
                }
            } else {
                kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                    database.dashcamDao().updateMergeStatus(dateStr, "FAILED")
                }
            }
        }
    }
    
    fun isHistoricalDate(dateStr: String): Boolean {
        val sdf = SimpleDateFormat("YYYY-MM-DD", Locale.US)
        val currentDateStr = sdf.format(Date())
        return dateStr != currentDateStr
    }
}
