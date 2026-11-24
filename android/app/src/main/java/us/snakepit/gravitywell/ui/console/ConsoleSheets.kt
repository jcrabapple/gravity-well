package us.snakepit.gravitywell.ui.console

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import us.snakepit.gravitywell.theme.GravityWellTheme
import us.snakepit.gravitywell.ui.sessions.ArtifactItemDto
import us.snakepit.gravitywell.ui.sessions.PermissionRuleDto

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionConfigSheet(
    session: ReducedSessionState,
    streamIndicator: ConsoleStreamIndicator = ConsoleStreamIndicator.LIVE,
    onUpdateConfig: (model: String?, thoughtLevel: String?, mode: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    val modelOption = session.configOptions.firstOrNull { it.id == "model" }
    val modelChoices = buildList {
        modelOption?.options?.map { it.value }?.forEach { add(it) }
        addAll(listOf("gemini-4-neon", "gemini-3.8-flash", "claude-sonnet-5-5"))
        if (session.modelId.isNotBlank()) add(session.modelId)
    }.distinct()

    val thoughtChoices = listOf(
        "low" to "Low",
        "medium" to "Medium",
        "high" to "High",
        "xhigh" to "Max",
    )
    val modeChoices = listOf(
        Triple("default", "Ask before tools", "Prompt for approval before running mutating tools"),
        Triple("accept-edits", "Accept file edits", "Auto-accept workspace edits; ask before commands"),
        Triple("auto", "Auto-run tools", "Automatically executes tools without asking"),
        Triple("plan", "Plan only", "Read-only planning and architecture pass"),
    )

    val fillFraction = if (session.usage.size > 0L) {
        (session.usage.used.toFloat() / session.usage.size.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    val (streamLabel, streamColor) = when (streamIndicator) {
        ConsoleStreamIndicator.LIVE -> "Connected" to colors.signalEmerald
        ConsoleStreamIndicator.REPLAYING -> "Syncing history..." to colors.signalCyan
        ConsoleStreamIndicator.RECONNECTING -> "Reconnecting..." to colors.signalAmber
        ConsoleStreamIndicator.RESYNCED -> "Resynced" to colors.signalViolet
        ConsoleStreamIndicator.OFFLINE -> "Offline cache" to colors.inkMuted
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.stratumBase,
        contentColor = colors.inkPrimary,
        tonalElevation = spacing.elevationZero,
        shape = shapes.sheetTop,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.xl, vertical = spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Chat settings", style = type.headlineSection, color = colors.inkPrimary)
                Box(
                    modifier = Modifier
                        .clip(shapes.pill)
                        .background(if (session.sandbox) colors.signalEmeraldDim else colors.stratumRaised)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = if (session.sandbox) "Sandbox on" else "Sandbox off",
                        style = type.labelSmallUi,
                        color = if (session.sandbox) colors.signalEmerald else colors.inkSecondary,
                    )
                }
            }

            // Context window & connection info card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shapes.md)
                    .background(colors.stratumRaised)
                    .padding(spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Context window", style = type.labelUi, color = colors.inkPrimary)
                    Text(
                        text = "${formatTokenCompact(session.usage.used)} / ${formatTokenCompact(session.usage.size)}",
                        style = type.monoMicro,
                        color = colors.inkSecondary,
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(shapes.pill)
                        .background(colors.stratumWell),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fillFraction)
                            .fillMaxHeight()
                            .clip(shapes.pill)
                            .background(if (fillFraction > 0.85f) colors.signalAmber else colors.signalCyan),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(streamColor),
                        )
                        Text(streamLabel, style = type.labelSmallUi, color = colors.inkSecondary)
                    }
                    if (session.lastCursor > 0L) {
                        Text(
                            text = "Cursor #${session.lastCursor}",
                            style = type.monoMicro,
                            color = colors.inkMuted,
                        )
                    }
                }
                if (session.cwd.isNotBlank()) {
                    Text(
                        text = session.cwd,
                        style = type.monoMicro,
                        color = colors.inkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // 1. Model Family
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Text("Model", style = type.labelUi, color = colors.inkSecondary)
                Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    modelChoices.forEach { m ->
                        val selected = session.modelId == m
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = spacing.touchFloor)
                                .clip(shapes.sm)
                                .background(if (selected) colors.signalCyanDim else colors.stratumRaised)
                                .border(
                                    spacing.hairlineWidth,
                                    if (selected) colors.signalCyan else colors.hairline,
                                    shapes.sm,
                                )
                                .clickable { onUpdateConfig(m, null, null) }
                                .padding(horizontal = spacing.md),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = formatModelDisplayName(m),
                                style = type.bodyCompact,
                                color = if (selected) colors.signalCyan else colors.inkPrimary,
                            )
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = "Selected",
                                    tint = colors.signalCyan,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }

            // 2. Thinking Effort
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Text("Thinking effort", style = type.labelUi, color = colors.inkSecondary)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shapes.pill)
                        .background(colors.stratumRaised)
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    thoughtChoices.forEach { (lvl, label) ->
                        val selected = session.thoughtLevel.lowercase() == lvl
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 42.dp)
                                .clip(shapes.pill)
                                .background(if (selected) colors.signalCyanDim else colors.stratumRaised)
                                .clickable { onUpdateConfig(null, lvl, null) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = label,
                                style = type.labelUi,
                                color = if (selected) colors.signalCyan else colors.inkSecondary,
                            )
                        }
                    }
                }
            }

            // 3. Execution Mode
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Text("Tool approval mode", style = type.labelUi, color = colors.inkSecondary)
                Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    modeChoices.forEach { (modeKey, displayTitle, subtitle) ->
                        val selected = session.modeId == modeKey
                        val isAutoRisky = modeKey == "auto"
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = spacing.touchFloor)
                                .clip(shapes.sm)
                                .background(
                                    when {
                                        selected && isAutoRisky -> colors.signalAmberDim
                                        selected -> colors.signalCyanDim
                                        else -> colors.stratumRaised
                                    },
                                )
                                .border(
                                    spacing.hairlineWidth,
                                    when {
                                        selected && isAutoRisky -> colors.signalAmber
                                        selected -> colors.signalCyan
                                        else -> colors.hairline
                                    },
                                    shapes.sm,
                                )
                                .clickable { onUpdateConfig(null, null, modeKey) }
                                .padding(horizontal = spacing.sm, vertical = spacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = { onUpdateConfig(null, null, modeKey) },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = if (isAutoRisky) colors.signalAmber else colors.signalCyan,
                                    unselectedColor = colors.inkMuted,
                                ),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = displayTitle,
                                    style = type.labelUi,
                                    color = if (isAutoRisky) colors.signalAmber else colors.inkPrimary,
                                )
                                Text(
                                    text = subtitle,
                                    style = type.bodyCompact,
                                    color = if (isAutoRisky && selected) colors.signalAmber else colors.inkSecondary,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(spacing.lg))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrainArtifactViewerSheet(
    artifacts: List<ArtifactItemDto>,
    isLoading: Boolean,
    selectedArtifact: LoadedBrainArtifact?,
    onSelectArtifact: (ArtifactItemDto) -> Unit,
    onBackToList: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.stratumBase,
        contentColor = colors.inkPrimary,
        tonalElevation = spacing.elevationZero,
        shape = shapes.sheetTop,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 320.dp, max = 640.dp)
                .padding(horizontal = spacing.xl, vertical = spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            if (selectedArtifact == null) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Artifacts", style = type.headlineSection, color = colors.inkPrimary)
                    Text(
                        text = "Files and logs saved by this conversation",
                        style = type.bodyCompact,
                        color = colors.inkSecondary,
                    )
                }

                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(spacing.xl),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = colors.signalCyan)
                    }
                } else if (artifacts.isEmpty()) {
                    Text(
                        text = "No artifacts saved for this chat yet.",
                        style = type.bodyCompact,
                        color = colors.inkMuted,
                        modifier = Modifier.padding(vertical = spacing.lg),
                    )
                } else {
                    val grouped = remember(artifacts) {
                        artifacts.groupBy { item ->
                            val slash = item.path.lastIndexOf('/')
                            if (slash > 0) item.path.substring(0, slash) else "/"
                        }
                    }
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(spacing.xs),
                    ) {
                        grouped.forEach { (dir, dirItems) ->
                            item(key = "dir_$dir") {
                                Text(
                                    text = dir,
                                    style = type.labelSmallUi,
                                    color = colors.inkMuted,
                                    modifier = Modifier.padding(top = spacing.sm, bottom = 2.dp),
                                )
                            }
                            items(dirItems, key = { it.path }) { item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .defaultMinSize(minHeight = spacing.touchFloor)
                                        .clip(shapes.sm)
                                        .background(colors.stratumRaised)
                                        .clickable { onSelectArtifact(item) }
                                        .padding(horizontal = spacing.md, vertical = spacing.sm),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.path.substringAfterLast('/'),
                                            style = type.bodyCompact,
                                            color = colors.inkPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = item.mimeType,
                                            style = type.monoMicro,
                                            color = colors.inkSecondary,
                                        )
                                    }
                                    Text(
                                        text = formatByteSize(item.sizeBytes),
                                        style = type.monoMicro,
                                        color = colors.signalCyan,
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f),
                    ) {
                        IconButton(
                            onClick = onBackToList,
                            modifier = Modifier.defaultMinSize(
                                minWidth = spacing.touchFloor,
                                minHeight = spacing.touchFloor,
                            ),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back to artifacts list",
                                tint = colors.inkPrimary,
                            )
                        }
                        Text(
                            text = selectedArtifact.item.path.substringAfterLast('/'),
                            style = type.titleBar,
                            color = colors.inkPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    if (selectedArtifact.isSecretRedactedText) {
                        Row(
                            modifier = Modifier
                                .clip(shapes.pill)
                                .background(colors.signalVioletDim)
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Shield,
                                contentDescription = null,
                                tint = colors.signalViolet,
                                modifier = Modifier.size(13.dp),
                            )
                            Text("Redacted", style = type.labelSmallUi, color = colors.signalViolet)
                        }
                    }
                }

                HorizontalDivider(thickness = spacing.hairlineWidth, color = colors.hairline)

                val imageBytes = selectedArtifact.imageBytes
                if (imageBytes != null) {
                    val bitmap = remember(imageBytes) {
                        runCatching { BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)?.asImageBitmap() }
                            .getOrNull()
                    }
                    var scale by remember(imageBytes) { mutableFloatStateOf(1f) }
                    var offsetX by remember(imageBytes) { mutableFloatStateOf(0f) }
                    var offsetY by remember(imageBytes) { mutableFloatStateOf(0f) }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(380.dp)
                            .clip(shapes.md)
                            .background(colors.stratumWell)
                            .border(spacing.hairlineWidth, colors.hairline, shapes.md)
                            .pointerInput(imageBytes) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    scale = (scale * zoom).coerceIn(1f, 5f)
                                    offsetX += pan.x
                                    offsetY += pan.y
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = selectedArtifact.item.path,
                                modifier = Modifier.graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    translationX = offsetX,
                                    translationY = offsetY,
                                ),
                            )
                        } else {
                            Text("Unable to decode image artifact", style = type.bodyCompact, color = colors.signalCrimson)
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(shapes.md)
                            .background(colors.stratumWell)
                            .border(spacing.hairlineWidth, colors.hairline, shapes.md)
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                            .padding(spacing.md),
                    ) {
                        SelectionContainer {
                            Text(
                                text = selectedArtifact.textContent.orEmpty(),
                                style = type.monoCode,
                                color = colors.inkPrimary,
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(spacing.sm))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceRulesSheet(
    rules: List<PermissionRuleDto>,
    onRevokeRule: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.stratumBase,
        contentColor = colors.inkPrimary,
        tonalElevation = spacing.elevationZero,
        shape = shapes.sheetTop,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.xl, vertical = spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Project permission rules", style = type.headlineSection, color = colors.inkPrimary)
                Text(
                    text = "Tools automatically approved for this workspace",
                    style = type.bodyCompact,
                    color = colors.inkSecondary,
                )
            }

            if (rules.isEmpty()) {
                Text(
                    text = "No auto-approval rules saved for this project.",
                    style = type.bodyCompact,
                    color = colors.inkMuted,
                    modifier = Modifier.padding(vertical = spacing.md),
                )
            } else {
                rules.forEach { rule ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(shapes.sm)
                            .background(colors.stratumRaised)
                            .padding(spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = buildString {
                                    append(rule.toolKind)
                                    if (rule.commandPrefix.isNotBlank()) {
                                        append(" · ")
                                        append(rule.commandPrefix)
                                    }
                                },
                                style = type.monoCode,
                                color = colors.inkPrimary,
                            )
                            Text(
                                text = rule.workspaceRoot,
                                style = type.monoMicro,
                                color = colors.inkSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(modifier = Modifier.width(spacing.sm))
                        OutlinedButton(
                            onClick = { onRevokeRule(rule.ruleId) },
                            shape = shapes.pill,
                            border = BorderStroke(spacing.hairlineWidth, colors.signalCrimson),
                            modifier = Modifier.defaultMinSize(
                                minWidth = spacing.touchFloor,
                                minHeight = spacing.touchFloor,
                            ),
                        ) {
                            Text("Revoke", style = type.labelUi, color = colors.signalCrimson)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(spacing.lg))
        }
    }
}

internal fun formatModelDisplayName(rawModelId: String): String {
    val clean = rawModelId.trim()
    if (clean.isEmpty()) return "Default model"
    return clean.split('-', '_')
        .filter { it.isNotBlank() }
        .joinToString(" ") { part ->
            part.replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase() else ch.toString() }
        }
}
