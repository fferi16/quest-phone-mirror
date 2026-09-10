package com.feri.vrmirror.quest

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Hardveres H.264 dekóder, amely közvetlenül a megadott Surface-re rajzol.
 * Aszinkron módban fut: minden kész képkockát azonnal megjelenít.
 */
class VideoDecoder(
    width: Int,
    height: Int,
    surface: Surface,
    private val onError: (Exception) -> Unit
) {
    companion object {
        private const val TAG = "VideoDecoder"
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    }

    private val codec: MediaCodec = MediaCodec.createDecoderByType(MIME)
    private val thread = HandlerThread("VideoDecoder").apply { start() }
    private val handler = Handler(thread.looper)
    private val freeInputs = LinkedBlockingQueue<Int>()

    @Volatile private var released = false

    /** Igaz, ha már kapott SPS/PPS-t. */
    @Volatile var hasCodecConfig = false
        private set

    init {
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(c: MediaCodec, index: Int) {
                freeInputs.offer(index)
            }

            override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                try {
                    // true = azonnal a Surface-re rajzolja
                    c.releaseOutputBuffer(index, true)
                } catch (_: IllegalStateException) {
                }
            }

            override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(TAG, "Dekóder hiba: ${e.diagnosticInfo}")
                onError(e)
            }

            override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) {
                Log.i(TAG, "Kimeneti formátum: $format")
            }
        }, handler)

        try {
            codec.configure(buildFormat(width, height, lowLatency = true), surface, null, 0)
        } catch (e: Exception) {
            Log.w(TAG, "Alacsony késleltetésű mód nem támogatott, normál mód: ${e.message}")
            codec.reset()
            codec.configure(buildFormat(width, height, lowLatency = false), surface, null, 0)
        }
        codec.start()
    }

    private fun buildFormat(width: Int, height: Int, lowLatency: Boolean): MediaFormat {
        return MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
            if (lowLatency) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                // Qualcomm-specifikus kulcs (a Quest Snapdragon XR2 chipje ezt ismeri).
                setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
            }
        }
    }

    /**
     * Egy bemeneti egység (SPS/PPS vagy képkocka) betöltése a dekóderbe.
     * @return false, ha nem sikerült (pl. nincs szabad puffer időben).
     */
    fun feed(data: ByteArray, offset: Int, length: Int, ptsUs: Long, codecConfig: Boolean): Boolean {
        if (released) return false
        val index = freeInputs.poll(300, TimeUnit.MILLISECONDS) ?: return false
        if (released) return false
        return try {
            val buffer = codec.getInputBuffer(index) ?: return false
            buffer.clear()
            if (length > buffer.capacity()) {
                Log.w(TAG, "Túl nagy képkocka ($length > ${buffer.capacity()}), eldobva")
                codec.queueInputBuffer(index, 0, 0, 0, 0)
                return false
            }
            buffer.put(data, offset, length)
            val flags = if (codecConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
            codec.queueInputBuffer(index, 0, length, ptsUs, flags)
            if (codecConfig) hasCodecConfig = true
            true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "queueInputBuffer hiba: ${e.message}")
            false
        }
    }

    fun release() {
        released = true
        freeInputs.clear()
        try {
            codec.stop()
        } catch (_: Exception) {
        }
        try {
            codec.release()
        } catch (_: Exception) {
        }
        thread.quitSafely()
    }
}
