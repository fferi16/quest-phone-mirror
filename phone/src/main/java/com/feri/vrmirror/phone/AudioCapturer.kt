package com.feri.vrmirror.phone

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.util.Log

/**
 * A telefonon lejátszott hang elkapása (AudioPlaybackCapture, Android 10+).
 * Csak azoknak az appoknak a hangját adja, amelyek engedik a rögzítést
 * (a legtöbb média/játék app igen, a telefonhívás és a DRM-es tartalom nem).
 */
class AudioCapturer(
    projection: MediaProjection,
    private val listener: Listener
) {
    interface Listener {
        fun onAudioConfig(sampleRate: Int, channels: Int)
        fun onAudio(data: ByteArray, length: Int)
    }

    companion object {
        private const val TAG = "AudioCapturer"
        const val SAMPLE_RATE = 48000
        const val CHANNELS = 2
        /** Egy csomag hossza: 20 ms. */
        private const val CHUNK_FRAMES = SAMPLE_RATE / 50
        private const val CHUNK_BYTES = CHUNK_FRAMES * CHANNELS * 2
    }

    private val record: AudioRecord
    private var thread: Thread? = null
    @Volatile private var running = false

    init {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setAudioPlaybackCaptureConfig(config)
            .setBufferSizeInBytes(maxOf(minBuf, CHUNK_BYTES * 4))
            .build()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord nem inicializálható")
        }
    }

    fun start() {
        running = true
        record.startRecording()
        listener.onAudioConfig(SAMPLE_RATE, CHANNELS)
        thread = Thread(::loop, "AudioCapturer").apply { start() }
    }

    private fun loop() {
        val buf = ByteArray(CHUNK_BYTES)
        while (running) {
            val n = try {
                record.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
            } catch (e: Exception) {
                break
            }
            if (n > 0) {
                listener.onAudio(buf.copyOf(n), n)
            } else if (n < 0) {
                Log.w(TAG, "AudioRecord.read hiba: $n")
                break
            }
        }
    }

    fun stop() {
        running = false
        try {
            record.stop()
        } catch (_: Exception) {
        }
        thread?.join(500)
        thread = null
        try {
            record.release()
        } catch (_: Exception) {
        }
    }
}
