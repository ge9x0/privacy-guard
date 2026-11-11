package com.privacyguard.apps

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.privacyguard.collect.Mode
import com.privacyguard.collect.Shell

/**
 * Spyware/stalkerware check: apps with powers that allow full surveillance.
 * Read only; no action is taken automatically.
 */
object Security {
    enum class Power(val label: String, val why: String, val weight: Int) {
        ACCESSIBILITY("Accessibility service", "can read everything on screen and tap for you", 3),
        NOTIFICATIONS("Reads notifications", "sees every message preview and code that arrives", 2),
        ADMIN("Device admin", "can lock or wipe the phone and is harder to uninstall", 3),
        KEYBOARD("Keyboard", "sees everything you type", 2),
        VPN("VPN app", "can see or redirect your internet traffic when on", 1),
        OVERLAY("Draws over other apps", "can show fake screens on top of other apps", 1),
        INSTALL("Installs apps", "can install other apps", 1),
        USAGE("Usage access", "knows which apps you use and when", 1),
        HIDDEN("No app icon", "hidden from the app drawer", 2),
        SIDELOADED("Not from an app store", "installed from a file", 1),
    }
    data class App(val pkg: String, val powers: Set<Power>) {
        val score get() = powers.sumOf { it.weight }
        /** Typical stalkerware signs: hidden or from outside a store, plus surveillance powers. */
        val risky get() = score >= 5 || ((Power.HIDDEN in powers || Power.SIDELOADED in powers) &&
            powers.any { it == Power.ACCESSIBILITY || it == Power.NOTIFICATIONS || it == Power.ADMIN || it == Power.KEYBOARD })
    }
    data class Report(val apps: List<App>, val userCerts: List<String>, val adbOn: Boolean, val full: Boolean)

    private val stores = setOf("com.android.vending", "com.sec.android.app.samsungapps", "com.huawei.appmarket",
        "com.xiaomi.market", "com.xiaomi.mipicks", "com.heytap.market", "com.oppo.market", "com.bbk.appstore", "com.hihonor.appmarket",
        "org.fdroid.fdroid", "org.fdroid.basic", "com.aurora.store", "com.amazon.venezia", "com.looker.droidify", "dev.imranr.obtainium",
        "com.google.android.feedback", "com.android.shell")

    /** The part without Android: merges the powers per app (for tests). */
    fun merge(entries: List<Pair<String, Power>>): List<App> =
        entries.groupBy({ it.first }, { it.second }).map { (p, l) -> App(p, l.toSet()) }
            .sortedWith(compareBy({ !it.risky }, { -it.score }, { it.pkg }))

    fun scan(ctx: Context): Report {
        val pm = ctx.packageManager
        val self = ctx.packageName
        @Suppress("DEPRECATION")
        val apps = pm.getInstalledApplications(0)
        val user = apps.filter { it.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0 }
            .map { it.packageName }.filter { it != self }.toSet()
        val e = ArrayList<Pair<String, Power>>()
        fun add(pkg: String?, p: Power) { if (pkg != null && pkg in user) e += pkg to p }

        runCatching {
            ctx.getSystemService(AccessibilityManager::class.java)
                .getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .forEach { add(it.resolveInfo?.serviceInfo?.packageName, Power.ACCESSIBILITY) }
        }
        val mode = Shell.mode(ctx, tryConnect = false)
        val listeners = runCatching { Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") }.getOrNull()
            ?: Shell.run(ctx, "settings get secure enabled_notification_listeners", mode)
        listeners.orEmpty().split(':').map { it.substringBefore('/').trim() }.forEach { add(it, Power.NOTIFICATIONS) }
        runCatching { ctx.getSystemService(DevicePolicyManager::class.java).activeAdmins?.forEach { add(it.packageName, Power.ADMIN) } }
        runCatching {
            Settings.Secure.getString(ctx.contentResolver, "enabled_input_methods").orEmpty().split(':')
                .forEach { add(it.substringBefore('/'), Power.KEYBOARD) }
        }
        for (p in user) {
            if (pm.getLaunchIntentForPackage(p) == null) add(p, Power.HIDDEN)
            val src = runCatching { pm.getInstallSourceInfo(p).installingPackageName }.getOrNull()
            if (src == null || src !in stores) add(p, Power.SIDELOADED)
        }
        // VPN services: only apps that declare a VpnService
        runCatching {
            pm.queryIntentServices(android.content.Intent("android.net.VpnService"), 0).forEach { add(it.serviceInfo.packageName, Power.VPN) }
        }
        val full = mode != Mode.LIMITED
        if (full) for ((op, pw) in listOf("SYSTEM_ALERT_WINDOW" to Power.OVERLAY, "REQUEST_INSTALL_PACKAGES" to Power.INSTALL, "GET_USAGE_STATS" to Power.USAGE)) {
            Shell.run(ctx, "cmd appops query-op --user 0 $op allow", mode).orEmpty().lines().map { it.trim() }
                .filter { it.matches(Regex("""[A-Za-z0-9_.]+""")) }.forEach { add(it, pw) }
        }
        val certs = runCatching {
            val ks = java.security.KeyStore.getInstance("AndroidCAStore").apply { load(null) }
            ks.aliases().toList().filter { it.startsWith("user:") }.map { a ->
                (ks.getCertificate(a) as? java.security.cert.X509Certificate)?.subjectX500Principal?.name
                    ?.substringAfter("CN=")?.substringBefore(',') ?: a
            }
        }.getOrDefault(emptyList())
        val adb = runCatching { Settings.Global.getInt(ctx.contentResolver, Settings.Global.ADB_ENABLED) == 1 }.getOrDefault(false)
        // SIDELOADED alone is not enough to be shown; another power is needed too
        val merged = merge(e).filter { it.powers.any { p -> p != Power.SIDELOADED } }
        return Report(merged, certs, adb, full)
    }
}
