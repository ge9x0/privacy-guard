package com.privacyguard.core

import com.privacyguard.apps.SdkScanner
import com.privacyguard.block.FilterLists
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewFeaturesTest {
    private val list = FilterLists.compileText("# HaGeZi\n# comment\nappsflyersdk.com\ncrashlytics.com\nan.facebook.com\n\n*.doubleclick.net\n")
    private fun blocked(h: String) = FilterLists.contains(list, FilterLists.suffixHashes(h))

    @Test fun listBlocksDomainAndSubdomains() {
        assertEquals(4, list.size)
        assertTrue(blocked("t.appsflyersdk.com"))
        assertTrue(blocked("firebase-settings.crashlytics.com"))
        assertTrue(blocked("ad.doubleclick.net"))
        assertTrue(blocked("an.facebook.com"))
    }

    @Test fun listDoesNotBlockParentsOrLookalikes() {
        assertFalse(blocked("facebook.com"))          // only an.facebook.com is listed
        assertFalse(blocked("graph.facebook.com"))
        assertFalse(blocked("notappsflyersdk.com"))
    }

    @Test fun appsOwnServersAreAllowed() {
        assertTrue(Trackers.ownOrContent("i.instagram.com", "com.instagram.android"))
        assertTrue(Trackers.ownOrContent("graph.facebook.com", "com.instagram.android"))   // Meta app -> Meta server
        assertTrue(Trackers.ownOrContent("api.spotify.com", "com.spotify.music"))
        assertFalse(Trackers.ownOrContent("graph.facebook.com", "com.alibaba.aliexpresshd"))
        assertTrue(Trackers.isContent("scontent.cdninstagram.com"))
        assertFalse(Trackers.isContent("graph.facebook.com"))
    }

    @Test fun keywordsFlagUnknownTrackers() {
        assertEquals("analytics", Trackers.keyword("analytics.example-shop.com"))
        assertEquals("telemetry", Trackers.keyword("telemetry.vendor.io"))
        assertEquals("ads", Trackers.keyword("ads.weather.com"))
        assertEquals(null, Trackers.keyword("api.weather.com"))
        assertEquals(null, Trackers.keyword("downloads.example.com"))   // "ads." must start a label
    }

    @Test fun sdkSignatures() {
        val found = SdkScanner.match(listOf(
            "com.appsflyer.SingleInstallBroadcastReceiver", "com.facebook.internal.FacebookInitProvider",
            "com.google.android.gms.ads.AdActivity", "com.example.MainActivity",
        )).map { it.name }
        assertEquals(setOf("AppsFlyer", "Facebook SDK (analytics)", "Google AdMob"), found.toSet())
    }

    @Test fun sameSdkFromTwoListsCountsOnce() {
        assertEquals(SdkScanner.key("Facebook Audience Network"), SdkScanner.key("Facebook Ads"))
        assertEquals(SdkScanner.key("Unity Ads"), SdkScanner.key("Unity3d Ads"))
        assertEquals(SdkScanner.key("Google Crashlytics"), SdkScanner.key("Crashlytics"))
        assertEquals(SdkScanner.key("Pangle (ByteDance)"), SdkScanner.key("Pangle"))
        assertEquals(SdkScanner.key("Vungle / Liftoff"), SdkScanner.key("Vungle"))
        assertEquals(SdkScanner.key("Yandex Ads"), SdkScanner.key("Yandex Ad"))
        assertTrue(SdkScanner.key("Google AdMob") != SdkScanner.key("Google Firebase Analytics"))
    }
}
