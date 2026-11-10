package com.privacyguard.collect

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.privacyguard.adb.AdbManager
import java.util.concurrent.TimeUnit

/**
 * Tre menyrat e qasjes:
 *  DIRECT  - app-i ka lejen DUMP (dhene me "pm grant", nga kompjuteri ose nga vete app-i)
 *            dhe ekzekuton "dumpsys" vete. Mbetet edhe pas restartimit.
 *  ADB     - app-i lidhet me vete telefonin me Wireless debugging dhe ekzekuton
 *            komandat si "shell" (si ADB nga kompjuteri).
 *  LIMITED - asnjera s'eshte aktive: vetem callback-et e kameres/mikrofonit.
 */
enum class Mode(val label: String, val desc: String) {
    DIRECT("Full access (permanent)", "Full history is read directly, also after a restart."),
    ADB("Full access (Wireless debugging)", "Full history is read through a local debug connection. Must be reconnected after a restart."),
    LIMITED("Limited", "Shows when the microphone/camera was used, but not always by which app."),
}

object Shell {
    private const val TAG = "PG.Shell"
    const val SELF = "com.privacyguard"

    fun hasDump(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED

    /** Menyra qe funksionon tani (provon edhe lidhjen ADB). */
    fun mode(ctx: Context, tryConnect: Boolean = true): Mode = when {
        hasDump(ctx) && directWorks() -> Mode.DIRECT
        adbConnected(ctx, tryConnect) -> Mode.ADB
        else -> Mode.LIMITED
    }

    private var directOk: Boolean? = null

    private fun directWorks(): Boolean = directOk ?: run {
        val out = runCatching { direct(listOf("dumpsys", "appops", "--op", "RECORD_AUDIO"), 20) }.getOrDefault("")
        // nese Samsung/SELinux e bllokon, del "Permission Denial" ose bosh
        (out.length > 50 && !out.contains("Permission Denial")).also { directOk = it }
    }

    fun direct(cmd: List<String>, timeoutSec: Long = 120): String {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = StringBuilder()
        val reader = Thread { p.inputStream.bufferedReader().use { out.append(it.readText()) } }
        reader.start()
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) p.destroyForcibly()
        reader.join(5000)
        return out.toString()
    }

    fun adbConnected(ctx: Context, tryConnect: Boolean): Boolean {
        val m = AdbManager.get(ctx)
        return runCatching {
            m.isConnected || (tryConnect && m.autoConnect(ctx, 6000))
        }.onFailure { Log.w(TAG, "autoConnect", it) }.getOrDefault(false)
    }

    fun adb(ctx: Context, cmd: String): String {
        val stream = AdbManager.get(ctx).openStream("shell:$cmd")
        val out = java.io.ByteArrayOutputStream()
        try {
            val ins = stream.openInputStream()
            val buf = ByteArray(8192)
            while (true) {
                // libadb 3.1.1: kur telefoni e mbyll stream-in pa dalje (p.sh. "pm grant" i suksesshem),
                // read() hedh IOException("Stream closed.") ne vend te -1. Ky eshte fundi normal.
                val n = try { ins.read(buf) } catch (e: java.io.IOException) { if (stream.isClosed) break else throw e }
                if (n < 0) break
                out.write(buf, 0, n)
            }
        } finally {
            runCatching { stream.close() }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /** Ekzekuton nje komande me menyren me te mire qe kemi; null nese s'kemi asnje. */
    fun run(ctx: Context, cmd: String, mode: Mode = mode(ctx)): String? = runCatching {
        when (mode) {
            Mode.DIRECT -> direct(cmd.split(' ').filter { it.isNotBlank() })
            Mode.ADB -> adb(ctx, cmd)
            Mode.LIMITED -> null
        }
    }.onFailure { Log.w(TAG, "run $cmd", it) }.getOrNull()

    /**
     * Pas lidhjes me Wireless debugging: app-i i jep vetes lejet qe i duhen.
     * Nese Samsung i pranon, menyra DIRECT mbetet pergjithmone dhe Wireless
     * debugging nuk duhet me.
     */
    fun selfGrant(ctx: Context): String {
        val cmds = listOf(
            "pm grant $SELF android.permission.DUMP",
            "pm grant $SELF android.permission.GET_APP_OPS_STATS",
            // Android 17: "dumpsys appops" kerkon edhe kete leje (jo vetem app-op-in GET_USAGE_STATS)
            "pm grant $SELF android.permission.PACKAGE_USAGE_STATS",
            "appops set $SELF GET_USAGE_STATS allow",
            // Android-i e mban historikun e qasjeve 7 dite ne vend te 1
            "device_config put privacy discrete_history_cutoff_millis 604800000",
        )
        val log = StringBuilder()
        for (c in cmds) {
            val r = runCatching { adb(ctx, c) }.getOrElse { "error: ${it.message}" }
            log.append("$ ").append(c).append('\n').append(r.trim()).append('\n')
        }
        directOk = null
        return log.toString()
    }
}

/** Kush po perdor mikrofonin/kameren tani (vetem ne menyrat e plota). */
object LiveAttribution {
    fun who(ctx: Context, isMic: Boolean, mode: Mode): Set<String> {
        val cmd = if (isMic) "dumpsys audio" else "dumpsys media.camera"
        val out = Shell.run(ctx, cmd, mode) ?: return emptySet()
        return if (isMic) com.privacyguard.core.LiveParser.micPackages(out)
        else com.privacyguard.core.LiveParser.cameraPackages(out)
    }
}
