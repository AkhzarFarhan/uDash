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
        sl.log.x("MergeWorker", "doWork() started")
        try {
            promoteToForeground()
            sl.log.x("MergeWorker", "Fetching downloaded segments from DB")
            val segments = sl.files.downloadedSegments()
            sl.log.x("MergeWorker", "Found ${segments.size} downloaded segments")
            if (segments.isEmpty()) {
                sl.log.i("MergeWorker", "No segments to merge")
                return Result.success()
            }

            // Check for incomplete downloads of the same calendar days
            val incomplete = sl.files.getIncompleteSegments()
            val zoneId = java.time.ZoneId.systemDefault()
            val incompleteByDateAndStream = incomplete.groupBy { e ->
                val date = java.time.Instant.ofEpochMilli(e.capturedAtEpoch)
                    .atZone(zoneId)
                    .toLocalDate()
                val stream = DriveGrouper.streamKeyOf(e.fileName) ?: "MAIN"
                Pair(date, stream)
            }

            val grouperSegs = segments.mapNotNull { e ->
                DriveGrouper.streamKeyOf(e.fileName)?.let { key ->
                    DriveGrouper.Segment(e.fileName, e.capturedAtEpoch, key)
                }
            }
            sl.log.x("MergeWorker", "Mapping segment keys: ${grouperSegs.size} mapped")
            val groups = DriveGrouper.buildClosedGroups(grouperSegs, System.currentTimeMillis())
            sl.log.i("MergeWorker", "${groups.size} closed drive group(s) ready to merge")
            val byName = segments.associateBy { it.fileName }
            val merger = Mp4Merger()

            for (group in groups) {
                if (group.segments.isEmpty()) continue
                val groupDate = java.time.Instant.ofEpochMilli(group.segments.first().capturedAtEpoch)
                    .atZone(zoneId)
                    .toLocalDate()

                if (incompleteByDateAndStream.containsKey(Pair(groupDate, group.streamKey))) {
                    sl.log.i(
                        "MergeWorker",
                        "Skipping merge for $groupDate stream ${group.streamKey} because there are still incomplete downloads for this day"
                    )
                    continue
                }

                sl.log.x("MergeWorker", "Processing group for streamKey ${group.streamKey} with ${group.segments.size} segments")
                processGroup(group, byName, merger)
            }
            sl.log.i("MergeWorker", "Merge cycle complete")
            return Result.success()
        } catch (t: Throwable) {
            sl.log.e("MergeWorker", "Fatal unhandled exception in MergeWorker: ${t.message}\n${t.stackTraceToString()}")
            return Result.failure()
        }
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
