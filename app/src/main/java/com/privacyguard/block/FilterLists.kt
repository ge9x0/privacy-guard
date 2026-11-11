package com.privacyguard.block

import android.content.Context
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lista bllokimi si te uBlock Origin, por ne nivel DNS (HaGeZi, formati "wildcard onlydomains":
 * nje domen bllokon edhe gjithe nen-domenet e tij).
 *
 * Ruhen si hash 64-bit te renditur (8 bajte/domen; ~600k domene = ~5 MB) dhe kerkohen me
 * binary search per cdo prapashtese te emrit: "a.b.tracker.com" -> tracker.com, b.tracker.com...
 */
object FilterLists {
    private const val TAG = "PG.Lists"
    private const val CDN = "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/wildcard/"
    private const val GH = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/"

    data class Def(val id: String, val name: String, val desc: String, val file: String, val defaultOn: Boolean)

    val all = listOf(
        Def("pro", "Ads & trackers", "HaGeZi Pro: ad networks, trackers, analytics, data brokers.", "pro-onlydomains.txt", true),
        Def("tif", "Malware & phishing", "HaGeZi Threat Intelligence (medium): scam, phishing, malware servers.", "tif.medium-onlydomains.txt", true),
        Def("samsung", "Samsung telemetry", "HaGeZi Native: Samsung's own tracking and telemetry. May turn off some Samsung extras.", "native.samsung-onlydomains.txt", false),
        Def("tiktok", "TikTok tracking", "HaGeZi Native: TikTok/ByteDance tracking domains. Breaks the TikTok app if you use it.", "native.tiktok-onlydomains.txt", false),
    )

    private fun p(ctx: Context) = ctx.getSharedPreferences("pg_lists", Context.MODE_PRIVATE)
    fun enabled(ctx: Context, id: String) = p(ctx).getBoolean("on:$id", all.first { it.id == id }.defaultOn)
    fun setEnabled(ctx: Context, id: String, v: Boolean) { p(ctx).edit().putBoolean("on:$id", v).apply(); loaded.remove(id) }
    fun count(ctx: Context, id: String) = p(ctx).getInt("n:$id", 0)
    fun updatedAt(ctx: Context, id: String) = p(ctx).getLong("t:$id", 0L)
    fun lastError(ctx: Context) = p(ctx).getString("err", null)

    private fun bin(ctx: Context, id: String) = File(ctx.filesDir, "list_$id.bin")

    /** id -> hash-et e renditura; ngarkohen nga disku sipas nevojes. */
    private val loaded = java.util.concurrent.ConcurrentHashMap<String, LongArray>()

    private fun arr(ctx: Context, id: String): LongArray? = loaded[id] ?: synchronized(this) {
        loaded[id] ?: run {
            val f = bin(ctx, id)
            if (!f.exists()) return null
            runCatching {
                DataInputStream(f.inputStream().buffered(1 shl 16)).use { ins ->
                    val n = ins.readInt(); LongArray(n) { ins.readLong() }
                }
            }.onFailure { Log.w(TAG, "load $id", it) }.getOrNull()?.also { loaded[id] = it }
        }
    }

    fun hash(s: String): Long {   // FNV-1a 64
        var h = -0x340d631b7bdddcdbL
        for (ch in s) { h = h xor ch.code.toLong(); h *= 0x100000001b3L }
        return h
    }

    /** id-ja e listes qe e bllokon kete emer, ose null. */
    fun match(ctx: Context, host: String): String? {
        val hs = suffixHashes(host)
        if (hs.isEmpty()) return null
        for (d in all) {
            if (!enabled(ctx, d.id)) continue
            val a = arr(ctx, d.id) ?: continue
            if (contains(a, hs)) return d.id
        }
        return null
    }

    fun suffixHashes(host: String): List<Long> {
        val parts = host.lowercase().trimEnd('.').split('.')
        if (parts.size < 2) return emptyList()
        return (0 until parts.size - 1).map { hash(parts.subList(it, parts.size).joinToString(".")) }
    }

    fun contains(sorted: LongArray, hs: List<Long>) = hs.any { java.util.Arrays.binarySearch(sorted, it) >= 0 }

    fun nameOf(id: String) = all.firstOrNull { it.id == id }?.name ?: id

    /** Shkarkon listat e ndezura (CDN, pastaj GitHub). Kthen nje permbledhje per perdoruesin. */
    fun update(ctx: Context, force: Boolean = false): String {
        val out = StringBuilder()
        var err: String? = null
        for (d in all) {
            if (!enabled(ctx, d.id)) continue
            if (!force && System.currentTimeMillis() - updatedAt(ctx, d.id) < 6 * 86_400_000L && bin(ctx, d.id).exists()) continue
            val r = runCatching { download(CDN + d.file) }.recoverCatching { download(GH + d.file) }
            r.onSuccess { text ->
                val n = compile(ctx, d.id, text)
                p(ctx).edit().putInt("n:${d.id}", n).putLong("t:${d.id}", System.currentTimeMillis()).apply()
                out.append("${d.name}: ${"%,d".format(n)} domains\n")
            }.onFailure { Log.w(TAG, "update ${d.id}", it); err = "${d.name}: ${it.message}" }
        }
        p(ctx).edit().putString("err", err).apply()
        return out.toString().trim().ifEmpty { err ?: "Lists are up to date." }
    }

    private fun download(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 60000
        c.setRequestProperty("User-Agent", "PrivacyGuard")
        try {
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally { c.disconnect() }
    }

    /** Tekst -> hash-e te renditur ne disk. Rreshtat "#" dhe bosh kapercehen. */
    fun compile(ctx: Context, id: String, text: String): Int {
        val a = compileText(text)
        val tmp = File(ctx.filesDir, "list_$id.tmp")
        DataOutputStream(tmp.outputStream().buffered(1 shl 16)).use { o -> o.writeInt(a.size); for (h in a) o.writeLong(h) }
        tmp.renameTo(bin(ctx, id))
        loaded[id] = a
        return a.size
    }

    fun compileText(text: String): LongArray {
        val tmp = LongArray(text.count { it == '\n' } + 1)
        var n = 0
        text.reader().buffered().forEachLine { l ->
            val t = l.trim().removePrefix("*.").lowercase()
            if (t.isNotEmpty() && t[0] != '#' && '.' in t) tmp[n++] = hash(t)
        }
        val a = tmp.copyOf(n); a.sort()
        return a
    }
}
