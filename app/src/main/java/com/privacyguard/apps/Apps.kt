package com.privacyguard.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.privacyguard.Prefs
import com.privacyguard.block.Blocker
import com.privacyguard.collect.Shell
import com.privacyguard.core.Analyzer
import com.privacyguard.core.Sev
import com.privacyguard.core.Trackers
import com.privacyguard.data.Db
import java.time.LocalDate

/** App-et e instaluara nga perdoruesi (jo te sistemit), me emrin. */
object UserApps {
    fun list(ctx: Context): List<PackageInfo> {
        val pm = ctx.packageManager
        val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS or PackageManager.GET_META_DATA or PackageManager.GET_PERMISSIONS
        return pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
            .filter { it.applicationInfo?.let { a -> a.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0 } == true }
            .filter { it.packageName != ctx.packageName }
    }

    fun info(ctx: Context, pkg: String): PackageInfo? = runCatching {
        val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS or PackageManager.GET_META_DATA or PackageManager.GET_PERMISSIONS
        ctx.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags.toLong()))
    }.getOrNull()
}

/**
 * Gjen SDK-te e gjurmimit brenda nje app-i (si Exodus Privacy): nga emrat e komponenteve
 * (activity, service, receiver, provider) dhe meta-data te manifestit. Nuk ekzekuton asgje.
 */
object SdkScanner {
    data class Sdk(val name: String, val category: String)

    private val sig: List<Pair<String, Sdk>> = listOf(
        "com.facebook.ads." to Sdk("Facebook Audience Network", Trackers.ADS),
        "com.facebook.appevents" to Sdk("Facebook SDK (analytics)", Trackers.META),
        "com.facebook.internal.FacebookInitProvider" to Sdk("Facebook SDK (analytics)", Trackers.META),
        "com.facebook.sdk.ApplicationId" to Sdk("Facebook SDK (analytics)", Trackers.META),
        "com.facebook.FacebookActivity" to Sdk("Facebook Login", Trackers.META),
        "com.google.android.gms.ads." to Sdk("Google AdMob", Trackers.GOOGLE),
        "com.google.android.gms.ads.APPLICATION_ID" to Sdk("Google AdMob", Trackers.GOOGLE),
        "com.google.android.gms.measurement." to Sdk("Google Firebase Analytics", Trackers.GOOGLE),
        "com.google.firebase.analytics" to Sdk("Google Firebase Analytics", Trackers.GOOGLE),
        "com.google.firebase.crashlytics" to Sdk("Google Crashlytics", Trackers.GOOGLE),
        "com.google.android.gms.analytics." to Sdk("Google Analytics", Trackers.GOOGLE),
        "com.appsflyer." to Sdk("AppsFlyer", Trackers.ATTR),
        "com.adjust.sdk." to Sdk("Adjust", Trackers.ATTR),
        "io.branch." to Sdk("Branch", Trackers.ATTR),
        "com.singular." to Sdk("Singular", Trackers.ATTR),
        "com.kochava." to Sdk("Kochava", Trackers.BROKER),
        "com.applovin." to Sdk("AppLovin", Trackers.ADS),
        "com.unity3d.ads." to Sdk("Unity Ads", Trackers.ADS),
        "com.unity3d.services." to Sdk("Unity Ads", Trackers.ADS),
        "com.ironsource." to Sdk("ironSource", Trackers.ADS),
        "com.mbridge." to Sdk("Mintegral", Trackers.ADS),
        "com.mintegral." to Sdk("Mintegral", Trackers.ADS),
        "com.vungle." to Sdk("Vungle / Liftoff", Trackers.ADS),
        "com.chartboost." to Sdk("Chartboost", Trackers.ADS),
        "com.inmobi." to Sdk("InMobi", Trackers.ADS),
        "com.bytedance.sdk.openadsdk." to Sdk("Pangle (ByteDance)", Trackers.BYTEDANCE),
        "com.bytedance.applog" to Sdk("ByteDance AppLog", Trackers.BYTEDANCE),
        "com.adcolony." to Sdk("AdColony", Trackers.ADS),
        "com.fyber." to Sdk("Fyber", Trackers.ADS),
        "com.tapjoy." to Sdk("Tapjoy", Trackers.ADS),
        "com.startapp." to Sdk("Start.io", Trackers.ADS),
        "com.smaato." to Sdk("Smaato", Trackers.ADS),
        "com.criteo." to Sdk("Criteo", Trackers.ADS),
        "com.taboola." to Sdk("Taboola", Trackers.ADS),
        "com.outbrain." to Sdk("Outbrain", Trackers.ADS),
        "com.yandex.mobile.ads." to Sdk("Yandex Ads", Trackers.ADS),
        "com.yandex.metrica." to Sdk("Yandex AppMetrica", Trackers.ANALYTICS),
        "io.appmetrica." to Sdk("Yandex AppMetrica", Trackers.ANALYTICS),
        "com.onesignal." to Sdk("OneSignal", Trackers.ANALYTICS),
        "com.amplitude." to Sdk("Amplitude", Trackers.ANALYTICS),
        "com.mixpanel." to Sdk("Mixpanel", Trackers.ANALYTICS),
        "com.braze." to Sdk("Braze", Trackers.ANALYTICS),
        "com.appboy." to Sdk("Braze", Trackers.ANALYTICS),
        "com.clevertap." to Sdk("CleverTap", Trackers.ANALYTICS),
        "com.moengage." to Sdk("MoEngage", Trackers.ANALYTICS),
        "com.segment." to Sdk("Segment", Trackers.ANALYTICS),
        "com.flurry." to Sdk("Flurry", Trackers.ANALYTICS),
        "com.huawei.hms.analytics." to Sdk("Huawei Analytics", Trackers.ANALYTICS),
        "com.newrelic." to Sdk("New Relic", Trackers.ANALYTICS),
        "io.sentry." to Sdk("Sentry (crash reports)", Trackers.ANALYTICS),
        "com.bugsnag." to Sdk("Bugsnag (crash reports)", Trackers.ANALYTICS),
        "com.uxcam." to Sdk("UXCam (records the screen)", Trackers.ANALYTICS),
        "com.smartlook." to Sdk("Smartlook (records the screen)", Trackers.ANALYTICS),
        "com.microsoft.clarity." to Sdk("Microsoft Clarity (records the screen)", Trackers.ANALYTICS),
        "com.comscore." to Sdk("comScore", Trackers.ANALYTICS),
        "com.foursquare." to Sdk("Foursquare Pilgrim", Trackers.BROKER),
        "io.radar." to Sdk("Radar", Trackers.BROKER),
        "com.cuebiq." to Sdk("Cuebiq", Trackers.BROKER),
        "io.mysdk." to Sdk("X-Mode / Outlogic", Trackers.BROKER),
        "com.xmode." to Sdk("X-Mode / Outlogic", Trackers.BROKER),
        "io.huq." to Sdk("Huq Industries", Trackers.BROKER),
        "com.tutelatechnologies." to Sdk("Tutela", Trackers.BROKER),
        "com.incognia." to Sdk("Incognia", Trackers.BROKER),
    )

    fun match(strings: Collection<String>): List<Sdk> =
        sig.filter { (p, _) -> strings.any { it.startsWith(p) } }.map { it.second }.distinct()

    fun scan(pi: PackageInfo): List<Sdk> {
        val s = HashSet<String>()
        pi.activities?.forEach { s += it.name }
        pi.receivers?.forEach { s += it.name }
        pi.providers?.forEach { s += it.name }
        pi.services?.forEach { sv -> s += sv.name; sv.metaData?.keySet()?.forEach { k -> s += k.substringAfter("com.google.firebase.components:") } }
        pi.applicationInfo?.metaData?.keySet()?.let { s += it }
        return match(s)
    }

    private fun p(ctx: Context) = ctx.getSharedPreferences("pg_sdk", Context.MODE_PRIVATE)

    /** Me cache sipas versionit; skanimi i plote i ~100 app-eve zgjat disa sekonda. */
    fun of(ctx: Context, pi: PackageInfo): List<Sdk> {
        val key = "${pi.longVersionCode}:${pi.lastUpdateTime}"
        val c = p(ctx).getString(pi.packageName, null)
        if (c != null && c.substringBefore('|') == key) return decode(c.substringAfter('|'))
        val r = scan(pi)
        p(ctx).edit().putString(pi.packageName, key + "|" + r.joinToString(";") { "${it.name}~${it.category}" }).apply()
        return r
    }

    private fun decode(s: String) = if (s.isEmpty()) emptyList() else s.split(';').map { val (n, c) = it.split('~', limit = 2); Sdk(n, c) }
}

/** Leje te rrezikshme: grupi -> lejet e Android-it dhe op-i i AppOps per "perdorur se fundi". */
object PermGroups {
    data class G(val id: String, val label: String, val perms: List<String>, val ops: List<String>)
    val all = listOf(
        G("mic", "Microphone", listOf("android.permission.RECORD_AUDIO"), listOf("RECORD_AUDIO")),
        G("cam", "Camera", listOf("android.permission.CAMERA"), listOf("CAMERA")),
        G("loc", "Location", listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_BACKGROUND_LOCATION"), listOf("FINE_LOCATION", "COARSE_LOCATION")),
        G("contacts", "Contacts", listOf("android.permission.READ_CONTACTS", "android.permission.WRITE_CONTACTS"), listOf("READ_CONTACTS")),
        G("calllog", "Call log", listOf("android.permission.READ_CALL_LOG", "android.permission.WRITE_CALL_LOG"), listOf("READ_CALL_LOG")),
        G("sms", "SMS", listOf("android.permission.READ_SMS", "android.permission.RECEIVE_SMS", "android.permission.SEND_SMS"), listOf("READ_SMS")),
        G("calendar", "Calendar", listOf("android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR"), listOf("READ_CALENDAR")),
        G("phone", "Phone number & status", listOf("android.permission.READ_PHONE_NUMBERS", "android.permission.READ_PHONE_STATE"), listOf("READ_PHONE_NUMBERS")),
        G("sensors", "Body sensors", listOf("android.permission.BODY_SENSORS"), listOf("BODY_SENSORS")),
        G("activity", "Physical activity", listOf("android.permission.ACTIVITY_RECOGNITION"), listOf("ACTIVITY_RECOGNITION")),
        G("photos", "Photos & videos", listOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO"), listOf("READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO")),
    )

    fun granted(pi: PackageInfo): Map<G, List<String>> {
        val req = pi.requestedPermissions ?: return emptyMap()
        val fl = pi.requestedPermissionsFlags ?: return emptyMap()
        val g = req.indices.filter { fl[it] and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0 }.map { req[it] }.toSet()
        return all.associateWith { gr -> gr.perms.filter { it in g } }.filterValues { it.isNotEmpty() }
    }
}

/**
 * Keshilla per lejet: leje qe app-i s'i ka perdorur, ose qe s'perkojne me llojin e app-it
 * (nje loje me SMS, nje elektrik dore me kontakte). "Hiq" ekzekuton `pm revoke`.
 */
object PermissionAdvisor {
    data class Advice(val pkg: String, val group: PermGroups.G, val perms: List<String>, val reason: String, val strong: Boolean)

    private val socialish = setOf(ApplicationInfo.CATEGORY_SOCIAL)
    private val noPersonal = setOf(
        ApplicationInfo.CATEGORY_GAME, ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO,
        ApplicationInfo.CATEGORY_IMAGE, ApplicationInfo.CATEGORY_NEWS,
    )

    fun advise(ctx: Context): List<Advice> {
        val db = Db.get(ctx)
        val now = System.currentTimeMillis()
        val since = maxOf(Prefs.since(ctx), now - 60 * 86_400_000L)
        val days = ((now - since) / 86_400_000L).toInt()
        // perdorimi i fundit per pkg+op nga historiku
        val last = HashMap<Pair<String, String>, Long>()
        for (e in db.access(since, now + 1)) if (e.kind == "access") last.merge(e.pkg to e.op, e.ts, ::maxOf)
        val sms = runCatching { android.provider.Telephony.Sms.getDefaultSmsPackage(ctx) }.getOrNull()
        val dialer = runCatching { ctx.getSystemService(android.telecom.TelecomManager::class.java).defaultDialerPackage }.getOrNull()
        val out = ArrayList<Advice>()
        for (pi in UserApps.list(ctx)) {
            val pkg = pi.packageName
            val cat = pi.applicationInfo?.category ?: ApplicationInfo.CATEGORY_UNDEFINED
            for ((g, perms) in PermGroups.granted(pi)) {
                val used = g.ops.mapNotNull { last[pkg to it] }.maxOrNull()
                val reason: String? = when {
                    g.id == "sms" && pkg != sms -> "Can read your SMS but is not your messaging app. One-time codes arrive by SMS."
                    g.id == "calllog" && pkg != dialer && cat !in socialish -> "Can read who you call, but it is not your phone app."
                    "android.permission.ACCESS_BACKGROUND_LOCATION" in perms -> "Allowed to track your location all the time, even when closed."
                    cat in noPersonal && g.id in setOf("contacts", "calllog", "sms", "loc", "mic") ->
                        "Unusual for this kind of app (${catName(cat)})."
                    used == null && days >= 7 -> "Not used in the last $days days."
                    used == null -> "Not used since tracking began $days day(s) ago."
                    else -> null
                }
                if (reason != null) out += Advice(pkg, g, perms, reason, strong = used == null && days >= 7 || !reason.startsWith("Not used"))
            }
        }
        return out.sortedWith(compareBy({ !it.strong }, { it.pkg }))
    }

    private fun catName(c: Int) = when (c) {
        ApplicationInfo.CATEGORY_GAME -> "game"; ApplicationInfo.CATEGORY_AUDIO -> "music/audio"
        ApplicationInfo.CATEGORY_VIDEO -> "video"; ApplicationInfo.CATEGORY_IMAGE -> "photo"
        ApplicationInfo.CATEGORY_NEWS -> "news"; else -> "app"
    }

    private fun p(ctx: Context) = ctx.getSharedPreferences("pg_revoked", Context.MODE_PRIVATE)
    fun revoked(ctx: Context): Map<String, String> = p(ctx).all.mapValues { it.value.toString() }   // "pkg|group" -> perms

    /** Heq lejet me `pm revoke` (kerkon Wireless debugging), perndryshe hap Settings. */
    fun revoke(ctx: Context, a: Advice): Blocker.Result {
        val cmds = a.perms.map { "pm revoke ${a.pkg} $it" }
        if (!Shell.adbConnected(ctx, true)) return Blocker.Result.Manual(
            "Removing a permission needs Wireless debugging connected. Or remove it yourself in Settings, or run from a computer:",
            cmds.map { "adb shell $it" })
        for (c in cmds) {
            val r = runCatching { Shell.adb(ctx, c) }.getOrElse { "error: ${it.message}" }
            if (r.isNotBlank()) return Blocker.Result.Done("Failed: ${r.trim()}")
        }
        p(ctx).edit().putString("${a.pkg}|${a.group.id}", a.perms.joinToString(",")).apply()
        return Blocker.Result.Done("${a.group.label} removed. The app will ask again if it really needs it.")
    }

    fun restore(ctx: Context, key: String): Blocker.Result {
        val perms = p(ctx).getString(key, null)?.split(',') ?: return Blocker.Result.Done("Nothing to restore.")
        val pkg = key.substringBefore('|')
        val cmds = perms.map { "pm grant $pkg $it" }
        if (!Shell.adbConnected(ctx, true)) return Blocker.Result.Manual("Restoring needs Wireless debugging, or run from a computer:", cmds.map { "adb shell $it" })
        for (c in cmds) runCatching { Shell.adb(ctx, c) }
        p(ctx).edit().remove(key).apply()
        return Blocker.Result.Done("Permission given back.")
    }
}

/** Pike privatesie 0-100 per app, nga 7 ditet e fundit. 100 = asgje e dyshimte. */
object Score {
    data class S(val pkg: String, val score: Int, val crit: Int, val high: Int, val med: Int,
                 val companies: Set<String>, val sdks: Int, val bgMb: Long)

    fun compute(ctx: Context, days: Int = 7): List<S> {
        val an = Analyzer(ctx)
        val acc = HashMap<String, IntArray>()                 // crit, high, med
        val comp = HashMap<String, MutableSet<String>>()
        val bg = HashMap<String, Long>()
        for (i in 0 until days) {
            val d = an.build(LocalDate.now().minusDays(i.toLong()))
            for (a in d.alerts) {
                val x = acc.getOrPut(a.e.pkg) { IntArray(3) }
                when (a.sev) { Sev.CRIT -> x[0]++; Sev.HIGH -> x[1]++; Sev.MED -> x[2]++; else -> {} }
            }
            for ((pkg, m) in d.trackers) comp.getOrPut(pkg) { HashSet() } += m.keys.filter { !Trackers.isFirstParty(it, pkg) }.map { it.company }
            for ((pkg, n) in d.net) bg.merge(pkg, n.txBg, Long::plus)
        }
        val sdks = UserApps.list(ctx).associate { it.packageName to SdkScanner.of(ctx, it).size }
        val pkgs = (acc.keys + comp.keys + sdks.keys).filter { it in sdks }   // vetem app-et e perdoruesit
        return pkgs.map { pkg ->
            val a = acc[pkg] ?: IntArray(3)
            val c = comp[pkg].orEmpty()
            val mb = (bg[pkg] ?: 0) / 1_048_576
            val pen = minOf(a[0], 4) * 15 + minOf(a[1], 5) * 8 + minOf(a[2], 5) * 2 + minOf(c.size, 6) * 4 +
                minOf(sdks[pkg] ?: 0, 8) * 2 + (if (mb > 50) 5 else 0)
            S(pkg, (100 - pen).coerceIn(0, 100), a[0], a[1], a[2], c, sdks[pkg] ?: 0, mb)
        }.sortedBy { it.score }
    }
}
