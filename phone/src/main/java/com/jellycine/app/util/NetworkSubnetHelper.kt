package com.jellycine.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkSubnetHelper {

    private const val TAG = "NetworkSubnetHelper"

    /**
     * 获取当前手机在 WiFi 网络下的 IPv4 地址 (例如 "192.168.50.123")
     */
    fun getWifiIpv4Address(context: Context): String? {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
            val activeNetwork = cm.activeNetwork ?: return null
            val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return null

            // 仅在当前活跃网络是 WiFi 时检测
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return null
            }

            // 遍历网络接口获取 IPv4
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val name = intf.name.lowercase()
                // 常见 Android WiFi 接口: wlan0, wlan1, etc.
                val isWifiInterface = name.contains("wlan") || name.contains("wifi") || name.contains("eth")
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress
                        if (host != null && !host.startsWith("127.")) {
                            if (isWifiInterface) {
                                return host
                            }
                        }
                    }
                }
            }

            // 如果接口名称不规范，降级返回任意非回环私有 IPv4
            val fallbackInterfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in fallbackInterfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress
                        if (host != null && isPrivateIpv4(host)) {
                            return host
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get WiFi IPv4: ${e.message}")
        }
        return null
    }

    /**
     * 判断两个 IPv4 是否处于同一子网 (默认按 /24 前三段比对，例如 192.168.1.xxx)
     */
    fun isSameSubnet(ip1: String?, ip2: String?): Boolean {
        if (ip1.isNullOrBlank() || ip2.isNullOrBlank()) return false
        val parts1 = ip1.split(".")
        val parts2 = ip2.split(".")
        if (parts1.size != 4 || parts2.size != 4) return false

        // 比对前三段 (A.B.C)
        return parts1[0] == parts2[0] && parts1[1] == parts2[1] && parts1[2] == parts2[2]
    }

    /**
     * 从 URL (如 "http://192.168.1.100:8096" 或 "301:https://...") 提取纯 host
     */
    fun extractHost(url: String?): String {
        if (url.isNullOrBlank()) return ""
        val cleanUrl = when {
            url.startsWith("301:", ignoreCase = true) -> url.substring(4)
            url.startsWith("txt:", ignoreCase = true) -> url.substring(4)
            else -> url
        }.trim()

        return try {
            val uri = Uri.parse(cleanUrl)
            uri.host ?: cleanUrl
        } catch (_: Exception) {
            cleanUrl
        }
    }

    /**
     * 判断一个 host 是否为局域网私有 IPv4
     */
    fun isPrivateIpv4(host: String): Boolean {
        if (host.isBlank()) return false
        val parts = host.split(".")
        if (parts.size != 4) return false
        val a = parts[0].toIntOrNull() ?: return false
        val b = parts[1].toIntOrNull() ?: return false

        return when {
            a == 10 -> true
            a == 192 && b == 168 -> true
            a == 172 && (b in 16..31) -> true
            else -> false
        }
    }
}
