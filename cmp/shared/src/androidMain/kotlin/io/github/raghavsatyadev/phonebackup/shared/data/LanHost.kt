package io.github.raghavsatyadev.phonebackup.shared.data

import java.net.Inet4Address
import java.net.InetAddress
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Plain HTTP only to PCs on the local network. Android's network security config can't match IP
 * ranges, and a PC's address changes, so cleartext is allowed for one made-up domain only
 * (res/xml/network_security_config.xml). [LanHostInterceptor] rewrites http://192.168.1.5 to
 * http://192-168-1-5.pc.phonebackup.lan and [LanHostDns] turns that name back into the address,
 * only for private, link-local and loopback IPv4. Public hosts keep needing HTTPS.
 */
object LanHost {
  const val DOMAIN = "pc.phonebackup.lan"

  /** The alias for [host], or null when it isn't a private IPv4 literal. */
  fun alias(host: String): String? = parseIpv4(host)?.takeIf(::isPrivate)?.let { it.joinToString("-") + ".$DOMAIN" }

  /** The address behind an alias made by [alias], or null for any other name. */
  fun address(alias: String): InetAddress? {
    if (!alias.endsWith(".$DOMAIN")) return null
    val ip = parseIpv4(alias.removeSuffix(".$DOMAIN").replace('-', '.'))?.takeIf(::isPrivate) ?: return null
    return InetAddress.getByAddress(alias, ByteArray(4) { ip[it].toByte() })
  }

  private fun parseIpv4(host: String): List<Int>? {
    val parts = host.split('.')
    if (parts.size != 4) return null
    val nums = parts.map { p -> p.toIntOrNull()?.takeIf { p.length in 1..3 && it in 0..255 } ?: return null }
    return nums
  }

  /** 10/8, 172.16/12, 192.168/16, 169.254/16 (link-local), 127/8 (adb reverse), 100.64/10 (CGNAT, VPNs). */
  private fun isPrivate(ip: List<Int>): Boolean {
    val (a, b) = ip
    return a == 10 ||
      (a == 172 && b in 16..31) ||
      (a == 192 && b == 168) ||
      (a == 169 && b == 254) ||
      a == 127 ||
      (a == 100 && b in 64..127)
  }
}

/** Sends plain-HTTP requests for LAN addresses to their [LanHost] alias; the Host header keeps the IP. */
object LanHostInterceptor : Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response {
    val request = chain.request()
    val url = request.url
    val alias = if (url.isHttps) null else LanHost.alias(url.host) ?: lanAddressOf(url.host)?.let(LanHost::alias)
    if (alias == null) return chain.proceed(request)
    val hostHeader = if (url.port == 80) url.host else "${url.host}:${url.port}"
    return chain.proceed(request.newBuilder().url(url.newBuilder().host(alias).build()).header("Host", hostHeader).build())
  }

  /** A typed PC name ("my-pc.lan") that resolves to a LAN address is treated like that address. */
  private fun lanAddressOf(host: String): String? =
    runCatching { Dns.SYSTEM.lookup(host) }.getOrNull()?.firstOrNull { it is Inet4Address }?.hostAddress
}

/** Resolves [LanHost] aliases without asking any DNS server; everything else goes to the system. */
object LanHostDns : Dns {
  override fun lookup(hostname: String): List<InetAddress> =
    LanHost.address(hostname)?.let { listOf(it) } ?: Dns.SYSTEM.lookup(hostname)
}
