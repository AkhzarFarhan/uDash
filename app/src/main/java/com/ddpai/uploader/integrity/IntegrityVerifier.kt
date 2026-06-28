package com.ddpai.uploader.integrity

import com.ddpai.uploader.data.repo.LogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class IntegrityVerifier(private val logger: LogRepository) {
    data class Verdict(val valid: Boolean, val reason: String)

    suspend fun verify(file: File): Verdict = withContext(Dispatchers.IO) {
        val scan = Mp4AtomScanner.scan(file)
        if (!scan.sizeOk) return@withContext Verdict(
            false,
            "size<1MB (${file.length()} bytes) — likely HTML error body"
        )
        if (!scan.hasFtyp) return@withContext Verdict(false, "missing ftyp atom")
        if (!scan.hasMdat) return@withContext Verdict(false, "missing mdat atom")
        if (!scan.hasMoov) return@withContext Verdict(false, "missing moov atom — aborted/interrupted write")

        val probe = runFfprobe(file.absolutePath)
        if (probe != null && !probe) {
            return@withContext Verdict(false, "FFprobe could not read stream index")
        }
        Verdict(true, "ok")
    }

    private fun runFfprobe(path: String): Boolean? = try {
        val session = com.arthenica.ffmpegkit.FFprobeKit.execute(
            "-v error -show_entries format=duration -of default=nw=1 \"$path\""
        )
        val rc = session.returnCode
        when {
            com.arthenica.ffmpegkit.ReturnCode.isSuccess(rc) -> {
                val out = session.output.orEmpty()
                out.contains("duration=") && !out.contains("N/A")
            }
            else -> false
        }
    } catch (t: Throwable) {
        logger.w("IntegrityVerifier", "FFprobe unavailable, relying on atom scan: ${t.message}")
        null
    }
}
