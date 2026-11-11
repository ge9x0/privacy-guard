package com.privacyguard.vpn

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import com.privacyguard.block.Blocker
import com.privacyguard.data.ConnRow
import com.privacyguard.data.Db
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Local VPN that carries ONLY DNS requests (server names), not the traffic.
 * For every request Android tells us which app made it (getConnectionOwnerUid),
 * so we see e.g. "Weatherly -> sdk.x-mode.io". Nothing leaves the
 * phone except the DNS request itself to the usual server.
 */
class DnsVpnService : VpnService() {
    private var iface: ParcelFileDescriptor? = null
    @Volatile private var running = false
    // 16, not 4: a request without an answer holds its thread for 5 s; with 4 the queue filled up and
    // apps gave up before their turn came.
    private val pool = Executors.newFixedThreadPool(16)
    @Volatile private var up: InetAddress = InetAddress.getByName("1.1.1.1")
    private var netCb: ConnectivityManager.NetworkCallback? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn(); stopSelf(); return START_NOT_STICKY
        }
        if (!running) startVpn()
        return START_STICKY
    }

    private fun upstream(lp: LinkProperties? = null): InetAddress {
        val cm = getSystemService(ConnectivityManager::class.java)
        val l = lp ?: cm.activeNetwork?.let { cm.getLinkProperties(it) }
        return l?.dnsServers
            ?.firstOrNull { it is Inet4Address && it.hostAddress != FAKE_DNS }
            ?: InetAddress.getByName("1.1.1.1")
    }

    /** The DNS server follows network changes (Wi-Fi <-> mobile); before, it stayed on the initial one. */
    private fun watchUpstream(cm: ConnectivityManager) {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                // only real networks (not our VPN) and only if they have IPv4 DNS; otherwise
                // keep the current server instead of falling back to 1.1.1.1
                if (cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != false) return
                val n = lp.dnsServers.firstOrNull { it is Inet4Address && it.hostAddress != FAKE_DNS } ?: return
                if (n != up) { Log.i(TAG, "upstream ${up.hostAddress} -> ${n.hostAddress}"); up = n }
            }
        }
        // our app is outside the VPN, so the "default" network is the real one
        runCatching { cm.registerDefaultNetworkCallback(cb); netCb = cb }.onFailure { Log.w(TAG, "netcb", it) }
    }

    /** IPs of the public DNS servers being caught (empty when catching is off). */
    @Volatile private var bypassIps: Set<String> = emptySet()

    private fun startVpn() {
        up = upstream()
        val b = Builder()
            .setSession("Privacy Guard DNS")
            .addAddress("10.111.222.2", 32)
            .addRoute(FAKE_DNS, 32)
            .addDnsServer(FAKE_DNS)
            .setBlocking(true)
            .addDisallowedApplication(packageName)
        // Bypass catching: apps that talk straight to 8.8.8.8 / 1.1.1.1 ... go through us.
        // Not when the user has a named "Private DNS" (the system itself uses these servers then).
        val pdns = runCatching { android.provider.Settings.Global.getString(contentResolver, "private_dns_mode") }.getOrNull()
        bypassIps = if (Blocker.antiBypass(this) && pdns != "hostname") Packets.PUBLIC_DNS else emptySet()
        for (ip in bypassIps) b.addRoute(ip, 32)
        Log.i(TAG, "start: bypass catcher ${if (bypassIps.isEmpty()) "off (private_dns_mode=$pdns)" else "on, ${bypassIps.size} servers"}")
        val pfd = b.establish() ?: return
        iface = pfd
        running = true
        isRunning = true
        com.privacyguard.widget.PrivacyWidget.updateAsync(this)
        android.service.quicksettings.TileService.requestListeningState(this, android.content.ComponentName(this, com.privacyguard.widget.VpnTile::class.java))
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        val db = Db.get(this)
        val cm = getSystemService(ConnectivityManager::class.java)
        watchUpstream(cm)
        // first time: download the block lists (in the background, with internet)
        androidx.work.WorkManager.getInstance(this).enqueueUniqueWork("filter-lists-now", androidx.work.ExistingWorkPolicy.KEEP,
            androidx.work.OneTimeWorkRequestBuilder<com.privacyguard.apps.FilterUpdateWorker>()
                .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build()).build())
        thread(name = "pg-dns") {
            val buf = ByteArray(32767)
            while (running) {
                val n = try { input.read(buf) } catch (e: Exception) { if (running) Log.w(TAG, "read", e); break }
                if (n <= 0) continue
                val q = Packets.parseDnsQuery(buf, n)
                if (q == null) { if (bypassIps.isNotEmpty()) refuseBypass(buf, n, cm, db, output); continue }
                val pkt = buf.copyOf(n)
                // the uid is looked up IMMEDIATELY, while the app's socket is still open; before, it was looked up
                // inside the queue and, when the queue was slow, Android returned -1 (unknown app).
                val uid = runCatching {
                    cm.getConnectionOwnerUid(
                        OsConstants.IPPROTO_UDP,
                        InetSocketAddress(InetAddress.getByAddress(q.src), q.srcPort),
                        InetSocketAddress(InetAddress.getByAddress(q.dst), q.dstPort),
                    )
                }.getOrDefault(-1)
                val ts = System.currentTimeMillis()
                pool.execute {
                    val why = runCatching { Blocker.dnsVerdict(this, uid, q.name) }.getOrNull()
                    val nx = if (why != null) Packets.nxdomain(pkt, q) else null
                    runCatching { db.addConn(ConnRow(ts, uid, q.name, q.payloadLen.toLong(), blocked = nx != null, why = why)) }
                    if (nx != null) {
                        Log.d(TAG, "bllokuar ($why) uid=$uid ${q.name}")
                        runCatching { synchronized(output) { output.write(Packets.buildUdpReply(q, nx, nx.size)) } }
                    } else forward(pkt, q, up, output)
                }
            }
        }
    }

    private val bypassLogged = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Non-DNS traffic to a public DNS server (DoH on 443, DoT on 853): TCP gets an RST so the app
     * switches to the phone's DNS at once; UDP (QUIC/DoQ) is dropped. Logged once per minute.
     */
    private fun refuseBypass(buf: ByteArray, n: Int, cm: ConnectivityManager, db: Db, out: FileOutputStream) {
        val dst = Packets.ipv4Dst(buf, n) ?: return
        if (dst !in bypassIps) return
        val syn = Packets.isTcpSyn(buf, n)
        val rst = Packets.tcpReset(buf, n)
        val ports = Packets.ports(buf, n)
        // the uid is looked up BEFORE the RST: after it the socket closes and Android returns -1
        val uid = if (ports != null && (syn || Packets.proto(buf) == 17)) runCatching {
            cm.getConnectionOwnerUid(Packets.proto(buf),
                InetSocketAddress(InetAddress.getByAddress(buf.copyOfRange(12, 16)), ports.first),
                InetSocketAddress(InetAddress.getByAddress(buf.copyOfRange(16, 20)), ports.second))
        }.getOrDefault(-1) else -1
        rst?.let { runCatching { synchronized(out) { out.write(it) } } }
        if (ports == null || (!syn && Packets.proto(buf) == 6)) return
        val key = "$uid|$dst|${ports.second}"
        val now = System.currentTimeMillis()
        if ((bypassLogged.put(key, now) ?: 0L) > now - 60_000) return
        val what = when (ports.second) { 853 -> "dns-over-tls"; 443 -> "dns-over-https"; else -> "port-${ports.second}" }
        Log.i(TAG, "bypass refused uid=$uid $dst:${ports.second}")
        runCatching { db.addConn(ConnRow(now, uid, "$what.$dst", 0, blocked = true, why = "bypass")) }
    }

    private fun forward(pkt: ByteArray, q: Packets.DnsQuery, up: InetAddress, out: FileOutputStream) {
        try {
            DatagramSocket().use { s ->
                protect(s)
                s.soTimeout = 5000
                s.send(DatagramPacket(pkt, q.payloadOffset, q.payloadLen, up, 53))
                val resp = ByteArray(4096)
                val dp = DatagramPacket(resp, resp.size)
                s.receive(dp)
                val reply = Packets.buildUdpReply(q, resp, dp.length)
                synchronized(out) { out.write(reply) }
            }
        } catch (e: Exception) {
            Log.d(TAG, "dns ${q.name}: ${e.message}")
        }
    }

    private fun stopVpn() {
        netCb?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) } }
        netCb = null
        running = false
        isRunning = false
        com.privacyguard.widget.PrivacyWidget.updateAsync(this)
        runCatching { android.service.quicksettings.TileService.requestListeningState(this, android.content.ComponentName(this, com.privacyguard.widget.VpnTile::class.java)) }
        runCatching { iface?.close() }
        iface = null
    }

    override fun onRevoke() { stopVpn(); super.onRevoke() }
    override fun onDestroy() { stopVpn(); pool.shutdownNow(); super.onDestroy() }

    companion object {
        private const val TAG = "PG.Dns"
        const val FAKE_DNS = "10.111.222.1"
        const val ACTION_STOP = "com.privacyguard.STOP_VPN"
        @Volatile var isRunning = false

        /** Call after VpnService.prepare() has the user's consent. */
        fun start(ctx: Context) = ctx.startService(Intent(ctx, DnsVpnService::class.java))
        fun stop(ctx: Context) = ctx.startService(Intent(ctx, DnsVpnService::class.java).setAction(ACTION_STOP))
    }
}

/** Minimal IPv4/UDP packet for DNS. */
object Packets {
    class DnsQuery(
        val src: ByteArray, val dst: ByteArray, val srcPort: Int, val dstPort: Int,
        val payloadOffset: Int, val payloadLen: Int, val name: String,
    )

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    fun parseDnsQuery(b: ByteArray, n: Int): DnsQuery? {
        if (n < 28 || (b[0].toInt() ushr 4) != 4) return null
        val ihl = (b[0].toInt() and 0x0F) * 4
        if (b[9].toInt() != 17 || n < ihl + 8) return null
        val sport = u16(b, ihl)
        val dport = u16(b, ihl + 2)
        if (dport != 53) return null
        val udpLen = u16(b, ihl + 4)
        val off = ihl + 8
        val len = minOf(udpLen - 8, n - off)
        if (len < 13) return null
        // the question name starts after the 12-byte DNS header
        val sb = StringBuilder()
        var i = off + 12
        while (i < off + len) {
            val l = b[i].toInt() and 0xFF
            if (l == 0 || l >= 64) break
            if (sb.isNotEmpty()) sb.append('.')
            for (k in 1..l) if (i + k < off + len) sb.append((b[i + k].toInt() and 0xFF).toChar())
            i += l + 1
        }
        if (sb.isEmpty()) return null
        return DnsQuery(b.copyOfRange(12, 16), b.copyOfRange(16, 20), sport, dport, off, len, sb.toString().lowercase())
    }

    /**
     * NXDOMAIN answer ("this server doesn't exist") for a blocked request: header + question,
     * no other sections. null if the question can't be read (the request then passes normally).
     */
    fun nxdomain(b: ByteArray, q: DnsQuery): ByteArray? {
        val off = q.payloadOffset
        val end = off + q.payloadLen
        var i = off + 12
        while (i < end) {
            val l = b[i].toInt() and 0xFF
            if (l == 0) break
            if (l >= 64) return null
            i += l + 1
        }
        val qEnd = i + 1 + 4                       // the trailing 0 + QTYPE + QCLASS
        if (i >= end || qEnd > end) return null
        val r = b.copyOfRange(off, qEnd)
        r[2] = ((r[2].toInt() and 0x79) or 0x80).toByte()   // QR=1, keeps OPCODE and RD
        r[3] = 0x83.toByte()                                // RA=1, RCODE=3 (NXDOMAIN)
        r[4] = 0; r[5] = 1                                  // QDCOUNT=1
        for (k in 6 until 12) r[k] = 0                      // AN/NS/AR = 0
        return r
    }

    /** Public DNS servers (IPv4) that apps use to bypass the phone's DNS. */
    val PUBLIC_DNS = setOf(
        "8.8.8.8", "8.8.4.4", "1.1.1.1", "1.0.0.1", "1.1.1.2", "1.0.0.2", "1.1.1.3", "1.0.0.3",
        "9.9.9.9", "149.112.112.112", "9.9.9.11", "149.112.112.11", "208.67.222.222", "208.67.220.220",
        "94.140.14.14", "94.140.15.15", "185.228.168.9", "185.228.169.9", "76.76.2.0", "76.76.10.0",
        "45.90.28.0", "45.90.30.0", "194.242.2.2", "223.5.5.5", "223.6.6.6", "180.76.76.76",
    )

    fun proto(b: ByteArray) = b[9].toInt() and 0xFF
    fun ipv4Dst(b: ByteArray, n: Int): String? =
        if (n >= 20 && (b[0].toInt() ushr 4) == 4) "${b[16].toInt() and 0xFF}.${b[17].toInt() and 0xFF}.${b[18].toInt() and 0xFF}.${b[19].toInt() and 0xFF}" else null
    fun ports(b: ByteArray, n: Int): Pair<Int, Int>? {
        val ihl = (b[0].toInt() and 0x0F) * 4
        if (n < ihl + 4 || (proto(b) != 6 && proto(b) != 17)) return null
        return u16(b, ihl) to u16(b, ihl + 2)
    }
    fun isTcpSyn(b: ByteArray, n: Int): Boolean {
        val ihl = (b[0].toInt() and 0x0F) * 4
        return proto(b) == 6 && n >= ihl + 14 && (b[ihl + 13].toInt() and 0x12) == 0x02
    }

    private fun csum(b: ByteArray, off: Int, len: Int, start: Int = 0): Int {
        var sum = start
        var i = off
        while (i + 1 < off + len) { sum += u16(b, i); i += 2 }
        if (i < off + len) sum += (b[i].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum
    }

    /**
     * RST+ACK for an IPv4 TCP segment (the connection is refused at once, no waiting). null for non-TCP
     * or when the segment itself is an RST.
     */
    fun tcpReset(b: ByteArray, n: Int): ByteArray? {
        if (n < 20 || (b[0].toInt() ushr 4) != 4 || proto(b) != 6) return null
        val ihl = (b[0].toInt() and 0x0F) * 4
        if (n < ihl + 20) return null
        val flags = b[ihl + 13].toInt() and 0xFF
        if (flags and 0x04 != 0) return null
        val doff = ((b[ihl + 12].toInt() and 0xFF) ushr 4) * 4
        val totalIn = u16(b, 2)
        var segLen = (minOf(totalIn, n) - ihl - doff).coerceAtLeast(0)
        if (flags and 0x02 != 0) segLen++            // SYN
        if (flags and 0x01 != 0) segLen++            // FIN
        val seqIn = ((b[ihl + 4].toLong() and 0xFF) shl 24) or ((b[ihl + 5].toLong() and 0xFF) shl 16) or
            ((b[ihl + 6].toLong() and 0xFF) shl 8) or (b[ihl + 7].toLong() and 0xFF)
        val ackOut = (seqIn + segLen) and 0xFFFFFFFFL
        val hasAck = flags and 0x10 != 0
        val p = ByteArray(40)
        p[0] = 0x45; p[3] = 40; p[6] = 0x40; p[8] = 64; p[9] = 6
        System.arraycopy(b, 16, p, 12, 4); System.arraycopy(b, 12, p, 16, 4)
        // ports: from the server to the app
        p[20] = b[ihl + 2]; p[21] = b[ihl + 3]; p[22] = b[ihl]; p[23] = b[ihl + 1]
        if (hasAck) System.arraycopy(b, ihl + 8, p, 24, 4)   // seq = the app's ack
        p[28] = (ackOut ushr 24).toByte(); p[29] = (ackOut ushr 16).toByte(); p[30] = (ackOut ushr 8).toByte(); p[31] = ackOut.toByte()
        p[32] = 0x50; p[33] = 0x14                               // 20 bajte koke, RST|ACK
        val ics = csum(p, 0, 20).inv() and 0xFFFF
        p[10] = (ics ushr 8).toByte(); p[11] = ics.toByte()
        val pseudo = csum(p, 12, 8, 6 + 20)                     // src+dst, protokolli, gjatesia TCP
        val tcs = csum(p, 20, 20, pseudo).inv() and 0xFFFF
        p[36] = (tcs ushr 8).toByte(); p[37] = tcs.toByte()
        return p
    }

    fun buildUdpReply(q: DnsQuery, payload: ByteArray, plen: Int): ByteArray {
        val total = 20 + 8 + plen
        val p = ByteArray(total)
        p[0] = 0x45; p[2] = (total ushr 8).toByte(); p[3] = total.toByte()
        p[6] = 0x40; p[8] = 64; p[9] = 17
        System.arraycopy(q.dst, 0, p, 12, 4)   // from the "DNS server"
        System.arraycopy(q.src, 0, p, 16, 4)   // to the app
        var sum = 0
        for (i in 0 until 20 step 2) sum += u16(p, i)
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        val cs = sum.inv() and 0xFFFF
        p[10] = (cs ushr 8).toByte(); p[11] = cs.toByte()
        p[20] = (q.dstPort ushr 8).toByte(); p[21] = q.dstPort.toByte()
        p[22] = (q.srcPort ushr 8).toByte(); p[23] = q.srcPort.toByte()
        val ul = 8 + plen
        p[24] = (ul ushr 8).toByte(); p[25] = ul.toByte()
        // UDP checksum = 0 (allowed on IPv4)
        System.arraycopy(payload, 0, p, 28, plen)
        return p
    }
}
