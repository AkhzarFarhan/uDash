package com.ddpai.uploader.youtube

sealed interface ResumeResult {
    data class Complete(val videoId: String?) : ResumeResult
    data class Incomplete(val nextByte: Long) : ResumeResult
    data object SessionExpired : ResumeResult
    data class Fatal(val code: Int, val body: String) : ResumeResult
}

/** Interprets responses from the YouTube resumable-upload protocol. Pure; no IO. */
object ResumeParser {
    private val ID_RE = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"")

    fun videoId(body: String): String? = ID_RE.find(body)?.groupValues?.get(1)

    /** Result of a "query current offset" PUT (Content-Range: bytes *&#47;total). */
    fun parseOffset(code: Int, rangeHeader: String?, body: String): ResumeResult = when (code) {
        200, 201 -> ResumeResult.Complete(videoId(body))
        308 -> ResumeResult.Incomplete(nextByteFromRange(rangeHeader))
        404, 410, 400 -> ResumeResult.SessionExpired
        else -> ResumeResult.Fatal(code, body)
    }

    /** Result of a bytes-uploading PUT. */
    fun parseFinal(code: Int, body: String): ResumeResult = when (code) {
        200, 201 -> ResumeResult.Complete(videoId(body))
        308 -> ResumeResult.Incomplete(-1L) // more bytes expected; caller re-queries
        404, 410, 400 -> ResumeResult.SessionExpired
        else -> ResumeResult.Fatal(code, body)
    }

    private fun nextByteFromRange(rangeHeader: String?): Long {
        val last = rangeHeader?.substringAfterLast('-')?.toLongOrNull() ?: return 0L
        return last + 1L
    }
}
