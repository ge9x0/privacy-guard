package com.privacyguard.core

import com.privacyguard.data.AccessEvent
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Lexon tekstin e `dumpsys appops`. I njejti logjike si scripti Python:
 * mban kontekstin Uid -> Package -> Op sipas rreshtave dhe kap rreshtat
 * "Access"/"Reject". Formati ndryshon pak nga versioni ne version, prandaj
 * gjithcka eshte me regex te tolerueshem.
 */
object AppOpsParser {
    private val reUid = Regex("""^\s*Uid[: ]\s*(u(\d+)a(\d+)|u(\d+)s(\d+)|\d+)\b""")
    private val rePkg = Regex("""^\s*Package[: ]\s*([A-Za-z0-9_.]+)""")
    private val reOp = Regex("""^\s*(?:Op[: ]\s*)?([A-Z][A-Z0-9_]{2,})\b\s*(?:\(.*\))?:?\s*$""")
    private val reEvent = Regex(
        """(Access|Reject)\b[^\[]*\[([a-zA-Z]+)(?:-([a-zA-Z]+))?\][^0-9]*""" +
            """(\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(?:\.\d+)?)"""
    )
    private val reDurFor = Regex("""for\s+(\d+)\s*(?:milliseconds|ms)""")
    private val reDurPlus = Regex("""duration=\+?((?:\d+d)?(?:\d+h)?(?:\d+m(?!s))?(?:\d+s)?(?:\d+ms)?)""")
    private val reDurPart = Regex("""(\d+)(ms|d|h|m|s)""")
    private val reRelative = Regex("""\((-|\+)\d""")
    private val fmtMs = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val fmtS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private fun uidOf(m: MatchResult): Int {
        val g = m.groupValues
        return when {
            g[2].isNotEmpty() -> g[2].toInt() * 100000 + 10000 + g[3].toInt()
            g[4].isNotEmpty() -> g[4].toInt() * 100000 + g[5].toInt()
            else -> g[1].toInt()
        }
    }

    private fun duration(line: String): Long {
        reDurFor.find(line)?.let { return it.groupValues[1].toLong() }
        val m = reDurPlus.find(line) ?: return 0
        val unit = mapOf("d" to 86_400_000L, "h" to 3_600_000L, "m" to 60_000L, "s" to 1000L, "ms" to 1L)
        return reDurPart.findAll(m.groupValues[1]).sumOf { it.groupValues[1].toLong() * unit.getValue(it.groupValues[2]) }
    }

    private fun parseTs(s0: String): Long {
        var s = s0.replace('T', ' ')
        val dt = if ('.' in s) {
            val (a, b) = s.split('.', limit = 2)
            s = a + "." + b.padEnd(3, '0').take(3)
            LocalDateTime.parse(s, fmtMs)
        } else LocalDateTime.parse(s, fmtS)
        return dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    fun parse(text: String): List<AccessEvent> {
        val out = ArrayList<AccessEvent>()
        var uid = -1
        var pkg: String? = null
        var op: String? = null
        var source = "last"
        for (line in text.lineSequence()) {
            val low = line.trim().lowercase()
            if (low.startsWith("discrete accesses") || low.startsWith("discrete ops") ||
                low.startsWith("discrete registry")
            ) {
                source = "discrete"; pkg = null; op = null; continue
            }
            val mu = reUid.find(line)
            if (mu != null) {
                uid = uidOf(mu); pkg = null; op = null
                continue
            }
            val mp = rePkg.find(line)
            if (mp != null) {
                pkg = mp.groupValues[1]; op = null
                continue
            }
            val ev = reEvent.find(line)
            if (ev != null) {
                val p = pkg
                val o = op
                if (p != null && o != null) {
                    runCatching {
                        out += AccessEvent(
                            pkg = p, uid = uid, op = o,
                            ts = parseTs(ev.groupValues[4]),
                            durationMs = duration(line),
                            state = ev.groupValues[2].lowercase(),
                            kind = ev.groupValues[1].lowercase(),
                            source = if (reRelative.containsMatchIn(line)) "last" else source,
                        )
                    }
                }
                continue
            }
            val m = reOp.find(line)
            if (m != null && pkg != null) op = m.groupValues[1]
        }
        return out
    }
}

/** Gjen kush po perdor mikrofonin/kameren tani, nga `dumpsys audio` dhe `dumpsys media.camera`. */
object LiveParser {
    private val rePack = Regex("""pack(?:age)?[:=]\s*([A-Za-z0-9_.]+)""", RegexOption.IGNORE_CASE)
    private val reCamClient = Regex("""Client\s+package:\s*([A-Za-z0-9_.]+)""")

    fun micPackages(audioDump: String): Set<String> {
        // Seksioni i regjistrimeve aktive; formati ndryshon, prandaj kerkojme "pack:" pas fjales "record"
        val start = audioDump.indexOf("RecordActivityMonitor").takeIf { it >= 0 }
            ?: audioDump.indexOf("AudioRecordingConfiguration").takeIf { it >= 0 } ?: return emptySet()
        val section = audioDump.substring(start, minOf(audioDump.length, start + 6000))
        return section.lineSequence()
            .filter { it.contains("rec", true) || it.contains("session", true) }
            .mapNotNull { rePack.find(it)?.groupValues?.get(1) }
            .filter { it != "android" }
            .toSet()
    }

    fun cameraPackages(cameraDump: String): Set<String> =
        reCamClient.findAll(cameraDump).map { it.groupValues[1] }.toSet()
}
