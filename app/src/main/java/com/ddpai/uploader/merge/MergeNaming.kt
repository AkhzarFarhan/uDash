package com.ddpai.uploader.merge

import com.ddpai.uploader.dashcam.DashcamParserHelper

object MergeNaming {
    /** DRIVE_<date>_<time>_<stream>.mp4, with _p<N> for parts beyond the first. */
    fun outputName(head: DriveGrouper.Segment, part: Int): String {
        val clean = head.fileName.replace(Regex("[^0-9]"), "")
        val m14 = Regex("""(\d{8})(\d{6})""").find(clean)
        val date = m14?.groupValues?.get(1) ?: "00000000"
        val time = m14?.groupValues?.get(2) ?: "000000"
        val suffix = if (part > 1) "_p$part" else ""
        return "DRIVE_${date}_${time}_${head.streamKey}$suffix.mp4"
    }
}
