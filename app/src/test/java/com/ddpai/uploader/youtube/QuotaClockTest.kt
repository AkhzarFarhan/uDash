package com.ddpai.uploader.youtube

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class QuotaClockTest {
    private val pt = ZoneId.of("America/Los_Angeles")

    @Test fun resetIsNextMidnightPacificAndInFuture() {
        val now = ZonedDateTime.of(2026, 7, 10, 15, 30, 0, 0, pt).toInstant().toEpochMilli()
        val reset = QuotaClock.nextResetMillis(now, pt)
        val resetZdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(reset), pt)
        assertTrue(reset > now)
        assertTrue(resetZdt.hour == 0 && resetZdt.minute == 0)
        assertTrue(resetZdt.dayOfMonth == 11)
    }

    @Test fun justBeforeMidnightRollsToNextDay() {
        val now = ZonedDateTime.of(2026, 7, 10, 23, 59, 0, 0, pt).toInstant().toEpochMilli()
        val reset = QuotaClock.nextResetMillis(now, pt)
        assertTrue(reset > now)
        assertTrue(reset - now <= 60_000L + 1000L)
    }
}
