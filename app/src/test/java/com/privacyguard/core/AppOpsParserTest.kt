package com.privacyguard.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class AppOpsParserTest {
    private fun ms(s: String) =
        LocalDateTime.parse(s.replace(' ', 'T')).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun discreteFormat() {
        val txt = """
            Discrete accesses:
              Uid: u0a123
                Package: com.example.spy
                  Op: RECORD_AUDIO
                      Access [top-s] at 2026-11-08 10:15:00.000 for 12000 milliseconds
                  Op: CAMERA
                      Reject [bg] at 2026-11-08 11:00:05.5 for 0 milliseconds
        """.trimIndent()
        val ev = AppOpsParser.parse(txt)
        assertEquals(2, ev.size)
        with(ev[0]) {
            assertEquals("com.example.spy", pkg)
            assertEquals(10123, uid)
            assertEquals("RECORD_AUDIO", op)
            assertEquals(ms("2026-11-08 10:15:00.000"), ts)
            assertEquals(12_000L, durationMs)
            assertEquals("top", state)
            assertEquals("access", kind)
            assertEquals("discrete", source)
        }
        with(ev[1]) {
            assertEquals("CAMERA", op)
            assertEquals("reject", kind)
            assertEquals("bg", state)
            assertEquals(ms("2026-11-08 11:00:05.500"), ts)
            assertEquals(0L, durationMs)
        }
    }

    @Test
    fun regularFormat() {
        val txt = """
            Current AppOps Service state:
              Uid 10456:
                state=top
                Package com.example.maps:
                  COARSE_LOCATION (allow / switch COARSE_LOCATION=allow):
                    Access: [bg-s] 2026-11-08 10:15:00.123 (-5m) duration=+12s345ms
                  RECORD_AUDIO (allow):
                    Access: [fg-s] 2026-11-08 09:00:00.000 (-1h20m) duration=+1h2m3s
        """.trimIndent()
        val ev = AppOpsParser.parse(txt)
        assertEquals(2, ev.size)
        with(ev[0]) {
            assertEquals("com.example.maps", pkg)
            assertEquals(10456, uid)
            assertEquals("COARSE_LOCATION", op)
            assertEquals(ms("2026-11-08 10:15:00.123"), ts)
            assertEquals(12_345L, durationMs)
            assertEquals("bg", state)
            assertEquals("access", kind)
            assertEquals("last", source)
        }
        assertEquals("RECORD_AUDIO", ev[1].op)
        assertEquals(3_723_000L, ev[1].durationMs)
    }

    /** Android 17 (Galaxy A35, One UI 9): the discrete section as a table. Lines in the real format. */
    @Test
    fun android17DiscreteTable() {
        val txt = """
            Uid 10319:
                Package com.instagram.android:
                  CAMERA (allow):
                    null=[
                      Access: [top-s] 2026-01-15 18:07:20.167 (-3h1m) duration=+442ms
                    ]
            ------------Discrete App Ops-------------

            UID|PACKAGE_NAME|DEVICE_ID|OP_NAME|ATTRIBUTION_TAG|ACCESS_TIME|ACCESS_COUNTS|REJECT_COUNTS|DURATION|UID_STATE|OP_FLAGS|ATTRIBUTION_CHAIN_ID|ATTRIBUTION_FLAGS
            10319|com.instagram.android|default:0|CAMERA|null|2026-01-15 18:07:20.167|0|0|442|top|s|-1|0
            10346|com.truecaller|default:0|READ_DEVICE_IDENTIFIERS|null|2026-01-15 20:30:01.912|0|96|0|bg|s|-1|0
            15010401|com.google.android.marvin.talkback|default:0|BIND_ACCESSIBILITY_SERVICE|null|2026-01-15 21:06:20.089|2|0|0|cch|s|-1|0
        """.trimIndent()
        val ev = AppOpsParser.parse(txt)
        assertEquals(4, ev.size)
        assertEquals("last", ev[0].source)
        assertEquals(442L, ev[0].durationMs)
        with(ev[1]) {
            assertEquals("com.instagram.android", pkg)
            assertEquals(10319, uid)
            assertEquals("CAMERA", op)
            assertEquals(ms("2026-01-15 18:07:20.167"), ts)
            assertEquals(442L, durationMs)
            assertEquals("top", state)
            assertEquals("access", kind)
            assertEquals("discrete", source)
        }
        with(ev[2]) {
            assertEquals("com.truecaller", pkg)
            assertEquals("reject", kind)
            assertEquals("bg", state)
        }
        with(ev[3]) {
            assertEquals(15010401, uid)
            assertEquals("access", kind)
            assertEquals("cch", state)
        }
    }

    @Test
    fun noEventsWithoutPackageContext() {
        assertEquals(0, AppOpsParser.parse("Access [top-s] at 2026-11-08 10:15:00.000 for 1 milliseconds").size)
    }
}
