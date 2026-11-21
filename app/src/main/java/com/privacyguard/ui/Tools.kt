package com.privacyguard.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privacyguard.apps.Debloat
import com.privacyguard.block.Blocker
import com.privacyguard.core.Analyzer
import com.privacyguard.inspect.Inspect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun DebloatCard(tick: Int, enabled: Boolean, onWork: (String, suspend () -> String?) -> Unit,
                         onManual: (String, Blocker.Result.Manual) -> Unit, onChange: () -> Unit) {
    val ctx = localContext()
    val items = cached("debloat", tick) { Debloat.items(ctx) }
    var advanced by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf(false) }
    var q by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<String?>(null) }
    var showAll by remember { mutableStateOf(false) }
    SectionCard {
        Title("Debloater")
        Hint("Removes pre-installed apps you don't use, only for your user. Nothing is deleted from the phone: every removal can be restored here, and a factory reset brings everything back. Descriptions come from the android-debloat-list community project.")
        val all = items
        if (all == null) { Hint("Loading list…"); return@SectionCard }
        val removed = all.filter { it.removed }
        val pool = all.filter { !it.removed && (advanced || it.e.safe) }
        val shown = pool.filter { q.isBlank() || it.name.contains(q, true) || it.e.id.contains(q, true) || it.e.desc.contains(q, true) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${all.count { it.e.safe && !it.removed }} safe to remove · ${removed.size} removed", fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("Advanced", fontSize = 12.sp); Spacer(Modifier.width(6.dp))
            Switch(advanced, { if (it) ask = true else advanced = false })
        }
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Search apps") })
        for (it in if (showAll || q.isNotBlank()) shown.take(300) else shown.take(25)) {
            HorizontalDivider(Modifier.padding(vertical = 2.dp))
            DebloatRow(it, open == it.e.id, { open = if (open == it.e.id) null else it.e.id }) {
                TextButton(enabled = enabled, onClick = {
                    onWork("Removing ${it.name}") {
                        when (val r = Debloat.remove(ctx, it.e.id)) {
                            is Blocker.Result.Manual -> { onManual(it.e.id, r); null }
                            is Blocker.Result.Done -> { onChange(); r.msg }
                        }
                    }
                }) { Text("Remove", fontSize = 12.sp, color = if (it.e.safe) MaterialTheme.colorScheme.primary else RED) }
            }
        }
        if (q.isBlank() && shown.size > 25) TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Show less" else "Show all ${shown.size}") }
        if (removed.isNotEmpty()) { HorizontalDivider(); Text("Removed (${removed.size})", fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
        for (it in removed) DebloatRow(it, open == it.e.id, { open = if (open == it.e.id) null else it.e.id }) {
            TextButton(enabled = enabled, onClick = {
                onWork("Restoring ${it.name}") {
                    when (val r = Debloat.restore(ctx, it.e.id)) {
                        is Blocker.Result.Manual -> { onManual(it.e.id, r); null }
                        is Blocker.Result.Done -> { onChange(); r.msg }
                    }
                }
            }) { Text(if (it.system) "Restore" else "Reinstall", fontSize = 12.sp) }
        }
        Hint("List: android-debloat-list by Muntashir Al-Islam and contributors, AGPL-3.0.")
    }
    if (ask) AlertDialog(onDismissRequest = { ask = false },
        title = { Text("Show risky apps?") },
        text = { Text("Advanced adds apps marked Caution or Unsafe. Removing them can break calls, the camera, updates, or even stop the phone from starting until a factory reset. Read each warning first.") },
        confirmButton = { TextButton(onClick = { advanced = true; ask = false }) { Text("I understand", color = RED) } },
        dismissButton = { TextButton(onClick = { ask = false }) { Text("Cancel") } })
}

@Composable
private fun DebloatRow(it: Debloat.Item, expanded: Boolean, toggle: () -> Unit, action: @Composable () -> Unit) {
    val (lbl, c) = when (it.e.removal) {
        "delete" -> "Safe" to GREEN; "replace" -> "Replace" to GREEN; "caution" -> "Caution" to ORANGE; else -> "Unsafe" to RED
    }
    Column(Modifier.fillMaxWidth().clickable(onClick = toggle)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(it.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Hint(it.e.id)
            }
            Chip(lbl, c, small = true)
            action()
        }
        if (expanded) {
            Text(it.e.desc.ifBlank { "No description." }, fontSize = 12.sp)
            it.e.warning?.let { w -> Text("Warning: $w", fontSize = 12.sp, color = RED) }
            if (it.e.requiredBy.isNotEmpty()) Text("Needed by: ${it.e.requiredBy.joinToString()}", fontSize = 12.sp, color = ORANGE)
            if (it.e.deps.isNotEmpty()) Hint("Depends on: ${it.e.deps.joinToString()}")
            if (it.e.removal == "replace") Hint("Install an alternative before removing it.")
        }
    }
}

@Composable
internal fun InspectCard() {
    val ctx = localContext()
    val an = remember { com.privacyguard.core.AppNames(ctx) }
    var q by remember { mutableStateOf("") }
    var system by remember { mutableStateOf(false) }
    val apps = cached("inspect:$system", 0) {
        ctx.packageManager.getInstalledApplications(0)
            .filter { system || it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 }
            .map { it.packageName }.sortedBy { an.name(it).lowercase() }
    }.orEmpty()
    var pick by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var view by remember { mutableStateOf<Pair<String, String>?>(null) }
    var summary by remember { mutableStateOf<Inspect.Manifest?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    val p = pick
    LaunchedEffect(p) { summary = null; err = null; if (p != null) summary = withContext(Dispatchers.IO) { runCatching { Inspect.manifest(ctx, p) }.onFailure { err = it.message }.getOrNull() } }
    SectionCard {
        Title("Inspect an app (reverse engineering)")
        Hint("Opens an app's package without running it: decodes its manifest (what it asks for and exposes) and finds the servers and keys written in its code. Read only.")
        if (p == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(q, { q = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Find an app") })
                Spacer(Modifier.width(6.dp)); Text("System", fontSize = 12.sp); Switch(system, { system = it })
            }
            val list = apps.filter { q.isBlank() || an.name(it).contains(q, true) || it.contains(q, true) }
            for (a in list.take(if (q.isBlank()) 8 else 40)) {
                HorizontalDivider(Modifier.padding(vertical = 2.dp))
                Column(Modifier.fillMaxWidth().clickable { pick = a }) {
                    Text(an.name(a), fontSize = 14.sp, fontWeight = FontWeight.SemiBold); Hint(a)
                }
            }
            if (q.isBlank() && list.size > 8) Hint("Type to search ${list.size} apps.")
            return@SectionCard
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(an.name(p), fontSize = 15.sp, fontWeight = FontWeight.Bold); Hint(p) }
            TextButton(onClick = { pick = null }) { Text("Change") }
        }
        err?.let { Text("Could not read: $it", color = RED, fontSize = 12.sp) }
        val m = summary
        if (m == null && err == null) Hint("Decoding manifest…")
        if (m != null) {
            for (f in m.flags) Text((if (f.bad) "• " else "· ") + f.text, fontSize = 12.sp, color = if (f.bad) ORANGE else MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${m.dangerous.size} dangerous permissions · ${m.exported.size} open to other apps · ${m.links.size} links", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Row {
                OutlinedButton(onClick = { view = "Report · ${an.name(p)}" to Inspect.manifestReport(an.name(p), p, m) }) { Text("Report", fontSize = 12.sp) }
                Spacer(Modifier.width(6.dp))
                OutlinedButton(onClick = { view = "AndroidManifest.xml · ${an.name(p)}" to m.xml }) { Text("Manifest", fontSize = 12.sp) }
            }
        }
        if (busy != null) { LinearProgressIndicator(Modifier.fillMaxWidth()); Hint(busy!!) }
        else Button(onClick = { busy = "Reading every string in its code. Big apps take up to a minute…" }) { Text("Find servers & keys") }
        Hint("Keys found are usually public client keys that every copy of the app contains; only private keys or AWS secrets are real leaks.")
    }
    if (busy != null && p != null) LaunchedEffect(busy) {
        val s = withContext(Dispatchers.IO) { runCatching { Inspect.stringsReport(an.name(p), p, Inspect.strings(ctx, p)) }.getOrElse { "Failed: ${it.message}" } }
        busy = null
        view = "Servers & keys · ${an.name(p)}" to s
    }
    view?.let { (t, s) -> CodeViewer(t, s) { view = null } }
}

@Composable
private fun localContext() = androidx.compose.ui.platform.LocalContext.current

/** Tab results are kept in memory: switching tabs doesn't recompute them, only when `tick` changes. */
internal object TabCache {
    private val m = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Any?>>()
    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String, tick: Int): T? = m[key]?.takeIf { it.first == tick }?.second as T?
    @Suppress("UNCHECKED_CAST")
    fun <T> any(key: String): T? = m[key]?.second as T?
    fun put(key: String, tick: Int, v: Any?) { m[key] = tick to v }
}

/** Value loaded on IO; shows the old value until the new one arrives. */
@Composable
internal fun <T> cached(key: String, tick: Int, load: () -> T): T? {
    var v by remember { mutableStateOf(TabCache.any<T>(key)) }
    LaunchedEffect(key, tick) {
        TabCache.get<T>(key, tick)?.let { v = it; return@LaunchedEffect }
        // An error in one section (e.g. the system not answering) must not crash the app.
        val r = withContext(Dispatchers.IO) { runCatching { load() }.onFailure { android.util.Log.w("PG.UI", "load $key", it) }.getOrNull() }
        if (r != null) { TabCache.put(key, tick, r); v = r }
    }
    return v
}

@Composable
internal fun SecurityCard(tick: Int) {
    val ctx = localContext()
    val an = remember { com.privacyguard.core.AppNames(ctx) }
    val r = cached("security", tick) { com.privacyguard.apps.Security.scan(ctx) }
    var all by remember { mutableStateOf(false) }
    SectionCard {
        Title("Spyware check")
        Hint("Apps with powers that allow watching you: reading the screen, notifications or what you type, controlling the device. Spyware usually combines these with a hidden icon or an install from outside an app store. Most matches are normal apps; check that you know each one.")
        if (r == null) { Hint("Checking…"); return@SectionCard }
        val risky = r.apps.filter { it.risky }
        if (risky.isEmpty()) Text("No app combines a hidden icon or unknown source with surveillance powers.", fontSize = 13.sp, color = GREEN)
        else Text("${risky.size} app(s) worth a close look", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = RED)
        for (a in if (all) r.apps else risky) {
            HorizontalDivider(Modifier.padding(vertical = 2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(an.name(a.pkg), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (a.risky) RED else Color.Unspecified)
                    Hint(a.powers.sortedByDescending { it.weight }.joinToString(" · ") { it.label })
                    Text(a.powers.sortedByDescending { it.weight }.take(2).joinToString("; ") { it.why }.replaceFirstChar { it.uppercase() } + ".", fontSize = 12.sp)
                }
                TextButton(onClick = {
                    runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:${a.pkg}"))) }
                }) { Text("Settings", fontSize = 12.sp) }
            }
        }
        val others = r.apps.size - risky.size
        if (others > 0) TextButton(onClick = { all = !all }) { Text(if (all) "Show only the risky ones" else "Show $others more apps with powerful access") }
        if (r.userCerts.isNotEmpty()) {
            HorizontalDivider()
            Text("Certificates you installed: ${r.userCerts.joinToString()}", fontSize = 13.sp, color = ORANGE, fontWeight = FontWeight.SemiBold)
            Hint("A certificate added by a user, an employer or an app can be used to read encrypted (HTTPS) traffic. Remove it if you don't know why it is there: Settings → Security → Other security settings → User certificates.")
        }
        if (!r.full) Hint("With full access (Setup, step 2) this also checks which apps can draw over others, install apps or see your app usage.")
    }
}

@Composable
internal fun AppChangesCard(tick: Int) {
    val ctx = localContext()
    val an = remember { com.privacyguard.core.AppNames(ctx) }
    val list = cached("changes", tick) { com.privacyguard.apps.AppChanges.recent(ctx) } ?: return
    SectionCard {
        Title("Changes after updates")
        Hint("When an app updates, its new version is compared with the old one. Shown when it starts asking for more or adds tracker SDKs. Last 30 days.")
        if (list.isEmpty()) Text("No app gained permissions or trackers in an update.", fontSize = 13.sp, color = GREEN)
        for (c in list.take(20)) {
            HorizontalDivider(Modifier.padding(vertical = 2.dp))
            Text("${an.name(c.pkg)} ${c.version}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (c.groups.isNotEmpty()) Text("Now asks for: ${c.groups.joinToString()}", fontSize = 12.sp, color = ORANGE)
            if (c.sdks.isNotEmpty()) Text("New tracker SDKs: ${c.sdks.joinToString()}", fontSize = 12.sp, color = RED)
            Hint(java.time.Instant.ofEpochMilli(c.ts).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm")))
        }
    }
}

/** Daily bars, one series: tap a bar for its value. Axis and text in neutral colors. */
@Composable
internal fun DayBars(title: String, days: List<com.privacyguard.apps.AppHistory.Day>, value: (com.privacyguard.apps.AppHistory.Day) -> Int) {
    val bar = MaterialTheme.colorScheme.primary
    val axis = MaterialTheme.colorScheme.outlineVariant
    val max = days.maxOfOrNull(value)?.coerceAtLeast(1) ?: 1
    var sel by remember(days) { mutableStateOf(days.indexOfLast { value(it) > 0 }) }
    val fmt = java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.ENGLISH)
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(days.getOrNull(sel)?.let { "${it.date.format(fmt)}: ${value(it)}" } ?: "total ${days.sumOf(value)}", fontSize = 12.sp)
        }
        androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(90.dp).padding(top = 4.dp)
            .pointerInput(days) { detectTapGestures { o -> sel = (o.x / size.width * days.size).toInt().coerceIn(0, days.size - 1) } }) {
            val w = size.width / days.size
            val gap = 2.dp.toPx()
            val r = minOf(4.dp.toPx(), (w - gap) / 2)
            days.forEachIndexed { i, d ->
                val v = value(d); if (v == 0) return@forEachIndexed
                val h = (size.height - 1.dp.toPx()) * v / max
                val x = i * w + gap / 2
                val path = androidx.compose.ui.graphics.Path().apply {
                    addRoundRect(androidx.compose.ui.geometry.RoundRect(x, size.height - h, x + w - gap, size.height,
                        topLeftCornerRadius = androidx.compose.ui.geometry.CornerRadius(r), topRightCornerRadius = androidx.compose.ui.geometry.CornerRadius(r)))
                }
                drawPath(path, if (i == sel) bar else bar.copy(alpha = 0.55f))
            }
            drawLine(axis, androidx.compose.ui.geometry.Offset(0f, size.height), androidx.compose.ui.geometry.Offset(size.width, size.height), 1.dp.toPx())
        }
        Row {
            Hint(days.first().date.format(fmt)); Spacer(Modifier.weight(1f)); Hint("max $max/day"); Spacer(Modifier.weight(1f)); Hint("today")
        }
    }
}

@Composable
internal fun AppHistoryDialog(pkg: String, onClose: () -> Unit) {
    val ctx = localContext()
    val an = remember { com.privacyguard.core.AppNames(ctx) }
    var h by remember { mutableStateOf<com.privacyguard.apps.AppHistory.H?>(null) }
    LaunchedEffect(pkg) { h = withContext(Dispatchers.IO) { runCatching { com.privacyguard.apps.AppHistory.of(ctx, pkg) }.getOrNull() } }
    AlertDialog(onDismissRequest = onClose, confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        title = { Text("${an.name(pkg)} · last 30 days") },
        text = {
            val x = h
            if (x == null) Text("Loading…")
            else Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DayBars("Sensitive access", x.days) { it.access }
                if (x.byWhat.isNotEmpty()) Hint(x.byWhat.take(6).joinToString(" · ") { "${it.first} ${it.second}" })
                DayBars("Tracker requests", x.days) { it.trackers }
                if (x.companies.isNotEmpty()) Hint(x.companies.take(6).joinToString(" · ") { "${it.first} ${it.second}" })
                else Hint("No tracker requests recorded (needs the local VPN).")
                Hint("Accesses within the same minute count once.")
            }
        })
}

/** Setup step 2, option C: Shizuku (root phones, or started with Wireless debugging once). */
@Composable
internal fun ShizukuOption(onWork: (String, suspend () -> String?) -> Unit) {
    val ctx = localContext()
    val sh = com.privacyguard.collect.ShizukuShell
    var tick by remember { mutableIntStateOf(0) }
    val installed = remember(tick) { sh.installed(ctx) }
    val running = remember(tick) { sh.running() }
    val granted = remember(tick) { sh.granted() }
    // the permission dialog belongs to Shizuku: check again a few times after asking
    var asked by remember { mutableStateOf(false) }
    LaunchedEffect(asked) { if (asked) repeat(20) { kotlinx.coroutines.delay(1000); tick++; if (sh.granted()) { asked = false; return@LaunchedEffect } } }
    Text("Option C – Shizuku (rooted phones, or no computer):", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    Hint("Shizuku is a free app that gives other apps shell access. On a rooted phone it runs as root and starts by itself after a restart, so nothing needs reconnecting.")
    when {
        !installed -> {
            Text("Shizuku is not installed.", fontSize = 13.sp)
            OutlinedButton(onClick = {
                runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("market://details?id=${sh.PKG}")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://shizuku.rikka.app/download/")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            }) { Text("Get Shizuku") }
        }
        !running -> {
            Text("Shizuku is installed but not running. Open it and tap Start (root, or Wireless debugging).", fontSize = 13.sp, color = ORANGE)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { ctx.packageManager.getLaunchIntentForPackage(sh.PKG)?.let { runCatching { ctx.startActivity(it) } } }) { Text("Open Shizuku") }
                TextButton(onClick = { tick++ }) { Text("Check again") }
            }
        }
        !granted -> Button(onClick = { sh.request(); asked = true }) { Text("Allow Privacy Guard in Shizuku") }
        else -> {
            Text("Connected through Shizuku" + if (sh.isRoot()) " (root)." else " (shell).", fontSize = 13.sp, color = GREEN)
            OutlinedButton(onClick = {
                onWork("Setting up with Shizuku") {
                    android.util.Log.i("PG.Shell", "Shizuku setup\n" + com.privacyguard.collect.Shell.selfGrant(ctx))
                    "Full access through Shizuku. " + (com.privacyguard.block.Firewall.sync(ctx) ?: "")
                }
            }) { Text("Set up full access") }
        }
    }
}

internal fun hhmm(ts: Long): String = java.time.Instant.ofEpochMilli(ts).atZone(java.time.ZoneId.systemDefault())
    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))

/** Today: how many requests the blocking stopped, by reason and by app. */
@Composable
internal fun BlockedTodayCard(tick: Int, vpn: Boolean) {
    val ctx = localContext()
    val rows = cached("blocked", tick) {
        val from = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        com.privacyguard.data.Db.get(ctx).blockedSince(from)
    } ?: return
    if (rows.isEmpty() && !vpn) return
    val an = remember { com.privacyguard.core.AppNames(ctx) }
    val total = rows.sumOf { it.third }
    SectionCard {
        Title("Blocked today")
        if (total == 0) { Hint("Nothing blocked yet today. Turn on block lists or tracker blocking on the Blocking tab."); return@SectionCard }
        Text("%,d ".format(total) + (if (total == 1) "request stopped" else "requests stopped"), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = GREEN)
        val byWhy = rows.groupBy { com.privacyguard.block.Blocker.reasonLabel(it.second).ifEmpty { "other" } }
            .mapValues { e -> e.value.sumOf { it.third } }.entries.sortedByDescending { it.value }
        for ((why, n) in byWhy.take(5)) Row {
            Text(why.replaceFirstChar { it.uppercase() }, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text("%,d".format(n), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        val byApp = rows.groupBy { it.first }.mapValues { e -> e.value.sumOf { it.third } }.entries.sortedByDescending { it.value }.take(3)
        if (byApp.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("Most blocked", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            for ((uid, n) in byApp) Row {
                Text(an.name(an.pkgForUid(uid)), fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("%,d".format(n), fontSize = 13.sp)
            }
        }
    }
}

/** Today: the Wi-Fi the phone is on, its encryption and DNS servers. */
@Composable
internal fun WifiCard(tick: Int) {
    val ctx = localContext()
    val s = cached("wifi", tick) { com.privacyguard.monitor.WifiCheck.current(ctx) ?: com.privacyguard.monitor.WifiCheck.State(null, false, emptyList(), false).copy(security = "-") }
        ?: return
    if (s.security == "-") return
    SectionCard {
        Title("Wi-Fi check")
        when {
            s.weak -> Banner("${s.security}: anyone nearby can see traffic that is not encrypted, including the names of the sites your apps open. " +
                "Avoid logging in to sites without https, or use a real VPN here.", RED)
            s.security != null -> Text("Encryption: ${s.security}", fontSize = 13.sp, color = GREEN)
            else -> Hint("Android does not report this network's encryption on this version.")
        }
        for ((ip, label) in s.dns) Text("DNS: $ip · $label", fontSize = 13.sp,
            color = if (label == com.privacyguard.monitor.WifiCheck.UNKNOWN) ORANGE else Color.Unspecified)
        if (s.unknownDns.isNotEmpty()) Hint("This network sends your DNS questions to a server that is neither your router nor a known provider. " +
            "It is often your internet provider, which is normal; on a public network it can be used to see or change which sites you open.")
        Text(if (s.privateDns) "Private DNS: on (encrypted)" else "Private DNS: off", fontSize = 13.sp)
    }
}
