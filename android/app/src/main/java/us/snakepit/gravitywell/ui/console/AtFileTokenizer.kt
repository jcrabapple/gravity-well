package us.snakepit.gravitywell.ui.console

import androidx.compose.runtime.Immutable
import us.snakepit.gravitywell.net.AvailableCommandDto

@Immutable
data class AtFileMentionQuery(
    val query: String,
    val startIndex: Int, // index of '@'
    val endIndex: Int, // cursor index
)

object ComposerAutocompleteHelper {

    private val allowedPathChars = Regex("""^[A-Za-z0-9._/\-]*$""")

    /**
     * Detects an active `@<token>` mention immediately preceding [cursorIndex].
     * Triggers only when `@` is at start-of-text or preceded by whitespace,
     * avoiding email addresses like `user@example.com`.
     */
    fun detectAtMention(text: String, cursorIndex: Int): AtFileMentionQuery? {
        if (text.isEmpty()) return null
        val safeCursor = cursorIndex.coerceIn(0, text.length)
        var pos = safeCursor - 1
        while (pos >= 0 && !text[pos].isWhitespace()) {
            pos--
        }
        val tokenStart = pos + 1
        if (tokenStart >= safeCursor) return null
        if (text[tokenStart] != '@') return null
        if (tokenStart > 0 && !text[tokenStart - 1].isWhitespace()) return null

        val rawQuery = text.substring(tokenStart + 1, safeCursor)
        if (!allowedPathChars.matches(rawQuery)) return null
        return AtFileMentionQuery(
            query = rawQuery,
            startIndex = tokenStart,
            endIndex = safeCursor,
        )
    }

    /**
     * Replaces the active `@<token>` span with `@<selectedPath> ` and returns `(newText, newCursor)`.
     */
    fun applyAtMentionReplacement(
        text: String,
        mention: AtFileMentionQuery,
        selectedPath: String,
    ): Pair<String, Int> {
        val safeStart = mention.startIndex.coerceIn(0, text.length)
        val safeEnd = mention.endIndex.coerceIn(safeStart, text.length)
        val replacement = "@$selectedPath "
        val updated = buildString(text.length + replacement.length) {
            append(text, 0, safeStart)
            append(replacement)
            append(text, safeEnd, text.length)
        }
        return updated to (safeStart + replacement.length)
    }

    /**
     * Determines whether slash command chips should be visible and filters commands when typing `/prefix`.
     */
    fun filterSlashCommands(
        text: String,
        forceShowSlashBar: Boolean,
        available: List<AvailableCommandDto>,
    ): List<AvailableCommandDto> {
        val catalog = available.ifEmpty { DEFAULT_FALLBACK_COMMANDS }
        val trimmedStart = text.trimStart()
        val isSlashTyping = trimmedStart.startsWith("/") && !trimmedStart.contains(' ') && !trimmedStart.contains('\n')
        if (!forceShowSlashBar && !isSlashTyping) return emptyList()

        val prefix = if (isSlashTyping) trimmedStart.removePrefix("/").lowercase() else ""
        return if (prefix.isEmpty()) {
            catalog
        } else {
            catalog.filter { it.name.lowercase().startsWith(prefix) }
        }
    }

    val DEFAULT_FALLBACK_COMMANDS: List<AvailableCommandDto> = listOf(
        AvailableCommandDto(name = "plan", description = "Switch to structured planning workflow"),
        AvailableCommandDto(name = "compact", description = "Compact conversation context"),
        AvailableCommandDto(name = "clear", description = "Reset active context window"),
    )
}
