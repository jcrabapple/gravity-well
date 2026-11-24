package us.snakepit.gravitywell.ui.console

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import us.snakepit.gravitywell.theme.GravityWellColors
import us.snakepit.gravitywell.theme.GravityWellTheme

@Immutable
sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class CodeFence(val language: String, val code: String, val isClosed: Boolean) : MarkdownBlock
    data class ListBlock(val ordered: Boolean, val items: List<String>) : MarkdownBlock
    data class BlockQuote(val text: String) : MarkdownBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MarkdownBlock
    data object Rule : MarkdownBlock
}

/**
 * Incremental streaming Markdown parser supporting headings, bold/italic/strikethrough,
 * inline code, links, ordered & unordered lists, blockquotes, pipe tables, and fenced
 * code blocks (including unclosed live-streaming fences).
 */
object ConsoleMarkdownParser {
    private val headingRegex = Regex("""^(#{1,6})\s+(.+)$""")
    private val unorderedRegex = Regex("""^\s*[-*+]\s+(.+)$""")
    private val orderedRegex = Regex("""^\s*\d+\.\s+(.+)$""")
    private val hrRegex = Regex("""^\s*(?:---+|\*\*\*+|___+)\s*$""")
    private val tableSepRegex = Regex("""^\s*\|?(?:\s*:?-{2,}:?\s*\|)+\s*:?-{2,}:?\s*\|?\s*$""")

    fun parseBlocks(markdown: String): List<MarkdownBlock> {
        if (markdown.isEmpty()) return emptyList()
        val lines = markdown.replace("\r\n", "\n").lines()
        val blocks = mutableListOf<MarkdownBlock>()

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()

            // 1. Fenced code block (``` or ~~~), tolerant of unclosed streaming fence
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                val fenceMarker = trimmed.take(3)
                val lang = trimmed.drop(3).trim()
                val codeLines = mutableListOf<String>()
                var closed = false
                i++
                while (i < lines.size) {
                    if (lines[i].trimStart().startsWith(fenceMarker)) {
                        closed = true
                        i++
                        break
                    }
                    codeLines += lines[i]
                    i++
                }
                blocks += MarkdownBlock.CodeFence(
                    language = lang,
                    code = codeLines.joinToString("\n"),
                    isClosed = closed,
                )
                continue
            }

            // 2. Blank line
            if (line.isBlank()) {
                i++
                continue
            }

            // 3. Horizontal rule
            if (hrRegex.matches(line)) {
                blocks += MarkdownBlock.Rule
                i++
                continue
            }

            // 4. ATX Heading
            val headingMatch = headingRegex.matchEntire(line)
            if (headingMatch != null) {
                blocks += MarkdownBlock.Heading(
                    level = headingMatch.groupValues[1].length,
                    text = headingMatch.groupValues[2].trim(),
                )
                i++
                continue
            }

            // 5. Pipe table (header row + separator row)
            if (line.contains('|') && i + 1 < lines.size && tableSepRegex.matches(lines[i + 1])) {
                val headers = splitPipeRow(line)
                i += 2
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                    rows += splitPipeRow(lines[i])
                    i++
                }
                blocks += MarkdownBlock.Table(headers = headers, rows = rows)
                continue
            }

            // 6. Blockquote
            if (trimmed.startsWith(">")) {
                val quoteLines = mutableListOf<String>()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    quoteLines += lines[i].trimStart().removePrefix(">").trimStart()
                    i++
                }
                blocks += MarkdownBlock.BlockQuote(quoteLines.joinToString("\n"))
                continue
            }

            // 7. Unordered list
            val ulMatch = unorderedRegex.matchEntire(line)
            if (ulMatch != null) {
                val items = mutableListOf<String>()
                while (i < lines.size) {
                    val m = unorderedRegex.matchEntire(lines[i]) ?: break
                    items += m.groupValues[1]
                    i++
                }
                blocks += MarkdownBlock.ListBlock(ordered = false, items = items)
                continue
            }

            // 8. Ordered list
            val olMatch = orderedRegex.matchEntire(line)
            if (olMatch != null) {
                val items = mutableListOf<String>()
                while (i < lines.size) {
                    val m = orderedRegex.matchEntire(lines[i]) ?: break
                    items += m.groupValues[1]
                    i++
                }
                blocks += MarkdownBlock.ListBlock(ordered = true, items = items)
                continue
            }

            // 9. Paragraph (consume until blank line or structural starter)
            val paraLines = mutableListOf<String>()
            while (i < lines.size) {
                val cur = lines[i]
                val curTrim = cur.trimStart()
                if (cur.isBlank() ||
                    curTrim.startsWith("```") ||
                    curTrim.startsWith("~~~") ||
                    headingRegex.matches(cur) ||
                    hrRegex.matches(cur) ||
                    curTrim.startsWith(">") ||
                    unorderedRegex.matches(cur) ||
                    orderedRegex.matches(cur) ||
                    (cur.contains('|') && i + 1 < lines.size && tableSepRegex.matches(lines[i + 1]))
                ) {
                    break
                }
                paraLines += cur
                i++
            }
            blocks += MarkdownBlock.Paragraph(paraLines.joinToString("\n"))
        }

        return blocks
    }

    private fun splitPipeRow(row: String): List<String> =
        row.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

    fun parseInline(text: String, colors: GravityWellColors): AnnotatedString = buildAnnotatedString {
        var idx = 0
        while (idx < text.length) {
            when {
                // Inline code `...`
                text[idx] == '`' -> {
                    val close = text.indexOf('`', idx + 1)
                    if (close > idx) {
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = colors.stratumRaised,
                                color = colors.signalCyan,
                                fontSize = 12.5.sp,
                            ),
                        ) {
                            append("\u00A0")
                            append(text.substring(idx + 1, close))
                            append("\u00A0")
                        }
                        idx = close + 1
                    } else {
                        append('`')
                        idx++
                    }
                }

                // Bold **...**
                text.startsWith("**", idx) -> {
                    val close = text.indexOf("**", idx + 2)
                    if (close > idx) {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = colors.inkPrimary)) {
                            append(text.substring(idx + 2, close))
                        }
                        idx = close + 2
                    } else {
                        append("**")
                        idx += 2
                    }
                }

                // Strikethrough ~~...~~
                text.startsWith("~~", idx) -> {
                    val close = text.indexOf("~~", idx + 2)
                    if (close > idx) {
                        withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = colors.inkMuted)) {
                            append(text.substring(idx + 2, close))
                        }
                        idx = close + 2
                    } else {
                        append("~~")
                        idx += 2
                    }
                }

                // Italic *...*
                text[idx] == '*' -> {
                    val close = text.indexOf('*', idx + 1)
                    if (close > idx && !text.substring(idx + 1, close).contains('\n')) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(text.substring(idx + 1, close))
                        }
                        idx = close + 1
                    } else {
                        append('*')
                        idx++
                    }
                }

                // Link [label](url)
                text[idx] == '[' -> {
                    val closeBracket = text.indexOf(']', idx + 1)
                    val openParen = if (closeBracket > idx && closeBracket + 1 < text.length && text[closeBracket + 1] == '(') {
                        closeBracket + 1
                    } else {
                        -1
                    }
                    val closeParen = if (openParen > 0) text.indexOf(')', openParen + 1) else -1
                    if (closeParen > openParen) {
                        val label = text.substring(idx + 1, closeBracket)
                        withStyle(
                            SpanStyle(
                                color = colors.signalCyan,
                                textDecoration = TextDecoration.Underline,
                            ),
                        ) {
                            append(label)
                        }
                        idx = closeParen + 1
                    } else {
                        append('[')
                        idx++
                    }
                }

                else -> {
                    append(text[idx])
                    idx++
                }
            }
        }
    }
}

@Composable
fun MarkdownStratumContent(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing
    val context = LocalContext.current

    val blocks = remember(markdown) { ConsoleMarkdownParser.parseBlocks(markdown) }

    SelectionContainer(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            blocks.forEach { block ->
                when (block) {
                    is MarkdownBlock.Heading -> {
                        val headingStyle = when (block.level) {
                            1, 2 -> type.headlineSection
                            else -> type.titleBar
                        }
                        Text(
                            text = ConsoleMarkdownParser.parseInline(block.text, colors),
                            style = headingStyle,
                            color = colors.inkPrimary,
                            modifier = Modifier.padding(horizontal = spacing.proseHorizontalMargin),
                        )
                    }

                    is MarkdownBlock.Paragraph -> {
                        Text(
                            text = ConsoleMarkdownParser.parseInline(block.text, colors),
                            style = type.bodyProse,
                            color = colors.inkPrimary,
                            modifier = Modifier.padding(horizontal = spacing.proseHorizontalMargin),
                        )
                    }

                    is MarkdownBlock.ListBlock -> {
                        Column(
                            modifier = Modifier.padding(horizontal = spacing.proseHorizontalMargin),
                            verticalArrangement = Arrangement.spacedBy(spacing.xs),
                        ) {
                            block.items.forEachIndexed { idx, itemText ->
                                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                                    Text(
                                        text = if (block.ordered) "${idx + 1}." else "•",
                                        style = type.monoCode,
                                        color = colors.signalCyan,
                                    )
                                    Text(
                                        text = ConsoleMarkdownParser.parseInline(itemText, colors),
                                        style = type.bodyProse,
                                        color = colors.inkPrimary,
                                    )
                                }
                            }
                        }
                    }

                    is MarkdownBlock.BlockQuote -> {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = spacing.proseHorizontalMargin)
                                .fillMaxWidth()
                                .clip(shapes.sm)
                                .background(colors.stratumBase)
                                .border(spacing.hairlineWidth, colors.hairline, shapes.sm),
                        ) {
                            Box(
                                modifier = Modifier
                                    .widthIn(min = spacing.statusRailWidth)
                                    .background(colors.signalCyan)
                                    .padding(vertical = spacing.md),
                            )
                            Text(
                                text = ConsoleMarkdownParser.parseInline(block.text, colors),
                                style = type.bodyCompact,
                                color = colors.inkSecondary,
                                modifier = Modifier.padding(spacing.md),
                            )
                        }
                    }

                    is MarkdownBlock.CodeFence -> {
                        var copied by remember(block.code) { mutableStateOf(false) }
                        Column(
                            modifier = Modifier
                                .padding(horizontal = spacing.proseHorizontalMargin)
                                .fillMaxWidth()
                                .clip(shapes.md)
                                .background(colors.stratumWell)
                                .border(spacing.hairlineWidth, colors.hairline, shapes.md),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(colors.stratumBase)
                                    .padding(start = spacing.md, end = spacing.xs),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = buildString {
                                        append(block.language.ifBlank { "code" }.lowercase())
                                        if (!block.isClosed) append(" · streaming")
                                    },
                                    style = type.monoMicro,
                                    color = if (block.isClosed) colors.inkMuted else colors.signalCyan,
                                )
                                Box(
                                    modifier = Modifier
                                        .defaultMinSize(
                                            minWidth = spacing.touchFloor,
                                            minHeight = spacing.touchFloor,
                                        )
                                        .clip(shapes.sm)
                                        .clickable {
                                            copyToClipboard(context, block.code)
                                            copied = true
                                        }
                                        .padding(horizontal = spacing.sm),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = if (copied) "Copied" else "Copy",
                                        style = type.labelSmallUi,
                                        color = if (copied) colors.signalEmerald else colors.signalCyan,
                                    )
                                }
                            }
                            HorizontalDivider(thickness = spacing.hairlineWidth, color = colors.hairline)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(spacing.md),
                            ) {
                                Text(
                                    text = block.code,
                                    style = type.monoCode,
                                    color = colors.inkPrimary,
                                )
                            }
                        }
                    }

                    is MarkdownBlock.Table -> {
                        Box(
                            modifier = Modifier
                                .padding(horizontal = spacing.proseHorizontalMargin)
                                .clip(shapes.md)
                                .border(spacing.hairlineWidth, colors.hairline, shapes.md)
                                .horizontalScroll(rememberScrollState()),
                        ) {
                            Column {
                                Row(modifier = Modifier.background(colors.stratumRaised)) {
                                    block.headers.forEach { header ->
                                        Text(
                                            text = header,
                                            style = type.labelSmallUi,
                                            color = colors.inkPrimary,
                                            modifier = Modifier
                                                .widthIn(min = 96.dp)
                                                .padding(horizontal = spacing.md, vertical = spacing.sm),
                                        )
                                    }
                                }
                                HorizontalDivider(thickness = spacing.hairlineWidth, color = colors.hairline)
                                block.rows.forEachIndexed { rowIdx, row ->
                                    Row(modifier = Modifier.background(colors.stratumBase)) {
                                        block.headers.indices.forEach { colIdx ->
                                            Text(
                                                text = row.getOrNull(colIdx).orEmpty(),
                                                style = type.bodyCompact,
                                                color = colors.inkSecondary,
                                                modifier = Modifier
                                                    .widthIn(min = 96.dp)
                                                    .padding(horizontal = spacing.md, vertical = spacing.sm),
                                            )
                                        }
                                    }
                                    if (rowIdx < block.rows.lastIndex) {
                                        HorizontalDivider(thickness = spacing.hairlineWidth, color = colors.hairline)
                                    }
                                }
                            }
                        }
                    }

                    MarkdownBlock.Rule -> {
                        HorizontalDivider(
                            thickness = spacing.hairlineWidth,
                            color = colors.hairline,
                            modifier = Modifier.padding(horizontal = spacing.proseHorizontalMargin),
                        )
                    }
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, code: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("Gravity Well Code", code))
}
