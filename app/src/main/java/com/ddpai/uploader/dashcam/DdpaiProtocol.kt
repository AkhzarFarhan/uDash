package com.ddpai.uploader.dashcam

import com.ddpai.uploader.data.model.DashcamFile
import com.ddpai.uploader.data.repo.LogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class DdpaiProtocol(private val logger: LogRepository) : DashcamProtocol {
    private var sessionId: String? = null

    override suspend fun handshake(client: OkHttpClient, base: String): Boolean = withContext(Dispatchers.IO) {
        if (sessionId != null) return@withContext true

        val url = "$base/vcam/cmd.cgi?cmd=API_RequestSessionID"
        logger.i("DdpaiProtocol", "Requesting Session ID from: $url")
        try {
            val req = Request.Builder().url(url).post(okhttp3.internal.EMPTY_REQUEST).build()
            client.newCall(req).execute().use { resp ->
                logger.d("DdpaiProtocol", "Session request HTTP response code: ${resp.code}")
                
                // 1. Try Set-Cookie header
                val cookies = resp.headers("Set-Cookie")
                for (cookie in cookies) {
                    if (cookie.contains("SessionID=", ignoreCase = true)) {
                        val parsedId = cookie.substringAfter("SessionID=").substringBefore(";")
                        if (parsedId.isNotBlank()) {
                            sessionId = parsedId
                            logger.i("DdpaiProtocol", "Acquired Session ID from cookie: $sessionId")
                            return@withContext true
                        }
                    }
                }

                // 2. Try parsing body
                val body = resp.body?.string().orEmpty()
                logger.d("DdpaiProtocol", "Session body: $body")

                val sessionRegex = Regex(""""sessionid"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
                val match = sessionRegex.find(body)
                if (match != null) {
                    sessionId = match.groupValues[1]
                    logger.i("DdpaiProtocol", "Acquired Session ID from JSON match: $sessionId")
                    return@withContext true
                }

                val dataRegex = Regex(""""data"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
                val dataMatch = dataRegex.find(body)
                if (dataMatch != null && !dataMatch.groupValues[1].startsWith("0x")) {
                    sessionId = dataMatch.groupValues[1]
                    logger.i("DdpaiProtocol", "Acquired Session ID from JSON data: $sessionId")
                    return@withContext true
                }
            }
        } catch (e: Exception) {
            logger.w("DdpaiProtocol", "Failed to acquire Session ID: ${e.javaClass.simpleName}: ${e.message}")
        }
        return@withContext sessionId != null
    }

    private fun Request.Builder.addSessionHeaders(): Request.Builder {
        sessionId?.let { sid ->
            header("sessionid", sid)
            header("Cookie", "SessionID=$sid")
        }
        return this
    }

    override suspend fun listFiles(client: OkHttpClient, base: String): List<DashcamFile> = withContext(Dispatchers.IO) {
        handshake(client, base)

        val endpoints = listOf(
            "$base/vcam/cmd.cgi?cmd=APP_PlaybackListReq",
            "$base/vcam/cmd.cgi?cmd=APP_PlaybackListReq_RearCam",
            "$base/vcam/cmd.cgi?cmd=APP_EventListReq",
            "$base/vcam/cmd.cgi?cmd=getFileList",
            "$base/vcam/cmd.cgi?cmd=getfilelist",
            "$base/vcam/cmd.cgi",
            "$base/"
        )
        for (url in endpoints) {
            try {
                logger.d("DdpaiProtocol", "Trying endpoint: $url")
                val req = Request.Builder().url(url).get().addSessionHeaders().build()
                client.newCall(req).execute().use { resp ->
                    logger.d("DdpaiProtocol", "Response from $url → HTTP ${resp.code}, content-type=${resp.header("Content-Type")}")
                    if (!resp.isSuccessful) {
                        logger.d("DdpaiProtocol", "Non-success HTTP ${resp.code} from $url, skipping")
                        return@use
                    }
                    val body = resp.body?.string().orEmpty()
                    logger.d("DdpaiProtocol", "Response body length=${body.length} from $url")
                    val files = DashcamFileListParser.parse(body)
                    logger.d("DdpaiProtocol", "Parsed ${files.size} files from $url")
                    if (files.isNotEmpty()) {
                        logger.i("DdpaiProtocol", "Listing OK via $url → ${files.size} files")
                        return@withContext files
                    }
                }
            } catch (e: Exception) {
                logger.w("DdpaiProtocol", "Listing endpoint failed: $url → ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        emptyList()
    }

    override fun buildDownloadRequest(base: String, fileName: String, rangeHeader: String?): Request {
        val url = "$base/$fileName"
        val builder = Request.Builder().url(url).get().addSessionHeaders()
        if (rangeHeader != null) {
            builder.header("Range", rangeHeader)
        }
        return builder.build()
    }
}
