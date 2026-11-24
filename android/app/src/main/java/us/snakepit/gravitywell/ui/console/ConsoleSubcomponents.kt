package us.snakepit.gravitywell.ui.console

import android.graphics.BitmapFactory
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.WarningAmber
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
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
    modelId: String,
    thoughtLevel: String,
    modeId: String,
    usage: UsageDto,
    streamIndicator: ConsoleStreamIndicator,
    isTurnActive: Boolean,
    showBackButton: Boolean,
    onBack: () -> Unit,
    onRenameTitle: () -> Unit,
    onOpenConfigSheet: () -> Unit,
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

    val contextPct = if (usage.size > 0L) {
        ((usage.used.toDouble() / usage.size.toDouble()) * 100.0).toInt().coerceIn(0, 100)
    } else {
        0
    }

    val thoughtLabel = when (thoughtLevel.lowercase()) {
        "low" -> "Low"
        "medium" -> "Med"
        "high" -> "High"
        "xhigh" -> "Max"
        else -> thoughtLevel
    }
    val modeBadge = when (modeId) {
        "auto" -> " · Auto"
        "plan" -> " · Plan"
        "accept-edits" -> " · Edits"
        else -> ""
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 60.dp)
            .background(colors.chassisVoid)
            .padding(horizontal = spacing.xs, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBackButton) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back to Chats",
                    tint = colors.inkPrimary,
                )
            }
        } else {
            Spacer(modifier = Modifier.width(spacing.md))
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = spacing.xs),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = type.titleBar,
                color = colors.inkPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(onClick = onRenameTitle),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .clip(shapes.pill)
                    .clickable(onClick = onOpenConfigSheet)
                    .padding(vertical = 2.dp),
            ) {
                Text(
                    text = "${formatModelDisplayName(modelId)} · $thoughtLabel$modeBadge",
                    style = type.labelSmallUi,
                    color = colors.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = "Change model or chat settings",
                    tint = colors.inkMuted,
                    modifier = Modifier.size(15.dp),
                )
                if (contextPct >= 75) {
                    Box(
                        modifier = Modifier
                            .clip(shapes.pill)
                            .background(colors.signalAmberDim)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    ) {
                        Text(
                            text = "$contextPct%",
                            style = type.monoMicro,
                            color = colors.signalAmber,
                        )
                    }
                }
                if (streamIndicator != ConsoleStreamIndicator.LIVE) {
                    val (label, tint) = when (streamIndicator) {
                        ConsoleStreamIndicator.REPLAYING -> "Syncing" to colors.signalCyan
                        ConsoleStreamIndicator.RECONNECTING -> "Reconnecting" to colors.signalAmber
                        ConsoleStreamIndicator.RESYNCED -> "Resynced" to colors.signalViolet
                        ConsoleStreamIndicator.OFFLINE -> "Offline" to colors.inkMuted
                        ConsoleStreamIndicator.LIVE -> "" to colors.signalEmerald
                    }
                    Box(
                        modifier = Modifier
                            .clip(shapes.pill)
                            .background(colors.stratumRaised)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    ) {
                        Text(label, style = type.labelSmallUi, color = tint)
                    }
                }
            }
        }

        if (isTurnActive) {
            OutlinedButton(
                onClick = onCancelTurn,
                shape = shapes.pill,
                border = BorderStroke(spacing.hairlineWidth, colors.signalCrimson),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = colors.signalCrimsonDim,
                    contentColor = colors.signalCrimson,
                ),
                modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = 40.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Stop,
                    contentDescription = "Stop response",
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Stop", style = type.labelUi)
            }
            Spacer(modifier = Modifier.width(4.dp))
        }

        Box {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
            ) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = "Chat options",
                    tint = colors.inkSecondary,
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                containerColor = colors.stratumRaised,
                tonalElevation = spacing.elevationZero,
                shadowElevation = spacing.elevationZero,
                shape = shapes.md,
                border = BorderStroke(spacing.hairlineWidth, colors.hairline),
            ) {
                DropdownMenuItem(
                    text = { Text("Chat settings", style = type.bodyCompact, color = colors.inkPrimary) },
                    onClick = {
                        menuExpanded = false
                        onOpenConfigSheet()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Rename chat", style = type.bodyCompact, color = colors.inkPrimary) },
                    onClick = {
                        menuExpanded = false
                        onRenameTitle()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Artifacts", style = type.bodyCompact, color = colors.inkPrimary) },
                    onClick = {
                        menuExpanded = false
                        onOpenArtifacts()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Project rules", style = type.bodyCompact, color = colors.inkPrimary) },
                    onClick = {
                        menuExpanded = false
                        onOpenWorkspaceRules()
                    },
                )
                HorizontalDivider(thickness = spacing.hairlineWidth, color = colors.hairline)
                DropdownMenuItem(
                    text = { Text("Close chat", style = type.bodyCompact, color = colors.signalCrimson) },
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
fun SwarmProgressStrip(
    workers: List<SwarmWorkerBadge>,
    onSelectWorker: (SwarmWorkerBadge) -> Unit,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    val noun = if (workers.size == 1) "agent" else "agents"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.stratumBase)
            .padding(horizontal = spacing.lg, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = "${workers.size} $noun",
            style = type.labelSmallUi,
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
                        .defaultMinSize(minHeight = 36.dp)
                        .clip(shapes.pill)
                        .background(colors.stratumRaised)
                        .border(spacing.hairlineWidth, colors.hairline, shapes.pill)
                        .clickable { onSelectWorker(w) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(dotColor),
                    )
                    Text(w.label, style = type.labelSmallUi, color = colors.inkPrimary)
                }
            }
        }
    }
}

// ── Chat Messages, Thinking Pills & Tool Activity Cards ────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UserTurnStratum(item: LedgerItem.UserTurn) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 52.dp, end = spacing.lg),
        horizontalArrangement = Arrangement.End,
    ) {
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .clip(shapes.userBubble)
                    .background(colors.stratumUser)
                    .border(spacing.hairlineWidth, colors.hairlineStrong, shapes.userBubble)
                    .padding(horizontal = 15.dp, vertical = 11.dp),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
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
                                    .clip(shapes.pill)
                                    .background(colors.stratumBase)
                                    .border(spacing.hairlineWidth, colors.hairline, shapes.pill)
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AttachFile,
                                    contentDescription = null,
                                    tint = colors.signalCyan,
                                    modifier = Modifier.size(13.dp),
                                )
                                Text(
                                    text = label,
                                    style = type.labelSmallUi,
                                    color = colors.inkSecondary,
                                )
                            }
                        }
                    }
                }
            }
            if (item.ts.isNotBlank()) {
                Text(
                    text = compactTimestamp(item.ts),
                    style = type.monoMicro,
                    color = colors.inkMuted,
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
        }
    }
}

@Composable
fun AgentProseStratum(item: LedgerItem.AgentProse) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val spacing = GravityWellTheme.spacing

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        MarkdownStratumContent(markdown = item.text)

        if (item.isStreaming) {
            val transition = rememberInfiniteTransition(label = "stream_pulse")
            val dotAlpha by transition.animateFloat(
                initialValue = 1f,
                targetValue = 0.25f,
                animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
                label = "stream_dot_alpha",
            )
            Row(
                modifier = Modifier.padding(horizontal = spacing.proseHorizontalMargin),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .alpha(dotAlpha)
                        .clip(CircleShape)
                        .background(colors.signalCyan),
                )
                Text(
                    text = "Responding...",
                    style = type.labelSmallUi,
                    color = colors.inkMuted,
                )
            }
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
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.proseHorizontalMargin)
            .clip(shapes.md)
            .background(colors.stratumBase)
            .border(spacing.hairlineWidth, colors.hairline, shapes.md),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = spacing.touchFloor)
                .clickable(onClick = onToggle)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = colors.signalViolet,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = if (item.isStreaming) {
                        "Thinking..."
                    } else {
                        "Thought process · ${item.lineCount} lines"
                    },
                    style = type.labelUi,
                    color = colors.signalViolet,
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = if (expanded) "Collapse thought" else "Expand thought",
                tint = colors.inkMuted,
                modifier = Modifier.size(20.dp),
            )
        }

        if (expanded) {
            HorizontalDivider(thickness = spacing.hairlineWidth, color = colors.hairline)
            SelectionContainer {
                Text(
                    text = item.text,
                    style = type.monoCode,
                    color = colors.inkSecondary,
                    modifier = Modifier.padding(14.dp),
                )
            }
        } else if (item.isStreaming && item.text.isNotBlank()) {
            val tailPreview = item.text.trimEnd().lines().takeLast(2).joinToString("\n")
            Text(
                text = tailPreview,
                style = type.bodyCompact,
                color = colors.inkMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
            )
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

    val lowerStatus = item.status.lowercase()
    val isRunning = lowerStatus == "pending" || lowerStatus == "in_progress" || lowerStatus == "running"
    val isError = lowerStatus == "error" || lowerStatus == "failed" || lowerStatus == "rejected" || lowerStatus == "cancelled"

    val (kindIcon, kindLabel) = toolKindPresentation(item.normalizedKindBadge)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.proseHorizontalMargin)
            .clip(shapes.md)
            .background(colors.stratumBase)
            .border(spacing.hairlineWidth, colors.hairline, shapes.md),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = spacing.touchFloor)
                .clickable(onClick = onToggleExpanded)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(colors.stratumRaised),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = kindIcon,
                    contentDescription = kindLabel,
                    tint = colors.signalCyan,
                    modifier = Modifier.size(15.dp),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = type.bodyCompact,
                    color = colors.inkPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = kindLabel,
                    style = type.labelSmallUi,
                    color = colors.inkMuted,
                )
            }

            if (item.parsedDiff != null) {
                Row(
                    modifier = Modifier
                        .clip(shapes.pill)
                        .background(colors.stratumRaised)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "+${item.parsedDiff.addedCount}",
                        style = type.monoMicro,
                        color = colors.diffAddInk,
                    )
                    Text(
                        text = "-${item.parsedDiff.removedCount}",
                        style = type.monoMicro,
                        color = colors.diffDelInk,
                    )
                }
            }

            when {
                isRunning -> CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = colors.signalCyan,
                )
                isError -> Icon(
                    imageVector = Icons.Rounded.ErrorOutline,
                    contentDescription = "Failed",
                    tint = colors.signalCrimson,
                    modifier = Modifier.size(16.dp),
                )
                else -> Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = "Completed",
                    tint = colors.signalEmerald,
                    modifier = Modifier.size(16.dp),
                )
            }

            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = if (expanded) "Collapse tool details" else "Expand tool details",
                tint = colors.inkMuted,
                modifier = Modifier.size(20.dp),
            )
        }

        if (expanded) {
            HorizontalDivider(thickness = spacing.hairlineWidth, color = colors.hairline)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.stratumWell)
                    .padding(spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                if (item.rawInputSummary.isNotBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Input", style = type.labelSmallUi, color = colors.inkMuted)
                        SelectionContainer {
                            Text(
                                text = item.rawInputSummary,
                                style = type.monoCode,
                                color = colors.inkPrimary,
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                            )
                        }
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
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Output", style = type.labelSmallUi, color = colors.inkMuted)
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
}

private fun toolKindPresentation(badge: String): Pair<ImageVector, String> = when (badge) {
    "EXECUTE" -> Icons.Rounded.Terminal to "Run command"
    "EDIT" -> Icons.Rounded.Edit to "Edit file"
    "READ" -> Icons.Rounded.Description to "Read file"
    "SEARCH" -> Icons.Rounded.Search to "Search"
    else -> Icons.Rounded.Build to badge.lowercase().replaceFirstChar { it.titlecase() }
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
                .padding(horizontal = spacing.md, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = diff.filePath ?: "Diff",
                style = type.monoMicro,
                color = colors.inkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            Row(
                modifier = Modifier
                    .clip(shapes.pill)
                    .background(colors.stratumRaised)
                    .padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                DiffDisplayMode.entries.forEach { m ->
                    val selected = m == mode
                    Box(
                        modifier = Modifier
                            .defaultMinSize(minWidth = spacing.touchFloor, minHeight = 36.dp)
                            .clip(shapes.pill)
                            .background(if (selected) colors.signalCyanDim else colors.stratumRaised)
                            .clickable { onSelectMode(m) }
                            .padding(horizontal = spacing.sm),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (m == DiffDisplayMode.UNIFIED) "Unified" else "Word",
                            style = type.labelSmallUi,
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
                            .padding(horizontal = spacing.sm, vertical = 2.dp),
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
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    val accent = if (item.isError) colors.signalCrimson else colors.signalViolet
    val bg = if (item.isError) colors.signalCrimsonDim else colors.stratumBase

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.xl),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(shapes.pill)
                .background(bg)
                .border(spacing.hairlineWidth, if (item.isError) colors.signalCrimson else colors.hairline, shapes.pill)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(item.label, style = type.labelSmallUi, color = accent)
            Text(item.message, style = type.bodyCompact, color = colors.inkPrimary)
        }
    }
}

// ── Docked Permission & Question Cards ─────────────────────────────────────

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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.md, vertical = spacing.xs)
            .clip(shapes.md)
            .background(colors.stratumBase)
            .border(spacing.hairlineWidth, colors.signalAmber, shapes.md)
            .padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = colors.signalAmber,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "Approval needed",
                    style = type.titleBar,
                    color = colors.inkPrimary,
                )
                Box(
                    modifier = Modifier
                        .clip(shapes.pill)
                        .background(colors.signalAmberDim)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = toolKind.replaceFirstChar { it.titlecase() },
                        style = type.labelSmallUi,
                        color = colors.signalAmber,
                    )
                }
            }
            TextButton(
                onClick = { expandedPreview = !expandedPreview },
                modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = 40.dp),
            ) {
                Text(
                    text = if (expandedPreview) "Less" else "Details",
                    style = type.labelSmallUi,
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
                .padding(spacing.md),
        ) {
            Text(
                text = previewText.ifBlank { "Antigravity wants to run a tool that requires your approval." },
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
                shape = shapes.pill,
                elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.signalAmber,
                    contentColor = colors.inkInverse,
                ),
                modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
            ) {
                Text(
                    text = allowOnceOpt?.name?.ifBlank { "Allow once" } ?: "Allow once",
                    style = type.labelUi,
                )
            }

            if (allowAlwaysOpt != null || permission.options.isEmpty()) {
                OutlinedButton(
                    onClick = { onResolve("allow_rule", allowAlwaysOpt?.optionId) },
                    shape = shapes.pill,
                    border = BorderStroke(spacing.hairlineWidth, colors.signalCyan),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = colors.signalCyanDim,
                        contentColor = colors.signalCyan,
                    ),
                    modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                ) {
                    Text("Always allow in project", style = type.labelUi)
                }
            }

            OutlinedButton(
                onClick = { onResolve("deny", rejectOpt?.optionId) },
                shape = shapes.pill,
                border = BorderStroke(spacing.hairlineWidth, colors.signalCrimson),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = colors.signalCrimsonDim,
                    contentColor = colors.signalCrimson,
                ),
                modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
            ) {
                Text("Deny", style = type.labelUi)
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
            .padding(horizontal = spacing.md, vertical = spacing.xs)
            .clip(shapes.md)
            .background(colors.stratumBase)
            .border(spacing.hairlineWidth, colors.signalCyan, shapes.md)
            .padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.HelpOutline,
                contentDescription = null,
                tint = colors.signalCyan,
                modifier = Modifier.size(18.dp),
            )
            Text("Question from Antigravity", style = type.titleBar, color = colors.inkPrimary)
        }

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
                    style = type.labelUi,
                    color = colors.inkSecondary,
                )
                if (!field.description.isNullOrBlank()) {
                    Text(field.description.orEmpty(), style = type.bodyCompact, color = colors.inkMuted)
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
                                    .background(if (active) colors.signalCyanDim else colors.stratumRaised)
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
                            textStyle = type.bodyProse,
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
                                .background(colors.stratumRaised)
                                .padding(horizontal = spacing.md),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(field.title, style = type.bodyCompact, color = colors.inkPrimary)
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
                shape = shapes.pill,
                elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.signalCyan,
                    contentColor = colors.inkInverse,
                    disabledContainerColor = colors.stratumRaised,
                    disabledContentColor = colors.inkMuted,
                ),
                modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
            ) {
                Text("Submit", style = type.labelUi)
            }

            OutlinedButton(
                onClick = { onResolve("decline") },
                shape = shapes.pill,
                border = BorderStroke(spacing.hairlineWidth, colors.hairlineStrong),
                modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
            ) {
                Text("Decline", style = type.labelUi, color = colors.inkSecondary)
            }

            TextButton(
                onClick = { onResolve("cancel") },
                modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
            ) {
                Text("Cancel", style = type.labelUi, color = colors.signalCrimson)
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
            .padding(horizontal = spacing.md, vertical = spacing.xs)
            .clip(shapes.md)
            .background(colors.stratumBase)
            .border(spacing.hairlineWidth, colors.hairline, shapes.md),
    ) {
        if (queuePaused) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.signalAmberDim)
                    .padding(horizontal = spacing.md, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Queue paused after stop or error",
                    style = type.labelSmallUi,
                    color = colors.signalAmber,
                )
                if (items.isNotEmpty()) {
                    TextButton(
                        onClick = onResumeQueue,
                        modifier = Modifier.defaultMinSize(
                            minWidth = spacing.touchFloor,
                            minHeight = 40.dp,
                        ),
                    ) {
                        Text("Resume", style = type.labelUi, color = colors.signalAmber)
                    }
                }
            }
            HorizontalDivider(thickness = spacing.hairlineWidth, color = colors.hairline)
        } else if (items.isNotEmpty()) {
            Text(
                text = "Up next · ${items.size} queued",
                style = type.labelSmallUi,
                color = colors.inkMuted,
                modifier = Modifier.padding(start = spacing.md, top = spacing.sm, bottom = 2.dp),
            )
        }

        items.sortedBy { it.position }.forEachIndexed { idx, item ->
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
                        Text("Save", style = type.labelUi, color = colors.signalCyan)
                    }
                    TextButton(
                        onClick = onCancelEdit,
                        modifier = Modifier.defaultMinSize(
                            minWidth = spacing.touchFloor,
                            minHeight = spacing.touchFloor,
                        ),
                    ) {
                        Text("Cancel", style = type.labelUi, color = colors.inkMuted)
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
                        text = "${item.position + 1}.",
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
                                style = type.labelSmallUi,
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
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = "Edit queued prompt ${item.position + 1}",
                            tint = colors.inkSecondary,
                            modifier = Modifier.size(18.dp),
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
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = "Delete queued prompt ${item.position + 1}",
                            tint = colors.signalCrimson,
                            modifier = Modifier.size(18.dp),
                        )
                    }

                    TextButton(
                        onClick = { onSendNow(item.itemId) },
                        modifier = Modifier.defaultMinSize(
                            minWidth = spacing.touchFloor,
                            minHeight = spacing.touchFloor,
                        ),
                    ) {
                        Text("Send now", style = type.labelUi, color = colors.signalCyan)
                    }
                }
            }
            if (idx < items.lastIndex) {
                HorizontalDivider(thickness = spacing.hairlineWidth, color = colors.hairline)
            }
        }
    }
}

// ── Modern Rounded Chat Composer Dock ──────────────────────────────────────

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
            .background(colors.chassisVoid)
            .imePadding(),
    ) {
        // 1. IME-Bounded @file Autocomplete (maxHeight = 180.dp)
        if (atFileCandidates.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.md, vertical = spacing.xs)
                    .heightIn(max = 180.dp)
                    .clip(shapes.md)
                    .background(colors.stratumRaised)
                    .border(spacing.hairlineWidth, colors.signalCyan, shapes.md),
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
                            style = type.labelSmallUi,
                            color = colors.signalCyan,
                        )
                    }
                    HorizontalDivider(thickness = spacing.hairlineWidth, color = colors.hairline)
                }
            }
        }

        // 2. Slash Command Pills Row
        if (slashCommands.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = spacing.md, vertical = spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(slashCommands, key = { it.name }) { cmd ->
                    Row(
                        modifier = Modifier
                            .defaultMinSize(minHeight = 40.dp)
                            .clip(shapes.pill)
                            .background(colors.stratumBase)
                            .border(spacing.hairlineWidth, colors.hairlineStrong, shapes.pill)
                            .clickable { onSelectSlashCommand(cmd.name) }
                            .padding(horizontal = spacing.md, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                    ) {
                        Text("/${cmd.name}", style = type.monoCode, color = colors.signalCyan)
                        if (cmd.description.isNotBlank()) {
                            Text(
                                text = cmd.description,
                                style = type.labelSmallUi,
                                color = colors.inkMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        // 3. Magic-Byte Attachment Preview Strip
        if (attachments.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = spacing.md, vertical = spacing.xs),
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
        }

        // 4. Unified Rounded Chat Input Bar
        val hasReadyContent = draftText.isNotBlank() ||
            attachments.any { it.state == AttachmentUploadState.READY }
        val anyUploading = attachments.any { it.state == AttachmentUploadState.UPLOADING }
        val canSend = hasReadyContent && !anyUploading && !isSubmitting

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.md, vertical = spacing.sm)
                .clip(shapes.lg)
                .background(colors.stratumBase)
                .border(spacing.hairlineWidth, colors.hairlineStrong, shapes.lg)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            IconButton(
                onClick = onPickAttachment,
                modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
            ) {
                Icon(
                    imageVector = Icons.Rounded.AttachFile,
                    contentDescription = "Attach file",
                    tint = colors.inkSecondary,
                )
            }

            IconButton(
                onClick = onToggleSlashBar,
                modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
            ) {
                Text(
                    text = "/",
                    style = type.titleBar,
                    color = colors.signalCyan,
                )
            }

            OutlinedTextField(
                value = fieldValue,
                onValueChange = { next ->
                    fieldValue = next
                    onDraftChange(next.text, next.selection.end)
                },
                placeholder = {
                    Text(
                        text = if (isTurnActive) "Queue a follow-up..." else "Message Antigravity...",
                        style = type.bodyProse,
                        color = colors.inkMuted,
                    )
                },
                textStyle = type.bodyProse,
                maxLines = 5,
                shape = shapes.md,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedTextColor = colors.inkPrimary,
                    unfocusedTextColor = colors.inkPrimary,
                    cursorColor = colors.signalCyan,
                ),
                modifier = Modifier.weight(1f),
            )

            Box(
                modifier = Modifier
                    .defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor)
                    .padding(4.dp),
                contentAlignment = Alignment.Center,
            ) {
                val buttonBg = when {
                    !canSend -> colors.stratumRaised
                    isTurnActive -> colors.signalAmber
                    else -> colors.signalCyan
                }
                val iconTint = if (canSend) colors.inkInverse else colors.inkMuted

                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(buttonBg)
                        .clickable(enabled = canSend, onClick = onSubmit),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = colors.inkInverse,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.ArrowUpward,
                            contentDescription = if (isTurnActive) "Queue follow-up" else "Send message",
                            tint = iconTint,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
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
    val bgColor = if (isFailed) colors.signalCrimsonDim else colors.stratumBase

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
                    .background(colors.stratumRaised)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
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
                style = type.labelSmallUi,
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
                        Text("Uploading", style = type.labelSmallUi, color = colors.signalCyan)
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
                        .ifBlank { "Rejected" }
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
                imageVector = Icons.Rounded.Close,
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
    mime.startsWith("text/") || mime.contains("json") -> "TEXT"
    else -> "FILE"
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
