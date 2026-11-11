package com.privacyguard.collect

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.privacyguard.adb.AdbManager
import java.util.concurrent.TimeUnit

/**
 * Three access levels:
 *  DIRECT  - the app holds the DUMP permission (granted with "pm grant", from a computer or by the app itself)
 *            and runs "dumpsys" itself. Survives restarts.
 *  ADB     - the app connects to the phone itself over Wireless debugging and runs
 *            commands as "shell" (like ADB from a computer).
 *  LIMITED - neither is active: only the camera/microphone callbacks.
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

    /** The level that works right now (also tries the ADB connection). */
    fun mode(ctx: Context, tryConnect: Boolean = true): Mode = when {
        hasDump(ctx) && directWorks() -> Mode.DIRECT
        adbConnected(ctx, tryConnect) -> Mode.ADB
        else -> Mode.LIMITED
    }

    private var directOk: Boolean? = null

    private fun directWorks(): Boolean = directOk ?: run {
        val out = runCatching { direct(listOf("dumpsys", "appops", "--op", "RECORD_AUDIO"), 20) }.getOrDefault("")
        // if Samsung/SELinux blocks it, the output is "Permission Denial" or empty
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
                // libadb 3.1.1: when the phone closes the stream without output (e.g. a successful "pm grant"),
                // read() throws IOException("Stream closed.") instead of returning -1. That is the normal end.
                val n = try { ins.read(buf) } catch (e: java.io.IOException) { if (stream.isClosed) break else throw e }
                if (n < 0) break
                out.write(buf, 0, n)
            }
        } finally {
            runCatching { stream.close() }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /** Runs a command with the best level we have; null if we have none. */
    fun run(ctx: Context, cmd: String, mode: Mode = mode(ctx)): String? = runCatching {
        when (mode) {
            Mode.DIRECT -> direct(cmd.split(' ').filter { it.isNotBlank() })
            Mode.ADB -> adb(ctx, cmd)
            Mode.LIMITED -> null
        }
    }.onFailure { Log.w(TAG, "run $cmd", it) }.getOrNull()

    /**
     * After connecting with Wireless debugging the app grants itself the permissions it needs.
     * If the system accepts them, DIRECT mode stays permanently and Wireless
     * debugging is no longer needed.
     */
    fun selfGrant(ctx: Context): String {
        val cmds = listOf(
            "pm grant $SELF android.permission.DUMP",
            "pm grant $SELF android.permission.GET_APP_OPS_STATS",
            // Android 17: "dumpsys appops" also needs this permission (not only the GET_USAGE_STATS app-op)
            "pm grant $SELF android.permission.PACKAGE_USAGE_STATS",
            "appops set $SELF GET_USAGE_STATS allow",
            // keep the access history for 7 days instead of 1
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

/** Who is using the microphone/camera right now (full levels only). */
object LiveAttribution {
    fun who(ctx: Context, isMic: Boolean, mode: Mode): Set<String> {
        val cmd = if (isMic) "dumpsys audio" else "dumpsys media.camera"
        val out = Shell.run(ctx, cmd, mode) ?: return emptySet()
        return if (isMic) com.privacyguard.core.LiveParser.micPackages(out)
        else com.privacyguard.core.LiveParser.cameraPackages(out)
    }
}
