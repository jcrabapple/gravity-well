package us.snakepit.gravitywell.ui.sessions

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import us.snakepit.gravitywell.theme.GravityWellTheme

@Composable
fun SessionBrowserScreen(
    state: SessionBrowserUiState,
    selectedSessionId: String?,
    onScopeChange: (SessionScopeFilter) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSelectWorkspace: (String?) -> Unit,
    onToggleRadar: () -> Unit,
    onRefresh: () -> Unit,
    onOpenNewSession: () -> Unit,
    onSessionClick: (SessionBrowserItem) -> Unit,
    onRenameRequest: (SessionBrowserItem) -> Unit,
    onCloseSession: (String) -> Unit,
    onForkSession: (SessionBrowserItem) -> Unit,
    onResumeExternalSession: (ExternalSessionPresenceDto) -> Unit,
    onDismissResumePrompt: () -> Unit,
    onConfirmResume: (sessionId: String, cwd: String?, loadHistory: Boolean) -> Unit,
    onUpdateRenameDraft: (String) -> Unit,
    onDismissRename: () -> Unit,
    onSubmitRename: () -> Unit,
    onDismissNewSession: () -> Unit,
    onUpdateNewSessionForm: ((NewSessionFormState) -> NewSessionFormState) -> Unit,
    onAddExtraDir: () -> Unit,
    onRemoveExtraDir: (String) -> Unit,
    onSubmitCreateSession: () -> Unit,
    onDismissNotices: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.chassisVoid),
    ) {
        // ── Scope Strip + New Session + Refresh ──────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.stratumBase)
                .padding(horizontal = spacing.md, vertical = spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                ScopeSegmentedStrip(
                    selected = state.scope,
                    onSelect = onScopeChange,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh sessions",
                        tint = colors.inkSecondary,
                    )
                }
                OutlinedButton(
                    onClick = onOpenNewSession,
                    shape = shapes.sm,
                    border = BorderStroke(spacing.hairlineWidth, colors.signalCyan),
                    contentPadding = PaddingValues(horizontal = spacing.md, vertical = 0.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = colors.signalCyanDim,
                        contentColor = colors.signalCyan,
                    ),
                    modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(spacing.xs))
                    Text("NEW", style = type.labelTelemetry)
                }
            }

            // ── FTS5 Search Input (stratumWell) ──────────────────────────────
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = onSearchQueryChange,
                singleLine = true,
                textStyle = type.monoCode,
                placeholder = {
                    Text(
                        text = "Search live & FTS5 archived conversations...",
                        style = type.bodyCompact,
                        color = colors.inkMuted,
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = colors.inkMuted,
                    )
                },
                trailingIcon = {
                    if (state.searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { onSearchQueryChange("") },
                            modifier = Modifier.defaultMinSize(
                                minWidth = spacing.touchFloor,
                                minHeight = spacing.touchFloor,
                            ),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear search query",
                                tint = colors.inkSecondary,
                            )
                        }
                    }
                },
                shape = shapes.sm,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = colors.stratumWell,
                    unfocusedContainerColor = colors.stratumWell,
                    focusedBorderColor = colors.signalCyan,
                    unfocusedBorderColor = colors.hairline,
                    focusedTextColor = colors.inkPrimary,
                    unfocusedTextColor = colors.inkPrimary,
                    cursorColor = colors.signalCyan,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            // ── Workspace Filter Chips ───────────────────────────────────────
            if (state.availableWorkspaces.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    item(key = "__all_ws__") {
                        WorkspaceFilterChip(
                            label = "All Workspaces",
                            selected = state.selectedWorkspaceCwd == null,
                            onClick = { onSelectWorkspace(null) },
                        )
                    }
                    items(state.availableWorkspaces, key = { it }) { ws ->
                        WorkspaceFilterChip(
                            label = basenameOfPath(ws),
                            selected = state.selectedWorkspaceCwd == ws,
                            onClick = { onSelectWorkspace(ws) },
                        )
                    }
                }
            }
        }

        HorizontalDivider(thickness = spacing.hairlineWidth, color = colors.hairline)

        // ── External Session Radar Strip (FLOCK-verified only, D10) ──────────
        if (state.externalSessions.isNotEmpty()) {
            ExternalSessionRadarStrip(
                externalSessions = state.externalSessions,
                expanded = state.radarExpanded,
                onToggle = onToggleRadar,
                onResumeExternal = onResumeExternalSession,
            )
            HorizontalDivider(thickness = spacing.hairlineWidth, color = colors.hairline)
        }

        // ── Inline Error / Capability Notice Banner ──────────────────────────
        val bannerText = state.errorBanner ?: state.infoNotice
        if (bannerText != null) {
            val isError = state.errorBanner != null
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (isError) colors.signalCrimsonDim else colors.signalAmberDim)
                    .padding(horizontal = spacing.md, vertical = spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = bannerText,
                    style = type.monoMicro,
                    color = if (isError) colors.signalCrimson else colors.signalAmber,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onDismissNotices,
                    modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss notice",
                        tint = if (isError) colors.signalCrimson else colors.signalAmber,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            HorizontalDivider(thickness = spacing.hairlineWidth, color = colors.hairline)
        }

        // ── Telemetry Session Ledger List ────────────────────────────────────
        if (state.sessions.isEmpty() && !state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(spacing.xl),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    Text(
                        text = "NO SESSIONS IN SCOPE",
                        style = type.labelTelemetry,
                        color = colors.inkMuted,
                    )
                    Text(
                        text = "Spawn an Antigravity ACP supervisor in a host workspace or broaden the filter.",
                        style = type.bodyCompact,
                        color = colors.inkSecondary,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
            ) {
                items(
                    items = state.sessions,
                    key = { it.sessionId },
                ) { item ->
                    TelemetrySessionRow(
                        item = item,
                        isSelected = item.sessionId == selectedSessionId,
                        forkSupported = state.capabilities.fork,
                        agyVersion = state.agyVersion,
                        onClick = { onSessionClick(item) },
                        onRename = { onRenameRequest(item) },
                        onClose = { onCloseSession(item.sessionId) },
                        onFork = { onForkSession(item) },
                    )
                    HorizontalDivider(thickness = spacing.hairlineWidth, color = colors.hairline)
                }
            }
        }
    }

    // ── Resume Archived Session Sheet ────────────────────────────────────────
    val resumeTarget = state.resumePromptTarget
    if (resumeTarget != null) {
        ResumeSessionSheet(
            target = resumeTarget,
            isResuming = state.isResuming,
            onResumeFast = {
                onConfirmResume(resumeTarget.sessionId, resumeTarget.cwd, false)
            },
            onResumeWithHistory = {
                onConfirmResume(resumeTarget.sessionId, resumeTarget.cwd, true)
            },
            onDismiss = onDismissResumePrompt,
        )
    }

    // ── Rename Session Modal Card ────────────────────────────────────────────
    val renameTarget = state.renameTarget
    if (renameTarget != null) {
        AlertDialog(
            onDismissRequest = onDismissRename,
            containerColor = colors.stratumBase,
            tonalElevation = spacing.elevationZero,
            shape = shapes.md,
            title = {
                Text("RENAME SESSION OVERRIDE", style = type.labelTelemetry, color = colors.signalViolet)
            },
            text = {
                OutlinedTextField(
                    value = state.renameDraft,
                    onValueChange = onUpdateRenameDraft,
                    singleLine = true,
                    textStyle = type.bodyProse,
                    shape = shapes.sm,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = colors.stratumWell,
                        unfocusedContainerColor = colors.stratumWell,
                        focusedBorderColor = colors.signalViolet,
                        unfocusedBorderColor = colors.hairline,
                        focusedTextColor = colors.inkPrimary,
                        unfocusedTextColor = colors.inkPrimary,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = onSubmitRename,
                    modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                ) {
                    Text("SAVE", style = type.labelTelemetry, color = colors.signalCyan)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onDismissRename,
                    modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                ) {
                    Text("CANCEL", style = type.labelTelemetry, color = colors.inkSecondary)
                }
            },
        )
    }

    // ── New Session Sheet ────────────────────────────────────────────────────
    if (state.showNewSessionSheet) {
        NewSessionBottomSheet(
            form = state.newSessionForm,
            recentWorkspaces = state.availableWorkspaces,
            spawnAddDirSupported = state.capabilities.spawnAddDir,
            onUpdateForm = onUpdateNewSessionForm,
            onAddExtraDir = onAddExtraDir,
            onRemoveExtraDir = onRemoveExtraDir,
            onSubmit = onSubmitCreateSession,
            onDismiss = onDismissNewSession,
        )
    }
}

@Composable
private fun ScopeSegmentedStrip(
    selected: SessionScopeFilter,
    onSelect: (SessionScopeFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    Row(
        modifier = modifier
            .clip(shapes.sm)
            .border(spacing.hairlineWidth, colors.hairline, shapes.sm)
            .background(colors.stratumRaised),
    ) {
        SessionScopeFilter.entries.forEachIndexed { index, option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor)
                    .background(if (isSelected) colors.signalCyanDim else colors.stratumRaised)
                    .clickable(role = Role.Tab) { onSelect(option) }
                    .padding(horizontal = spacing.sm),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option.label,
                    style = type.labelTelemetry,
                    color = if (isSelected) colors.signalCyan else colors.inkSecondary,
                )
            }
            if (index < SessionScopeFilter.entries.lastIndex) {
                Box(
                    modifier = Modifier
                        .width(spacing.hairlineWidth)
                        .height(spacing.touchFloor)
                        .background(colors.hairline),
                )
            }
        }
    }
}

@Composable
private fun WorkspaceFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor)
            .clip(shapes.sm)
            .background(if (selected) colors.signalCyanDim else colors.stratumRaised)
            .border(
                width = spacing.hairlineWidth,
                color = if (selected) colors.signalCyan else colors.hairline,
                shape = shapes.sm,
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = spacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = type.monoMicro,
            color = if (selected) colors.signalCyan else colors.inkSecondary,
        )
    }
}

@Composable
private fun ExternalSessionRadarStrip(
    externalSessions: List<ExternalSessionPresenceDto>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onResumeExternal: (ExternalSessionPresenceDto) -> Unit,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    val hasUnsupervised = externalSessions.any { !it.supervised }
    val accent = if (hasUnsupervised) colors.signalAmber else colors.signalCyan
    val bgTint = if (hasUnsupervised) colors.signalAmberDim else colors.signalCyanDim
    val noun = if (externalSessions.size == 1) "SESSION" else "SESSIONS"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgTint),
    ) {
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
                text = "EXTERNAL RADAR · ${externalSessions.size} ACTIVE HOST $noun",
                style = type.labelTelemetry,
                color = accent,
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse external radar" else "Expand external radar",
                tint = accent,
            )
        }

        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = spacing.md, end = spacing.md, bottom = spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                externalSessions.forEach { ext ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(shapes.sm)
                            .background(colors.stratumBase)
                            .border(spacing.hairlineWidth, colors.hairline, shapes.sm)
                            .padding(spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                            ) {
                                Text(
                                    text = ext.title,
                                    style = type.bodyCompact,
                                    color = colors.inkPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(shapes.sm)
                                        .background(if (ext.supervised) colors.signalCyanDim else colors.signalAmberDim)
                                        .border(
                                            spacing.hairlineWidth,
                                            if (ext.supervised) colors.signalCyan else colors.signalAmber,
                                            shapes.sm,
                                        )
                                        .padding(horizontal = 4.dp, vertical = 1.dp),
                                ) {
                                    Text(
                                        text = if (ext.supervised) "SUPERVISED" else "HOST EXTERNAL",
                                        style = type.monoMicro,
                                        color = if (ext.supervised) colors.signalCyan else colors.signalAmber,
                                    )
                                }
                            }
                            Text(
                                text = buildString {
                                    append(ext.cwd ?: "unknown cwd")
                                    if (!ext.supervised) append(" · read-only unless resumed")
                                },
                                style = type.monoMicro,
                                color = colors.inkSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (!ext.supervised) {
                            TextButton(
                                onClick = { onResumeExternal(ext) },
                                modifier = Modifier.defaultMinSize(
                                    minWidth = spacing.touchFloor,
                                    minHeight = spacing.touchFloor,
                                ),
                            ) {
                                Text("RESUME", style = type.labelTelemetry, color = colors.signalCyan)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetrySessionRow(
    item: SessionBrowserItem,
    isSelected: Boolean,
    forkSupported: Boolean,
    agyVersion: String,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onClose: () -> Unit,
    onFork: () -> Unit,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    val isPulsing = item.status == "running" || item.status == "starting"
    val pulseAlpha = if (isPulsing) {
        val transition = rememberInfiniteTransition(label = "session_rail_pulse")
        val anim by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(750), RepeatMode.Reverse),
            label = "rail_alpha",
        )
        anim
    } else {
        1f
    }

    val railColor = when (item.status) {
        "running", "starting" -> colors.signalCyan
        "awaiting_approval", "awaiting_question" -> colors.signalAmber
        "idle" -> colors.signalEmerald
        "error" -> colors.signalCrimson
        else -> colors.inkMuted
    }

    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(if (isSelected) colors.stratumRaised else colors.stratumBase)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .width(spacing.statusRailWidth)
                .fillMaxHeight()
                .alpha(pulseAlpha)
                .background(railColor),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = spacing.md, top = spacing.sm, bottom = spacing.sm),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // Line 1: title + optional GATEWAY / GATE pills + step count & time
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Text(
                    text = item.title,
                    style = type.titleBar,
                    color = colors.inkPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (item.hasPendingGate) {
                    Box(
                        modifier = Modifier
                            .clip(shapes.sm)
                            .background(colors.signalAmberDim)
                            .border(spacing.hairlineWidth, colors.signalAmber, shapes.sm)
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    ) {
                        Text("GATE", style = type.monoMicro, color = colors.signalAmber)
                    }
                }
                if (item.titleSource == "gateway") {
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
                Text(
                    text = "${item.stepCount}st · ${compactTimestamp(item.updatedAt)}",
                    style = type.monoMicro,
                    color = colors.inkMuted,
                )
            }

            // Line 2: cwd basename + truncated path
            Text(
                text = "${item.cwdBasename} · ${item.cwd}",
                style = type.monoMicro,
                color = colors.inkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // Line 3: live config telemetry + preview snippet
            if (item.isLive) {
                val cfgTag = listOfNotNull(item.modelId, item.thoughtLevel, item.modeId)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                if (cfgTag.isNotEmpty()) {
                    Text(
                        text = cfgTag,
                        style = type.labelTelemetry,
                        color = colors.signalCyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (item.preview.isNotBlank()) {
                Text(
                    text = item.preview,
                    style = type.bodyCompact,
                    color = colors.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Box {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Session actions for ${item.title}",
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
                    text = { Text("Rename", style = type.bodyCompact, color = colors.inkPrimary) },
                    onClick = {
                        menuExpanded = false
                        onRename()
                    },
                )
                if (item.isLive) {
                    DropdownMenuItem(
                        text = { Text("Close Live Process", style = type.bodyCompact, color = colors.signalCrimson) },
                        onClick = {
                            menuExpanded = false
                            onClose()
                        },
                    )
                }
                DropdownMenuItem(
                    enabled = forkSupported,
                    text = {
                        Column {
                            Text(
                                text = "Fork Session",
                                style = type.bodyCompact,
                                color = if (forkSupported) colors.inkPrimary else colors.inkMuted,
                            )
                            if (!forkSupported) {
                                Text(
                                    text = "-32601 unsupported on agy $agyVersion",
                                    style = type.monoMicro,
                                    color = colors.inkMuted,
                                )
                            }
                        }
                    },
                    onClick = {
                        menuExpanded = false
                        onFork()
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResumeSessionSheet(
    target: SessionBrowserItem,
    isResuming: Boolean,
    onResumeFast: () -> Unit,
    onResumeWithHistory: () -> Unit,
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
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Text("ATTACH ACP SUPERVISOR", style = type.labelTelemetry, color = colors.signalCyan)
            Text(target.title, style = type.headlineSection, color = colors.inkPrimary)
            Text(target.cwd, style = type.monoCode, color = colors.inkSecondary)

            Button(
                onClick = onResumeWithHistory,
                enabled = !isResuming,
                shape = shapes.sm,
                elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.signalCyan,
                    contentColor = colors.inkInverse,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = spacing.touchFloor),
            ) {
                Text("Resume + Load History (session/load)", style = type.labelTelemetry)
            }

            OutlinedButton(
                onClick = onResumeFast,
                enabled = !isResuming,
                shape = shapes.sm,
                border = BorderStroke(spacing.hairlineWidth, colors.hairlineStrong),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = colors.stratumRaised,
                    contentColor = colors.inkPrimary,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = spacing.touchFloor),
            ) {
                Text("Resume Without Replay (session/resume)", style = type.labelTelemetry)
            }
            Spacer(modifier = Modifier.height(spacing.md))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun NewSessionBottomSheet(
    form: NewSessionFormState,
    recentWorkspaces: List<String>,
    spawnAddDirSupported: Boolean,
    onUpdateForm: ((NewSessionFormState) -> NewSessionFormState) -> Unit,
    onAddExtraDir: () -> Unit,
    onRemoveExtraDir: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = colors.stratumWell,
        unfocusedContainerColor = colors.stratumWell,
        focusedBorderColor = colors.signalCyan,
        unfocusedBorderColor = colors.hairline,
        focusedTextColor = colors.inkPrimary,
        unfocusedTextColor = colors.inkPrimary,
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
            Text("SPAWN ANTIGRAVITY ACP SESSION", style = type.labelTelemetry, color = colors.signalCyan)
            Text("New Supervised Session", style = type.headlineSection, color = colors.inkPrimary)

            OutlinedTextField(
                value = form.cwd,
                onValueChange = { v -> onUpdateForm { it.copy(cwd = v, error = null) } },
                label = { Text("WORKSPACE CWD", style = type.labelTelemetry) },
                textStyle = type.monoCode,
                singleLine = true,
                shape = shapes.sm,
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )

            if (recentWorkspaces.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    recentWorkspaces.forEach { ws ->
                        WorkspaceFilterChip(
                            label = basenameOfPath(ws),
                            selected = form.cwd == ws,
                            onClick = { onUpdateForm { it.copy(cwd = ws) } },
                        )
                    }
                }
            }

            if (spawnAddDirSupported) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    OutlinedTextField(
                        value = form.addDirDraft,
                        onValueChange = { v -> onUpdateForm { it.copy(addDirDraft = v) } },
                        label = { Text("--ADD-DIR PATH", style = type.labelTelemetry) },
                        textStyle = type.monoCode,
                        singleLine = true,
                        shape = shapes.sm,
                        colors = fieldColors,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = onAddExtraDir,
                        shape = shapes.sm,
                        border = BorderStroke(spacing.hairlineWidth, colors.hairlineStrong),
                        modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                    ) {
                        Text("ADD", style = type.labelTelemetry, color = colors.signalCyan)
                    }
                }

                if (form.addDirs.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                        verticalArrangement = Arrangement.spacedBy(spacing.xs),
                    ) {
                        form.addDirs.forEach { dir ->
                            Row(
                                modifier = Modifier
                                    .clip(shapes.sm)
                                    .background(colors.stratumRaised)
                                    .border(spacing.hairlineWidth, colors.hairline, shapes.sm)
                                    .defaultMinSize(minHeight = spacing.touchFloor)
                                    .clickable { onRemoveExtraDir(dir) }
                                    .padding(horizontal = spacing.sm),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                            ) {
                                Text(dir, style = type.monoMicro, color = colors.inkPrimary)
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Remove $dir",
                                    tint = colors.inkMuted,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                }
            }

            OutlinedTextField(
                value = form.title,
                onValueChange = { v -> onUpdateForm { it.copy(title = v) } },
                label = { Text("CUSTOM TITLE (OPTIONAL)", style = type.labelTelemetry) },
                textStyle = type.bodyProse,
                singleLine = true,
                shape = shapes.sm,
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = form.model,
                onValueChange = { v -> onUpdateForm { it.copy(model = v) } },
                label = { Text("MODEL FAMILY", style = type.labelTelemetry) },
                textStyle = type.monoCode,
                singleLine = true,
                shape = shapes.sm,
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("THOUGHT LEVEL", style = type.labelTelemetry, color = colors.inkSecondary)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                listOf("low", "medium", "high", "xhigh").forEach { lvl ->
                    val selected = form.thoughtLevel == lvl
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = spacing.touchFloor)
                            .clip(shapes.sm)
                            .background(if (selected) colors.signalCyanDim else colors.stratumRaised)
                            .border(
                                spacing.hairlineWidth,
                                if (selected) colors.signalCyan else colors.hairline,
                                shapes.sm,
                            )
                            .clickable { onUpdateForm { it.copy(thoughtLevel = lvl) } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = lvl.uppercase(),
                            style = type.labelTelemetry,
                            color = if (selected) colors.signalCyan else colors.inkSecondary,
                        )
                    }
                }
            }

            Text("EXECUTION MODE", style = type.labelTelemetry, color = colors.inkSecondary)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                listOf("default", "accept-edits", "auto", "plan").forEach { m ->
                    val selected = form.mode == m
                    val isRisky = m == "auto"
                    Box(
                        modifier = Modifier
                            .defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor)
                            .clip(shapes.sm)
                            .background(
                                when {
                                    selected && isRisky -> colors.signalAmberDim
                                    selected -> colors.signalCyanDim
                                    else -> colors.stratumRaised
                                },
                            )
                            .border(
                                spacing.hairlineWidth,
                                when {
                                    selected && isRisky -> colors.signalAmber
                                    selected -> colors.signalCyan
                                    else -> colors.hairline
                                },
                                shapes.sm,
                            )
                            .clickable { onUpdateForm { it.copy(mode = m) } }
                            .padding(horizontal = spacing.md),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = m,
                            style = type.monoMicro,
                            color = when {
                                selected && isRisky -> colors.signalAmber
                                selected -> colors.signalCyan
                                else -> colors.inkSecondary
                            },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = form.agent,
                onValueChange = { v -> onUpdateForm { it.copy(agent = v) } },
                label = { Text("CUSTOM AGENT SLUG (OPTIONAL)", style = type.labelTelemetry) },
                textStyle = type.monoCode,
                singleLine = true,
                shape = shapes.sm,
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shapes.sm)
                    .background(colors.stratumRaised)
                    .border(spacing.hairlineWidth, colors.hairline, shapes.sm)
                    .padding(horizontal = spacing.md, vertical = spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("SANDBOX CONTAINMENT", style = type.labelTelemetry, color = colors.inkPrimary)
                    Text("Pass --sandbox at agy acp spawn", style = type.monoMicro, color = colors.inkSecondary)
                }
                Switch(
                    checked = form.sandbox,
                    onCheckedChange = { checked -> onUpdateForm { it.copy(sandbox = checked) } },
                )
            }

            if (form.error != null) {
                Text(form.error, style = type.monoMicro, color = colors.signalCrimson)
            }

            Button(
                onClick = onSubmit,
                enabled = !form.isSubmitting && form.cwd.isNotBlank(),
                shape = shapes.sm,
                elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.signalCyan,
                    contentColor = colors.inkInverse,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = spacing.touchFloor),
            ) {
                Text(
                    text = if (form.isSubmitting) "SPAWNING ACP..." else "Create & Attach Session",
                    style = type.labelTelemetry,
                )
            }
            Spacer(modifier = Modifier.height(spacing.md))
        }
    }
}

internal fun compactTimestamp(iso: String): String {
    if (iso.isBlank()) return "--:--"
    val tIdx = iso.indexOf('T')
    if (tIdx >= 0 && iso.length >= tIdx + 6) {
        return iso.substring(tIdx + 1, tIdx + 6)
    }
    return iso.take(10)
}
