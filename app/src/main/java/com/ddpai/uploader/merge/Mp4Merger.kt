package com.ddpai.uploader.merge

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Concatenates MP4 segments into one file by copying encoded samples (no re-encode) with
 * presentation-timestamp offsets. All inputs must share the same track layout/format as the first.
 */
class Mp4Merger {
    sealed interface Outcome {
        data object Success : Outcome
        data class FormatMismatch(val atIndex: Int) : Outcome
        data class Error(val message: String) : Outcome
    }

    fun merge(inputs: List<File>, output: File): Outcome {
        if (inputs.isEmpty()) return Outcome.Error("no inputs")
        var muxer: MediaMuxer? = null
        var started = false
        try {
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            // Establish tracks from the first segment; also find the largest declared sample size.
            val head = MediaExtractor().apply { setDataSource(inputs[0].absolutePath) }
            val muxTrackIndex = IntArray(head.trackCount)
            val headSignature = ArrayList<String>(head.trackCount)
            var maxInputSize = 0
            for (t in 0 until head.trackCount) {
                val fmt = head.getTrackFormat(t)
                headSignature.add(fmt.signature())
                muxTrackIndex[t] = muxer.addTrack(fmt)
                if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    maxInputSize = maxOf(maxInputSize, fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                }
            }
            head.release()

            muxer.start()
            started = true
            // Size the sample buffer from the tracks' declared max input size (with a generous floor),
            // so a large keyframe can never overflow and silently truncate a track.
            val buffer = ByteBuffer.allocate(maxOf(maxInputSize, 4 * 1024 * 1024))
            var timeOffsetUs = 0L

            for ((index, input) in inputs.withIndex()) {
                val extractor = MediaExtractor().apply { setDataSource(input.absolutePath) }
                try {
                    if (extractor.trackCount != muxTrackIndex.size ||
                        (0 until extractor.trackCount).map { extractor.getTrackFormat(it).signature() } != headSignature
                    ) {
                        return Outcome.FormatMismatch(index)
                    }
                    var maxPtsThisSegment = timeOffsetUs
                    for (t in 0 until extractor.trackCount) {
                        extractor.selectTrack(t)
                        val bufferInfo = MediaCodec.BufferInfo()
                        while (true) {
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) break
                            val pts = extractor.sampleTime + timeOffsetUs
                            bufferInfo.set(0, size, pts, extractor.sampleFlags)
                            muxer.writeSampleData(muxTrackIndex[t], buffer, bufferInfo)
                            if (pts > maxPtsThisSegment) maxPtsThisSegment = pts
                            extractor.advance()
                        }
                        extractor.unselectTrack(t)
                    }
                    // Next segment starts after this one (+ ~1 frame at 30fps); never regress the offset.
                    timeOffsetUs = maxPtsThisSegment + 33_333L
                } finally {
                    extractor.release()
                }
            }
            // Finalize explicitly so a stop() failure (e.g. moov not written) surfaces as Error, not Success.
            muxer.stop()
            started = false
            return Outcome.Success
        } catch (e: Exception) {
            return Outcome.Error(e.message ?: e.javaClass.simpleName)
        } finally {
            if (started) { try { muxer?.stop() } catch (_: Exception) {} }
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    private fun MediaFormat.signature(): String {
        val mime = getString(MediaFormat.KEY_MIME) ?: "?"
        fun intOrZero(key: String) = if (containsKey(key)) getInteger(key) else 0
        return listOf(
            mime,
            intOrZero(MediaFormat.KEY_WIDTH),
            intOrZero(MediaFormat.KEY_HEIGHT),
            intOrZero(MediaFormat.KEY_SAMPLE_RATE),
            intOrZero(MediaFormat.KEY_CHANNEL_COUNT)
        ).joinToString("|")
    }
}
