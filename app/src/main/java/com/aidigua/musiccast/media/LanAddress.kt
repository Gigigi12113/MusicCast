package com.aidigua.musiccast.media

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import java.net.Inet4Address
import java.net.NetworkInterface

/** 找出手机在局域网里的 IPv4 地址（DLNA 只走局域网）。 */
object LanAddress {

    fun wifiIpv4(context: Context): String? {
        val cm = runCatching {
            context.getSystemService(ConnectivityManager::class.java)
        }.getOrNull()
        if (cm != null) {
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                val lan = caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
                if (!lan) continue
                ipFromLink(cm.getLinkProperties(n))?.let { return it }
            }
        }
        return allInterfacesIpv4()
    }

    private fun ipFromLink(lp: LinkProperties?): String? {
        lp ?: return null
        for (addr in lp.linkAddresses) {
            val a = addr.address
            if (a is Inet4Address && !a.isLoopbackAddress) {
                val ip = a.hostAddress ?: continue
                if (!ip.startsWith("169.254.")) return ip
            }
        }
        return null
    }

    private fun allInterfacesIpv4(): String? = runCatching {
        val ifaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        val preferred = ifaces.filter { i ->
            i.isUp && !i.isLoopback &&
                (i.name.startsWith("wlan") || i.name.startsWith("eth"))
        }
        (preferred + ifaces).firstNotNullOfOrNull { iface ->
            iface.inetAddresses.toList()
                .filterIsInstance<Inet4Address>()
                .firstOrNull { a ->
                    !a.isLoopbackAddress && !(a.hostAddress?.startsWith("169.254.") ?: true)
                }
                ?.hostAddress
        }
    }.getOrNull()
}
