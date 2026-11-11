package com.privacyguard

import com.privacyguard.inspect.Axml
import com.privacyguard.inspect.Inspect
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class InspectTest {
    @Test fun decodesRealManifest() {
        val apk = File("build/outputs/apk/debug/app-debug.apk")
        assumeTrue(apk.exists())
        val root = ZipFile(apk).use { z -> Axml.parse(z.getInputStream(z.getEntry("AndroidManifest.xml")).readBytes()) }!!
        assertEquals("manifest", root.name)
        assertEquals("com.privacyguard", root.attr("package"))
        val perms = root.walk().filter { it.name == "uses-permission" }.mapNotNull { it.attr("name") }.toList()
        assertTrue("android.permission.DUMP" in perms)
        assertTrue(Axml.toXml(root).contains("<application"))
        val arsc = ZipFile(apk).use { z -> Axml.arscStrings(z.getInputStream(z.getEntry("resources.arsc")).readBytes()) }
        assertTrue(arsc.any { it.contains("appsflyer", true) })   // from the App Manager list
    }

    private fun node(n: String, vararg a: Pair<String, String>, ch: List<Axml.Node> = emptyList()) =
        Axml.Node(n, a.toMutableList(), ch.toMutableList())

    @Test fun summaryAndExported() {
        val launcher = node("activity", "android:name" to ".Main", "android:exported" to "true", ch = listOf(node("intent-filter", ch = listOf(
            node("action", "android:name" to "android.intent.action.MAIN"), node("category", "android:name" to "android.intent.category.LAUNCHER")))))
        val open = node("receiver", "android:name" to ".Open", "android:exported" to "true")
        val guarded = node("service", "android:name" to ".G", "android:exported" to "true", "android:permission" to "x")
        val link = node("activity", "android:name" to ".L", "android:exported" to "true", ch = listOf(node("intent-filter", ch = listOf(
            node("data", "android:scheme" to "myapp", "android:host" to "pay")))))
        val app = node("application", "android:debuggable" to "true", "android:usesCleartextTraffic" to "true", ch = listOf(launcher, open, guarded, link))
        val root = node("manifest", ch = listOf(app))
        val f = Inspect.summary(root, 34, listOf("android.permission.QUERY_ALL_PACKAGES"))
        assertTrue(f.any { it.bad && it.text.startsWith("debuggable") })
        assertTrue(f.any { it.bad && it.text.startsWith("usesCleartext") })
        assertTrue(f.any { it.bad && it.text.contains("QUERY_ALL") })
        assertEquals(listOf("receiver: .Open", "activity: .L"), Inspect.exported(root, 34))
        assertEquals(listOf("myapp://pay"), Inspect.deepLinks(root))
    }

    @Test fun findsServersAndKeys() {
        val s = Inspect.analyze(listOf(
            "https://api.example.com/v1/login?x=1", "t.appsflyer.com", "com.google.android.gms", "1.2.3", "Version 10.0.0.1 build",
            "http://203.0.113.7:8080/x", "AIzaSyA1234567890abcdefghijklmnopqrstuv", "my-proj.firebaseio.com",
            "-----BEGIN RSA PRIVATE KEY-----", "sk_live_abcdefghijklmnop1234",
        )) { if (it.endsWith("appsflyer.com")) "AppsFlyer" else null }
        val hosts = s.servers.map { it.host }
        assertTrue("api.example.com" in hosts); assertTrue("t.appsflyer.com" in hosts)
        assertFalse(hosts.any { it.startsWith("com.google") })
        assertEquals("AppsFlyer", s.servers.first().tag)
        assertEquals(listOf("203.0.113.7"), s.ips)
        val kinds = s.secrets.map { it.kind }.toSet()
        assertEquals(setOf("Google API key", "Firebase database", "Private key", "Stripe key"), kinds)
    }
}
