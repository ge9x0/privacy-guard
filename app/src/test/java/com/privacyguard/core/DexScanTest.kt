package com.privacyguard.core

import com.privacyguard.apps.DexScan
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class DexScanTest {
    @Test fun readsPackagesFromARealDex() {
        // the debug build's own dex files (present after assembleDebug)
        val dex = File("build/intermediates").walkTopDown().filter { it.name.matches(Regex("classes\\d*\\.dex")) }.toList()
        assumeTrue(dex.isNotEmpty())
        val out = HashSet<String>()
        for (f in dex) DexScan.readTypes(f.readBytes(), out)
        assertTrue(out.any { it.startsWith("com.privacyguard") })
        assertTrue(out.any { it.startsWith("androidx.compose") })
        assertTrue(out.none { it.contains('/') || it.endsWith(";") })
    }

    @Test fun ignoresNonDex() {
        val out = HashSet<String>()
        DexScan.readTypes(ByteArray(200) { 7 }, out)
        assertTrue(out.isEmpty())
    }
}
