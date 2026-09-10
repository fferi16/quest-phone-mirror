package com.feri.vrmirror.phone

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Hardveres H.264 kódoló, amelynek a bemenete egy Surface.
 * A MediaProjection virtuális kijelzője erre a Surface-re rajzol,
 * a kódoló kimenetét pedig a [Listener] kapja meg képkockánként.
 */
class ScreenEncoder(val width: Int, val height: Int, private val listener: Listener) {

    interface Listener {
        /** SPS/PPS – ezt minden új kliensnek el kell küldeni a képkockák előtt. */
        fun onCodecConfig(data: ByteArray)
        fun onFrame(data: ByteArray, keyframe: Boolean, ptsUs: Long)
    }

    companion object {
        private const val TAG = "ScreenEncoder"
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        const val BITRATE = 8_000_000
        const val FRAME_RATE = 60
        const val MAX_LONG_EDGE = 1280

        /** A képernyő méretét levágja a kódolónak megfelelő méretre (max 1280 a hosszabb oldal, 8-cal osztható). */
        fun chooseSize(w: Int, h: Int): Pair<Int, Int> {
            val longEdge = max(w, h)
            val scale = if (longEdge > MAX_LONG_EDGE) MAX_LONG_EDGE.toFloat() / longEdge else 1f
            fun fit(v: Int) = max(64, ((v * scale).roundToInt() / 8) * 8)
            return Pair(fit(w), fit(h))
        }
    }

    private val codec: MediaCodec
    val inputSurface: Surface
    private var thread: Thread? = null
    @Volatile private var running = false

    init {
        codec = MediaCodec.createEncoderByType(MIME)
        try {
            codec.configure(buildFormat(cbr = true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            // Néhány kódoló nem támogatja a CBR módot – próbáljuk anélkül.
            Log.w(TAG, "CBR konfiguráció nem sikerült, VBR-rel próbálom: ${e.message}")
            codec.reset()
            codec.configure(buildFormat(cbr = false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        inputSurface = codec.createInputSurface()
    }

    private fun buildFormat(cbr: Boolean): MediaFormat {
        return MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            // Valós idejű prioritás, a lehető legkisebb késleltetés.
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_LATENCY, 1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
            if (cbr) {
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                )
            }
        }
    }

    fun start() {
        running = true
        codec.start()
        thread = Thread(::drainLoop, "ScreenEncoder").apply { start() }
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = try {
                codec.dequeueOutputBuffer(info, 100_000)
            } catch (e: IllegalStateException) {
                break
            }
            if (index < 0) continue
            try {
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null && info.size > 0) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val data = ByteArray(info.size)
                    buffer.get(data)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        listener.onCodecConfig(data)
                    } else {
                        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        listener.onFrame(data, key, info.presentationTimeUs)
                    }
                }
            } finally {
                try {
                    codec.releaseOutputBuffer(index, false)
                } catch (_: IllegalStateException) {
                }
            }
        }
    }

    /** Kulcskocka kérése – új kliens csatlakozásakor, hogy azonnal legyen képe. */
    fun requestKeyframe() {
        try {
            codec.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        } catch (e: Exception) {
            Log.w(TAG, "Kulcskocka kérés sikertelen: ${e.message}")
        }
    }

    fun stop() {
        running = false
        try {
            codec.stop()
        } catch (_: Exception) {
        }
        thread?.join(1000)
        thread = null
        try {
            codec.release()
        } catch (_: Exception) {
        }
        inputSurface.release()
    }
}
