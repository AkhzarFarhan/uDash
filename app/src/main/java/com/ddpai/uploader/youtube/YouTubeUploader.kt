package com.ddpai.uploader.youtube

import com.ddpai.uploader.data.config.ConfigRepository
import com.ddpai.uploader.data.repo.LogRepository
import com.ddpai.uploader.network.BoundHttpClientFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

class YouTubeUploader(
    private val auth: YouTubeAuthManager,
    private val configRepo: ConfigRepository,
    private val logger: LogRepository
) {
    private val http = BoundHttpClientFactory.default()
    private val INIT_URL =
        "https://www.googleapis.com/upload/youtube/v3/videos?uploadType=resumable&part=snippet,status"

    suspend fun initiate(file: File, title: String): String {
        val token = auth.freshAccessToken() ?: throw IllegalStateException("Not authorized")
        val privacy = configRepo.config.value.uploadPrivacy
        val metaJson = """
            {"snippet":{"title":${title.json()},"description":"Auto-uploaded dashcam clip","categoryId":"2"},
             "status":{"privacyStatus":"$privacy","selfDeclaredMadeForKids":false}}
        """.trimIndent()
        val req = Request.Builder().url(INIT_URL)
            .header("Authorization", "Bearer $token")
            .header("X-Upload-Content-Type", "video/mp4")
            .header("X-Upload-Content-Length", file.length().toString())
            .post(metaJson.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("init ${resp.code}: ${resp.body?.string()}")
            return resp.header("Location") ?: throw IOException("No resumable session URI")
        }
    }

    suspend fun queryOffset(sessionUri: String, total: Long): ResumeResult {
        val token = auth.freshAccessToken() ?: throw IllegalStateException("Not authorized")
        val req = Request.Builder().url(sessionUri)
            .header("Authorization", "Bearer $token")
            .header("Content-Range", "bytes */$total")
            .put(ByteArray(0).toRequestBody(null))
            .build()
        http.newCall(req).execute().use { resp ->
            return ResumeParser.parseOffset(resp.code, resp.header("Range"), resp.body?.string().orEmpty())
        }
    }

    /** Uploads from [startByte]; returns YouTube video ID on success, throws [UploadHttpException] otherwise. */
    suspend fun uploadFrom(
        sessionUri: String,
        file: File,
        startByte: Long,
        onProgress: (sent: Long, total: Long) -> Unit
    ): String {
        val token = auth.freshAccessToken() ?: throw IllegalStateException("Not authorized")
        val total = file.length()
        val body = FileRangeRequestBody(file, startByte, total, onProgress)
        val req = Request.Builder().url(sessionUri)
            .header("Authorization", "Bearer $token")
            .header("Content-Range", "bytes $startByte-${total - 1}/$total")
            .put(body)
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            return when (val r = ResumeParser.parseFinal(resp.code, text)) {
                is ResumeResult.Complete -> r.videoId ?: throw UploadHttpException(resp.code, "ok but no video id")
                else -> throw UploadHttpException(resp.code, text)
            }
        }
    }
}

class FileRangeRequestBody(
    private val file: File,
    private val start: Long,
    private val total: Long,
    private val onProgress: (Long, Long) -> Unit
) : RequestBody() {
    override fun contentType() = "video/mp4".toMediaType()
    override fun contentLength() = total - start
    override fun writeTo(sink: BufferedSink) {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val buf = ByteArray(256 * 1024)
            var sent = start
            while (true) {
                val n = raf.read(buf)
                if (n == -1) break
                sink.write(buf, 0, n)
                sent += n
                onProgress(sent, total)
            }
        }
    }
}

private fun String.json() = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
