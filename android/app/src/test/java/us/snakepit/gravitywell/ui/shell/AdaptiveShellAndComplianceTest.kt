package us.snakepit.gravitywell.ui.shell

import android.content.Intent
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import us.snakepit.gravitywell.MainActivity

@RunWith(RobolectricTestRunner::class)
class AdaptiveShellAndComplianceTest {

    @Test
    fun `compact layout under 600dp shows bottom bar except when inside attached session console`() {
        val sessionsCompact = AdaptiveShellBreakpoints.resolveLayout(
            widthDp = 411,
            destination = TopLevelDestination.SESSIONS,
            attachedSessionId = "sess_1",
        )
        assertFalse(sessionsCompact.isExpanded)
        assertTrue(sessionsCompact.showBottomNavigationBar)
        assertFalse(sessionsCompact.showNavigationRail)
        assertFalse(sessionsCompact.showTwoPaneSessionsConsole)
        assertFalse(sessionsCompact.showCompactConsoleBackButton)

        val unattachedConsoleCompact = AdaptiveShellBreakpoints.resolveLayout(
            widthDp = 599,
            destination = TopLevelDestination.CONSOLE,
            attachedSessionId = null,
        )
        assertTrue(unattachedConsoleCompact.showBottomNavigationBar)
        assertFalse(unattachedConsoleCompact.showCompactConsoleBackButton)

        val attachedConsoleCompact = AdaptiveShellBreakpoints.resolveLayout(
            widthDp = 411,
            destination = TopLevelDestination.CONSOLE,
            attachedSessionId = "sess_1",
        )
        assertFalse("Bottom nav must hide inside compact attached Console", attachedConsoleCompact.showBottomNavigationBar)
        assertTrue(attachedConsoleCompact.showCompactConsoleBackButton)
    }

    @Test
    fun `expanded layout at or above 600dp shows rail and two-pane split for sessions and console`() {
        val expandedSessions = AdaptiveShellBreakpoints.resolveLayout(
            widthDp = 600,
            destination = TopLevelDestination.SESSIONS,
            attachedSessionId = "sess_1",
        )
        assertTrue(expandedSessions.isExpanded)
        assertTrue(expandedSessions.showNavigationRail)
        assertFalse(expandedSessions.showBottomNavigationBar)
        assertTrue(expandedSessions.showTwoPaneSessionsConsole)
        assertFalse(expandedSessions.showCompactConsoleBackButton)

        val expandedDeck = AdaptiveShellBreakpoints.resolveLayout(
            widthDp = 840,
            destination = TopLevelDestination.DECK,
            attachedSessionId = "sess_1",
        )
        assertTrue(expandedDeck.showNavigationRail)
        assertFalse(expandedDeck.showTwoPaneSessionsConsole)
    }

    @Test
    fun `main activity deep-link extractor handles cold-start and warm-intent extras`() {
        assertNull(MainActivity.extractDeepLink(null))
        assertNull(MainActivity.extractDeepLink(Intent()))

        val valid = Intent().apply {
            putExtra(MainActivity.EXTRA_HOST_ID, "host_aurora")
            putExtra(MainActivity.EXTRA_SESSION_ID, "5b1f0b18-1111-2222-3333-444455556666")
        }
        val parsed = MainActivity.extractDeepLink(valid)
        assertEquals(
            PendingDeepLink(hostId = "host_aurora", sessionId = "5b1f0b18-1111-2222-3333-444455556666"),
            parsed,
        )
    }

    @Test
    fun `vernier rig source audit enforces zero literal hex outside Color_kt and zero chat bubbles`() {
        val srcRoot = locateMainJavaRoot()
        val ktFiles = srcRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("Expected Kotlin source files under $srcRoot", ktFiles.size >= 15)

        val literalColorRegex = Regex("""\bColor\s*\(\s*0x""")
        val dynamicSchemeRegex = Regex("""\bdynamic(?:Dark|Light)ColorScheme\b""")
        val chatBubbleCornerRegex = Regex("""RoundedCornerShape\s*\(\s*(?:1[6-9]|[2-9]\d)\s*\.dp""")

        for (file in ktFiles) {
            val rel = file.relativeTo(srcRoot).invariantSeparatorsPath
            val text = file.readText()

            assertFalse(
                "Forbidden dynamic wallpaper color call in $rel",
                dynamicSchemeRegex.containsMatchIn(text),
            )

            if (rel != "theme/Color.kt") {
                assertFalse(
                    "Literal Color(0x...) forbidden outside theme/Color.kt, found in $rel",
                    literalColorRegex.containsMatchIn(text),
                )
            }

            if (rel.startsWith("ui/console/")) {
                assertFalse(
                    "Forbidden >=16.dp chat bubble RoundedCornerShape in $rel",
                    chatBubbleCornerRegex.containsMatchIn(text),
                )
            }
        }
    }

    private fun locateMainJavaRoot(): File {
        val candidates = listOf(
            File("src/main/java/us/snakepit/gravitywell"),
            File("app/src/main/java/us/snakepit/gravitywell"),
            File("android/app/src/main/java/us/snakepit/gravitywell"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Could not locate main Kotlin source tree from ${File(".").absolutePath}")
    }
}
