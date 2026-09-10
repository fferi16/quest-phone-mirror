package com.feri.vrmirror.phone

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtils {
    /** A telefon helyi IPv4 címe (a wifi interfészt részesíti előnyben), vagy null. */
    fun localIp(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
            val candidates = interfaces
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
                .flatMap { nif -> nif.inetAddresses.toList().map { nif to it } }
                .filter { (_, addr) -> addr is Inet4Address && addr.isSiteLocalAddress }
            candidates.firstOrNull()?.second?.hostAddress
        } catch (e: Exception) {
            null
        }
    }
}
