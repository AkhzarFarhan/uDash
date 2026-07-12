package com.ddpai.uploader.merge

object MergeNaming {
    private val NAME_RE = Regex("""(\d{8})(\d{6})_(0060|F|R)\.mp4""", RegexOption.IGNORE_CASE)

    /** DRIVE_<date>_<time>_<stream>.mp4, with _p<N> for parts beyond the first. */
    fun outputName(head: DriveGrouper.Segment, part: Int): String {
        val m = NAME_RE.find(head.fileName)
        val date = m?.groupValues?.get(1) ?: "00000000"
        val time = m?.groupValues?.get(2) ?: "000000"
        val suffix = if (part > 1) "_p$part" else ""
        return "DRIVE_${date}_${time}_${head.streamKey}$suffix.mp4"
    }
}
