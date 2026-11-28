package io.github.mthli.appine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException

/** Try the default network first, then other connected LANs (for example, under a VPN). */
@Suppress("DEPRECATION") // A one-time snapshot for a listen notification or the port mappings dialog.
internal fun Context.lanAddresses(interfaceIndex: Int = 0): Sequence<InetAddress> = sequence {
    val cm = getSystemService(ConnectivityManager::class.java)
    for (network in (listOfNotNull(cm.activeNetwork) + cm.allNetworks).distinct()) {
        val caps = cm.getNetworkCapabilities(network) ?: continue
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) continue
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue

        val link = cm.getLinkProperties(network) ?: continue
        if (interfaceIndex != 0 && interfaceIndex != indexOfInterface(link.interfaceName)) continue
        yieldAll(link.linkAddresses.map { it.address }.sortedBy { it !is Inet4Address })
    }
}

private fun indexOfInterface(name: String?): Int? = try {
    name?.let { NetworkInterface.getByName(it)?.index }
} catch (_: SocketException) {
    null
}

internal fun InetAddress.isUsableLanAddress(): Boolean =
    !isAnyLocalAddress && !isLoopbackAddress && !isMulticastAddress && !(this is Inet6Address && isLinkLocalAddress)

internal fun Sequence<InetAddress>.firstUsableLanIp(): String? =
    firstOrNull { it.isUsableLanAddress() }?.hostAddress?.substringBefore('%')
