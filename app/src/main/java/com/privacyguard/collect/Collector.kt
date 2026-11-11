package com.privacyguard.collect

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Process
import android.util.Log
import com.privacyguard.core.AppOpsParser
import com.privacyguard.data.Db
import com.privacyguard.data.NetRow
import java.time.LocalDate
import java.time.ZoneId

data class CollectResult(val mode: Mode, val accessRead: Int, val accessNew: Int, val netRows: Int, val note: String?)

object Collector {
    private const val TAG = "PG.Collect"

    fun hasUsageAccess(ctx: Context): Boolean {
        val aom = ctx.getSystemService(AppOpsManager::class.java)
        return aom.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }

    /** Full collection: access history (if we have a full level) + network statistics. */
    fun snapshot(ctx: Context): CollectResult {
        val db = Db.get(ctx)
        val mode = Shell.mode(ctx)
        var read = 0
        var new = 0
        var note: String? = null
        var t0 = System.currentTimeMillis()
        fun lap(n: String) { val now = System.currentTimeMillis(); Log.d(TAG, "time $n ${now - t0} ms"); t0 = now }
        lap("mode")
        if (mode != Mode.LIMITED) {
            var txt = Shell.run(ctx, "dumpsys appops --include-discrete 100000", mode).orEmpty()
            if (txt.length < 200 || txt.take(500).contains("Unknown option")) {
                txt = Shell.run(ctx, "dumpsys appops", mode).orEmpty()
            }
            lap("dumpsys")
            val evs = AppOpsParser.parse(txt)
            lap("parse")
            read = evs.size
            // prune() deletes anything older than 60 days anyway; don't write it at all
            val cut = maxOf(System.currentTimeMillis() - 60 * 86_400_000L, com.privacyguard.Prefs.since(ctx))
            new = db.addAccess(evs.filter { it.ts >= cut })
            lap("db access")
            if (!txt.contains("iscrete")) note = "This Android version gave no detailed history; only the last access per app was saved."
            if (txt.isNotEmpty()) RawStore.save(ctx, "appops", txt)
            lap("raw save")
        }
        val net = if (hasUsageAccess(ctx)) netStats(ctx, 7) else emptyList()
        lap("netstats")
        db.addNet(net)
        db.prune()
        lap("db net+prune")
        Log.i(TAG, "mode=$mode read=$read new=$new net=${net.size}")
        return CollectResult(mode, read, new, net.size, note)
    }

    /** How many bytes each app sent/received per day, split into foreground / background. */
    fun netStats(ctx: Context, days: Int): List<NetRow> {
        val nsm = ctx.getSystemService(NetworkStatsManager::class.java)
        val z = ZoneId.systemDefault()
        val out = ArrayList<NetRow>()
        val today = LocalDate.now()
        val since = com.privacyguard.Prefs.since(ctx)
        for (i in 0 until days) {
            val d = today.minusDays(i.toLong())
            val dayStart = d.atStartOfDay(z).toInstant().toEpochMilli()
            val to = d.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli()
            if (to <= since) continue
            val from = maxOf(dayStart, since)
            for ((type, name) in listOf(ConnectivityManager.TYPE_WIFI to "WIFI", ConnectivityManager.TYPE_MOBILE to "MOBILE")) {
                val agg = HashMap<Pair<Int, String>, LongArray>()
                runCatching {
                    nsm.querySummary(type, null, from, to).use { stats ->
                        val b = NetworkStats.Bucket()
                        while (stats.hasNextBucket()) {
                            stats.getNextBucket(b)
                            val set = if (b.state == NetworkStats.Bucket.STATE_FOREGROUND) "FOREGROUND" else "DEFAULT"
                            val a = agg.getOrPut(b.uid to set) { LongArray(2) }
                            a[0] += b.rxBytes; a[1] += b.txBytes
                        }
                    }
                }.onFailure { Log.w(TAG, "netstats $name", it) }
                for ((k, v) in agg) out += NetRow(k.first, name, k.second, dayStart, v[0], v[1])
            }
        }
        return out
    }
}

/** Keeps the last raw text, for diagnostics ("Export raw data"). */
object RawStore {
    fun save(ctx: Context, name: String, text: String) {
        runCatching { java.io.File(ctx.filesDir, "raw_$name.txt").writeText(text) }
    }
    fun file(ctx: Context, name: String) = java.io.File(ctx.filesDir, "raw_$name.txt")
}
