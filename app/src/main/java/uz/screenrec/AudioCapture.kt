package uz.screenrec

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * O'yin tovushi (va ixtiyoriy mikrofon) ni yozadi va AAC formatida alohida faylga saqlaydi.
 * Video va tovush keyin Mux.run() bilan birlashtiriladi (qayta kodlashsiz, tez).
 */
class AudioCapture(
    private val projection: MediaProjection,
    private val useGame: Boolean,
    private val useMic: Boolean,
    private val outFile: File
) {
    private val sampleRate = 44100

    @Volatile private var active = false
    private var thread: Thread? = null
    private var game: AudioRecord? = null
    private var mic: AudioRecord? = null

    @SuppressLint("MissingPermission")
    fun start() {
        val stereo = AudioFormat.CHANNEL_IN_STEREO
        val mono = AudioFormat.CHANNEL_IN_MONO
        val enc = AudioFormat.ENCODING_PCM_16BIT

        if (useGame) {
            val fmt = AudioFormat.Builder()
                .setEncoding(enc)
                .setSampleRate(sampleRate)
                .setChannelMask(stereo)
                .build()
            val cfg = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val size = max(AudioRecord.getMinBufferSize(sampleRate, stereo, enc) * 2, 16384)
            game = AudioRecord.Builder()
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(size)
                .setAudioPlaybackCaptureConfig(cfg)
                .build()
        }
        if (useMic) {
            val size = max(AudioRecord.getMinBufferSize(sampleRate, mono, enc) * 2, 8192)
            mic = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, mono, enc, size)
        }

        val g = game
        val m = mic
        if ((g != null && g.state != AudioRecord.STATE_INITIALIZED) ||
            (m != null && m.state != AudioRecord.STATE_INITIALIZED)
        ) {
            release()
            throw IllegalStateException("Tovush yozish ishga tushmadi")
        }

        active = true
        thread = Thread { loop() }.also { it.start() }
    }

    fun stop() {
        active = false
        try { thread?.join(5000) } catch (_: InterruptedException) { }
        thread = null
    }

    private fun release() {
        try { game?.release() } catch (_: Exception) { }
        try { mic?.release() } catch (_: Exception) { }
        game = null
        mic = null
    }

    private fun clip(v: Int): Short = v.coerceIn(-32768, 32767).toShort()

    private fun loop() {
        val frames = 2048
        val gBuf = ShortArray(frames * 2)
        val mBuf = ShortArray(frames)
        val pcm = ShortArray(frames * 2)
        val info = MediaCodec.BufferInfo()

        var codecRef: MediaCodec? = null
        var muxerRef: MediaMuxer? = null
        var muxStarted = false
        var track = -1
        var total = 0L

        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codecRef = codec
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxerRef = muxer

            // true qaytaradi: oqim tugadi
            fun drain(timeoutUs: Long): Boolean {
                while (true) {
                    val oi = codec.dequeueOutputBuffer(info, timeoutUs)
                    if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) return false
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxStarted = true
                        continue
                    }
                    if (oi < 0) continue
                    val ob = codec.getOutputBuffer(oi)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (ob != null && info.size > 0 && muxStarted) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        muxer.writeSampleData(track, ob, info)
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                }
            }

            game?.startRecording()
            mic?.startRecording()

            while (active) {
                drain(0)

                var gn = 0
                game?.let { gn = it.read(gBuf, 0, gBuf.size); if (gn < 0) gn = 0 }
                if (gn < gBuf.size) java.util.Arrays.fill(gBuf, gn, gBuf.size, 0.toShort())

                var mn = 0
                mic?.let { mn = it.read(mBuf, 0, mBuf.size); if (mn < 0) mn = 0 }
                if (mn < mBuf.size) java.util.Arrays.fill(mBuf, mn, mBuf.size, 0.toShort())

                for (i in 0 until frames) {
                    val mv = mBuf[i].toInt()
                    pcm[2 * i] = clip(gBuf[2 * i].toInt() + mv)
                    pcm[2 * i + 1] = clip(gBuf[2 * i + 1].toInt() + mv)
                }

                val ii = codec.dequeueInputBuffer(10_000)
                if (ii >= 0) {
                    val ib = codec.getInputBuffer(ii)!!
                    ib.clear()
                    ib.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm)
                    codec.queueInputBuffer(ii, 0, pcm.size * 2, total * 1_000_000L / sampleRate, 0)
                    total += frames
                }
            }

            // oqimni yakunlash
            var sent = false
            var tries = 0
            while (!sent && tries < 20) {
                val ii = codec.dequeueInputBuffer(10_000)
                if (ii >= 0) {
                    codec.queueInputBuffer(
                        ii, 0, 0, total * 1_000_000L / sampleRate,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                    sent = true
                } else {
                    drain(0)
                    tries++
                }
            }
            var t = 0
            while (t < 50 && !drain(20_000)) t++
        } catch (_: Exception) {
        } finally {
            try { game?.stop() } catch (_: Exception) { }
            try { mic?.stop() } catch (_: Exception) { }
            release()
            try { codecRef?.stop() } catch (_: Exception) { }
            try { codecRef?.release() } catch (_: Exception) { }
            try { if (muxStarted) muxerRef?.stop() } catch (_: Exception) { }
            try { muxerRef?.release() } catch (_: Exception) { }
        }
    }
}

/** Tayyor video fayl + tayyor audio fayldan bitta MP4 yasaydi (qayta kodlashsiz). */
object Mux {
    fun run(video: File, audio: File, out: FileDescriptor) {
        val muxer = MediaMuxer(out, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val vEx = MediaExtractor()
        val aEx = MediaExtractor()
        var started = false
        try {
            vEx.setDataSource(video.absolutePath)
            aEx.setDataSource(audio.absolutePath)
            val vi = pick(vEx, "video/")
            val ai = pick(aEx, "audio/")
            vEx.selectTrack(vi)
            aEx.selectTrack(ai)
            val vt = muxer.addTrack(vEx.getTrackFormat(vi))
            val at = muxer.addTrack(aEx.getTrackFormat(ai))
            muxer.start()
            started = true
            copy(vEx, muxer, vt)
            copy(aEx, muxer, at)
            muxer.stop()
            started = false
        } finally {
            try { vEx.release() } catch (_: Exception) { }
            try { aEx.release() } catch (_: Exception) { }
            try { if (started) muxer.stop() } catch (_: Exception) { }
            try { muxer.release() } catch (_: Exception) { }
        }
    }

    private fun pick(ex: MediaExtractor, prefix: String): Int {
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) return i
        }
        throw IllegalStateException("$prefix trek topilmadi")
    }

    private fun copy(ex: MediaExtractor, muxer: MediaMuxer, track: Int) {
        val buf = ByteBuffer.allocate(4 shl 20)
        val info = MediaCodec.BufferInfo()
        while (true) {
            buf.clear()
            val size = ex.readSampleData(buf, 0)
            if (size < 0) break
            val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            info.set(0, size, ex.sampleTime, flags)
            muxer.writeSampleData(track, buf, info)
            ex.advance()
        }
    }
}
