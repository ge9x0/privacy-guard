package com.privacyguard.monitor

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.privacyguard.App
import com.privacyguard.R
import com.privacyguard.collect.LiveAttribution
import com.privacyguard.collect.Mode
import com.privacyguard.collect.Shell
import com.privacyguard.core.AppNames
import com.privacyguard.core.Ops
import com.privacyguard.data.AccessEvent
import com.privacyguard.data.Db
import com.privacyguard.data.ScreenRow
import com.privacyguard.ui.MainActivity

/**
 * Sherbimi qe punon gjithe kohen:
 *  - shenon kur ekrani eshte ndezur/fikur (Android-i s'e ruan vete)
 *  - degjon kur mikrofoni/kamera ndizen e fiken (punon edhe pa leje te vecanta)
 *  - ne menyrat e plota gjen menjehere cili app i perdor
 *  - njofton menjehere nese ndodh me ekran te fikur
 */
class MonitorService : Service() {
    private lateinit var db: Db
    private lateinit var names: AppNames
    private lateinit var worker: Handler
    private lateinit var thread: HandlerThread
    private var mode: Mode = Mode.LIMITED

    private var micStart = 0L
    private var micPkgs: Set<String> = emptySet()
    private var micScreenOn = true
    private val camStart = HashMap<String, Long>()
    private var camPkgs: Set<String> = emptySet()
    private var camScreenOn = true

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { worker.post { sampleScreen() } }
    }

    private var clipSince = 0L
    private val clipNotified = HashMap<String, Long>()

    /** Clipboard: lexo op-in READ_CLIPBOARD dhe njofto per leximet jashte ekranit. */
    private val clipWatch = object : Runnable {
        override fun run() {
            val on = getSystemService(android.os.PowerManager::class.java).isInteractive
            if (com.privacyguard.Prefs.clipAlerts(this@MonitorService) && mode != Mode.LIMITED) runCatching { checkClipboard() }
                .onFailure { Log.w(TAG, "clip", it) }
            worker.postDelayed(this, if (on) 20_000L else 60_000L)
        }
    }

    private fun checkClipboard() {
        val out = Shell.run(this, "dumpsys appops --op READ_CLIPBOARD", mode) ?: return
        val evs = com.privacyguard.core.AppOpsParser.parse(out).filter { it.op == "READ_CLIPBOARD" }
        val now = System.currentTimeMillis()
        if (clipSince == 0L) { clipSince = now; return }          // hera e pare: mos njofto historine
        val hits = ClipWatch.newHits(evs, clipSince, ClipWatch.ignorer(this))
        clipSince = maxOf(clipSince, evs.maxOfOrNull { it.ts } ?: clipSince)
        if (hits.isEmpty()) return
        runCatching { db.addAccess(hits.map { it.e }) }
        for (h in hits.filter { !it.refused }) {
            val pkg = h.e.pkg
            if ((clipNotified[pkg] ?: 0L) > now - 10 * 60_000L) continue
            clipNotified[pkg] = now
            val t = java.time.Instant.ofEpochMilli(h.e.ts).atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
            val n = Notification.Builder(this, App.CH_ALERTS)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Clipboard read in the background")
                .setContentText("${names.name(pkg)} read what you copied at $t while it was not on screen.")
                .setStyle(Notification.BigTextStyle().bigText("${names.name(pkg)} read what you copied at $t while it was not on screen. " +
                    "Copied passwords, codes or addresses can be read this way."))
                .setContentIntent(openApp()).setAutoCancel(true).build()
            Log.i(TAG, "clip alert $pkg")
            getSystemService(NotificationManager::class.java).notify(4000 + (pkg.hashCode() and 0xfff), n)
        }
        hits.filter { it.refused }.forEach { Log.i(TAG, "clip refused by Android: ${it.e.pkg}") }
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            sampleScreen()
            worker.postDelayed(this, 60_000)
        }
    }

    private val recCb = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            worker.post { onMic(configs.isNotEmpty()) }
        }
    }

    private val camCb = object : CameraManager.AvailabilityCallback() {
        override fun onCameraUnavailable(id: String) { worker.post { onCam(id, true) } }
        override fun onCameraAvailable(id: String) { worker.post { onCam(id, false) } }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** App i ri i instaluar -> skano SDK-te dhe lejet, njofto. */
    private val installReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
            val pkg = i.data?.schemeSpecificPart ?: return
            worker.post { runCatching { com.privacyguard.apps.Insights.onInstalled(this@MonitorService, pkg) } }
        }
    }

    override fun onCreate() {
        super.onCreate()
        db = Db.get(this)
        names = AppNames(this)
        thread = HandlerThread("pg-monitor").apply { start() }
        worker = Handler(thread.looper)
        startForeground(
            App.NOTIF_MONITOR, ongoing("Watching the microphone and camera"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        // sistemi e dergon vetem ai (broadcast i mbrojtur)
        ContextCompat.registerReceiver(this, installReceiver, IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addDataScheme("package") },
            ContextCompat.RECEIVER_EXPORTED)
        getSystemService(AudioManager::class.java).registerAudioRecordingCallback(recCb, worker)
        getSystemService(CameraManager::class.java).registerAvailabilityCallback(camCb, worker)
        worker.post {
            mode = Shell.mode(this, tryConnect = true)
            updateOngoing()
            heartbeat.run()
            clipWatch.run()
            onMic(getSystemService(AudioManager::class.java).activeRecordingConfigurations.isNotEmpty())
        }
        worker.postDelayed(object : Runnable {
            override fun run() {
                mode = Shell.mode(this@MonitorService, tryConnect = true)
                updateOngoing()
                // pas restartimit firewall-i i kernelit eshte bosh: rivendose sapo ka lidhje ADB
                if (com.privacyguard.block.Firewall.pending(this@MonitorService)) runCatching { com.privacyguard.block.Firewall.sync(this@MonitorService) }
                worker.postDelayed(this, 15 * 60_000L)
            }
        }, 15 * 60_000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching { unregisterReceiver(installReceiver) }
        getSystemService(AudioManager::class.java).unregisterAudioRecordingCallback(recCb)
        getSystemService(CameraManager::class.java).unregisterAvailabilityCallback(camCb)
        thread.quitSafely()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ ekrani
    private fun screenOn() = getSystemService(PowerManager::class.java).isInteractive

    private fun sampleScreen() {
        val on = screenOn()
        db.addScreen(ScreenRow(System.currentTimeMillis(), on, if (on) topApp() else null))
    }

    /** App-i ne ekran (nga UsageStats; kerkon "Usage access"). */
    private fun topApp(): String? {
        val usm = getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val ev = usm.queryEvents(now - 10 * 60_000, now)
        val e = UsageEvents.Event()
        var last: String? = null
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = e.packageName
        }
        return last
    }

    // ------------------------------------------------------------------ mikrofoni
    private fun inCall(): Boolean {
        val m = getSystemService(AudioManager::class.java).mode
        return m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_IN_COMMUNICATION
    }

    private fun onMic(active: Boolean) {
        val now = System.currentTimeMillis()
        if (active && micStart == 0L) {
            micStart = now
            micScreenOn = screenOn()
            micPkgs = attribute(isMic = true)
            Log.i(TAG, "mic ON screen=$micScreenOn call=${inCall()} pkgs=$micPkgs")
            if (!micScreenOn && !inCall()) alertOff("Microphone", micPkgs)
        } else if (!active && micStart != 0L) {
            record(if (inCall()) "PHONE_CALL_MICROPHONE" else "RECORD_AUDIO", micPkgs, micStart, now, micScreenOn)
            micStart = 0L
        }
    }

    // ------------------------------------------------------------------ kamera
    private fun onCam(id: String, inUse: Boolean) {
        val now = System.currentTimeMillis()
        if (inUse) {
            if (camStart.isEmpty()) {
                camScreenOn = screenOn()
                camPkgs = attribute(isMic = false)
                Log.i(TAG, "cam ON id=$id screen=$camScreenOn pkgs=$camPkgs")
                if (!camScreenOn) alertOff("Camera", camPkgs)
            }
            camStart.putIfAbsent(id, now)
        } else {
            val s = camStart.remove(id) ?: return
            if (camStart.isEmpty()) record("CAMERA", camPkgs, s, now, camScreenOn)
        }
    }

    /** Cili app? Ne menyrat e plota e pyesim sistemin; ne menyren e kufizuar hamendesojme app-in ne ekran. */
    private fun attribute(isMic: Boolean): Set<String> {
        if (mode != Mode.LIMITED) {
            val p = LiveAttribution.who(this, isMic, mode)
            if (p.isNotEmpty()) return p
        }
        return emptySet()
    }

    private fun record(op: String, pkgs: Set<String>, start: Long, end: Long, screenWasOn: Boolean) {
        val top = if (screenWasOn) topApp() else null
        val list = if (pkgs.isNotEmpty()) pkgs.map { it to (if (it == top) "top" else "unknown") }
        else listOf((top ?: AppNames.UNKNOWN) to "unknown")
        db.addAccess(list.map { (pkg, st) ->
            AccessEvent(pkg, -1, op, start, end - start, st, "access", "live")
        })
    }

    // ------------------------------------------------------------------ njoftimet
    private fun alertOff(what: String, pkgs: Set<String>) {
        val real = pkgs.filter { it !in Ops.knownSystem }
        if (pkgs.isNotEmpty() && real.isEmpty()) { Log.i(TAG, "alert $what: vetem app sistemi $pkgs"); return }   // p.sh. Google Assistant
        val who = if (real.isEmpty()) "unknown app" else real.joinToString { names.name(it) }
        val n = Notification.Builder(this, App.CH_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("$what turned on while the screen was off")
            .setContentText("By: $who")
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        Log.i(TAG, "alert $what: njoftim per $who")
        getSystemService(NotificationManager::class.java).notify((System.currentTimeMillis() % 100000).toInt(), n)
    }

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun ongoing(text: String) = Notification.Builder(this, App.CH_MONITOR)
        .setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle("Privacy Guard")
        .setContentText(text)
        .setContentIntent(openApp())
        .setOngoing(true)
        .build()

    private fun updateOngoing() {
        getSystemService(NotificationManager::class.java)
            .notify(App.NOTIF_MONITOR, ongoing("Mode: ${mode.label}"))
    }

    companion object {
        private const val TAG = "PG.Monitor"
        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, MonitorService::class.java))
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, MonitorService::class.java))
    }
}
