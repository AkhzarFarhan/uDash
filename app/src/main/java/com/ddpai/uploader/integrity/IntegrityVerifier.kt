package com.ddpai.uploader.integrity

import com.ddpai.uploader.data.repo.LogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class IntegrityVerifier(private val logger: LogRepository) {
    data class Verdict(val valid: Boolean, val reason: String)

    suspend fun verify(file: File): Verdict = withContext(Dispatchers.IO) {
        val scan = Mp4AtomScanner.scan(file)
        when {
            !scan.sizeOk -> Verdict(false, "size<1MB (${file.length()} bytes) — likely HTML error body")
            !scan.hasFtyp -> Verdict(false, "missing ftyp atom")
            !scan.hasMdat -> Verdict(false, "missing mdat atom")
            !scan.hasMoov -> Verdict(false, "missing moov atom — aborted/interrupted write")
            else -> Verdict(true, "ok")
        }.also {
            if (!it.valid) logger.w("IntegrityVerifier", "${file.name}: ${it.reason}")
        }
    }
}
