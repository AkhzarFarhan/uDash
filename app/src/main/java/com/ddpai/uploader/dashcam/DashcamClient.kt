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

    suspend fun listFiles(): List<DashcamFile> = withContext(Dispatchers.IO) {
        val endpoints = listOf(
            "$base/vcam/cmd.cgi?cmd=getFileList",
            "$base/vcam/cmd.cgi?cmd=getfilelist",
            "$base/vcam/cmd.cgi",
            "$base/"
        )
        for (url in endpoints) {
            try {
                val req = Request.Builder().url(url).get().build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string().orEmpty()
                    val files = DashcamFileListParser.parse(body)
                    if (files.isNotEmpty()) {
                        logger.i("DashcamClient", "Listing OK via $url → ${files.size} files")
                        return@withContext files
                    }
                }
            } catch (e: Exception) {
                logger.w("DashcamClient", "Listing endpoint failed: $url (${e.message})")
            }
        }
        logger.w("DashcamClient", "No files found from any endpoint")
        emptyList()
    }

    suspend fun download(
        fileName: String,
        target: File,
        existingBytes: Long,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): Long = withContext(Dispatchers.IO) {
        val url = "$base/$fileName"
        val reqBuilder = Request.Builder().url(url).get()
        if (existingBytes > 0) reqBuilder.header("Range", "bytes=$existingBytes-")
        val request = reqBuilder.build()

        client.newCall(request).execute().use { resp ->
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
                    return@withContext written
                }
            }
        }
    }
}
