package com.privacyguard.block

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.PowerManager

/** Which app is on screen now (from UsageStats). null when the screen is off. Called by the VPN. */
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
                // the last app that came on screen; when going to the launcher, that becomes "on screen"
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) pkg = e.packageName
            }
        }
        lastQuery = now
        return pkg
    }
}
