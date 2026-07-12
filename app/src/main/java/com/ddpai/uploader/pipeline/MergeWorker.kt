package com.ddpai.uploader.pipeline

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ddpai.uploader.data.db.entity.VideoFileEntity
import com.ddpai.uploader.data.model.FileStatus
import com.ddpai.uploader.di.ServiceLocator
import com.ddpai.uploader.integrity.Mp4AtomScanner
import com.ddpai.uploader.merge.DriveGrouper
import com.ddpai.uploader.merge.MergeNaming
import com.ddpai.uploader.merge.Mp4Merger
import java.io.File

class MergeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val sl = ServiceLocator.get(applicationContext)

    override suspend fun getForegroundInfo() =
        sl.notifications.uploadForegroundInfo(applicationContext, "Merging drive clips…")

    override suspend fun doWork(): Result {
        promoteToForeground()
        val segments = sl.files.downloadedSegments()
        if (segments.isEmpty()) {
            sl.log.i("MergeWorker", "No segments to merge")
            return Result.success()
        }
        val grouperSegs = segments.mapNotNull { e ->
            DriveGrouper.streamKeyOf(e.fileName)?.let { key ->
                DriveGrouper.Segment(e.fileName, e.capturedAtEpoch, key)
            }
        }
        val groups = DriveGrouper.buildClosedGroups(grouperSegs, System.currentTimeMillis())
        sl.log.i("MergeWorker", "${groups.size} closed drive group(s) ready to merge")
        val byName = segments.associateBy { it.fileName }
        val merger = Mp4Merger()

        for (group in groups) {
            // DriveGrouper already splits by gap and by the 60-segment cap, so every group has a
            // distinct head timestamp and therefore a unique output name — no _p suffix needed.
            processGroup(group, byName, merger)
        }
        sl.log.i("MergeWorker", "Merge cycle complete")
        return Result.success()
    }

    private suspend fun processGroup(
        group: DriveGrouper.DriveGroup,
        byName: Map<String, VideoFileEntity>,
        merger: Mp4Merger
    ) {
        val entities = group.segments.mapNotNull { byName[it.fileName] }
        if (entities.isEmpty()) return

        if (entities.size == 1) {
            sl.files.relabelAsMerged(entities[0].fileName)
            sl.log.i("MergeWorker", "Single-segment drive ${entities[0].fileName} → MERGED (no copy)")
            return
        }

        val outputName = MergeNaming.outputName(group.segments.first(), 1)
        val outputFile = sl.files.fileFor(outputName)
        val tmp = File(outputFile.absolutePath + ".tmp")
        if (tmp.exists()) tmp.delete()

        val inputs = entities.map { sl.files.fileFor(it.fileName) }
        when (val outcome = merger.merge(inputs, tmp)) {
            is Mp4Merger.Outcome.Success -> {
                val scan = Mp4AtomScanner.scan(tmp)
                if (!scan.isValid) {
                    sl.log.e("MergeWorker", "Merged $outputName failed integrity (${scanReason(scan)}); fallback to per-segment")
                    tmp.delete()
                    fallbackRelabel(entities)
                    return
                }
                if (!tmp.renameTo(outputFile)) {
                    sl.log.e("MergeWorker", "Rename failed for $outputName; fallback to per-segment")
                    tmp.delete()
                    fallbackRelabel(entities)
                    return
                }
                val merged = VideoFileEntity(
                    fileName = outputName,
                    remoteUrl = "",
                    localPath = outputFile.absolutePath,
                    status = FileStatus.DOWNLOADED.name,
                    kind = "MERGED",
                    sizeBytes = outputFile.length(),
                    downloadedBytes = outputFile.length(),
                    capturedAtEpoch = group.segments.first().capturedAtEpoch
                )
                sl.files.commitMerge(merged, entities.map { it.fileName })
                entities.forEach { sl.files.fileFor(it.fileName).delete() }
                sl.log.i("MergeWorker", "Merged ${entities.size} clips → $outputName (${outputFile.length()} bytes)")
            }
            is Mp4Merger.Outcome.FormatMismatch -> {
                sl.log.w("MergeWorker", "Format mismatch at index ${outcome.atIndex} in $outputName; fallback to per-segment")
                tmp.delete()
                fallbackRelabel(entities)
            }
            is Mp4Merger.Outcome.Error -> {
                sl.log.e("MergeWorker", "Merge error for $outputName: ${outcome.message}; fallback to per-segment")
                tmp.delete()
                fallbackRelabel(entities)
            }
        }
    }

    /** On any merge failure, upload the segments individually (still lossless, just more uploads). */
    private suspend fun fallbackRelabel(entities: List<VideoFileEntity>) {
        entities.forEach { sl.files.relabelAsMerged(it.fileName) }
    }

    private fun scanReason(scan: Mp4AtomScanner.Result): String = buildString {
        if (!scan.sizeOk) append("size ")
        if (!scan.hasFtyp) append("ftyp ")
        if (!scan.hasMdat) append("mdat ")
        if (!scan.hasMoov) append("moov ")
    }.trim().ifEmpty { "unknown" }

    private suspend fun promoteToForeground() {
        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            sl.log.w("MergeWorker", "Foreground promotion refused (${e.message}); running in background")
        }
    }
}
