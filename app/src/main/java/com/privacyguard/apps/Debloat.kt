package com.privacyguard.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.privacyguard.block.Blocker
import com.privacyguard.collect.Shell
import org.json.JSONArray

/**
 * Debloater: heq app-e te sistemit vetem per perdoruesin 0 (`pm uninstall -k --user 0`).
 * APK-ja mbetet ne /system, prandaj cdo heqje kthehet me `cmd package install-existing`.
 * Lista: android-debloat-list (AGPL-3.0), ne assets/debloat.
 */
object Debloat {
    data class Entry(
        val id: String, val label: String?, val desc: String, val removal: String, val warning: String?,
        val deps: List<String>, val requiredBy: List<String>, val list: String,
    ) {
        /** delete/replace jane te sigurta; caution/unsafe kerkojne paralajmerim. */
        val safe get() = removal == "delete" || removal == "replace"
    }
    data class Item(val e: Entry, val name: String, val removed: Boolean)

    /** Kurre: pa keto humbet ADB-ja, ekrani ose vete Privacy Guard. */
    private val never = setOf("android", "com.android.shell", "com.android.systemui", "com.android.settings",
        "com.android.phone", "com.android.providers.settings", "com.android.packageinstaller",
        "com.google.android.packageinstaller", "com.android.permissioncontroller",
        "com.google.android.permissioncontroller", Shell.SELF)

    @Volatile private var cache: List<Entry>? = null

    fun entries(ctx: Context): List<Entry> = cache ?: run {
        val out = ArrayList<Entry>()
        for (f in listOf("oem", "google", "aosp", "carrier", "misc")) runCatching {
            val a = JSONArray(ctx.assets.open("debloat/$f.json").bufferedReader().use { it.readText() })
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                fun strs(k: String) = o.optJSONArray(k)?.let { j -> List(j.length()) { j.optString(it) } } ?: emptyList()
                out += Entry(o.getString("id"), o.optString("label").ifBlank { null }, o.optString("description").trim(),
                    o.optString("removal", "caution"), o.optString("warning").ifBlank { null }, strs("dependencies"), strs("required_by"), f)
            }
        }
        out.distinctBy { it.id }.also { cache = it }
    }

    /** Vetem paketat qe ekzistojne ne kete telefon (te instaluara ose te hequra per user 0). */
    fun items(ctx: Context): List<Item> {
        val pm = ctx.packageManager
        val self = setOf(defaultLauncher(ctx), defaultIme(ctx))
        return entries(ctx).mapNotNull { e ->
            if (e.id in never || e.id in self) return@mapNotNull null
            val ai = runCatching { pm.getApplicationInfo(e.id, PackageManager.MATCH_UNINSTALLED_PACKAGES) }.getOrNull() ?: return@mapNotNull null
            val removed = ai.flags and ApplicationInfo.FLAG_INSTALLED == 0
            Item(e, e.label ?: runCatching { ai.loadLabel(pm).toString() }.getOrDefault(e.id), removed)
        }.sortedWith(compareBy({ !it.removed }, { order(it.e.removal) }, { it.name.lowercase() }))
    }

    private fun order(r: String) = when (r) { "delete" -> 0; "replace" -> 1; "caution" -> 2; else -> 3 }

    private fun defaultLauncher(ctx: Context) = runCatching {
        ctx.packageManager.resolveActivity(android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
    }.getOrNull()

    private fun defaultIme(ctx: Context) = runCatching {
        android.provider.Settings.Secure.getString(ctx.contentResolver, "default_input_method")?.substringBefore('/')
    }.getOrNull()

    fun removeCmd(pkg: String) = "pm uninstall -k --user 0 $pkg"
    fun restoreCmd(pkg: String) = "cmd package install-existing --user 0 $pkg"

    fun remove(ctx: Context, pkg: String) = exec(ctx, removeCmd(pkg), "Removed for your user. The app's files stay on the phone; tap Restore any time.",
        "Removing a system app needs Wireless debugging connected (Shell access). Or run from a computer:") { it.contains("Success") }

    fun restore(ctx: Context, pkg: String) = exec(ctx, restoreCmd(pkg), "Restored.",
        "Restoring needs Wireless debugging connected. Or run from a computer:") { it.contains("installed for user", true) }

    private fun exec(ctx: Context, cmd: String, ok: String, manual: String, success: (String) -> Boolean): Blocker.Result {
        require(cmd.substringAfterLast(' ') !in never)
        if (!Shell.adbConnected(ctx, true)) return Blocker.Result.Manual(manual, listOf("adb shell $cmd"))
        val r = runCatching { Shell.adb(ctx, cmd) }.getOrElse { "error: ${it.message}" }.trim()
        return Blocker.Result.Done(if (success(r)) ok else "Failed: $r")
    }
}
