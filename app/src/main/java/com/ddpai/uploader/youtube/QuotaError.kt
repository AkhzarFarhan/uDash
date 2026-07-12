package com.ddpai.uploader.youtube

/** Recognises YouTube Data API daily-quota / upload-limit errors from a 403 response body. */
object QuotaError {
    private val REASONS = listOf("quotaExceeded", "uploadLimitExceeded", "dailyLimitExceeded")

    fun isQuota(code: Int, body: String): Boolean =
        code == 403 && REASONS.any { body.contains(it, ignoreCase = true) }
}
