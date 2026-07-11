package com.ddpai.uploader.dashcam

import com.ddpai.uploader.data.model.DashcamFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListingFilterTest {
    @Test fun excludesSingleNewestByCaptureTime() {
        val files = listOf(
            DashcamFile("20260626180800_0060.mp4", 0L, 1000L),
            DashcamFile("20260626181000_0060.mp4", 0L, 3000L), // newest
            DashcamFile("20260626180900_0060.mp4", 0L, 2000L),
        )
        val kept = ListingFilter.excludeNewest(files).map { it.fileName }
        assertEquals(2, kept.size)
        assertTrue("20260626181000_0060.mp4" !in kept)
    }

    @Test fun emptyListReturnsEmpty() = assertTrue(ListingFilter.excludeNewest(emptyList()).isEmpty())

    @Test fun singleElementIsExcluded() =
        assertTrue(ListingFilter.excludeNewest(listOf(DashcamFile("a", 0L, 1L))).isEmpty())

    @Test fun excludesNewestPerStreamOnDualCamera() {
        // Front + rear are cut simultaneously → identical capture time; BOTH are in-progress.
        val files = listOf(
            DashcamFile("20260626180900_F.mp4", 0L, 2000L),
            DashcamFile("20260626180900_R.mp4", 0L, 2000L),
            DashcamFile("20260626180800_F.mp4", 0L, 1000L),
            DashcamFile("20260626180800_R.mp4", 0L, 1000L),
        )
        val kept = ListingFilter.excludeNewest(files).map { it.fileName }.toSet()
        assertEquals(setOf("20260626180800_F.mp4", "20260626180800_R.mp4"), kept)
    }
}
