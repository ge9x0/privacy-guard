package com.privacyguard.block

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.PowerManager

/** Cili app eshte ne ekran tani (nga UsageStats). null kur ekrani eshte i fikur. Thirret nga VPN-ja. */
object ForegroundApp {
    @Volatile private var pkg: String? = null
    @Volatile private var lastQuery = 0L

    @Synchronized
    fun current(ctx: Context): String? {
        if (!ctx.getSystemService(PowerManager::class.java).isInteractive) return null
        val now = System.currentTimeMillis()
        if (now - lastQuery < 1000) return pkg
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        val from = if (lastQuery == 0L) now - 30 * 60_000 else lastQuery - 2000
        runCatching {
            val ev = usm.queryEvents(from, now)
            val e = UsageEvents.Event()
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                // app-i i fundit qe erdhi ne ekran; kur kalon te launcher-i, ai behet "ne ekran"
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) pkg = e.packageName
            }
        }
        lastQuery = now
        return pkg
    }
}
