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

    /** Mbledhje e plote: historiku i qasjeve (nese kemi menyre te plote) + statistikat e rrjetit. */
    fun snapshot(ctx: Context): CollectResult {
        val db = Db.get(ctx)
        val mode = Shell.mode(ctx)
        var read = 0
        var new = 0
        var note: String? = null
        if (mode != Mode.LIMITED) {
            var txt = Shell.run(ctx, "dumpsys appops --include-discrete 100000", mode).orEmpty()
            if (txt.length < 200 || txt.take(500).contains("Unknown option")) {
                txt = Shell.run(ctx, "dumpsys appops", mode).orEmpty()
            }
            val evs = AppOpsParser.parse(txt)
            read = evs.size
            new = db.addAccess(evs)
            if (!txt.contains("iscrete")) note = "Ky Android nuk dha historik të plotë; u ruajt vetëm qasja e fundit për çdo app."
            if (txt.isNotEmpty()) RawStore.save(ctx, "appops", txt)
        }
        val net = if (hasUsageAccess(ctx)) netStats(ctx, 7) else emptyList()
        db.addNet(net)
        db.prune()
        Log.i(TAG, "mode=$mode read=$read new=$new net=${net.size}")
        return CollectResult(mode, read, new, net.size, note)
    }

    /** Sa bajte dergoi/mori cdo app cdo dite, te ndara ne plan te pare / background. */
    fun netStats(ctx: Context, days: Int): List<NetRow> {
        val nsm = ctx.getSystemService(NetworkStatsManager::class.java)
        val z = ZoneId.systemDefault()
        val out = ArrayList<NetRow>()
        val today = LocalDate.now()
        for (i in 0 until days) {
            val d = today.minusDays(i.toLong())
            val from = d.atStartOfDay(z).toInstant().toEpochMilli()
            val to = d.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli()
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
                for ((k, v) in agg) out += NetRow(k.first, name, k.second, from, v[0], v[1])
            }
        }
        return out
    }
}

/** Ruan tekstin origjinal te fundit, per diagnostikim ("Eksporto te dhenat raw"). */
object RawStore {
    fun save(ctx: Context, name: String, text: String) {
        runCatching { java.io.File(ctx.filesDir, "raw_$name.txt").writeText(text) }
    }
    fun file(ctx: Context, name: String) = java.io.File(ctx.filesDir, "raw_$name.txt")
}
