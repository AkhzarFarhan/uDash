package com.ddpai.uploader.merge

import com.ddpai.uploader.merge.DriveGrouper.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveGrouperTest {
    private val minute = 60_000L
    // A "now" far in the future so every group is closed unless a test overrides.
    private val farFuture = 10_000_000_000L

    private fun seg(name: String, epoch: Long, stream: String = "0060") = Segment(name, epoch, stream)

    @Test fun streamKeyParsing() {
        assertEquals("0060", DriveGrouper.streamKeyOf("20260626180905_0060.mp4"))
        assertEquals("F", DriveGrouper.streamKeyOf("20260626180905_F.mp4"))
        assertEquals("R", DriveGrouper.streamKeyOf("20260626180905_R.mp4"))
        assertNull(DriveGrouper.streamKeyOf("garbage.mp4"))
    }

    @Test fun contiguousSegmentsFormOneGroup() {
        val segs = listOf(
            seg("a", 0L), seg("b", minute), seg("c", 2 * minute)
        )
        val groups = DriveGrouper.buildClosedGroups(segs, farFuture)
        assertEquals(1, groups.size)
        assertEquals(listOf("a", "b", "c"), groups[0].segments.map { it.fileName })
    }

    @Test fun largeGapSplitsGroups() {
        val segs = listOf(
            seg("a", 0L), seg("b", minute),
            seg("c", 10 * minute), seg("d", 11 * minute) // 9-min gap > 120s
        )
        val groups = DriveGrouper.buildClosedGroups(segs, farFuture)
        assertEquals(2, groups.size)
        assertEquals(listOf("a", "b"), groups[0].segments.map { it.fileName })
        assertEquals(listOf("c", "d"), groups[1].segments.map { it.fileName })
    }

    @Test fun differentStreamsNeverMerge() {
        val segs = listOf(
            seg("f1", 0L, "F"), seg("r1", 0L, "R"), seg("f2", minute, "F")
        )
        val groups = DriveGrouper.buildClosedGroups(segs, farFuture)
        assertEquals(2, groups.size)
        assertTrue(groups.all { g -> g.segments.all { it.streamKey == g.streamKey } })
    }

    @Test fun capSplitsIntoParts() {
        val segs = (0 until 130).map { seg("s$it", it * minute) }
        val groups = DriveGrouper.buildClosedGroups(segs, farFuture, maxPerGroup = 60)
        assertEquals(3, groups.size) // 60 + 60 + 10
        assertEquals(60, groups[0].segments.size)
        assertEquals(60, groups[1].segments.size)
        assertEquals(10, groups[2].segments.size)
    }

    @Test fun openGroupIsExcluded() {
        // newest segment ended (epoch + 60s) only 1 minute before now → still open
        val now = 100 * minute
        val segs = listOf(seg("a", now - 2 * minute), seg("b", now - minute))
        val groups = DriveGrouper.buildClosedGroups(segs, now, closeAfterMillis = 5 * minute)
        assertTrue(groups.isEmpty())
    }

    @Test fun singleSegmentClosedGroupIsKept() {
        val groups = DriveGrouper.buildClosedGroups(listOf(seg("solo", 0L)), farFuture)
        assertEquals(1, groups.size)
        assertEquals(1, groups[0].segments.size)
    }
}
