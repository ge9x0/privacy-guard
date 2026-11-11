package com.privacyguard.inspect

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import com.privacyguard.apps.DexScan
import com.privacyguard.block.FilterLists
import com.privacyguard.core.Trackers
import java.util.zip.ZipFile

/** Reverse engineering vetem-lexim: manifesti i dekoduar dhe serverat/celesat e shkruar ne kod. */
object Inspect {
    data class Flag(val text: String, val bad: Boolean)
    data class Manifest(val xml: String, val flags: List<Flag>, val dangerous: List<String>, val other: List<String>,
                        val exported: List<String>, val links: List<String>)
    data class Server(val host: String, val count: Int, val tag: String?)
    data class Secret(val kind: String, val value: String)
    data class Strings(val servers: List<Server>, val urls: List<String>, val ips: List<String>, val secrets: List<Secret>)

    private val attrNames: Map<Int, String> by lazy {
        runCatching {
            android.R.attr::class.java.fields.associate { it.getInt(null) to it.name }
        }.getOrDefault(emptyMap())
    }

    fun apks(ctx: Context, pkg: String): List<String> {
        val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
        return listOf(ai.sourceDir) + (ai.splitSourceDirs?.toList() ?: emptyList())
    }

    fun manifestNode(apk: String): Axml.Node? = ZipFile(apk).use { z ->
        z.getEntry("AndroidManifest.xml")?.let { e -> Axml.parse(z.getInputStream(e).use { it.readBytes() }) { attrNames[it] } }
    }

    fun manifest(ctx: Context, pkg: String): Manifest {
        val root = manifestNode(apks(ctx, pkg).first()) ?: return Manifest("Could not read the manifest.", emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        val pm = ctx.packageManager
        val target = runCatching { pm.getApplicationInfo(pkg, 0).targetSdkVersion }.getOrDefault(0)
        val perms = root.walk().filter { it.name.startsWith("uses-permission") }.mapNotNull { it.attr("name") }.distinct().toList()
        val (dang, other) = perms.partition { p ->
            runCatching { pm.getPermissionInfo(p, 0).protection == PermissionInfo.PROTECTION_DANGEROUS }.getOrDefault(false)
        }
        return Manifest(Axml.toXml(root), summary(root, target, perms), dang.sorted(), other.sorted(), exported(root, target), deepLinks(root))
    }

    /** Pjesa pa Android (per teste). */
    fun summary(root: Axml.Node, target: Int, perms: List<String>): List<Flag> {
        val app = root.all("application").firstOrNull()
        val f = ArrayList<Flag>()
        f += Flag("Target SDK $target", target in 1..29)
        if (app?.attr("debuggable") == "true") f += Flag("debuggable=true: anyone with a cable can read its data", true)
        when (app?.attr("allowBackup")) {
            "false" -> f += Flag("allowBackup=false", false)
            else -> f += Flag("allowBackup on: its data can be copied out with a backup", target < 31)
        }
        when (app?.attr("usesCleartextTraffic")) {
            "true" -> f += Flag("usesCleartextTraffic=true: may send data without encryption (http)", true)
            "false" -> f += Flag("Cleartext (http) traffic off", false)
            else -> f += Flag(if (target < 28) "Cleartext (http) allowed by default (old target)" else "Cleartext (http) off by default", target < 28)
        }
        app?.attr("networkSecurityConfig")?.let { f += Flag("Custom network security config ($it)", false) }
        if ("android.permission.QUERY_ALL_PACKAGES" in perms) f += Flag("Can see every app installed (QUERY_ALL_PACKAGES)", true)
        if (perms.any { it.endsWith("BIND_ACCESSIBILITY_SERVICE") } || root.walk().any { n -> n.attr("permission") == "android.permission.BIND_ACCESSIBILITY_SERVICE" })
            f += Flag("Has an accessibility service: can read the screen", true)
        if (perms.any { it == "android.permission.REQUEST_INSTALL_PACKAGES" }) f += Flag("Can install other apps", true)
        if (perms.any { it == "android.permission.SYSTEM_ALERT_WINDOW" }) f += Flag("Can draw over other apps", true)
        return f
    }

    private val comps = setOf("activity", "activity-alias", "service", "receiver", "provider")

    /** Komponentet qe app-e te tjera mund t'i hapin pa asnje leje. */
    fun exported(root: Axml.Node, target: Int): List<String> {
        val app = root.all("application").firstOrNull() ?: return emptyList()
        return app.children.filter { it.name in comps }.filter { c ->
            val ex = c.attr("exported")
            val open = ex == "true" || (ex == null && c.all("intent-filter").isNotEmpty() && target < 31)
            open && c.attr("permission") == null && (c.name != "provider" || c.attr("readPermission") == null)
        }.filterNot { c -> c.all("intent-filter").any { f -> f.all("category").any { it.attr("name") == "android.intent.category.LAUNCHER" } } }
            .map { "${it.name}: ${it.attr("name")}" }
    }

    fun deepLinks(root: Axml.Node): List<String> = root.walk().filter { it.name == "intent-filter" }.flatMap { f ->
        val d = f.all("data")
        val schemes = d.mapNotNull { it.attr("scheme") }.ifEmpty { return@flatMap emptySequence() }
        val hosts = d.mapNotNull { it.attr("host") }.ifEmpty { listOf("*") }
        schemes.flatMap { s -> hosts.map { h -> "$s://$h" } }.asSequence()
    }.filterNot { it.startsWith("http://*") || it.startsWith("https://*") }.distinct().sorted().toList()

    // ---------------------------------------------------------------- serverat dhe celesat

    private val reUrl = Regex("""(?:https?|wss?)://[A-Za-z0-9._~:/?#\[\]@!$&'()*+,;=%-]+""")
    private val tlds = "com|net|org|io|co|app|dev|ai|me|info|biz|cn|ru|de|uk|fr|jp|kr|in|br|us|eu|tv|xyz|cloud|tech|link|site|online|ly|gl|gg|so|to|sh|cc|al|it|es|nl|pl"
    private val reHost = Regex("""^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+(?:$tlds)$""")
    private val reIp = Regex("""(?<![\d.])(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})(?![\d.])""")
    val keys = listOf(
        "Google API key" to Regex("""AIza[0-9A-Za-z_-]{35}"""),
        "Firebase database" to Regex("""[a-z0-9-]+\.(?:firebaseio\.com|firebaseapp\.com)"""),
        "AWS access key" to Regex("""\b(?:AKIA|ASIA)[0-9A-Z]{16}\b"""),
        "Stripe key" to Regex("""\b[sp]k_(?:live|test)_[0-9A-Za-z]{16,}"""),
        "Slack token" to Regex("""\bxox[baprs]-[0-9A-Za-z-]{10,}"""),
        "Private key" to Regex("""-----BEGIN (?:RSA |EC )?PRIVATE KEY-----"""),
        "JWT" to Regex("""\beyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}"""),
    )

    private fun candidate(s: String) = s.length in 6..2000 && (s.contains("://") || s.contains('.') || s.startsWith("AIza") ||
        s.contains("AKIA") || s.contains("_live_") || s.contains("xox") || s.contains("PRIVATE KEY") || s.startsWith("eyJ"))

    /** Analizon stringjet (pa Android): `tag` jep etiketen e trackerit/listes per nje host. */
    fun analyze(strings: Collection<String>, tag: (String) -> String?): Strings {
        val hosts = HashMap<String, Int>(); val urls = LinkedHashSet<String>(); val ips = LinkedHashSet<String>()
        val secrets = LinkedHashSet<Secret>()
        fun host(h0: String) {
            val h = h0.lowercase().trimEnd('.')
            if (reHost.matches(h) && !h.startsWith("www.w3.org") && h != "schemas.android.com") hosts.merge(h, 1, Int::plus)
        }
        for (s in strings) {
            for (m in reUrl.findAll(s)) {
                val u = m.value.trimEnd('.', ',', ')', '\'')
                if (urls.size < 3000) urls += u
                host(u.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore(':').substringAfter('@'))
            }
            if (s.length <= 253 && ' ' !in s && '/' !in s) host(s)
            for (m in reIp.findAll(s)) {
                val o = m.groupValues.drop(1).map { it.toInt() }
                if (o.any { it > 255 } || o[0] == 0 || o[0] == 127 || o.all { it < 10 } || o[0] >= 224) continue
                // vetem ne URL ose si string me vete ("1.2.3.4" / "1.2.3.4:443"); perndryshe zakonisht eshte version
                if (!s.contains("://") && !s.trim().matches(Regex("""[\d.]+(?::\d+)?"""))) continue
                ips += m.value
            }
            for ((k, r) in keys) r.findAll(s).forEach { secrets += Secret(k, it.value) }
        }
        val servers = hosts.map { (h, n) -> Server(h, n, tag(h)) }
            .filterNot { it.host.endsWith("android.com") && it.tag == null && it.host.startsWith("schemas.") }
            .sortedWith(compareBy({ it.tag == null }, { it.host }))
        return Strings(servers, urls.toList().sorted(), ips.toList(), secrets.toList())
    }

    fun strings(ctx: Context, pkg: String): Strings {
        val apks = apks(ctx, pkg)
        val all = HashSet<String>()
        all += DexScan.strings(apks, ::candidate)
        for (a in apks) runCatching {
            ZipFile(a).use { z -> z.getEntry("resources.arsc")?.let { e -> all += Axml.arscStrings(z.getInputStream(e).use { it.readBytes() }).filter(::candidate) } }
        }
        return analyze(all) { h ->
            Trackers.classify(h)?.let { "${it.company} · ${it.category}" } ?: FilterLists.match(ctx, h)?.let { "on list: ${FilterLists.nameOf(it)}" }
        }
    }

    fun manifestReport(name: String, pkg: String, m: Manifest) = buildString {
        appendLine("# $name ($pkg)")
        appendLine(); appendLine("## Findings"); m.flags.forEach { appendLine((if (it.bad) "! " else "  ") + it.text) }
        appendLine(); appendLine("## Dangerous permissions (${m.dangerous.size})"); m.dangerous.forEach { appendLine("  $it") }
        appendLine(); appendLine("## Open to other apps without a permission (${m.exported.size})"); m.exported.forEach { appendLine("  $it") }
        appendLine(); appendLine("## Links it opens (${m.links.size})"); m.links.forEach { appendLine("  $it") }
        appendLine(); appendLine("## Other permissions (${m.other.size})"); m.other.forEach { appendLine("  $it") }
    }

    fun stringsReport(name: String, pkg: String, s: Strings) = buildString {
        appendLine("# $name ($pkg): servers and keys written in its code")
        appendLine(); appendLine("## Keys (${s.secrets.size})")
        if (s.secrets.isNotEmpty()) appendLine("  Most are public client keys every copy of the app ships with. A private key or AWS secret is a real leak.")
        s.secrets.forEach { appendLine("! ${it.kind}: ${it.value}") }
        val t = s.servers.filter { it.tag != null }
        appendLine(); appendLine("## Tracker / listed servers (${t.size})"); t.forEach { appendLine("! ${it.host}  [${it.tag}]") }
        val o = s.servers.filter { it.tag == null }
        appendLine(); appendLine("## Other servers (${o.size})"); o.forEach { appendLine("  ${it.host}") }
        appendLine(); appendLine("## IP addresses (${s.ips.size})"); s.ips.forEach { appendLine("  $it") }
        appendLine(); appendLine("## URLs (${s.urls.size})"); s.urls.forEach { appendLine("  $it") }
    }
}
