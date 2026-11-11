package com.privacyguard.inspect

/**
 * Reader for Android's binary XML (AndroidManifest.xml inside an APK) and its string pool
 * (the same format is used in resources.arsc). Read only; written for this project.
 */
object Axml {
    class Node(val name: String, val attrs: MutableList<Pair<String, String>> = ArrayList(), val children: MutableList<Node> = ArrayList()) {
        fun attr(n: String) = attrs.firstOrNull { it.first == n || it.first.substringAfter(':') == n }?.second
        fun all(n: String): List<Node> = children.filter { it.name == n }
        fun walk(): Sequence<Node> = sequenceOf(this) + children.asSequence().flatMap { it.walk() }
    }

    private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, i: Int) = u16(b, i) or (u16(b, i + 2) shl 16)

    /** The string pool starting at `at` (chunk RES_STRING_POOL_TYPE). */
    fun stringPool(b: ByteArray, at: Int): List<String> {
        if (at + 28 > b.size || u16(b, at) != 0x0001) return emptyList()
        val count = u32(b, at + 8)
        val utf8 = u32(b, at + 16) and (1 shl 8) != 0
        val start = at + u32(b, at + 20)
        val offs = at + u16(b, at + 2)
        if (count < 0 || offs + count * 4L > b.size) return emptyList()
        return List(count) { i ->
            runCatching {
                var p = start + u32(b, offs + i * 4)
                if (utf8) {
                    if (b[p].toInt() and 0x80 != 0) p += 2 else p += 1           // length in UTF-16 units
                    var len = b[p].toInt() and 0xFF
                    if (len and 0x80 != 0) { len = ((len and 0x7F) shl 8) or (b[p + 1].toInt() and 0xFF); p += 2 } else p += 1
                    String(b, p, minOf(len, b.size - p), Charsets.UTF_8)
                } else {
                    var len = u16(b, p)
                    if (len and 0x8000 != 0) { len = ((len and 0x7FFF) shl 16) or u16(b, p + 2); p += 4 } else p += 2
                    String(b, p, minOf(len * 2, b.size - p), Charsets.UTF_16LE)
                }
            }.getOrDefault("")
        }
    }

    /** Strings of resources.arsc (the global string pool after the 12-byte header). */
    fun arscStrings(b: ByteArray): List<String> = if (b.size > 12 && u16(b, 0) == 0x0002) stringPool(b, u16(b, 2)) else emptyList()

    /**
     * Decodes binary XML into a tree. `attrName` gives an attribute's name from its resource ID
     * (for "android:" attributes when the name in the string pool is missing or obfuscated).
     */
    fun parse(b: ByteArray, attrName: (Int) -> String? = { null }): Node? {
        if (b.size < 8 || u16(b, 0) != 0x0003) return null
        var strings = emptyList<String>()
        var resIds = IntArray(0)
        val nsPrefix = HashMap<String, String>()
        val stack = ArrayList<Node>()
        var root: Node? = null
        var p = u16(b, 2)
        fun str(i: Int) = if (i >= 0 && i < strings.size) strings[i] else ""
        while (p + 8 <= b.size) {
            val type = u16(b, p); val hs = u16(b, p + 2); val size = u32(b, p + 4)
            if (size < 8 || p + size > b.size) break
            when (type) {
                0x0001 -> strings = stringPool(b, p)
                0x0180 -> resIds = IntArray((size - hs) / 4) { u32(b, p + hs + it * 4) }
                0x0100 -> nsPrefix[str(u32(b, p + 20))] = str(u32(b, p + 16))
                0x0102 -> {
                    val ext = p + hs
                    val name = str(u32(b, ext + 4))
                    val aStart = u16(b, ext + 8); val aSize = u16(b, ext + 10); val aCount = u16(b, ext + 12)
                    val n = Node(name)
                    for (k in 0 until aCount) {
                        val a = ext + aStart + k * aSize
                        if (a + 20 > b.size) break
                        val ns = u32(b, a); val ni = u32(b, a + 4); val raw = u32(b, a + 8)
                        val dType = b[a + 15].toInt() and 0xFF; val data = u32(b, a + 16)
                        var an = str(ni)
                        if ((an.isEmpty() || an.length <= 2) && ni >= 0 && ni < resIds.size) an = attrName(resIds[ni]) ?: an
                        if (an.isEmpty() && ni >= 0 && ni < resIds.size) an = "attr_0x%08x".format(resIds[ni])
                        val prefix = if (ns != -1) nsPrefix[str(ns)]?.let { "$it:" } ?: "" else ""
                        n.attrs += (prefix + an) to value(dType, data, if (raw != -1) str(raw) else null, ::str)
                    }
                    if (stack.isEmpty()) root = n else stack.last().children += n
                    stack += n
                }
                0x0103 -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
            }
            p += size
        }
        return root
    }

    private fun value(t: Int, d: Int, raw: String?, str: (Int) -> String): String = when (t) {
        0x03 -> raw ?: str(d)
        0x01 -> "@0x%08x".format(d)
        0x02 -> "?0x%08x".format(d)
        0x10 -> d.toString()
        0x11 -> "0x%x".format(d)
        0x12 -> if (d != 0) "true" else "false"
        0x04 -> java.lang.Float.intBitsToFloat(d).toString()
        in 0x1c..0x1f -> "#%08x".format(d)
        else -> raw ?: "0x%x".format(d)
    }

    fun toXml(n: Node, indent: String = ""): String = buildString {
        append(indent).append('<').append(n.name)
        if (n.attrs.size <= 1) n.attrs.forEach { (k, v) -> append(' ').append(k).append("=\"").append(esc(v)).append('"') }
        else n.attrs.forEach { (k, v) -> append('\n').append(indent).append("    ").append(k).append("=\"").append(esc(v)).append('"') }
        if (n.children.isEmpty()) append(" />\n")
        else {
            append(">\n")
            n.children.forEach { append(toXml(it, "$indent    ")) }
            append(indent).append("</").append(n.name).append(">\n")
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
}
