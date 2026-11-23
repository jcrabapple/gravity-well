package us.snakepit.gravitywell.ui.console

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

enum class DiffLineKind {
    HUNK_HEADER,
    CONTEXT,
    ADDED,
    REMOVED,
}

sealed interface WordDiffSpan {
    val text: String
    data class Unchanged(override val text: String) : WordDiffSpan
    data class Removed(override val text: String) : WordDiffSpan
    data class Added(override val text: String) : WordDiffSpan
}

@Immutable
data class DiffLine(
    val kind: DiffLineKind,
    val text: String,
    val oldLineNumber: Int? = null,
    val newLineNumber: Int? = null,
    val wordSpans: List<WordDiffSpan>? = null,
)

@Immutable
data class DiffHunk(
    val header: String,
    val lines: List<DiffLine>,
)

@Immutable
data class ParsedDiff(
    val filePath: String? = null,
    val hunks: List<DiffHunk>,
) {
    val addedCount: Int
        get() = hunks.sumOf { h -> h.lines.count { it.kind == DiffLineKind.ADDED } }
    val removedCount: Int
        get() = hunks.sumOf { h -> h.lines.count { it.kind == DiffLineKind.REMOVED } }
}

/**
 * Unified + Word-level diff parser and LCS highlighter for Live Session Console tool gutters.
 */
object DiffEngine {
    private val hunkHeaderRegex = Regex("""^@@\s*-(\d+)(?:,\d+)?\s+\+(\d+)(?:,\d+)?\s*@@.*$""")
    private val wordTokenRegex = Regex("""\s+|\w+|[^\w\s]+""")

    fun extractFromToolPayload(
        rawInput: JsonObject?,
        rawOutput: JsonObject?,
        explicitDiff: JsonElement?,
    ): ParsedDiff? {
        if (explicitDiff != null) {
            parseJsonDiffElement(explicitDiff)?.let { return it }
        }
        if (rawOutput != null) {
            rawOutput["diff"]?.let { parseJsonDiffElement(it) }?.let { return it }
            parseOldNewObject(rawOutput)?.let { return it }
        }
        if (rawInput != null) {
            rawInput["diff"]?.let { parseJsonDiffElement(it) }?.let { return it }
            parseOldNewObject(rawInput)?.let { return it }
        }
        return null
    }

    private fun parseJsonDiffElement(el: JsonElement): ParsedDiff? {
        return when (el) {
            is JsonPrimitive -> el.contentOrNull?.let { parseUnifiedDiff(it) }
            is JsonObject -> {
                val unified = (el["unified"] as? JsonPrimitive)?.contentOrNull
                    ?: (el["patch"] as? JsonPrimitive)?.contentOrNull
                    ?: (el["diff"] as? JsonPrimitive)?.contentOrNull
                if (!unified.isNullOrBlank()) {
                    parseUnifiedDiff(
                        unified,
                        fallbackPath = (el["path"] as? JsonPrimitive)?.contentOrNull
                            ?: (el["file"] as? JsonPrimitive)?.contentOrNull,
                    )
                } else {
                    parseOldNewObject(el)
                }
            }
            is JsonArray -> {
                val sub = el.mapNotNull { parseJsonDiffElement(it) }
                if (sub.isEmpty()) null
                else ParsedDiff(
                    filePath = sub.firstNotNullOfOrNull { it.filePath },
                    hunks = sub.flatMap { it.hunks },
                )
            }
        }
    }

    private fun parseOldNewObject(obj: JsonObject): ParsedDiff? {
        val oldStr = listOf("old_string", "oldText", "old_str", "before", "original")
            .firstNotNullOfOrNull { k -> (obj[k] as? JsonPrimitive)?.contentOrNull }
        val newStr = listOf("new_string", "newText", "new_str", "after", "replacement", "content")
            .firstNotNullOfOrNull { k -> (obj[k] as? JsonPrimitive)?.contentOrNull }
        if (oldStr == null && newStr == null) return null
        if (oldStr == null && obj.keys.none { it in setOf("old_string", "oldText", "old_str", "before") }) {
            return null
        }
        val path = listOf("path", "file_path", "filePath", "target_file")
            .firstNotNullOfOrNull { k -> (obj[k] as? JsonPrimitive)?.contentOrNull }
        return buildDiffFromBeforeAfter(oldStr.orEmpty(), newStr.orEmpty(), filePath = path)
    }

    fun parseUnifiedDiff(rawDiff: String, fallbackPath: String? = null): ParsedDiff? {
        val lines = rawDiff.replace("\r\n", "\n").lines()
        if (lines.isEmpty()) return null

        var detectedPath: String? = fallbackPath
        val hunks = mutableListOf<DiffHunk>()
        var currentHeader: String? = null
        var currentLines = mutableListOf<DiffLine>()
        var oldLine = 1
        var newLine = 1
        var sawDiffSyntax = false

        fun flushHunk() {
            if (currentLines.isNotEmpty()) {
                val enriched = attachWordSpansToHunk(currentLines)
                hunks += DiffHunk(
                    header = currentHeader ?: "@@ -1 +1 @@",
                    lines = enriched,
                )
                currentLines = mutableListOf()
            }
        }

        for (line in lines) {
            when {
                line.startsWith("diff --git ") -> {
                    flushHunk()
                    sawDiffSyntax = true
                }
                line.startsWith("--- ") -> {
                    sawDiffSyntax = true
                }
                line.startsWith("+++ ") -> {
                    sawDiffSyntax = true
                    val candidate = line.removePrefix("+++ ").trim().removePrefix("b/")
                    if (candidate.isNotBlank() && candidate != "/dev/null") {
                        detectedPath = candidate
                    }
                }
                line.startsWith("@@") -> {
                    flushHunk()
                    sawDiffSyntax = true
                    currentHeader = line
                    val match = hunkHeaderRegex.matchEntire(line)
                    oldLine = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
                    newLine = match?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 1
                    currentLines += DiffLine(kind = DiffLineKind.HUNK_HEADER, text = line)
                }
                line.startsWith("+") && !line.startsWith("+++") -> {
                    sawDiffSyntax = true
                    currentLines += DiffLine(
                        kind = DiffLineKind.ADDED,
                        text = line.substring(1),
                        oldLineNumber = null,
                        newLineNumber = newLine++,
                    )
                }
                line.startsWith("-") && !line.startsWith("---") -> {
                    sawDiffSyntax = true
                    currentLines += DiffLine(
                        kind = DiffLineKind.REMOVED,
                        text = line.substring(1),
                        oldLineNumber = oldLine++,
                        newLineNumber = null,
                    )
                }
                line.startsWith(" ") -> {
                    if (sawDiffSyntax) {
                        currentLines += DiffLine(
                            kind = DiffLineKind.CONTEXT,
                            text = line.substring(1),
                            oldLineNumber = oldLine++,
                            newLineNumber = newLine++,
                        )
                    }
                }
            }
        }
        flushHunk()
        if (!sawDiffSyntax || hunks.isEmpty()) return null
        return ParsedDiff(filePath = detectedPath, hunks = hunks)
    }

    fun buildDiffFromBeforeAfter(
        before: String,
        after: String,
        filePath: String? = null,
    ): ParsedDiff? {
        if (before == after) return null
        val oldLines = if (before.isEmpty()) emptyList() else before.replace("\r\n", "\n").lines()
        val newLines = if (after.isEmpty()) emptyList() else after.replace("\r\n", "\n").lines()
        val lcs = computeLineLcs(oldLines, newLines)

        val diffLines = mutableListOf<DiffLine>(
            DiffLine(
                kind = DiffLineKind.HUNK_HEADER,
                text = "@@ -1,${oldLines.size} +1,${newLines.size} @@",
            ),
        )
        var i = 0
        var j = 0
        var k = 0
        var oldNo = 1
        var newNo = 1
        while (i < oldLines.size || j < newLines.size) {
            if (k < lcs.size && i < oldLines.size && j < newLines.size &&
                oldLines[i] == lcs[k] && newLines[j] == lcs[k]
            ) {
                diffLines += DiffLine(
                    kind = DiffLineKind.CONTEXT,
                    text = oldLines[i],
                    oldLineNumber = oldNo++,
                    newLineNumber = newNo++,
                )
                i++
                j++
                k++
            } else {
                while (i < oldLines.size && (k >= lcs.size || oldLines[i] != lcs[k])) {
                    diffLines += DiffLine(
                        kind = DiffLineKind.REMOVED,
                        text = oldLines[i],
                        oldLineNumber = oldNo++,
                    )
                    i++
                }
                while (j < newLines.size && (k >= lcs.size || newLines[j] != lcs[k])) {
                    diffLines += DiffLine(
                        kind = DiffLineKind.ADDED,
                        text = newLines[j],
                        newLineNumber = newNo++,
                    )
                    j++
                }
            }
        }
        val enriched = attachWordSpansToHunk(diffLines)
        return ParsedDiff(
            filePath = filePath,
            hunks = listOf(
                DiffHunk(
                    header = "@@ -1,${oldLines.size} +1,${newLines.size} @@",
                    lines = enriched,
                ),
            ),
        )
    }

    /**
     * Pairs contiguous REMOVED + ADDED runs in a hunk and attaches token-level word spans
     * so toggling UNIFIED <-> WORD requires zero re-parsing during scroll.
     */
    internal fun attachWordSpansToHunk(lines: List<DiffLine>): List<DiffLine> {
        val out = lines.toMutableList()
        var idx = 0
        while (idx < out.size) {
            if (out[idx].kind != DiffLineKind.REMOVED) {
                idx++
                continue
            }
            val remStart = idx
            while (idx < out.size && out[idx].kind == DiffLineKind.REMOVED) idx++
            val remEnd = idx
            val addStart = idx
            while (idx < out.size && out[idx].kind == DiffLineKind.ADDED) idx++
            val addEnd = idx

            val pairCount = minOf(remEnd - remStart, addEnd - addStart)
            for (p in 0 until pairCount) {
                val rIdx = remStart + p
                val aIdx = addStart + p
                val (remSpans, addSpans) = computeWordSpans(out[rIdx].text, out[aIdx].text)
                out[rIdx] = out[rIdx].copy(wordSpans = remSpans)
                out[aIdx] = out[aIdx].copy(wordSpans = addSpans)
            }
        }
        return out
    }

    fun computeWordSpans(oldLine: String, newLine: String): Pair<List<WordDiffSpan>, List<WordDiffSpan>> {
        val oldTokens = wordTokenRegex.findAll(oldLine).map { it.value }.toList()
        val newTokens = wordTokenRegex.findAll(newLine).map { it.value }.toList()
        if (oldTokens.size * newTokens.size > 40_000) {
            return listOf(WordDiffSpan.Removed(oldLine)) to listOf(WordDiffSpan.Added(newLine))
        }
        val lcs = computeTokenLcs(oldTokens, newTokens)
        val remSpans = mutableListOf<WordDiffSpan>()
        val addSpans = mutableListOf<WordDiffSpan>()

        var i = 0
        var j = 0
        var k = 0
        while (i < oldTokens.size || j < newTokens.size) {
            if (k < lcs.size && i < oldTokens.size && j < newTokens.size &&
                oldTokens[i] == lcs[k] && newTokens[j] == lcs[k]
            ) {
                remSpans += WordDiffSpan.Unchanged(oldTokens[i])
                addSpans += WordDiffSpan.Unchanged(newTokens[j])
                i++
                j++
                k++
            } else {
                val remBuf = StringBuilder()
                while (i < oldTokens.size && (k >= lcs.size || oldTokens[i] != lcs[k])) {
                    remBuf.append(oldTokens[i++])
                }
                if (remBuf.isNotEmpty()) remSpans += WordDiffSpan.Removed(remBuf.toString())

                val addBuf = StringBuilder()
                while (j < newTokens.size && (k >= lcs.size || newTokens[j] != lcs[k])) {
                    addBuf.append(newTokens[j++])
                }
                if (addBuf.isNotEmpty()) addSpans += WordDiffSpan.Added(addBuf.toString())
            }
        }
        return remSpans to addSpans
    }

    private fun computeLineLcs(a: List<String>, b: List<String>): List<String> {
        if (a.isEmpty() || b.isEmpty() || a.size * b.size > 100_000) return emptyList()
        return computeTokenLcs(a, b)
    }

    private fun computeTokenLcs(a: List<String>, b: List<String>): List<String> {
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) {
            for (j in b.indices.reversed()) {
                dp[i][j] = if (a[i] == b[j]) 1 + dp[i + 1][j + 1] else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
        val res = ArrayList<String>(dp[0][0])
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] == b[j] -> {
                    res += a[i]
                    i++
                    j++
                }
                dp[i + 1][j] >= dp[i][j + 1] -> i++
                else -> j++
            }
        }
        return res
    }
}
