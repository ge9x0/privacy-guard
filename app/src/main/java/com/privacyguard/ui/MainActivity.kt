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
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
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
import com.privacyguard.core.Sev
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

internal val RED = Color(0xFFC62828)
internal val ORANGE = Color(0xFFE65100)
internal val GREEN = Color(0xFF2E7D32)

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
    var tab by remember { mutableIntStateOf(0) }
    // One scroll state per tab: switching doesn't lose your place.
    val scrolls = List(4) { rememberScrollState() }
    var setupY by remember { mutableIntStateOf(0) }
    var goSetup by remember { mutableIntStateOf(0) }
    LaunchedEffect(goSetup) { if (goSetup > 0) { kotlinx.coroutines.delay(50); scrolls[0].animateScrollTo(setupY) } }

    LaunchedEffect(refresh) {
        // The ADB connection can take up to 6 s: it must not delay showing the data.
        launch(Dispatchers.IO) { mode = Shell.mode(ctx, tryConnect = true) }
        usage = Collector.hasUsageAccess(ctx)
        today = withContext(Dispatchers.IO) { runCatching { Analyzer(ctx).build(LocalDate.now()) }.getOrNull() }
    }

    fun work(label: String, block: suspend () -> String?) {
        busy = label
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { block() }.getOrElse { "Error: ${it.message}" } }
            busy = null; if (!r.isNullOrBlank()) msg = r; refresh++
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
        work("Blocking internet") {
            com.privacyguard.block.Firewall.sync(ctx)?.let { "$it All its traffic is cut, not only DNS." }
                ?: if (vpn) "Internet blocked by DNS. Connect Wireless debugging to also cut its direct connections (firewall)."
                else "Saved. Turn on the local VPN, or connect Wireless debugging for the firewall."
        }
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
        if (android.os.Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val full = mode == Mode.DIRECT || mode == Mode.ADB
    val stepsDone = listOf(usage, full, monitor, vpn).count { it }
    val showSetup = setupOpen ?: (mode != null && stepsDone < 4)

    // Status: a progress bar fixed at the top and the result at the bottom, visible from anywhere on the page.
    val snack = remember { androidx.compose.material3.SnackbarHostState() }
    LaunchedEffect(msg) {
        val m = msg ?: return@LaunchedEffect
        snack.currentSnackbarData?.dismiss()
        snack.showSnackbar(m, withDismissAction = true, duration = androidx.compose.material3.SnackbarDuration.Long)
        if (msg == m) msg = null
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
    busy?.let {
        androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
        Text("$it…", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
    }
    Box(Modifier.weight(1f)) {
    Column(
        Modifier.fillMaxSize().verticalScroll(scrolls[tab]).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---------------- header
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Privacy Guard", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM", java.util.Locale.ENGLISH)), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val m = mode
            Chip(m?.label ?: "Checking…", when (m) { null -> null; Mode.LIMITED -> ORANGE; else -> GREEN })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("Monitor " + if (monitor) "on" else "off", if (monitor) GREEN else ORANGE, small = true)
            Chip("VPN " + if (vpn) "on" else "off", if (vpn) GREEN else ORANGE, small = true)
            Chip("Usage access " + if (usage) "on" else "off", if (usage) GREEN else ORANGE, small = true)
        }
        if (mode != null && stepsDone < 4) Banner("Setup incomplete ($stepsDone of 4 done). Some data will be missing.", ORANGE) {
            TextButton(onClick = { setupOpen = true; tab = 0; goSetup++ }) { Text("Open setup") }
        }

        manual?.let { (pkg, r) -> ManualCard(pkg, r) { manual = null } }

        // ---------------- today
        val d = today
        if (d == null && tab <= 1) Text("Loading today's data…", fontSize = 13.sp)
        else if (tab == 1 && d != null) {
            AllActivityCard(d)
            DnsLogCard(d, blockTick, vpn, onHost = ::blockHost, onAllow = { h -> Blocker.setAllowed(ctx, h, true); Blocker.setHost(ctx, h, false); blockTick++; msg = "$h allowed." })
        } else if (tab == 2) {
            AppsTab(blockTick, busy == null, onWork = { l, b -> work(l, b) }, onManual = { pkg, r -> manual = pkg to r }, onChange = { blockTick++ })
        } else if (tab == 0 && d != null) {
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
        if (tab == 3) {
        FilterListsCard(vpn, blockTick, busy == null, onChange = { blockTick++ },
            onUpdate = { work("Downloading block lists") { com.privacyguard.block.FilterLists.update(ctx, force = true) } })
        FirewallCard(blockTick, busy == null, onChange = { blockTick++ },
            onSync = { work("Applying firewall") { com.privacyguard.block.Firewall.sync(ctx) ?: "Connect Wireless debugging first (Today → Setup, step 2, option B)." } })
        BgBlockCard(blockTick, onChange = { blockTick++ })
        BlockSection(vpn, blockTick, onChange = { blockTick++ }, onRestore = { key ->
            work("Restoring") {
                when (val r = Blocker.restore(ctx, key)) {
                    is Blocker.Result.Manual -> { manual = key.substringBefore('|') to r; null }
                    is Blocker.Result.Done -> { blockTick++; r.msg }
                }
            }
        })
        }

        // ---------------- setup (collapsed)
        if (tab == 0) {
        Box(Modifier.onGloballyPositioned { setupY = it.positionInParent().y.toInt() }) {
        SectionCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Title("Setup")
                    Text("$stepsDone of 4 steps done", fontSize = 12.sp, color = if (stepsDone == 4) GREEN else ORANGE)
                }
                TextButton(onClick = { setupOpen = !showSetup }) { Text(if (showSetup) "Hide" else "Show") }
            }
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
                                "Connected over Wireless debugging. " + (com.privacyguard.block.Firewall.sync(ctx) ?: "")
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
                var clip by remember { mutableStateOf(Prefs.clipAlerts(ctx)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Clipboard alerts", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Hint("Notify when an app reads what you copied while it is not on screen. Your keyboard and system apps are ignored.")
                    }
                    Switch(checked = clip, onCheckedChange = { clip = it; Prefs.setClipAlerts(ctx, it) })
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
        HistoryCard(busy == null) {
            work("Clearing history") { com.privacyguard.History.clear(ctx); "History cleared. Only what happens from now on is recorded." }
        }
        Text("A report is created automatically every night at 23:50 and saved to Download/PrivacyGuard.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Hint("Privacy Guard is free software under GPL-3.0-or-later. Tracker signatures from App Manager by Muntashir Al-Islam " +
            "(github.com/MuntashirAkon/AppManager), based on Exodus Privacy. Block lists by HaGeZi (github.com/hagezi/dns-blocklists). Debloat list: android-debloat-list by Muntashir Al-Islam and contributors (AGPL-3.0).")
        }
        Spacer(Modifier.height(24.dp))
    }
    androidx.compose.material3.SnackbarHost(snack, Modifier.align(Alignment.BottomCenter))
    }
    androidx.compose.material3.NavigationBar(Modifier.testTag("nav")) {
        for ((i, pair) in listOf("Today" to "◉", "Activity" to "≡", "Apps" to "▦", "Blocking" to "⛨").withIndex()) {
            NavigationBarItem(selected = tab == i, onClick = { tab = i },
                icon = { Text(pair.second, fontSize = 18.sp) }, label = { Text(pair.first) })
        }
    }
    }
}

@Composable
private fun HistoryCard(enabled: Boolean, onClear: () -> Unit) {
    val ctx = LocalContext.current
    var ask by remember { mutableStateOf(false) }
    val since = Prefs.since(ctx)
    SectionCard {
        Title("History")
        Hint(if (since > 0) "Recording since " + java.time.Instant.ofEpochMilli(since).atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")) + "."
            else "Includes the last 7 days that Android keeps itself.")
        val size = remember { com.privacyguard.data.Db.get(ctx).sizeBytes(ctx) }
        Hint("Stored on this phone: %.1f MB. Activity is kept 60 days, the DNS log 30 days.".format(size / 1_048_576.0))
        if (!ask) OutlinedButton(onClick = { ask = true }, enabled = enabled) { Text("Clear history") }
        else {
            Text("Delete everything collected so far and start from zero? Earlier activity will not be read again. " +
                "Permissions, pairing and blocking stay. Saved PDFs are not deleted.", fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { ask = false; onClear() }, colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = RED)) { Text("Delete") }
                TextButton(onClick = { ask = false }) { Text("Cancel") }
            }
        }
    }
}

@Composable
internal fun Title(t: String) = Text(t, fontSize = 17.sp, fontWeight = FontWeight.Bold)

@Composable
internal fun Hint(t: String) = Text(t, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
internal fun Chip(t: String, c: Color?, small: Boolean = false) {
    val col = c ?: MaterialTheme.colorScheme.onSurfaceVariant
    Text(t, color = col, fontSize = if (small) 11.sp else 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.background(col.copy(alpha = 0.12f), RoundedCornerShape(50))
            .padding(horizontal = if (small) 8.dp else 10.dp, vertical = 4.dp))
}

@Composable
internal fun Banner(t: String, c: Color?, action: (@Composable () -> Unit)? = null) {
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
        Triple("${d.cases(Sev.CRIT)}", "Critical" + if (d.crit > d.cases(Sev.CRIT)) " (${d.crit} events)" else "", if (d.crit > 0) RED else null),
        Triple("${d.cases(Sev.HIGH)}", "High" + if (d.high > d.cases(Sev.HIGH)) " (${d.high} events)" else "", if (d.high > 0) ORANGE else null),
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
internal fun SectionCard(content: @Composable () -> Unit) {
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
    val an = remember { com.privacyguard.core.AppNames(ctx) }
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
    val an = remember { com.privacyguard.core.AppNames(ctx) }
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
    val an = remember { com.privacyguard.core.AppNames(ctx) }
    SectionCard {
        Title("Blocking")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Block third-party trackers", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Hint("E.g. the Facebook SDK inside another app is blocked, but Instagram inside Instagram is not. System apps are never touched.")
            }
            Switch(checked = trackers, onCheckedChange = { Blocker.setTrackers(ctx, it); onChange() })
        }
        if (!vpn) Banner("Network blocking works through the local VPN, which is off. Turn it on in Today → Setup, step 4.", ORANGE)
        if (apps.isNotEmpty()) { HorizontalDivider(); Text("Apps without internet", fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
        for (a in apps) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(an.name(a), fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                Blocker.setApp(ctx, a, false); onChange()
                kotlin.concurrent.thread { com.privacyguard.block.Firewall.sync(ctx) }
            }) { Text("Remove", fontSize = 12.sp) }
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
    val an = remember { com.privacyguard.core.AppNames(ctx) }
    val hm = remember { java.time.format.DateTimeFormatter.ofPattern("HH:mm") }
    fun t(ts: Long) = java.time.Instant.ofEpochMilli(ts).atZone(java.time.ZoneId.systemDefault()).format(hm)
    SectionCard {
        Title("Data access followed by a tracker")
        Hint("An app used something sensitive and contacted a tracker within 10 minutes. Matched by time only: the app's traffic is encrypted, so the content cannot be seen.")
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

// ======================================================================== Activity tab

private val groupOrder = listOf(
    "mic" to "Microphone", "cam" to "Camera", "loc" to "Location", "personal" to "Contacts & personal data",
    "clip" to "Clipboard", "media" to "Photos & files", "call" to "Calls",
)

@Composable
private fun AllActivityCard(d: DayReport) {
    val ctx = LocalContext.current
    val an = remember { com.privacyguard.core.AppNames(ctx) }
    var sel by remember { mutableStateOf<String?>(null) }
    val byGroup = d.usage.entries.filter { it.value.count > 0 || it.value.reject > 0 }.groupBy { it.key.third }
    SectionCard {
        Title("All activity today")
        Hint("Every access by every app, on screen or not, grouped by what was used.")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            androidx.compose.material3.FilterChip(sel == null, { sel = null }, label = { Text("All") })
            for ((g, l) in groupOrder) if (byGroup[g] != null)
                androidx.compose.material3.FilterChip(sel == g, { sel = g }, label = { Text("$l ${byGroup[g]!!.sumOf { it.value.count }}") })
        }
        if (byGroup.isEmpty()) Text("Nothing recorded yet today.", fontSize = 13.sp)
        for ((g, label) in groupOrder) {
            val rows = byGroup[g] ?: continue
            if (sel != null && sel != g) continue
            Text(label, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
            for ((k, u) in rows.sortedByDescending { it.value.count }.take(if (sel == null) 6 else 50)) {
                HorizontalDivider(Modifier.padding(vertical = 2.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(an.name(k.first), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Hint(k.second + (if (u.first > 0) " · ${hmOf(u.first)}–${hmOf(u.last)}" else "") +
                            (if (u.ms > 0) " · ${fmtDur(u.ms)}" else ""))
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${u.count}×", fontWeight = FontWeight.Bold)
                        if (u.bg > 0) Text("${u.bg} off screen", fontSize = 11.sp, color = ORANGE)
                        if (u.reject > 0) Text("${u.reject} refused", fontSize = 11.sp, color = GREEN)
                    }
                }
            }
            if (sel == null && rows.size > 6) TextButton(onClick = { sel = g }) { Text("Show all ${rows.size}") }
        }
    }
}

private fun hmOf(ts: Long) = java.time.Instant.ofEpochMilli(ts).atZone(java.time.ZoneId.systemDefault())
    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))

@Composable
private fun DnsLogCard(d: DayReport, tick: Int, vpn: Boolean, onHost: (String) -> Unit, onAllow: (String) -> Unit) {
    val ctx = LocalContext.current
    val an = remember { com.privacyguard.core.AppNames(ctx) }
    val blockedHosts = remember(tick) { Blocker.hosts(ctx) }
    var onlyFlagged by remember { mutableStateOf(true) }
    var q by remember { mutableStateOf("") }
    val rows = remember(d, onlyFlagged, q) { d.dns.filter { (!onlyFlagged || it.kind != null || it.blocked > 0) &&
        (q.isBlank() || it.host.contains(q, true) || an.name(it.pkg).contains(q, true)) } }
    SectionCard {
        Title("DNS log")
        Hint("Every server name apps asked for today. Flagged: known trackers, block-list hits, and names with tracking keywords (analytics, telemetry, pixel…).")
        if (!d.hasConnData) { Hint(if (vpn) "No requests recorded yet." else "Turn on the local VPN in Setup to record this."); return@SectionCard }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            androidx.compose.material3.FilterChip(onlyFlagged, { onlyFlagged = true }, label = { Text("Flagged ${d.dns.count { it.kind != null || it.blocked > 0 }}") })
            androidx.compose.material3.FilterChip(!onlyFlagged, { onlyFlagged = false }, label = { Text("All ${d.dns.size}") })
        }
        OutlinedTextField(q, { q = it }, label = { Text("Search app or server") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        var limit by remember(onlyFlagged, q) { mutableIntStateOf(50) }
        for (e in rows.take(limit)) {
            HorizontalDivider(Modifier.padding(vertical = 2.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(e.host, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                    Hint("${an.name(e.pkg)} · ${e.count}× · last ${hmOf(e.last)}")
                    val tag = when {
                        e.tracker != null -> "${e.tracker.company} · ${e.tracker.category}" to RED
                        e.list != null -> "On block list: ${com.privacyguard.block.FilterLists.nameOf(e.list)}" to ORANGE
                        e.keyword != null -> "Keyword: “${e.keyword}”" to ORANGE
                        else -> null
                    }
                    tag?.let { Text(it.first, fontSize = 11.sp, color = it.second) }
                    if (e.blocked > 0) Text("Blocked ${e.blocked}× (${Blocker.reasonLabel(e.why)})", fontSize = 11.sp, color = GREEN)
                }
                if (e.blocked > 0 && e.host !in blockedHosts) TextButton(onClick = { onAllow(e.host) }) { Text("Allow", fontSize = 12.sp) }
                else if (e.host !in blockedHosts) TextButton(onClick = { onHost(e.host) }) { Text("Block", fontSize = 12.sp) }
                else Chip("Blocked", RED, small = true)
            }
        }
        if (rows.size > limit) TextButton(onClick = { limit += 50 }) { Text("Show ${minOf(50, rows.size - limit)} more (${rows.size - limit} left)") }
    }
}

// ======================================================================== Apps tab

@Composable
private fun AppsTab(tick: Int, enabled: Boolean, onWork: (String, suspend () -> String?) -> Unit,
                    onManual: (String, Blocker.Result.Manual) -> Unit, onChange: () -> Unit) {
    val ctx = LocalContext.current
    val an = remember { com.privacyguard.core.AppNames(ctx) }
    // Each section loads on its own and stays in memory until something changes (tick).
    val scores = cached("scores", tick) { com.privacyguard.apps.Score.compute(ctx) }
    val sdks = cached("sdks", tick) {
        com.privacyguard.apps.UserApps.list(ctx).map { it.packageName to com.privacyguard.apps.SdkScanner.of(ctx, it) }
            .filter { it.second.isNotEmpty() }.sortedByDescending { it.second.size }
    }
    val advice = cached("advice", tick) { com.privacyguard.apps.PermissionAdvisor.advise(ctx) }
    val bg = remember(tick) { Blocker.bgApps(ctx) }
    val revoked = remember(tick) { com.privacyguard.apps.PermissionAdvisor.revoked(ctx) }
    var histPkg by remember { mutableStateOf<String?>(null) }
    histPkg?.let { AppHistoryDialog(it) { histPkg = null } }
    SecurityCard(tick)
    var allScores by remember { mutableStateOf(false) }
    SectionCard {
        Title("Privacy score · last 7 days")
        Hint("Tap an app for its last 30 days. 100 is clean. Points are lost for alerts, tracker companies contacted, tracker SDKs inside and heavy background uploads.")
        val sc = scores
        if (sc == null) Hint("Calculating…")
        for (s in if (allScores) sc.orEmpty() else sc.orEmpty().take(10)) {
            HorizontalDivider(Modifier.padding(vertical = 2.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(48.dp)) { Chip("${s.score}", when { s.score < 50 -> RED; s.score < 80 -> ORANGE; else -> GREEN }) }
                Column(Modifier.weight(1f).clickable { histPkg = s.pkg }) {
                    Text(an.name(s.pkg), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Hint(listOfNotNull(
                        s.crit.takeIf { it > 0 }?.let { "$it critical" }, s.high.takeIf { it > 0 }?.let { "$it high" },
                        s.companies.size.takeIf { it > 0 }?.let { "$it tracker companies" }, s.sdks.takeIf { it > 0 }?.let { "$it SDKs" },
                        s.bgMb.takeIf { it > 0 }?.let { "$it MB in background" },
                    ).joinToString(" · ").ifEmpty { "Nothing suspicious" })
                }
                val on = s.pkg in bg
                TextButton(onClick = { Blocker.setBgApp(ctx, s.pkg, !on); onChange() }) {
                    Text(if (on) "Internet limited" else "Limit internet", fontSize = 11.sp, color = if (on) RED else MaterialTheme.colorScheme.primary)
                }
            }
        }
        if ((sc?.size ?: 0) > 10) TextButton(onClick = { allScores = !allScores }) { Text(if (allScores) "Show less" else "Show all ${sc!!.size}") }
        Hint("“Limit internet” lets the app go online only while it is on screen.")
    }

    SectionCard {
        Title("Tracker SDKs inside apps")
        Hint("Found in each app's manifest and, after a deep scan, in its code (about 1,000 signatures from App Manager and Exodus Privacy). An SDK inside does not mean it is active, but it can send data whenever the app runs.")
        val deep = com.privacyguard.apps.DeepScanWorker
        var prog by remember { mutableStateOf(deep.progress(ctx)) }
        LaunchedEffect(prog.first) { while (prog.first) { kotlinx.coroutines.delay(1500); prog = deep.progress(ctx); if (!prog.first) onChange() } }
        if (prog.first) {
            androidx.compose.material3.LinearProgressIndicator(progress = { if (prog.third > 0) prog.second / prog.third.toFloat() else 0f }, modifier = Modifier.fillMaxWidth())
            Hint("Deep scan: ${prog.second} of ${prog.third} apps. You can leave this screen.")
        } else {
            val at = deep.lastRun(ctx)
            OutlinedButton(onClick = { deep.start(ctx); prog = Triple(true, 0, prog.third) }) {
                Text(if (at == 0L) "Deep scan (reads each app's code)" else "Deep scan again")
            }
            if (at > 0) Hint("Last deep scan: " + java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm")))
        }
        val sd = sdks
        if (sd == null) Hint("Checking apps…")
        else if (sd.isEmpty()) Text("No known tracker SDKs found.", fontSize = 13.sp, color = GREEN)
        for ((pkg, list) in sd.orEmpty().take(25)) {
            HorizontalDivider(Modifier.padding(vertical = 2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(an.name(pkg), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Chip("${list.size}", if (list.size >= 5) RED else ORANGE, small = true)
            }
            Hint(list.joinToString { it.name })
        }
    }

    SectionCard {
        Title("Permission advisor")
        Hint("Permissions an app holds but has not used, or that do not fit the kind of app. Removing one is safe: the app asks again if it needs it.")
        var weak by remember { mutableStateOf(false) }
        val gone = remember { androidx.compose.runtime.mutableStateListOf<String>() }
        val adv = advice.orEmpty()
        val strong = adv.filter { it.strong }
        val shown = (if (weak) adv else strong).filter { "${it.pkg}|${it.group.id}" !in revoked && "${it.pkg}|${it.group.id}" !in gone }
        if (advice == null) Hint("Checking permissions…")
        else if (shown.isEmpty()) Text("Nothing clear to suggest yet.", fontSize = 13.sp, color = GREEN)
        for (a in shown.take(40)) {
            HorizontalDivider(Modifier.padding(vertical = 2.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${an.name(a.pkg)} · ${a.group.label}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(a.reason, fontSize = 12.sp, color = if (a.strong) ORANGE else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(enabled = enabled, onClick = {
                    onWork("Removing permission") {
                        when (val r = com.privacyguard.apps.PermissionAdvisor.revoke(ctx, a)) {
                            is Blocker.Result.Manual -> { onManual(a.pkg, r); null }
                            is Blocker.Result.Done -> { if (!r.msg.startsWith("Failed")) gone += "${a.pkg}|${a.group.id}"; onChange(); r.msg }
                        }
                    }
                }) { Text("Remove", fontSize = 12.sp) }
            }
        }
        val nWeak = adv.size - strong.size
        if (nWeak > 0) TextButton(onClick = { weak = !weak }) {
            Text(if (weak) "Hide permissions that just haven't been used yet" else "Show $nWeak more (not used yet, still watching)")
        }
        if (revoked.isNotEmpty()) { HorizontalDivider(); Text("Removed by you", fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
        for (k in revoked.keys) Row(verticalAlignment = Alignment.CenterVertically) {
            val g = com.privacyguard.apps.PermGroups.all.firstOrNull { it.id == k.substringAfter('|') }?.label ?: k.substringAfter('|')
            Text("${an.name(k.substringBefore('|'))} · $g", fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                onWork("Restoring permission") {
                    when (val r = com.privacyguard.apps.PermissionAdvisor.restore(ctx, k)) {
                        is Blocker.Result.Manual -> { onManual(k.substringBefore('|'), r); null }
                        is Blocker.Result.Done -> { onChange(); r.msg }
                    }
                }
            }) { Text("Restore", fontSize = 12.sp) }
        }
    }
    AppChangesCard(tick)
    InspectCard()
    DebloatCard(tick, enabled, onWork, onManual, onChange)
}

// ======================================================================== Blocking tab

@Composable
private fun FilterListsCard(vpn: Boolean, tick: Int, enabled: Boolean, onChange: () -> Unit, onUpdate: () -> Unit) {
    val ctx = LocalContext.current
    val fl = com.privacyguard.block.FilterLists
    SectionCard {
        Title("Block lists")
        Hint("Blocks ads, trackers and dangerous sites for every app, at the DNS level. Facebook, Instagram, WhatsApp and every app's own servers are never blocked by a list. Lists update weekly.")
        if (!vpn) Banner("Lists work through the local VPN, which is off. Turn it on in Today → Setup, step 4.", ORANGE)
        for (d in fl.all) {
            val on = remember(tick) { fl.enabled(ctx, d.id) }
            HorizontalDivider(Modifier.padding(vertical = 2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(d.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Hint(d.desc)
                    val n = remember(tick) { fl.count(ctx, d.id) }
                    val t = remember(tick) { fl.updatedAt(ctx, d.id) }
                    Text(if (n > 0) "${"%,d".format(n)} domains · updated " + java.time.Instant.ofEpochMilli(t).atZone(java.time.ZoneId.systemDefault())
                        .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm")) else "Not downloaded yet",
                        fontSize = 11.sp, color = if (n > 0) GREEN else ORANGE)
                }
                Switch(checked = on, onCheckedChange = { fl.setEnabled(ctx, d.id, it); onChange(); if (it && fl.count(ctx, d.id) == 0) onUpdate() })
            }
        }
        remember(tick) { fl.lastError(ctx) }?.let { Text("Last update problem: $it", fontSize = 11.sp, color = RED) }
        OutlinedButton(onClick = onUpdate, enabled = enabled) { Text("Update lists now") }
        Hint("Something stopped working? Open Activity → DNS log, find the blocked server and tap Allow.")
    }
}

@Composable
private fun BgBlockCard(tick: Int, onChange: () -> Unit) {
    val ctx = LocalContext.current
    val an = remember { com.privacyguard.core.AppNames(ctx) }
    val bg = remember(tick) { Blocker.bgApps(ctx).sorted() }
    SectionCard {
        Title("Internet only while on screen")
        Hint("These apps cannot reach the internet when they are not on screen. Add apps from the Apps tab with “Limit internet”. Music or navigation apps would stop in the background.")
        if (bg.isEmpty()) Text("No apps limited.", fontSize = 13.sp)
        for (p in bg) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(an.name(p), fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { Blocker.setBgApp(ctx, p, false); onChange() }) { Text("Remove", fontSize = 12.sp) }
        }
    }
}

@Composable
private fun FirewallCard(tick: Int, enabled: Boolean, onChange: () -> Unit, onSync: () -> Unit) {
    val ctx = LocalContext.current
    val fw = com.privacyguard.block.Firewall
    val applied = remember(tick) { fw.applied(ctx) }
    val want = remember(tick) { Blocker.apps(ctx) }
    val pending = remember(tick) { fw.pending(ctx) }
    val anti = remember(tick) { Blocker.antiBypass(ctx) }
    SectionCard {
        Title("Firewall")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Firewall for “Apps without internet”", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Hint("Android's own kernel firewall cuts ALL traffic of those apps, including hardcoded IPs and their own encrypted DNS. Needs Wireless debugging to switch on; after a restart it is applied again on the next Reconnect.")
            }
        }
        when {
            want.isEmpty() -> Text("No apps blocked. Use Block… → Block this app's internet on an alert.", fontSize = 12.sp)
            !pending -> Text("Active for ${applied.size} app(s).", fontSize = 12.sp, color = GREEN)
            else -> Text("Not active for ${want.size - applied.intersect(want).size} app(s) (DNS block only for now).", fontSize = 12.sp, color = ORANGE)
        }
        if (want.isNotEmpty()) OutlinedButton(onClick = onSync, enabled = enabled) { Text(if (pending) "Apply firewall now" else "Re-apply") }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Stop DNS bypass", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Hint("Apps that talk straight to 8.8.8.8, 1.1.1.1, Quad9 and other public DNS, or use encrypted DNS (DoH/DoT), are sent back to the phone's DNS, so blocking and the DNS log still see them. Turned off automatically if Private DNS is set to a hostname. Takes effect when the VPN restarts.")
            }
            Switch(checked = anti, onCheckedChange = { Blocker.setAntiBypass(ctx, it); onChange() })
        }
    }
}
