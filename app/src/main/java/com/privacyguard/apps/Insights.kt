package com.privacyguard.apps

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.privacyguard.App
import com.privacyguard.R
import com.privacyguard.block.FilterLists
import com.privacyguard.core.AppNames
import com.privacyguard.ui.MainActivity

/** Njoftimet e reja: app i ri i instaluar, pikët e javës. */
object Insights {
    private fun open(ctx: Context) = PendingIntent.getActivity(ctx, 11, Intent(ctx, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)

    private fun post(ctx: Context, id: Int, title: String, text: String) {
        val n = Notification.Builder(ctx, App.CH_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher).setContentTitle(title).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open(ctx)).setAutoCancel(true).build()
        ctx.getSystemService(NotificationManager::class.java).notify(id, n)
    }

    /** Thirret nga MonitorService kur instalohet nje app i ri. */
    fun onInstalled(ctx: Context, pkg: String) {
        val pi = UserApps.info(ctx, pkg) ?: return
        if (pi.applicationInfo?.let { it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0 } == true) return
        val sdks = SdkScanner.of(ctx, pi)
        val perms = PermGroups.granted(pi).keys.map { it.label } +
            PermGroups.all.filter { g -> pi.requestedPermissions.orEmpty().any { it in g.perms } }.map { it.label }
        val name = AppNames(ctx).name(pkg)
        val t = if (sdks.isEmpty()) "No known tracker SDKs inside." else "${sdks.size} tracker SDKs inside: " + sdks.joinToString { it.name } + "."
        val p = perms.distinct().takeIf { it.isNotEmpty() }?.let { " Can ask for: ${it.joinToString()}." } ?: ""
        Log.i("PG.Apps", "installed $pkg sdks=${sdks.size}")
        post(ctx, 3000 + (pkg.hashCode() and 0xfff), "New app: $name", t + p)
    }

    /** E diel: kush u perkeqesua dhe kush ka me pak pike. */
    fun weekly(ctx: Context) {
        val now = Score.compute(ctx)
        val prefs = ctx.getSharedPreferences("pg_score", Context.MODE_PRIVATE)
        val names = AppNames(ctx)
        val worse = now.filter { s -> prefs.getInt(s.pkg, 100) - s.score >= 10 }
        val worst = now.take(3).filter { it.score < 80 }
        prefs.edit().apply { now.forEach { putInt(it.pkg, it.score) } }.apply()
        val text = buildString {
            if (worst.isNotEmpty()) append("Lowest: " + worst.joinToString { "${names.name(it.pkg)} ${it.score}" } + ". ")
            if (worse.isNotEmpty()) append("Got worse: " + worse.take(3).joinToString { names.name(it.pkg) } + ". ")
            if (isEmpty()) append("No app looked worse this week.")
        }
        post(ctx, 2900, "Your weekly privacy score", text)
    }
}

class FilterUpdateWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result = runCatching {
        Log.i("PG.Lists", FilterLists.update(applicationContext)); Result.success()
    }.getOrElse { Result.retry() }
}

class WeeklyScoreWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result = runCatching {
        Insights.weekly(applicationContext); Result.success()
    }.getOrElse { Log.w("PG.Apps", "weekly", it); Result.retry() }
}
