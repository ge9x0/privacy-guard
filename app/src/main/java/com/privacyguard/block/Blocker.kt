package com.privacyguard.block

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import com.privacyguard.collect.Shell
import com.privacyguard.core.Ops
import com.privacyguard.core.Trackers
import java.util.concurrent.ConcurrentHashMap

/**
 * Blocking, in two layers:
 *
 *  1. DNS (through the local VPN): third-party trackers, chosen domains,
 *     or an app's whole internet -> NXDOMAIN answer. Works in every mode.
 *  2. Permissions (app-ops): "allow only while in use" for microphone, camera, contacts...
 *     Needs the ADB connection (Wireless debugging); otherwise the app's Settings page opens.
 *     The previous mode is saved so "Restore" puts it back as it was.
 */
object Blocker {
    private const val TAG = "PG.Block"
    private fun p(ctx: Context) = ctx.getSharedPreferences("pg_block", Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ DNS
    fun trackersOn(ctx: Context) = p(ctx).getBoolean("trackers", false)
    /** Stop apps that use their own DNS (DoH/DoT, hardcoded 8.8.8.8). */
    fun antiBypass(ctx: Context) = p(ctx).getBoolean("antibypass", true)
    fun setAntiBypass(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("antibypass", v).apply()
    fun setTrackers(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("trackers", v).apply()

    fun apps(ctx: Context): Set<String> = p(ctx).getStringSet("apps", emptySet())!!.toSet()
    /** Internet is allowed only while the app is on screen. */
    fun bgApps(ctx: Context): Set<String> = p(ctx).getStringSet("bgapps", emptySet())!!.toSet()
    fun setBgApp(ctx: Context, pkg: String, blocked: Boolean) = edit(ctx, "bgapps", pkg, blocked)
    /** Domains the user allowed even though a list blocks them (false positive). */
    fun allowed(ctx: Context): Set<String> = p(ctx).getStringSet("allow", emptySet())!!.toSet()
    fun setAllowed(ctx: Context, host: String, v: Boolean) = edit(ctx, "allow", host.lowercase().trimEnd('.'), v)
    fun hosts(ctx: Context): Set<String> = p(ctx).getStringSet("hosts", emptySet())!!.toSet()
    fun setApp(ctx: Context, pkg: String, blocked: Boolean) = edit(ctx, "apps", pkg, blocked)
    fun setHost(ctx: Context, host: String, blocked: Boolean) = edit(ctx, "hosts", host.lowercase().trimEnd('.'), blocked)

    private fun edit(ctx: Context, key: String, v: String, add: Boolean) {
        val s = p(ctx).getStringSet(key, emptySet())!!.toMutableSet()
        if (add) s += v else s -= v
        p(ctx).edit().putStringSet(key, s).apply()
    }

    private val uidPkgs = ConcurrentHashMap<Int, List<String>>()
    private fun pkgsOf(ctx: Context, uid: Int): List<String> = uidPkgs.getOrPut(uid) {
        ctx.packageManager.getPackagesForUid(uid)?.toList().orEmpty()
    }

    /** Domains never to touch: connectivity checks, time, push notifications. */
    private val essential = setOf(
        "connectivitycheck.gstatic.com", "connectivitycheck.android.com", "clients3.google.com", "time.android.com",
        "mtalk.google.com", "android.clients.google.com", "play.googleapis.com",
    )

    /**
     * Encrypted DNS servers (DoH/DoT). An app using them bypasses the phone's DNS,
     * and so the blocking; when they don't resolve, it falls back to normal DNS. Browsers do this themselves.
     */
    val dohHosts = setOf(
        "dns.google", "dns64.dns.google", "cloudflare-dns.com", "mozilla.cloudflare-dns.com", "chrome.cloudflare-dns.com",
        "one.one.one.one", "1dot1dot1dot1.cloudflare-dns.com", "dns.quad9.net", "dns9.quad9.net", "dns11.quad9.net",
        "doh.opendns.com", "dns.adguard-dns.com", "dns.adguard.com", "dns.nextdns.io", "doh.cleanbrowsing.org",
        "dns.mullvad.net", "doh.dns.sb", "dns.alidns.com", "doh.pub", "dns.twnic.tw", "doh.libredns.gr",
    )

    /** The block reason, or null if the request is allowed. Called for every DNS request. */
    fun dnsVerdict(ctx: Context, uid: Int, host: String): String? {
        val h = host.lowercase().trimEnd('.')
        val sx = suffixes(h)
        val hs = hosts(ctx)
        if (hs.isNotEmpty() && sx.any { it in hs }) return "domain"
        val al = allowed(ctx)
        if (al.isNotEmpty() && sx.any { it in al }) return null
        if (h in essential) return null
        if (antiBypass(ctx) && h in dohHosts) return "bypass"
        val pk = if (uid >= 0) pkgsOf(ctx, uid) else emptyList()
        if (uid >= 0) {
            val ap = apps(ctx)
            if (ap.isNotEmpty() && pk.any { it in ap }) return "app"
            val bg = bgApps(ctx)
            if (bg.isNotEmpty() && pk.any { it in bg } && ForegroundApp.current(ctx) !in pk) return "background"
        }
        // Facebook/Instagram/WhatsApp... don't break: content and the app's own servers are always allowed
        if (pk.any { Trackers.ownOrContent(h, it) } || Trackers.isContent(h)) return null
        // system apps (uid < 10000) are not touched; for a shared uid, block only
        // if none of its apps owns the domain
        if (trackersOn(ctx) && uid >= 10000 && pk.isNotEmpty() && pk.all { Trackers.blockable(h, it) }) return "tracker"
        FilterLists.match(ctx, h)?.let { return "list:$it" }
        return null
    }

    /** User-facing text for the reason stored in conn.why. */
    fun reasonLabel(why: String?): String = when {
        why == null -> ""
        why == "domain" -> "blocked domain"
        why == "app" -> "app's internet blocked"
        why == "background" -> "background internet blocked"
        why == "bypass" -> "private DNS bypass stopped"
        why == "tracker" -> "third-party tracker"
        why.startsWith("list:") -> FilterLists.nameOf(why.removePrefix("list:")) + " list"
        else -> why
    }

    private fun suffixes(h: String): List<String> {
        val parts = h.split('.')
        return (0 until parts.size - 1).map { parts.subList(it, parts.size).joinToString(".") }
    }

    // ------------------------------------------------------------------ permissions
    /** Ops limited for an alert group; for "personal" etc. only the specific op. */
    fun opsFor(op: String): List<String> = when (Ops.map[op]?.second) {
        "mic" -> listOf("RECORD_AUDIO")
        "cam" -> listOf("CAMERA")
        "loc" -> listOf("FINE_LOCATION", "COARSE_LOCATION")
        else -> listOf(op)
    }

    /** pkg|OP -> previous mode */
    fun limited(ctx: Context): Map<String, String> =
        p(ctx).all.filterKeys { it.startsWith("op:") }.map { (k, v) -> k.removePrefix("op:") to v.toString() }.toMap()

    /** Only these ops have "only while in use" on Android; the rest are allow/deny. */
    private val fgCapable = setOf("mic", "cam", "loc")
    fun limitMode(op: String) = if (Ops.map[op]?.second in fgCapable) "foreground" else "ignore"
    fun limitLabel(op: String, label: String) =
        if (limitMode(op) == "foreground") "$label: allow only while in use"
        else "$label: block completely (app gets an empty list)"

    // Android 17 accepts runtime permissions only at uid level; at package level it silently reverts them
    fun cmdSet(pkg: String, op: String, mode: String) = "appops set --uid $pkg $op $mode"

    sealed class Result(val msg: String) {
        class Done(msg: String) : Result(msg)
        /** No ADB connection: the user does it in Settings or with the command from a computer. */
        class Manual(msg: String, val commands: List<String>) : Result(msg)
    }

    private fun uidMode(ctx: Context, pkg: String, op: String): String? {
        val out = runCatching { Shell.adb(ctx, "appops get --uid $pkg $op") }.getOrDefault("")
        // "Uid mode: OP: ignore" when there is a uid-level limit; otherwise absent (= allow)
        return Regex("""Uid mode: $op: (\w+)""").find(out)?.groupValues?.get(1)
    }

    /**
     * Microphone/camera/location: "only while in use" (foreground). Contacts, calls, SMS,
     * clipboard...: Android has no middle ground, so they are blocked fully (ignore). Verified afterwards.
     */
    fun limit(ctx: Context, pkg: String, op: String): Result {
        val ops = opsFor(op)
        val mode = limitMode(op)
        val cmds = ops.map { cmdSet(pkg, it, mode) }
        if (!Shell.adbConnected(ctx, true)) return Result.Manual(
            "This needs Wireless debugging connected (Setup → Full access, option B). Or turn it off yourself in Settings, " +
                "or run from a computer:", cmds.map { "adb shell $it" })
        val e = p(ctx).edit()
        for (o in ops) {
            val prev = uidMode(ctx, pkg, o) ?: "allow"
            val r = runCatching { Shell.adb(ctx, cmdSet(pkg, o, mode)) }.getOrElse { "error: ${it.message}" }
            Log.i(TAG, "limit $pkg $o -> $mode (was $prev): ${r.trim()}")
            if (r.isNotBlank()) { e.apply(); return Result.Done("Failed: ${r.trim()}") }
            val now = uidMode(ctx, pkg, o)
            if (now != mode) {
                Log.w(TAG, "limit $pkg $o: Android left it at '$now'")
                e.apply()
                return Result.Manual(
                    "Android did not allow this change for this app (it stayed \"${now ?: "allow"}\"), usually because it is a system app. " +
                        "Remove the permission yourself in Settings → Permissions.", emptyList())
            }
            if (!p(ctx).contains("op:$pkg|$o")) e.putString("op:$pkg|$o", prev)
        }
        e.apply()
        return Result.Done(if (mode == "foreground") "Limited: now allowed only while the app is in use."
            else "Blocked: the app now gets an empty list. \"Restore\" under Blocking undoes it.")
    }

    fun restore(ctx: Context, key: String): Result {
        val prev = p(ctx).getString("op:$key", null) ?: return Result.Done("Nothing to restore.")
        val (pkg, op) = key.split('|', limit = 2)
        val cmd = cmdSet(pkg, op, prev)
        if (!Shell.adbConnected(ctx, true)) return Result.Manual("Restoring needs Wireless debugging, or run from a computer:", listOf("adb shell $cmd"))
        val r = runCatching { Shell.adb(ctx, cmd) }.getOrElse { "error: ${it.message}" }
        if (r.isNotBlank()) return Result.Done("Failed: ${r.trim()}")
        p(ctx).edit().remove("op:$key").apply()
        return Result.Done("Restored ($prev).")
    }

    fun openAppSettings(ctx: Context, pkg: String) = runCatching {
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
