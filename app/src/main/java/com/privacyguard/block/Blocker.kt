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

    /** Vetem keto op-e kane "vetem kur e perdor" ne Android; te tjerat jane lejo/blloko. */
    private val fgCapable = setOf("mic", "cam", "loc")
    fun limitMode(op: String) = if (Ops.map[op]?.second in fgCapable) "foreground" else "ignore"
    fun limitLabel(op: String, label: String) =
        if (limitMode(op) == "foreground") "$label: lejo vetëm kur e përdor"
        else "$label: blloko plotësisht (app-i merr listë bosh)"

    // Android 17 i pranon vetem ne nivel uid per lejet runtime; ne nivel package i kthen ne heshtje
    fun cmdSet(pkg: String, op: String, mode: String) = "appops set --uid $pkg $op $mode"

    sealed class Result(val msg: String) {
        class Done(msg: String) : Result(msg)
        /** S'ka lidhje ADB: perdoruesi e ben vete te Settings ose me komanden nga kompjuteri. */
        class Manual(msg: String, val commands: List<String>) : Result(msg)
    }

    private fun uidMode(ctx: Context, pkg: String, op: String): String? {
        val out = runCatching { Shell.adb(ctx, "appops get --uid $pkg $op") }.getOrDefault("")
        // "Uid mode: OP: ignore" kur ka kufizim ne nivel uid; perndryshe s'ka (= allow)
        return Regex("""Uid mode: $op: (\w+)""").find(out)?.groupValues?.get(1)
    }

    /**
     * Mikrofoni/kamera/vendndodhja: "vetem kur e perdor" (foreground). Kontaktet, thirrjet, SMS,
     * clipboard...: Android-i s'ka mes, keshtu qe bllokohen plotesisht (ignore). Verifikohet pas.
     */
    fun limit(ctx: Context, pkg: String, op: String): Result {
        val ops = opsFor(op)
        val mode = limitMode(op)
        val cmds = ops.map { cmdSet(pkg, it, mode) }
        if (!Shell.adbConnected(ctx, true)) return Result.Manual(
            "Për këtë duhet Wireless debugging i lidhur (Mënyra B). Ose çaktivizoje vetë te Settings, " +
                "ose ekzekuto nga kompjuteri:", cmds.map { "adb shell $it" })
        val e = p(ctx).edit()
        for (o in ops) {
            val prev = uidMode(ctx, pkg, o) ?: "allow"
            val r = runCatching { Shell.adb(ctx, cmdSet(pkg, o, mode)) }.getOrElse { "gabim: ${it.message}" }
            Log.i(TAG, "limit $pkg $o -> $mode (ishte $prev): ${r.trim()}")
            if (r.isNotBlank()) { e.apply(); return Result.Done("Dështoi: ${r.trim()}") }
            val now = uidMode(ctx, pkg, o)
            if (now != mode) {
                Log.w(TAG, "limit $pkg $o: Android-i e la '$now'")
                e.apply()
                return Result.Manual(
                    "Android-i nuk e lejoi ndryshimin për këtë app (mbeti \"${now ?: "allow"}\"), zakonisht sepse është app sistemi. " +
                        "Hiqe lejen vetë te Settings → Permissions.", emptyList())
            }
            if (!p(ctx).contains("op:$pkg|$o")) e.putString("op:$pkg|$o", prev)
        }
        e.apply()
        return Result.Done(if (mode == "foreground") "U kufizua: tani lejohet vetëm kur app-i është në përdorim."
            else "U bllokua: app-i tani merr listë bosh. \"Rikthe\" te hapi 5 e kthen.")
    }

    fun restore(ctx: Context, key: String): Result {
        val prev = p(ctx).getString("op:$key", null) ?: return Result.Done("S'ka asgjë për të rikthyer.")
        val (pkg, op) = key.split('|', limit = 2)
        val cmd = cmdSet(pkg, op, prev)
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
