package com.feri.vrmirror.phone

import android.util.Log
import com.feri.vrmirror.common.MessageReader
import com.feri.vrmirror.common.MessageWriter
import com.feri.vrmirror.common.Protocol
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * TCP szerver: egyszerre egy Quest klienst szolgál ki.
 * Ha új kliens csatlakozik, a régit lecseréli.
 *
 * Minden küldés egy dedikált küldő szálon történik (a fő szálról tilos a hálózat),
 * és a sorrend garantált: konfiguráció -> codec config -> képkockák.
 * Ha a hálózat nem bírja az iramot, a képkockákat eldobjuk a következő kulcskockáig.
 */
class NetServer(private val listener: Listener) {

    interface Listener {
        fun onClientConnected(address: String)
        fun onClientDisconnected()
        fun onTouch(action: Int, x: Float, y: Float)
        fun onScroll(x: Float, y: Float, dx: Float, dy: Float)
        fun onKey(key: Int)
        fun onKeyframeRequested()
    }

    companion object {
        private const val TAG = "NetServer"
        /** Ennyi képkocka várakozhat küldésre; felette eldobás a következő kulcskockáig. */
        private const val MAX_QUEUED_FRAMES = 4
    }

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    @Volatile private var running = false

    private val lock = Any()
    private var client: Socket? = null
    @Volatile private var writer: MessageWriter? = null

    private val sender = Executors.newSingleThreadExecutor { r -> Thread(r, "NetServer-send") }
    private val queuedFrames = AtomicInteger(0)
    @Volatile private var dropUntilKeyframe = false

    @Volatile private var videoWidth = 0
    @Volatile private var videoHeight = 0
    @Volatile private var codecConfig: ByteArray? = null
    @Volatile private var statusFlags = 0

    val isClientConnected: Boolean get() = writer != null

    fun start() {
        running = true
        serverSocket = ServerSocket(Protocol.TCP_PORT).apply { reuseAddress = true }
        acceptThread = Thread(::acceptLoop, "NetServer-accept").apply { start() }
    }

    private fun acceptLoop() {
        while (running) {
            val socket = try {
                serverSocket?.accept() ?: break
            } catch (e: IOException) {
                break
            }
            Thread({ handleClient(socket) }, "NetServer-client").start()
        }
    }

    private fun handleClient(socket: Socket) {
        val address = socket.inetAddress?.hostAddress ?: "?"
        Log.i(TAG, "Kliens csatlakozott: $address")
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.sendBufferSize = 1024 * 1024
        } catch (_: Exception) {
        }

        val w = MessageWriter(socket.getOutputStream())
        val old: Socket?
        synchronized(lock) {
            old = client
            client = socket
            writer = w
            dropUntilKeyframe = true
            // A kezdő konfiguráció a küldő sorba kerül, még mielőtt bármelyik képkocka.
            submit(w) { sendInitialConfig(it) }
        }
        old?.let { closeQuietly(it) }

        try {
            listener.onClientConnected(address)
            val reader = MessageReader(socket.getInputStream())
            while (running && client === socket) {
                val msg = reader.read()
                when (msg.type) {
                    Protocol.MSG_TOUCH -> {
                        val bb = ByteBuffer.wrap(msg.payload)
                        val action = bb.get().toInt()
                        val x = bb.float
                        val y = bb.float
                        listener.onTouch(action, x, y)
                    }
                    Protocol.MSG_SCROLL -> {
                        val bb = ByteBuffer.wrap(msg.payload)
                        listener.onScroll(bb.float, bb.float, bb.float, bb.float)
                    }
                    Protocol.MSG_KEY -> listener.onKey(msg.payload[0].toInt())
                    Protocol.MSG_REQUEST_KEYFRAME -> listener.onKeyframeRequested()
                    else -> Log.w(TAG, "Ismeretlen üzenet: ${msg.type}")
                }
            }
        } catch (e: Exception) {
            Log.i(TAG, "Kliens kapcsolat lezárult ($address): ${e.message}")
        } finally {
            var wasCurrent = false
            synchronized(lock) {
                if (client === socket) {
                    client = null
                    writer = null
                    wasCurrent = true
                }
            }
            closeQuietly(socket)
            if (wasCurrent) listener.onClientDisconnected()
        }
    }

    private fun sendInitialConfig(w: MessageWriter) {
        w.write(Protocol.MSG_STATUS, ByteBuffer.allocate(4).putInt(statusFlags).array())
        if (videoWidth > 0 && videoHeight > 0) {
            w.write(Protocol.MSG_VIDEO_CONFIG, videoConfigPayload(videoWidth, videoHeight))
        }
        codecConfig?.let { w.write(Protocol.MSG_CODEC_CONFIG, it) }
    }

    private fun videoConfigPayload(w: Int, h: Int): ByteArray =
        ByteBuffer.allocate(8).putInt(w).putInt(h).array()

    /** Új videóméret (indításkor vagy elforgatáskor). */
    fun setVideoConfig(w: Int, h: Int) {
        videoWidth = w
        videoHeight = h
        codecConfig = null
        dropUntilKeyframe = true
        send { it.write(Protocol.MSG_VIDEO_CONFIG, videoConfigPayload(w, h)) }
    }

    /** Állapotjelzők (rögzítés fut-e, érintésvezérlés engedélyezve-e) – változáskor elküldi. */
    fun setStatus(flags: Int) {
        if (flags == statusFlags) return
        statusFlags = flags
        send { it.write(Protocol.MSG_STATUS, ByteBuffer.allocate(4).putInt(flags).array()) }
    }

    /** Rögzítés leállásakor: a régi SPS/PPS már nem érvényes. */
    fun clearVideo() {
        videoWidth = 0
        videoHeight = 0
        codecConfig = null
    }

    fun setCodecConfig(data: ByteArray) {
        codecConfig = data
        send { it.write(Protocol.MSG_CODEC_CONFIG, data) }
    }

    fun sendFrame(data: ByteArray, keyframe: Boolean, ptsUs: Long) {
        synchronized(lock) {
            val w = writer ?: return
            if (keyframe) {
                dropUntilKeyframe = false
            } else if (dropUntilKeyframe) {
                return
            }
            if (!keyframe && queuedFrames.get() >= MAX_QUEUED_FRAMES) {
                // A hálózat lemaradt: eldobjuk a kockákat a következő kulcskockáig, és kérünk egyet.
                Log.w(TAG, "Küldési sor tele, képkockák eldobása a következő kulcskockáig")
                dropUntilKeyframe = true
                listener.onKeyframeRequested()
                return
            }
            val header = ByteBuffer.allocate(12)
                .putInt(if (keyframe) Protocol.FRAME_FLAG_KEYFRAME else 0)
                .putLong(ptsUs)
                .array()
            queuedFrames.incrementAndGet()
            submit(w) {
                try {
                    it.write(Protocol.MSG_VIDEO_FRAME, header, data)
                } finally {
                    queuedFrames.decrementAndGet()
                }
            }
        }
    }

    /** Vezérlő üzenet küldése az aktuális kliensnek a küldő szálon. */
    private fun send(block: (MessageWriter) -> Unit) {
        synchronized(lock) {
            val w = writer ?: return
            submit(w, block)
        }
    }

    /** Csak lock alatt hívható. */
    private fun submit(w: MessageWriter, block: (MessageWriter) -> Unit) {
        if (sender.isShutdown) return
        sender.execute {
            try {
                block(w)
            } catch (e: IOException) {
                Log.w(TAG, "Küldés sikertelen: ${e.message}")
                val s: Socket?
                synchronized(lock) {
                    if (writer === w) {
                        s = client
                        writer = null
                        client = null
                    } else {
                        s = null
                    }
                }
                s?.let { closeQuietly(it) }
            }
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        val s: Socket?
        synchronized(lock) {
            s = client
            client = null
            writer = null
        }
        s?.let { closeQuietly(it) }
        sender.shutdownNow()
    }

    private fun closeQuietly(s: Socket) {
        try {
            s.close()
        } catch (_: Exception) {
        }
    }
}
