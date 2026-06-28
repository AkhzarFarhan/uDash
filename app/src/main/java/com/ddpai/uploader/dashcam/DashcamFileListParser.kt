package com.ddpai.uploader.dashcam

import com.ddpai.uploader.data.model.DashcamFile
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object DashcamFileListParser {
    private val NAME_RE = Regex("""(\d{8})(\d{6})_(0060|F|R)\.mp4""", RegexOption.IGNORE_CASE)

    fun parse(body: String): List<DashcamFile> {
        val trimmed = body.trimStart()
        return if (trimmed.startsWith("{") || trimmed.startsWith("[")) parseJson(body)
        else parseHtml(body)
    }

    private fun parseHtml(html: String): List<DashcamFile> =
        NAME_RE.findAll(html).map { m ->
            DashcamFile(m.value, 0L, epochFrom(m.groupValues[1], m.groupValues[2]))
        }.distinctBy { it.fileName }.toList()

    private fun parseJson(json: String): List<DashcamFile> {
        val files = NAME_RE.findAll(json).map { it.value }.distinct().toList()
        return files.map { name ->
            val m = NAME_RE.find(name)!!
            DashcamFile(name, 0L, epochFrom(m.groupValues[1], m.groupValues[2]))
        }
    }

    private fun epochFrom(date8: String, time6: String): Long = try {
        val fmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
        fmt.timeZone = TimeZone.getDefault()
        fmt.parse(date8 + time6)?.time ?: 0L
    } catch (e: Exception) { 0L }
}
