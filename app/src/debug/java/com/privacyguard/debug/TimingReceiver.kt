package com.privacyguard.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.privacyguard.collect.Collector
import com.privacyguard.collect.Shell
import com.privacyguard.core.Analyzer
import java.time.LocalDate
import kotlin.concurrent.thread

/**
 * Debug only. Runs the same steps as the Refresh button and logs how long each one takes.
 *   adb shell am broadcast -n com.privacyguard/.debug.TimingReceiver
 */
class TimingReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pending = goAsync()
        thread {
            try {
                var t = System.currentTimeMillis()
                fun lap(n: String) { val now = System.currentTimeMillis(); Log.i("PG.Time", "$n ${now - t} ms"); t = now }
                intent.getStringExtra("deep")?.let { list ->
                    for (pkg in list.split(',')) {
                        val pi = com.privacyguard.apps.UserApps.info(ctx, pkg) ?: continue
                        val m = com.privacyguard.apps.SdkScanner.scan(pi).size
                        val d = com.privacyguard.apps.SdkScanner.deepScan(ctx, pi)
                        Log.i("PG.Time", "deep $pkg manifest=$m deep=${d.size}: ${d.joinToString { it.name }}"); lap("deep $pkg")
                    }
                    return@thread
                }
                if (intent.getBooleanExtra("lists", false)) {
                    Log.i("PG.Time", com.privacyguard.block.FilterLists.update(ctx, force = true)); lap("lists update")
                    for (h in listOf("t.appsflyersdk.com", "graph.facebook.com", "i.instagram.com", "googleads.g.doubleclick.net", "www.google.com"))
                        Log.i("PG.Time", "verdict uid=10319(instagram?) $h -> ${com.privacyguard.block.Blocker.dnsVerdict(ctx, 10319, h)}")
                    lap("verdicts")
                    com.privacyguard.apps.UserApps.list(ctx).let { l -> Log.i("PG.Time", "sdk scan ${l.size} apps, with sdks: " +
                        l.count { com.privacyguard.apps.SdkScanner.of(ctx, it).isNotEmpty() }) }; lap("sdk scan")
                    Log.i("PG.Time", "advice ${com.privacyguard.apps.PermissionAdvisor.advise(ctx).size}"); lap("advisor")
                    Log.i("PG.Time", "scores " + com.privacyguard.apps.Score.compute(ctx).take(5).joinToString { "${it.pkg}=${it.score}" }); lap("scores")
                }
                Collector.snapshot(ctx); lap("snapshot total")
                Shell.mode(ctx, tryConnect = true); lap("mode")
                val d = Analyzer(ctx).build(LocalDate.now()); lap("analyzer")
                d.alerts.filter { it.sev == com.privacyguard.core.Sev.HIGH }.groupingBy { "${it.e.pkg} ${it.e.op} ${it.e.state}" }.eachCount()
                    .forEach { (k, n) -> Log.i("PG.Time", "HIGH $n $k") }
            } finally { pending.finish() }
        }
    }
}
