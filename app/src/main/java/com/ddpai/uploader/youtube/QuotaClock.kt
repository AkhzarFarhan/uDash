package com.ddpai.uploader.youtube

import java.time.Instant
import java.time.ZoneId

/** Computes the next midnight in the given zone (YouTube quota resets at midnight Pacific). */
object QuotaClock {
    fun nextResetMillis(nowMillis: Long, zone: ZoneId = ZoneId.of("America/Los_Angeles")): Long {
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(zone)
        return nextMidnight.toInstant().toEpochMilli()
    }
}
