package com.privacyguard.core

import com.privacyguard.data.AccessEvent
import com.privacyguard.monitor.ClipWatch
import org.junit.Assert.assertEquals
import org.junit.Test

class ClipWatchTest {
    private fun ev(pkg: String, ts: Long, state: String, kind: String = "access") =
        AccessEvent(pkg, 10100, "READ_CLIPBOARD", ts, 0, state, kind, "last")

    @Test fun onlyNewOffScreenReadsByOrdinaryApps() {
        val evs = listOf(
            ev("com.shop", 900, "bg"),                       // old
            ev("com.shop", 1100, "bg"),                      // new, background -> hit
            ev("com.notes", 1200, "top"),                    // on screen -> fine
            ev("com.sync", 1300, "fgsvc"),                   // service, not on screen -> hit
            ev("com.samsung.android.honeyboard", 1400, "fg"),// keyboard -> ignored
            ev("com.spy", 1500, "cch", "reject"),            // Android refused it
            AccessEvent("com.shop", 1, "CAMERA", 1600, 0, "bg", "access", "last"),
        )
        val hits = ClipWatch.newHits(evs, 1000) { it == "com.samsung.android.honeyboard" }
        assertEquals(listOf("com.shop", "com.sync", "com.spy"), hits.map { it.e.pkg })
        assertEquals(listOf(false, false, true), hits.map { it.refused })
    }
}
