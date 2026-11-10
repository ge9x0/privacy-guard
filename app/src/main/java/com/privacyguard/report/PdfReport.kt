package com.privacyguard.report

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.privacyguard.core.AppNames
import com.privacyguard.core.DayReport
import com.privacyguard.core.Ev
import com.privacyguard.core.Ops
import com.privacyguard.core.Sev
import com.privacyguard.core.Trackers
import com.privacyguard.core.fmtBytes
import com.privacyguard.core.fmtDur
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Raporti PDF, i njejte me ate te scriptit: permbledhje + nje seksion per dite. */
class PdfReport(private val names: AppNames, private val modeLabel: String) {
    private val W = 595f; private val H = 842f; private val M = 40f
    private val CW get() = W - 2 * M
    private val INK = Color.parseColor("#1B2333")
    private val MUTED = Color.parseColor("#5B6475")
    private val LINE = Color.parseColor("#DDE1E8")
    private val SOFT = Color.parseColor("#F4F6F9")
    private val C_TOP = Color.parseColor("#4C78A8")
    private val C_FGS = Color.parseColor("#E8A33D")
    private val C_BG = Color.parseColor("#D64545")
    private val C_OFF = Color.parseColor("#E3E6EC")

    private val doc = PdfDocument()
    private var page: PdfDocument.Page? = null
    private lateinit var c: Canvas
    private var y = 0f
    private var pageNo = 0

    private fun tp(size: Float, color: Int = INK, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; this.color = color; typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    // ------------------------------------------------------------- faqet
    private fun newPage() {
        page?.let { doc.finishPage(it) }
        pageNo++
        val p = doc.startPage(PdfDocument.PageInfo.Builder(W.toInt(), H.toInt(), pageNo).create())
        page = p; c = p.canvas; y = M
        val f = tp(7f, MUTED)
        c.drawText("Privacy Guard · raport privatësie", M, H - 22f, f)
        val s = "Faqja $pageNo"
        c.drawText(s, W - M - f.measureText(s), H - 22f, f)
    }

    private fun need(h: Float) { if (y + h > H - M - 10) newPage() }

    private fun layout(text: CharSequence, paint: TextPaint, width: Float): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt().coerceAtLeast(10))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()

    private fun text(t: String, size: Float, color: Int = INK, bold: Boolean = false, gapAfter: Float = 4f) {
        val l = layout(t, tp(size, color, bold), CW)
        need(l.height.toFloat())
        c.save(); c.translate(M, y); l.draw(c); c.restore()
        y += l.height + gapAfter
    }

    private fun h1(t: String) = text(t, 19f, INK, true, 2f)
    private fun h2(t: String) { y += 8f; need(40f); text(t, 12.5f, INK, true, 5f) }
    private fun sub(t: String) = text(t, 9f, MUTED)
    private fun note(t: String) = text(t, 7f, MUTED, gapAfter = 2f)

    /** Rreshti me numrat e medhenj. */
    private fun kpis(items: List<Triple<String, String, Int?>>) {
        val h = 52f
        need(h + 6)
        val w = CW / items.size
        val bg = Paint().apply { color = SOFT }
        val border = Paint().apply { color = LINE; style = Paint.Style.STROKE; strokeWidth = 0.6f }
        c.drawRect(M, y, M + CW, y + h, bg)
        c.drawRect(M, y, M + CW, y + h, border)
        items.forEachIndexed { i, (v, l, col) ->
            val x = M + i * w
            if (i > 0) c.drawLine(x, y, x, y + h, border)
            if (col != null) c.drawRect(x, y, x + w, y + 2.5f, Paint().apply { color = col })
            val vp = tp(13f, col ?: INK, true)
            val vl = layout(v, vp, w - 6)
            c.save(); c.translate(x + (w - vl.getLineWidth(0)) / 2, y + 9); vl.draw(c); c.restore()
            val lp = tp(6.6f, MUTED)
            val ll = layout(l, lp, w - 8)
            c.save(); c.translate(x + 4, y + 33)
            for (li in 0 until ll.lineCount) {
                val s = l.substring(ll.getLineStart(li), ll.getLineEnd(li)).trim()
                c.drawText(s, (w - 8 - lp.measureText(s)) / 2, ll.getLineBaseline(li).toFloat(), lp)
            }
            c.restore()
        }
        y += h + 6
    }

    private class G(val e: Ev) {
        val times = mutableListOf<Long>()
        var ms = 0L
        val states = LinkedHashSet<String>()
    }

    class Cell(val text: String, val color: Int? = null, val bold: Boolean = false, val small: Boolean = false)

    private fun table(header: List<String>, rows: List<List<Cell>>, fr: List<Float>) {
        val widths = fr.map { it * CW }
        val pad = 3.5f
        fun rowLayouts(cells: List<Cell>, head: Boolean) = cells.mapIndexed { i, cell ->
            val p = when {
                head -> tp(7f, Color.WHITE, true)
                cell.small -> tp(6.4f, cell.color ?: MUTED, cell.bold)
                else -> tp(7f, cell.color ?: INK, cell.bold)
            }
            layout(cell.text, p, widths[i] - 2 * pad)
        }
        fun drawRow(ls: List<StaticLayout>, fill: Int?) {
            val h = ls.maxOf { it.height } + 2 * pad
            need(h)
            if (fill != null) c.drawRect(M, y, M + CW, y + h, Paint().apply { color = fill })
            var x = M
            ls.forEachIndexed { i, l ->
                c.save(); c.translate(x + pad, y + pad); l.draw(c); c.restore()
                x += widths[i]
            }
            c.drawLine(M, y + h, M + CW, y + h, Paint().apply { color = LINE; strokeWidth = 0.4f })
            y += h
        }
        val head = rowLayouts(header.map { Cell(it) }, true)
        drawRow(head, INK)
        rows.forEachIndexed { i, r ->
            if (y + 20 > H - M - 10) { newPage(); drawRow(head, INK) }
            drawRow(rowLayouts(r, false), if (i % 2 == 1) SOFT else null)
        }
        y += 4
    }

    // ------------------------------------------------------------- grafiku
    private fun timeline(d: DayReport) {
        val evs = d.events.filter { it.group in setOf("mic", "cam", "loc", "personal", "clip", "media") }
        if (evs.isEmpty()) return
        val score = HashMap<Pair<String, String>, Int>()
        for (e in evs) {
            var w = if (e.bgOrOff) 5 else 1
            if (e.group == "mic" || e.group == "cam") w *= 3
            score.merge(e.e.pkg to e.label, w, Int::plus)
        }
        val rows = score.entries.sortedByDescending { it.value }.take(16).map { it.key }
            .sortedWith(compareBy({ it.second }, { names.name(it.first) }))
        val rowH = 13f
        val labelW = 150f
        val chartW = CW - labelW
        val h = rows.size * rowH + 34
        h2("Kush përdori çfarë, gjatë ditës")
        need(h)
        val top = y
        val x0 = M + labelW
        val z = ZoneId.systemDefault()
        val dayStart = d.day.atStartOfDay(z).toInstant().toEpochMilli()
        fun xOf(ts: Long) = x0 + ((ts - dayStart) / 3_600_000f).coerceIn(0f, 24f) / 24f * chartW

        // ekrani i fikur
        if (d.screen.isNotEmpty()) {
            val off = Paint().apply { color = C_OFF }
            for (i in d.screen.indices) {
                val s = d.screen[i]
                if (s.on) continue
                val end = d.screen.getOrNull(i + 1)?.ts ?: (s.ts + 60_000)
                if (end - s.ts > 600_000) continue
                c.drawRect(xOf(s.ts), top, xOf(end), top + rows.size * rowH, off)
            }
        }
        val grid = Paint().apply { color = LINE; strokeWidth = 0.5f }
        val axis = tp(6f, MUTED)
        for (hh in 0..24 step 2) {
            val x = x0 + hh / 24f * chartW
            c.drawLine(x, top, x, top + rows.size * rowH, grid)
            val s = "%02d".format(hh)
            c.drawText(s, x - axis.measureText(s) / 2, top + rows.size * rowH + 9, axis)
        }
        val lab = tp(6.3f, INK)
        rows.forEachIndexed { i, (pkg, label) ->
            var s = "${names.name(pkg)} · $label"
            while (lab.measureText(s) > labelW - 6 && s.length > 4) s = s.dropLast(2) + "…"
            c.drawText(s, M + labelW - 6 - lab.measureText(s), top + i * rowH + rowH * 0.68f, lab)
        }
        val idx = rows.withIndex().associate { it.value to it.index }
        for (e in evs) {
            val i = idx[e.e.pkg to e.label] ?: continue
            val yy = top + i * rowH
            val x = xOf(e.e.ts)
            if (e.e.kind == "reject") {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK; strokeWidth = 0.9f }
                c.drawLine(x - 2.5f, yy + 4, x + 2.5f, yy + 9, p); c.drawLine(x - 2.5f, yy + 9, x + 2.5f, yy + 4, p)
                continue
            }
            val col = when {
                e.bgOrOff -> C_BG
                e.e.state in Ops.fgService -> C_FGS
                else -> C_TOP
            }
            val w = maxOf(xOf(e.e.ts + e.e.durationMs) - x, 1.6f)
            c.drawRect(RectF(x, yy + 2.5f, x + w, yy + rowH - 2.5f), Paint().apply { color = col })
        }
        // legjenda
        var lx = x0
        val ly = top + rows.size * rowH + 18
        val leg = mutableListOf(C_TOP to "Në ekran", C_FGS to "Shërbim me njoftim", C_BG to "Background / ekran i fikur")
        if (d.screen.isNotEmpty()) leg += C_OFF to "Ekrani i fikur"
        for ((col, t) in leg) {
            c.drawRect(lx, ly, lx + 8, ly + 6, Paint().apply { color = col })
            c.drawText(t, lx + 11, ly + 5.5f, axis)
            lx += 18 + axis.measureText(t)
        }
        y = top + h
    }

    // ------------------------------------------------------------- permbajtja
    private val dayNames = listOf("E hënë", "E martë", "E mërkurë", "E enjte", "E premte", "E shtunë", "E diel")
    private val months = listOf("Janar", "Shkurt", "Mars", "Prill", "Maj", "Qershor", "Korrik", "Gusht", "Shtator", "Tetor", "Nëntor", "Dhjetor")
    private fun dayTitle(d: LocalDate) = "${dayNames[d.dayOfWeek.value - 1]}, ${d.dayOfMonth} ${months[d.monthValue - 1]} ${d.year}"
    private val hmFmt = DateTimeFormatter.ofPattern("HH:mm")
    private fun hm(ts: Long) = LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.systemDefault()).format(hmFmt)
    private fun red(n: Int) = if (n > 0) Sev.CRIT.color else null

    private fun summary(days: List<DayReport>) {
        newPage()
        h1("Raporti i privatësisë së telefonit")
        val model = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"
        sub("$model · ${dayTitle(days.first().day)}${if (days.size > 1) " – " + dayTitle(days.last().day) else ""}")
        sub("Krijuar më ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm"))} · Mënyra: $modeLabel")
        y += 6
        val crit = days.sumOf { it.crit }; val high = days.sumOf { it.high }; val trk = days.sumOf { it.trk }
        kpis(listOf(
            Triple("$crit", "Sinjalizime kritike", red(crit)),
            Triple("$high", "Të dhëna personale në background", if (high > 0) Sev.HIGH.color else null),
            Triple(fmtDur(days.sumOf { it.micMs }), "Mikrofon (${days.sumOf { it.micN }} herë)", null),
            Triple(fmtDur(days.sumOf { it.camMs }), "Kamera (${days.sumOf { it.camN }} herë)", null),
            Triple(fmtBytes(days.sumOf { it.txBg }), "Dërguar në background", null),
            Triple("$trk", "Kërkesa te trackera", if (trk > 0) Sev.HIGH.color else null),
        ))
        if (days.size > 1) {
            h2("Dita pas dite")
            table(listOf("Dita", "Mikrofon", "Kamera", "Kritike", "Të larta", "Dërguar (bg)", "Gjithsej", "Trackera"),
                days.map { d ->
                    listOf(Cell(dayTitle(d.day)), Cell("${d.micN}× · ${fmtDur(d.micMs)}"), Cell("${d.camN}× · ${fmtDur(d.camMs)}"),
                        Cell("${d.crit}", red(d.crit), d.crit > 0), Cell("${d.high}", red(d.high), d.high > 0),
                        Cell(fmtBytes(d.txBg)), Cell(fmtBytes(d.tx)), Cell("${d.trk}"))
                }, listOf(.22f, .13f, .13f, .08f, .08f, .13f, .13f, .10f))
        }
        val agg = LinkedHashMap<String, IntArray>()
        val what = HashMap<String, MutableSet<String>>()
        days.flatMap { it.alerts }.forEach {
            agg.getOrPut(it.e.pkg) { IntArray(3) }[it.sev.order.coerceAtMost(2)]++
            what.getOrPut(it.e.pkg) { sortedSetOf() } += it.label
        }
        if (agg.isNotEmpty()) {
            h2("App-et që duhen parë")
            table(listOf("App", "Paketa", "Kritike", "Të larta", "Mesatare", "Çfarë"),
                agg.entries.sortedWith(compareBy({ -it.value[0] }, { -it.value[1] }, { -it.value[2] })).take(10).map { (pkg, a) ->
                    listOf(Cell(names.name(pkg), bold = true), Cell(pkg, small = true), Cell("${a[0]}", red(a[0])),
                        Cell("${a[1]}"), Cell("${a[2]}"), Cell(what[pkg].orEmpty().joinToString(", ")))
                }, listOf(.16f, .30f, .09f, .09f, .10f, .26f))
        }
        h2("Si ta lexosh")
        for ((s, t) in listOf(
            Sev.CRIT to "Mikrofoni ose kamera u përdor kur app-i nuk ishte në ekran, ose kur ekrani ishte i fikur.",
            Sev.HIGH to "Kontaktet, SMS, thirrjet ose clipboard u lexuan në background.",
            Sev.MED to "Vendndodhja në background, ose mikrofon/kamera nga një shërbim me njoftim (p.sh. thirrje). Shpesh normale, por kontrolloje.",
            Sev.INFO to "Përdorim i dukshëm, ndërsa e kishe app-in hapur.",
        )) {
            val l = layout("${s.label} – $t", tp(8.5f), CW)
            need(l.height + 3f)
            c.drawText(s.label, M, y + 9f, tp(8.5f, s.color, true))
            val rest = layout(t, tp(8.5f), CW - 70)
            c.save(); c.translate(M + 70, y); rest.draw(c); c.restore()
            y += rest.height + 3
        }
        note("“Background” do të thotë që Android-i vetë e ka regjistruar app-in jashtë ekranit në momentin e qasjes. " +
            "Asistentët zanorë (Google, Bixby) dhe thirrjet shënohen veçmas, sepse dëgjimi në background është funksioni i tyre.")
    }

    private fun day(d: DayReport) {
        newPage()
        h1(dayTitle(d.day))
        sub(if (d.screen.isNotEmpty()) "Me të dhëna të ekranit (ndezur/fikur)" else "Pa të dhëna të ekranit – ndize monitorimin në app")
        y += 4
        kpis(listOf(
            Triple("${d.crit}", "Kritike", red(d.crit)),
            Triple("${d.high}", "Të larta", if (d.high > 0) Sev.HIGH.color else null),
            Triple("${d.micN}×", "Mikrofon · ${fmtDur(d.micMs)}", null),
            Triple("${d.camN}×", "Kamera · ${fmtDur(d.camMs)}", null),
            Triple("${d.locN}×", "Vendndodhje", null),
            Triple(fmtBytes(d.txBg), "Dërguar në background", null),
        ))
        timeline(d)

        h2("Sinjalizime")
        if (d.alerts.isEmpty()) text("Asnjë qasje e dyshimtë këtë ditë.", 8.5f)
        else {
            val groups = LinkedHashMap<List<String>, G>()
            for (e in d.alerts) {
                val st = when {
                    e.e.kind == "reject" -> "U bllokua"
                    e.screenOn == false -> "Ekran i fikur"
                    else -> Ops.stateLabel[e.e.state] ?: e.e.state
                }
                val g = groups.getOrPut(listOf(e.e.pkg, e.label, e.sev.name, e.e.kind)) { G(e) }
                g.times += e.e.ts; g.ms += e.e.durationMs; g.states += st
            }
            table(listOf("Ora", "App", "Çfarë", "Gjendja", "Kohë", "Niveli", "Pse"),
                groups.values.take(25).map { g ->
                    val t = g.times
                    val whenS = when {
                        t.size == 1 -> hm(t[0])
                        t.size <= 3 -> t.joinToString(", ") { hm(it) }
                        else -> "${hm(t.min())}–${hm(t.max())}\n${t.size} herë"
                    }
                    listOf(Cell(whenS), Cell(names.name(g.e.e.pkg), bold = true),
                        Cell(g.e.label + if (t.size > 1) " ×${t.size}" else ""), Cell(g.states.joinToString(", ")),
                        Cell(if (g.ms > 0) fmtDur(g.ms) else "–"), Cell(g.e.sev.label, g.e.sev.color, true), Cell(g.e.why))
                }, listOf(.10f, .13f, .15f, .12f, .09f, .10f, .31f))
        }

        h2("Çfarë u përdor dhe u regjistrua")
        val us = d.usage.entries.filter { it.value.count > 0 || it.value.reject > 0 }
            .sortedWith(compareBy({ -it.value.bg }, { if (it.key.third == "mic") 0 else if (it.key.third == "cam") 1 else 2 }, { -it.value.ms }))
        if (us.isEmpty()) text("Nuk u regjistrua asnjë qasje.", 8.5f)
        else {
            table(listOf("App", "Çfarë", "Herë", "Kohëzgjatja", "Jashtë ekranit", "Ora", "Bllokuar"),
                us.take(30).map { (k, v) ->
                    listOf(Cell(names.name(k.first), bold = true), Cell(k.second), Cell("${v.count}"),
                        Cell(if (v.ms > 0) fmtDur(v.ms) else "–"),
                        if (v.bg > 0) Cell("${v.bg}× · ${fmtDur(v.bgMs)}", Sev.CRIT.color, true) else Cell("–"),
                        Cell(if (v.first > 0) "${hm(v.first)}–${hm(v.last)}" else "–"), Cell(if (v.reject > 0) "${v.reject}" else "–"))
                }, listOf(.17f, .21f, .07f, .14f, .17f, .14f, .10f))
            note("Kohëzgjatja jepet për mikrofon, kamera dhe vendndodhje; për të dhënat e tjera Android-i regjistron vetëm momentin e leximit.")
        }

        h2("Çfarë u dërgua në internet")
        val net = d.net.entries.filter { it.value.tx > 0 }.sortedByDescending { it.value.txBg }.take(15)
        if (net.isEmpty()) text("S'ka statistika rrjeti (jep lejen “Usage access”).", 8.5f)
        else {
            val bgApps = d.alerts.filter { it.sev == Sev.CRIT || it.sev == Sev.HIGH }.map { it.e.pkg }.toSet()
            table(listOf("App", "Dërguar gjithsej", "Dërguar në background", "Me data celulare", "Marrë", "Shënim"),
                net.map { (pkg, v) ->
                    val share = if (v.tx > 0) v.txBg * 100 / v.tx else 0
                    val n = when {
                        pkg in bgApps -> Cell("Përdori edhe sensorë në background këtë ditë", Sev.CRIT.color)
                        share > 60 && v.txBg > 1_048_576 -> Cell("Shumica u dërgua pa e pasur app-in hapur")
                        else -> Cell("")
                    }
                    listOf(Cell(names.name(pkg), bold = true), Cell(fmtBytes(v.tx)), Cell("${fmtBytes(v.txBg)} ($share%)"),
                        Cell(fmtBytes(v.mobileTx)), Cell(fmtBytes(v.rx)), n)
                }, listOf(.17f, .14f, .19f, .14f, .12f, .24f))
            note("Sasitë vijnë nga statistikat e rrjetit të Android-it; tregojnë sa, jo çfarë përmbajtje u dërgua.")
        }

        h2("Kujt iu dërgua: brokerë të dhënash, reklama, gjurmues")
        val flat = d.trackers.flatMap { (pkg, m) -> m.map { (t, u) -> Triple(pkg, t, u) } }
            .sortedWith(compareBy({ -(Trackers.weight[it.second.category] ?: 1) }, { -it.third.count }))
        when {
            flat.isNotEmpty() -> table(listOf("App", "Kompania", "Kategoria", "Kërkesa", "Domain-et"),
                flat.take(25).map { (pkg, t, u) ->
                    val col = if ((Trackers.weight[t.category] ?: 1) >= 3) Sev.CRIT.color else null
                    listOf(Cell(names.name(pkg), bold = true), Cell(t.company, col, true), Cell(t.category),
                        Cell("${u.count}"), Cell(u.hosts.sorted().take(3).joinToString(", ") + if (u.hosts.size > 3) " …" else "", small = true))
                }, listOf(.16f, .19f, .22f, .09f, .34f))
            d.hasConnData -> text("Asnjë lidhje me trackera të njohur këtë ditë.", 8.5f)
            else -> text("Ndize “Regjistro adresat (VPN lokale)” në app për të parë kujt i dërgon çdo app.", 8.5f)
        }

        if (d.flows.isNotEmpty()) {
            h2("Çfarë mori dhe kujt ia çoi")
            table(listOf("App", "Mori", "Ia çoi", "Pas marrjes", "Bllokuar"),
                d.flows.take(20).map { f ->
                    listOf(
                        Cell(names.name(f.pkg), bold = true),
                        Cell(f.took.entries.joinToString { (k, n) -> k + (if (n > 1) " ×$n" else "") + if (k in f.bgTook) " (bg)" else "" }
                            .ifEmpty { "–" }, if (f.bgTook.isNotEmpty()) Sev.HIGH.color else null),
                        Cell(f.to.keys.joinToString { it.company }),
                        Cell(f.links.joinToString("\n") { "${hm(it.ts)} ${it.what} → ${it.company} pas ${fmtDur(it.afterMs)}" }.ifEmpty { "–" },
                            if (f.links.isNotEmpty()) Sev.CRIT.color else null, small = true),
                        Cell(if (f.blocked > 0) "${f.blocked}" else "–"),
                    )
                }, listOf(.15f, .25f, .2f, .3f, .1f))
            note("“Pas marrjes”: i njëjti app kontaktoi tracker-in brenda 10 minutave pasi lexoi të dhënën. " +
                "Është lidhje në kohë, jo provë që pikërisht ajo e dhënë u dërgua; DNS-ja tregon kujt, jo çfarë.")
        }
    }

    private fun method() {
        newPage()
        h1("Si u mblodhën të dhënat dhe kufizimet")
        for (t in listOf(
            "Mikrofon, kamera, vendndodhje, kontakte, clipboard – nga regjistri AppOps i Android-it, i njëjti burim që përdor paneli i privatësisë. " +
                "Në mënyrën e kufizuar, nga njoftimet e sistemit kur mikrofoni/kamera ndizet.",
            "Sa të dhëna u dërguan – nga statistikat e rrjetit të Android-it, të ndara në plan të parë dhe background.",
            "Kujt iu dërguan – nga VPN-ja lokale e app-it, që regjistron vetëm emrat e serverave (DNS) për çdo app. " +
                "App-et që përdorin DNS të vetin të enkriptuar nuk duken këtu.",
            "Kufizime: mjeti tregon kur dhe sa, jo përmbajtjen. Një app që do anashkalonte vetë regjistrin e sistemit nuk do dukej. " +
                "Një sinjalizim do të thotë “kontrolloje”, jo provë automatike përgjimi.",
        )) { text(t, 8.5f, gapAfter = 7f) }
    }

    fun write(days: List<DayReport>, out: OutputStream) {
        summary(days)
        days.forEach { day(it) }
        method()
        page?.let { doc.finishPage(it) }
        doc.writeTo(out)
        doc.close()
    }
}
