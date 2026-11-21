package com.privacyguard.monitor

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.Build
import android.util.Log
import java.net.Inet6Address
import java.net.InetAddress

/** Checks the Wi-Fi network: open or weak encryption, which DNS servers it hands out, Private DNS. */
object WifiCheck {
    private const val TAG = "PG.Wifi"
    private const val NOTIF = 7300

    data class State(
        /** e.g. "WPA2", "WPA3", "Open", or null when Android does not say (Android 11). */
        val security: String?,
        val weak: Boolean,
        val dns: List<Pair<String, String>>,
        val privateDns: Boolean,
    ) {
        val unknownDns get() = dns.filter { it.second == UNKNOWN }.map { it.first }
    }

    const val UNKNOWN = "unknown public server"

    private val known = mapOf(
        "8.8.8.8" to "Google", "8.8.4.4" to "Google", "1.1.1.1" to "Cloudflare", "1.0.0.1" to "Cloudflare",
        "9.9.9.9" to "Quad9", "149.112.112.112" to "Quad9", "208.67.222.222" to "OpenDNS", "208.67.220.220" to "OpenDNS",
        "94.140.14.14" to "AdGuard", "94.140.15.15" to "AdGuard", "76.76.2.0" to "Control D", "76.76.10.0" to "Control D",
        "185.228.168.9" to "CleanBrowsing", "185.228.169.9" to "CleanBrowsing",
    )
    private val known6 = listOf("2001:4860:4860:" to "Google", "2606:4700:4700:" to "Cloudflare", "2620:fe:" to "Quad9",
        "2620:119:35:" to "OpenDNS", "2620:119:53:" to "OpenDNS", "2a10:50c0:" to "AdGuard")

    /** "your router / local network", a known provider, or [UNKNOWN]. */
    fun dnsLabel(a: InetAddress): String {
        val ip = a.hostAddress?.substringBefore('%') ?: return UNKNOWN
        if (a.isSiteLocalAddress || a.isLinkLocalAddress || a.isLoopbackAddress) return "your router (local)"
        if (a is Inet6Address && (a.address[0].toInt() and 0xFE) == 0xFC) return "your router (local)"
        // 100.64.0.0/10: the operator's own network (carrier-grade NAT)
        val b = a.address
        if (b.size == 4 && (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64) return "provider's network"
        known[ip]?.let { return it }
        known6.firstOrNull { ip.lowercase().startsWith(it.first) }?.let { return it.second }
        return UNKNOWN
    }

    fun securityName(t: Int): Pair<String, Boolean>? = when (t) {
        WifiInfo.SECURITY_TYPE_OPEN -> "Open (no password)" to true
        WifiInfo.SECURITY_TYPE_WEP -> "WEP (broken encryption)" to true
        WifiInfo.SECURITY_TYPE_PSK -> "WPA2" to false
        WifiInfo.SECURITY_TYPE_SAE -> "WPA3" to false
        WifiInfo.SECURITY_TYPE_OWE -> "Enhanced Open (encrypted)" to false
        WifiInfo.SECURITY_TYPE_EAP, WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE,
        WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT -> "WPA Enterprise" to false
        WifiInfo.SECURITY_TYPE_WAPI_PSK, WifiInfo.SECURITY_TYPE_WAPI_CERT -> "WAPI" to false
        else -> null
    }

    private fun stateOf(caps: NetworkCapabilities?, lp: LinkProperties?): State? {
        if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val sec = if (Build.VERSION.SDK_INT >= 31) (caps.transportInfo as? WifiInfo)?.currentSecurityType?.let(::securityName) else null
        return State(sec?.first, sec?.second == true,
            lp?.dnsServers.orEmpty().map { (it.hostAddress?.substringBefore('%') ?: "?") to dnsLabel(it) },
            lp?.isPrivateDnsActive == true)
    }

    @Volatile private var last: State? = null

    /** The Wi-Fi the phone is on now, or null when it is not on Wi-Fi. */
    fun current(ctx: Context): State? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        val wifi = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            ?: return null
        return stateOf(cm.getNetworkCapabilities(wifi), cm.getLinkProperties(wifi)) ?: last
    }

    private var callback: ConnectivityManager.NetworkCallback? = null
    private val warned = HashSet<Network>()

    /** Watches Wi-Fi changes and notifies once per connection to an open or WEP network. */
    fun watch(ctx: Context) {
        if (callback != null) return
        val app = ctx.applicationContext
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = HashMap<Network, NetworkCapabilities>()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) { caps[n] = c; check(n, cm.getLinkProperties(n)) }
            override fun onLinkPropertiesChanged(n: Network, lp: LinkProperties) = check(n, lp)
            override fun onLost(n: Network) { caps.remove(n); warned.remove(n); last = null }
            private fun check(n: Network, lp: LinkProperties?) {
                val s = stateOf(caps[n], lp) ?: return
                last = s
                if (s.weak && warned.add(n)) {
                    Log.i(TAG, "weak Wi-Fi: ${s.security}")
                    com.privacyguard.apps.Insights.post(app, NOTIF, "Unsafe Wi-Fi: ${s.security}",
                        "Anyone nearby can see traffic on this network that is not encrypted, including the names of the sites your apps open. " +
                            "Avoid logging in to sites without https, or use a real VPN here.")
                }
            }
        }
        cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), cb)
        callback = cb
    }

    fun stop(ctx: Context) {
        callback?.let { runCatching { ctx.applicationContext.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        callback = null
    }
}
