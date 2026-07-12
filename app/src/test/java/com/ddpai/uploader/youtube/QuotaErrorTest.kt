package com.ddpai.uploader.youtube

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuotaErrorTest {
    @Test fun detectsQuotaExceededReason() {
        val body = """{"error":{"code":403,"errors":[{"reason":"quotaExceeded"}]}}"""
        assertTrue(QuotaError.isQuota(403, body))
    }

    @Test fun detectsUploadLimitExceeded() {
        val body = """{"error":{"errors":[{"reason":"uploadLimitExceeded"}]}}"""
        assertTrue(QuotaError.isQuota(403, body))
    }

    @Test fun plainForbiddenIsNotQuota() {
        val body = """{"error":{"code":403,"errors":[{"reason":"forbidden"}]}}"""
        assertFalse(QuotaError.isQuota(403, body))
    }

    @Test fun non403IsNotQuota() =
        assertFalse(QuotaError.isQuota(500, """{"error":{"errors":[{"reason":"quotaExceeded"}]}}"""))
}
