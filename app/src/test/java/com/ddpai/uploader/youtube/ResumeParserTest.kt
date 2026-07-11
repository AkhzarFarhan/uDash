package com.ddpai.uploader.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeParserTest {
    @Test fun completeParsesVideoId() {
        val r = ResumeParser.parseOffset(200, null, """{"kind":"youtube#video","id":"abc123"}""")
        assertTrue(r is ResumeResult.Complete)
        assertEquals("abc123", (r as ResumeResult.Complete).videoId)
    }

    @Test fun incompleteReadsRangeHeader() {
        val r = ResumeParser.parseOffset(308, "bytes=0-262143", "")
        assertTrue(r is ResumeResult.Incomplete)
        assertEquals(262144L, (r as ResumeResult.Incomplete).nextByte)
    }

    @Test fun incompleteWithNoRangeStartsAtZero() {
        val r = ResumeParser.parseOffset(308, null, "")
        assertEquals(0L, (r as ResumeResult.Incomplete).nextByte)
    }

    @Test fun goneIsSessionExpired() {
        assertTrue(ResumeParser.parseOffset(410, null, "") is ResumeResult.SessionExpired)
        assertTrue(ResumeParser.parseOffset(404, null, "") is ResumeResult.SessionExpired)
    }

    @Test fun serverErrorIsFatal() {
        val r = ResumeParser.parseOffset(500, null, "boom")
        assertTrue(r is ResumeResult.Fatal)
        assertEquals(500, (r as ResumeResult.Fatal).code)
    }
}
