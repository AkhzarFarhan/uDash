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
    private val logger: LogRepository,
    private val dashcamType: String = "AUTODETECT"
) {
    private val client = BoundHttpClientFactory.forNetwork(network)
    private val base = "http://$gateway"
    private var resolvedProtocol: DashcamProtocol? = null

    private suspend fun resolveProtocol(): DashcamProtocol {
        resolvedProtocol?.let { return it }

        val type = if (dashcamType == "AUTODETECT") {
            autodetectType()
        } else {
            dashcamType
        }

        val proto = when (type) {
            "DDPAI" -> DdpaiProtocol(logger)
            "NOVATEK_GENERIC" -> NovatekGenericProtocol(logger)
            else -> DdpaiProtocol(logger) // default fallback
        }
        
        logger.i("DashcamClient", "Resolved active protocol: $type")
        resolvedProtocol = proto
        return proto
    }

    private suspend fun autodetectType(): String {
        logger.d("DashcamClient", "Autodetecting dashcam protocol via endpoint probing...")
        
        // 1. Try DDPAI Protocol handshake first
        val ddpai = DdpaiProtocol(logger)
        val ddpaiOk = ddpai.handshake(client, base)
        if (ddpaiOk) {
            logger.i("DashcamClient", "Autodetect: DDPAI protocol confirmed")
            return "DDPAI"
        }

        // 2. Try Novatek Generic Index directory crawling
        val generic = NovatekGenericProtocol(logger)
        val genericFiles = generic.listFiles(client, base)
        if (genericFiles.isNotEmpty()) {
            logger.i("DashcamClient", "Autodetect: Generic Web / Novatek protocol confirmed")
            return "NOVATEK_GENERIC"
        }

        // 3. Unrecognized default fallback
        logger.w("DashcamClient", "Autodetect failed to determine camera type. Defaulting to DDPAI")
        return "DDPAI"
    }

    suspend fun listFiles(): List<DashcamFile> {
        val proto = resolveProtocol()
        return proto.listFiles(client, base)
    }

    suspend fun download(
        fileName: String,
        target: File,
        existingBytes: Long,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): Long = withContext(Dispatchers.IO) {
        val proto = resolveProtocol()
        val rangeHeader = if (existingBytes > 0) "bytes=$existingBytes-" else null
        val request = proto.buildDownloadRequest(base, fileName, rangeHeader)

        client.newCall(request).execute().use { resp ->
            logger.d("DashcamClient", "download response: HTTP ${resp.code}, content-length=${resp.header("Content-Length")}")
            if (!resp.isSuccessful && resp.code != 206) {
                throw IOException("HTTP ${resp.code} for $fileName")
            }
            val body = resp.body ?: throw IOException("Empty body for $fileName")
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
