package com.privacyguard.apps

import android.content.Context
import com.privacyguard.core.Ops
import com.privacyguard.core.Trackers
import com.privacyguard.data.Db
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One app's 30-day history: sensitive access and tracker contacts, day by day. */
object AppHistory {
    data class Day(val date: LocalDate, val access: Int, val trackers: Int)
    data class H(val days: List<Day>, val byWhat: List<Pair<String, Int>>, val companies: List<Pair<String, Int>>)

    /** The part without Android: counts per day. The same access within one minute counts once. */
    fun build(today: LocalDate, n: Int, zone: ZoneId, access: List<Pair<Long, String>>, conns: List<Pair<Long, String>>,
              label: (String) -> String?, tracker: (String) -> String?): H {
        fun day(ts: Long) = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()
        val acc = access.filter { label(it.second) != null }.distinctBy { it.second to it.first / 60_000 }
        val trk = conns.mapNotNull { (ts, h) -> tracker(h)?.let { ts to it } }
        val accBy = acc.groupingBy { day(it.first) }.eachCount()
        val trkBy = trk.groupingBy { day(it.first) }.eachCount()
        val days = (n - 1 downTo 0).map { today.minusDays(it.toLong()) }.map { Day(it, accBy[it] ?: 0, trkBy[it] ?: 0) }
        return H(days,
            acc.groupingBy { label(it.second)!! }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value },
            trk.groupingBy { it.second }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value })
    }

    fun of(ctx: Context, pkg: String, n: Int = 30): H {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val from = today.minusDays((n - 1).toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        val db = Db.get(ctx)
        val uid = runCatching { ctx.packageManager.getApplicationInfo(pkg, 0).uid }.getOrDefault(-1)
        return build(today, n, zone, db.accessOf(pkg, from), if (uid > 0) db.connOf(uid, from) else emptyList(),
            { Ops.map[it]?.first }, { Trackers.classify(it)?.company })
    }
}
