package com.privacyguard.monitor

import android.content.Context
import android.content.pm.ApplicationInfo
import com.privacyguard.data.AccessEvent
import com.privacyguard.core.Ops

/**
 * Kush lexoi clipboard-in (tekstin e kopjuar) pa qene ne ekran. Android 10+ e lejon vetem
 * app-in ne ekran dhe tastieren; nje lexim "bg" eshte pra i rralle dhe vlen te dihet.
 * Burimi: `dumpsys appops --op READ_CLIPBOARD`, i lexuar cdo 20 s (60 s me ekran te fikur).
 */
object ClipWatch {
    data class Hit(val e: AccessEvent, val refused: Boolean)

    /** Leximet e reja (pas `since`) jashte ekranit, pa sistemin, tastieren dhe app-in tone. */
    fun newHits(events: List<AccessEvent>, since: Long, ignore: (String) -> Boolean): List<Hit> =
        events.asSequence()
            .filter { it.op == "READ_CLIPBOARD" && it.ts > since && offScreen(it.state) && !ignore(it.pkg) }
            .distinctBy { Triple(it.pkg, it.ts, it.kind) }
            .map { Hit(it, it.kind == "reject") }
            .sortedBy { it.e.ts }
            .toList()

    /** "top" = ne ekran, "pers" = sistemi; cdo gjendje tjeter (bg, cch, fg/fgsvc sherbim) eshte jashte ekranit. */
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
