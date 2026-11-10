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
 * Bllokimi, ne dy shtresa:
 *
 *  1. DNS (perdor VPN-ne lokale): trackerat e paleve te treta, domene te zgjedhura,
 *     ose gjithe interneti i nje app-i -> pergjigje NXDOMAIN. Punon ne cdo menyre.
 *  2. Lejet (app-ops): "lejo vetem kur e perdor" per mikrofonin, kameren, kontaktet...
 *     Kerkon lidhjen ADB (Wireless debugging); perndryshe hapet faqja e app-it te Settings.
 *     Menyra e meparshme ruhet, qe "Rikthe" ta ktheje ashtu si ishte.
 */
object Blocker {
    private const val TAG = "PG.Block"
    private fun p(ctx: Context) = ctx.getSharedPreferences("pg_block", Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ DNS
    fun trackersOn(ctx: Context) = p(ctx).getBoolean("trackers", false)
    fun setTrackers(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("trackers", v).apply()

    fun apps(ctx: Context): Set<String> = p(ctx).getStringSet("apps", emptySet())!!.toSet()
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

    /** Arsyeja e bllokimit, ose null nese kerkesa lejohet. Thirret per cdo kerkese DNS. */
    fun dnsVerdict(ctx: Context, uid: Int, host: String): String? {
        val h = host.lowercase().trimEnd('.')
        val hs = hosts(ctx)
        if (hs.isNotEmpty() && suffixes(h).any { it in hs }) return "domen"
        if (uid < 0) return null                      // pa app te njohur: vetem domenet e zgjedhura
        val pk = pkgsOf(ctx, uid)
        val ap = apps(ctx)
        if (ap.isNotEmpty() && pk.any { it in ap }) return "app"
        // app-et e sistemit (uid < 10000) nuk preken; per uid te ndare, bllokohet vetem
        // nese asnjeri nga app-et e tij nuk e ka domenin si te vetin
        if (trackersOn(ctx) && uid >= 10000 && pk.isNotEmpty() && pk.all { Trackers.blockable(h, it) }) return "tracker"
        return null
    }

    private fun suffixes(h: String): List<String> {
        val parts = h.split('.')
        return (0 until parts.size - 1).map { parts.subList(it, parts.size).joinToString(".") }
    }

    // ------------------------------------------------------------------ lejet
    /** Op-et qe kufizohen per nje grup sinjalizimi; per "personal" etj. vetem op-i konkret. */
    fun opsFor(op: String): List<String> = when (Ops.map[op]?.second) {
        "mic" -> listOf("RECORD_AUDIO")
        "cam" -> listOf("CAMERA")
        "loc" -> listOf("FINE_LOCATION", "COARSE_LOCATION")
        else -> listOf(op)
    }

    /** pkg|OP -> menyra e meparshme */
    fun limited(ctx: Context): Map<String, String> =
        p(ctx).all.filterKeys { it.startsWith("op:") }.map { (k, v) -> k.removePrefix("op:") to v.toString() }.toMap()

    fun cmdLimit(pkg: String, op: String) = "appops set $pkg $op foreground"

    sealed class Result(val msg: String) {
        class Done(msg: String) : Result(msg)
        /** S'ka lidhje ADB: perdoruesi e ben vete te Settings ose me komanden nga kompjuteri. */
        class Manual(msg: String, val commands: List<String>) : Result(msg)
    }

    /**
     * "Lejo vetem kur e perdor": app-i e merr mikrofonin/kameren/kontaktet vetem kur eshte ne ekran
     * ose ka njoftim te dukshem; ne background merr "refuzim".
     */
    fun limit(ctx: Context, pkg: String, op: String): Result {
        val ops = opsFor(op)
        val cmds = ops.map { cmdLimit(pkg, it) }
        if (!Shell.adbConnected(ctx, true)) return Result.Manual(
            "Për këtë duhet Wireless debugging i lidhur (Mënyra B). Ose çaktivizoje vetë te Settings, " +
                "ose ekzekuto nga kompjuteri:", cmds.map { "adb shell $it" })
        val e = p(ctx).edit()
        for (o in ops) {
            val before = runCatching { Shell.adb(ctx, "appops get $pkg $o") }.getOrDefault("")
            val prev = Regex("""$o: (\w+)""").find(before)?.groupValues?.get(1) ?: "default"
            val r = runCatching { Shell.adb(ctx, cmdLimit(pkg, o)) }.getOrElse { "gabim: ${it.message}" }
            Log.i(TAG, "limit $pkg $o (ishte $prev): ${r.trim()}")
            if (r.isNotBlank()) return Result.Done("Dështoi: ${r.trim()}")
            if (!p(ctx).contains("op:$pkg|$o")) e.putString("op:$pkg|$o", prev)
        }
        e.apply()
        return Result.Done("U kufizua: tani lejohet vetëm kur app-i është në përdorim.")
    }

    fun restore(ctx: Context, key: String): Result {
        val prev = p(ctx).getString("op:$key", null) ?: return Result.Done("S'ka asgjë për të rikthyer.")
        val (pkg, op) = key.split('|', limit = 2)
        val cmd = "appops set $pkg $op $prev"
        if (!Shell.adbConnected(ctx, true)) return Result.Manual("Për rikthim duhet Wireless debugging, ose nga kompjuteri:", listOf("adb shell $cmd"))
        val r = runCatching { Shell.adb(ctx, cmd) }.getOrElse { "gabim: ${it.message}" }
        if (r.isNotBlank()) return Result.Done("Dështoi: ${r.trim()}")
        p(ctx).edit().remove("op:$key").apply()
        return Result.Done("U rikthye ($prev).")
    }

    fun openAppSettings(ctx: Context, pkg: String) = runCatching {
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
