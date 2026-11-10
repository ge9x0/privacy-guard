package com.privacyguard.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privacyguard.Prefs
import com.privacyguard.adb.Pairing
import com.privacyguard.block.Blocker
import com.privacyguard.collect.Collector
import com.privacyguard.collect.Mode
import com.privacyguard.collect.Shell
import com.privacyguard.core.Analyzer
import com.privacyguard.core.DayReport
import com.privacyguard.core.fmtBytes
import com.privacyguard.core.fmtDur
import com.privacyguard.monitor.MonitorService
import com.privacyguard.vpn.DnsVpnService
import com.privacyguard.work.Reports
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Home() }
            }
        }
    }
}

private val RED = Color(0xFFC62828)
private val ORANGE = Color(0xFFE65100)
private val GREEN = Color(0xFF2E7D32)

@Composable
private fun Home() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf<Mode?>(null) }
    var today by remember { mutableStateOf<DayReport?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var usage by remember { mutableStateOf(Collector.hasUsageAccess(ctx)) }
    var monitor by remember { mutableStateOf(Prefs.monitor(ctx)) }
    var vpn by remember { mutableStateOf(DnsVpnService.isRunning) }
    var manual by remember { mutableStateOf<Pair<String, Blocker.Result.Manual>?>(null) }
    var blockTick by remember { mutableIntStateOf(0) }
    var setupOpen by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(refresh) {
        withContext(Dispatchers.IO) {
            mode = Shell.mode(ctx, tryConnect = true)
            today = runCatching { Analyzer(ctx).build(LocalDate.now()) }.getOrNull()
        }
        usage = Collector.hasUsageAccess(ctx)
    }

    fun work(label: String, block: suspend () -> String?) {
        busy = label
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { block() }.getOrElse { "Error: ${it.message}" } }
            busy = null; msg = r; refresh++
            // the message at the top is not visible while scrolled down in Setup
            if (!r.isNullOrBlank()) android.widget.Toast.makeText(ctx, r, android.widget.Toast.LENGTH_LONG).show()
        }
    }
    fun limit(pkg: String, op: String) = work("Limiting permission") {
        when (val r = Blocker.limit(ctx, pkg, op)) {
            is Blocker.Result.Manual -> { manual = pkg to r; null }
            is Blocker.Result.Done -> { blockTick++; r.msg }
        }
    }
    fun blockNet(pkg: String) {
        Blocker.setApp(ctx, pkg, true); blockTick++
        msg = if (vpn) "Internet blocked for this app (DNS). It can take a few minutes for cached addresses to expire."
        else "Saved. Blocking only works while the local VPN is on (Setup → Record destinations)."
    }
    fun blockHost(h: String) {
        Blocker.setHost(ctx, h, true); blockTick++
        msg = "$h blocked" + if (vpn) "." else " (turn on the local VPN for it to take effect)."
    }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (VpnService.prepare(ctx) == null) {
            DnsVpnService.start(ctx); Prefs.setVpn(ctx, true); vpn = true
        }
    }
    LaunchedEffect(Unit) {
        if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val full = mode == Mode.DIRECT || mode == Mode.ADB
    val stepsDone = listOf(usage, full, monitor, vpn).count { it }
    val showSetup = setupOpen ?: (mode != null && stepsDone < 4)

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---------------- header
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Privacy Guard", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text("Today, ${LocalDate.now()}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val m = mode
            Chip(m?.label ?: "Checking…", when (m) { null -> null; Mode.LIMITED -> ORANGE; else -> GREEN })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("Monitor " + if (monitor) "on" else "off", if (monitor) GREEN else ORANGE, small = true)
            Chip("VPN " + if (vpn) "on" else "off", if (vpn) GREEN else ORANGE, small = true)
            Chip("Usage " + if (usage) "ok" else "missing", if (usage) GREEN else ORANGE, small = true)
        }
        if (mode != null && stepsDone < 4) Banner("Setup incomplete ($stepsDone of 4 done). Some data will be missing.", ORANGE) {
            TextButton(onClick = { setupOpen = true }) { Text("Open setup") }
        }

        busy?.let { Banner("$it…", MaterialTheme.colorScheme.primary) }
        msg?.let { Banner(it, null) { TextButton(onClick = { msg = null }) { Text("Dismiss") } } }
        manual?.let { (pkg, r) -> ManualCard(pkg, r) { manual = null } }

        // ---------------- today
        val d = today
        if (d == null) Text("Loading today's data…", fontSize = 13.sp)
        else {
            KpiGrid(d)
            Actions(busy == null,
                onCollect = { work("Collecting") { val r = Collector.snapshot(ctx); "Collected ${r.accessNew} new access records (${r.mode.label}). ${r.note ?: ""}" } },
                onPdf = {
                    work("Creating PDF") {
                        runCatching { Collector.snapshot(ctx) }
                        val day = LocalDate.now()
                        Reports.create(ctx, listOf(day), "privacy_report_$day.pdf")?.let { openPdf(ctx, it) }
                        "PDF saved to Download/PrivacyGuard"
                    }
                },
                onWeek = {
                    work("Creating 7-day PDF") {
                        val days = (6 downTo 0).map { LocalDate.now().minusDays(it.toLong()) }
                        Reports.create(ctx, days, "privacy_report_7_days_${LocalDate.now()}.pdf")?.let { openPdf(ctx, it) }
                        "7-day PDF saved to Download/PrivacyGuard"
                    }
                })
            AlertsCard(d, onLimit = ::limit, onNet = ::blockNet)
            FlowsCard(d)
            TrackersCard(d, blockTick, vpn, onHost = ::blockHost)
        }

        // ---------------- blocking
        BlockSection(vpn, blockTick, onChange = { blockTick++ }, onRestore = { key ->
            work("Restoring") {
                when (val r = Blocker.restore(ctx, key)) {
                    is Blocker.Result.Manual -> { manual = key.substringBefore('|') to r; null }
                    is Blocker.Result.Done -> { blockTick++; r.msg }
                }
            }
        })

        // ---------------- setup (collapsed)
        SectionCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Title("Setup")
                    Text("$stepsDone of 4 steps done", fontSize = 12.sp, color = if (stepsDone == 4) GREEN else ORANGE)
                }
                TextButton(onClick = { setupOpen = !showSetup }) { Text(if (showSetup) "Hide" else "Show") }
            }
        }
        if (showSetup) {
            Step("1. Usage access", usage,
                "Needed to know how much data each app sends and which app is on screen.") {
                TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("Open Settings") }
            }

            Step("2. Full access", full,
                "Reads the full history: who, when, for how long, on screen or in the background.") {
                Text("Option A – from a computer, once (USB or Wi-Fi):", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                val cmd = "adb shell pm grant ${Shell.SELF} android.permission.DUMP\n" +
                    "adb shell pm grant ${Shell.SELF} android.permission.PACKAGE_USAGE_STATS"
                Code(cmd)
                TextButton(onClick = {
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("cmd", cmd))
                }) { Text("Copy commands") }
                Spacer(Modifier.height(6.dp))
                Text("Option B – no computer, with Wireless debugging:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text("1) Tap “Start”. 2) In Developer options turn on Wireless debugging → Pair device with pairing code. " +
                    "3) Type the code into the Privacy Guard notification without leaving Settings.", fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        Pairing.start(ctx)
                        runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                    }) { Text("Start") }
                    OutlinedButton(onClick = {
                        work("Connecting") {
                            if (Shell.adbConnected(ctx, true)) {
                                android.util.Log.i("PG.Shell", "Reconnect: connected\n" + Shell.selfGrant(ctx))
                                "Connected over Wireless debugging."
                            } else "Not connected. Is Wireless debugging on?"
                        }
                    }) { Text("Reconnect") }
                }
                ManualPair { port, code -> work("Pairing") { Pairing.pairAndSetup(ctx, port, code) } }
            }

            Step("3. Continuous monitoring", monitor,
                "Records when the screen is off and alerts you right away if the microphone or camera turns on then.") {
                Toggle(monitor) {
                    monitor = it; Prefs.setMonitor(ctx, it)
                    if (it) MonitorService.start(ctx) else MonitorService.stop(ctx)
                }
            }

            Step("4. Record destinations (local VPN)", vpn,
                "Sees which servers each app contacts and matches them against the list of data brokers and trackers. " +
                    "Only DNS passes through it; your traffic never leaves the phone. For best results set Private DNS to “Off”.") {
                Toggle(vpn) {
                    if (it) {
                        val i = VpnService.prepare(ctx)
                        if (i != null) vpnLauncher.launch(i) else { DnsVpnService.start(ctx); Prefs.setVpn(ctx, true); vpn = true }
                    } else { DnsVpnService.stop(ctx); Prefs.setVpn(ctx, false); vpn = false }
                }
            }
        }
        Text("A report is created automatically every night at 23:50 and saved to Download/PrivacyGuard.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Title(t: String) = Text(t, fontSize = 17.sp, fontWeight = FontWeight.Bold)

@Composable
private fun Hint(t: String) = Text(t, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Chip(t: String, c: Color?, small: Boolean = false) {
    val col = c ?: MaterialTheme.colorScheme.onSurfaceVariant
    Text(t, color = col, fontSize = if (small) 11.sp else 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.background(col.copy(alpha = 0.12f), RoundedCornerShape(50))
            .padding(horizontal = if (small) 8.dp else 10.dp, vertical = 4.dp))
}

@Composable
private fun Banner(t: String, c: Color?, action: (@Composable () -> Unit)? = null) {
    val col = c ?: MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().background(col.copy(alpha = 0.08f), RoundedCornerShape(12.dp)).padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(t, fontSize = 13.sp, color = col, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
        action?.invoke()
    }
}

@Composable
private fun Code(t: String) = SelectionContainer {
    Text(t, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)).padding(8.dp))
}

@Composable
private fun Toggle(on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = on, onCheckedChange = onChange)
        Spacer(Modifier.width(8.dp)); Text(if (on) "On" else "Off")
    }
}

@Composable
private fun KpiGrid(d: DayReport) {
    val items = listOf(
        Triple("${d.crit}", "Critical", if (d.crit > 0) RED else null),
        Triple("${d.high}", "High", if (d.high > 0) ORANGE else null),
        Triple("${d.trk}", "Tracker requests", if (d.trk > 0) ORANGE else null),
        Triple("${d.micN}×", "Microphone · ${fmtDur(d.micMs)}", null),
        Triple("${d.camN}×", "Camera · ${fmtDur(d.camMs)}", null),
        Triple(fmtBytes(d.txBg), "Sent in background", null),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in items.chunked(3)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((v, l, c) in row) Card(Modifier.weight(1f), colors = CardDefaults.cardColors(
                containerColor = c?.copy(alpha = 0.10f) ?: MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
                    Text(v, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = c ?: MaterialTheme.colorScheme.onSurface)
                    Text(l, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
            }
        }
    }
}

@Composable
private fun Actions(enabled: Boolean, onCollect: () -> Unit, onPdf: () -> Unit, onWeek: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onCollect, enabled = enabled, modifier = Modifier.weight(1f)) { Text("Refresh", fontSize = 13.sp) }
        OutlinedButton(onClick = onPdf, enabled = enabled, modifier = Modifier.weight(1f)) { Text("Today PDF", fontSize = 13.sp) }
        OutlinedButton(onClick = onWeek, enabled = enabled, modifier = Modifier.weight(1f)) { Text("7-day PDF", fontSize = 13.sp) }
    }
}

private fun openPdf(ctx: android.content.Context, uri: android.net.Uri) {
    runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

@Composable
private fun Step(title: String, done: Boolean, desc: String, content: @Composable () -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (done) "✓" else "•", color = if (done) GREEN else ORANGE, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.width(8.dp))
            Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(if (done) "Done" else "To do", fontSize = 11.sp, color = if (done) GREEN else ORANGE)
        }
        Text(desc, fontSize = 13.sp)
        content()
    }
}

@Composable
private fun ManualPair(onPair: (Int, String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var port by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    TextButton(onClick = { open = !open }) { Text(if (open) "Close" else "No notification? Enter it here") }
    if (open) {
        Hint("Use split screen so you can see Settings and this app at the same time.")
        OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Port (e.g. 37145)") }, singleLine = true)
        OutlinedTextField(code, { code = it.filter(Char::isDigit) }, label = { Text("6-digit code") }, singleLine = true)
        Button(onClick = { port.toIntOrNull()?.let { onPair(it, code) } }, enabled = port.isNotEmpty() && code.length == 6) { Text("Pair") }
    }
}

@Composable
private fun AlertsCard(d: DayReport, onLimit: (String, String) -> Unit, onNet: (String) -> Unit) {
    val groups = d.alerts.groupBy { Triple(it.e.pkg, it.label, it.sev) }.entries.toList()
    var all by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val an = remember { Analyzer(ctx).names }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Title("Alerts")
            Spacer(Modifier.width(8.dp))
            if (groups.isNotEmpty()) Chip("${groups.size}", if (d.crit > 0) RED else ORANGE, small = true)
        }
        if (groups.isEmpty()) { Text("No suspicious access today.", fontSize = 13.sp, color = GREEN); return@SectionCard }
        Hint("Sensitive access while the app was in the background or the screen was off.")
        for ((k, list) in if (all) groups else groups.take(6)) {
            val (pkg, label, sev) = k
            HorizontalDivider(Modifier.padding(vertical = 2.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Box(Modifier.width(72.dp).padding(top = 2.dp)) { Chip(sev.label, Color(sev.color), small = true) }
                Column(Modifier.weight(1f)) {
                    Text(an.name(pkg), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    val ms = list.sumOf { it.e.durationMs }
                    Text(label + (if (list.size > 1) " ×${list.size}" else "") + if (ms > 0) " · ${fmtDur(ms)}" else "", fontSize = 13.sp)
                    Hint(list.first().why)
                    BlockMenu(Blocker.limitLabel(list.first().e.op, label), onLimit = { onLimit(pkg, list.first().e.op) }, onNet = { onNet(pkg) })
                }
            }
        }
        if (groups.size > 6) TextButton(onClick = { all = !all }) { Text(if (all) "Show less" else "Show all ${groups.size}") }
    }
}

@Composable
private fun BlockMenu(limitText: String, onLimit: () -> Unit, onNet: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    if (!open) { TextButton(onClick = { open = true }) { Text("Block…", fontSize = 12.sp) }; return }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))) {
        TextButton(onClick = { open = false; onLimit() }) { Text(limitText, fontSize = 12.sp) }
        TextButton(onClick = { open = false; onNet() }) { Text("Block this app's internet", fontSize = 12.sp) }
        TextButton(onClick = { open = false }) { Text("Cancel", fontSize = 12.sp) }
    }
}

@Composable
private fun ManualCard(pkg: String, r: Blocker.Result.Manual, onClose: () -> Unit) {
    val ctx = LocalContext.current
    SectionCard {
        Title("Do it yourself")
        Text(r.msg, fontSize = 13.sp)
        if (r.commands.isNotEmpty()) Code(r.commands.joinToString("\n"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { Blocker.openAppSettings(ctx, pkg) }) { Text("Open Settings") }
            if (r.commands.isNotEmpty()) OutlinedButton(onClick = {
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("cmd", r.commands.joinToString("\n")))
            }) { Text("Copy") }
            TextButton(onClick = onClose) { Text("Close") }
        }
    }
}

@Composable
private fun TrackersCard(d: DayReport, tick: Int, vpn: Boolean, onHost: (String) -> Unit) {
    val ctx = LocalContext.current
    val blocked = remember(tick) { Blocker.hosts(ctx) }
    // host -> (company, apps)
    val hosts = d.trackers.flatMap { (pkg, m) -> m.flatMap { (t, u) -> u.hosts.map { Triple(it, t.company, pkg) } } }
        .groupBy { it.first }.entries.sortedByDescending { it.value.size }.take(10)
    val an = remember { Analyzer(ctx).names }
    SectionCard {
        Title("Trackers today")
        if (hosts.isEmpty()) {
            Hint(if (d.hasConnData) "No connections to known trackers today."
                else "Turn on “Record destinations (local VPN)” in Setup to see who each app talks to.")
            return@SectionCard
        }
        Hint("Servers of ad networks, data brokers and analytics that apps contacted.")
        for ((h, l) in hosts) {
            HorizontalDivider(Modifier.padding(vertical = 2.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(l.first().second, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(h, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    Hint("from " + l.map { an.name(it.third) }.distinct().take(3).joinToString())
                }
                if (h in blocked) Chip("Blocked", RED, small = true)
                else TextButton(onClick = { onHost(h) }) { Text("Block", fontSize = 12.sp) }
            }
        }
        if (!vpn) Hint("The local VPN is off, so blocks have no effect right now.")
    }
}

@Composable
private fun BlockSection(vpn: Boolean, tick: Int, onChange: () -> Unit, onRestore: (String) -> Unit) {
    val ctx = LocalContext.current
    val trackers = remember(tick) { Blocker.trackersOn(ctx) }
    val apps = remember(tick) { Blocker.apps(ctx).sorted() }
    val hosts = remember(tick) { Blocker.hosts(ctx).sorted() }
    val ops = remember(tick) { Blocker.limited(ctx) }
    val an = remember { Analyzer(ctx).names }
    SectionCard {
        Title("Blocking")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Block third-party trackers", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Hint("E.g. the Facebook SDK inside another app is blocked, but Instagram inside Instagram is not. System apps are never touched.")
            }
            Switch(checked = trackers, onCheckedChange = { Blocker.setTrackers(ctx, it); onChange() })
        }
        if (!vpn) Banner("Network blocking works through the local VPN, which is off. Turn it on in Setup → step 4.", ORANGE)
        if (apps.isNotEmpty()) { HorizontalDivider(); Text("Apps without internet", fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
        for (a in apps) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(an.name(a), fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { Blocker.setApp(ctx, a, false); onChange() }) { Text("Remove", fontSize = 12.sp) }
        }
        if (hosts.isNotEmpty()) { HorizontalDivider(); Text("Blocked domains", fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
        for (h in hosts) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(h, fontSize = 13.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
            TextButton(onClick = { Blocker.setHost(ctx, h, false); onChange() }) { Text("Remove", fontSize = 12.sp) }
        }
        if (ops.isNotEmpty()) { HorizontalDivider(); Text("Limited permissions", fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
        for ((k, _) in ops) Row(verticalAlignment = Alignment.CenterVertically) {
            val (pkg, op) = k.split('|', limit = 2)
            Text("${an.name(pkg)} · ${com.privacyguard.core.Ops.map[op]?.first ?: op}", fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { onRestore(k) }) { Text("Restore", fontSize = 12.sp) }
        }
        Hint("Limiting permissions needs full access via Wireless debugging; otherwise Settings opens. Microphone, camera and location can be set to " +
            "“only while in use”; contacts, call log, SMS and clipboard can only be blocked completely.")
    }
}

@Composable
private fun FlowsCard(d: DayReport) {
    if (d.flows.isEmpty()) return
    val ctx = LocalContext.current
    val an = remember { Analyzer(ctx).names }
    val hm = remember { java.time.format.DateTimeFormatter.ofPattern("HH:mm") }
    fun t(ts: Long) = java.time.Instant.ofEpochMilli(ts).atZone(java.time.ZoneId.systemDefault()).format(hm)
    SectionCard {
        Title("What apps took → where it went")
        Hint("Matched by time (tracker contacted within 10 min after the access), not by content.")
        for (f in d.flows.take(8)) {
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(an.name(f.pkg), fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (f.blocked > 0) Chip("${f.blocked} blocked", GREEN, small = true)
            }
            if (f.took.isNotEmpty()) Text("Took: " + f.took.entries.joinToString { (k, n) ->
                k + (if (n > 1) " ×$n" else "") + if (k in f.bgTook) " (background)" else "" }, fontSize = 12.sp,
                color = if (f.bgTook.isNotEmpty()) ORANGE else MaterialTheme.colorScheme.onSurface)
            Text("Sent to: " + f.to.entries.sortedByDescending { it.value.count }
                .joinToString { (tr, u) -> "${tr.company} (${tr.category}) ×${u.count}" }, fontSize = 12.sp)
            for (l in f.links) Text("• ${t(l.ts)} ${l.what} → ${l.company} after ${fmtDur(l.afterMs)} (${l.host})",
                fontSize = 12.sp, color = RED)
        }
    }
}
