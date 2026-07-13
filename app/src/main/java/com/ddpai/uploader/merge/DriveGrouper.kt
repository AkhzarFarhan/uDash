package com.ddpai.uploader.merge

/** Groups downloaded dashcam segments into drive sessions for lossless merging. Pure; no IO. */
object DriveGrouper {
    data class Segment(val fileName: String, val capturedAtEpoch: Long, val streamKey: String)
    data class DriveGroup(val streamKey: String, val segments: List<Segment>)

    private val NAME_RE = Regex("""\d{8}\d{6}_?([a-zA-Z0-9_-]*)\.mp4""", RegexOption.IGNORE_CASE)

    fun streamKeyOf(fileName: String): String? =
        NAME_RE.find(fileName)?.let { match ->
            val key = match.groupValues[1].uppercase()
            if (key.isEmpty()) "MAIN" else key
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
        // Split by stream, then walk each stream in capture order.
        segments.groupBy { it.streamKey }.forEach { (stream, streamSegs) ->
            val ordered = streamSegs.sortedBy { it.capturedAtEpoch }
            var current = mutableListOf<Segment>()

            fun flush() {
                if (current.isEmpty()) return
                val newest = current.maxOf { it.capturedAtEpoch }
                val closed = newest + segmentDurationMillis <= nowMillis - closeAfterMillis
                if (closed) result += DriveGroup(stream, current.toList())
                current = mutableListOf()
            }

            for (seg in ordered) {
                val prev = current.lastOrNull()
                val breaks = prev != null &&
                    (seg.capturedAtEpoch - prev.capturedAtEpoch > gapMillis || current.size >= maxPerGroup)
                if (breaks) flush()
                current.add(seg)
            }
            flush()
        }
        return result
    }
}
