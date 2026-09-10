package com.feri.vrmirror.quest

import android.util.Log
import com.feri.vrmirror.common.Protocol
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/** UDP broadcast alapú telefon-keresés a helyi hálózaton. */
object Discovery {
    private const val TAG = "Discovery"

    /** Blokkoló hívás – háttérszálról indítsd. A megtalált IP-t adja vissza, vagy null-t. */
    fun find(timeoutMs: Long = 3000): String? {
        val request = Protocol.DISCOVERY_REQUEST.toByteArray()
        val targets = broadcastAddresses() + InetAddress.getByName("255.255.255.255")
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 500
                val deadline = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(256)
                while (System.currentTimeMillis() < deadline) {
                    for (t in targets) {
                        try {
                            socket.send(DatagramPacket(request, request.size, t, Protocol.DISCOVERY_PORT))
                        } catch (e: Exception) {
                            Log.d(TAG, "Küldés $t sikertelen: ${e.message}")
                        }
                    }
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        socket.receive(packet)
                        val text = String(packet.data, 0, packet.length)
                        if (text.startsWith(Protocol.DISCOVERY_REPLY)) {
                            return packet.address.hostAddress
                        }
                    } catch (_: SocketTimeoutException) {
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Keresés hiba: ${e.message}")
        }
        return null
    }

    private fun broadcastAddresses(): List<InetAddress> {
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }
                .mapNotNull { it.broadcast }
                .distinct()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
