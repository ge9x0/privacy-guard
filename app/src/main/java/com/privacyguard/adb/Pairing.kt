package com.privacyguard.adb

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.privacyguard.App
import com.privacyguard.R
import com.privacyguard.collect.Shell
import kotlin.concurrent.thread

/**
 * Ciftimi me Wireless debugging pa kompjuter.
 *
 * Problemi: kodi i ciftimit zhduket nese del nga ekrani i Settings. Zgjidhja
 * (si Shizuku): app-i gjen porten me mDNS dhe nxjerr nje njoftim ku e shkruan
 * kodin direkt, pa lene Settings.
 */
object Pairing {
    private const val TAG = "PG.Pairing"
    const val NOTIF_ID = 42
    const val EXTRA_PORT = "port"
    const val KEY_CODE = "code"
    private var listener: NsdManager.DiscoveryListener? = null
    private val main = Handler(Looper.getMainLooper())

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        notify(app, "Hap: Settings → Developer options → Wireless debugging → Pair device with pairing code", null)
        discover(app, "_adb-tls-pairing._tcp") { port ->
            notify(app, "U gjet porta $port. Shkruaj këtu kodin 6-shifror nga Settings.", port)
        }
        // ndal kerkimin pas 5 minutash
        main.postDelayed({ stop(app) }, 5 * 60_000L)
    }

    fun stop(ctx: Context) {
        val nsd = ctx.getSystemService(NsdManager::class.java)
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
    }

    private fun discover(ctx: Context, type: String, onPort: (Int) -> Unit) {
        stop(ctx)
        val nsd = ctx.getSystemService(NsdManager::class.java)
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { Log.w(TAG, "start failed $errorCode") }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo, errorCode: Int) { Log.w(TAG, "resolve $errorCode") }
                    override fun onServiceResolved(i: NsdServiceInfo) { main.post { onPort(i.port) } }
                })
            }
        }
        listener = l
        nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l)
    }

    fun notify(ctx: Context, text: String, port: Int?) {
        val b = Notification.Builder(ctx, App.CH_SETUP)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Lidhja me Wireless debugging")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
        if (port != null) {
            val ri = RemoteInput.Builder(KEY_CODE).setLabel("Kodi i çiftimit").build()
            val pi = PendingIntent.getBroadcast(
                ctx, 1,
                Intent(ctx, PairingReceiver::class.java).putExtra(EXTRA_PORT, port),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            b.addAction(Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_launcher), "Shkruaj kodin", pi).addRemoteInput(ri).build())
        }
        ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, b.build())
    }

    /** Ciftim + lidhje + leje. Kthen nje mesazh per perdoruesin. */
    fun pairAndSetup(ctx: Context, port: Int, code: String): String {
        val m = AdbManager.get(ctx)
        val paired = runCatching { m.pair("127.0.0.1", port, code.trim()) }
            .onFailure { Log.w(TAG, "pair", it) }.getOrDefault(false)
        if (!paired) return "Çiftimi dështoi. Kontrollo kodin dhe provo përsëri."
        stop(ctx)
        if (!Shell.adbConnected(ctx, true)) return "U çiftua, por lidhja dështoi. Sigurohu që Wireless debugging është ndezur."
        val log = Shell.selfGrant(ctx)
        Log.i(TAG, log)
        return if (Shell.hasDump(ctx) && Shell.mode(ctx, tryConnect = false) == com.privacyguard.collect.Mode.DIRECT)
            "Gati! Leja e përhershme u dha. Tani Wireless debugging mund ta fikësh."
        else "Gati! Samsung-u nuk e pranoi lejen e përhershme, prandaj do përdoret Wireless debugging."
    }
}

class PairingReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Pairing.KEY_CODE)?.toString() ?: return
        val port = intent.getIntExtra(Pairing.EXTRA_PORT, -1)
        if (port <= 0) return
        val pending = goAsync()
        Pairing.notify(ctx, "Po lidhem...", null)
        thread {
            val msg = Pairing.pairAndSetup(ctx.applicationContext, port, code)
            Pairing.notify(ctx, msg, null)
            pending.finish()
        }
    }
}
