package com.privacyguard.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PacketsTest {
    /** IPv4/UDP 10.111.222.2:40000 -> 10.111.222.1:53, A query for graph.facebook.com with EDNS OPT. */
    private fun query(): ByteArray {
        val name = "graph.facebook.com".split('.').flatMap { listOf(it.length.toByte()) + it.toByteArray().toList() } + 0.toByte()
        val dns = byteArrayOf(0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 1) + name.toByteArray() +
            byteArrayOf(0, 1, 0, 1) + byteArrayOf(0, 0, 41, 0x10, 0, 0, 0, 0, 0, 0, 0)   // OPT
        val udp = 8 + dns.size
        val ip = ByteArray(20).apply {
            this[0] = 0x45; this[2] = ((20 + udp) ushr 8).toByte(); this[3] = (20 + udp).toByte(); this[9] = 17
            byteArrayOf(10, 111, 222.toByte(), 2).copyInto(this, 12); byteArrayOf(10, 111, 222.toByte(), 1).copyInto(this, 16)
        }
        val u = byteArrayOf((40000 ushr 8).toByte(), 40000.toByte(), 0, 53, (udp ushr 8).toByte(), udp.toByte(), 0, 0)
        return ip + u + dns
    }

    @Test
    fun nxdomainKeepsIdAndQuestionDropsExtras() {
        val p = query()
        val q = Packets.parseDnsQuery(p, p.size)!!
        assertEquals("graph.facebook.com", q.name)
        val r = Packets.nxdomain(p, q)
        assertNotNull(r)
        r!!
        assertEquals(12 + 20 + 4, r.size)                       // header + name (20 bytes) + QTYPE/QCLASS, without OPT
        assertEquals(0x12, r[0].toInt()); assertEquals(0x34, r[1].toInt())
        assertEquals(0x81, r[2].toInt() and 0xFF)              // QR + RD
        assertEquals(3, r[3].toInt() and 0x0F)                 // NXDOMAIN
        assertArrayEquals(byteArrayOf(0, 1, 0, 0, 0, 0, 0, 0), r.copyOfRange(4, 12))
        val reply = Packets.buildUdpReply(q, r, r.size)
        assertEquals(53, ((reply[20].toInt() and 0xFF) shl 8) or (reply[21].toInt() and 0xFF))   // from port 53
        assertEquals(40000, ((reply[22].toInt() and 0xFF) shl 8) or (reply[23].toInt() and 0xFF))
    }
}
