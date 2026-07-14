package com.ddpai.uploader.dashcam

import android.net.Network
import com.ddpai.uploader.data.model.DashcamFile
import com.ddpai.uploader.data.repo.LogRepository
import com.ddpai.uploader.network.BoundHttpClientFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

class DashcamClient(
    private val network: Network,
    private val gateway: String,
    private val logger: LogRepository
) {
    private val client = BoundHttpClientFactory.forNetwork(network)
    private val base = "http://$gateway"
    private var sessionId: String? = null

    private suspend fun ensureSession(): Boolean {
        if (sessionId != null) return true

        val url = "$base/vcam/cmd.cgi?cmd=API_RequestSessionID"
        logger.i("DashcamClient", "Requesting Session ID from: $url")
        try {
            val req = Request.Builder().url(url).post(okhttp3.internal.EMPTY_REQUEST).build()
            client.newCall(req).execute().use { resp ->
                logger.d("DashcamClient", "Session request HTTP response code: ${resp.code}")
                
                // 1. Try Set-Cookie header
                val cookies = resp.headers("Set-Cookie")
                for (cookie in cookies) {
                    if (cookie.contains("SessionID=", ignoreCase = true)) {
                        val parsedId = cookie.substringAfter("SessionID=").substringBefore(";")
                        if (parsedId.isNotBlank()) {
                            sessionId = parsedId
                            logger.i("DashcamClient", "Acquired Session ID from cookie: $sessionId")
                            return true
                        }
                    }
                }

                // 2. Try parsing body
                val body = resp.body?.string().orEmpty()
                logger.d("DashcamClient", "Session body: $body")

                val sessionRegex = Regex(""""sessionid"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
                val match = sessionRegex.find(body)
                if (match != null) {
                    sessionId = match.groupValues[1]
                    logger.i("DashcamClient", "Acquired Session ID from JSON match: $sessionId")
                    return true
                }

                val dataRegex = Regex(""""data"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
                val dataMatch = dataRegex.find(body)
                if (dataMatch != null && !dataMatch.groupValues[1].startsWith("0x")) {
                    sessionId = dataMatch.groupValues[1]
                    logger.i("DashcamClient", "Acquired Session ID from JSON data: $sessionId")
                    return true
                }
            }
        } catch (e: Exception) {
            logger.w("DashcamClient", "Failed to acquire Session ID: ${e.javaClass.simpleName}: ${e.message}")
        }
        return sessionId != null
    }

    private fun Request.Builder.addSessionHeaders(): Request.Builder {
        sessionId?.let { sid ->
            header("sessionid", sid)
            header("Cookie", "SessionID=$sid")
        }
        return this
    }

    suspend fun listFiles(): List<DashcamFile> = withContext(Dispatchers.IO) {
        logger.i("DashcamClient", "listFiles() start: base=$base, network=$network")
        
        // Ensure a session exists first if camera requires auth
        ensureSession()

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
                logger.d("DashcamClient", "Trying endpoint: $url")
                val req = Request.Builder().url(url).get().addSessionHeaders().build()
                client.newCall(req).execute().use { resp ->
                    logger.d("DashcamClient", "Response from $url → HTTP ${resp.code}, content-type=${resp.header("Content-Type")}")
                    if (!resp.isSuccessful) {
                        logger.d("DashcamClient", "Non-success HTTP ${resp.code} from $url, skipping")
                        return@use
                    }
                    val body = resp.body?.string().orEmpty()
                    logger.d("DashcamClient", "Response body length=${body.length} from $url")
                    if (body.length < 2000) {
                        logger.d("DashcamClient", "Full response body:\n$body")
                    } else {
                        logger.d("DashcamClient", "Body preview (first 500 chars): ${body.take(500)}")
                    }
                    val files = DashcamFileListParser.parse(body)
                    logger.d("DashcamClient", "Parsed ${files.size} files from $url")
                    if (files.isNotEmpty()) {
                        logger.i("DashcamClient", "Listing OK via $url → ${files.size} files")
                        files.take(5).forEachIndexed { i, f ->
                            logger.d("DashcamClient", "  [$i] ${f.fileName} size=${f.sizeBytes} epoch=${f.capturedAtEpoch}")
                        }
                        return@withContext files
                    }
                }
            } catch (e: Exception) {
                logger.w("DashcamClient", "Listing endpoint failed: $url → ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        logger.w("DashcamClient", "No files found from any of ${endpoints.size} endpoints")
        emptyList()
    }

    suspend fun download(
        fileName: String,
        target: File,
        existingBytes: Long,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): Long = withContext(Dispatchers.IO) {
        val url = "$base/$fileName"
        logger.d("DashcamClient", "download() start: $url, resume@$existingBytes → ${target.absolutePath}")
        val reqBuilder = Request.Builder().url(url).get().addSessionHeaders()
        if (existingBytes > 0) reqBuilder.header("Range", "bytes=$existingBytes-")
        val request = reqBuilder.build()

        client.newCall(request).execute().use { resp ->
            logger.d("DashcamClient", "download response: HTTP ${resp.code}, content-length=${resp.header("Content-Length")}")
            if (!resp.isSuccessful && resp.code != 206) {
                throw IOException("HTTP ${resp.code} for $url")
            }
            val body = resp.body ?: throw IOException("Empty body for $url")
            val totalFromHeader = body.contentLength().let {
                if (it > 0) it + existingBytes else -1L
            }
            val append = existingBytes > 0 && resp.code == 206
            RandomAccessFile(target, "rw").use { raf ->
                if (append) raf.seek(existingBytes) else raf.setLength(0)
                var written = existingBytes
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        raf.write(buf, 0, n)
                        written += n
                        onProgress(written, totalFromHeader)
                    }
                    logger.d("DashcamClient", "download complete: $fileName, $written bytes written")
                    return@withContext written
                }
            }
        }
    }
}
