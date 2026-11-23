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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Shield
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

    val thoughtChoices = listOf("low", "medium", "high", "xhigh")
    val modeChoices = listOf(
        "default" to "Prompt before running mutating tools",
        "accept-edits" to "Auto-accept workspace file edits; gate commands",
        "auto" to "Auto-executes tools",
        "plan" to "Read-only planning & architecture pass",
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.stratumBase,
        contentColor = colors.inkPrimary,
        tonalElevation = spacing.elevationZero,
        shape = shapes.md,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("LIVE SESSION CONFIG", style = type.headlineSection, color = colors.inkPrimary)
                Box(
                    modifier = Modifier
                        .clip(shapes.sm)
                        .background(if (session.sandbox) colors.signalEmeraldDim else colors.stratumRaised)
                        .border(
                            spacing.hairlineWidth,
                            if (session.sandbox) colors.signalEmerald else colors.hairlineStrong,
                            shapes.sm,
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = if (session.sandbox) "SANDBOX: ON" else "SANDBOX: OFF",
                        style = type.monoMicro,
                        color = if (session.sandbox) colors.signalEmerald else colors.inkMuted,
                    )
                }
            }

            // 1. Model Family
            Text("MODEL FAMILY", style = type.labelTelemetry, color = colors.inkSecondary)
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
                    ) {
                        Text(
                            text = m,
                            style = type.monoCode,
                            color = if (selected) colors.signalCyan else colors.inkPrimary,
                        )
                    }
                }
            }

            // 2. 4-Segment Thought Level Selector (LOW | MEDIUM | HIGH | XHIGH)
            Text("THOUGHT LEVEL", style = type.labelTelemetry, color = colors.inkSecondary)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shapes.sm)
                    .border(spacing.hairlineWidth, colors.hairlineStrong, shapes.sm),
            ) {
                thoughtChoices.forEachIndexed { idx, lvl ->
                    val selected = session.thoughtLevel.lowercase() == lvl
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = spacing.touchFloor)
                            .background(if (selected) colors.signalCyanDim else colors.stratumRaised)
                            .clickable { onUpdateConfig(null, lvl, null) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = lvl.uppercase(),
                            style = type.labelTelemetry,
                            color = if (selected) colors.signalCyan else colors.inkSecondary,
                        )
                    }
                    if (idx < thoughtChoices.lastIndex) {
                        Box(
                            modifier = Modifier
                                .width(spacing.hairlineWidth)
                                .height(spacing.touchFloor)
                                .background(colors.hairline),
                        )
                    }
                }
            }

            // 3. 4-Row Mode Radio Selector
            Text("EXECUTION MODE", style = type.labelTelemetry, color = colors.inkSecondary)
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                modeChoices.forEach { (modeKey, subtitle) ->
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
                                text = modeKey,
                                style = type.monoCode,
                                color = if (isAutoRisky) colors.signalAmber else colors.inkPrimary,
                            )
                            Text(
                                text = subtitle,
                                style = type.monoMicro,
                                color = if (isAutoRisky) colors.signalAmber else colors.inkSecondary,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(spacing.md))
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
        shape = shapes.md,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 320.dp, max = 640.dp)
                .padding(horizontal = spacing.lg, vertical = spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            if (selectedArtifact == null) {
                Text("BRAIN/ ARTIFACT BAY", style = type.labelTelemetry, color = colors.signalCyan)
                Text("Session Artifacts", style = type.headlineSection, color = colors.inkPrimary)
                HorizontalDivider(thickness = spacing.hairlineWidth, color = colors.hairline)

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
                        text = "No brain/ trajectory artifacts recorded for this session.",
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
                                    style = type.labelTelemetry,
                                    color = colors.inkMuted,
                                    modifier = Modifier.padding(top = spacing.xs),
                                )
                            }
                            items(dirItems, key = { it.path }) { item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .defaultMinSize(minHeight = spacing.touchFloor)
                                        .clip(shapes.sm)
                                        .background(colors.stratumRaised)
                                        .border(spacing.hairlineWidth, colors.hairline, shapes.sm)
                                        .clickable { onSelectArtifact(item) }
                                        .padding(horizontal = spacing.md, vertical = spacing.xs),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.path.substringAfterLast('/'),
                                            style = type.monoCode,
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onBackToList,
                            modifier = Modifier.defaultMinSize(
                                minWidth = spacing.touchFloor,
                                minHeight = spacing.touchFloor,
                            ),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back to artifacts list",
                                tint = colors.inkPrimary,
                            )
                        }
                        Text(
                            text = selectedArtifact.item.path,
                            style = type.monoCode,
                            color = colors.inkPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    if (selectedArtifact.isSecretRedactedText) {
                        Row(
                            modifier = Modifier
                                .clip(shapes.sm)
                                .background(colors.signalVioletDim)
                                .border(spacing.hairlineWidth, colors.signalViolet, shapes.sm)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                tint = colors.signalViolet,
                                modifier = Modifier.size(12.dp),
                            )
                            Text("SECRET-REDACTED", style = type.monoMicro, color = colors.signalViolet)
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
                            .clip(shapes.sm)
                            .background(colors.stratumWell)
                            .border(spacing.hairlineWidth, colors.hairline, shapes.sm)
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
                            Text("Unable to decode image artifact", style = type.monoMicro, color = colors.signalCrimson)
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(shapes.sm)
                            .background(colors.stratumWell)
                            .border(spacing.hairlineWidth, colors.hairline, shapes.sm)
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
        shape = shapes.md,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text("WORKSPACE PERMISSION RULES", style = type.labelTelemetry, color = colors.signalAmber)
            Text("Granted Allow-Rules", style = type.headlineSection, color = colors.inkPrimary)
            HorizontalDivider(thickness = spacing.hairlineWidth, color = colors.hairline)

            if (rules.isEmpty()) {
                Text(
                    text = "Zero persistent workspace allow-rules active for this cwd.",
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
                            .border(spacing.hairlineWidth, colors.hairline, shapes.sm)
                            .padding(spacing.sm),
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
                            shape = shapes.sm,
                            border = BorderStroke(spacing.hairlineWidth, colors.signalCrimson),
                            modifier = Modifier.defaultMinSize(
                                minWidth = spacing.touchFloor,
                                minHeight = spacing.touchFloor,
                            ),
                        ) {
                            Text("Revoke", style = type.labelTelemetry, color = colors.signalCrimson)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(spacing.md))
        }
    }
}
