package com.privacyguard.core

import android.content.Context
import android.content.pm.PackageManager
import com.privacyguard.data.AccessEvent
import com.privacyguard.data.ConnRow
import com.privacyguard.data.Db
import com.privacyguard.data.ScreenRow
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

enum class Sev(val label: String, val color: Int, val order: Int) {
    CRIT("KRITIKE", 0xFFC62828.toInt(), 0),
    HIGH("E LARTË", 0xFFE65100.toInt(), 1),
    MED("MESATARE", 0xFFB7791F.toInt(), 2),
    INFO("INFO", 0xFF5B6475.toInt(), 3),
}

object Ops {
    /** op -> (emri, grupi) */
    val map = mapOf(
        "RECORD_AUDIO" to ("Mikrofon" to "mic"),
        "CAMERA" to ("Kamera" to "cam"),
        "PHONE_CALL_MICROPHONE" to ("Mikrofon (thirrje)" to "call"),
        "PHONE_CALL_CAMERA" to ("Kamera (thirrje)" to "call"),
        "RECEIVE_AMBIENT_TRIGGER_AUDIO" to ("Mikrofon (hotword)" to "mic"),
        "FINE_LOCATION" to ("Vendndodhje e saktë" to "loc"),
        "COARSE_LOCATION" to ("Vendndodhje e përafërt" to "loc"),
        "MONITOR_LOCATION" to ("Ndjekje vendndodhjeje" to "loc"),
        "MONITOR_HIGH_POWER_LOCATION" to ("Ndjekje GPS" to "loc"),
        "READ_CONTACTS" to ("Kontaktet" to "personal"),
        "READ_CALL_LOG" to ("Historiku i thirrjeve" to "personal"),
        "READ_SMS" to ("Mesazhet SMS" to "personal"),
        "READ_CALENDAR" to ("Kalendari" to "personal"),
        "GET_ACCOUNTS" to ("Llogaritë" to "personal"),
        "READ_CLIPBOARD" to ("Clipboard (tekst i kopjuar)" to "clip"),
        "READ_MEDIA_IMAGES" to ("Fotot" to "media"),
        "READ_MEDIA_VIDEO" to ("Videot" to "media"),
        "READ_MEDIA_AUDIO" to ("Audio" to "media"),
        "READ_EXTERNAL_STORAGE" to ("Skedarët" to "media"),
        "ACCESS_MEDIA_LOCATION" to ("Vendndodhja në foto" to "media"),
        "BODY_SENSORS" to ("Sensorët e trupit" to "personal"),
        "ACTIVITY_RECOGNITION" to ("Aktiviteti fizik" to "personal"),
        "READ_PHONE_NUMBERS" to ("Numri i telefonit" to "personal"),
    )

    val stateLabel = mapOf(
        "top" to "Në ekran", "fgsvc" to "Shërbim (me njoftim)", "fg" to "Shërbim (me njoftim)",
        "bg" to "Background", "cch" to "Background (fjetur)", "pers" to "Sistem", "unknown" to "E panjohur",
    )
    val background = setOf("bg", "cch", "cached")
    val sensitive = setOf("personal", "loc", "mic", "cam", "clip", "media")
    val fgService = setOf("fgsvc", "fg", "fgs", "fgsvcl")

    /** App-e qe e perdorin mikrofonin/vendndodhjen ne background per arsye te njohura. */
    val knownSystem = mapOf(
        "com.google.android.googlequicksearchbox" to "Google Assistant ('Hey Google')",
        "com.google.android.as" to "Android System Intelligence",
        "com.samsung.android.bixby.agent" to "Bixby",
        "com.samsung.android.bixby.wakeup" to "Bixby (fjala zgjuese)",
        "com.samsung.android.incallui" to "Thirrjet Samsung",
        "com.android.phone" to "Thirrjet",
        "com.samsung.android.dialer" to "Thirrjet Samsung",
        "com.sec.android.app.camera" to "Kamera e Samsung",
        "com.android.systemui" to "Sistemi",
        "android" to "Sistemi Android",
        "com.google.android.gms" to "Google Play Services",
        "com.samsung.android.location" to "Vendndodhja Samsung",
        "com.sec.android.app.voicenote" to "Diktafoni Samsung",
        "com.privacyguard" to "Privacy Guard",
    )
}

/** Emrat e app-eve nga vete telefoni. */
class AppNames(private val ctx: Context) {
    private val cache = HashMap<String, String>()
    private val uidCache = HashMap<Int, String>()

    fun name(pkg: String): String = cache.getOrPut(pkg) {
        if (pkg == UNKNOWN) return@getOrPut "E panjohur"
        runCatching {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA)).toString()
        }.getOrElse { Ops.knownSystem[pkg] ?: pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() } }
    }

    fun pkgForUid(uid: Int): String = uidCache.getOrPut(uid) {
        when (uid) {
            0 -> "root"; 1000 -> "android"; 1001 -> "com.android.phone"
            else -> ctx.packageManager.getPackagesForUid(uid)?.minByOrNull { it.length } ?: "uid $uid"
        }
    }

    companion object { const val UNKNOWN = "?" }
}

data class Ev(
    val e: AccessEvent,
    val label: String,
    val group: String,
    val screenOn: Boolean?,
    val sev: Sev,
    val why: String,
) {
    val bgOrOff get() = e.state in Ops.background || screenOn == false
}

data class Usage(
    var count: Int = 0, var ms: Long = 0, var bg: Int = 0, var bgMs: Long = 0,
    var reject: Int = 0, var first: Long = 0, var last: Long = 0,
)

data class NetUse(var txBg: Long = 0, var txFg: Long = 0, var rx: Long = 0, var mobileTx: Long = 0) {
    val tx get() = txBg + txFg
}

data class TrackerUse(val hosts: MutableSet<String> = mutableSetOf(), var count: Int = 0)

/** "Mori X ne ora H dhe pas N min kontaktoi kompanine Y." Lidhje ne kohe, jo prove e permbajtjes. */
data class Link(val what: String, val ts: Long, val company: String, val host: String, val afterMs: Long)

/** Per nje app: cfare mori, kujt i dergoi (trackera te paleve te treta) dhe sa u bllokuan. */
data class Flow(
    val pkg: String,
    val took: Map<String, Int>,          // emri i te dhenes -> sa here
    val bgTook: Set<String>,             // te marra ne background / me ekran te fikur
    val to: Map<Trackers.T, TrackerUse>,
    val links: List<Link>,
    val blocked: Int,
    val txBg: Long,
)

data class DayReport(
    val day: LocalDate,
    val events: List<Ev>,
    val alerts: List<Ev>,
    val usage: Map<Triple<String, String, String>, Usage>,   // (pkg, label, group)
    val net: Map<String, NetUse>,
    val trackers: Map<String, Map<Trackers.T, TrackerUse>>,
    val screen: List<ScreenRow>,
    val hasConnData: Boolean,
    val flows: List<Flow> = emptyList(),
) {
    private fun tot(g: String, f: (Ev) -> Long): Long = events.filter { it.group == g && it.e.kind == "access" }.sumOf(f)
    val micN get() = tot("mic") { 1 }.toInt()
    val micMs get() = tot("mic") { it.e.durationMs }
    val camN get() = tot("cam") { 1 }.toInt()
    val camMs get() = tot("cam") { it.e.durationMs }
    val locN get() = tot("loc") { 1 }.toInt()
    val crit get() = alerts.count { it.sev == Sev.CRIT }
    val high get() = alerts.count { it.sev == Sev.HIGH }
    val txBg get() = net.values.sumOf { it.txBg }
    val tx get() = net.values.sumOf { it.tx }
    val trk get() = trackers.values.sumOf { m -> m.values.sumOf { it.count } }
}

class Analyzer(private val ctx: Context, private val screenGapMs: Long = 180_000) {
    private val db = Db.get(ctx)
    val names = AppNames(ctx)

    fun dayBounds(day: LocalDate): Pair<Long, Long> {
        val z = ZoneId.systemDefault()
        return day.atStartOfDay(z).toInstant().toEpochMilli() to day.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli()
    }

    private fun screenAt(rows: List<ScreenRow>, ts: Long): Boolean? {
        var lo = 0; var hi = rows.size - 1; var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (rows[mid].ts <= ts) { idx = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (idx < 0 || ts - rows[idx].ts > screenGapMs) return null
        return rows[idx].on
    }

    fun judge(e: AccessEvent, group: String, screenOn: Boolean?): Pair<Sev, String> {
        val bg = e.state in Ops.background
        val fgs = e.state in Ops.fgService
        val off = screenOn == false
        var sev = Sev.INFO
        var why = "Përdorim i dukshëm (app-i ishte në ekran)."
        if (e.kind == "reject") {
            return if (group == "mic" || group == "cam")
                Sev.MED to "Tentoi ta përdorë, por leja ishte e bllokuar. Pse e kërkoi në atë moment?"
            else Sev.INFO to "Tentoi, por u bllokua."
        }
        when (group) {
            "mic", "cam" -> when {
                e.pkg == AppNames.UNKNOWN && off -> { sev = Sev.CRIT; why = "U aktivizua me ekran të FIKUR (app-i nuk dihet në këtë mënyrë)." }
                off -> { sev = Sev.CRIT; why = "Ekrani ishte i FIKUR kur u përdor." }
                bg -> { sev = Sev.CRIT; why = "App-i NUK ishte në ekran (background)." }
                fgs -> { sev = Sev.MED; why = "App-i ishte jashtë ekranit si shërbim. Normale për thirrje ose regjistrim me njoftim." }
            }
            "loc" -> if (bg || fgs || off) { sev = Sev.MED; why = "Vendndodhja u lexua kur app-i s'ishte në ekran." }
            "personal", "clip" -> if (bg || off) { sev = Sev.HIGH; why = "Të dhëna personale u lexuan në background." }
            "media" -> if (bg || off) { sev = Sev.MED; why = "Fotot/skedarët u lexuan në background." }
            "call" -> { sev = Sev.INFO; why = "Pjesë e një thirrjeje." }
        }
        val known = Ops.knownSystem[e.pkg]
        if (known != null && sev != Sev.INFO) {
            sev = if (sev == Sev.MED) Sev.INFO else Sev.MED
            why += " ($known - zakonisht normale.)"
        }
        return sev to why
    }

    fun build(day: LocalDate): DayReport {
        val (from, to) = dayBounds(day)
        val screen = db.screen(from - 600_000, to)
        val raw = db.access(from, to).filter { it.op in Ops.map }

        // e njejta qasje mund te vije nga 'discrete', 'last' dhe 'live' -> mbaj me te sakten
        val exact = raw.filter { it.source == "discrete" }.groupBy { Triple(it.pkg, it.op, it.kind) }
        val exactByOp = raw.filter { it.source != "live" }.groupBy { it.op }
        val kept = raw.filter { e ->
            when (e.source) {
                "last" -> exact[Triple(e.pkg, e.op, e.kind)].orEmpty().none { abs(it.ts - e.ts) <= 90_000 }
                "live" -> exactByOp[e.op].orEmpty().none {
                    abs(it.ts - e.ts) <= 120_000 && (e.pkg == AppNames.UNKNOWN || it.pkg == e.pkg)
                }
                else -> true
            }
        }
        val events = kept.map { e ->
            val (label, group) = Ops.map.getValue(e.op)
            val so = screenAt(screen, e.ts)
            val (sev, why) = judge(e, group, so)
            Ev(e, label, group, so, sev, why)
        }
        val alerts = events.filter { it.sev != Sev.INFO }.sortedWith(compareBy({ it.sev.order }, { it.e.ts }))

        val usage = LinkedHashMap<Triple<String, String, String>, Usage>()
        for (v in events) {
            val u = usage.getOrPut(Triple(v.e.pkg, v.label, v.group)) { Usage() }
            if (v.e.kind == "reject") { u.reject++; continue }
            u.count++; u.ms += v.e.durationMs
            if (v.bgOrOff) { u.bg++; u.bgMs += v.e.durationMs }
            u.first = if (u.first == 0L) v.e.ts else minOf(u.first, v.e.ts)
            u.last = maxOf(u.last, v.e.ts)
        }

        val net = HashMap<String, NetUse>()
        for (r in db.net(from, to)) {
            val n = net.getOrPut(names.pkgForUid(r.uid)) { NetUse() }
            if (r.set == "FOREGROUND") n.txFg += r.tx else n.txBg += r.tx
            n.rx += r.rx
            if (r.net == "MOBILE") n.mobileTx += r.tx
        }

        val conns = db.conns(from, to)
        val trk = HashMap<String, HashMap<Trackers.T, TrackerUse>>()
        for (c in conns) {
            val t = Trackers.classify(c.host) ?: continue
            val pkg = if (c.uid >= 0) names.pkgForUid(c.uid) else AppNames.UNKNOWN
            if (Trackers.isFirstParty(t, pkg)) continue
            val u = trk.getOrPut(pkg) { HashMap() }.getOrPut(t) { TrackerUse() }
            u.hosts += c.host; u.count++
        }
        val flows = flows(events, conns, trk, net)
        return DayReport(day, events, alerts, usage, net, trk, screen.filter { it.ts >= from }, conns.isNotEmpty(), flows)
    }

    /** Lidh qasjet ne te dhena personale me trackerat qe i kontaktoi i njejti app brenda 10 minutash. */
    private fun flows(
        events: List<Ev>, conns: List<ConnRow>, trk: Map<String, Map<Trackers.T, TrackerUse>>, net: Map<String, NetUse>,
    ): List<Flow> {
        val window = 10 * 60_000L
        val took = events.filter { it.e.kind == "access" && it.group in Ops.sensitive }.groupBy { it.e.pkg }
        val pk = { c: ConnRow -> if (c.uid >= 0) names.pkgForUid(c.uid) else AppNames.UNKNOWN }
        val tc = conns.mapNotNull { c ->
            val t = Trackers.classify(c.host) ?: return@mapNotNull null
            val p = pk(c)
            if (Trackers.isFirstParty(t, p)) null else Triple(p, t, c)
        }.groupBy { it.first }.mapValues { (_, v) -> v.sortedBy { it.third.ts } }
        val blocked = conns.filter { it.blocked }.groupingBy(pk).eachCount()
        return trk.keys.filter { it != AppNames.UNKNOWN }.map { app ->
            val evs = took[app].orEmpty().sortedBy { it.e.ts }
            val links = LinkedHashMap<Pair<String, String>, Link>()
            for (ev in evs) {
                val hit = tc[app]?.firstOrNull { it.third.ts >= ev.e.ts && it.third.ts - ev.e.ts <= window } ?: continue
                links.putIfAbsent(ev.label to hit.second.company,
                    Link(ev.label, ev.e.ts, hit.second.company, hit.third.host, hit.third.ts - ev.e.ts))
            }
            Flow(
                app, evs.groupingBy { it.label }.eachCount(), evs.filter { it.bgOrOff }.map { it.label }.toSet(),
                trk[app].orEmpty(), links.values.take(8), blocked[app] ?: 0, net[app]?.txBg ?: 0,
            )
        }.sortedWith(compareBy({ -it.links.size }, { -it.took.size }, { f -> -(f.to.keys.maxOfOrNull { Trackers.weight[it.category] ?: 1 } ?: 0) }))
    }
}

fun fmtDur(ms: Long): String {
    val s = (ms + 500) / 1000
    if (s < 60) return "$s sek"
    val m = s / 60
    if (m < 60) return "$m min ${"%02d".format(s % 60)} s"
    return "${m / 60} o ${"%02d".format(m % 60)} min"
}

fun fmtBytes(b0: Long): String {
    var b = b0.toDouble()
    for (u in listOf("B", "KB", "MB", "GB")) {
        if (b < 1024 || u == "GB") return if (u == "B") "${b.toLong()} B" else "%.1f %s".format(b, u)
        b /= 1024
    }
    return "$b0 B"
}
