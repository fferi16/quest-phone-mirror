package com.feri.vrmirror.quest

import android.util.Log
import com.feri.vrmirror.common.MessageReader
import com.feri.vrmirror.common.MessageWriter
import com.feri.vrmirror.common.Protocol
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * TCP kliens a telefonhoz. Saját szálon fut, kapcsolatvesztéskor
 * néhány másodperc múlva automatikusan újrapróbálkozik.
 * A visszahívások a hálózati szálon érkeznek!
 */
class StreamClient(private val host: String, private val listener: Listener) {

    interface Listener {
        fun onConnected()
        fun onDisconnected(reason: String)
        fun onVideoConfig(width: Int, height: Int)
        fun onCodecConfig(data: ByteArray)
        fun onFrame(data: ByteArray, offset: Int, length: Int, keyframe: Boolean, ptsUs: Long)
        /** A telefon állapota: Protocol.STATUS_FLAG_* bitek. */
        fun onStatus(flags: Int)
    }

    companion object {
        private const val TAG = "StreamClient"
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val RETRY_DELAY_MS = 2000L
    }

    @Volatile private var running = true
    @Volatile private var socket: Socket? = null
    @Volatile private var writer: MessageWriter? = null
    private val thread = Thread(::run, "StreamClient")
    private val sender = Executors.newSingleThreadExecutor { r -> Thread(r, "StreamClient-send") }

    fun start() = thread.start()

    private fun run() {
        while (running) {
            val s = Socket()
            try {
                s.connect(InetSocketAddress(host, Protocol.TCP_PORT), CONNECT_TIMEOUT_MS)
                s.tcpNoDelay = true
                s.keepAlive = true
                s.receiveBufferSize = 1024 * 1024
                socket = s
                writer = MessageWriter(s.getOutputStream())
                listener.onConnected()

                val reader = MessageReader(s.getInputStream())
                while (running) {
                    val msg = reader.read()
                    when (msg.type) {
                        Protocol.MSG_VIDEO_CONFIG -> {
                            val bb = ByteBuffer.wrap(msg.payload)
                            listener.onVideoConfig(bb.int, bb.int)
                        }
                        Protocol.MSG_CODEC_CONFIG -> listener.onCodecConfig(msg.payload)
                        Protocol.MSG_STATUS -> listener.onStatus(ByteBuffer.wrap(msg.payload).int)
                        Protocol.MSG_VIDEO_FRAME -> {
                            val bb = ByteBuffer.wrap(msg.payload)
                            val flags = bb.int
                            val pts = bb.long
                            listener.onFrame(
                                msg.payload, 12, msg.payload.size - 12,
                                flags and Protocol.FRAME_FLAG_KEYFRAME != 0, pts
                            )
                        }
                        else -> Log.w(TAG, "Ismeretlen üzenet: ${msg.type}")
                    }
                }
            } catch (e: Exception) {
                if (running) {
                    Log.i(TAG, "Kapcsolat hiba: ${e.message}")
                    listener.onDisconnected(e.message ?: e.javaClass.simpleName)
                }
            } finally {
                writer = null
                socket = null
                try {
                    s.close()
                } catch (_: Exception) {
                }
            }
            if (running) {
                try {
                    Thread.sleep(RETRY_DELAY_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    val isConnected: Boolean get() = writer != null

    fun sendTouch(action: Int, x: Float, y: Float) {
        val payload = ByteBuffer.allocate(9)
            .put(action.toByte())
            .putFloat(x)
            .putFloat(y)
            .array()
        send(Protocol.MSG_TOUCH, payload)
    }

    fun sendKey(key: Int) {
        send(Protocol.MSG_KEY, byteArrayOf(key.toByte()))
    }

    fun requestKeyframe() {
        send(Protocol.MSG_REQUEST_KEYFRAME, Protocol.EMPTY)
    }

    private fun send(type: Byte, payload: ByteArray) {
        val w = writer ?: return
        if (sender.isShutdown) return
        sender.execute {
            try {
                w.write(type, payload)
            } catch (e: IOException) {
                Log.w(TAG, "Küldés sikertelen: ${e.message}")
                try {
                    socket?.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    fun close() {
        running = false
        sender.shutdownNow()
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        thread.interrupt()
    }
}
