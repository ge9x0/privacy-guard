package com.privacyguard.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.util.Log
import com.privacyguard.core.AppNames

/** When an app updates: which new permissions or tracking SDKs it brought. */
object AppChanges {
    data class Snap(val version: String, val groups: Set<String>, val sdks: Set<String>, val deep: Boolean)
    data class Change(val ts: Long, val pkg: String, val version: String, val groups: List<String>, val sdks: List<String>)

    private fun p(ctx: Context) = ctx.getSharedPreferences("pg_snap", Context.MODE_PRIVATE)
    private fun log(ctx: Context) = ctx.getSharedPreferences("pg_changes", Context.MODE_PRIVATE)

    fun encode(s: Snap) = listOf(s.version, s.groups.joinToString(","), s.sdks.joinToString(","), if (s.deep) "1" else "0").joinToString("|")
    fun decode(v: String): Snap? = v.split('|').takeIf { it.size == 4 }?.let { f ->
        fun set(x: String) = x.split(',').filter { it.isNotBlank() }.toSet()
        Snap(f[0], set(f[1]), set(f[2]), f[3] == "1")
    }

    /** What is new in `new` compared to `old`. SDKs are compared only when both come from the deep scan. */
    fun diff(old: Snap, new: Snap): Pair<List<String>, List<String>> =
        (new.groups - old.groups).sorted() to (if (old.deep && new.deep) (new.sdks - old.sdks).sorted() else emptyList())

    fun snapOf(ctx: Context, pi: PackageInfo, deep: Boolean): Snap {
        val req = pi.requestedPermissions.orEmpty().toSet()
        val groups = PermGroups.all.filter { g -> g.perms.any { it in req } }.map { it.label }.toSet()
        return Snap(pi.versionName ?: pi.longVersionCode.toString(), groups, SdkScanner.of(ctx, pi, deep).map { it.name }.toSet(), deep)
    }

    fun save(ctx: Context, pkg: String, s: Snap) = p(ctx).edit().putString(pkg, encode(s)).apply()

    /** Called by MonitorService after an update (ACTION_PACKAGE_ADDED with EXTRA_REPLACING). */
    fun onUpdated(ctx: Context, pkg: String) {
        UserApps.invalidate()
        val pi = UserApps.info(ctx, pkg) ?: return
        if (pi.applicationInfo?.let { it.flags and ApplicationInfo.FLAG_SYSTEM != 0 } == true) return
        val old = p(ctx).getString(pkg, null)?.let(::decode)
        val new = snapOf(ctx, pi, deep = true)
        save(ctx, pkg, new)
        if (old == null) return
        val (g, s) = diff(old, new)
        Log.i("PG.Apps", "updated $pkg ${old.version}->${new.version} groups=$g sdks=$s")
        if (g.isEmpty() && s.isEmpty()) return
        val c = Change(System.currentTimeMillis(), pkg, new.version, g, s)
        log(ctx).edit().putString("${c.ts}|$pkg", "${c.version}|${g.joinToString(",")}|${s.joinToString(",")}").apply()
        val name = AppNames(ctx).name(pkg)
        val text = listOfNotNull(
            g.takeIf { it.isNotEmpty() }?.let { "Now asks for: ${it.joinToString()}." },
            s.takeIf { it.isNotEmpty() }?.let { "New tracker SDKs: ${it.joinToString()}." },
        ).joinToString(" ")
        Insights.post(ctx, 3500 + (pkg.hashCode() and 0xfff), "$name updated", text)
    }

    /** Baseline for apps without a snapshot (uses the saved scan, doesn't re-read the code). */
    fun baseline(ctx: Context) {
        val have = p(ctx).all.keys
        for (pi in UserApps.list(ctx)) if (pi.packageName !in have) runCatching {
            save(ctx, pi.packageName, snapOf(ctx, pi, deep = SdkScanner.isDeep(ctx, pi.packageName)))
        }
    }

    fun recent(ctx: Context, days: Int = 30): List<Change> {
        val cut = System.currentTimeMillis() - days * 86_400_000L
        return log(ctx).all.mapNotNull { (k, v) ->
            val ts = k.substringBefore('|').toLongOrNull() ?: return@mapNotNull null
            if (ts < cut) return@mapNotNull null
            val f = v.toString().split('|')
            Change(ts, k.substringAfter('|'), f.getOrElse(0) { "" }, f.getOrElse(1) { "" }.split(',').filter { it.isNotBlank() },
                f.getOrElse(2) { "" }.split(',').filter { it.isNotBlank() })
        }.sortedByDescending { it.ts }
    }
}
