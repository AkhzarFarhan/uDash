package com.ddpai.uploader.integrity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile

class Mp4AtomScannerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testValidMp4() {
        val file = tempFolder.newFile("valid.mp4")
        RandomAccessFile(file, "rw").use { raf ->
            // ftyp atom: size 16
            raf.writeInt(16)
            raf.write("ftyp".toByteArray(Charsets.US_ASCII))
            raf.write(ByteArray(8))

            // mdat atom: size 16
            raf.writeInt(16)
            raf.write("mdat".toByteArray(Charsets.US_ASCII))
            raf.write(ByteArray(8))

            // moov atom: size 16
            raf.writeInt(16)
            raf.write("moov".toByteArray(Charsets.US_ASCII))
            raf.write(ByteArray(8))

            // Make file size >= 1MB (1,048,576 bytes)
            raf.setLength(1_048_576L)
        }

        val result = Mp4AtomScanner.scan(file)
        assertTrue(result.sizeOk)
        assertTrue(result.hasFtyp)
        assertTrue(result.hasMdat)
        assertTrue(result.hasMoov)
        assertTrue(result.isValid)
    }

    @Test
    fun testMissingMoov() {
        val file = tempFolder.newFile("missing_moov.mp4")
        RandomAccessFile(file, "rw").use { raf ->
            // ftyp
            raf.writeInt(16)
            raf.write("ftyp".toByteArray(Charsets.US_ASCII))
            raf.write(ByteArray(8))

            // mdat
            raf.writeInt(16)
            raf.write("mdat".toByteArray(Charsets.US_ASCII))
            raf.write(ByteArray(8))

            raf.setLength(1_048_576L)
        }

        val result = Mp4AtomScanner.scan(file)
        assertTrue(result.sizeOk)
        assertTrue(result.hasFtyp)
        assertTrue(result.hasMdat)
        assertFalse(result.hasMoov)
        assertFalse(result.isValid)
    }

    @Test
    fun testSmallHtml() {
        val file = tempFolder.newFile("error.html")
        file.writeText("<html><body>Error 500: Gateway timeout</body></html>")

        val result = Mp4AtomScanner.scan(file)
        assertFalse(result.sizeOk)
        assertFalse(result.isValid)
    }
}
