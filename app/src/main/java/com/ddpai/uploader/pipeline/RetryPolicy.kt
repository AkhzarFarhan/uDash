package com.ddpai.uploader.pipeline

/** Decides whether a file that just consumed a retry has exhausted its budget. */
object RetryPolicy {
    fun shouldFail(retryCount: Int, maxRetries: Int): Boolean = retryCount >= maxRetries
}
