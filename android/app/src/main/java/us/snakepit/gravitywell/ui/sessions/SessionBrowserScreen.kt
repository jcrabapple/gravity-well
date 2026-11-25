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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import us.snakepit.gravitywell.theme.GravityWellTheme
import us.snakepit.gravitywell.ui.console.formatModelDisplayName

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
    onTogglePin: (SessionBrowserItem) -> Unit = {},
    onToggleArchive: (SessionBrowserItem) -> Unit = {},
    onRequestDelete: (SessionBrowserItem) -> Unit = {},
    onDismissDelete: () -> Unit = {},
    onConfirmDelete: () -> Unit = {},
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.chassisVoid),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Search + Filter Pills ────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.chassisVoid)
                    .padding(horizontal = spacing.lg, vertical = spacing.xs),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    OutlinedTextField(
                        value = state.searchQuery,
                        onValueChange = onSearchQueryChange,
                        singleLine = true,
                        textStyle = type.bodyProse,
                        placeholder = {
                            Text(
                                text = "Search chats...",
                                style = type.bodyProse,
                                color = colors.inkMuted,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Search,
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
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Clear search query",
                                        tint = colors.inkSecondary,
                                    )
                                }
                            }
                        },
                        shape = shapes.pill,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = colors.stratumBase,
                            unfocusedContainerColor = colors.stratumBase,
                            focusedBorderColor = colors.signalCyan,
                            unfocusedBorderColor = colors.hairline,
                            focusedTextColor = colors.inkPrimary,
                            unfocusedTextColor = colors.inkPrimary,
                            cursorColor = colors.signalCyan,
                        ),
                        modifier = Modifier.weight(1f),
                    )

                    IconButton(
                        onClick = onRefresh,
                        modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Refresh chats",
                            tint = colors.inkSecondary,
                        )
                    }
                }

                // Scope pills (All / Active / Archived)
                ScopeSegmentedStrip(
                    selected = state.scope,
                    onSelect = onScopeChange,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Workspace Filter Chips (shown when multiple workspaces exist or one is selected)
                if (state.availableWorkspaces.size > 1 || state.selectedWorkspaceCwd != null) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                    ) {
                        item(key = "__all_ws__") {
                            WorkspaceFilterChip(
                                label = "All projects",
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

            Spacer(modifier = Modifier.height(spacing.xs))

            // ── Active External Host Sessions Banner (FLOCK-verified, D10) ───
            if (state.externalSessions.isNotEmpty()) {
                ExternalSessionRadarStrip(
                    externalSessions = state.externalSessions,
                    expanded = state.radarExpanded,
                    onToggle = onToggleRadar,
                    onResumeExternal = onResumeExternalSession,
                    modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.xs),
                )
            }

            // ── Inline Error / Notice Banner ─────────────────────────────────
            val bannerText = state.errorBanner ?: state.infoNotice
            if (bannerText != null) {
                val isError = state.errorBanner != null
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.lg, vertical = spacing.xs)
                        .clip(shapes.sm)
                        .background(if (isError) colors.signalCrimsonDim else colors.signalAmberDim)
                        .border(
                            spacing.hairlineWidth,
                            if (isError) colors.signalCrimson else colors.signalAmber,
                            shapes.sm,
                        )
                        .padding(start = spacing.md, end = spacing.xs, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = bannerText,
                        style = type.bodyCompact,
                        color = if (isError) colors.signalCrimson else colors.signalAmber,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = onDismissNotices,
                        modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Dismiss notice",
                            tint = if (isError) colors.signalCrimson else colors.signalAmber,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            // ── Chat List ────────────────────────────────────────────────────
            if (state.sessions.isEmpty() && !state.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(spacing.xxl),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(colors.stratumBase),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Forum,
                                contentDescription = null,
                                tint = colors.inkMuted,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                        Text(
                            text = "No chats yet",
                            style = type.titleBar,
                            color = colors.inkPrimary,
                        )
                        Text(
                            text = "Start a new conversation or broaden your search filter.",
                            style = type.bodyCompact,
                            color = colors.inkSecondary,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = spacing.md,
                        end = spacing.md,
                        top = spacing.xs,
                        bottom = 92.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
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
                            onTogglePin = { onTogglePin(item) },
                            onRename = { onRenameRequest(item) },
                            onToggleArchive = { onToggleArchive(item) },
                            onClose = { onCloseSession(item.sessionId) },
                            onDelete = { onRequestDelete(item) },
                            onFork = { onForkSession(item) },
                        )
                    }
                }
            }
        }

        // ── Floating New Chat Pill Button ────────────────────────────────────
        Button(
            onClick = onOpenNewSession,
            shape = shapes.pill,
            elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.signalCyan,
                contentColor = colors.inkInverse,
            ),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(spacing.lg)
                .defaultMinSize(minWidth = spacing.touchFloor, minHeight = 52.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("New chat", style = type.labelUi)
        }
    }

    // ── Resume Archived Chat Sheet ───────────────────────────────────────────
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

    // ── Rename Chat Dialog ───────────────────────────────────────────────────
    val renameTarget = state.renameTarget
    if (renameTarget != null) {
        AlertDialog(
            onDismissRequest = onDismissRename,
            containerColor = colors.stratumBase,
            tonalElevation = spacing.elevationZero,
            shape = shapes.lg,
            title = {
                Text("Rename chat", style = type.titleBar, color = colors.inkPrimary)
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
                        focusedBorderColor = colors.signalCyan,
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
                    Text("Save", style = type.labelUi, color = colors.signalCyan)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onDismissRename,
                    modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                ) {
                    Text("Cancel", style = type.labelUi, color = colors.inkSecondary)
                }
            },
        )
    }

    // ── Delete Chat Confirmation Dialog ──────────────────────────────────────
    val deleteTarget = state.deleteTarget
    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = onDismissDelete,
            containerColor = colors.stratumBase,
            tonalElevation = spacing.elevationZero,
            shape = shapes.lg,
            title = {
                Text("Delete chat?", style = type.titleBar, color = colors.inkPrimary)
            },
            text = {
                Text(
                    text = "Permanently remove \"${deleteTarget.title}\" from Gravity Well? This cannot be undone.",
                    style = type.bodyCompact,
                    color = colors.inkSecondary,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !state.isDeleting,
                    onClick = onConfirmDelete,
                    modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                ) {
                    Text(
                        text = if (state.isDeleting) "Deleting..." else "Delete",
                        style = type.labelUi,
                        color = colors.signalCrimson,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !state.isDeleting,
                    onClick = onDismissDelete,
                    modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                ) {
                    Text("Cancel", style = type.labelUi, color = colors.inkSecondary)
                }
            },
        )
    }

    // ── New Chat Sheet ───────────────────────────────────────────────────────
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
            .clip(shapes.pill)
            .background(colors.stratumBase)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        SessionScopeFilter.entries.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minWidth = spacing.touchFloor, minHeight = 40.dp)
                    .clip(shapes.pill)
                    .background(if (isSelected) colors.signalCyanDim else colors.stratumBase)
                    .clickable(role = Role.Tab) { onSelect(option) }
                    .padding(horizontal = spacing.sm),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option.label,
                    style = type.labelUi,
                    color = if (isSelected) colors.signalCyan else colors.inkSecondary,
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
            .defaultMinSize(minWidth = spacing.touchFloor, minHeight = 36.dp)
            .clip(shapes.pill)
            .background(if (selected) colors.signalCyanDim else colors.stratumBase)
            .border(
                width = spacing.hairlineWidth,
                color = if (selected) colors.signalCyan else colors.hairline,
                shape = shapes.pill,
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = type.labelSmallUi,
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
    modifier: Modifier = Modifier,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    val hasUnsupervised = externalSessions.any { !it.supervised }
    val accent = if (hasUnsupervised) colors.signalAmber else colors.signalCyan
    val bgTint = if (hasUnsupervised) colors.signalAmberDim else colors.signalCyanDim
    val noun = if (externalSessions.size == 1) "session" else "sessions"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shapes.md)
            .background(bgTint)
            .border(spacing.hairlineWidth, accent, shapes.md),
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
                text = "${externalSessions.size} active host $noun",
                style = type.labelUi,
                color = accent,
            )
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = if (expanded) "Collapse active host sessions" else "Expand active host sessions",
                tint = accent,
            )
        }

        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = spacing.md, end = spacing.md, bottom = spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                externalSessions.forEach { ext ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(shapes.sm)
                            .background(colors.stratumBase)
                            .padding(horizontal = spacing.md, vertical = spacing.sm),
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
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(shapes.pill)
                                        .background(if (ext.supervised) colors.signalCyanDim else colors.signalAmberDim)
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                ) {
                                    Text(
                                        text = if (ext.supervised) "Connected" else "External",
                                        style = type.labelSmallUi,
                                        color = if (ext.supervised) colors.signalCyan else colors.signalAmber,
                                    )
                                }
                            }
                            Text(
                                text = ext.cwd?.let { basenameOfPath(it) } ?: "Unknown folder",
                                style = type.labelSmallUi,
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
                                Text("Resume", style = type.labelUi, color = colors.signalCyan)
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
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onToggleArchive: () -> Unit,
    onClose: () -> Unit,
    onDelete: () -> Unit,
    onFork: () -> Unit,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    val isPulsing = item.status == "running" || item.status == "starting"
    val pulseAlpha = if (isPulsing) {
        val transition = rememberInfiniteTransition(label = "session_dot_pulse")
        val anim by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.3f,
            animationSpec = infiniteRepeatable(tween(750), RepeatMode.Reverse),
            label = "dot_alpha",
        )
        anim
    } else {
        1f
    }

    val dotColor = when (item.status) {
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
            .clip(shapes.md)
            .background(if (isSelected) colors.stratumRaised else colors.stratumBase)
            .border(
                width = spacing.hairlineWidth,
                color = when {
                    isSelected -> colors.signalCyan
                    item.isPinned -> colors.signalCyan.copy(alpha = 0.45f)
                    else -> colors.hairline
                },
                shape = shapes.md,
            )
            .clickable(onClick = onClick)
            .padding(start = spacing.lg, end = spacing.xs, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 7.dp)
                .size(9.dp)
                .alpha(pulseAlpha)
                .clip(CircleShape)
                .background(dotColor),
        )

        Spacer(modifier = Modifier.width(spacing.md))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Line 1: Pin badge + Title + Relative Timestamp
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    if (item.isPinned) {
                        Icon(
                            imageVector = Icons.Rounded.PushPin,
                            contentDescription = "Pinned chat",
                            tint = colors.signalCyan,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    Text(
                        text = item.title,
                        style = type.titleBar,
                        color = colors.inkPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Spacer(modifier = Modifier.width(spacing.sm))
                Text(
                    text = compactTimestamp(item.updatedAt),
                    style = type.labelSmallUi,
                    color = colors.inkMuted,
                )
            }

            // Line 2: Preview snippet (if present)
            if (item.preview.isNotBlank()) {
                Text(
                    text = item.preview,
                    style = type.bodyCompact,
                    color = colors.inkSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Line 3: Compact metadata badges (project folder, status pill, model)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 2.dp),
            ) {
                Row(
                    modifier = Modifier
                        .clip(shapes.pill)
                        .background(colors.stratumRaised)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FolderOpen,
                        contentDescription = null,
                        tint = colors.inkMuted,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        text = item.cwdBasename,
                        style = type.labelSmallUi,
                        color = colors.inkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (item.hasPendingGate) {
                    Box(
                        modifier = Modifier
                            .clip(shapes.pill)
                            .background(colors.signalAmberDim)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text("Needs approval", style = type.labelSmallUi, color = colors.signalAmber)
                    }
                } else if (item.isLive && !item.modelId.isNullOrBlank()) {
                    Text(
                        text = formatModelDisplayName(item.modelId),
                        style = type.labelSmallUi,
                        color = colors.inkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Box {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
            ) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = "Chat actions for ${item.title}",
                    tint = colors.inkMuted,
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
                    text = {
                        Text(
                            text = if (item.isPinned) "Unpin" else "Pin to top",
                            style = type.bodyCompact,
                            color = colors.inkPrimary,
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onTogglePin()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Rename", style = type.bodyCompact, color = colors.inkPrimary) },
                    onClick = {
                        menuExpanded = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (item.isArchived) "Unarchive" else "Archive",
                            style = type.bodyCompact,
                            color = colors.inkPrimary,
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onToggleArchive()
                    },
                )
                if (item.isLive) {
                    DropdownMenuItem(
                        text = { Text("Close session", style = type.bodyCompact, color = colors.inkSecondary) },
                        onClick = {
                            menuExpanded = false
                            onClose()
                        },
                    )
                }
                HorizontalDivider(thickness = spacing.hairlineWidth, color = colors.hairline)
                DropdownMenuItem(
                    text = { Text("Delete", style = type.bodyCompact, color = colors.signalCrimson) },
                    onClick = {
                        menuExpanded = false
                        onDelete()
                    },
                )
                DropdownMenuItem(
                    enabled = forkSupported,
                    text = {
                        Column {
                            Text(
                                text = "Fork chat",
                                style = type.bodyCompact,
                                color = if (forkSupported) colors.inkPrimary else colors.inkMuted,
                            )
                            if (!forkSupported) {
                                Text(
                                    text = "Not supported on agy $agyVersion",
                                    style = type.labelSmallUi,
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
        shape = shapes.sheetTop,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.xl, vertical = spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Text("Continue chat", style = type.headlineSection, color = colors.inkPrimary)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(target.title, style = type.titleBar, color = colors.inkPrimary)
                Text(target.cwd, style = type.monoMicro, color = colors.inkSecondary)
            }

            Button(
                onClick = onResumeWithHistory,
                enabled = !isResuming,
                shape = shapes.pill,
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
                    text = if (isResuming) "Resuming..." else "Resume with full history",
                    style = type.labelUi,
                )
            }

            OutlinedButton(
                onClick = onResumeFast,
                enabled = !isResuming,
                shape = shapes.pill,
                border = BorderStroke(spacing.hairlineWidth, colors.hairlineStrong),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = colors.stratumRaised,
                    contentColor = colors.inkPrimary,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = spacing.touchFloor),
            ) {
                Text("Quick resume (skip history replay)", style = type.labelUi)
            }
            Spacer(modifier = Modifier.height(spacing.lg))
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

    var showAdvanced by remember { mutableStateOf(false) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = colors.stratumWell,
        unfocusedContainerColor = colors.stratumWell,
        focusedBorderColor = colors.signalCyan,
        unfocusedBorderColor = colors.hairline,
        focusedTextColor = colors.inkPrimary,
        unfocusedTextColor = colors.inkPrimary,
    )

    val modelPresets = remember(form.model) {
        buildList {
            addAll(listOf("gemini-4-neon", "gemini-3.8-flash", "claude-sonnet-5-5"))
            if (form.model.isNotBlank()) add(form.model)
        }.distinct()
    }

    val thoughtOptions = listOf(
        "low" to "Low",
        "medium" to "Medium",
        "high" to "High",
        "xhigh" to "Max",
    )

    val modeOptions = listOf(
        "default" to "Ask first",
        "accept-edits" to "Accept edits",
        "auto" to "Auto-run",
        "plan" to "Plan only",
    )

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
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("New chat", style = type.headlineSection, color = colors.inkPrimary)
                Text(
                    text = "Pick a workspace and model to start chatting with Antigravity.",
                    style = type.bodyCompact,
                    color = colors.inkSecondary,
                )
            }

            // 1. Workspace Picker
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                Text("Project folder", style = type.labelUi, color = colors.inkSecondary)
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
                                onClick = { onUpdateForm { it.copy(cwd = ws, error = null) } },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = form.cwd,
                    onValueChange = { v -> onUpdateForm { it.copy(cwd = v, error = null) } },
                    placeholder = { Text("/home/user/projects/my-app", style = type.monoCode, color = colors.inkMuted) },
                    textStyle = type.monoCode,
                    singleLine = true,
                    shape = shapes.sm,
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // 2. Model Selector Chips
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                Text("Model", style = type.labelUi, color = colors.inkSecondary)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    modelPresets.forEach { m ->
                        val selected = form.model == m
                        Box(
                            modifier = Modifier
                                .defaultMinSize(minHeight = 40.dp)
                                .clip(shapes.pill)
                                .background(if (selected) colors.signalCyanDim else colors.stratumRaised)
                                .border(
                                    spacing.hairlineWidth,
                                    if (selected) colors.signalCyan else colors.hairline,
                                    shapes.pill,
                                )
                                .clickable { onUpdateForm { it.copy(model = m) } }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = formatModelDisplayName(m),
                                style = type.labelSmallUi,
                                color = if (selected) colors.signalCyan else colors.inkPrimary,
                            )
                        }
                    }
                }
            }

            // 3. Thinking Effort
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                Text("Thinking effort", style = type.labelUi, color = colors.inkSecondary)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shapes.pill)
                        .background(colors.stratumRaised)
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    thoughtOptions.forEach { (lvl, label) ->
                        val selected = form.thoughtLevel == lvl
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 40.dp)
                                .clip(shapes.pill)
                                .background(if (selected) colors.signalCyanDim else colors.stratumRaised)
                                .clickable { onUpdateForm { it.copy(thoughtLevel = lvl) } },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = label,
                                style = type.labelSmallUi,
                                color = if (selected) colors.signalCyan else colors.inkSecondary,
                            )
                        }
                    }
                }
            }

            // 4. Tool Approval Mode
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                Text("Tool approvals", style = type.labelUi, color = colors.inkSecondary)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    modeOptions.forEach { (m, label) ->
                        val selected = form.mode == m
                        val isRisky = m == "auto"
                        Box(
                            modifier = Modifier
                                .defaultMinSize(minWidth = spacing.touchFloor, minHeight = 40.dp)
                                .clip(shapes.pill)
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
                                    shapes.pill,
                                )
                                .clickable { onUpdateForm { it.copy(mode = m) } }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = label,
                                style = type.labelSmallUi,
                                color = when {
                                    selected && isRisky -> colors.signalAmber
                                    selected -> colors.signalCyan
                                    else -> colors.inkSecondary
                                },
                            )
                        }
                    }
                }
            }

            // 5. Collapsible Advanced Options (all features preserved!)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = spacing.touchFloor)
                    .clip(shapes.sm)
                    .background(colors.stratumRaised)
                    .clickable { showAdvanced = !showAdvanced }
                    .padding(horizontal = spacing.md, vertical = spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Tune,
                        contentDescription = null,
                        tint = colors.inkSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text("Advanced options", style = type.labelUi, color = colors.inkPrimary)
                }
                Icon(
                    imageVector = if (showAdvanced) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (showAdvanced) "Hide advanced options" else "Show advanced options",
                    tint = colors.inkMuted,
                )
            }

            if (showAdvanced) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(spacing.md),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedTextField(
                        value = form.title,
                        onValueChange = { v -> onUpdateForm { it.copy(title = v) } },
                        label = { Text("Custom title (optional)", style = type.labelSmallUi) },
                        textStyle = type.bodyProse,
                        singleLine = true,
                        shape = shapes.sm,
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = form.model,
                        onValueChange = { v -> onUpdateForm { it.copy(model = v) } },
                        label = { Text("Model ID override", style = type.labelSmallUi) },
                        textStyle = type.monoCode,
                        singleLine = true,
                        shape = shapes.sm,
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (spawnAddDirSupported) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                        ) {
                            OutlinedTextField(
                                value = form.addDirDraft,
                                onValueChange = { v -> onUpdateForm { it.copy(addDirDraft = v) } },
                                label = { Text("Extra workspace directory", style = type.labelSmallUi) },
                                textStyle = type.monoCode,
                                singleLine = true,
                                shape = shapes.sm,
                                colors = fieldColors,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedButton(
                                onClick = onAddExtraDir,
                                shape = shapes.pill,
                                border = BorderStroke(spacing.hairlineWidth, colors.hairlineStrong),
                                modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                            ) {
                                Text("Add", style = type.labelUi, color = colors.signalCyan)
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
                                            .clip(shapes.pill)
                                            .background(colors.stratumRaised)
                                            .border(spacing.hairlineWidth, colors.hairline, shapes.pill)
                                            .defaultMinSize(minHeight = 36.dp)
                                            .clickable { onRemoveExtraDir(dir) }
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(dir, style = type.monoMicro, color = colors.inkPrimary)
                                        Icon(
                                            imageVector = Icons.Rounded.Close,
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
                        value = form.agent,
                        onValueChange = { v -> onUpdateForm { it.copy(agent = v) } },
                        label = { Text("Custom agent slug (optional)", style = type.labelSmallUi) },
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
                            .padding(horizontal = spacing.md, vertical = spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Sandbox mode", style = type.labelUi, color = colors.inkPrimary)
                            Text("Isolate command execution (--sandbox)", style = type.bodyCompact, color = colors.inkSecondary)
                        }
                        Switch(
                            checked = form.sandbox,
                            onCheckedChange = { checked -> onUpdateForm { it.copy(sandbox = checked) } },
                        )
                    }
                }
            }

            if (form.error != null) {
                Text(form.error, style = type.bodyCompact, color = colors.signalCrimson)
            }

            Button(
                onClick = onSubmit,
                enabled = !form.isSubmitting && form.cwd.isNotBlank(),
                shape = shapes.pill,
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
                    text = if (form.isSubmitting) "Starting chat..." else "Start chat",
                    style = type.labelUi,
                )
            }
            Spacer(modifier = Modifier.height(spacing.lg))
        }
    }
}

private val shortDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d", Locale.US).withZone(ZoneId.systemDefault())

internal fun compactTimestamp(iso: String): String {
    if (iso.isBlank()) return ""
    val parsed = runCatching { Instant.parse(iso) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull()
    if (parsed != null) {
        val elapsed = Duration.between(parsed, Instant.now())
        val mins = elapsed.toMinutes()
        return when {
            mins < 1 -> "Now"
            mins < 60 -> "${mins}m"
            mins < 24 * 60 -> "${elapsed.toHours()}h"
            mins < 7 * 24 * 60 -> "${elapsed.toDays()}d"
            else -> shortDateFormatter.format(parsed)
        }
    }
    val tIdx = iso.indexOf('T')
    if (tIdx >= 0 && iso.length >= tIdx + 6) {
        return iso.substring(tIdx + 1, tIdx + 6)
    }
    return iso.take(10)
}
