package com.privacyguard.collect

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

/**
 * Shizuku (https://shizuku.rikka.app): runs commands as shell (ADB) or as root on rooted phones.
 * An alternative to Wireless debugging: no pairing, and with root it starts by itself after a restart.
 * Uses the Shizuku API (Apache-2.0).
 */
object ShizukuShell {
    private const val TAG = "PG.Shizuku"
    const val PKG = "moe.shizuku.privileged.api"
    const val REQUEST = 4711

    fun installed(ctx: Context) = runCatching { ctx.packageManager.getPackageInfo(PKG, 0); true }.getOrDefault(false)

    /** Shizuku's service is running (started from its app, by root, or over Wireless debugging). */
    fun running() = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun granted() = running() && runCatching {
        !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** Ready to run commands. */
    fun ready() = granted()

    /** true when Shizuku runs as root (uid 0) rather than shell (uid 2000). */
    fun isRoot() = runCatching { Shizuku.getUid() == 0 }.getOrDefault(false)

    fun request() = runCatching { Shizuku.requestPermission(REQUEST) }.onFailure { Log.w(TAG, "request", it) }

    // Shizuku.newProcess is private in API 13; it is still the simplest way to run a shell command
    // (the alternative, a bound UserService, needs a separate AIDL service for the same result).
    private val newProcess by lazy {
        Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            .apply { isAccessible = true }
    }

    /** Runs `cmd` through `sh -c` with Shizuku's uid and returns stdout + stderr. */
    fun exec(cmd: String, timeoutSec: Long = 120): String {
        val p = newProcess.invoke(null, arrayOf("sh", "-c", "$cmd 2>&1"), null, null) as Process
        val out = StringBuilder()
        // Shizuku's remote process has no waitFor(timeout); the output closes when the command ends
        val reader = Thread { runCatching { p.inputStream.bufferedReader().use { out.append(it.readText()) } } }
        reader.start()
        reader.join(TimeUnit.SECONDS.toMillis(timeoutSec))
        if (reader.isAlive) { runCatching { p.destroy() }; reader.join(2000) }
        else runCatching { p.waitFor() }
        return out.toString()
    }
}
