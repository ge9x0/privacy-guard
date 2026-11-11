package com.privacyguard

import android.util.Log
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.privacyguard.ui.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Hap cdo tab dhe shtyp cdo buton/switch qe gjen (vetem ne emulator: perfshin edhe Remove/Block).
 * Testi deshton nese app-i rrezohet ose ngec. Butonat qe hapin ekrane te sistemit kthehen me Back.
 */
@RunWith(AndroidJUnit4::class)
class ClickEverythingTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val dev = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val tabs = listOf("Today", "Activity", "Apps", "Blocking")
    /** Hapin dialogje te sistemit qe s'mbyllen dot me siguri, ose fillojne pairing-un. */
    private val skip = setOf("Start", "Pair", "Reconnect")

    private fun label(n: SemanticsNode): String =
        (n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
            ?: n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")).orEmpty()

    private fun backToApp() {
        val pkg = rule.activity.packageName
        // Kthehu te i njejti Activity (Back mund ta mbyllte ate vete); REORDER_TO_FRONT e sjell perpara pa krijuar te ri.
        if (dev.currentPackageName != pkg) {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            ctx.startActivity(android.content.Intent(ctx, MainActivity::class.java).addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            dev.waitForIdle(1500); Thread.sleep(1000)
        }
        rule.waitForIdle()
    }

    private fun closeDialogs() {
        repeat(3) {
            val c = rule.onAllNodes(isDialog()).fetchSemanticsNodes().size
            if (c == 0) return
            val btn = rule.onAllNodes(hasAnyAncestor(isDialog()) and hasClickAction() and
                (hasText("Cancel") or hasText("Close") or hasText("Dismiss"))).fetchSemanticsNodes()
            if (btn.isNotEmpty()) rule.onAllNodes(hasAnyAncestor(isDialog()) and hasClickAction() and
                (hasText("Cancel") or hasText("Close") or hasText("Dismiss")))[0].performClick()
            else dev.pressBack()
            rule.waitForIdle()
        }
    }

    private fun openTab(t: String) {
        rule.onNode(hasText(t) and hasClickAction() and hasAnyAncestor(hasTestTag("nav"))).performClick()
        rule.waitForIdle(); Thread.sleep(1500); rule.waitForIdle()
    }

    @Test fun everyButtonOnEveryTab() {
        Thread.sleep(3000)
        val clicked = ArrayList<String>()
        for (t in tabs) {
            openTab(t)
            var i = 0
            while (i < 120) {
                val nodes = rule.onAllNodes(hasClickAction() and !hasAnyAncestor(hasTestTag("nav")) and !hasAnyAncestor(isDialog()))
                    .fetchSemanticsNodes()
                if (i >= nodes.size) break
                val l = label(nodes[i])
                if (l in skip) { i++; continue }
                Log.i("PG.Test", "[$t] click #$i '$l'")
                runCatching {
                    rule.onAllNodes(hasClickAction() and !hasAnyAncestor(hasTestTag("nav")) and !hasAnyAncestor(isDialog()))[i]
                        .performScrollTo().performClick()
                }.onFailure { Log.w("PG.Test", "could not click '$l': ${it.message}") }
                rule.waitForIdle(); Thread.sleep(700)
                backToApp(); closeDialogs(); rule.waitForIdle()
                clicked += "[$t] $l"
                openTab(t)   // nje buton mund te kete nderruar tab-in
                i++
            }
        }
        Log.i("PG.Test", "clicked ${clicked.size}: " + clicked.joinToString(" | "))
        assert(clicked.size > 20) { "too few buttons found: $clicked" }
    }
}
