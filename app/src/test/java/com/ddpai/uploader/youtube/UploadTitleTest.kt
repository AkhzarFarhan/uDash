package com.ddpai.uploader.youtube

import com.ddpai.uploader.data.db.entity.VideoFileEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadTitleTest {
    private fun entity(name: String, kind: String, epoch: Long) = VideoFileEntity(
        fileName = name, remoteUrl = "", localPath = null, status = "DOWNLOADED",
        kind = kind, capturedAtEpoch = epoch
    )

    @Test fun segmentTitleIsFilenameStem() {
        val t = UploadTitle.of(entity("20260626180905_0060.mp4", "SEGMENT", 0L))
        assertEquals("20260626180905_0060", t)
    }

    @Test fun mergedTitleIsHumanReadable() {
        val t = UploadTitle.of(entity("DRIVE_20260626_180905_F.mp4", "MERGED", 0L))
        assertTrue(t.startsWith("Dashcam "))
        assertTrue(t.contains("Front"))
    }
}
