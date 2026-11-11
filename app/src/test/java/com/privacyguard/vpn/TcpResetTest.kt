package com.privacyguard.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TcpResetTest {
    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
    private fun fold(sum0: Int): Int { var s = sum0; while (s ushr 16 != 0) s = (s and 0xFFFF) + (s ushr 16); return s }
    private fun sum(b: ByteArray, off: Int, len: Int, start: Int = 0): Int { var s = start; for (i in off until off + len step 2) s += u16(b, i); return fold(s) }

    /** SYN 10.111.222.2:40000 -> 1.1.1.1:443, seq 1000 */
    private fun syn(): ByteArray {
        val p = ByteArray(40)
        p[0] = 0x45; p[3] = 40; p[8] = 64; p[9] = 6
        byteArrayOf(10, 111, 222.toByte(), 2).copyInto(p, 12); byteArrayOf(1, 1, 1, 1).copyInto(p, 16)
        p[20] = (40000 ushr 8).toByte(); p[21] = 40000.toByte(); p[22] = 1; p[23] = 0xBB.toByte()
        p[24] = 0; p[25] = 0; p[26] = 0x03; p[27] = 0xE8.toByte()   // seq 1000
        p[32] = 0x50; p[33] = 0x02                                    // SYN
        return p
    }

    @Test fun resetAnswersSynWithValidChecksums() {
        val r = Packets.tcpReset(syn(), 40)!!
        assertArrayEquals(byteArrayOf(1, 1, 1, 1), r.copyOfRange(12, 16))                   // from the server
        assertArrayEquals(byteArrayOf(10, 111, 222.toByte(), 2), r.copyOfRange(16, 20))     // to the app
        assertEquals(443, u16(r, 20)); assertEquals(40000, u16(r, 22))
        assertEquals(1001, (u16(r, 28) shl 16) or u16(r, 30))                               // ack = seq + 1 (SYN)
        assertEquals(0x14, r[33].toInt())                                                    // RST|ACK
        assertEquals(0xFFFF, sum(r, 0, 20))                                                  // IP checksum ok
        val pseudo = fold(u16(r, 12) + u16(r, 14) + u16(r, 16) + u16(r, 18) + 6 + 20)
        assertEquals(0xFFFF, sum(r, 20, 20, pseudo))                                         // TCP checksum ok
    }

    @Test fun neverAnswersARst() {
        val p = syn(); p[33] = 0x04
        assertNull(Packets.tcpReset(p, 40))
    }

    @Test fun publicDnsRecognised() {
        assertEquals("1.1.1.1", Packets.ipv4Dst(syn(), 40))
        assertEquals(true, "1.1.1.1" in Packets.PUBLIC_DNS)
        assertEquals(true, Packets.isTcpSyn(syn(), 40))
    }
}
