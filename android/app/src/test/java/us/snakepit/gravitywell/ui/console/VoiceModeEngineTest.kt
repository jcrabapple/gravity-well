package us.snakepit.gravitywell.ui.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceModeEngineTest {

    @Test
    fun speechFormatterStripsCodeBlocksAndMarkdown() {
        val md = """
            ### Fixed the bug
            Updated `ConsoleSubcomponents.kt` to use a **two-tier** layout.
            ```kotlin
            fun demo() = println("secret")
            ```
            See [docs](https://example.com) for details.
        """.trimIndent()

        val spoken = VoiceSpeechFormatter.toSpokenText(md)
        assertFalse(spoken.contains("```"))
        assertFalse(spoken.contains("println"))
        assertTrue(spoken.contains("Fixed the bug"))
        assertTrue(spoken.contains("two-tier"))
        assertTrue(spoken.contains("Code details are in the chat."))
    }

    @Test
    fun commandParserHandlesPermissionsStopAndEndCall() {
        assertEquals(
            VoiceIntent.EndCall,
            VoiceCommandParser.parseUtterance("End the call", hasPendingPermission = false, isTurnActive = false),
        )
        assertEquals(
            VoiceIntent.StopTurn,
            VoiceCommandParser.parseUtterance("cancel that", hasPendingPermission = false, isTurnActive = true),
        )
        assertEquals(
            VoiceIntent.PermissionDecision("allow_once"),
            VoiceCommandParser.parseUtterance("Allow it just this once", hasPendingPermission = true, isTurnActive = true),
        )
        assertEquals(
            VoiceIntent.PermissionDecision("allow_rule"),
            VoiceCommandParser.parseUtterance("Always allow this", hasPendingPermission = true, isTurnActive = true),
        )
        assertEquals(
            VoiceIntent.PermissionDecision("deny"),
            VoiceCommandParser.parseUtterance("No, don't do that", hasPendingPermission = true, isTurnActive = true),
        )
        assertEquals(
            VoiceIntent.PromptText("Check the failing unit tests"),
            VoiceCommandParser.parseUtterance("Check the failing unit tests", hasPendingPermission = false, isTurnActive = false),
        )
    }

    @Test
    fun destructiveCommandGuardFlagsDangerousShellCommands() {
        assertTrue(VoiceCommandParser.isDestructiveCommand("rm -rf build/"))
        assertTrue(VoiceCommandParser.isDestructiveCommand("sudo systemctl restart foo"))
        assertTrue(VoiceCommandParser.isDestructiveCommand("git push --force origin main"))
        assertFalse(VoiceCommandParser.isDestructiveCommand("pytest -q tests/"))
    }
}
