package com.ddpai.uploader.integrity

import java.io.File
import java.io.RandomAccessFile

object Mp4AtomScanner {
    data class Result(
        val hasFtyp: Boolean,
        val hasMoov: Boolean,
        val hasMdat: Boolean,
        val sizeOk: Boolean
    ) {
        val isValid get() = hasFtyp && hasMoov && hasMdat && sizeOk
    }
    private const val MIN_SIZE = 1_048_576L // 1 MB

    fun scan(file: File): Result {
        if (!file.exists()) return Result(false, false, false, false)
        val sizeOk = file.length() >= MIN_SIZE
        var hasFtyp = false
        var hasMoov = false
        var hasMdat = false
        try {
            RandomAccessFile(file, "r").use { raf ->
                val len = raf.length()
                var pos = 0L
                while (pos + 8 <= len) {
                    raf.seek(pos)
                    val size32 = raf.readInt().toLong() and 0xFFFFFFFFL
                    val typeBytes = ByteArray(4)
                    raf.readFully(typeBytes)
                    val type = typeBytes.toString(Charsets.US_ASCII)
                    val (atomSize, headerSize) = when (size32) {
                        1L -> Pair(raf.readLong(), 16L) // 64-bit size
                        0L -> Pair(len - pos, 8L)       // to EOF
                        else -> Pair(size32, 8L)
                    }
                    when (type) {
                        "ftyp" -> hasFtyp = true
                        "moov" -> hasMoov = true
                        "mdat" -> hasMdat = true
                    }
                    if (atomSize < headerSize) break // malformed; stop
                    pos += atomSize
                    if (hasFtyp && hasMoov && hasMdat) break
                }
            }
        } catch (e: Exception) {
            // treat as failure
        }
        return Result(hasFtyp, hasMoov, hasMdat, sizeOk)
    }
}
