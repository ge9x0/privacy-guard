package com.privacyguard.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SeverityRefineTest {
    private fun sev(group: String, op: String, pkg: String, system: Boolean, start: Sev = Sev.HIGH) =
        Ops.refine(start, "x", group, op, pkg, system).first

    @Test fun systemAppsReadingTheirOwnDataAreInfo() {
        assertEquals(Sev.INFO, sev("personal", "READ_CONTACTS", "com.samsung.android.app.contacts", true))
        assertEquals(Sev.INFO, sev("personal", "READ_CALENDAR", "com.samsung.android.calendar", true))
    }

    @Test fun vendorAppsOwningTheDataAreInfoEvenIfNotSystem() {
        assertEquals(Sev.INFO, sev("personal", "READ_CALENDAR", "com.google.android.calendar", false))
        assertEquals(Sev.INFO, sev("personal", "READ_CALENDAR", "com.samsung.android.calendar", false))
        assertEquals(Sev.HIGH, sev("personal", "READ_CONTACTS", "com.google.android.calendar", false)) // not its data
        assertEquals(Sev.HIGH, sev("personal", "READ_CALENDAR", "com.example.calendar", false))        // not a vendor
        assertEquals(Sev.INFO, sev("personal", "READ_PHONE_NUMBERS", "com.google.android.contacts", false))
        assertEquals(Sev.HIGH, sev("personal", "READ_PHONE_NUMBERS", "com.instagram.android", false))
        assertEquals(Sev.HIGH, sev("personal", "READ_CALL_LOG", "com.truecaller", false))
    }

    @Test fun messengersSyncingContactsAreMedium() {
        assertEquals(Sev.MED, sev("personal", "READ_CONTACTS", "com.whatsapp", false))
        assertEquals(Sev.MED, sev("personal", "READ_CALL_LOG", "org.telegram.messenger", false))
    }

    @Test fun otherAppsStayHigh() {
        assertEquals(Sev.HIGH, sev("personal", "READ_CONTACTS", "com.zhiliaoapp.musically", false))
        assertEquals(Sev.HIGH, sev("clip", "READ_CLIPBOARD", "com.whatsapp", false))   // clipboard is not contact sync
        assertEquals(Sev.HIGH, sev("personal", "READ_SMS", "com.example.flashlight", false))
    }

    @Test fun systemMicIsLoweredNotHidden() {
        assertEquals(Sev.MED, sev("mic", "RECORD_AUDIO", "com.samsung.android.faceservice", true, Sev.CRIT))
        assertEquals(Sev.CRIT, sev("mic", "RECORD_AUDIO", "com.example.flashlight", false, Sev.CRIT))
    }
}
