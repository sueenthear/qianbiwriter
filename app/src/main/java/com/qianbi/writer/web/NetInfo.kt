package com.qianbi.writer.web

import java.net.Inet4Address
import java.net.NetworkInterface

/** 找出这台手机在局域网里的 IPv4 地址，用来拼出电脑可以打开的 URL。 */
object NetInfo {

    fun localIpv4(): List<String> {
        val found = LinkedHashSet<String>()
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()
        } catch (e: Exception) {
            null
        } ?: return emptyList()

        for (nif in interfaces) {
            try {
                if (!nif.isUp || nif.isLoopback) continue
                if (!looksLikeLanInterface(nif.name)) continue
                for (address in nif.inetAddresses) {
                    val ip = address as? Inet4Address ?: continue
                    if (ip.isLoopbackAddress || ip.isLinkLocalAddress) continue
                    if (!ip.isSiteLocalAddress) continue
                    val host = ip.hostAddress ?: continue
                    found.add(host)
                }
            } catch (e: Exception) {
                continue
            }
        }
        return found.toList()
    }

    /**
     * 排掉蜂窝 / 点对点 / 隧道这类接口：它们上面的地址要么不对外，要么连上也没用，
     * 混进 URL 列表只会让用户抄错一个。
     */
    private fun looksLikeLanInterface(name: String): Boolean {
        val lower = name.lowercase()
        val dead = listOf("rmnet", "ccmni", "p2p", "dummy", "tun", "ppp", "sit", "ip6tnl")
        return dead.none { lower.startsWith(it) }
    }

    /** 拆成多个地址时每个都给一条完整 URL，方便挨个试。 */
    fun urls(port: Int, accessCode: String): List<String> =
        localIpv4().map { ip ->
            if (accessCode.isBlank()) "http://$ip:$port/"
            else "http://$ip:$port/?t=$accessCode"
        }
}
