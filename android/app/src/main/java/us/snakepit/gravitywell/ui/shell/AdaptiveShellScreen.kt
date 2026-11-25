package us.snakepit.gravitywell.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import us.snakepit.gravitywell.cache.CachedHost
import us.snakepit.gravitywell.net.WsConnectionState
import us.snakepit.gravitywell.service.GravityWellNotifications
import us.snakepit.gravitywell.theme.GravityWellTheme
import us.snakepit.gravitywell.ui.console.ConsoleQuickAttachBay
import us.snakepit.gravitywell.ui.console.SessionConsoleController
import us.snakepit.gravitywell.ui.console.SessionConsoleScreen
import us.snakepit.gravitywell.ui.deck.ControlDeckController
import us.snakepit.gravitywell.ui.deck.ControlDeckScreen
import us.snakepit.gravitywell.ui.hosts.HostOnboardingController
import us.snakepit.gravitywell.ui.hosts.HostOnboardingScreen
import us.snakepit.gravitywell.ui.hosts.HostSwitcherSheet
import us.snakepit.gravitywell.ui.observatory.ObservatoryController
import us.snakepit.gravitywell.ui.observatory.ObservatoryScreen
import us.snakepit.gravitywell.ui.sessions.SessionBrowserController
import us.snakepit.gravitywell.ui.sessions.SessionBrowserScreen

@Composable
fun GravityWellAdaptiveShell(
    controller: ShellController,
    modifier: Modifier = Modifier,
) {
    val appContext = LocalContext.current.applicationContext
    LaunchedEffect(controller, appContext) {
        controller.bindApplicationContext(appContext)
    }

    val shellState by controller.state.collectAsState()
    val keepaliveEnabled by controller.keepaliveEnabled.collectAsState()
    val onboardingCtrl by controller.onboardingController.collectAsState()
    val browserCtrl by controller.sessionBrowserController.collectAsState()
    val consoleCtrl by controller.sessionConsoleController.collectAsState()
    val observatoryCtrl by controller.observatoryController.collectAsState()
    val deckCtrl by controller.controlDeckController.collectAsState()

    val colors = GravityWellTheme.colors
    val spacing = GravityWellTheme.spacing

    if (!shellState.initialized) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(colors.chassisVoid),
        )
        return
    }

    // Full-screen 3-Leg Pre-Flight Onboarding when zero hosts exist or modal open
    if (shellState.hosts.isEmpty() || shellState.showAddHostModal) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(colors.chassisVoid)
                .systemBarsPadding(),
        ) {
            BoundHostOnboardingPane(
                controller = onboardingCtrl,
                canCancel = shellState.hosts.isNotEmpty(),
                onSaved = { saved -> controller.onHostOnboardingSaved(saved) },
                onCancel = { controller.dismissAddHostModal() },
            )
        }
        return
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(colors.chassisVoid)
            .systemBarsPadding(),
    ) {
        val widthDp = maxWidth.value.toInt()
        val layout = AdaptiveShellBreakpoints.resolveLayout(
            widthDp = widthDp,
            destination = shellState.destination,
            attachedSessionId = shellState.attachedSessionId,
        )

        BackHandler(enabled = layout.showCompactConsoleBackButton) {
            controller.exitCompactConsoleToSessions()
        }

        Row(modifier = Modifier.fillMaxSize()) {
            if (layout.showNavigationRail) {
                VernierNavigationRail(
                    activeDestination = shellState.destination,
                    activeHost = shellState.activeHost,
                    wsState = shellState.wsState,
                    onSelectDestination = controller::selectDestination,
                    onOpenHostSwitcher = { controller.setShowHostSwitcher(true) },
                )
                VerticalDivider(thickness = spacing.hairlineWidth, color = colors.hairline)
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                // Top Persistent Host Bar on Sessions / Observatory / Deck (and Quick-Attach Console)
                val showTopHostBar = shellState.destination != TopLevelDestination.CONSOLE ||
                    shellState.attachedSessionId == null ||
                    layout.showTwoPaneSessionsConsole

                if (showTopHostBar) {
                    PersistentHostTopBar(
                        destination = shellState.destination,
                        host = shellState.activeHost,
                        wsState = shellState.wsState,
                        onOpenHostSwitcher = { controller.setShowHostSwitcher(true) },
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    if (layout.showTwoPaneSessionsConsole) {
                        // >= 600dp Expanded Two-Pane Instrument Bench: 360dp Sessions + Console Detail
                        Row(modifier = Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .width(360.dp)
                                    .fillMaxHeight(),
                            ) {
                                browserCtrl?.let { bCtrl ->
                                    BoundSessionBrowserPane(
                                        controller = bCtrl,
                                        selectedSessionId = shellState.attachedSessionId,
                                        onOpenSession = controller::openSessionInConsole,
                                        onSessionDeleted = { deletedId ->
                                            if (shellState.attachedSessionId == deletedId) {
                                                controller.detachActiveConsoleSession()
                                            }
                                        },
                                    )
                                }
                            }
                            VerticalDivider(thickness = spacing.hairlineWidth, color = colors.hairline)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                            ) {
                                BoundConsoleOrQuickAttachPane(
                                    consoleCtrl = consoleCtrl,
                                    browserCtrl = browserCtrl,
                                    showBackButton = false,
                                    onBack = controller::exitCompactConsoleToSessions,
                                    onAttachSession = controller::openSessionInConsole,
                                    onSessionClosed = controller::detachActiveConsoleSession,
                                )
                            }
                        }
                    } else {
                        when (shellState.destination) {
                            TopLevelDestination.SESSIONS -> {
                                browserCtrl?.let { bCtrl ->
                                    BoundSessionBrowserPane(
                                        controller = bCtrl,
                                        selectedSessionId = shellState.attachedSessionId,
                                        onOpenSession = controller::openSessionInConsole,
                                        onSessionDeleted = { deletedId ->
                                            if (shellState.attachedSessionId == deletedId) {
                                                controller.detachActiveConsoleSession()
                                            }
                                        },
                                    )
                                }
                            }

                            TopLevelDestination.CONSOLE -> {
                                BoundConsoleOrQuickAttachPane(
                                    consoleCtrl = consoleCtrl,
                                    browserCtrl = browserCtrl,
                                    showBackButton = layout.showCompactConsoleBackButton,
                                    onBack = controller::exitCompactConsoleToSessions,
                                    onAttachSession = controller::openSessionInConsole,
                                    onSessionClosed = controller::detachActiveConsoleSession,
                                )
                            }

                            TopLevelDestination.OBSERVATORY -> {
                                observatoryCtrl?.let { obsCtrl ->
                                    BoundObservatoryPane(
                                        controller = obsCtrl,
                                        browserController = browserCtrl,
                                        onOpenSessionInConsole = controller::openSessionInConsole,
                                    )
                                }
                            }

                            TopLevelDestination.DECK -> {
                                deckCtrl?.let { dCtrl ->
                                    BoundControlDeckPane(
                                        controller = dCtrl,
                                        hosts = shellState.hosts,
                                        activeHost = shellState.activeHost,
                                        keepaliveEnabled = keepaliveEnabled,
                                        onSetKeepaliveEnabled = controller::setKeepaliveEnabled,
                                        onSelectHost = controller::selectActiveHost,
                                        onReverifyHost = controller::openReverifyHostModal,
                                        onAddNewHost = controller::openAddHostModal,
                                        onDeleteHost = controller::deleteHost,
                                    )
                                }
                            }
                        }
                    }
                }

                if (layout.showBottomNavigationBar) {
                    HorizontalDivider(thickness = spacing.hairlineWidth, color = colors.hairline)
                    VernierNavigationBar(
                        activeDestination = shellState.destination,
                        onSelectDestination = controller::selectDestination,
                    )
                }
            }
        }
    }

    if (shellState.showHostSwitcherSheet) {
        HostSwitcherSheet(
            hosts = shellState.hosts,
            activeHostId = shellState.activeHost?.hostId,
            onSelectHost = controller::selectActiveHost,
            onReverifyHost = controller::openReverifyHostModal,
            onDeleteHost = controller::deleteHost,
            onAddNewHost = controller::openAddHostModal,
            onDismiss = { controller.setShowHostSwitcher(false) },
        )
    }
}

@Composable
private fun BoundObservatoryPane(
    controller: ObservatoryController,
    browserController: SessionBrowserController?,
    onOpenSessionInConsole: (String) -> Unit,
) {
    val st by controller.state.collectAsState()
    ObservatoryScreen(
        state = st,
        onRefreshAll = { controller.refreshAll(forceRefreshUsage = false) },
        onRefreshUsageBypassCache = controller::refreshUsageBypassCache,
        onUpdateDaemonNameDraft = controller::updateDaemonNameDraft,
        onSetDaemonSessionScoped = controller::setDaemonSessionScoped,
        onStartDaemon = controller::startRemoteControl,
        onStopDaemon = controller::stopRemoteControl,
        onOpenUpdateConfirmSheet = controller::openUpdateConfirmSheet,
        onDismissUpdateConfirmSheet = controller::dismissUpdateConfirmSheet,
        onConfirmAndRunUpdate = controller::confirmAndRunUpdate,
        onSelectLogSource = controller::selectLogSource,
        onSelectCrashFile = controller::selectCrashFile,
        onSelectLogTail = controller::selectLogTail,
        onUpdateLogSearchQuery = controller::updateLogSearchQuery,
        onFetchLogsNow = controller::fetchLogsNow,
        onSetShowExpandedLogSheet = controller::setShowExpandedLogSheet,
        onOpenOrResumeExternalSession = { ext ->
            if (ext.supervised) {
                onOpenSessionInConsole(ext.sessionId)
            } else {
                browserController?.confirmResumeSession(
                    sessionId = ext.sessionId,
                    cwd = ext.cwd,
                    loadHistory = true,
                    onResumed = onOpenSessionInConsole,
                )
            }
        },
        onDismissErrorBanner = controller::dismissErrorBanner,
    )
}

@Composable
private fun BoundControlDeckPane(
    controller: ControlDeckController,
    hosts: List<CachedHost>,
    activeHost: CachedHost?,
    keepaliveEnabled: Boolean,
    onSetKeepaliveEnabled: (Boolean) -> Unit,
    onSelectHost: (String) -> Unit,
    onReverifyHost: (CachedHost) -> Unit,
    onAddNewHost: () -> Unit,
    onDeleteHost: (String) -> Unit,
) {
    val context = LocalContext.current
    val st by controller.state.collectAsState()
    ControlDeckScreen(
        state = st,
        hosts = hosts,
        activeHost = activeHost,
        keepaliveEnabled = keepaliveEnabled,
        onSelectTab = controller::selectTab,
        onRefreshAll = controller::refreshAll,
        onOpenAddMcpSheet = controller::openAddMcpSheet,
        onDismissAddMcpSheet = controller::dismissAddMcpSheet,
        onUpdateAddMcpForm = controller::updateAddMcpForm,
        onSubmitAddMcp = { controller.submitAddMcpServer(authenticateAfterSave = false) },
        onSubmitAddMcpAndAuth = { controller.submitAddMcpServer(authenticateAfterSave = true) },
        onStartMcpOAuth = { name -> controller.openMcpOAuthFlow(name) },
        onUpdateMcpOAuthFlow = controller::updateMcpOAuthFlow,
        onRetryMcpOAuthDiscovery = controller::startMcpOAuthDiscovery,
        onSubmitMcpOAuthCode = controller::submitMcpOAuthCode,
        onDismissMcpOAuthSheet = controller::dismissMcpOAuthSheet,
        onRefreshMcpOAuth = controller::refreshMcpOAuthToken,
        onSignOutMcpOAuth = controller::signOutMcpOAuth,
        onToggleMcpEnabled = controller::toggleMcpServerEnabled,
        onRequestRemoveMcp = controller::requestRemoveMcpServer,
        onCancelRemoveMcp = controller::cancelRemoveMcpServer,
        onConfirmRemoveMcp = controller::confirmRemoveMcpServer,
        onUpdatePluginTarget = controller::updatePluginTargetDraft,
        onSetPluginStrict = controller::setPluginValidateStrict,
        onValidatePlugin = controller::validatePluginTarget,
        onInstallPlugin = controller::installPluginTarget,
        onTogglePluginEnabled = controller::togglePluginEnabled,
        onRequestUninstallPlugin = controller::requestUninstallPlugin,
        onCancelUninstallPlugin = controller::cancelUninstallPlugin,
        onConfirmUninstallPlugin = controller::confirmUninstallPlugin,
        onOpenNewAgentSheet = controller::openNewAgentSheet,
        onOpenEditAgentSheet = controller::openEditAgentSheet,
        onDismissAgentEditor = controller::dismissAgentEditorSheet,
        onUpdateAgentForm = controller::updateAgentForm,
        onSubmitAgentForm = controller::submitAgentForm,
        onRequestDeleteAgent = controller::requestDeleteAgent,
        onCancelDeleteAgent = controller::cancelDeleteAgent,
        onConfirmDeleteAgent = controller::confirmDeleteAgent,
        onRefreshRules = controller::refreshPermissionRules,
        onRevokeRule = controller::revokePermissionRule,
        onToggleAllowlistedSetting = controller::updateAllowlistedSetting,
        onSetDefaultModel = controller::updateGatewayDefaultModel,
        onSetKeepaliveEnabled = onSetKeepaliveEnabled,
        onOpenSystemNotificationSettings = { GravityWellNotifications.openSystemNotificationSettings(context) },
        onSelectHost = onSelectHost,
        onReverifyHost = onReverifyHost,
        onAddNewHost = onAddNewHost,
        onRequestDeleteHost = controller::requestDeleteHost,
        onCancelDeleteHost = controller::cancelDeleteHost,
        onConfirmDeleteHost = {
            val target = controller.consumeDeleteHostTarget()
            if (target != null) {
                onDeleteHost(target)
            }
        },
        onDismissBanners = controller::dismissBanners,
    )
}

@Composable
private fun BoundHostOnboardingPane(
    controller: HostOnboardingController,
    canCancel: Boolean,
    onSaved: (CachedHost) -> Unit,
    onCancel: () -> Unit,
) {
    val st by controller.state.collectAsState()
    HostOnboardingScreen(
        state = st,
        onLabelChange = controller::updateLabel,
        onBaseUrlChange = controller::updateBaseUrl,
        onTokenChange = controller::updateBearerToken,
        onToggleTokenVisibility = controller::toggleTokenVisibility,
        onRunPreflight = { controller.runPreflightCheck() },
        onSaveVerifiedHost = { controller.saveVerifiedHost(onSaved = onSaved) },
        onCancel = if (canCancel) onCancel else null,
    )
}

@Composable
private fun BoundSessionBrowserPane(
    controller: SessionBrowserController,
    selectedSessionId: String?,
    onOpenSession: (String) -> Unit,
    onSessionDeleted: (String) -> Unit = {},
) {
    val st by controller.state.collectAsState()
    SessionBrowserScreen(
        state = st,
        selectedSessionId = selectedSessionId,
        onScopeChange = controller::setScope,
        onSearchQueryChange = controller::setSearchQuery,
        onSelectWorkspace = controller::selectWorkspace,
        onToggleRadar = controller::toggleRadarExpanded,
        onRefresh = controller::refresh,
        onOpenNewSession = controller::openNewSessionSheet,
        onSessionClick = { item -> controller.onSessionRowClick(item, onOpenSession) },
        onRenameRequest = controller::openRenameDialog,
        onTogglePin = controller::togglePinSession,
        onToggleArchive = controller::toggleArchiveSession,
        onRequestDelete = controller::requestDeleteSession,
        onDismissDelete = controller::dismissDeleteSession,
        onConfirmDelete = { controller.confirmDeleteSession(onDeleted = onSessionDeleted) },
        onCloseSession = controller::closeLiveSession,
        onForkSession = { item -> controller.attemptForkSession(item, onOpenSession) },
        onResumeExternalSession = { ext ->
            controller.confirmResumeSession(
                sessionId = ext.sessionId,
                cwd = ext.cwd,
                loadHistory = true,
                onResumed = onOpenSession,
            )
        },
        onDismissResumePrompt = controller::dismissResumePrompt,
        onConfirmResume = { sid, cwd, loadHist ->
            controller.confirmResumeSession(sid, cwd, loadHist, onOpenSession)
        },
        onUpdateRenameDraft = controller::updateRenameDraft,
        onDismissRename = controller::dismissRenameDialog,
        onSubmitRename = controller::submitRename,
        onDismissNewSession = controller::dismissNewSessionSheet,
        onUpdateNewSessionForm = controller::updateNewSessionForm,
        onAddExtraDir = controller::addExtraDirectoryChip,
        onRemoveExtraDir = controller::removeExtraDirectoryChip,
        onSubmitCreateSession = { controller.submitCreateSession(onOpenSession) },
        onDismissNotices = controller::dismissNotices,
    )
}

@Composable
private fun BoundConsoleOrQuickAttachPane(
    consoleCtrl: SessionConsoleController?,
    browserCtrl: SessionBrowserController?,
    showBackButton: Boolean,
    onBack: () -> Unit,
    onAttachSession: (String) -> Unit,
    onSessionClosed: () -> Unit,
) {
    if (consoleCtrl == null) {
        val browserState = browserCtrl?.state?.collectAsState()?.value
        val liveSessions = browserState?.sessions?.filter { it.isLive }.orEmpty()
        ConsoleQuickAttachBay(
            liveSessions = liveSessions,
            onAttachSession = onAttachSession,
            onCreateNewSession = { browserCtrl?.openNewSessionSheet() },
        )
        return
    }

    val st by consoleCtrl.state.collectAsState()
    key(st.session.sessionId) {
        SessionConsoleScreen(
            state = st,
            showBackButton = showBackButton,
            onBack = onBack,
            onRenameTitleClick = consoleCtrl::openRenameDialog,
            onTogglePin = { consoleCtrl.togglePinSession(onChanged = { browserCtrl?.refresh() }) },
            onToggleArchive = {
                consoleCtrl.toggleArchiveSession(
                    onArchived = {
                        browserCtrl?.refresh()
                        onSessionClosed()
                    },
                )
            },
            onDeleteSession = {
                consoleCtrl.deleteSessionPermanently(
                    onDeleted = {
                        browserCtrl?.refresh()
                        onSessionClosed()
                    },
                )
            },
            onUpdateRenameDraft = consoleCtrl::updateRenameDraft,
            onDismissRename = consoleCtrl::dismissRenameDialog,
            onSubmitRename = consoleCtrl::submitRenameSession,
            onCancelTurn = consoleCtrl::cancelActiveTurn,
            onOpenConfigSheet = { consoleCtrl.setShowConfigSheet(true) },
            onDismissConfigSheet = { consoleCtrl.setShowConfigSheet(false) },
            onUpdateLiveConfig = consoleCtrl::updateLiveConfig,
            onOpenArtifacts = consoleCtrl::openArtifactsSheet,
            onDismissArtifacts = consoleCtrl::closeArtifactsSheet,
            onSelectArtifact = consoleCtrl::openArtifactItem,
            onBackToArtifactList = consoleCtrl::clearSelectedArtifact,
            onOpenWorkspaceRules = consoleCtrl::openWorkspaceRulesSheet,
            onDismissWorkspaceRules = consoleCtrl::closeWorkspaceRulesSheet,
            onRevokeWorkspaceRule = consoleCtrl::revokeWorkspaceRule,
            onCloseSession = { consoleCtrl.closeSession(onClosed = onSessionClosed) },
            onToggleThought = consoleCtrl::toggleThoughtExpanded,
            onToggleToolGutter = consoleCtrl::toggleToolExpanded,
            onExpandSwarmTool = consoleCtrl::expandToolAndFocus,
            onSetToolDiffMode = consoleCtrl::setToolDiffMode,
            onResolvePermission = consoleCtrl::resolvePermission,
            onUpdateQuestionAnswer = consoleCtrl::updateQuestionDraftAnswer,
            onResolveQuestion = consoleCtrl::resolveQuestion,
            onResumeQueue = consoleCtrl::resumePausedQueue,
            onStartEditQueueItem = { q -> consoleCtrl.startEditingQueueItem(q.itemId, q.text) },
            onUpdateEditQueueText = consoleCtrl::updateEditingQueueText,
            onSaveEditQueueItem = consoleCtrl::saveEditedQueueItem,
            onCancelEditQueueItem = consoleCtrl::cancelEditingQueueItem,
            onDeleteQueueItem = consoleCtrl::deleteQueueItem,
            onSendQueueItemNow = { itemId -> consoleCtrl.sendQueueItemNow(itemId, interrupt = true) },
            onDraftChange = consoleCtrl::updateDraft,
            onSubmitSpokenPrompt = consoleCtrl::submitSpokenPrompt,
            onAppendVoiceNotice = consoleCtrl::appendVoiceNotice,
            onAttachBytes = consoleCtrl::attachFileBytes,
            onRejectOversizedAttachment = consoleCtrl::rejectOversizedAttachment,
            onRemoveAttachment = consoleCtrl::removeAttachment,
            onToggleSlashBar = consoleCtrl::toggleSlashCommandChips,
            onSelectSlashCommand = consoleCtrl::selectSlashCommandChip,
            onSelectAtFile = consoleCtrl::selectAtFileCandidate,
            onSubmitPrompt = consoleCtrl::submitComposerPrompt,
            onDismissErrorBanner = consoleCtrl::dismissErrorBanner,
        )
    }
}

// ── Modern Chat-First NavigationBar (<600dp) & NavigationRail (>=600dp) ───

@Composable
fun VernierNavigationBar(
    activeDestination: TopLevelDestination,
    onSelectDestination: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    val highlightedTab = if (activeDestination == TopLevelDestination.CONSOLE) {
        TopLevelDestination.SESSIONS
    } else {
        activeDestination
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(68.dp)
            .background(colors.stratumBase)
            .padding(horizontal = spacing.md, vertical = 6.dp)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TopLevelDestination.primaryTabs.forEach { dest ->
            val selected = dest == highlightedTab
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor)
                    .clip(shapes.md)
                    .clickable(role = Role.Tab) { onSelectDestination(dest) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier
                        .clip(shapes.pill)
                        .background(if (selected) colors.signalCyanDim else colors.stratumBase)
                        .padding(horizontal = 18.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = destinationIcon(dest),
                        contentDescription = dest.label,
                        tint = if (selected) colors.signalCyan else colors.inkMuted,
                        modifier = Modifier.size(21.dp),
                    )
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = dest.label,
                    style = type.labelSmallUi,
                    color = if (selected) colors.inkPrimary else colors.inkMuted,
                )
            }
        }
    }
}

@Composable
fun VernierNavigationRail(
    activeDestination: TopLevelDestination,
    activeHost: CachedHost?,
    wsState: WsConnectionState,
    onSelectDestination: (TopLevelDestination) -> Unit,
    onOpenHostSwitcher: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    val highlightedTab = if (activeDestination == TopLevelDestination.CONSOLE) {
        TopLevelDestination.SESSIONS
    } else {
        activeDestination
    }

    Column(
        modifier = modifier
            .width(82.dp)
            .fillMaxHeight()
            .background(colors.stratumBase)
            .padding(vertical = spacing.md)
            .selectableGroup(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor)
                .clip(shapes.pill)
                .background(colors.stratumRaised)
                .border(spacing.hairlineWidth, colors.hairline, shapes.pill)
                .clickable(onClick = onOpenHostSwitcher)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(wsDotColor(wsState)),
                )
                Icon(
                    imageVector = Icons.Default.Dns,
                    contentDescription = activeHost?.label ?: "Switch host",
                    tint = colors.inkPrimary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(spacing.xs))

        TopLevelDestination.primaryTabs.forEach { dest ->
            val selected = dest == highlightedTab
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.sm)
                    .defaultMinSize(minHeight = 60.dp)
                    .clip(shapes.md)
                    .clickable(role = Role.Tab) { onSelectDestination(dest) }
                    .padding(vertical = spacing.xs),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier
                        .clip(shapes.pill)
                        .background(if (selected) colors.signalCyanDim else colors.stratumBase)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = destinationIcon(dest),
                        contentDescription = dest.label,
                        tint = if (selected) colors.signalCyan else colors.inkMuted,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = dest.label,
                    style = type.labelSmallUi,
                    color = if (selected) colors.inkPrimary else colors.inkMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun PersistentHostTopBar(
    destination: TopLevelDestination,
    host: CachedHost?,
    wsState: WsConnectionState,
    onOpenHostSwitcher: () -> Unit,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    val screenTitle = when (destination) {
        TopLevelDestination.SESSIONS,
        TopLevelDestination.CONSOLE,
        -> "Chats"
        TopLevelDestination.OBSERVATORY -> "Status"
        TopLevelDestination.DECK -> "Settings"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 58.dp)
            .background(colors.chassisVoid)
            .padding(horizontal = spacing.lg, vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = screenTitle,
            style = type.headlineSection,
            color = colors.inkPrimary,
        )

        Row(
            modifier = Modifier
                .defaultMinSize(minWidth = spacing.touchFloor, minHeight = 40.dp)
                .clip(shapes.pill)
                .background(colors.stratumBase)
                .border(spacing.hairlineWidth, colors.hairline, shapes.pill)
                .clickable(onClick = onOpenHostSwitcher)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(wsDotColor(wsState)),
            )
            Text(
                text = host?.label ?: "Select host",
                style = type.labelUi,
                color = colors.inkPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = Icons.Rounded.ExpandMore,
                contentDescription = "Switch host",
                tint = colors.inkMuted,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

private fun destinationIcon(dest: TopLevelDestination): ImageVector = when (dest) {
    TopLevelDestination.SESSIONS -> Icons.Rounded.Forum
    TopLevelDestination.CONSOLE -> Icons.AutoMirrored.Rounded.Chat
    TopLevelDestination.OBSERVATORY -> Icons.Rounded.Insights
    TopLevelDestination.DECK -> Icons.Rounded.Settings
}

@Composable
private fun wsDotColor(wsState: WsConnectionState) = when (wsState) {
    is WsConnectionState.Live -> GravityWellTheme.colors.signalEmerald
    is WsConnectionState.Replaying,
    is WsConnectionState.Connecting,
    -> GravityWellTheme.colors.signalCyan
    is WsConnectionState.Reconnecting -> GravityWellTheme.colors.signalAmber
    is WsConnectionState.AuthFailed -> GravityWellTheme.colors.signalCrimson
    is WsConnectionState.Disconnected -> GravityWellTheme.colors.inkMuted
}
