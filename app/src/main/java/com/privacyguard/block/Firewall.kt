package com.privacyguard.block

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.privacyguard.collect.Shell

/**
 * Firewall-i i vertete i Android-it (zinxhiri i kernelit FIREWALL_CHAIN_OEM_DENY_3): ndal GJITHE
 * trafikun e nje app-i, jo vetem DNS-ne, keshtu qe IP-te e shkruara ne kod dhe DNS-ja e tij e
 * enkriptuar nuk e anashkalojne. E ndez vetem shell-i (Wireless debugging), dhe Android-i e
 * fshin pas restartimit: rivendoset ne lidhjen e radhes ADB (Reconnect / monitorimi cdo 15 min).
 */
object Firewall {
    private const val TAG = "PG.Firewall"
    private fun p(ctx: Context) = ctx.getSharedPreferences("pg_fw", Context.MODE_PRIVATE)

    /** App-et qe firewall-i i ka bllokuar tani (sipas nesh). Fshihet ne restartim. */
    fun applied(ctx: Context): Set<String> = p(ctx).getStringSet("applied", emptySet())!!.toSet()
    fun lastSync(ctx: Context) = p(ctx).getLong("t", 0L)
    fun onBoot(ctx: Context) = p(ctx).edit().remove("applied").apply()

    /** Vetem app-e te perdoruesit; kurre sistemi ose Privacy Guard vete. */
    private fun eligible(ctx: Context, pkg: String) = pkg != ctx.packageName && runCatching {
        ctx.packageManager.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM == 0
    }.getOrDefault(false)

    fun pending(ctx: Context): Boolean {
        val want = Blocker.apps(ctx).filter { eligible(ctx, it) }.toSet()
        return want != applied(ctx)
    }

    /** Ben qe firewall-i te perputhet me listen "Apps without internet". null nese s'ka lidhje ADB. */
    fun sync(ctx: Context): String? {
        if (!Shell.adbConnected(ctx, true)) return null
        val want = Blocker.apps(ctx).filter { eligible(ctx, it) }.toSet()
        val have = applied(ctx)
        fun run(c: String) = runCatching { Shell.adb(ctx, c) }.getOrElse { "error: ${it.message}" }.trim()
        val now = HashSet<String>()
        if (want.isNotEmpty()) run("cmd connectivity set-chain3-enabled true")
        for (pkg in want) {
            run("cmd connectivity set-package-networking-enabled false $pkg")
            if (run("cmd connectivity get-package-networking-enabled $pkg").endsWith(":deny")) now += pkg
            else Log.w(TAG, "deny failed for $pkg")
        }
        for (pkg in have - want) run("cmd connectivity set-package-networking-enabled true $pkg")
        if (want.isEmpty() && have.isNotEmpty()) run("cmd connectivity set-chain3-enabled false")
        p(ctx).edit().putStringSet("applied", now).putLong("t", System.currentTimeMillis()).apply()
        Log.i(TAG, "sync: ${now.size}/${want.size} apps blocked")
        return if (now.size == want.size) "Firewall: ${now.size} app(s) fully blocked." else "Firewall: blocked ${now.size} of ${want.size} apps."
    }
}
