package com.ddpai.uploader.dashcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class DashcamFileListParserTest {

    @Test
    fun testParseHtmlListing() {
        val html = """
            <html>
            <body>
            <a href="20260626180905_0060.mp4">20260626180905_0060.mp4</a>
            <a href="20260626181005_F.mp4">20260626181005_F.mp4</a>
            </body>
            </html>
        """.trimIndent()

        val files = DashcamFileListParser.parse(html)
        assertEquals(2, files.size)

        val file1 = files.first { it.fileName == "20260626180905_0060.mp4" }
        val sdf = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
        val expectedEpoch1 = sdf.parse("20260626180905")?.time ?: 0L
        assertEquals(expectedEpoch1, file1.capturedAtEpoch)

        val file2 = files.first { it.fileName == "20260626181005_F.mp4" }
        val expectedEpoch2 = sdf.parse("20260626181005")?.time ?: 0L
        assertEquals(expectedEpoch2, file2.capturedAtEpoch)
    }

    @Test
    fun testParseJsonListing() {
        val json = """
            [
                {"name": "20260626180905_0060.mp4", "size": 12345},
                {"name": "20260626181105_R.mp4", "size": 67890}
            ]
        """.trimIndent()

        val files = DashcamFileListParser.parse(json)
        assertEquals(2, files.size)

        val file1 = files.first { it.fileName == "20260626180905_0060.mp4" }
        val sdf = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
        val expectedEpoch1 = sdf.parse("20260626180905")?.time ?: 0L
        assertEquals(expectedEpoch1, file1.capturedAtEpoch)

        val file2 = files.first { it.fileName == "20260626181105_R.mp4" }
        val expectedEpoch2 = sdf.parse("20260626181105")?.time ?: 0L
        assertEquals(expectedEpoch2, file2.capturedAtEpoch)
    }

    @Test
    fun testParseEmptyBody() {
        val files = DashcamFileListParser.parse("")
        assertTrue(files.isEmpty())
    }
}
