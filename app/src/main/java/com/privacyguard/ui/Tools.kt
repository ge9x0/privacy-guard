package com.privacyguard.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/** Rezultatet e tab-eve mbahen ne memorie: kalimi midis tab-eve s'i rillogarit, vetem kur `tick` ndryshon. */
internal object TabCache {
    private val m = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Any?>>()
    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String, tick: Int): T? = m[key]?.takeIf { it.first == tick }?.second as T?
    @Suppress("UNCHECKED_CAST")
    fun <T> any(key: String): T? = m[key]?.second as T?
    fun put(key: String, tick: Int, v: Any?) { m[key] = tick to v }
}

/** Vlera e ngarkuar ne IO; tregon vleren e vjeter derisa te vije e reja. */
@Composable
internal fun <T> cached(key: String, tick: Int, load: () -> T): T? {
    var v by remember { mutableStateOf(TabCache.any<T>(key)) }
    LaunchedEffect(key, tick) {
        TabCache.get<T>(key, tick)?.let { v = it; return@LaunchedEffect }
        // Nje gabim ne nje seksion (p.sh. sistemi s'pergjigjet) s'duhet ta rrezoje app-in.
        val r = withContext(Dispatchers.IO) { runCatching { load() }.onFailure { android.util.Log.w("PG.UI", "load $key", it) }.getOrNull() }
        if (r != null) { TabCache.put(key, tick, r); v = r }
    }
    return v
}
