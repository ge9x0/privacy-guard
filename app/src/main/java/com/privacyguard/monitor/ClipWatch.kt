package com.privacyguard.monitor

import android.content.Context
import android.content.pm.ApplicationInfo
import com.privacyguard.data.AccessEvent
import com.privacyguard.core.Ops

/**
 * Who read the clipboard (the copied text) while not on screen. Android 10+ only allows
 * the app on screen and the keyboard, so a background read is rare and worth knowing.
 * Source: `dumpsys appops --op READ_CLIPBOARD`, read every 30 s (5 min with the screen off).
 */
object ClipWatch {
    data class Hit(val e: AccessEvent, val refused: Boolean)

    /** New reads (after `since`) while off screen, excluding the system, the keyboard and our app. */
    fun newHits(events: List<AccessEvent>, since: Long, ignore: (String) -> Boolean): List<Hit> =
        events.asSequence()
            .filter { it.op == "READ_CLIPBOARD" && it.ts > since && offScreen(it.state) && !ignore(it.pkg) }
            .distinctBy { Triple(it.pkg, it.ts, it.kind) }
            .map { Hit(it, it.kind == "reject") }
            .sortedBy { it.e.ts }
            .toList()

    /** "top" = on screen, "pers" = system; any other state (bg, cch, fg/fgsvc service) is off screen. */
    fun offScreen(state: String) = state in Ops.background || state in Ops.fgService

    fun ignorer(ctx: Context): (String) -> Boolean {
        val ime = runCatching {
            android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')
        }.getOrNull()
        val cache = HashMap<String, Boolean>()
        return { pkg ->
            cache.getOrPut(pkg) {
                pkg == ctx.packageName || pkg == ime || pkg in Ops.knownSystem || runCatching {
                    ctx.packageManager.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0
                }.getOrDefault(true)
            }
        }
    }
}
