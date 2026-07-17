package com.ddpai.uploader.dashcam

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object DashcamParserHelper {
    fun parseCapturedAt(fileName: String): Long {
        val clean = fileName.replace(Regex("[^0-9]"), "")
        val m14 = Regex("""(\d{8})(\d{6})""").find(clean)
        if (m14 != null) {
            val dateStr = m14.groupValues[1]
            val timeStr = m14.groupValues[2]
            return parseDateTime(dateStr, timeStr)
        }
        val mDate = Regex("""(\d{4})[-_]?(\d{2})[-_]?(\d{2})""").find(fileName)
        val mTime = Regex("""(\d{2})[-_]?(\d{2})[-_]?(\d{2})""").find(fileName, mDate?.range?.last ?: 0)
        if (mDate != null && mTime != null) {
            val dateStr = "${mDate.groupValues[1]}${mDate.groupValues[2]}${mDate.groupValues[3]}"
            val timeStr = "${mTime.groupValues[1]}${mTime.groupValues[2]}${mTime.groupValues[3]}"
            return parseDateTime(dateStr, timeStr)
        }
        return 0L
    }

    fun extractStreamKey(fileName: String): String {
        val uppercase = fileName.uppercase()
        return when {
            uppercase.contains("_F") || uppercase.contains("-F") || uppercase.contains("FRONT") -> "F"
            uppercase.contains("_R") || uppercase.contains("-R") || uppercase.contains("REAR") || uppercase.contains("BACK") -> "R"
            uppercase.contains("_B") || uppercase.contains("-B") -> "B"
            else -> {
                val clean = fileName.replace(Regex("[^0-9]"), "")
                val m14 = Regex("""(\d{8})(\d{6})""").find(clean)
                if (m14 != null) {
                    val dateTimeStr = m14.value
                    // Find where the date-time sequence actually starts in the original filename
                    val index = fileName.indexOf(dateTimeStr)
                    if (index != -1) {
                        val suffixPart = fileName.substring(index + dateTimeStr.length)
                            .removePrefix("_")
                            .removePrefix("-")
                            .substringBefore(".")
                        if (suffixPart.isNotBlank()) {
                            return suffixPart
                        }
                    }
                }
                "MAIN"
            }
        }
    }

    private fun parseDateTime(dateStr: String, timeStr: String): Long {
        return try {
            val format = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
            format.timeZone = TimeZone.getDefault()
            val date = format.parse("$dateStr$timeStr")
            date?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}
