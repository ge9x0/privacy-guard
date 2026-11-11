package com.privacyguard.block

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.privacyguard.collect.Shell

/**
 * Android's real firewall (kernel chain FIREWALL_CHAIN_OEM_DENY_3): stops ALL
 * traffic of an app, not only DNS, so hardcoded IPs and its own encrypted
 * DNS cannot bypass it. Only the shell (Wireless debugging) can turn it on, and Android
 * clears it on restart: it is applied again on the next ADB connection (Reconnect / monitor every 15 min).
 */
object Firewall {
    private const val TAG = "PG.Firewall"
    private fun p(ctx: Context) = ctx.getSharedPreferences("pg_fw", Context.MODE_PRIVATE)

    /** Apps the firewall blocks right now (as far as we know). Cleared on restart. */
    fun applied(ctx: Context): Set<String> = p(ctx).getStringSet("applied", emptySet())!!.toSet()
    fun lastSync(ctx: Context) = p(ctx).getLong("t", 0L)
    fun onBoot(ctx: Context) = p(ctx).edit().remove("applied").apply()

    /** User apps only; never the system or Privacy Guard itself. */
    private fun eligible(ctx: Context, pkg: String) = pkg != ctx.packageName && runCatching {
        ctx.packageManager.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM == 0
    }.getOrDefault(false)

    fun pending(ctx: Context): Boolean {
        val want = Blocker.apps(ctx).filter { eligible(ctx, it) }.toSet()
        return want != applied(ctx)
    }

    /** Makes the firewall match the "Apps without internet" list. null if there is no ADB connection. */
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
