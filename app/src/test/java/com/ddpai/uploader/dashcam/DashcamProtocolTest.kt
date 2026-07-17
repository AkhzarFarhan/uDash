package com.ddpai.uploader.dashcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class DashcamProtocolTest {

    @Test
    fun testDashcamParserHelperCapturedAt() {
        val sdf = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }

        // Test YYYYMMDD_hhmmss
        val epoch1 = DashcamParserHelper.parseCapturedAt("20260717_213000_F.mp4")
        val expected1 = sdf.parse("20260717213000")?.time ?: 0L
        assertEquals(expected1, epoch1)

        // Test contiguous YYYYMMDDhhmmss
        val epoch2 = DashcamParserHelper.parseCapturedAt("20260717213000.mp4")
        val expected2 = sdf.parse("20260717213000")?.time ?: 0L
        assertEquals(expected2, epoch2)

        // Test REC_YYYY_MM_DD_hh_mm_ss
        val epoch3 = DashcamParserHelper.parseCapturedAt("REC_2026_07_17_21_30_00.mp4")
        val expected3 = sdf.parse("20260717213000")?.time ?: 0L
        assertEquals(expected3, epoch3)

        // Test fallback (no date)
        val epoch4 = DashcamParserHelper.parseCapturedAt("unknown_format.mp4")
        assertEquals(0L, epoch4)
    }

    @Test
    fun testDashcamParserHelperStreamKey() {
        assertEquals("F", DashcamParserHelper.extractStreamKey("20260717_213000_F.mp4"))
        assertEquals("F", DashcamParserHelper.extractStreamKey("REC-FRONT-20260717.mp4"))
        assertEquals("R", DashcamParserHelper.extractStreamKey("20260717_213000_R.mp4"))
        assertEquals("R", DashcamParserHelper.extractStreamKey("REC_20260717_BACK.mp4"))
        assertEquals("B", DashcamParserHelper.extractStreamKey("20260717_213000_B.mp4"))
        assertEquals("MAIN", DashcamParserHelper.extractStreamKey("20260717_213000.mp4"))
    }
}
