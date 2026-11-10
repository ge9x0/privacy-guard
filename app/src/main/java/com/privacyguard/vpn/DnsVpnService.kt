package com.privacyguard.vpn

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
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
 * VPN lokale qe kalon VETEM kerkesat DNS (emrat e serverave), jo trafikun.
 * Per cdo kerkese Android-i na tregon cili app e beri (getConnectionOwnerUid),
 * keshtu shohim p.sh. "Weatherly -> sdk.x-mode.io". Asgje nuk dergohet jashte
 * telefonit pervec vete kerkeses DNS te serveri i zakonshem.
 */
class DnsVpnService : VpnService() {
    private var iface: ParcelFileDescriptor? = null
    @Volatile private var running = false
    private val pool = Executors.newFixedThreadPool(4)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn(); stopSelf(); return START_NOT_STICKY
        }
        if (!running) startVpn()
        return START_STICKY
    }

    private fun upstream(): InetAddress {
        val cm = getSystemService(ConnectivityManager::class.java)
        return cm.activeNetwork?.let { cm.getLinkProperties(it) }?.dnsServers
            ?.firstOrNull { it is Inet4Address && it.hostAddress != FAKE_DNS }
            ?: InetAddress.getByName("1.1.1.1")
    }

    private fun startVpn() {
        val up = upstream()
        val pfd = Builder()
            .setSession("Privacy Guard DNS")
            .addAddress("10.111.222.2", 32)
            .addRoute(FAKE_DNS, 32)
            .addDnsServer(FAKE_DNS)
            .setBlocking(true)
            .addDisallowedApplication(packageName)
            .establish() ?: return
        iface = pfd
        running = true
        isRunning = true
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        val db = Db.get(this)
        val cm = getSystemService(ConnectivityManager::class.java)
        thread(name = "pg-dns") {
            val buf = ByteArray(32767)
            while (running) {
                val n = try { input.read(buf) } catch (e: Exception) { if (running) Log.w(TAG, "read", e); break }
                if (n <= 0) continue
                val q = Packets.parseDnsQuery(buf, n) ?: continue
                val pkt = buf.copyOf(n)
                pool.execute {
                    val uid = runCatching {
                        cm.getConnectionOwnerUid(
                            OsConstants.IPPROTO_UDP,
                            InetSocketAddress(InetAddress.getByAddress(q.src), q.srcPort),
                            InetSocketAddress(InetAddress.getByAddress(q.dst), q.dstPort),
                        )
                    }.getOrDefault(-1)
                    runCatching { db.addConn(ConnRow(System.currentTimeMillis(), uid, q.name, q.payloadLen.toLong())) }
                    forward(pkt, q, up, output)
                }
            }
        }
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
        running = false
        isRunning = false
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

        /** Thirr pasi VpnService.prepare() ka marre pelqimin e perdoruesit. */
        fun start(ctx: Context) = ctx.startService(Intent(ctx, DnsVpnService::class.java))
        fun stop(ctx: Context) = ctx.startService(Intent(ctx, DnsVpnService::class.java).setAction(ACTION_STOP))
    }
}

/** Paketa IPv4/UDP minimale per DNS. */
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
        // emri i pyetjes fillon pas 12 bajteve te kokes DNS
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

    fun buildUdpReply(q: DnsQuery, payload: ByteArray, plen: Int): ByteArray {
        val total = 20 + 8 + plen
        val p = ByteArray(total)
        p[0] = 0x45; p[2] = (total ushr 8).toByte(); p[3] = total.toByte()
        p[6] = 0x40; p[8] = 64; p[9] = 17
        System.arraycopy(q.dst, 0, p, 12, 4)   // nga "serveri DNS"
        System.arraycopy(q.src, 0, p, 16, 4)   // te app-i
        var sum = 0
        for (i in 0 until 20 step 2) sum += u16(p, i)
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        val cs = sum.inv() and 0xFFFF
        p[10] = (cs ushr 8).toByte(); p[11] = cs.toByte()
        p[20] = (q.dstPort ushr 8).toByte(); p[21] = q.dstPort.toByte()
        p[22] = (q.srcPort ushr 8).toByte(); p[23] = q.srcPort.toByte()
        val ul = 8 + plen
        p[24] = (ul ushr 8).toByte(); p[25] = ul.toByte()
        // checksum UDP = 0 (lejohet ne IPv4)
        System.arraycopy(payload, 0, p, 28, plen)
        return p
    }
}
