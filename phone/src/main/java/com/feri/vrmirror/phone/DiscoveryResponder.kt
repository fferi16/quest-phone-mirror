package com.feri.vrmirror.phone

import android.util.Log
import com.feri.vrmirror.common.Protocol
import java.net.DatagramPacket
import java.net.DatagramSocket

/**
 * UDP "kereső" válaszoló: a Quest broadcastot küld, mi visszaküldjük, hogy itt vagyunk.
 * Így nem kell kézzel IP-címet beírni.
 */
class DiscoveryResponder {
    companion object {
        private const val TAG = "Discovery"
    }

    private var socket: DatagramSocket? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    fun start() {
        running = true
        thread = Thread(::loop, "DiscoveryResponder").apply { start() }
    }

    private fun loop() {
        try {
            val s = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(java.net.InetSocketAddress(Protocol.DISCOVERY_PORT))
            }
            socket = s
            val buf = ByteArray(256)
            val reply = "${Protocol.DISCOVERY_REPLY}:${Protocol.TCP_PORT}".toByteArray()
            while (running) {
                val packet = DatagramPacket(buf, buf.size)
                s.receive(packet)
                val text = String(packet.data, 0, packet.length)
                if (text.trim() == Protocol.DISCOVERY_REQUEST) {
                    s.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                }
            }
        } catch (e: Exception) {
            if (running) Log.w(TAG, "Discovery leállt: ${e.message}")
        }
    }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
    }
}
