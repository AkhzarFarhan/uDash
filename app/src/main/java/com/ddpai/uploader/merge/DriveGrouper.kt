package com.ddpai.uploader.merge

/** Groups downloaded dashcam segments into drive sessions for lossless merging. Pure; no IO. */
object DriveGrouper {
    data class Segment(val fileName: String, val capturedAtEpoch: Long, val streamKey: String)
    data class DriveGroup(val streamKey: String, val segments: List<Segment>)


    fun streamKeyOf(fileName: String): String? {
        if (!fileName.endsWith(".mp4", ignoreCase = true)) return null
        if (com.ddpai.uploader.dashcam.DashcamParserHelper.parseCapturedAt(fileName) == 0L) return null
        return com.ddpai.uploader.dashcam.DashcamParserHelper.extractStreamKey(fileName)
    }

    fun buildClosedGroups(
        segments: List<Segment>,
        nowMillis: Long,
        maxPerGroup: Int = 60,
        gapMillis: Long = 120_000L,
        closeAfterMillis: Long = 300_000L,
        segmentDurationMillis: Long = 60_000L
    ): List<DriveGroup> {
        val result = mutableListOf<DriveGroup>()
        val nowLocalDate = java.time.Instant.ofEpochMilli(nowMillis)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()

        segments.groupBy { it.streamKey }.forEach { (stream, streamSegs) ->
            // Group by local calendar date (Day-wise merging)
            val dateGroups = streamSegs.groupBy { seg ->
                java.time.Instant.ofEpochMilli(seg.capturedAtEpoch)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDate()
            }

            dateGroups.forEach { (date, dateSegs) ->
                val ordered = dateSegs.sortedBy { it.capturedAtEpoch }
                if (ordered.isEmpty()) return@forEach

                val newest = ordered.maxOf { it.capturedAtEpoch }
                val isPastDay = date.isBefore(nowLocalDate)
                val hasQuietPeriod = newest + segmentDurationMillis <= nowMillis - closeAfterMillis

                if (isPastDay || hasQuietPeriod) {
                    // Chunk by maxPerGroup to stay within system file handle limits, but otherwise
                    // merge all clips of the same day together even if hours apart.
                    ordered.chunked(maxPerGroup).forEach { chunk ->
                        result += DriveGroup(stream, chunk)
                    }
                }
            }
        }
        return result
    }
}
