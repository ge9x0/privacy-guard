package com.privacyguard

import android.net.wifi.WifiInfo
import com.privacyguard.monitor.WifiCheck
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class WifiCheckTest {
    private fun l(ip: String) = WifiCheck.dnsLabel(InetAddress.getByName(ip))

    @Test fun dnsLabels() {
        assertEquals("your router (local)", l("192.168.1.1"))
        assertEquals("your router (local)", l("10.0.0.1"))
        assertEquals("your router (local)", l("fd00::1"))
        assertEquals("your router (local)", l("fe80::1"))
        assertEquals("provider's network", l("100.64.0.1"))
        assertEquals("Cloudflare", l("1.1.1.1"))
        assertEquals("Google", l("2001:4860:4860::8888"))
        assertEquals(WifiCheck.UNKNOWN, l("203.0.113.7"))
    }

    @Test fun security() {
        assertTrue(WifiCheck.securityName(WifiInfo.SECURITY_TYPE_OPEN)!!.second)
        assertTrue(WifiCheck.securityName(WifiInfo.SECURITY_TYPE_WEP)!!.second)
        assertFalse(WifiCheck.securityName(WifiInfo.SECURITY_TYPE_SAE)!!.second)
        assertFalse(WifiCheck.securityName(WifiInfo.SECURITY_TYPE_OWE)!!.second)
        assertNull(WifiCheck.securityName(-1))
    }
}
