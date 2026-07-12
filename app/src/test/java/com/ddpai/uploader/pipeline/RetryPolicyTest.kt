package com.ddpai.uploader.pipeline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryPolicyTest {
    @Test fun belowCapDoesNotFail() = assertFalse(RetryPolicy.shouldFail(retryCount = 4, maxRetries = 5))
    @Test fun atCapFails() = assertTrue(RetryPolicy.shouldFail(retryCount = 5, maxRetries = 5))
    @Test fun aboveCapFails() = assertTrue(RetryPolicy.shouldFail(retryCount = 7, maxRetries = 5))
}
