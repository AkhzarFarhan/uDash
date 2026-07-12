package com.ddpai.uploader.merge

import com.ddpai.uploader.merge.DriveGrouper.Segment
import org.junit.Assert.assertEquals
import org.junit.Test

class MergeNamingTest {
    @Test fun firstPartHasNoSuffix() {
        val head = Segment("20260626180905_0060.mp4", 0L, "0060")
        assertEquals("DRIVE_20260626_180905_0060.mp4", MergeNaming.outputName(head, 1))
    }

    @Test fun laterPartsGetPartSuffix() {
        val head = Segment("20260626190000_F.mp4", 0L, "F")
        assertEquals("DRIVE_20260626_190000_F_p2.mp4", MergeNaming.outputName(head, 2))
    }
}
