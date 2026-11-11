package com.privacyguard.apps

import java.io.File
import java.util.zip.ZipFile

/**
 * Lexon emrat e klasave nga kodi i nje app-i (classes*.dex brenda APK-se), pa e ekzekutuar.
 * Ruan vetem paketat ("com.appsflyer.internal"), qe jane shume me pak se klasat.
 *
 * Formati DEX: koka ka string_ids (0x38/0x3C) dhe type_ids (0x40/0x44); cdo type_id tregon nje
 * string si "Lcom/appsflyer/AppsFlyerLib;".
 */
object DexScan {
    fun packages(apks: List<String>, maxDexBytes: Int = 64 shl 20): Set<String> {
        val out = HashSet<String>()
        for (path in apks) {
            if (!File(path).canRead()) continue
            runCatching {
                ZipFile(path).use { z ->
                    for (e in z.entries()) {
                        if (!e.name.matches(Regex("""classes\d*\.dex"""))) continue
                        if (e.size > maxDexBytes) continue
                        val b = z.getInputStream(e).use { it.readBytes() }
                        readTypes(b, out)
                    }
                }
            }
        }
        return out
    }

    private fun u32(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
        ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)

    /** Te gjitha stringjet e nje DEX-i (per URL, domene, celesa). `keep` filtron para se te ruhen. */
    fun readStrings(b: ByteArray, out: MutableCollection<String>, keep: (String) -> Boolean) {
        if (b.size < 0x70 || b[0] != 'd'.code.toByte() || b[1] != 'e'.code.toByte() || b[2] != 'x'.code.toByte()) return
        val strSize = u32(b, 0x38); val strOff = u32(b, 0x3C)
        if (strOff <= 0 || strOff + strSize * 4L > b.size) return
        for (si in 0 until strSize) {
            var p = u32(b, strOff + si * 4)
            if (p <= 0 || p >= b.size) continue
            while (p < b.size && b[p].toInt() and 0x80 != 0) p++
            p++
            var e = p
            while (e < b.size && b[e].toInt() != 0) e++
            if (e - p < 6 || e - p > 2000) continue
            val s = String(b, p, e - p, Charsets.UTF_8)
            if (keep(s)) out += s
        }
    }

    /** Stringjet nga te gjithe DEX-et e APK-ve. */
    fun strings(apks: List<String>, keep: (String) -> Boolean, maxDexBytes: Int = 64 shl 20): List<String> {
        val out = ArrayList<String>()
        for (path in apks) runCatching {
            ZipFile(path).use { z ->
                for (e in z.entries()) {
                    if (!e.name.matches(Regex("""classes\d*\.dex""")) || e.size > maxDexBytes) continue
                    readStrings(z.getInputStream(e).use { it.readBytes() }, out, keep)
                }
            }
        }
        return out
    }

    fun readTypes(b: ByteArray, out: MutableSet<String>) {
        if (b.size < 0x70 || b[0] != 'd'.code.toByte() || b[1] != 'e'.code.toByte() || b[2] != 'x'.code.toByte()) return
        val strSize = u32(b, 0x38); val strOff = u32(b, 0x3C)
        val typeSize = u32(b, 0x40); val typeOff = u32(b, 0x44)
        if (typeOff <= 0 || typeOff + typeSize * 4L > b.size || strOff + strSize * 4L > b.size) return
        val sb = StringBuilder()
        for (t in 0 until typeSize) {
            val si = u32(b, typeOff + t * 4)
            if (si < 0 || si >= strSize) continue
            var p = u32(b, strOff + si * 4)
            if (p <= 0 || p >= b.size) continue
            while (p < b.size && b[p].toInt() and 0x80 != 0) p++   // uleb128: gjatesia ne utf16
            p++
            if (p >= b.size || b[p] != 'L'.code.toByte()) continue
            sb.setLength(0)
            var lastSlash = -1
            var i = p + 1
            while (i < b.size) {
                val c = b[i].toInt() and 0xFF
                if (c == 0 || c == ';'.code) break
                if (c == '/'.code) { lastSlash = sb.length; sb.append('.') } else sb.append(c.toChar())
                i++
            }
            if (lastSlash > 0) out += sb.substring(0, lastSlash)
        }
    }
}
