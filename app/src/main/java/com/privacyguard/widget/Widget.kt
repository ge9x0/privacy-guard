package com.privacyguard.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import com.privacyguard.Prefs
import com.privacyguard.R
import com.privacyguard.core.Analyzer
import com.privacyguard.core.Sev
import com.privacyguard.ui.MainActivity
import com.privacyguard.vpn.DnsVpnService
import java.time.LocalDate

/** Home-screen widget: today's alerts, trackers and VPN state. */
class PrivacyWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val r = goAsync()
        Thread { try { update(ctx) } finally { r.finish() } }.start()
    }

    companion object {
        /** Recomputes and refreshes all widgets (called by collection and the VPN). On a background thread. */
        fun update(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, PrivacyWidget::class.java))
            if (ids.isEmpty()) return
            val d = runCatching { Analyzer(ctx).build(LocalDate.now()) }.getOrNull()
            val v = RemoteViews(ctx.packageName, R.layout.widget)
            if (d != null) {
                val crit = d.cases(Sev.CRIT); val high = d.cases(Sev.HIGH)
                v.setTextViewText(R.id.alerts, when {
                    crit + high == 0 -> "No alerts today"
                    else -> listOfNotNull(crit.takeIf { it > 0 }?.let { "$it critical" }, high.takeIf { it > 0 }?.let { "$it high" }).joinToString(" · ")
                })
                v.setTextColor(R.id.alerts, if (crit > 0) 0xFFFFB4A9.toInt() else if (high > 0) 0xFFFFCC80.toInt() else 0xFFA5D6A7.toInt())
                v.setTextViewText(R.id.line2, "${d.trk} tracker requests · mic ${d.micN}× · camera ${d.camN}×")
            }
            v.setTextViewText(R.id.vpn, if (DnsVpnService.isRunning) "VPN on" else "VPN off")
            v.setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(ctx, 0,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE))
            mgr.updateAppWidget(ids, v)
        }

        fun updateAsync(ctx: Context) = Thread { runCatching { update(ctx.applicationContext) } }.start()
    }
}

/** Quick Settings tile: turns the local VPN on/off. */
class VpnTile : TileService() {
    override fun onStartListening() = refresh()

    private fun refresh(on: Boolean = DnsVpnService.isRunning) {
        val t = qsTile ?: return
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (android.os.Build.VERSION.SDK_INT >= 29) t.subtitle = if (on) "Blocking on" else "Off"
        t.updateTile()
    }

    // Android 14+ uses the PendingIntent overload; the Intent one only runs on older versions.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        if (DnsVpnService.isRunning) {
            DnsVpnService.stop(this); Prefs.setVpn(this, false); refresh(false)
        } else if (VpnService.prepare(this) == null) {
            DnsVpnService.start(this); Prefs.setVpn(this, true); refresh(true)
        } else {
            // VPN permission not granted yet: open the app so the user grants it
            val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (android.os.Build.VERSION.SDK_INT >= 34)
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
            else @Suppress("DEPRECATION") startActivityAndCollapse(i)
        }
        PrivacyWidget.updateAsync(this)
    }
}
