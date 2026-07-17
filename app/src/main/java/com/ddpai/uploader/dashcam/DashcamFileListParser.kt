package com.ddpai.uploader.dashcam

import com.ddpai.uploader.data.model.DashcamFile

object DashcamFileListParser {
    private val MP4_FILENAME_RE = Regex("""[a-zA-Z0-9_-]+\.mp4""", RegexOption.IGNORE_CASE)

    fun parse(body: String): List<DashcamFile> {
        val trimmed = body.trimStart()
        return if (trimmed.startsWith("{") || trimmed.startsWith("[")) parseJson(body)
        else parseHtml(body)
    }

    private fun parseHtml(html: String): List<DashcamFile> {
        val matches = MP4_FILENAME_RE.findAll(html)
        return matches.map { m ->
            val fileName = m.value
            val capturedAt = DashcamParserHelper.parseCapturedAt(fileName)
            val streamKey = DashcamParserHelper.extractStreamKey(fileName)
            DashcamFile(
                fileName = fileName,
                sizeBytes = -1L,
                capturedAtEpoch = if (capturedAt > 0) capturedAt else System.currentTimeMillis()
            )
        }.distinctBy { it.fileName }.toList()
    }

    private fun parseJson(json: String): List<DashcamFile> {
        val matches = MP4_FILENAME_RE.findAll(json)
        return matches.map { m ->
            val fileName = m.value
            val capturedAt = DashcamParserHelper.parseCapturedAt(fileName)
            val streamKey = DashcamParserHelper.extractStreamKey(fileName)
            DashcamFile(
                fileName = fileName,
                sizeBytes = -1L,
                capturedAtEpoch = if (capturedAt > 0) capturedAt else System.currentTimeMillis()
            )
        }.distinctBy { it.fileName }.toList()
    }
}
