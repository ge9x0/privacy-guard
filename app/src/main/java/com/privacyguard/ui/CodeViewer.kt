package com.privacyguard.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/** Shikues kodi vetem-lexim: numra rreshtash, ngjyra per XML, kerkim dhe kopjim. */
@Composable
fun CodeViewer(title: String, text: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val lines = remember(text) { text.lines() }
    var q by remember { mutableStateOf("") }
    val hits = remember(q, lines) { if (q.length < 2) emptyList() else lines.indices.filter { lines[it].contains(q, true) } }
    var cur by remember(hits) { mutableIntStateOf(0) }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val col = remember(dark) {
        if (dark) Palette(Color(0xFF82AAFF), Color(0xFFC3E88D), Color(0xFFFFCB6B), Color(0xFF7F8C98), Color(0xFFFF5370))
        else Palette(Color(0xFF1565C0), Color(0xFF2E7D32), Color(0xFF8D6E00), Color(0xFF757575), Color(0xFFC62828))
    }
    fun jump(i: Int) { if (hits.isNotEmpty()) { cur = (i + hits.size) % hits.size; scope.launch { state.scrollToItem(maxOf(0, hits[cur] - 3)) } } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1)
                    TextButton(onClick = {
                        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(title, text))
                    }) { Text("Copy all") }
                    TextButton(onClick = onClose) { Text("Close") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(q, { q = it; }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Search") },
                        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp))
                    Text(if (hits.isEmpty()) "0" else "${cur + 1}/${hits.size}", fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp))
                    TextButton(onClick = { jump(cur - 1) }, enabled = hits.isNotEmpty()) { Text("▲") }
                    TextButton(onClick = { jump(cur + 1) }, enabled = hits.isNotEmpty()) { Text("▼") }
                }
                LaunchedEffect(hits) { if (hits.isNotEmpty()) state.scrollToItem(maxOf(0, hits[0] - 3)) }
                Text("${lines.size} lines · read only", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val w = lines.size.toString().length
                SelectionContainer(Modifier.weight(1f)) {
                    LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(lines) { i, l ->
                            val hit = hits.isNotEmpty() && hits[cur] == i
                            Row(Modifier.fillMaxWidth().background(if (hit) col.str.copy(alpha = 0.18f) else Color.Transparent)) {
                                Text((i + 1).toString().padStart(w), fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                                    color = col.comment, modifier = Modifier.padding(end = 8.dp))
                                Text(highlight(l, q, col), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

class Palette(val tag: Color, val str: Color, val attr: Color, val comment: Color, val warn: Color)

private val reTag = Regex("""</?[A-Za-z_][\w:.-]*|/?>""")
private val reAttr = Regex("""([\w:.-]+)(=)("[^"]*")""")

/** Ngjyra te thjeshta: etiketa XML, atribute, stringje, titujt "#" dhe rreshtat "!" te raporteve. */
fun highlight(l: String, q: String, c: Palette): AnnotatedString = buildAnnotatedString {
    append(l)
    val t = l.trimStart()
    when {
        t.startsWith("#") -> addStyle(SpanStyle(color = c.tag, fontWeight = FontWeight.Bold), 0, l.length)
        t.startsWith("!") -> addStyle(SpanStyle(color = c.warn), 0, l.length)
        else -> {
            reTag.findAll(l).forEach { addStyle(SpanStyle(color = c.tag), it.range.first, it.range.last + 1) }
            reAttr.findAll(l).forEach {
                val a = it.groups[1]!!.range; val s = it.groups[3]!!.range
                addStyle(SpanStyle(color = c.attr), a.first, a.last + 1)
                addStyle(SpanStyle(color = c.str), s.first, s.last + 1)
            }
        }
    }
    if (q.length >= 2) {
        var i = l.indexOf(q, ignoreCase = true)
        while (i >= 0) { addStyle(SpanStyle(background = Color(0x66FFD54F)), i, i + q.length); i = l.indexOf(q, i + q.length, true) }
    }
}
