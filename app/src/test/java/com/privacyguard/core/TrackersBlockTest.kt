package com.privacyguard.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bllokimi duhet ta ndale gjurmimin pa prishur Facebook-un, Instagram-in, WhatsApp-in etj. */
class TrackersBlockTest {
    @Test
    fun metaAppsAreNeverBrokenByTheirOwnDomains() {
        for ((pkg, host) in listOf(
            "com.instagram.android" to "i.instagram.com",
            "com.instagram.android" to "graph.facebook.com",
            "com.facebook.katana" to "graph.facebook.com",
            "com.facebook.katana" to "scontent-mxp1-1.xx.fbcdn.net",
            "com.whatsapp" to "g.whatsapp.net",
            "com.google.android.youtube" to "googleads.g.doubleclick.net",
            "com.zhiliaoapp.musically" to "analytics.tiktok.com",
        )) assertFalse("$pkg -> $host", Trackers.blockable(host, pkg))
    }

    @Test
    fun contentStaysReachableFromOtherApps() {
        // klient i pales se trete / postim i ngulitur: foto e video duhet te ngarkohen
        assertFalse(Trackers.blockable("i.instagram.com", "com.instaflow.android"))
        assertFalse(Trackers.blockable("scontent.cdninstagram.com", "com.example.news"))
        assertFalse(Trackers.blockable("static.xx.fbcdn.net", "com.example.news"))
        assertFalse(Trackers.blockable("web.whatsapp.com", "com.example.news"))
        assertFalse(Trackers.blockable("example.com", "com.example.news"))     // jo tracker
    }

    @Test
    fun trackingFromOtherAppsIsBlocked() {
        for (host in listOf(
            "graph.facebook.com", "an.facebook.com", "connect.facebook.net", "app-measurement.com",
            "googleads.g.doubleclick.net", "t.appsflyer.com", "api.mixpanel.com", "x-mode.io", "analytics.tiktok.com",
        )) assertTrue(host, Trackers.blockable(host, "com.example.weather"))
    }
}
