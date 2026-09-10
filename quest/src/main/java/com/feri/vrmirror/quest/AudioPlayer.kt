package com.feri.vrmirror.quest

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A telefonról érkező PCM hang lejátszása. Saját szálon ír az AudioTrack-be,
 * hogy a hálózati szálat ne blokkolja. Ha lemarad, a legrégebbi csomagokat eldobja.
 */
class AudioPlayer(sampleRate: Int, channels: Int) {

    companion object {
        private const val TAG = "AudioPlayer"
        /** Ennyi csomag (20 ms) várakozhat; felette a régieket eldobjuk. */
        private const val MAX_QUEUE = 12
    }

    private val track: AudioTrack
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val thread: Thread
    @Volatile private var running = true

    init {
        val channelMask = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        // 20 ms csomagméret
        val chunk = sampleRate / 50 * channels * 2
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, chunk * 6))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        track.play()
        thread = Thread(::loop, "AudioPlayer").apply { start() }
        Log.i(TAG, "Hang: $sampleRate Hz, $channels csatorna")
    }

    fun enqueue(data: ByteArray) {
        if (!running) return
        while (queue.size >= MAX_QUEUE) queue.poll()
        queue.offer(data)
    }

    private fun loop() {
        while (running) {
            val chunk = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
            try {
                track.write(chunk, 0, chunk.size)
            } catch (e: Exception) {
                Log.w(TAG, "AudioTrack.write hiba: ${e.message}")
            }
        }
    }

    fun release() {
        running = false
        queue.clear()
        thread.interrupt()
        try {
            track.pause()
            track.flush()
            track.release()
        } catch (_: Exception) {
        }
    }
}
