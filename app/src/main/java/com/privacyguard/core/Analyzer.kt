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
    CRIT("CRITICAL", 0xFFC62828.toInt(), 0),
    HIGH("HIGH", 0xFFE65100.toInt(), 1),
    MED("MEDIUM", 0xFFB7791F.toInt(), 2),
    INFO("INFO", 0xFF5B6475.toInt(), 3),
}

object Ops {
    /** op -> (emri, grupi) */
    val map = mapOf(
        "RECORD_AUDIO" to ("Microphone" to "mic"),
        "CAMERA" to ("Camera" to "cam"),
        "PHONE_CALL_MICROPHONE" to ("Microphone (call)" to "call"),
        "PHONE_CALL_CAMERA" to ("Camera (call)" to "call"),
        "RECEIVE_AMBIENT_TRIGGER_AUDIO" to ("Microphone (hotword)" to "mic"),
        "FINE_LOCATION" to ("Precise location" to "loc"),
        "COARSE_LOCATION" to ("Approximate location" to "loc"),
        "MONITOR_LOCATION" to ("Location tracking" to "loc"),
        "MONITOR_HIGH_POWER_LOCATION" to ("GPS tracking" to "loc"),
        "READ_CONTACTS" to ("Contacts" to "personal"),
        "READ_CALL_LOG" to ("Call log" to "personal"),
        "READ_SMS" to ("SMS messages" to "personal"),
        "READ_CALENDAR" to ("Calendar" to "personal"),
        "GET_ACCOUNTS" to ("Accounts" to "personal"),
        "READ_CLIPBOARD" to ("Clipboard (copied text)" to "clip"),
        "READ_MEDIA_IMAGES" to ("Photos" to "media"),
        "READ_MEDIA_VIDEO" to ("Videos" to "media"),
        "READ_MEDIA_AUDIO" to ("Audio" to "media"),
        "READ_EXTERNAL_STORAGE" to ("Files" to "media"),
        "ACCESS_MEDIA_LOCATION" to ("Location in photos" to "media"),
        "BODY_SENSORS" to ("Body sensors" to "personal"),
        "ACTIVITY_RECOGNITION" to ("Physical activity" to "personal"),
        "READ_PHONE_NUMBERS" to ("Phone number" to "personal"),
    )

    val stateLabel = mapOf(
        "top" to "On screen", "fgsvc" to "Service (with notification)", "fg" to "Service (with notification)",
        "bg" to "Background", "cch" to "Background (sleeping)", "pers" to "System", "unknown" to "Unknown",
    )
    val background = setOf("bg", "cch", "cached")
    val sensitive = setOf("personal", "loc", "mic", "cam", "clip", "media")
    val fgService = setOf("fgsvc", "fg", "fgs", "fgsvcl")

    /** Mesazheri/thirrje: sinkronizojne kontaktet ne background qe te gjejne personat qe njeh. */
    val contactSync = listOf(
        "com.whatsapp", "org.telegram.", "org.thoughtcrime.securesms", "com.viber.voip", "com.facebook.orca",
        "com.google.android.apps.tachyon", "com.skype.", "com.microsoft.teams", "jp.naver.line.", "com.discord",
    )
    /** App-i i prodhuesit qe e ka vete kete te dhene (kalendari lexon kalendarin, telefoni regjistrin e thirrjeve). */
    private val vendors = listOf("com.samsung.", "com.sec.", "com.google.android.", "com.android.", "com.miui.", "com.xiaomi.",
        "com.huawei.", "com.hihonor.", "com.oplus.", "com.coloros.", "com.oneplus.", "com.motorola.", "com.sonymobile.", "com.nothing.", "com.transsion.")
    private val owners = mapOf(
        "READ_CALENDAR" to listOf("calendar"), "READ_CONTACTS" to listOf("contacts", "dialer", "phone", "messaging"),
        "READ_CALL_LOG" to listOf("dialer", "phone", "contacts"), "READ_SMS" to listOf("messaging", "sms", "mms"),
        "READ_PHONE_NUMBERS" to listOf("contacts", "dialer", "phone", "messaging"),
        "GET_ACCOUNTS" to listOf("contacts", "calendar", "gm", "gms"),
    )
    fun ownsData(op: String, pkg: String) =
        vendors.any { pkg.startsWith(it) } && owners[op].orEmpty().any { it in pkg.substringAfter('.') }

    private val contactOps = setOf("READ_CONTACTS", "READ_CALL_LOG", "READ_PHONE_NUMBERS", "GET_ACCOUNTS")

    /**
     * Ul sinjalizimet per app-et qe e kane te natyrshme: app-et e sistemit (Kontaktet, Telefoni,
     * Mesazhet, Kalendari, Cloud...) lexojne te dhenat e veta, mesazherite sinkronizojne kontaktet.
     * Mikrofoni/kamera e sistemit nuk fshihen, vetem ulen nje nivel.
     */
    fun refine(sev: Sev, why: String, group: String, op: String, pkg: String, system: Boolean): Pair<Sev, String> {
        if (sev == Sev.INFO) return sev to why
        val known = knownSystem[pkg]
        return when {
            known != null -> (if (sev == Sev.MED) Sev.INFO else Sev.MED) to "$why ($known - usually normal.)"
            system && (group == "mic" || group == "cam") ->
                (if (sev == Sev.CRIT) Sev.MED else Sev.INFO) to "$why (Pre-installed system app.)"
            system || ownsData(op, pkg) -> Sev.INFO to "$why (Pre-installed system app; this is its own data or a system feature.)"
            op in contactOps && contactSync.any { pkg.startsWith(it) } ->
                Sev.MED to "Contact sync in the background. Normal for messaging apps, but your contacts are uploaded to their servers."
            else -> sev to why
        }
    }

    /** App-e qe e perdorin mikrofonin/vendndodhjen ne background per arsye te njohura. */
    val knownSystem = mapOf(
        "com.google.android.googlequicksearchbox" to "Google Assistant ('Hey Google')",
        "com.google.android.as" to "Android System Intelligence",
        "com.samsung.android.bixby.agent" to "Bixby",
        "com.samsung.android.bixby.wakeup" to "Bixby (wake word)",
        "com.samsung.android.incallui" to "Samsung Phone",
        "com.android.phone" to "Phone calls",
        "com.samsung.android.dialer" to "Samsung Phone",
        "com.sec.android.app.camera" to "Samsung Camera",
        "com.android.systemui" to "System UI",
        "android" to "Android system",
        "com.google.android.gms" to "Google Play Services",
        "com.samsung.android.location" to "Samsung Location",
        "com.sec.android.app.voicenote" to "Samsung Voice Recorder",
        "com.privacyguard" to "Privacy Guard",
    ).let { m ->
        // ne debug, prova e mikrofonit (TestMicReceiver) duhet te sillet si nje app i vertete;
        // ne release Privacy Guard s'ka fare leje mikrofoni
        if (com.privacyguard.BuildConfig.DEBUG) m - "com.privacyguard" else m
    }
}

/** Emrat e app-eve nga vete telefoni. */
class AppNames(private val ctx: Context) {
    // Te perbashketa per gjithe app-in: emrat lexohen nga sistemi nje here, jo ne cdo karte/tab.
    private val cache get() = sharedNames
    private val uidCache get() = sharedUids
    companion object {
        const val UNKNOWN = "?"
        private val sharedNames = java.util.concurrent.ConcurrentHashMap<String, String>()
        private val sharedUids = java.util.concurrent.ConcurrentHashMap<Int, String>()
    }

    fun name(pkg: String): String = cache.getOrPut(pkg) {
        if (pkg == UNKNOWN) return@getOrPut "Unknown app"
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

/**
 * Nje rresht i regjistrit DNS: kush (app), cilin server, sa here, a u bllokua dhe pse.
 * kind: "tracker" (lista jone), "list" (nje liste bllokimi e njeh), "keyword" (emri tregon gjurmim), null = normal.
 */
data class DnsEntry(
    val pkg: String, val host: String, val count: Int, val blocked: Int, val why: String?,
    val tracker: Trackers.T?, val list: String?, val keyword: String?, val last: Long,
) {
    val kind get() = when { tracker != null -> "tracker"; list != null -> "list"; keyword != null -> "keyword"; else -> null }
}

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
    val dns: List<DnsEntry> = emptyList(),
) {
    private fun tot(g: String, f: (Ev) -> Long): Long = events.filter { it.group == g && it.e.kind == "access" }.sumOf(f)
    val micN get() = tot("mic") { 1 }.toInt()
    val micMs get() = tot("mic") { it.e.durationMs }
    val camN get() = tot("cam") { 1 }.toInt()
    val camMs get() = tot("cam") { it.e.durationMs }
    val locN get() = tot("loc") { 1 }.toInt()
    val crit get() = alerts.count { it.sev == Sev.CRIT }
    val high get() = alerts.count { it.sev == Sev.HIGH }
    /** Sa raste te ndryshme (app + e dhene), jo sa evente: 10 lexime te kontakteve nga i njejti app = 1. */
    fun cases(s: Sev) = alerts.filter { it.sev == s }.map { it.e.pkg to it.label }.toSet().size
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
        var why = "Visible use (the app was on screen)."
        if (e.kind == "reject") {
            return if (group == "mic" || group == "cam")
                Sev.MED to "Tried to use it, but access was blocked. Why did it ask at that moment?"
            else Sev.INFO to "Tried, but was blocked."
        }
        when (group) {
            "mic", "cam" -> when {
                e.pkg == AppNames.UNKNOWN && off -> { sev = Sev.CRIT; why = "Turned on while the screen was OFF (app unknown in this mode)." }
                off -> { sev = Sev.CRIT; why = "The screen was OFF when it was used." }
                bg -> { sev = Sev.CRIT; why = "The app was NOT on screen (background)." }
                fgs -> { sev = Sev.MED; why = "The app was off screen as a service. Normal for calls or recording with a notification." }
            }
            "loc" -> if (bg || fgs || off) { sev = Sev.MED; why = "Location was read while the app was not on screen." }
            "personal", "clip" -> if (bg || off) { sev = Sev.HIGH; why = "Personal data was read in the background." }
            "media" -> if (bg || off) { sev = Sev.MED; why = "Photos/files were read in the background." }
            "call" -> { sev = Sev.INFO; why = "Part of a phone call." }
        }
        return Ops.refine(sev, why, group, e.op, e.pkg, e.uid in 0 until 10000 || isSystem(e.pkg))
    }

    private val systemCache = HashMap<String, Boolean>()
    /** I instaluar nga prodhuesi (Samsung/Google/Android), edhe nese eshte perditesuar. */
    private fun isSystem(pkg: String): Boolean = systemCache.getOrPut(pkg) {
        runCatching {
            val f = ctx.packageManager.getApplicationInfo(pkg, 0).flags
            f and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        }.getOrDefault(false)
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
        return DayReport(day, events, alerts, usage, net, trk, screen.filter { it.ts >= from }, conns.isNotEmpty(), flows, dnsLog(conns))
    }

    private fun dnsLog(conns: List<ConnRow>): List<DnsEntry> {
        val listHit = HashMap<String, String?>()
        return conns.groupBy { (if (it.uid >= 0) names.pkgForUid(it.uid) else AppNames.UNKNOWN) to it.host.lowercase().trimEnd('.') }
            .map { (k, rows) ->
                val (pkg, host) = k
                val t = Trackers.classify(host)?.takeIf { !Trackers.isFirstParty(it, pkg) }
                val list = listHit.getOrPut(host) { runCatching { com.privacyguard.block.FilterLists.match(ctx, host) }.getOrNull() }
                DnsEntry(pkg, host, rows.size, rows.count { it.blocked }, rows.lastOrNull { it.blocked }?.why, t,
                    list, if (t == null) Trackers.keyword(host) else null, rows.maxOf { it.ts })
            }.sortedWith(compareBy({ it.kind == null }, { -it.count }))
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
    if (s < 60) return "$s s"
    val m = s / 60
    if (m < 60) return "${m}m ${"%02d".format(s % 60)}s"
    return "${m / 60}h ${"%02d".format(m % 60)}m"
}

fun fmtBytes(b0: Long): String {
    var b = b0.toDouble()
    for (u in listOf("B", "KB", "MB", "GB")) {
        if (b < 1024 || u == "GB") return if (u == "B") "${b.toLong()} B" else "%.1f %s".format(b, u)
        b /= 1024
    }
    return "$b0 B"
}
