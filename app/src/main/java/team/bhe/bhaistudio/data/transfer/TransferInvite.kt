package team.bhe.bhaistudio.data.transfer

import android.os.Build
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 接收方生成的“连接邀请”：
 *   · 二维码内容 / 局域网信息都基于它；
 *   · host 用接收方当前局域网 IPv4（同一 WIFI 下有效）；
 *   · token 为一次性随机值，发送方 hello 时回传，接收方校验后才允许建立会话。
 */
data class InviteInfo(
    val host: String,
    val port: Int,
    val token: String
)

/** 本机设备名（展示用） */
fun localDeviceName(): String = Build.MODEL.ifBlank { "Android" }

/** 编码为二维码文本 */
fun InviteInfo.encode(): String =
    "wujie-transfer://$host:$port/token=$token"

/** 解析二维码 / 手输内容；非法返回 null */
fun parseInvite(raw: String): InviteInfo? {
    val text = raw.trim()
    val match = Regex("^wujie-transfer://([\\d.a-zA-Z\\[\\]:]+):(\\d{1,5})/token=([0-9a-fA-F]+)$")
        .matchEntire(text)
        ?: return null
    val host = match.groupValues[1]
    val port = match.groupValues[2].toIntOrNull() ?: return null
    if (port !in 1..65535) return null
    val token = match.groupValues[3]
    return InviteInfo(host, port, token)
}

/**
 * 取本机局域网 IPv4（排除回环/任意地址）。多网卡时优先常见局域网段，
 * 取不到局域网段则返回第一个可用 IPv4。
 */
fun lanIPv4(): String? = runCatching {
    var fallback: String? = null
    NetworkInterface.getNetworkInterfaces()?.asSequence()?.forEach { nif ->
        if (nif.isLoopback || !nif.isUp) return@forEach
        nif.inetAddresses?.asSequence()?.forEach { addr ->
            if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isAnyLocalAddress) {
                val host = addr.hostAddress ?: return@forEach
                if (isLanSegment(host)) {
                    return@runCatching host
                }
                if (fallback == null) fallback = host
            }
        }
    }
    fallback
}.getOrNull()

private fun isLanSegment(ip: String): Boolean {
    val parts = ip.split('.').mapNotNull { it.toIntOrNull() }
    if (parts.size != 4) return false
    val first = parts[0]
    val second = parts[1]
    // 192.168.* / 10.* / 172.16~31.*
    return (first == 192 && second == 168) ||
        first == 10 ||
        (first == 172 && second in 16..31)
}
