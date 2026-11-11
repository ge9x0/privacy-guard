package com.privacyguard

import com.privacyguard.apps.AppChanges
import com.privacyguard.apps.AppHistory
import com.privacyguard.apps.Security
import com.privacyguard.apps.Security.Power.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class NewToolsTest {
    @Test fun spywareRisk() {
        val apps = Security.merge(listOf("spy" to HIDDEN, "spy" to ACCESSIBILITY, "kb" to KEYBOARD, "vpn" to VPN, "spy" to SIDELOADED))
        assertEquals("spy", apps.first().pkg)
        assertTrue(apps.first().risky)
        assertFalse(apps.first { it.pkg == "kb" }.risky)          // a keyboard from Play is normal
        assertTrue(Security.App("x", setOf(SIDELOADED, NOTIFICATIONS)).risky)
        assertTrue(Security.App("y", setOf(ACCESSIBILITY, ADMIN)).risky)   // 6 pike
    }

    @Test fun updateDiff() {
        val old = AppChanges.Snap("1.0", setOf("Camera"), setOf("Firebase Analytics"), deep = true)
        val new = AppChanges.Snap("1.1", setOf("Camera", "Location"), setOf("Firebase Analytics", "AppsFlyer"), deep = true)
        assertEquals(listOf("Location") to listOf("AppsFlyer"), AppChanges.diff(old, new))
        // shallow scan before: SDKs are not compared (otherwise those found by the deep scan would show as "new")
        assertEquals(listOf("Location") to emptyList<String>(), AppChanges.diff(old.copy(deep = false), new))
        assertEquals(new, AppChanges.decode(AppChanges.encode(new)))
    }

    @Test fun historyByDay() {
        val z = ZoneOffset.UTC
        val today = LocalDate.of(2026, 11, 11)
        fun ts(d: Int, min: Int) = today.minusDays(d.toLong()).atStartOfDay(z).toInstant().toEpochMilli() + min * 60_000L + 5_000
        val h = AppHistory.build(today, 30, z,
            listOf(ts(0, 1) to "CAMERA", ts(0, 1) + 10_000 to "CAMERA", ts(0, 5) to "CAMERA", ts(2, 0) to "FINE_LOCATION", ts(1, 0) to "UNKNOWN_OP"),
            listOf(ts(0, 2) to "t.appsflyer.com", ts(0, 3) to "example.com"),
            { mapOf("CAMERA" to "Camera", "FINE_LOCATION" to "Location")[it] }, { if ("appsflyer" in it) "AppsFlyer" else null })
        assertEquals(30, h.days.size)
        assertEquals(2, h.days.last().access)      // two within the same minute count once
        assertEquals(1, h.days.last().trackers)
        assertEquals(1, h.days[27].access)
        assertEquals(listOf("Camera" to 2, "Location" to 1), h.byWhat)
        assertEquals(listOf("AppsFlyer" to 1), h.companies)
    }
}
