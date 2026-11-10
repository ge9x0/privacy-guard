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
                Collector.snapshot(ctx); lap("snapshot total")
                Shell.mode(ctx, tryConnect = true); lap("mode")
                Analyzer(ctx).build(LocalDate.now()); lap("analyzer")
            } finally { pending.finish() }
        }
    }
}
