package com.privacyguard.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.privacyguard.History

/** Debug only: same as the "Clear history" button.  adb shell am broadcast -n com.privacyguard/.debug.ClearHistoryReceiver */
class ClearHistoryReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        History.clear(ctx)
        Log.i("PG.Time", "history cleared")
    }
}
