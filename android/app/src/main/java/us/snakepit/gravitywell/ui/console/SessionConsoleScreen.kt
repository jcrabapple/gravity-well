package us.snakepit.gravitywell.ui.console

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import us.snakepit.gravitywell.theme.GravityWellTheme
import us.snakepit.gravitywell.ui.sessions.SessionBrowserItem
import us.snakepit.gravitywell.ui.sessions.WorkspaceFileItemDto
import us.snakepit.gravitywell.ui.sessions.basenameOfPath

@Composable
fun SessionConsoleScreen(
    state: SessionConsoleUiState,
    showBackButton: Boolean,
    onBack: () -> Unit,
    onRenameTitleClick: () -> Unit,
    onTogglePin: () -> Unit = {},
    onToggleArchive: () -> Unit = {},
    onDeleteSession: () -> Unit = {},
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
    onSubmitSpokenPrompt: (String) -> Unit = {},
    onAppendVoiceNotice: (label: String, message: String) -> Unit = { _, _ -> },
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
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var pendingMicAction by remember { mutableStateOf<String?>(null) }
    var dictationBaseDraft by remember { mutableStateOf("") }
    var lastSpokenProseId by remember { mutableStateOf<String?>(null) }
    var lastSpokenPermId by remember { mutableStateOf<String?>(null) }

    val latestState by rememberUpdatedState(state)
    val latestOnDraftChange by rememberUpdatedState(onDraftChange)
    val latestOnSubmitSpoken by rememberUpdatedState(onSubmitSpokenPrompt)
    val latestOnResolvePerm by rememberUpdatedState(onResolvePermission)
    val latestOnCancelTurn by rememberUpdatedState(onCancelTurn)
    val latestOnAppendVoiceNotice by rememberUpdatedState(onAppendVoiceNotice)

    val voiceEngine = remember(context) {
        var engineRef: AndroidVoiceEngine? = null
        val created = AndroidVoiceEngine(
            context = context,
            onDictationPartial = { partial ->
                val merged = if (dictationBaseDraft.isBlank()) partial else "${dictationBaseDraft.trimEnd()} $partial"
                latestOnDraftChange(merged, merged.length)
            },
            onDictationFinal = { spoken ->
                val merged = if (dictationBaseDraft.isBlank()) spoken else "${dictationBaseDraft.trimEnd()} $spoken"
                latestOnDraftChange(merged, merged.length)
                dictationBaseDraft = ""
            },
            onVoiceModeUtterance = { spoken ->
                val curSession = latestState.session
                val pendingPerm = curSession.pendingPermissions.firstOrNull()
                val intent = VoiceCommandParser.parseUtterance(
                    utterance = spoken,
                    hasPendingPermission = pendingPerm != null,
                    isTurnActive = curSession.isTurnActive,
                )
                when (intent) {
                    VoiceIntent.EndCall -> {
                        val secs = engineRef?.stopVoiceMode() ?: 0L
                        latestOnAppendVoiceNotice("VOICE MODE", "Stopped voice chat · ${formatVoiceElapsed(secs)}")
                    }
                    VoiceIntent.StopTurn -> {
                        latestOnCancelTurn()
                        engineRef?.speak("Stopped.")
                    }
                    is VoiceIntent.PermissionDecision -> {
                        if (pendingPerm != null) {
                            val summary = pendingPerm.toolCall.toString()
                            if (intent.decision != "deny" && VoiceCommandParser.isDestructiveCommand(summary)) {
                                engineRef?.speak("That command looks destructive, so please tap Allow on screen or say deny.")
                            } else {
                                latestOnResolvePerm(pendingPerm.requestId, intent.decision, null)
                                engineRef?.speak(if (intent.decision == "deny") "Denied." else "Allowed.")
                            }
                        }
                    }
                    is VoiceIntent.PromptText -> {
                        engineRef?.setWorkingHint("Working on your request...")
                        latestOnSubmitSpoken(intent.text)
                    }
                }
            },
            onVoiceError = { _ -> },
        )
        engineRef = created
        created
    }
    val voiceState by voiceEngine.state.collectAsState()

    DisposableEffect(voiceEngine) {
        onDispose { voiceEngine.release() }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val action = pendingMicAction
        pendingMicAction = null
        if (granted) {
            when (action) {
                "voice" -> {
                    onAppendVoiceNotice("VOICE MODE", "Started voice chat")
                    voiceEngine.startVoiceMode()
                }
                "dictate" -> {
                    dictationBaseDraft = state.draftText
                    voiceEngine.startDictation()
                }
            }
        }
    }

    fun requestOrRunMicAction(action: String) {
        val hasPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPerm) {
            when (action) {
                "voice" -> {
                    if (voiceState.isActive) {
                        val secs = voiceEngine.stopVoiceMode()
                        onAppendVoiceNotice("VOICE MODE", "Stopped voice chat · ${formatVoiceElapsed(secs)}")
                    } else {
                        onAppendVoiceNotice("VOICE MODE", "Started voice chat")
                        voiceEngine.startVoiceMode()
                    }
                }
                "dictate" -> {
                    if (voiceState.isDictating) {
                        voiceEngine.stopDictation()
                    } else {
                        dictationBaseDraft = state.draftText
                        voiceEngine.startDictation()
                    }
                }
            }
        } else {
            pendingMicAction = action
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Speak settled Antigravity replies aloud automatically while Voice Mode is active
    val latestSettledProse = remember(state.session.ledger) {
        state.session.ledger
            .lastOrNull { it is LedgerItem.AgentProse && !it.isStreaming && it.text.isNotBlank() }
            as? LedgerItem.AgentProse
    }
    LaunchedEffect(voiceState.isActive, latestSettledProse?.id, state.session.isTurnActive) {
        if (!voiceState.isActive) {
            lastSpokenProseId = latestSettledProse?.id
            return@LaunchedEffect
        }
        val prose = latestSettledProse ?: return@LaunchedEffect
        if (!state.session.isTurnActive && prose.id != lastSpokenProseId) {
            lastSpokenProseId = prose.id
            voiceEngine.speak(prose.text)
        }
    }

    // Read pending permission prompts aloud in Voice Mode
    val firstPendingPerm = state.session.pendingPermissions.firstOrNull()
    LaunchedEffect(voiceState.isActive, firstPendingPerm?.requestId) {
        if (!voiceState.isActive || firstPendingPerm == null) return@LaunchedEffect
        if (firstPendingPerm.requestId != lastSpokenPermId) {
            lastSpokenPermId = firstPendingPerm.requestId
            val title = firstPendingPerm.toolCall["title"]?.toString()?.trim('"') ?: "a tool action"
            voiceEngine.speak("Permission needed for $title. Say allow once, always allow, or deny.")
        }
    }

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

    val isNearBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val total = layoutInfo.totalItemsCount
            if (total == 0) {
                true
            } else {
                val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisible >= total - 2
            }
        }
    }

    val tailSignature = remember(state.session.ledger) {
        when (val last = state.session.ledger.lastOrNull()) {
            is LedgerItem.AgentProse -> "${last.id}:${last.text.length}"
            is LedgerItem.ThoughtBlock -> "${last.id}:${last.text.length}"
            is LedgerItem.ToolCallBlock -> "${last.id}:${last.status}:${last.rawOutputSummary.length}"
            else -> last?.id.orEmpty()
        }
    }

    LaunchedEffect(state.session.ledger.size, tailSignature) {
        if (state.session.ledger.isNotEmpty() && isNearBottom) {
            listState.animateScrollToItem(state.session.ledger.lastIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.chassisVoid),
    ) {
        // 1. Clean Single-Row Chat Header with Tappable Model Pill
        ConsoleTopBar(
            title = state.session.title,
            modelId = state.session.modelId,
            thoughtLevel = state.session.thoughtLevel,
            modeId = state.session.modeId,
            usage = state.session.usage,
            streamIndicator = state.streamIndicator,
            isTurnActive = state.session.isTurnActive,
            isPinned = state.session.isPinned,
            isArchived = state.session.isArchived,
            showBackButton = showBackButton,
            onBack = onBack,
            onRenameTitle = onRenameTitleClick,
            onTogglePin = onTogglePin,
            onToggleArchive = onToggleArchive,
            onRequestDelete = { showDeleteConfirm = true },
            onOpenConfigSheet = onOpenConfigSheet,
            onCancelTurn = onCancelTurn,
            onOpenArtifacts = onOpenArtifacts,
            onOpenWorkspaceRules = onOpenWorkspaceRules,
            onCloseSession = onCloseSession,
        )
        HorizontalDivider(thickness = spacing.hairlineWidth, color = colors.hairline)

        // 2. Active Subagent / Swarm Strip (shown only when child workers are present)
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
                    .padding(horizontal = spacing.md, vertical = spacing.xs)
                    .clip(shapes.sm)
                    .background(colors.signalCrimsonDim)
                    .border(spacing.hairlineWidth, colors.signalCrimson, shapes.sm)
                    .padding(start = spacing.md, end = spacing.xs, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = state.errorBanner,
                    style = type.bodyCompact,
                    color = colors.signalCrimson,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onDismissErrorBanner,
                    modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Dismiss error",
                        tint = colors.signalCrimson,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        // 3. Airy Chat Message Stream + Floating Jump-to-Latest Button
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (state.session.ledger.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(spacing.xxl),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(spacing.md),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(colors.signalCyanDim),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = colors.signalCyan,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                        Text(
                            text = "How can Antigravity help?",
                            style = type.headlineSection,
                            color = colors.inkPrimary,
                            textAlign = TextAlign.Center,
                        )
                        if (state.session.cwd.isNotBlank()) {
                            Box(
                                modifier = Modifier
                                    .clip(shapes.pill)
                                    .background(colors.stratumBase)
                                    .border(spacing.hairlineWidth, colors.hairline, shapes.pill)
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    text = basenameOfPath(state.session.cwd),
                                    style = type.monoMicro,
                                    color = colors.inkSecondary,
                                )
                            }
                        }
                        Text(
                            text = "Type a message, use @ to mention project files, or / for slash commands.",
                            style = type.bodyCompact,
                            color = colors.inkMuted,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(top = spacing.lg, bottom = spacing.xl),
                    verticalArrangement = Arrangement.spacedBy(spacing.md),
                    modifier = Modifier.fillMaxSize(),
                ) {
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
                    }
                }
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = !isNearBottom && state.session.ledger.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(spacing.lg),
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(colors.stratumRaised)
                        .border(spacing.hairlineWidth, colors.hairlineStrong, CircleShape)
                        .clickable {
                            uiScope.launch {
                                if (state.session.ledger.isNotEmpty()) {
                                    listState.animateScrollToItem(state.session.ledger.lastIndex)
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowDownward,
                        contentDescription = "Jump to latest message",
                        tint = colors.inkPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        // 4. Docked Permission & Question Cards (non-blocking so diffs stay readable above)
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

        // 5. Queued Follow-Up Tray
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

        // 5b. Active Antigravity Voice Mode Panel
        if (voiceState.isActive) {
            VoiceModeCallPanel(
                voiceState = voiceState,
                onToggleMicMuted = voiceEngine::toggleMicMuted,
                onToggleAgentMuted = voiceEngine::toggleAgentMuted,
                onCycleSpeed = voiceEngine::cycleSpeed,
                onOpenVoicePicker = { voiceEngine.setShowVoicePicker(true) },
                onToggleMinimized = { voiceEngine.setMinimized(!voiceState.minimized) },
                onInterruptSpeaking = voiceEngine::interruptSpeakingAndListen,
                onEndCall = {
                    val secs = voiceEngine.stopVoiceMode()
                    onAppendVoiceNotice("VOICE MODE", "Stopped voice chat · ${formatVoiceElapsed(secs)}")
                },
            )
        }

        // 6. Two-Tier Rounded Chat Composer Dock
        ThumbZoneComposerDock(
            draftText = state.draftText,
            draftCursorIndex = state.draftCursorIndex,
            isTurnActive = state.session.isTurnActive,
            isSubmitting = state.isSubmittingPrompt,
            attachments = state.draftAttachments,
            attachmentThumbnails = state.attachmentThumbnails,
            atFileCandidates = state.atFileCandidates,
            slashCommands = slashCommands,
            isVoiceModeActive = voiceState.isActive,
            isDictating = voiceState.isDictating,
            onDraftChange = onDraftChange,
            onPickAttachment = { filePickerLauncher.launch(arrayOf("*/*")) },
            onRemoveAttachment = onRemoveAttachment,
            onToggleSlashBar = onToggleSlashBar,
            onSelectSlashCommand = onSelectSlashCommand,
            onSelectAtFile = onSelectAtFile,
            onToggleDictation = { requestOrRunMicAction("dictate") },
            onToggleVoiceMode = { requestOrRunMicAction("voice") },
            onSubmit = onSubmitPrompt,
        )
    }

    if (voiceState.showVoicePicker) {
        VoicePickerSheet(
            voices = voiceState.availableVoices,
            selectedVoiceId = voiceState.selectedVoiceId,
            onSelectVoice = { id -> voiceEngine.selectVoice(id, preview = true) },
            onDismiss = { voiceEngine.setShowVoicePicker(false) },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = colors.stratumBase,
            tonalElevation = spacing.elevationZero,
            shape = shapes.lg,
            title = {
                Text("Delete chat?", style = type.titleBar, color = colors.inkPrimary)
            },
            text = {
                Text(
                    text = "Permanently remove \"${state.session.title}\" from Gravity Well? This cannot be undone.",
                    style = type.bodyCompact,
                    color = colors.inkSecondary,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteSession()
                    },
                    modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                ) {
                    Text("Delete", style = type.labelUi, color = colors.signalCrimson)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirm = false },
                    modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                ) {
                    Text("Cancel", style = type.labelUi, color = colors.inkSecondary)
                }
            },
        )
    }

    // ── Sheets & Rename Dialog ───────────────────────────────────────────────

    if (state.showRenameDialog) {
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

    if (state.showConfigSheet) {
        SessionConfigSheet(
            session = state.session,
            streamIndicator = state.streamIndicator,
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
 * Friendly empty state shown in the Chat detail pane on tablets when no chat is selected yet.
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.chassisVoid)
            .padding(spacing.xxl),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(colors.signalCyanDim),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Forum,
                    contentDescription = null,
                    tint = colors.signalCyan,
                    modifier = Modifier.size(28.dp),
                )
            }

            Text(
                text = if (liveSessions.isEmpty()) {
                    "Start a new chat"
                } else {
                    "Select a chat to continue"
                },
                style = type.headlineSection,
                color = colors.inkPrimary,
                textAlign = TextAlign.Center,
            )

            Text(
                text = "Pick a conversation from the list or start a fresh thread with Antigravity.",
                style = type.bodyCompact,
                color = colors.inkSecondary,
                textAlign = TextAlign.Center,
            )

            Button(
                onClick = onCreateNewSession,
                shape = shapes.pill,
                elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.signalCyan,
                    contentColor = colors.inkInverse,
                ),
                contentPadding = PaddingValues(horizontal = spacing.xl, vertical = spacing.md),
                modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(spacing.xs))
                Text("New chat", style = type.labelUi)
            }

            if (liveSessions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(spacing.sm))
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(liveSessions.take(5), key = { it.sessionId }) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = spacing.touchFloor)
                                .clip(shapes.md)
                                .background(colors.stratumBase)
                                .border(spacing.hairlineWidth, colors.hairline, shapes.md)
                                .clickable { onAttachSession(item.sessionId) }
                                .padding(horizontal = spacing.lg, vertical = spacing.md),
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
                                    text = basenameOfPath(item.cwd),
                                    style = type.bodyCompact,
                                    color = colors.inkSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .clip(shapes.pill)
                                    .background(if (item.hasPendingGate) colors.signalAmberDim else colors.signalEmeraldDim)
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    text = if (item.hasPendingGate) "Needs approval" else "Active",
                                    style = type.labelSmallUi,
                                    color = if (item.hasPendingGate) colors.signalAmber else colors.signalEmerald,
                                )
                            }
                        }
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
