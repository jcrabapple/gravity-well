package us.snakepit.gravitywell.ui.console

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import us.snakepit.gravitywell.theme.GravityWellTheme
import us.snakepit.gravitywell.ui.sessions.SessionBrowserItem
import us.snakepit.gravitywell.ui.sessions.WorkspaceFileItemDto

@Composable
fun SessionConsoleScreen(
    state: SessionConsoleUiState,
    showBackButton: Boolean,
    onBack: () -> Unit,
    onRenameTitleClick: () -> Unit,
    onUpdateRenameDraft: (String) -> Unit,
    onDismissRename: () -> Unit,
    onSubmitRename: () -> Unit,
    onCancelTurn: () -> Unit,
    onOpenConfigSheet: () -> Unit,
    onDismissConfigSheet: () -> Unit,
    onUpdateLiveConfig: (model: String?, thoughtLevel: String?, mode: String?) -> Unit,
    onOpenArtifacts: () -> Unit,
    onDismissArtifacts: () -> Unit,
    onSelectArtifact: (us.snakepit.gravitywell.ui.sessions.ArtifactItemDto) -> Unit,
    onBackToArtifactList: () -> Unit,
    onOpenWorkspaceRules: () -> Unit,
    onDismissWorkspaceRules: () -> Unit,
    onRevokeWorkspaceRule: (String) -> Unit,
    onCloseSession: () -> Unit,
    onToggleThought: (LedgerItem.ThoughtBlock) -> Unit,
    onToggleToolGutter: (String) -> Unit,
    onExpandSwarmTool: (String) -> Unit,
    onSetToolDiffMode: (itemId: String, mode: DiffDisplayMode) -> Unit,
    onResolvePermission: (requestId: String, decision: String, optionId: String?) -> Unit,
    onUpdateQuestionAnswer: (requestId: String, propKey: String, value: String) -> Unit,
    onResolveQuestion: (requestId: String, action: String) -> Unit,
    onResumeQueue: () -> Unit,
    onStartEditQueueItem: (us.snakepit.gravitywell.net.QueueItemDto) -> Unit,
    onUpdateEditQueueText: (String) -> Unit,
    onSaveEditQueueItem: () -> Unit,
    onCancelEditQueueItem: () -> Unit,
    onDeleteQueueItem: (String) -> Unit,
    onSendQueueItemNow: (String) -> Unit,
    onDraftChange: (text: String, cursor: Int) -> Unit,
    onAttachBytes: (filename: String, declaredMime: String?, bytes: ByteArray) -> Unit,
    onRejectOversizedAttachment: (filename: String, sizeBytes: Long) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onToggleSlashBar: () -> Unit,
    onSelectSlashCommand: (String) -> Unit,
    onSelectAtFile: (WorkspaceFileItemDto) -> Unit,
    onSubmitPrompt: () -> Unit,
    onDismissErrorBanner: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing
    val context = LocalContext.current
    val uiScope = rememberCoroutineScope()

    val listState = rememberLazyListState()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        uiScope.launch {
            readPickedUriBounded(
                context = context,
                uri = uri,
                onValidRead = onAttachBytes,
                onOversized = onRejectOversizedAttachment,
            )
        }
    }

    val slashCommands = remember(
        state.draftText,
        state.forceShowSlashChips,
        state.session.availableCommands,
    ) {
        ComposerAutocompleteHelper.filterSlashCommands(
            text = state.draftText,
            forceShowSlashBar = state.forceShowSlashChips,
            available = state.session.availableCommands,
        )
    }

    LaunchedEffect(state.session.ledger.size) {
        if (state.session.ledger.isNotEmpty()) {
            listState.animateScrollToItem(state.session.ledger.lastIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.chassisVoid),
    ) {
        // 1. 52dp Top Bar
        ConsoleTopBar(
            title = state.session.title,
            titleSource = state.session.titleSource,
            status = state.session.status,
            isTurnActive = state.session.isTurnActive,
            showBackButton = showBackButton,
            onBack = onBack,
            onRenameTitle = onRenameTitleClick,
            onCancelTurn = onCancelTurn,
            onOpenArtifacts = onOpenArtifacts,
            onOpenWorkspaceRules = onOpenWorkspaceRules,
            onCloseSession = onCloseSession,
        )
        HorizontalDivider(thickness = spacing.hairlineWidth, color = colors.hairline)

        // 2. 28dp Telemetry Sub-Header
        TelemetrySubHeader(
            modelId = state.session.modelId,
            thoughtLevel = state.session.thoughtLevel,
            modeId = state.session.modeId,
            usage = state.session.usage,
            streamIndicator = state.streamIndicator,
            lastCursor = state.session.lastCursor,
            onOpenConfigSheet = onOpenConfigSheet,
        )
        HorizontalDivider(thickness = spacing.hairlineWidth, color = colors.hairline)

        // 3. Pinned 36dp Subagent / Swarm Strip (when child worker calls exist)
        if (state.session.swarmWorkers.isNotEmpty()) {
            SwarmProgressStrip(
                workers = state.session.swarmWorkers,
                onSelectWorker = { worker ->
                    onExpandSwarmTool(worker.ledgerItemId)
                    val idx = state.session.ledger.indexOfFirst { it.id == worker.ledgerItemId }
                    if (idx >= 0) {
                        uiScope.launch { listState.animateScrollToItem(idx) }
                    }
                },
            )
            HorizontalDivider(thickness = spacing.hairlineWidth, color = colors.hairline)
        }

        // Optional inline error banner
        if (state.errorBanner != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.signalCrimsonDim)
                    .padding(horizontal = spacing.md, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = state.errorBanner,
                    style = type.monoMicro,
                    color = colors.signalCrimson,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onDismissErrorBanner,
                    modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss console error",
                        tint = colors.signalCrimson,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            HorizontalDivider(thickness = spacing.hairlineWidth, color = colors.hairline)
        }

        // 4. Turn Stream Ledger (0dp horizontal margins, full-bleed strata)
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (state.session.ledger.isEmpty()) {
                item(key = "__empty_ledger__") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(spacing.xl),
                        verticalArrangement = Arrangement.spacedBy(spacing.xs),
                    ) {
                        Text(
                            text = "OSCILLOSCOPE READY · ${state.session.cwd.ifBlank { "WORKSPACE" }}",
                            style = type.labelTelemetry,
                            color = colors.inkMuted,
                        )
                        Text(
                            text = "Submit a prompt, type @ to attach workspace files, or / for ACP commands.",
                            style = type.bodyCompact,
                            color = colors.inkSecondary,
                        )
                    }
                }
            } else {
                items(
                    items = state.session.ledger,
                    key = { it.id },
                ) { entry ->
                    when (entry) {
                        is LedgerItem.UserTurn -> UserTurnStratum(entry)
                        is LedgerItem.AgentProse -> AgentProseStratum(entry)
                        is LedgerItem.ThoughtBlock -> {
                            val isExpanded = entry.id in state.expandedThoughtIds
                            ThoughtStratum(
                                item = entry,
                                expanded = isExpanded,
                                onToggle = { onToggleThought(entry) },
                            )
                        }
                        is LedgerItem.ToolCallBlock -> {
                            ToolGutterStratum(
                                item = entry,
                                expanded = entry.id in state.expandedToolIds,
                                diffMode = state.toolDiffModes[entry.id] ?: DiffDisplayMode.UNIFIED,
                                onToggleExpanded = { onToggleToolGutter(entry.id) },
                                onSelectDiffMode = { mode -> onSetToolDiffMode(entry.id, mode) },
                            )
                        }
                        is LedgerItem.SystemNotice -> SystemNoticeStratum(entry)
                    }
                    HorizontalDivider(thickness = spacing.hairlineWidth, color = colors.hairline)
                }
            }
        }

        // 5. Docked Permission & Question Interlocks (never center-blocking popups)
        state.session.pendingPermissions.firstOrNull()?.let { perm ->
            DockedPermissionGateBar(
                permission = perm,
                onResolve = { decision, optionId ->
                    onResolvePermission(perm.requestId, decision, optionId)
                },
            )
        }

        state.session.pendingQuestions.firstOrNull()?.let { qst ->
            DockedQuestionBrokerBar(
                question = qst,
                draftAnswers = state.questionAnswersDraft[qst.requestId].orEmpty(),
                onUpdateAnswer = { key, value ->
                    onUpdateQuestionAnswer(qst.requestId, key, value)
                },
                onResolve = { action ->
                    onResolveQuestion(qst.requestId, action)
                },
            )
        }

        // 6. Queued Follow-Up Tray
        if (state.session.queuedFollowups.isNotEmpty() || state.session.queuePaused) {
            QueuedFollowupTray(
                items = state.session.queuedFollowups,
                queuePaused = state.session.queuePaused,
                editingItemId = state.editingQueueItemId,
                editingText = state.editingQueueText,
                onResumeQueue = onResumeQueue,
                onStartEdit = onStartEditQueueItem,
                onUpdateEditText = onUpdateEditQueueText,
                onSaveEdit = onSaveEditQueueItem,
                onCancelEdit = onCancelEditQueueItem,
                onDelete = onDeleteQueueItem,
                onSendNow = onSendQueueItemNow,
            )
        }

        // 7. Thumb-Zone Composer Dock
        ThumbZoneComposerDock(
            draftText = state.draftText,
            draftCursorIndex = state.draftCursorIndex,
            isTurnActive = state.session.isTurnActive,
            isSubmitting = state.isSubmittingPrompt,
            attachments = state.draftAttachments,
            attachmentThumbnails = state.attachmentThumbnails,
            atFileCandidates = state.atFileCandidates,
            slashCommands = slashCommands,
            onDraftChange = onDraftChange,
            onPickAttachment = { filePickerLauncher.launch(arrayOf("*/*")) },
            onRemoveAttachment = onRemoveAttachment,
            onToggleSlashBar = onToggleSlashBar,
            onSelectSlashCommand = onSelectSlashCommand,
            onSelectAtFile = onSelectAtFile,
            onSubmit = onSubmitPrompt,
        )
    }

    // ── Sheets & Rename Dialog ───────────────────────────────────────────────

    if (state.showRenameDialog) {
        AlertDialog(
            onDismissRequest = onDismissRename,
            containerColor = colors.stratumBase,
            tonalElevation = spacing.elevationZero,
            shape = shapes.md,
            title = {
                Text("RENAME SESSION", style = type.labelTelemetry, color = colors.signalViolet)
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

    if (state.showConfigSheet) {
        SessionConfigSheet(
            session = state.session,
            onUpdateConfig = onUpdateLiveConfig,
            onDismiss = onDismissConfigSheet,
        )
    }

    if (state.showArtifactsSheet) {
        BrainArtifactViewerSheet(
            artifacts = state.artifacts,
            isLoading = state.isLoadingArtifacts,
            selectedArtifact = state.selectedArtifact,
            onSelectArtifact = onSelectArtifact,
            onBackToList = onBackToArtifactList,
            onDismiss = onDismissArtifacts,
        )
    }

    if (state.showWorkspaceRulesSheet) {
        WorkspaceRulesSheet(
            rules = state.workspaceRules,
            onRevokeRule = onRevokeWorkspaceRule,
            onDismiss = onDismissWorkspaceRules,
        )
    }
}

/**
 * Quick-attach empty state shown in the Console lane when no session_id is attached yet
 * (DIRECTION-gravity-well.md Section 3).
 */
@Composable
fun ConsoleQuickAttachBay(
    liveSessions: List<SessionBrowserItem>,
    onAttachSession: (String) -> Unit,
    onCreateNewSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.chassisVoid)
            .padding(spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Icon(
                imageVector = Icons.Default.Terminal,
                contentDescription = null,
                tint = colors.signalCyan,
            )
            Text("CONSOLE BAY · QUICK ATTACH", style = type.labelTelemetry, color = colors.signalCyan)
        }

        Text(
            text = if (liveSessions.isEmpty()) {
                "No live ACP process attached"
            } else {
                "Select a live ACP session to attach"
            },
            style = type.headlineSection,
            color = colors.inkPrimary,
        )

        OutlinedButton(
            onClick = onCreateNewSession,
            shape = shapes.sm,
            border = BorderStroke(spacing.hairlineWidth, colors.signalCyan),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = colors.signalCyanDim,
                contentColor = colors.signalCyan,
            ),
            modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(spacing.xs))
            Text("New Session", style = type.labelTelemetry)
        }

        if (liveSessions.isNotEmpty()) {
            HorizontalDivider(thickness = spacing.hairlineWidth, color = colors.hairline)
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                items(liveSessions, key = { it.sessionId }) { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = spacing.touchFloor)
                            .clip(shapes.sm)
                            .background(colors.stratumBase)
                            .border(spacing.hairlineWidth, colors.hairline, shapes.sm)
                            .clickable { onAttachSession(item.sessionId) }
                            .padding(horizontal = spacing.md, vertical = spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.title,
                                style = type.titleBar,
                                color = colors.inkPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = item.cwd,
                                style = type.monoMicro,
                                color = colors.inkSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = item.status.uppercase(),
                            style = type.labelTelemetry,
                            color = if (item.hasPendingGate) colors.signalAmber else colors.signalEmerald,
                        )
                    }
                }
            }
        }
    }
}

private suspend fun readPickedUriBounded(
    context: Context,
    uri: Uri,
    onValidRead: (filename: String, declaredMime: String?, bytes: ByteArray) -> Unit,
    onOversized: (filename: String, sizeBytes: Long) -> Unit,
) {
    val resolver = context.contentResolver
    val (name, preSize, mime) = withContext(Dispatchers.IO) {
        var displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0 && !c.isNull(nameIdx)) {
                        displayName = c.getString(nameIdx)
                    }
                    val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0 && !c.isNull(sizeIdx)) {
                        size = c.getLong(sizeIdx)
                    }
                }
            }
        Triple(displayName, size, resolver.getType(uri))
    }

    if (preSize > AttachmentSniffer.MAX_ATTACHMENT_BYTES) {
        onOversized(name, preSize)
        return
    }

    val readResult: ByteArray? = withContext(Dispatchers.IO) {
        resolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16_384)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > AttachmentSniffer.MAX_ATTACHMENT_BYTES) {
                    return@withContext null
                }
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    }

    if (readResult == null) {
        onOversized(name, AttachmentSniffer.MAX_ATTACHMENT_BYTES + 1L)
    } else {
        onValidRead(name, mime, readResult)
    }
}
