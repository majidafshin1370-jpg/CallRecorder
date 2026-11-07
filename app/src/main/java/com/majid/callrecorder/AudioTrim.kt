package com.majid.callrecorder

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

object AudioTrim {

    /**
     * Keeps only the last [keepMs] of an .m4a file (plus a small margin) without re-encoding.
     * Returns true if the file was replaced by the shorter version.
     */
    fun keepLast(file: File, keepMs: Long, marginMs: Long = 500): Boolean {
        val tmp = File(file.parentFile, file.name + ".tmp")
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var success = false
        try {
            extractor.setDataSource(file.absolutePath)

            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    format = f
                    break
                }
            }
            if (track < 0 || format == null) return false
            if (!format.containsKey(MediaFormat.KEY_DURATION)) return false

            val totalUs = format.getLong(MediaFormat.KEY_DURATION)
            val startUs = totalUs - (keepMs + marginMs) * 1000
            if (startUs < 1_000_000) return false // less than 1 s to cut: not worth it

            extractor.selectTrack(track)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val m = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            val outTrack = m.addTrack(format)
            m.start()

            val buffer = ByteBuffer.allocate(1 shl 20)
            val info = MediaCodec.BufferInfo()
            var base = -1L
            var written = 0
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val t = extractor.sampleTime
                if (base < 0) base = t
                info.set(0, size, t - base, extractor.sampleFlags)
                m.writeSampleData(outTrack, buffer, info)
                written++
                extractor.advance()
            }
            m.stop()
            m.release()
            muxer = null

            if (written == 0) return false
            if (file.delete() && tmp.renameTo(file)) {
                success = true
            }
            return success
        } catch (e: Exception) {
            return false
        } finally {
            try { muxer?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
            if (!success) tmp.delete()
        }
    }
}
