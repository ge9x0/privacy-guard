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
 * Pairing with Wireless debugging without a computer.
 *
 * The problem: the pairing code disappears if you leave the Settings screen. The solution
 * (like Shizuku): the app finds the port with mDNS and posts a notification where you type
 * the code directly, without leaving Settings.
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
        notify(app, "Open: Settings → Developer options → Wireless debugging → Pair device with pairing code", null)
        discover(app, "_adb-tls-pairing._tcp") { port ->
            notify(app, "Found port $port. Type the 6-digit code from Settings here.", port)
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
            .setContentTitle("Wireless debugging pairing")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
        if (port != null) {
            val ri = RemoteInput.Builder(KEY_CODE).setLabel("Pairing code").build()
            val pi = PendingIntent.getBroadcast(
                ctx, 1,
                Intent(ctx, PairingReceiver::class.java).putExtra(EXTRA_PORT, port),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            b.addAction(Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_launcher), "Enter code", pi).addRemoteInput(ri).build())
        }
        ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, b.build())
    }

    /** Pair + connect + permissions. Returns a message for the user. */
    fun pairAndSetup(ctx: Context, port: Int, code: String): String {
        val m = AdbManager.get(ctx)
        val paired = runCatching { m.pair("127.0.0.1", port, code.trim()) }
            .onFailure { Log.w(TAG, "pair", it) }.getOrDefault(false)
        if (!paired) return "Pairing failed. Check the code and try again."
        stop(ctx)
        if (!Shell.adbConnected(ctx, true)) return "Paired, but connecting failed. Make sure Wireless debugging is on."
        val log = Shell.selfGrant(ctx)
        Log.i(TAG, log)
        return if (Shell.hasDump(ctx) && Shell.mode(ctx, tryConnect = false) == com.privacyguard.collect.Mode.DIRECT)
            "Done! Permanent access granted. You can turn Wireless debugging off now."
        else "Done! The phone refused permanent access, so Wireless debugging will be used."
    }
}

class PairingReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Pairing.KEY_CODE)?.toString() ?: return
        val port = intent.getIntExtra(Pairing.EXTRA_PORT, -1)
        if (port <= 0) return
        val pending = goAsync()
        Pairing.notify(ctx, "Connecting...", null)
        thread {
            val msg = Pairing.pairAndSetup(ctx.applicationContext, port, code)
            Pairing.notify(ctx, msg, null)
            pending.finish()
        }
    }
}
