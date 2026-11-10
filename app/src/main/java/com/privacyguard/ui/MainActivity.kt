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
            val r = withContext(Dispatchers.IO) { runCatching { block() }.getOrElse { "Gabim: ${it.message}" } }
            busy = null; msg = r; refresh++
            // mesazhi lart s'duket kur je poshte te konfigurimi
            if (!r.isNullOrBlank()) android.widget.Toast.makeText(ctx, r, android.widget.Toast.LENGTH_LONG).show()
        }
    }
    fun limit(pkg: String, op: String) = work("Po kufizoj lejen") {
        when (val r = Blocker.limit(ctx, pkg, op)) {
            is Blocker.Result.Manual -> { manual = pkg to r; null }
            is Blocker.Result.Done -> { blockTick++; r.msg }
        }
    }
    fun blockNet(pkg: String) {
        Blocker.setApp(ctx, pkg, true); blockTick++
        msg = if (vpn) "Interneti i app-it u bllokua (DNS). Mund të duhen disa minuta që të kalojë cache-i."
        else "U shënua. Bllokimi punon vetëm kur VPN-ja lokale (hapi 4) është ndezur."
    }
    fun blockHost(h: String) {
        Blocker.setHost(ctx, h, true); blockTick++
        msg = "$h u bllokua" + if (vpn) "." else " (ndiz VPN-në lokale që të ketë efekt)."
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

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Privacy Guard", fontSize = 24.sp, fontWeight = FontWeight.Bold)

        // ---------------- menyra
        SectionCard {
            Text("Mënyra e punës", fontWeight = FontWeight.SemiBold)
            val m = mode
            Text(m?.label ?: "Po kontrolloj…", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                color = if (m == Mode.LIMITED) ORANGE else MaterialTheme.colorScheme.primary)
            if (m != null) Text(m.desc, fontSize = 13.sp)
        }

        busy?.let { Text("$it…", color = MaterialTheme.colorScheme.primary) }
        msg?.let { Text(it, fontSize = 13.sp) }
        manual?.let { (pkg, r) -> ManualCard(pkg, r) { manual = null } }

        // ---------------- sot
        today?.let { d -> TodayCard(d, onLimit = ::limit, onNet = ::blockNet) }
        today?.let { d -> FlowsCard(d) }
        today?.let { d -> TrackersCard(d, blockTick, onHost = ::blockHost) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                work("Po mbledh") { val r = Collector.snapshot(ctx); "U mblodhën ${r.accessNew} qasje të reja (${r.mode.label}). ${r.note ?: ""}" }
            }, enabled = busy == null) { Text("Mblidh tani") }
            OutlinedButton(onClick = {
                work("Po krijoj PDF-në") {
                    runCatching { Collector.snapshot(ctx) }
                    val d = LocalDate.now()
                    Reports.create(ctx, listOf(d), "raporti_$d.pdf")?.let { openPdf(ctx, it) }
                    "PDF-ja u ruajt te Download/PrivacyGuard"
                }
            }, enabled = busy == null) { Text("PDF i sotëm") }
        }
        OutlinedButton(onClick = {
            work("Po krijoj PDF-në e javës") {
                val days = (6 downTo 0).map { LocalDate.now().minusDays(it.toLong()) }
                Reports.create(ctx, days, "raporti_7_dite_${LocalDate.now()}.pdf")?.let { openPdf(ctx, it) }
                "PDF-ja e 7 ditëve u ruajt te Download/PrivacyGuard"
            }
        }, enabled = busy == null) { Text("PDF i 7 ditëve") }

        HorizontalDivider()
        Text("Konfigurimi", fontSize = 18.sp, fontWeight = FontWeight.Bold)

        // ---------------- 1. usage access
        Step(
            "1. Qasja te përdorimi i app-eve", usage,
            "Duhet për të ditur sa të dhëna dërgon çdo app dhe cili app është në ekran.",
        ) {
            TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("Hap Settings") }
        }

        // ---------------- 2. menyra e plote
        Step(
            "2. Mënyra e plotë", mode == Mode.DIRECT || mode == Mode.ADB,
            "Lejon leximin e historikut të plotë: kush, kur, sa gjatë, në ekran apo në background.",
        ) {
            Text("Mënyra A – nga kompjuteri, një herë (me USB ose Wi-Fi):", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            val cmd = "adb shell pm grant ${Shell.SELF} android.permission.DUMP"
            SelectionContainer {
                Text(cmd, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)).padding(8.dp))
            }
            TextButton(onClick = {
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("cmd", cmd))
            }) { Text("Kopjo komandën") }
            Spacer(Modifier.height(6.dp))
            Text("Mënyra B – pa kompjuter, me Wireless debugging:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text("1) Shtyp “Fillo”. 2) Te Developer options ndiz Wireless debugging → Pair device with pairing code. " +
                "3) Shkruaj kodin te njoftimi i Privacy Guard, pa dalë nga Settings.", fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    Pairing.start(ctx)
                    runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                }) { Text("Fillo") }
                OutlinedButton(onClick = {
                    work("Po lidhem") {
                        if (Shell.adbConnected(ctx, true)) {
                            android.util.Log.i("PG.Shell", "Rilidhu: u lidh\n" + Shell.selfGrant(ctx))
                            "U lidh me Wireless debugging."
                        } else "S'u lidh. A është ndezur Wireless debugging?"
                    }
                }) { Text("Rilidhu") }
            }
            ManualPair { port, code -> work("Po çiftoj") { Pairing.pairAndSetup(ctx, port, code) } }
        }

        // ---------------- 3. monitorimi
        Step("3. Monitorimi i vazhdueshëm", monitor,
            "Shënon kur ekrani është i fikur dhe të njofton menjëherë nëse mikrofoni ose kamera ndizen atëherë.") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = monitor, onCheckedChange = {
                    monitor = it; Prefs.setMonitor(ctx, it)
                    if (it) MonitorService.start(ctx) else MonitorService.stop(ctx)
                })
                Spacer(Modifier.width(8.dp)); Text(if (monitor) "Ndezur" else "Fikur")
            }
        }

        // ---------------- 4. vpn
        Step("4. Regjistro adresat (VPN lokale)", vpn,
            "Shikon cilët servera kontakton çdo app dhe i krahason me listën e brokerëve dhe trackerave. " +
                "Kalon vetëm DNS; trafiku yt nuk largohet nga telefoni. Për rezultate më të mira, vendos Private DNS në “Off”.") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = vpn, onCheckedChange = {
                    if (it) {
                        val i = VpnService.prepare(ctx)
                        if (i != null) vpnLauncher.launch(i) else { DnsVpnService.start(ctx); Prefs.setVpn(ctx, true); vpn = true }
                    } else { DnsVpnService.stop(ctx); Prefs.setVpn(ctx, false); vpn = false }
                })
                Spacer(Modifier.width(8.dp)); Text(if (vpn) "Ndezur" else "Fikur")
            }
        }
        // ---------------- 5. bllokimi
        BlockSection(vpn, blockTick, onChange = { blockTick++ }, onRestore = { key ->
            work("Po rikthej") {
                when (val r = Blocker.restore(ctx, key)) {
                    is Blocker.Result.Manual -> { manual = key.substringBefore('|') to r; null }
                    is Blocker.Result.Done -> { blockTick++; r.msg }
                }
            }
        })
        Text("Raporti krijohet vetë çdo natë në 23:50 dhe ruhet te Download/PrivacyGuard.", fontSize = 12.sp)
        Spacer(Modifier.height(24.dp))
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
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    }
}

@Composable
private fun Step(title: String, done: Boolean, desc: String, content: @Composable () -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (done) "✓" else "•", color = if (done) Color(0xFF2E7D32) else ORANGE, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.width(8.dp))
            Text(title, fontWeight = FontWeight.SemiBold)
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
    TextButton(onClick = { open = !open }) { Text(if (open) "Mbyll" else "Njoftimi s'del? Shkruaje vetë") }
    if (open) {
        Text("Përdor ndarjen e ekranit (split screen) që të shohësh Settings dhe këtë app njëkohësisht.", fontSize = 12.sp)
        OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Porta (p.sh. 37145)") }, singleLine = true)
        OutlinedTextField(code, { code = it.filter(Char::isDigit) }, label = { Text("Kodi 6-shifror") }, singleLine = true)
        Button(onClick = { port.toIntOrNull()?.let { onPair(it, code) } }, enabled = port.isNotEmpty() && code.length == 6) { Text("Çifto") }
    }
}

@Composable
private fun TodayCard(d: DayReport, onLimit: (String, String) -> Unit, onNet: (String) -> Unit) {
    SectionCard {
        Text("Sot", fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Kpi("${d.crit}", "Kritike", if (d.crit > 0) RED else null)
            Kpi("${d.high}", "Të larta", if (d.high > 0) ORANGE else null)
            Kpi("${d.micN}×", "Mikrofon", null)
            Kpi("${d.camN}×", "Kamera", null)
            Kpi(fmtBytes(d.txBg), "Dërguar bg", null)
        }
        val names = d.alerts.groupBy { Triple(it.e.pkg, it.label, it.sev) }.entries.take(12)
        if (names.isEmpty()) Text("Asnjë qasje e dyshimtë sot.", fontSize = 13.sp)
        val an = Analyzer(LocalContext.current).names
        for ((k, list) in names) {
            val (pkg, label, sev) = k
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Box(Modifier.width(76.dp)) {
                    Text(sev.label, color = Color(sev.color), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Column {
                    Text("${an.name(pkg)} · $label" + if (list.size > 1) " ×${list.size}" else "", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(list.first().why + if (list.sumOf { it.e.durationMs } > 0) " (${fmtDur(list.sumOf { it.e.durationMs })})" else "", fontSize = 12.sp)
                    BlockMenu(label, onLimit = { onLimit(pkg, list.first().e.op) }, onNet = { onNet(pkg) })
                }
            }
        }
    }
}

@Composable
private fun Kpi(v: String, l: String, c: Color?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(v, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = c ?: MaterialTheme.colorScheme.onSurface)
        Text(l, fontSize = 11.sp)
    }
}


@Composable
private fun BlockMenu(label: String, onLimit: () -> Unit, onNet: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    if (!open) { TextButton(onClick = { open = true }) { Text("Blloko…", fontSize = 12.sp) }; return }
    Column {
        TextButton(onClick = { open = false; onLimit() }) { Text("$label: lejo vetëm kur e përdor", fontSize = 12.sp) }
        TextButton(onClick = { open = false; onNet() }) { Text("Blloko internetin e këtij app-i", fontSize = 12.sp) }
        TextButton(onClick = { open = false }) { Text("Anulo", fontSize = 12.sp) }
    }
}

@Composable
private fun ManualCard(pkg: String, r: Blocker.Result.Manual, onClose: () -> Unit) {
    val ctx = LocalContext.current
    SectionCard {
        Text("Bëje vetë", fontWeight = FontWeight.SemiBold)
        Text(r.msg, fontSize = 13.sp)
        if (r.commands.isNotEmpty()) SelectionContainer {
            Text(r.commands.joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)).padding(8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { Blocker.openAppSettings(ctx, pkg) }) { Text("Hap Settings") }
            if (r.commands.isNotEmpty()) OutlinedButton(onClick = {
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("cmd", r.commands.joinToString("\n")))
            }) { Text("Kopjo") }
            TextButton(onClick = onClose) { Text("Mbyll") }
        }
    }
}

@Composable
private fun TrackersCard(d: DayReport, tick: Int, onHost: (String) -> Unit) {
    val ctx = LocalContext.current
    val blocked = remember(tick) { Blocker.hosts(ctx) }
    // host -> (kompania, app-et)
    val hosts = d.trackers.flatMap { (pkg, m) -> m.flatMap { (t, u) -> u.hosts.map { Triple(it, t.company, pkg) } } }
        .groupBy { it.first }.entries.sortedByDescending { it.value.size }.take(10)
    if (hosts.isEmpty()) return
    val an = remember { Analyzer(ctx).names }
    SectionCard {
        Text("Trackera sot", fontWeight = FontWeight.SemiBold)
        for ((h, l) in hosts) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(h, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("${l.first().second} · " + l.map { an.name(it.third) }.distinct().take(3).joinToString(), fontSize = 12.sp)
                }
                if (h in blocked) Text("Bllokuar", fontSize = 12.sp, color = RED)
                else TextButton(onClick = { onHost(h) }) { Text("Blloko", fontSize = 12.sp) }
            }
        }
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
    Step("5. Bllokimi", trackers || apps.isNotEmpty() || hosts.isNotEmpty() || ops.isNotEmpty(),
        "Ndal trackerat dhe app-et që sillen keq. Bllokimi i rrjetit punon përmes VPN-së lokale" +
            (if (vpn) "." else " – ndize te hapi 4.") +
            " Kufizimi i lejeve (\"lejo vetëm kur e përdor\") kërkon Wireless debugging; përndryshe hapet Settings.") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = trackers, onCheckedChange = { Blocker.setTrackers(ctx, it); onChange() })
            Spacer(Modifier.width(8.dp))
            Text("Blloko trackerat e palëve të treta", fontSize = 13.sp)
        }
        Text("P.sh. Facebook SDK te një app tjetër bllokohet, por Instagram-i te vetë Instagram-i jo. App-et e sistemit nuk preken.", fontSize = 12.sp)
        if (apps.isNotEmpty()) Text("App-e pa internet", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        for (a in apps) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(an.name(a), fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { Blocker.setApp(ctx, a, false); onChange() }) { Text("Hiq", fontSize = 12.sp) }
        }
        if (hosts.isNotEmpty()) Text("Domene të bllokuara", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        for (h in hosts) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(h, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { Blocker.setHost(ctx, h, false); onChange() }) { Text("Hiq", fontSize = 12.sp) }
        }
        if (ops.isNotEmpty()) Text("Leje të kufizuara", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        for ((k, prev) in ops) Row(verticalAlignment = Alignment.CenterVertically) {
            val (pkg, op) = k.split('|', limit = 2)
            Text("${an.name(pkg)} · ${com.privacyguard.core.Ops.map[op]?.first ?: op}", fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { onRestore(k) }) { Text("Rikthe", fontSize = 12.sp) }
        }
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
        Text("Kush çfarë mori dhe kujt ia çoi", fontWeight = FontWeight.SemiBold)
        Text("Lidhja bëhet sipas kohës (brenda 10 min pas marrjes), jo sipas përmbajtjes.", fontSize = 11.sp)
        for (f in d.flows.take(8)) {
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text(an.name(f.pkg), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            if (f.took.isNotEmpty()) Text("Mori: " + f.took.entries.joinToString { (k, n) ->
                k + (if (n > 1) " ×$n" else "") + if (k in f.bgTook) " (background)" else "" }, fontSize = 12.sp)
            Text("Ia çoi: " + f.to.entries.sortedByDescending { it.value.count }
                .joinToString { (tr, u) -> "${tr.company} (${tr.category}) ×${u.count}" }, fontSize = 12.sp)
            for (l in f.links) Text("• ${t(l.ts)} ${l.what} → ${l.company} pas ${fmtDur(l.afterMs)} (${l.host})",
                fontSize = 12.sp, color = ORANGE)
            if (f.blocked > 0) Text("U bllokuan ${f.blocked} kërkesa", fontSize = 12.sp, color = Color(0xFF2E7D32))
        }
    }
}
