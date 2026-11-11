package com.privacyguard.core

import com.privacyguard.data.AccessEvent
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Reads the text of `dumpsys appops`. Same logic as the Python script:
 * keeps the context Uid -> Package -> Op line by line and captures the
 * "Access"/"Reject" lines. The format changes a little between versions, so
 * everything uses tolerant regexes.
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

    /** One row of the discrete table (Android 16+). Rows with only DURATION are long-running accesses. */
    private fun tableRow(row: String, c: Map<String, Int>, out: MutableList<AccessEvent>) {
        val f = row.split('|')
        fun col(name: String) = c[name]?.let { f.getOrNull(it) }?.trim().orEmpty()
        val pkg = col("PACKAGE_NAME")
        val op = col("OP_NAME")
        if (pkg.isEmpty() || op.isEmpty()) return
        runCatching {
            val uid = col("UID").toInt()
            val ts = parseTs(col("ACCESS_TIME"))
            val dur = col("DURATION").toLongOrNull() ?: 0L
            val acc = col("ACCESS_COUNTS").toIntOrNull() ?: 0
            val rej = col("REJECT_COUNTS").toIntOrNull() ?: 0
            val state = col("UID_STATE").lowercase().ifEmpty { "unknown" }
            fun ev(kind: String, d: Long) = AccessEvent(pkg, uid, op, ts, d, state, kind, "discrete")
            if (acc > 0 || dur > 0 || rej == 0) out += ev("access", dur)
            if (rej > 0) out += ev("reject", 0)
        }
    }

    fun parse(text: String): List<AccessEvent> {
        val out = ArrayList<AccessEvent>()
        var uid = -1
        var pkg: String? = null
        var op: String? = null
        var source = "last"
        var cols: Map<String, Int>? = null
        for (line in text.reader().buffered(1 shl 16).lineSequence()) {
            // Android 16+/17: the discrete section is a table "UID|PACKAGE_NAME|...|ACCESS_TIME|...".
            // Extra branch; the older formats below are unchanged.
            val t = line.trim()
            if (t.startsWith("UID|PACKAGE_NAME|")) {
                cols = t.split('|').withIndex().associate { it.value to it.index }
                continue
            }
            val c = cols
            if (c != null) {
                if ('|' in t) { tableRow(t, c, out); continue }
                if (t.isEmpty()) continue
                cols = null
            }
            // Speed: each regex below is tried only when the line could contain it (136k lines on Android 17).
            // The result is the same; skipped lines could not have matched anyway.
            if (t.startsWith("discrete accesses", true) || t.startsWith("discrete ops", true) ||
                t.startsWith("discrete registry", true)
            ) {
                source = "discrete"; pkg = null; op = null; continue
            }
            val mu = if (t.startsWith("Uid")) reUid.find(line) else null
            if (mu != null) {
                uid = uidOf(mu); pkg = null; op = null
                continue
            }
            val mp = if (t.startsWith("Package")) rePkg.find(line) else null
            if (mp != null) {
                pkg = mp.groupValues[1]; op = null
                continue
            }
            val ev = if (line.contains("Access") || line.contains("Reject")) reEvent.find(line) else null
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
            // reOp needs the line to end with the op name, ':' or ')'; "BODY_SENSORS: allow" can't match
            if (pkg != null && t.isNotEmpty() && t[0] in 'A'..'Z' && t.last().let { it == ':' || it == ')' || it == '_' || it in 'A'..'Z' || it in '0'..'9' })
                reOp.find(line)?.let { op = it.groupValues[1] }
        }
        return out
    }
}

/** Finds who is using the microphone/camera right now, from `dumpsys audio` and `dumpsys media.camera`. */
object LiveParser {
    private val rePack = Regex("""pack(?:age)?[:=]\s*([A-Za-z0-9_.]+)""", RegexOption.IGNORE_CASE)
    private val reRecEvent = Regex("""\brec\s+(start|update|stop|release)\b.*?\briid:(\d+).*?\bpack:([A-Za-z0-9_.]+)""", RegexOption.IGNORE_CASE)
    private val reCamClient = Regex("""Client\s+package:\s*([A-Za-z0-9_.]+)""")

    fun micPackages(audioDump: String): Set<String> {
        // The active recordings section; the format varies, so we look for "pack:" after the word "record"
        val start = audioDump.indexOf("RecordActivityMonitor").takeIf { it >= 0 }
            ?: audioDump.indexOf("AudioRecordingConfiguration").takeIf { it >= 0 } ?: return emptySet()
        val section = audioDump.substring(start, minOf(audioDump.length, start + 6000))
        // Android 14+: "rec start/update/stop riid:N ... pack:X" is HISTORY (includes removed apps).
        // Only a riid without "stop/release" after "start/update" is active.
        val evs = reRecEvent.findAll(section).toList()
        if (evs.isNotEmpty()) {
            val last = LinkedHashMap<String, Pair<String, String>>()
            for (m in evs) last[m.groupValues[2]] = m.groupValues[1].lowercase() to m.groupValues[3]
            return last.values.filter { it.first == "start" || it.first == "update" }.map { it.second }
                .filter { it != "android" }.toSet()
        }
        return section.lineSequence()
            .filter { it.contains("rec", true) || it.contains("session", true) }
            .mapNotNull { rePack.find(it)?.groupValues?.get(1) }
            .filter { it != "android" }
            .toSet()
    }

    fun cameraPackages(cameraDump: String): Set<String> =
        reCamClient.findAll(cameraDump).map { it.groupValues[1] }.toSet()
}
