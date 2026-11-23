package us.snakepit.gravitywell.ui.console

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import us.snakepit.gravitywell.net.AvailableCommandDto
import us.snakepit.gravitywell.net.PendingPermissionDto
import us.snakepit.gravitywell.net.PendingQuestionDto
import us.snakepit.gravitywell.net.QueueItemDto
import us.snakepit.gravitywell.net.UsageDto
import us.snakepit.gravitywell.theme.GravityWellTheme
import us.snakepit.gravitywell.ui.sessions.WorkspaceFileItemDto
import us.snakepit.gravitywell.ui.sessions.basenameOfPath
import us.snakepit.gravitywell.ui.sessions.compactTimestamp

@Composable
fun ConsoleTopBar(
    title: String,
    titleSource: String,
    status: String,
    isTurnActive: Boolean,
    showBackButton: Boolean,
    onBack: () -> Unit,
    onRenameTitle: () -> Unit,
    onCancelTurn: () -> Unit,
    onOpenArtifacts: () -> Unit,
    onOpenWorkspaceRules: () -> Unit,
    onCloseSession: () -> Unit,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    var menuExpanded by remember { mutableStateOf(false) }

    val statusColor = when (status) {
        "running", "starting" -> colors.signalCyan
        "awaiting_approval", "awaiting_question" -> colors.signalAmber
        "idle" -> colors.signalEmerald
        "error" -> colors.signalCrimson
        else -> colors.inkMuted
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .background(colors.stratumBase)
            .padding(horizontal = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBackButton) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to Sessions",
                    tint = colors.inkPrimary,
                )
            }
        } else {
            Spacer(modifier = Modifier.width(spacing.sm))
        }

        Row(
            modifier = Modifier
                .weight(1f)
                .defaultMinSize(minHeight = spacing.touchFloor)
                .clickable(onClick = onRenameTitle)
                .padding(horizontal = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Text(
                text = title,
                style = type.titleBar,
                color = colors.inkPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (titleSource == "gateway") {
                Box(
                    modifier = Modifier
                        .clip(shapes.sm)
                        .background(colors.signalVioletDim)
                        .border(spacing.hairlineWidth, colors.signalViolet, shapes.sm)
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                ) {
                    Text("GATEWAY", style = type.monoMicro, color = colors.signalViolet)
                }
            }
        }

        Box(
            modifier = Modifier
                .clip(shapes.sm)
                .background(colors.stratumRaised)
                .border(spacing.hairlineWidth, statusColor, shapes.sm)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                text = status.uppercase(),
                style = type.labelTelemetry,
                color = statusColor,
            )
        }

        if (isTurnActive) {
            Spacer(modifier = Modifier.width(spacing.xs))
            OutlinedButton(
                onClick = onCancelTurn,
                shape = shapes.sm,
                border = BorderStroke(spacing.hairlineWidth, colors.signalCrimson),
                contentPadding = PaddingValues(horizontal = spacing.sm, vertical = 0.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = colors.signalCrimsonDim,
                    contentColor = colors.signalCrimson,
                ),
                modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
            ) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = "Cancel active turn",
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text("STOP", style = type.labelTelemetry)
            }
        }

        Box {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Console options",
                    tint = colors.inkSecondary,
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                containerColor = colors.stratumRaised,
                tonalElevation = spacing.elevationZero,
                shadowElevation = spacing.elevationZero,
                border = BorderStroke(spacing.hairlineWidth, colors.hairlineStrong),
            ) {
                DropdownMenuItem(
                    text = { Text("brain/ Artifacts", style = type.bodyCompact, color = colors.inkPrimary) },
                    onClick = {
                        menuExpanded = false
                        onOpenArtifacts()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Workspace Rules", style = type.bodyCompact, color = colors.inkPrimary) },
                    onClick = {
                        menuExpanded = false
                        onOpenWorkspaceRules()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Close Session", style = type.bodyCompact, color = colors.signalCrimson) },
                    onClick = {
                        menuExpanded = false
                        onCloseSession()
                    },
                )
            }
        }
    }
}

@Composable
fun TelemetrySubHeader(
    modelId: String,
    thoughtLevel: String,
    modeId: String,
    usage: UsageDto,
    streamIndicator: ConsoleStreamIndicator,
    lastCursor: Long,
    onOpenConfigSheet: () -> Unit,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    val fillFraction = if (usage.size > 0L) {
        (usage.used.toFloat() / usage.size.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    val (streamText, streamColor) = when (streamIndicator) {
        ConsoleStreamIndicator.LIVE -> "LIVE #$lastCursor" to colors.signalEmerald
        ConsoleStreamIndicator.REPLAYING -> "REPLAYING" to colors.signalCyan
        ConsoleStreamIndicator.RECONNECTING -> "RECONNECTING" to colors.signalAmber
        ConsoleStreamIndicator.RESYNCED -> "RESYNCED" to colors.signalViolet
        ConsoleStreamIndicator.OFFLINE -> "CACHED #$lastCursor" to colors.inkMuted
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 28.dp)
            .background(colors.stratumRaised)
            .clickable(onClick = onOpenConfigSheet)
            .padding(horizontal = spacing.md, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "[$modelId · $thoughtLevel · $modeId]",
            style = type.monoMicro,
            color = colors.signalCyan,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )

        Spacer(modifier = Modifier.width(spacing.sm))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Box(
                modifier = Modifier
                    .width(44.dp)
                    .height(6.dp)
                    .clip(shapes.none)
                    .background(colors.stratumWell)
                    .border(spacing.hairlineWidth, colors.hairlineStrong),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fillFraction)
                        .fillMaxHeight()
                        .background(
                            if (fillFraction > 0.85f) colors.signalAmber else colors.signalCyan,
                        ),
                )
            }
            Text(
                text = "${formatTokenCompact(usage.used)} / ${formatTokenCompact(usage.size)}",
                style = type.monoMicro,
                color = colors.inkSecondary,
            )
        }

        Spacer(modifier = Modifier.width(spacing.sm))

        Text(
            text = streamText,
            style = type.monoMicro,
            color = streamColor,
        )
    }
}

@Composable
fun SwarmProgressStrip(
    workers: List<SwarmWorkerBadge>,
    onSelectWorker: (SwarmWorkerBadge) -> Unit,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    val noun = if (workers.size == 1) "WORKER" else "WORKERS"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 36.dp)
            .background(colors.stratumBase)
            .padding(horizontal = spacing.md, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = "SWARM · ${workers.size} $noun",
            style = type.labelTelemetry,
            color = colors.signalCyan,
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            modifier = Modifier.weight(1f),
        ) {
            items(workers, key = { it.toolCallId }) { w ->
                val dotColor = when {
                    w.isActive -> colors.signalCyan
                    w.status == "error" || w.status == "failed" -> colors.signalCrimson
                    else -> colors.signalEmerald
                }
                Row(
                    modifier = Modifier
                        .defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor)
                        .clip(shapes.sm)
                        .background(colors.stratumRaised)
                        .border(spacing.hairlineWidth, colors.hairline, shapes.sm)
                        .clickable { onSelectWorker(w) }
                        .padding(horizontal = spacing.sm, vertical = spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(w.label, style = type.monoMicro, color = colors.inkPrimary)
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(dotColor),
                    )
                }
            }
        }
    }
}

// ── Turn Stream Strata (Full-Bleed, Zero Chat Bubbles) ──────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UserTurnStratum(item: LedgerItem.UserTurn) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val spacing = GravityWellTheme.spacing

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(colors.stratumUser),
    ) {
        Box(
            modifier = Modifier
                .width(spacing.statusRailWidth)
                .fillMaxHeight()
                .background(colors.signalAmber),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = spacing.proseHorizontalMargin, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("USER", style = type.labelTelemetry, color = colors.signalAmber)
                Text(compactTimestamp(item.ts), style = type.monoMicro, color = colors.inkMuted)
            }
            SelectionContainer {
                Text(
                    text = item.text,
                    style = type.bodyProse,
                    color = colors.inkPrimary,
                )
            }
            if (item.attachmentLabels.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    item.attachmentLabels.forEach { label ->
                        Row(
                            modifier = Modifier
                                .background(colors.stratumRaised, CircleShape)
                                .border(spacing.hairlineWidth, colors.hairline, CircleShape)
                                .padding(horizontal = spacing.sm, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AttachFile,
                                contentDescription = null,
                                tint = colors.inkSecondary,
                                modifier = Modifier.size(12.dp),
                            )
                            Text(
                                text = label,
                                style = type.monoMicro,
                                color = colors.inkSecondary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AgentProseStratum(item: LedgerItem.AgentProse) {
    val colors = GravityWellTheme.colors
    val spacing = GravityWellTheme.spacing

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(colors.chassisVoid),
    ) {
        Box(
            modifier = Modifier
                .width(spacing.statusRailWidth)
                .fillMaxHeight()
                .background(if (item.isStreaming) colors.signalCyan else colors.hairline),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = spacing.md),
        ) {
            MarkdownStratumContent(markdown = item.text)
        }
    }
}

@Composable
fun ThoughtStratum(
    item: LedgerItem.ThoughtBlock,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val spacing = GravityWellTheme.spacing

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(colors.stratumWell)
            .border(spacing.hairlineWidth, colors.hairline),
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(colors.signalViolet),
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = spacing.touchFloor)
                    .clickable(onClick = onToggle)
                    .padding(horizontal = spacing.md, vertical = spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (item.isStreaming) {
                        "THOUGHT · streaming..."
                    } else {
                        "THOUGHT · ${item.lineCount} lines"
                    },
                    style = type.labelTelemetry,
                    color = colors.signalViolet,
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse thought" else "Expand thought",
                    tint = colors.inkSecondary,
                )
            }

            if (expanded) {
                SelectionContainer {
                    Text(
                        text = item.text,
                        style = type.monoCode,
                        color = colors.inkSecondary,
                        modifier = Modifier.padding(
                            start = spacing.md,
                            end = spacing.md,
                            bottom = spacing.md,
                        ),
                    )
                }
            } else if (item.isStreaming && item.text.isNotBlank()) {
                val tailPreview = item.text.trimEnd().lines().takeLast(2).joinToString("\n")
                Text(
                    text = tailPreview,
                    style = type.monoCode,
                    color = colors.inkMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(
                        start = spacing.md,
                        end = spacing.md,
                        bottom = spacing.sm,
                    ),
                )
            }
        }
    }
}

@Composable
fun ToolGutterStratum(
    item: LedgerItem.ToolCallBlock,
    expanded: Boolean,
    diffMode: DiffDisplayMode,
    onToggleExpanded: () -> Unit,
    onSelectDiffMode: (DiffDisplayMode) -> Unit,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    val statusDotColor = when (item.status.lowercase()) {
        "pending", "in_progress", "running" -> colors.signalCyan
        "error", "failed", "rejected", "cancelled" -> colors.signalCrimson
        else -> colors.signalEmerald
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.stratumBase)
            .border(spacing.hairlineWidth, colors.hairline),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = spacing.touchFloor)
                .clickable(onClick = onToggleExpanded)
                .padding(horizontal = spacing.md, vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Box(
                modifier = Modifier
                    .clip(shapes.sm)
                    .background(colors.stratumRaised)
                    .border(spacing.hairlineWidth, colors.hairlineStrong, shapes.sm)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = item.normalizedKindBadge,
                    style = type.monoMicro,
                    color = colors.inkPrimary,
                )
            }

            Text(
                text = item.title,
                style = type.monoCode,
                color = colors.inkPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            if (item.parsedDiff != null) {
                Text(
                    text = "+${item.parsedDiff.addedCount} -${item.parsedDiff.removedCount}",
                    style = type.monoMicro,
                    color = colors.inkSecondary,
                )
            }

            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusDotColor),
            )

            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse tool gutter" else "Expand tool gutter",
                tint = colors.inkSecondary,
            )
        }

        if (expanded) {
            HorizontalDivider(thickness = spacing.hairlineWidth, color = colors.hairline)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.stratumWell)
                    .padding(spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                if (item.rawInputSummary.isNotBlank()) {
                    Text("INPUT", style = type.monoMicro, color = colors.inkMuted)
                    SelectionContainer {
                        Text(
                            text = item.rawInputSummary,
                            style = type.monoCode,
                            color = colors.inkPrimary,
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        )
                    }
                }

                if (item.parsedDiff != null) {
                    DiffPanel(
                        diff = item.parsedDiff,
                        mode = diffMode,
                        onSelectMode = onSelectDiffMode,
                    )
                }

                if (item.rawOutputSummary.isNotBlank()) {
                    Text("OUTPUT", style = type.monoMicro, color = colors.inkMuted)
                    SelectionContainer {
                        Text(
                            text = item.rawOutputSummary,
                            style = type.monoCode,
                            color = colors.inkSecondary,
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DiffPanel(
    diff: ParsedDiff,
    mode: DiffDisplayMode,
    onSelectMode: (DiffDisplayMode) -> Unit,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shapes.sm)
            .border(spacing.hairlineWidth, colors.hairlineStrong, shapes.sm)
            .background(colors.stratumWell),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.stratumBase)
                .padding(horizontal = spacing.sm, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = diff.filePath ?: "DIFF",
                style = type.monoMicro,
                color = colors.inkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            Row(
                modifier = Modifier
                    .clip(shapes.sm)
                    .border(spacing.hairlineWidth, colors.hairline, shapes.sm),
            ) {
                DiffDisplayMode.entries.forEach { m ->
                    val selected = m == mode
                    Box(
                        modifier = Modifier
                            .defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor)
                            .background(if (selected) colors.signalCyanDim else colors.stratumRaised)
                            .clickable { onSelectMode(m) }
                            .padding(horizontal = spacing.sm),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = m.name,
                            style = type.monoMicro,
                            color = if (selected) colors.signalCyan else colors.inkMuted,
                        )
                    }
                }
            }
        }

        HorizontalDivider(thickness = spacing.hairlineWidth, color = colors.hairline)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            diff.hunks.forEach { hunk ->
                hunk.lines.forEach { line ->
                    val rowBg = when (line.kind) {
                        DiffLineKind.HUNK_HEADER -> colors.diffHunkBg
                        DiffLineKind.ADDED -> colors.diffAddBg
                        DiffLineKind.REMOVED -> colors.diffDelBg
                        DiffLineKind.CONTEXT -> colors.stratumWell
                    }
                    val rowInk = when (line.kind) {
                        DiffLineKind.HUNK_HEADER -> colors.signalCyan
                        DiffLineKind.ADDED -> colors.diffAddInk
                        DiffLineKind.REMOVED -> colors.diffDelInk
                        DiffLineKind.CONTEXT -> colors.inkSecondary
                    }
                    val prefix = when (line.kind) {
                        DiffLineKind.HUNK_HEADER -> ""
                        DiffLineKind.ADDED -> "+"
                        DiffLineKind.REMOVED -> "-"
                        DiffLineKind.CONTEXT -> " "
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(rowBg)
                            .padding(horizontal = spacing.sm, vertical = 1.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        if (line.kind != DiffLineKind.HUNK_HEADER) {
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "%4s %4s ",
                                    line.oldLineNumber?.toString() ?: "",
                                    line.newLineNumber?.toString() ?: "",
                                ),
                                style = type.monoMicro,
                                color = colors.inkMuted,
                            )
                        }

                        val styledLine = if (mode == DiffDisplayMode.WORD && line.wordSpans != null) {
                            buildAnnotatedString {
                                append(prefix)
                                line.wordSpans.forEach { span ->
                                    when (span) {
                                        is WordDiffSpan.Unchanged -> append(span.text)
                                        is WordDiffSpan.Added -> withStyle(
                                            SpanStyle(background = colors.diffAddWordBg, color = colors.diffAddInk),
                                        ) {
                                            append(span.text)
                                        }
                                        is WordDiffSpan.Removed -> withStyle(
                                            SpanStyle(background = colors.diffDelWordBg, color = colors.diffDelInk),
                                        ) {
                                            append(span.text)
                                        }
                                    }
                                }
                            }
                        } else {
                            buildAnnotatedString {
                                append(prefix)
                                append(line.text)
                            }
                        }

                        Text(
                            text = styledLine,
                            style = type.monoCode,
                            color = rowInk,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SystemNoticeStratum(item: LedgerItem.SystemNotice) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val spacing = GravityWellTheme.spacing

    val accent = if (item.isError) colors.signalCrimson else colors.signalViolet
    val bg = if (item.isError) colors.signalCrimsonDim else colors.stratumBase

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = spacing.proseHorizontalMargin, vertical = spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(item.label, style = type.labelTelemetry, color = accent)
        Text(item.message, style = type.monoMicro, color = colors.inkPrimary)
    }
}

// ── Docked Permission & Question Interlock Bars ─────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DockedPermissionGateBar(
    permission: PendingPermissionDto,
    onResolve: (decision: String, optionId: String?) -> Unit,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    var expandedPreview by remember(permission.requestId) { mutableStateOf(false) }

    val toolKind = (permission.toolCall["kind"] as? JsonPrimitive)?.contentOrNull ?: "tool"
    val rawInputObj = permission.toolCall["rawInput"] as? kotlinx.serialization.json.JsonObject
    val previewText = rawInputObj?.let { ConsoleStreamReducer.formatJsonCompact(it) }
        ?.ifBlank { null }
        ?: ConsoleStreamReducer.formatJsonCompact(permission.toolCall)

    val allowOnceOpt = permission.options.firstOrNull { it.kind == "allow_once" }
    val allowAlwaysOpt = permission.options.firstOrNull { it.kind == "allow_always" }
    val rejectOpt = permission.options.firstOrNull { it.kind == "reject_once" || it.kind == "reject_always" }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(colors.signalAmberDim)
            .border(2.dp, colors.signalAmber),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "PERMISSION GATE · ${toolKind.uppercase()}",
                    style = type.labelTelemetry,
                    color = colors.signalAmber,
                )
                TextButton(
                    onClick = { expandedPreview = !expandedPreview },
                    modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                ) {
                    Text(
                        text = if (expandedPreview) "LESS" else "FULL",
                        style = type.monoMicro,
                        color = colors.inkSecondary,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shapes.sm)
                    .background(colors.stratumWell)
                    .border(spacing.hairlineWidth, colors.hairline, shapes.sm)
                    .padding(spacing.sm),
            ) {
                Text(
                    text = previewText.ifBlank { "Tool execution requires explicit approval." },
                    style = type.monoCode,
                    color = colors.inkPrimary,
                    maxLines = if (expandedPreview) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Button(
                    onClick = { onResolve("allow_once", allowOnceOpt?.optionId) },
                    shape = shapes.sm,
                    elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.signalAmber,
                        contentColor = colors.inkInverse,
                    ),
                    modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                ) {
                    Text(
                        text = allowOnceOpt?.name?.ifBlank { "Allow Once" } ?: "Allow Once",
                        style = type.labelTelemetry,
                    )
                }

                if (allowAlwaysOpt != null || permission.options.isEmpty()) {
                    OutlinedButton(
                        onClick = { onResolve("allow_rule", allowAlwaysOpt?.optionId) },
                        shape = shapes.sm,
                        border = BorderStroke(spacing.hairlineWidth, colors.signalCyan),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = colors.stratumBase,
                            contentColor = colors.signalCyan,
                        ),
                        modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                    ) {
                        Column(horizontalAlignment = Alignment.Start) {
                            Text("Allow Workspace Rule", style = type.labelTelemetry)
                            Text(
                                text = "Scoped to cwd · revocable in Deck",
                                style = type.monoMicro,
                                color = colors.inkSecondary,
                            )
                        }
                    }
                }

                OutlinedButton(
                    onClick = { onResolve("deny", rejectOpt?.optionId) },
                    shape = shapes.sm,
                    border = BorderStroke(spacing.hairlineWidth, colors.signalCrimson),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = colors.signalCrimsonDim,
                        contentColor = colors.signalCrimson,
                    ),
                    modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                ) {
                    Text("Deny", style = type.labelTelemetry)
                }
            }
        }
    }
}

@Composable
fun DockedQuestionBrokerBar(
    question: PendingQuestionDto,
    draftAnswers: Map<String, String>,
    onUpdateAnswer: (propKey: String, value: String) -> Unit,
    onResolve: (action: String) -> Unit,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    val fields = remember(question.requestedSchema) {
        QuestionSchemaParser.parseFields(question.requestedSchema)
    }
    val canSubmit = QuestionSchemaParser.areRequiredAnswersSatisfied(fields, draftAnswers)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.stratumBase)
            .border(spacing.hairlineWidth, colors.signalCyan),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.signalCyanDim)
                .padding(horizontal = spacing.md, vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("AGENT QUESTION", style = type.labelTelemetry, color = colors.signalCyan)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            if (question.message.isNotBlank()) {
                Text(question.message, style = type.bodyProse, color = colors.inkPrimary)
            }

            fields.forEach { field ->
                Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    Text(
                        text = buildString {
                            append(field.title)
                            if (field.required) append(" *")
                        },
                        style = type.labelTelemetry,
                        color = colors.inkSecondary,
                    )
                    if (!field.description.isNullOrBlank()) {
                        Text(field.description.orEmpty(), style = type.monoMicro, color = colors.inkMuted)
                    }

                    when (field) {
                        is QuestionFieldSpec.EnumSelect -> {
                            val selectedVal = draftAnswers[field.key]
                            field.options.forEach { (rawVal, displayLabel) ->
                                val active = selectedVal == rawVal
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .defaultMinSize(minHeight = spacing.touchFloor)
                                        .clip(shapes.sm)
                                        .background(if (active) colors.signalCyanDim else colors.stratumWell)
                                        .border(
                                            spacing.hairlineWidth,
                                            if (active) colors.signalCyan else colors.hairline,
                                            shapes.sm,
                                        )
                                        .clickable { onUpdateAnswer(field.key, rawVal) }
                                        .padding(horizontal = spacing.md),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = displayLabel,
                                        style = type.bodyCompact,
                                        color = if (active) colors.signalCyan else colors.inkPrimary,
                                    )
                                }
                            }
                        }

                        is QuestionFieldSpec.TextField -> {
                            OutlinedTextField(
                                value = draftAnswers[field.key].orEmpty(),
                                onValueChange = { onUpdateAnswer(field.key, it) },
                                singleLine = true,
                                textStyle = type.monoCode,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = if (field.numericOnly) KeyboardType.Number else KeyboardType.Text,
                                ),
                                shape = shapes.sm,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = colors.stratumWell,
                                    unfocusedContainerColor = colors.stratumWell,
                                    focusedBorderColor = colors.signalCyan,
                                    unfocusedBorderColor = colors.hairline,
                                    focusedTextColor = colors.inkPrimary,
                                    unfocusedTextColor = colors.inkPrimary,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        is QuestionFieldSpec.BooleanField -> {
                            val checked = draftAnswers[field.key]?.toBooleanStrictOrNull() ?: field.defaultValue
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = spacing.touchFloor)
                                    .clip(shapes.sm)
                                    .background(colors.stratumWell)
                                    .border(spacing.hairlineWidth, colors.hairline, shapes.sm)
                                    .padding(horizontal = spacing.md),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(field.key, style = type.monoCode, color = colors.inkPrimary)
                                Switch(
                                    checked = checked,
                                    onCheckedChange = { onUpdateAnswer(field.key, it.toString()) },
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Button(
                    onClick = { onResolve("accept") },
                    enabled = canSubmit,
                    shape = shapes.sm,
                    elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.signalCyan,
                        contentColor = colors.inkInverse,
                        disabledContainerColor = colors.stratumRaised,
                        disabledContentColor = colors.inkMuted,
                    ),
                    modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                ) {
                    Text("Submit", style = type.labelTelemetry)
                }

                OutlinedButton(
                    onClick = { onResolve("decline") },
                    shape = shapes.sm,
                    border = BorderStroke(spacing.hairlineWidth, colors.hairlineStrong),
                    modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                ) {
                    Text("Decline", style = type.labelTelemetry, color = colors.inkSecondary)
                }

                TextButton(
                    onClick = { onResolve("cancel") },
                    modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                ) {
                    Text("Cancel", style = type.labelTelemetry, color = colors.signalCrimson)
                }
            }
        }
    }
}

// ── Queued Follow-Up Tray (Send Now Preserves Composer Draft!) ──────────────

@Composable
fun QueuedFollowupTray(
    items: List<QueueItemDto>,
    queuePaused: Boolean,
    editingItemId: String?,
    editingText: String,
    onResumeQueue: () -> Unit,
    onStartEdit: (QueueItemDto) -> Unit,
    onUpdateEditText: (String) -> Unit,
    onSaveEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onDelete: (String) -> Unit,
    onSendNow: (String) -> Unit,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.stratumBase)
            .border(spacing.hairlineWidth, colors.hairline),
    ) {
        if (queuePaused) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.signalAmberDim)
                    .padding(horizontal = spacing.md, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "QUEUE PAUSED AFTER CANCEL/ERROR",
                    style = type.labelTelemetry,
                    color = colors.signalAmber,
                )
                if (items.isNotEmpty()) {
                    TextButton(
                        onClick = onResumeQueue,
                        modifier = Modifier.defaultMinSize(
                            minWidth = spacing.touchFloor,
                            minHeight = spacing.touchFloor,
                        ),
                    ) {
                        Text("RESUME", style = type.labelTelemetry, color = colors.signalAmber)
                    }
                }
            }
            HorizontalDivider(thickness = spacing.hairlineWidth, color = colors.hairline)
        }

        items.sortedBy { it.position }.forEach { item ->
            if (editingItemId == item.itemId) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    OutlinedTextField(
                        value = editingText,
                        onValueChange = onUpdateEditText,
                        singleLine = true,
                        textStyle = type.bodyCompact,
                        shape = shapes.sm,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = onSaveEdit,
                        modifier = Modifier.defaultMinSize(
                            minWidth = spacing.touchFloor,
                            minHeight = spacing.touchFloor,
                        ),
                    ) {
                        Text("SAVE", style = type.labelTelemetry, color = colors.signalCyan)
                    }
                    TextButton(
                        onClick = onCancelEdit,
                        modifier = Modifier.defaultMinSize(
                            minWidth = spacing.touchFloor,
                            minHeight = spacing.touchFloor,
                        ),
                    ) {
                        Text("ESC", style = type.labelTelemetry, color = colors.inkMuted)
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = spacing.touchFloor)
                        .padding(start = spacing.md, end = spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "#${item.position + 1}",
                        style = type.monoMicro,
                        color = colors.signalAmber,
                    )
                    Spacer(modifier = Modifier.width(spacing.sm))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.text,
                            style = type.bodyCompact,
                            color = colors.inkPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (item.attachmentIds.isNotEmpty()) {
                            Text(
                                text = "${item.attachmentIds.size} attachment(s)",
                                style = type.monoMicro,
                                color = colors.inkMuted,
                            )
                        }
                    }

                    IconButton(
                        onClick = { onStartEdit(item) },
                        modifier = Modifier.defaultMinSize(
                            minWidth = spacing.touchFloor,
                            minHeight = spacing.touchFloor,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit queued prompt ${item.position + 1}",
                            tint = colors.inkSecondary,
                            modifier = Modifier.size(16.dp),
                        )
                    }

                    IconButton(
                        onClick = { onDelete(item.itemId) },
                        modifier = Modifier.defaultMinSize(
                            minWidth = spacing.touchFloor,
                            minHeight = spacing.touchFloor,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete queued prompt ${item.position + 1}",
                            tint = colors.signalCrimson,
                            modifier = Modifier.size(16.dp),
                        )
                    }

                    TextButton(
                        onClick = { onSendNow(item.itemId) },
                        modifier = Modifier.defaultMinSize(
                            minWidth = spacing.touchFloor,
                            minHeight = spacing.touchFloor,
                        ),
                    ) {
                        Text("Send Now", style = type.labelTelemetry, color = colors.signalCyan)
                    }
                }
            }
            HorizontalDivider(thickness = spacing.hairlineWidth, color = colors.hairline)
        }
    }
}

// ── Thumb-Zone Composer Dock ────────────────────────────────────────────────

@Composable
fun ThumbZoneComposerDock(
    draftText: String,
    draftCursorIndex: Int,
    isTurnActive: Boolean,
    isSubmitting: Boolean,
    attachments: List<DraftAttachmentRecord>,
    attachmentThumbnails: Map<String, ByteArray>,
    atFileCandidates: List<WorkspaceFileItemDto>,
    slashCommands: List<AvailableCommandDto>,
    onDraftChange: (text: String, cursor: Int) -> Unit,
    onPickAttachment: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onToggleSlashBar: () -> Unit,
    onSelectSlashCommand: (String) -> Unit,
    onSelectAtFile: (WorkspaceFileItemDto) -> Unit,
    onSubmit: () -> Unit,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    var fieldValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = draftText,
                selection = TextRange(draftCursorIndex.coerceIn(0, draftText.length)),
            ),
        )
    }

    LaunchedEffect(draftText, draftCursorIndex) {
        if (fieldValue.text != draftText) {
            fieldValue = TextFieldValue(
                text = draftText,
                selection = TextRange(draftCursorIndex.coerceIn(0, draftText.length)),
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.stratumBase)
            .imePadding(),
    ) {
        // 1. IME-Bounded @file Autocomplete (maxHeight = 180.dp)
        if (atFileCandidates.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp)
                    .background(colors.stratumRaised)
                    .border(spacing.hairlineWidth, colors.signalCyan),
            ) {
                items(atFileCandidates, key = { "${it.root}:${it.path}" }) { fileItem ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = spacing.touchFloor)
                            .clickable { onSelectAtFile(fileItem) }
                            .padding(horizontal = spacing.md, vertical = spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "@${fileItem.path}",
                            style = type.monoCode,
                            color = colors.inkPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(spacing.sm))
                        Text(
                            text = basenameOfPath(fileItem.root),
                            style = type.monoMicro,
                            color = colors.signalCyan,
                        )
                    }
                    HorizontalDivider(thickness = spacing.hairlineWidth, color = colors.hairline)
                }
            }
        }

        // 2. Slash Command Chips Row
        if (slashCommands.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = spacing.sm, vertical = spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.stratumBase),
            ) {
                items(slashCommands, key = { it.name }) { cmd ->
                    Row(
                        modifier = Modifier
                            .defaultMinSize(minHeight = spacing.touchFloor)
                            .clip(shapes.sm)
                            .background(colors.stratumRaised)
                            .border(spacing.hairlineWidth, colors.hairlineStrong, shapes.sm)
                            .clickable { onSelectSlashCommand(cmd.name) }
                            .padding(horizontal = spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                    ) {
                        Text("/${cmd.name}", style = type.monoCode, color = colors.signalCyan)
                        if (cmd.description.isNotBlank()) {
                            Text(
                                text = cmd.description,
                                style = type.monoMicro,
                                color = colors.inkMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            HorizontalDivider(thickness = spacing.hairlineWidth, color = colors.hairline)
        }

        // 3. Magic-Byte Attachment Preview Strip
        if (attachments.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = spacing.sm, vertical = spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(attachments, key = { it.localId }) { att ->
                    AttachmentPreviewChip(
                        record = att,
                        thumbnailBytes = attachmentThumbnails[att.localId],
                        onRemove = { onRemoveAttachment(att.localId) },
                    )
                }
            }
            HorizontalDivider(thickness = spacing.hairlineWidth, color = colors.hairline)
        }

        HorizontalDivider(thickness = spacing.hairlineWidth, color = colors.hairline)

        // 4. Primary Composer Row: [Attach] [/] [Multi-line stratumWell field] [SEND / QUEUE]
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.xs, vertical = spacing.xs),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            IconButton(
                onClick = onPickAttachment,
                modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
            ) {
                Icon(
                    imageVector = Icons.Default.AttachFile,
                    contentDescription = "Attach file",
                    tint = colors.inkSecondary,
                )
            }

            OutlinedButton(
                onClick = onToggleSlashBar,
                shape = shapes.sm,
                border = BorderStroke(spacing.hairlineWidth, colors.hairlineStrong),
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = colors.stratumRaised,
                    contentColor = colors.signalCyan,
                ),
                modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
            ) {
                Text("/", style = type.monoCode)
            }

            OutlinedTextField(
                value = fieldValue,
                onValueChange = { next ->
                    fieldValue = next
                    onDraftChange(next.text, next.selection.end)
                },
                placeholder = {
                    Text(
                        text = if (isTurnActive) "Queue follow-up (@file, /cmd)..." else "Prompt Antigravity (@file, /cmd)...",
                        style = type.bodyCompact,
                        color = colors.inkMuted,
                    )
                },
                textStyle = type.bodyProse,
                maxLines = 5,
                shape = shapes.md,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = colors.stratumWell,
                    unfocusedContainerColor = colors.stratumWell,
                    focusedBorderColor = colors.hairlineStrong,
                    unfocusedBorderColor = colors.hairline,
                    focusedTextColor = colors.inkPrimary,
                    unfocusedTextColor = colors.inkPrimary,
                    cursorColor = colors.signalCyan,
                ),
                modifier = Modifier.weight(1f),
            )

            val hasReadyContent = draftText.isNotBlank() ||
                attachments.any { it.state == AttachmentUploadState.READY }
            val anyUploading = attachments.any { it.state == AttachmentUploadState.UPLOADING }

            Button(
                onClick = onSubmit,
                enabled = hasReadyContent && !anyUploading && !isSubmitting,
                shape = shapes.sm,
                elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isTurnActive) colors.signalAmber else colors.signalCyan,
                    contentColor = colors.inkInverse,
                    disabledContainerColor = colors.stratumRaised,
                    disabledContentColor = colors.inkMuted,
                ),
                contentPadding = PaddingValues(horizontal = spacing.md, vertical = 0.dp),
                modifier = Modifier.defaultMinSize(minWidth = 64.dp, minHeight = spacing.touchFloor),
            ) {
                Text(
                    text = if (isTurnActive) "QUEUE" else "SEND",
                    style = type.labelTelemetry,
                )
            }
        }
    }
}

@Composable
private fun AttachmentPreviewChip(
    record: DraftAttachmentRecord,
    thumbnailBytes: ByteArray?,
    onRemove: () -> Unit,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    val isFailed = record.state == AttachmentUploadState.FAILED
    val borderColor = when (record.state) {
        AttachmentUploadState.FAILED -> colors.signalCrimson
        AttachmentUploadState.UPLOADING -> colors.signalCyan
        AttachmentUploadState.READY -> colors.hairlineStrong
    }
    val bgColor = if (isFailed) colors.signalCrimsonDim else colors.stratumRaised

    val decodedBitmap = remember(thumbnailBytes) {
        thumbnailBytes?.let { bytes ->
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
        }
    }

    Row(
        modifier = Modifier
            .clip(shapes.sm)
            .background(bgColor)
            .border(spacing.hairlineWidth, borderColor, shapes.sm)
            .padding(start = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        if (decodedBitmap != null) {
            Image(
                bitmap = decodedBitmap,
                contentDescription = record.filename,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(32.dp)
                    .clip(shapes.xs),
            )
        } else {
            Box(
                modifier = Modifier
                    .clip(shapes.xs)
                    .background(colors.stratumWell)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Text(
                    text = mimeBadgeLabel(record.mimeType),
                    style = type.monoMicro,
                    color = if (isFailed) colors.signalCrimson else colors.signalCyan,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = record.filename,
                style = type.monoMicro,
                color = colors.inkPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            when (record.state) {
                AttachmentUploadState.UPLOADING -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(10.dp),
                            strokeWidth = 1.5.dp,
                            color = colors.signalCyan,
                        )
                        Text("UPLOADING", style = type.monoMicro, color = colors.signalCyan)
                    }
                }
                AttachmentUploadState.READY -> {
                    Text(
                        text = formatByteSize(record.sizeBytes),
                        style = type.monoMicro,
                        color = colors.signalEmerald,
                    )
                }
                AttachmentUploadState.FAILED -> {
                    val codeBadge = listOfNotNull(record.errorStatus?.toString(), record.errorCode)
                        .joinToString(" ")
                        .ifBlank { "REJECTED" }
                    Text(
                        text = codeBadge,
                        style = type.monoMicro,
                        color = colors.signalCrimson,
                    )
                }
            }
        }

        IconButton(
            onClick = onRemove,
            modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Remove attachment ${record.filename}",
                tint = if (isFailed) colors.signalCrimson else colors.inkSecondary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

private fun mimeBadgeLabel(mime: String): String = when {
    mime == "application/pdf" -> "PDF"
    mime.startsWith("image/") -> mime.removePrefix("image/").uppercase().take(4)
    mime.startsWith("text/") || mime.contains("json") -> "UTF-8"
    else -> "BIN"
}

internal fun formatTokenCompact(tokens: Long): String = when {
    tokens >= 1_000_000L -> String.format(Locale.US, "%.1fM", tokens / 1_000_000.0)
    tokens >= 1_000L -> String.format(Locale.US, "%.1fk", tokens / 1_000.0)
    else -> tokens.toString()
}

internal fun formatByteSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.2f MiB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.US, "%.1f KiB", bytes / 1024.0)
    else -> "$bytes B"
}
