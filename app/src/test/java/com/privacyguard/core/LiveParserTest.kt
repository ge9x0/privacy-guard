package com.privacyguard.core

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveParserTest {
    // real lines from a Galaxy A35, Android 17
    private val head = "## RecordActivityMonitor\n\n### Recording Activity\n"
    private val probe = "11-10 22:34:10:797 rec update riid:199 uid:10100 session:137 src:MIC silenced pack:com.pgtest.micprobe\n" +
        "11-10 22:34:16:841 rec stop riid:199 uid:10100 session:137 src:MIC silenced pack:com.pgtest.micprobe\n"
    private val googleStart = "11-11 12:50:41:433 rec start riid:1143 uid:10246 session:1121 src:VOICE_RECOGNITION not silenced pack:com.google.android.googlequicksearchbox\n"
    private val googleStop = "11-11 12:50:42:249 rec stop riid:1143 uid:10246 session:1121 src:VOICE_RECOGNITION not silenced pack:com.google.android.googlequicksearchbox\n"

    @Test fun stoppedRecordingsAreHistoryNotActive() {
        assertEquals(setOf("com.google.android.googlequicksearchbox"), LiveParser.micPackages(head + probe + googleStart))
    }

    @Test fun nothingActiveWhenAllStopped() {
        assertEquals(emptySet<String>(), LiveParser.micPackages(head + probe + googleStart + googleStop))
    }
}
