package com.ddpai.uploader.dashcam

import com.ddpai.uploader.data.model.DashcamFile
import com.ddpai.uploader.data.repo.LogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI

class NovatekGenericProtocol(private val logger: LogRepository) : DashcamProtocol {
    private val HREF_MP4_RE = Regex("""href\s*=\s*["']([^"']+\.mp4)["']""", RegexOption.IGNORE_CASE)

    override suspend fun handshake(client: OkHttpClient, base: String): Boolean {
        return true
    }

    override suspend fun listFiles(client: OkHttpClient, base: String): List<DashcamFile> = withContext(Dispatchers.IO) {
        val endpoints = listOf(
            "$base/",
            "$base/DCIM/",
            "$base/sd/",
            "$base/index.html"
        )
        val allFiles = mutableListOf<DashcamFile>()
        val gatewayUri = URI(base)

        for (url in endpoints) {
            try {
                logger.d("NovatekGenericProtocol", "Scanning directory index: $url")
                val req = Request.Builder().url(url).get().build()
                client.newCall(req).execute().use { resp ->
                    logger.d("NovatekGenericProtocol", "Directory index response from $url → HTTP ${resp.code}")
                    if (!resp.isSuccessful) return@use

                    val body = resp.body?.string().orEmpty()
                    val matches = HREF_MP4_RE.findAll(body)
                    
                    val baseUri = URI(url)
                    val parsed = matches.map { it.groupValues[1] }.distinct().mapNotNull { path ->
                        try {
                            val resolvedUri = baseUri.resolve(path)
                            val relativePath = gatewayUri.relativize(resolvedUri).path.removePrefix("/")
                            val fileName = relativePath.substringAfterLast("/")
                            val capturedAt = DashcamParserHelper.parseCapturedAt(fileName)
                            
                            DashcamFile(
                                fileName = relativePath,
                                sizeBytes = -1L,
                                capturedAtEpoch = if (capturedAt > 0) capturedAt else System.currentTimeMillis()
                            )
                        } catch (e: Exception) {
                            logger.w("NovatekGenericProtocol", "Error resolving path $path: ${e.message}")
                            null
                        }
                    }.toList()

                    if (parsed.isNotEmpty()) {
                        logger.i("NovatekGenericProtocol", "Discovered ${parsed.size} mp4 files on $url")
                        allFiles.addAll(parsed)
                    }
                }
            } catch (e: Exception) {
                logger.w("NovatekGenericProtocol", "Failed scanning index $url: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        allFiles.distinctBy { it.fileName }
    }

    override fun buildDownloadRequest(base: String, fileName: String, rangeHeader: String?): Request {
        val url = "$base/$fileName"
        val builder = Request.Builder().url(url).get()
        if (rangeHeader != null) {
            builder.header("Range", rangeHeader)
        }
        return builder.build()
    }
}
