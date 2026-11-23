package com.transferhub.app.util

import java.net.Inet4Address
import java.net.NetworkInterface

data class LocalAddress(val ip: String, val iface: String) {
    /** 便于界面展示的网络类型 */
    val label: String
        get() = when {
            iface.startsWith("wlan") -> "Wi-Fi"
            iface.startsWith("ap") || iface.startsWith("swlan") || iface.startsWith("softap") -> "热点"
            iface.startsWith("p2p") -> "Wi-Fi 直连"
            iface.startsWith("rndis") || iface.startsWith("usb") -> "USB 共享"
            iface.startsWith("eth") -> "有线网络"
            else -> iface
        }
}

object NetUtils {
    /** 蜂窝网络、VPN 等接口，其他设备无法通过它们访问本机 */
    private val excludedPrefixes = listOf("rmnet", "ccmni", "pdp", "v4-", "dummy", "tun", "ppp", "lo", "ifb", "clat")

    /** 列出本机可被局域网其他设备访问的 IPv4 地址，Wi-Fi 优先。 */
    fun localAddresses(): List<LocalAddress> {
        val result = mutableListOf<LocalAddress>()
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull() ?: return result
        for (ni in interfaces) {
            val up = runCatching { ni.isUp && !ni.isLoopback }.getOrDefault(false)
            if (!up || excludedPrefixes.any { ni.name.startsWith(it) }) continue
            for (addr in ni.inetAddresses) {
                if (addr is Inet4Address && addr.isSiteLocalAddress) {
                    result += LocalAddress(addr.hostAddress ?: continue, ni.name)
                }
            }
        }
        return result.sortedBy { rank(it.iface) }
    }

    private fun rank(iface: String) = when {
        iface.startsWith("wlan") -> 0
        iface.startsWith("ap") || iface.startsWith("swlan") || iface.startsWith("softap") -> 1
        iface.startsWith("eth") -> 2
        else -> 3
    }
}
