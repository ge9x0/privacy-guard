package com.privacyguard.work

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.privacyguard.App
import com.privacyguard.R
import com.privacyguard.collect.Collector
import com.privacyguard.collect.Shell
import com.privacyguard.core.Analyzer
import com.privacyguard.report.PdfReport
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

object Reports {
    /** Krijon PDF-ne per ditet e dhena dhe e ruan te Download/PrivacyGuard. */
    fun create(ctx: Context, days: List<LocalDate>, fileName: String): Uri? {
        val an = Analyzer(ctx)
        val reports = days.map { an.build(it) }
        val mode = Shell.mode(ctx, tryConnect = false)
        val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PrivacyGuard")
        }
        val cr = ctx.contentResolver
        // nese ekziston nje me te njejtin emer (p.sh. raporti i sotem), zevendesoje
        cr.delete(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
            arrayOf(fileName, "%PrivacyGuard%"),
        )
        val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return null
        cr.openOutputStream(uri)?.use { PdfReport(an.names, mode.label).write(reports, it) }
        return uri
    }

    fun notifyReady(ctx: Context, uri: Uri, crit: Int) {
        val open = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(ctx, 7, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, App.CH_REPORTS)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Raporti i sotëm është gati")
            .setContentText(if (crit > 0) "$crit sinjalizime kritike – shiko raportin" else "Asnjë sinjalizim kritik sot")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(App.NOTIF_REPORT, n)
    }
}

/** Cdo 3 ore: mblidh historikun (qe te mos humbase asgje para se Android-i ta fshije). */
class CollectWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result = runCatching {
        Collector.snapshot(applicationContext); Result.success()
    }.getOrElse { Log.w("PG.Work", "collect", it); Result.retry() }
}

/** Cdo nate: mbledhje + PDF-ja e dites + njoftim. */
class DailyReportWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result = runCatching {
        val ctx = applicationContext
        runCatching { Collector.snapshot(ctx) }
        val today = LocalDate.now()
        val uri = Reports.create(ctx, listOf(today), "raporti_$today.pdf")
        if (uri != null) Reports.notifyReady(ctx, uri, Analyzer(ctx).build(today).crit)
        Result.success()
    }.getOrElse { Log.w("PG.Work", "report", it); Result.retry() }
}

object Scheduler {
    fun schedule(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        wm.enqueueUniquePeriodicWork(
            "collect", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CollectWorker>(3, TimeUnit.HOURS).build(),
        )
        // raporti ne 23:50 cdo dite
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(23, 50)
        if (!next.isAfter(now)) next = next.plusDays(1)
        wm.enqueueUniquePeriodicWork(
            "daily-report", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<DailyReportWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(Duration.between(now, next).toMinutes(), TimeUnit.MINUTES)
                .build(),
        )
    }
}
