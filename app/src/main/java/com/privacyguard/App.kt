package com.privacyguard

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.privacyguard.monitor.MonitorService
import com.privacyguard.work.Scheduler
import org.conscrypt.Conscrypt

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // TLS for ADB pairing
        runCatching { java.security.Security.insertProviderAt(Conscrypt.newProvider(), 1) }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(listOf(
            NotificationChannel(CH_MONITOR, "Monitoring", NotificationManager.IMPORTANCE_MIN),
            NotificationChannel(CH_ALERTS, "Alerts", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CH_REPORTS, "Daily reports", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CH_SETUP, "Setup", NotificationManager.IMPORTANCE_HIGH),
        ))
        Scheduler.schedule(this)
        if (Prefs.monitor(this)) runCatching { MonitorService.start(this) }
    }

    companion object {
        const val CH_MONITOR = "monitor"
        const val CH_ALERTS = "alerts"
        const val CH_REPORTS = "reports"
        const val CH_SETUP = "setup"
        const val NOTIF_MONITOR = 1
        const val NOTIF_REPORT = 2
    }
}

object Prefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("pg", Context.MODE_PRIVATE)
    fun monitor(ctx: Context) = p(ctx).getBoolean("monitor", true)
    fun setMonitor(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("monitor", v).apply()
    fun vpn(ctx: Context) = p(ctx).getBoolean("vpn", false)
    fun setVpn(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("vpn", v).apply()
    /** "Clear history": nothing before this moment is collected again (Android itself keeps 7 days). */
    fun clipAlerts(ctx: Context) = p(ctx).getBoolean("clip", true)
    fun setClipAlerts(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("clip", v).apply()
    fun since(ctx: Context) = p(ctx).getLong("since", 0L)
    fun setSince(ctx: Context, v: Long) = p(ctx).edit().putLong("since", v).apply()
}

object History {
    /** Deletes all collected history; permissions, the ADB connection and blocks stay. */
    fun clear(ctx: Context) {
        Prefs.setSince(ctx, System.currentTimeMillis())
        com.privacyguard.data.Db.get(ctx).clearAll()
        ctx.filesDir.listFiles { f -> f.name.startsWith("raw_") }?.forEach { it.delete() }
    }
}

/** After a restart: monitoring restarts by itself (App.onCreate). The VPN restarts if it was on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Android clears the kernel firewall on restart
        com.privacyguard.block.Firewall.onBoot(ctx)
        if (Prefs.vpn(ctx) && android.net.VpnService.prepare(ctx) == null) {
            com.privacyguard.vpn.DnsVpnService.start(ctx)
        }
    }
}
