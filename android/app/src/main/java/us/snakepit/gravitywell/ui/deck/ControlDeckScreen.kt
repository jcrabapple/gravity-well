package us.snakepit.gravitywell.ui.deck

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import us.snakepit.gravitywell.cache.CachedHost
import us.snakepit.gravitywell.service.GravityWellNotifications
import us.snakepit.gravitywell.theme.GravityWellTheme
import us.snakepit.gravitywell.ui.console.formatModelDisplayName
import us.snakepit.gravitywell.ui.sessions.PermissionRuleDto
import us.snakepit.gravitywell.ui.shell.AdaptiveShellBreakpoints

@Composable
fun ControlDeckScreen(
    state: ControlDeckUiState,
    hosts: List<CachedHost>,
    activeHost: CachedHost?,
    keepaliveEnabled: Boolean,
    onSelectTab: (ControlDeckTab) -> Unit,
    onRefreshAll: () -> Unit,
    onOpenAddMcpSheet: () -> Unit,
    onDismissAddMcpSheet: () -> Unit,
    onUpdateAddMcpForm: ((AddMcpFormState) -> AddMcpFormState) -> Unit,
    onSubmitAddMcp: () -> Unit,
    onSubmitAddMcpAndAuth: () -> Unit = {},
    onStartMcpOAuth: (String) -> Unit = {},
    onUpdateMcpOAuthFlow: ((McpOAuthFlowUiState) -> McpOAuthFlowUiState) -> Unit = {},
    onRetryMcpOAuthDiscovery: () -> Unit = {},
    onSubmitMcpOAuthCode: () -> Unit = {},
    onDismissMcpOAuthSheet: () -> Unit = {},
    onRefreshMcpOAuth: (String) -> Unit = {},
    onSignOutMcpOAuth: (String) -> Unit = {},
    onToggleMcpEnabled: (name: String, enabled: Boolean) -> Unit,
    onRequestRemoveMcp: (String) -> Unit,
    onCancelRemoveMcp: () -> Unit,
    onConfirmRemoveMcp: () -> Unit,
    onUpdatePluginTarget: (String) -> Unit,
    onSetPluginStrict: (Boolean) -> Unit,
    onValidatePlugin: () -> Unit,
    onInstallPlugin: () -> Unit,
    onTogglePluginEnabled: (name: String, enabled: Boolean) -> Unit,
    onRequestUninstallPlugin: (String) -> Unit,
    onCancelUninstallPlugin: () -> Unit,
    onConfirmUninstallPlugin: () -> Unit,
    onOpenNewAgentSheet: () -> Unit,
    onOpenEditAgentSheet: (AgentItemDto) -> Unit,
    onDismissAgentEditor: () -> Unit,
    onUpdateAgentForm: ((AgentEditorFormState) -> AgentEditorFormState) -> Unit,
    onSubmitAgentForm: () -> Unit,
    onRequestDeleteAgent: (String) -> Unit,
    onCancelDeleteAgent: () -> Unit,
    onConfirmDeleteAgent: () -> Unit,
    onRefreshRules: () -> Unit,
    onRevokeRule: (String) -> Unit,
    onToggleAllowlistedSetting: (key: String, enabled: Boolean) -> Unit,
    onSetDefaultModel: (model: String, thoughtLevel: String?) -> Unit,
    onSetKeepaliveEnabled: (Boolean) -> Unit,
    onOpenSystemNotificationSettings: () -> Unit,
    onSelectHost: (String) -> Unit,
    onReverifyHost: (CachedHost) -> Unit,
    onAddNewHost: () -> Unit,
    onRequestDeleteHost: (String) -> Unit,
    onCancelDeleteHost: () -> Unit,
    onConfirmDeleteHost: () -> Unit,
    onDismissBanners: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var notifPermissionGranted by remember(context) {
        mutableStateOf(GravityWellNotifications.hasPostNotificationsPermission(context))
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notifPermissionGranted = GravityWellNotifications.hasPostNotificationsPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notifPermissionGranted = granted
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(colors.chassisVoid),
    ) {
        val isExpanded = maxWidth.value.toInt() >= AdaptiveShellBreakpoints.EXPANDED_WIDTH_DP

        Column(modifier = Modifier.fillMaxSize()) {
            // ── Clean Pill Sub-Tabs + Refresh ────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.chassisVoid)
                    .padding(horizontal = spacing.lg, vertical = spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(shapes.pill)
                        .background(colors.stratumBase)
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    ControlDeckTab.entries.forEach { tab ->
                        val selected = tab == state.activeTab
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 40.dp)
                                .clip(shapes.pill)
                                .background(if (selected) colors.signalCyanDim else colors.stratumBase)
                                .clickable(role = Role.Tab) { onSelectTab(tab) }
                                .padding(horizontal = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = tab.label,
                                style = type.labelSmallUi,
                                color = if (selected) colors.signalCyan else colors.inkSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                IconButton(
                    onClick = onRefreshAll,
                    modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                ) {
                    if (state.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = colors.signalCyan,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Refresh settings",
                            tint = colors.inkSecondary,
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.lg, vertical = spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.lg),
            ) {
                if (!state.errorBanner.isNullOrBlank()) {
                    BannerStrip(
                        message = state.errorBanner,
                        isError = true,
                        onDismiss = onDismissBanners,
                    )
                } else if (!state.infoBanner.isNullOrBlank()) {
                    BannerStrip(
                        message = state.infoBanner,
                        isError = false,
                        onDismiss = onDismissBanners,
                    )
                }

                when (state.activeTab) {
                    ControlDeckTab.MCP -> McpServersTabContent(
                        servers = state.mcpServers,
                        isExpanded = isExpanded,
                        isMutating = state.isMutating,
                        onOpenAdd = onOpenAddMcpSheet,
                        onToggleEnabled = onToggleMcpEnabled,
                        onAuthenticate = onStartMcpOAuth,
                        onRefreshOAuth = onRefreshMcpOAuth,
                        onSignOutOAuth = onSignOutMcpOAuth,
                        onRequestRemove = onRequestRemoveMcp,
                    )

                    ControlDeckTab.PLUGINS -> PluginsTabContent(
                        plugins = state.plugins,
                        targetDraft = state.pluginTargetDraft,
                        validateStrict = state.pluginValidateStrict,
                        validationResult = state.lastPluginValidation,
                        installMessage = state.lastPluginInstallMessage,
                        isExpanded = isExpanded,
                        isMutating = state.isMutating,
                        onTargetChange = onUpdatePluginTarget,
                        onStrictChange = onSetPluginStrict,
                        onValidate = onValidatePlugin,
                        onInstall = onInstallPlugin,
                        onToggleEnabled = onTogglePluginEnabled,
                        onRequestUninstall = onRequestUninstallPlugin,
                    )

                    ControlDeckTab.AGENTS_AND_RULES -> AgentsAndRulesTabContent(
                        agents = state.agents,
                        rules = state.permissionRules,
                        isExpanded = isExpanded,
                        isMutating = state.isMutating,
                        onNewAgent = onOpenNewAgentSheet,
                        onEditAgent = onOpenEditAgentSheet,
                        onDeleteAgent = onRequestDeleteAgent,
                        onRefreshRules = onRefreshRules,
                        onRevokeRule = onRevokeRule,
                    )

                    ControlDeckTab.SYSTEM -> SystemSettingsTabContent(
                        previewFeaturesEnabled = state.previewFeaturesEnabled,
                        promptCompletionEnabled = state.promptCompletionEnabled,
                        keepaliveEnabled = keepaliveEnabled,
                        notificationPermissionGranted = notifPermissionGranted,
                        availableModels = state.availableModels,
                        defaultModelId = state.defaultModelId,
                        gatewayDefaultModel = state.gatewayDefaultModel,
                        gatewayDefaultThoughtLevel = state.gatewayDefaultThoughtLevel,
                        hosts = hosts,
                        activeHost = activeHost,
                        isExpanded = isExpanded,
                        isMutating = state.isMutating,
                        onToggleSetting = onToggleAllowlistedSetting,
                        onSetKeepalive = { enabled ->
                            onSetKeepaliveEnabled(enabled)
                            if (
                                enabled &&
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                !GravityWellNotifications.hasPostNotificationsPermission(context)
                            ) {
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        onOpenNotificationSettings = onOpenSystemNotificationSettings,
                        onSetDefaultModel = onSetDefaultModel,
                        onSelectHost = onSelectHost,
                        onReverifyHost = onReverifyHost,
                        onAddNewHost = onAddNewHost,
                        onRequestDeleteHost = onRequestDeleteHost,
                    )
                }

                Spacer(modifier = Modifier.height(spacing.md))
            }
        }
    }

    // ── Sheets & Destructive Confirmations ───────────────────────────────────
    if (state.showAddMcpSheet) {
        AddMcpServerSheet(
            form = state.addMcpForm,
            isMutating = state.isMutating,
            onUpdateForm = onUpdateAddMcpForm,
            onSubmit = onSubmitAddMcp,
            onSubmitAndAuthenticate = onSubmitAddMcpAndAuth,
            onDismiss = onDismissAddMcpSheet,
        )
    }

    val oauthFlow = state.activeOAuthFlow
    if (oauthFlow != null) {
        McpOAuthSheet(
            flow = oauthFlow,
            onUpdateFlow = onUpdateMcpOAuthFlow,
            onStartDiscovery = onRetryMcpOAuthDiscovery,
            onSubmitCode = onSubmitMcpOAuthCode,
            onDismiss = onDismissMcpOAuthSheet,
        )
    }

    if (state.showAgentEditorSheet) {
        AgentEditorSheet(
            form = state.agentForm,
            isMutating = state.isMutating,
            onUpdateForm = onUpdateAgentForm,
            onSubmit = onSubmitAgentForm,
            onDismiss = onDismissAgentEditor,
        )
    }

    if (state.pendingRemoveMcpName != null) {
        DestructiveActionConfirmSheet(
            title = "Remove MCP server",
            detail = "Removes '${state.pendingRemoveMcpName}' from the host (`agy mcp remove`).",
            confirmLabel = "Remove",
            isBusy = state.isMutating,
            onConfirm = onConfirmRemoveMcp,
            onDismiss = onCancelRemoveMcp,
        )
    }

    if (state.pendingUninstallPluginName != null) {
        DestructiveActionConfirmSheet(
            title = "Uninstall plugin",
            detail = "Uninstalls '${state.pendingUninstallPluginName}' from the host (`agy plugin uninstall`).",
            confirmLabel = "Uninstall",
            isBusy = state.isMutating,
            onConfirm = onConfirmUninstallPlugin,
            onDismiss = onCancelUninstallPlugin,
        )
    }

    if (state.pendingDeleteAgentSlug != null) {
        DestructiveActionConfirmSheet(
            title = "Delete custom agent",
            detail = "Deletes `~/.gemini/antigravity-cli/agents/${state.pendingDeleteAgentSlug}.md`.",
            confirmLabel = "Delete",
            isBusy = state.isMutating,
            onConfirm = onConfirmDeleteAgent,
            onDismiss = onCancelDeleteAgent,
        )
    }

    if (state.pendingDeleteHostId != null) {
        val targetLabel = hosts.firstOrNull { it.hostId == state.pendingDeleteHostId }?.label
            ?: state.pendingDeleteHostId
        DestructiveActionConfirmSheet(
            title = "Remove host",
            detail = "Removes '$targetLabel' from local storage and wipes its encrypted token from Android Keystore.",
            confirmLabel = "Remove host",
            isBusy = false,
            onConfirm = onConfirmDeleteHost,
            onDismiss = onCancelDeleteHost,
        )
    }
}

@Composable
private fun BannerStrip(
    message: String,
    isError: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    val bg = if (isError) colors.signalCrimsonDim else colors.signalEmeraldDim
    val border = if (isError) colors.signalCrimson else colors.signalEmerald
    val fg = if (isError) colors.signalCrimson else colors.signalEmerald

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shapes.sm)
            .background(bg)
            .border(spacing.hairlineWidth, border, shapes.sm)
            .padding(start = spacing.md, end = spacing.xs, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = message,
            style = type.bodyCompact,
            color = fg,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Dismiss notice",
                tint = fg,
            )
        }
    }
}

// ── Tab 1: MCP Servers ───────────────────────────────────────────────────────

@Composable
private fun McpServersTabContent(
    servers: List<McpServerItemDto>,
    isExpanded: Boolean,
    isMutating: Boolean,
    onOpenAdd: () -> Unit,
    onToggleEnabled: (name: String, enabled: Boolean) -> Unit,
    onAuthenticate: (String) -> Unit,
    onRefreshOAuth: (String) -> Unit,
    onSignOutOAuth: (String) -> Unit,
    onRequestRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "MCP servers (${servers.size})",
                style = type.titleBar,
                color = colors.inkPrimary,
            )
            OutlinedButton(
                onClick = onOpenAdd,
                shape = shapes.pill,
                border = BorderStroke(spacing.hairlineWidth, colors.signalCyan),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = colors.signalCyanDim,
                    contentColor = colors.signalCyan,
                ),
                modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = 40.dp),
            ) {
                Icon(imageVector = Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(spacing.xs))
                Text("Add server", style = type.labelUi)
            }
        }

        if (servers.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shapes.md)
                    .background(colors.stratumBase)
                    .border(spacing.hairlineWidth, colors.hairline, shapes.md)
                    .padding(spacing.lg),
            ) {
                Text(
                    text = "No MCP servers configured on this host.",
                    style = type.bodyCompact,
                    color = colors.inkMuted,
                )
            }
        } else if (isExpanded && servers.size > 1) {
            servers.chunked(2).forEach { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.md),
                    verticalAlignment = Alignment.Top,
                ) {
                    McpServerCard(
                        server = pair[0],
                        isMutating = isMutating,
                        onToggleEnabled = onToggleEnabled,
                        onAuthenticate = onAuthenticate,
                        onRefreshOAuth = onRefreshOAuth,
                        onSignOutOAuth = onSignOutOAuth,
                        onRequestRemove = onRequestRemove,
                        modifier = Modifier.weight(1f),
                    )
                    if (pair.size > 1) {
                        McpServerCard(
                            server = pair[1],
                            isMutating = isMutating,
                            onToggleEnabled = onToggleEnabled,
                            onAuthenticate = onAuthenticate,
                            onRefreshOAuth = onRefreshOAuth,
                            onSignOutOAuth = onSignOutOAuth,
                            onRequestRemove = onRequestRemove,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        } else {
            servers.forEach { srv ->
                McpServerCard(
                    server = srv,
                    isMutating = isMutating,
                    onToggleEnabled = onToggleEnabled,
                    onAuthenticate = onAuthenticate,
                    onRefreshOAuth = onRefreshOAuth,
                    onSignOutOAuth = onSignOutOAuth,
                    onRequestRemove = onRequestRemove,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun McpServerCard(
    server: McpServerItemDto,
    isMutating: Boolean,
    onToggleEnabled: (name: String, enabled: Boolean) -> Unit,
    onAuthenticate: (String) -> Unit,
    onRefreshOAuth: (String) -> Unit,
    onSignOutOAuth: (String) -> Unit,
    onRequestRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    val enabled = !server.disabled
    val targetLine = if (server.type == "http") {
        server.serverUrl.orEmpty()
    } else {
        listOfNotNull(server.command)
            .plus(server.args)
            .joinToString(" ")
    }
    val maskedKeys = (
        server.envKeys.ifEmpty { server.env.keys.toList() }.map { "env:$it" } +
            server.headerKeys.ifEmpty { server.headers.keys.toList() }.map { "hdr:$it" }
        )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shapes.md)
            .background(colors.stratumBase)
            .border(spacing.hairlineWidth, colors.hairline, shapes.md)
            .padding(spacing.lg),
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
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = server.name,
                    style = type.titleBar,
                    color = colors.inkPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(
                    modifier = Modifier
                        .clip(shapes.pill)
                        .background(colors.stratumRaised)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = if (server.type == "http") "HTTP" else "stdio",
                        style = type.labelSmallUi,
                        color = colors.signalCyan,
                    )
                }
            }

            Box(
                modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Switch(
                    checked = enabled,
                    enabled = !isMutating,
                    onCheckedChange = { next -> onToggleEnabled(server.name, next) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = colors.inkInverse,
                        checkedTrackColor = colors.signalEmerald,
                        uncheckedThumbColor = colors.inkSecondary,
                        uncheckedTrackColor = colors.stratumRaised,
                    ),
                )
            }
        }

        if (targetLine.isNotBlank()) {
            Text(
                text = targetLine,
                style = type.monoMicro,
                color = colors.inkSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (maskedKeys.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                maskedKeys.forEach { keyTag ->
                    Row(
                        modifier = Modifier
                            .clip(shapes.pill)
                            .background(colors.stratumWell)
                            .border(spacing.hairlineWidth, colors.hairline, shapes.pill)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(keyTag, style = type.monoMicro, color = colors.inkSecondary)
                        Box(
                            modifier = Modifier
                                .clip(shapes.pill)
                                .background(colors.signalVioletDim)
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        ) {
                            Text("[REDACTED]", style = type.monoMicro, color = colors.signalViolet)
                        }
                    }
                }
            }
        }

        val oauth = server.oauthStatus
        if (server.type == "http" && oauth?.authenticated == true) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Box(
                    modifier = Modifier
                        .clip(shapes.pill)
                        .background(colors.signalEmeraldDim)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text("OAuth authenticated", style = type.labelSmallUi, color = colors.signalEmerald)
                }
                if (!oauth.scope.isNullOrBlank()) {
                    Text(
                        text = oauth.scope,
                        style = type.monoMicro,
                        color = colors.inkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.xs, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            if (server.type == "http") {
                if (oauth?.authenticated == true) {
                    if (oauth.hasRefreshToken) {
                        OutlinedButton(
                            onClick = { onRefreshOAuth(server.name) },
                            enabled = !isMutating,
                            shape = shapes.pill,
                            border = BorderStroke(spacing.hairlineWidth, colors.signalCyan),
                            modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = 40.dp),
                        ) {
                            Text("Refresh", style = type.labelUi, color = colors.signalCyan)
                        }
                    }
                    OutlinedButton(
                        onClick = { onSignOutOAuth(server.name) },
                        enabled = !isMutating,
                        shape = shapes.pill,
                        border = BorderStroke(spacing.hairlineWidth, colors.hairlineStrong),
                        modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = 40.dp),
                    ) {
                        Text("Sign out", style = type.labelUi, color = colors.inkSecondary)
                    }
                } else {
                    OutlinedButton(
                        onClick = { onAuthenticate(server.name) },
                        enabled = !isMutating,
                        shape = shapes.pill,
                        border = BorderStroke(spacing.hairlineWidth, colors.signalCyan),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = colors.signalCyanDim,
                            contentColor = colors.signalCyan,
                        ),
                        modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = 40.dp),
                    ) {
                        Text("Authenticate", style = type.labelUi)
                    }
                }
            }

            OutlinedButton(
                onClick = { onRequestRemove(server.name) },
                enabled = !isMutating,
                shape = shapes.pill,
                border = BorderStroke(spacing.hairlineWidth, colors.signalCrimson),
                modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = 40.dp),
            ) {
                Text("Remove", style = type.labelUi, color = colors.signalCrimson)
            }
        }
    }
}

// ── Tab 2: Plugins ───────────────────────────────────────────────────────────

@Composable
private fun PluginsTabContent(
    plugins: List<PluginUiItem>,
    targetDraft: String,
    validateStrict: Boolean,
    validationResult: ValidatePluginResponseDto?,
    installMessage: String?,
    isExpanded: Boolean,
    isMutating: Boolean,
    onTargetChange: (String) -> Unit,
    onStrictChange: (Boolean) -> Unit,
    onValidate: () -> Unit,
    onInstall: () -> Unit,
    onToggleEnabled: (name: String, enabled: Boolean) -> Unit,
    onRequestUninstall: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        // Validate & Install Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shapes.md)
                .background(colors.stratumBase)
                .border(spacing.hairlineWidth, colors.hairline, shapes.md)
                .padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text("Install or validate a plugin", style = type.titleBar, color = colors.inkPrimary)
            OutlinedTextField(
                value = targetDraft,
                onValueChange = onTargetChange,
                singleLine = true,
                textStyle = type.monoCode,
                placeholder = {
                    Text(
                        text = "Plugin directory path or https:// URL",
                        style = type.bodyCompact,
                        color = colors.inkMuted,
                    )
                },
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

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = spacing.touchFloor)
                    .clip(shapes.sm)
                    .clickable(role = Role.Checkbox) { onStrictChange(!validateStrict) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = validateStrict,
                    onCheckedChange = null,
                    colors = CheckboxDefaults.colors(
                        checkedColor = colors.signalCyan,
                        checkmarkColor = colors.inkInverse,
                        uncheckedColor = colors.inkSecondary,
                    ),
                )
                Spacer(modifier = Modifier.width(spacing.xs))
                Text("Strict validation (--strict)", style = type.bodyCompact, color = colors.inkPrimary)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                OutlinedButton(
                    onClick = onValidate,
                    enabled = !isMutating,
                    shape = shapes.pill,
                    border = BorderStroke(spacing.hairlineWidth, colors.signalCyan),
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = spacing.touchFloor),
                ) {
                    Text("Validate", style = type.labelUi, color = colors.signalCyan)
                }
                Button(
                    onClick = onInstall,
                    enabled = !isMutating,
                    shape = shapes.pill,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.signalCyan,
                        contentColor = colors.inkInverse,
                    ),
                    elevation = ButtonDefaults.buttonElevation(
                        defaultElevation = spacing.elevationZero,
                        pressedElevation = spacing.elevationZero,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = spacing.touchFloor),
                ) {
                    Text("Install", style = type.labelUi)
                }
            }

            if (validationResult != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shapes.sm)
                        .background(colors.stratumWell)
                        .border(
                            spacing.hairlineWidth,
                            if (validationResult.valid) colors.signalEmerald else colors.signalCrimson,
                            shapes.sm,
                        )
                        .padding(spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = if (validationResult.valid) "Validation passed" else "Validation failed",
                        style = type.labelSmallUi,
                        color = if (validationResult.valid) colors.signalEmerald else colors.signalCrimson,
                    )
                    val details = validationResult.errors.ifBlank { validationResult.report?.toString().orEmpty() }
                    if (details.isNotBlank()) {
                        Text(
                            text = details,
                            style = type.monoMicro,
                            color = colors.inkSecondary,
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (!installMessage.isNullOrBlank()) {
                Text(installMessage, style = type.bodyCompact, color = colors.signalEmerald)
            }
        }

        Text("Installed plugins (${plugins.size})", style = type.titleBar, color = colors.inkPrimary)

        if (plugins.isEmpty()) {
            Text("No plugins installed on this host.", style = type.bodyCompact, color = colors.inkMuted)
        } else if (isExpanded && plugins.size > 1) {
            plugins.chunked(2).forEach { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.md),
                    verticalAlignment = Alignment.Top,
                ) {
                    PluginCard(
                        plugin = pair[0],
                        isMutating = isMutating,
                        onToggleEnabled = onToggleEnabled,
                        onRequestUninstall = onRequestUninstall,
                        modifier = Modifier.weight(1f),
                    )
                    if (pair.size > 1) {
                        PluginCard(
                            plugin = pair[1],
                            isMutating = isMutating,
                            onToggleEnabled = onToggleEnabled,
                            onRequestUninstall = onRequestUninstall,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        } else {
            plugins.forEach { p ->
                PluginCard(
                    plugin = p,
                    isMutating = isMutating,
                    onToggleEnabled = onToggleEnabled,
                    onRequestUninstall = onRequestUninstall,
                )
            }
        }
    }
}

@Composable
private fun PluginCard(
    plugin: PluginUiItem,
    isMutating: Boolean,
    onToggleEnabled: (name: String, enabled: Boolean) -> Unit,
    onRequestUninstall: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shapes.md)
            .background(colors.stratumBase)
            .border(spacing.hairlineWidth, colors.hairline, shapes.md)
            .padding(spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    Text(
                        text = plugin.displayName,
                        style = type.titleBar,
                        color = colors.inkPrimary,
                    )
                    if (plugin.builtin) {
                        Box(
                            modifier = Modifier
                                .clip(shapes.pill)
                                .background(colors.signalCyanDim)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text("Built-in", style = type.labelSmallUi, color = colors.signalCyan)
                        }
                    }
                    if (!plugin.version.isNullOrBlank()) {
                        Text("v${plugin.version}", style = type.monoMicro, color = colors.inkMuted)
                    }
                }
                if (plugin.description.isNotBlank()) {
                    Text(plugin.description, style = type.bodyCompact, color = colors.inkSecondary)
                }
            }

            if (!plugin.builtin) {
                Box(
                    modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Switch(
                        checked = plugin.enabled,
                        enabled = !isMutating,
                        onCheckedChange = { next -> onToggleEnabled(plugin.name, next) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = colors.inkInverse,
                            checkedTrackColor = colors.signalEmerald,
                            uncheckedThumbColor = colors.inkSecondary,
                            uncheckedTrackColor = colors.stratumRaised,
                        ),
                    )
                }
            }
        }

        if (!plugin.builtin) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinedButton(
                    onClick = { onRequestUninstall(plugin.name) },
                    enabled = !isMutating,
                    shape = shapes.pill,
                    border = BorderStroke(spacing.hairlineWidth, colors.signalCrimson),
                    modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = 40.dp),
                ) {
                    Text("Uninstall", style = type.labelUi, color = colors.signalCrimson)
                }
            }
        }
    }
}

// ── Tab 3: Agents & Project Permission Rules ─────────────────────────────────

@Composable
private fun AgentsAndRulesTabContent(
    agents: List<AgentItemDto>,
    rules: List<PermissionRuleDto>,
    isExpanded: Boolean,
    isMutating: Boolean,
    onNewAgent: () -> Unit,
    onEditAgent: (AgentItemDto) -> Unit,
    onDeleteAgent: (String) -> Unit,
    onRefreshRules: () -> Unit,
    onRevokeRule: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.lg),
    ) {
        // Project Permission Rules Safety Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shapes.md)
                .background(colors.stratumBase)
                .border(spacing.hairlineWidth, colors.hairline, shapes.md)
                .padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Project permission rules", style = type.titleBar, color = colors.inkPrimary)
                    Text(
                        text = "${rules.size} saved auto-approval rule(s)",
                        style = type.bodyCompact,
                        color = colors.inkSecondary,
                    )
                }
                IconButton(
                    onClick = onRefreshRules,
                    modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Refresh permission rules",
                        tint = colors.inkSecondary,
                    )
                }
            }

            if (rules.isEmpty()) {
                Text(
                    text = "No persistent project auto-allow rules active.",
                    style = type.bodyCompact,
                    color = colors.inkMuted,
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
                                text = "${rule.toolKind} · ${rule.commandPrefix.ifBlank { "*" }}",
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
                            enabled = !isMutating,
                            shape = shapes.pill,
                            border = BorderStroke(spacing.hairlineWidth, colors.signalCrimson),
                            modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = 40.dp),
                        ) {
                            Text("Revoke", style = type.labelUi, color = colors.signalCrimson)
                        }
                    }
                }
            }
        }

        // Custom Agents Section
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Custom agents (${agents.size})", style = type.titleBar, color = colors.inkPrimary)
            OutlinedButton(
                onClick = onNewAgent,
                shape = shapes.pill,
                border = BorderStroke(spacing.hairlineWidth, colors.signalCyan),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = colors.signalCyanDim,
                    contentColor = colors.signalCyan,
                ),
                modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = 40.dp),
            ) {
                Icon(imageVector = Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(spacing.xs))
                Text("New agent", style = type.labelUi)
            }
        }

        if (agents.isEmpty()) {
            Text("No custom agents registered on this host.", style = type.bodyCompact, color = colors.inkMuted)
        } else if (isExpanded && agents.size > 1) {
            agents.chunked(2).forEach { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.md),
                    verticalAlignment = Alignment.Top,
                ) {
                    AgentCard(
                        agent = pair[0],
                        isMutating = isMutating,
                        onEdit = onEditAgent,
                        onDelete = onDeleteAgent,
                        modifier = Modifier.weight(1f),
                    )
                    if (pair.size > 1) {
                        AgentCard(
                            agent = pair[1],
                            isMutating = isMutating,
                            onEdit = onEditAgent,
                            onDelete = onDeleteAgent,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        } else {
            agents.forEach { ag ->
                AgentCard(
                    agent = ag,
                    isMutating = isMutating,
                    onEdit = onEditAgent,
                    onDelete = onDeleteAgent,
                )
            }
        }
    }
}

@Composable
private fun AgentCard(
    agent: AgentItemDto,
    isMutating: Boolean,
    onEdit: (AgentItemDto) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shapes.md)
            .background(colors.stratumBase)
            .border(spacing.hairlineWidth, colors.hairline, shapes.md)
            .padding(spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(agent.name, style = type.titleBar, color = colors.inkPrimary)
            Box(
                modifier = Modifier
                    .clip(shapes.pill)
                    .background(if (agent.editable) colors.signalCyanDim else colors.stratumRaised)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    text = if (agent.editable) "Editable" else "Read-only",
                    style = type.labelSmallUi,
                    color = if (agent.editable) colors.signalCyan else colors.inkMuted,
                )
            }
        }
        if (agent.description.isNotBlank()) {
            Text(agent.description, style = type.bodyCompact, color = colors.inkSecondary)
        }
        if (agent.prompt.isNotBlank()) {
            Text(
                text = agent.prompt,
                style = type.monoMicro,
                color = colors.inkMuted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (agent.editable) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm, Alignment.End),
            ) {
                OutlinedButton(
                    onClick = { onEdit(agent) },
                    enabled = !isMutating,
                    shape = shapes.pill,
                    border = BorderStroke(spacing.hairlineWidth, colors.signalCyan),
                    modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = 40.dp),
                ) {
                    Text("Edit", style = type.labelUi, color = colors.signalCyan)
                }
                OutlinedButton(
                    onClick = { onDelete(agent.slug.ifBlank { agent.name }) },
                    enabled = !isMutating,
                    shape = shapes.pill,
                    border = BorderStroke(spacing.hairlineWidth, colors.signalCrimson),
                    modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = 40.dp),
                ) {
                    Text("Delete", style = type.labelUi, color = colors.signalCrimson)
                }
            }
        }
    }
}

// ── Tab 4: System (Preferences, Notifications, Default Model, Hosts) ────────

@Composable
private fun SystemSettingsTabContent(
    previewFeaturesEnabled: Boolean,
    promptCompletionEnabled: Boolean,
    keepaliveEnabled: Boolean,
    notificationPermissionGranted: Boolean,
    availableModels: List<EcosystemModelItemDto>,
    defaultModelId: String?,
    gatewayDefaultModel: String?,
    gatewayDefaultThoughtLevel: String?,
    hosts: List<CachedHost>,
    activeHost: CachedHost?,
    isExpanded: Boolean,
    isMutating: Boolean,
    onToggleSetting: (key: String, enabled: Boolean) -> Unit,
    onSetKeepalive: (Boolean) -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onSetDefaultModel: (model: String, thoughtLevel: String?) -> Unit,
    onSelectHost: (String) -> Unit,
    onReverifyHost: (CachedHost) -> Unit,
    onAddNewHost: () -> Unit,
    onRequestDeleteHost: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = GravityWellTheme.spacing

    if (isExpanded) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
                verticalAlignment = Alignment.Top,
            ) {
                DefaultModelPanel(
                    availableModels = availableModels,
                    defaultModelId = defaultModelId,
                    gatewayDefaultModel = gatewayDefaultModel,
                    gatewayDefaultThoughtLevel = gatewayDefaultThoughtLevel,
                    isMutating = isMutating,
                    onSetDefaultModel = onSetDefaultModel,
                    modifier = Modifier.weight(1f),
                )
                KeepaliveAndNotificationsPanel(
                    keepaliveEnabled = keepaliveEnabled,
                    notificationPermissionGranted = notificationPermissionGranted,
                    onSetKeepalive = onSetKeepalive,
                    onOpenNotificationSettings = onOpenNotificationSettings,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
                verticalAlignment = Alignment.Top,
            ) {
                AllowlistedSettingsPanel(
                    previewFeaturesEnabled = previewFeaturesEnabled,
                    promptCompletionEnabled = promptCompletionEnabled,
                    isMutating = isMutating,
                    onToggleSetting = onToggleSetting,
                    modifier = Modifier.weight(1f),
                )
                HostManagerPanel(
                    hosts = hosts,
                    activeHost = activeHost,
                    onSelectHost = onSelectHost,
                    onReverifyHost = onReverifyHost,
                    onAddNewHost = onAddNewHost,
                    onRequestDeleteHost = onRequestDeleteHost,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    } else {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            DefaultModelPanel(
                availableModels = availableModels,
                defaultModelId = defaultModelId,
                gatewayDefaultModel = gatewayDefaultModel,
                gatewayDefaultThoughtLevel = gatewayDefaultThoughtLevel,
                isMutating = isMutating,
                onSetDefaultModel = onSetDefaultModel,
            )
            KeepaliveAndNotificationsPanel(
                keepaliveEnabled = keepaliveEnabled,
                notificationPermissionGranted = notificationPermissionGranted,
                onSetKeepalive = onSetKeepalive,
                onOpenNotificationSettings = onOpenNotificationSettings,
            )
            AllowlistedSettingsPanel(
                previewFeaturesEnabled = previewFeaturesEnabled,
                promptCompletionEnabled = promptCompletionEnabled,
                isMutating = isMutating,
                onToggleSetting = onToggleSetting,
            )
            HostManagerPanel(
                hosts = hosts,
                activeHost = activeHost,
                onSelectHost = onSelectHost,
                onReverifyHost = onReverifyHost,
                onAddNewHost = onAddNewHost,
                onRequestDeleteHost = onRequestDeleteHost,
            )
        }
    }
}

@Composable
private fun AllowlistedSettingsPanel(
    previewFeaturesEnabled: Boolean,
    promptCompletionEnabled: Boolean,
    isMutating: Boolean,
    onToggleSetting: (key: String, enabled: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shapes.md)
            .background(colors.stratumBase)
            .border(spacing.hairlineWidth, colors.hairline, shapes.md)
            .padding(spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text("Antigravity preferences", style = type.titleBar, color = colors.inkPrimary)

        SettingSwitchRow(
            title = "Preview features",
            subtitle = "Enable experimental features (${ControlDeckValidation.KEY_PREVIEW_FEATURES})",
            checked = previewFeaturesEnabled,
            enabled = !isMutating,
            onCheckedChange = { next ->
                onToggleSetting(ControlDeckValidation.KEY_PREVIEW_FEATURES, next)
            },
        )

        HorizontalDivider(thickness = spacing.hairlineWidth, color = colors.hairline)

        SettingSwitchRow(
            title = "Prompt completion",
            subtitle = "Show inline CLI prompt suggestions (${ControlDeckValidation.KEY_ENABLE_PROMPT_COMPLETION})",
            checked = promptCompletionEnabled,
            enabled = !isMutating,
            onCheckedChange = { next ->
                onToggleSetting(ControlDeckValidation.KEY_ENABLE_PROMPT_COMPLETION, next)
            },
        )

        Text(
            text = "Security and authentication keys stay locked on the host.",
            style = type.labelSmallUi,
            color = colors.inkMuted,
        )
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val spacing = GravityWellTheme.spacing

    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = spacing.touchFloor),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = type.labelUi, color = colors.inkPrimary)
            Text(subtitle, style = type.bodyCompact, color = colors.inkSecondary)
        }
        Spacer(modifier = Modifier.width(spacing.sm))
        Box(
            modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = colors.inkInverse,
                    checkedTrackColor = colors.signalCyan,
                    uncheckedThumbColor = colors.inkSecondary,
                    uncheckedTrackColor = colors.stratumRaised,
                ),
            )
        }
    }
}

@Composable
private fun KeepaliveAndNotificationsPanel(
    keepaliveEnabled: Boolean,
    notificationPermissionGranted: Boolean,
    onSetKeepalive: (Boolean) -> Unit,
    onOpenNotificationSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shapes.md)
            .background(colors.stratumBase)
            .border(spacing.hairlineWidth, colors.hairline, shapes.md)
            .padding(spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Background alerts", style = type.titleBar, color = colors.inkPrimary)
            Box(
                modifier = Modifier
                    .clip(shapes.pill)
                    .background(if (notificationPermissionGranted) colors.signalEmeraldDim else colors.signalAmberDim)
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            ) {
                Text(
                    text = if (notificationPermissionGranted) "Allowed" else "Off",
                    style = type.labelSmallUi,
                    color = if (notificationPermissionGranted) colors.signalEmerald else colors.signalAmber,
                )
            }
        }

        SettingSwitchRow(
            title = "Keep connection alive",
            subtitle = "Stay connected in the background for live approval alerts. Turning off stops background sync immediately.",
            checked = keepaliveEnabled,
            enabled = true,
            onCheckedChange = onSetKeepalive,
        )

        OutlinedButton(
            onClick = onOpenNotificationSettings,
            shape = shapes.pill,
            border = BorderStroke(spacing.hairlineWidth, colors.hairlineStrong),
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = spacing.touchFloor),
        ) {
            Icon(
                imageVector = Icons.Rounded.Notifications,
                contentDescription = null,
                tint = colors.signalCyan,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(spacing.xs))
            Text(
                text = "System notification settings",
                style = type.labelUi,
                color = colors.inkPrimary,
            )
        }
    }
}

@Composable
private fun DefaultModelPanel(
    availableModels: List<EcosystemModelItemDto>,
    defaultModelId: String?,
    gatewayDefaultModel: String?,
    gatewayDefaultThoughtLevel: String?,
    isMutating: Boolean,
    onSetDefaultModel: (model: String, thoughtLevel: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    val families = remember(availableModels, defaultModelId, gatewayDefaultModel) {
        buildList {
            availableModels.map { it.family.ifBlank { it.modelId } }.forEach { add(it) }
            if (!gatewayDefaultModel.isNullOrBlank()) add(gatewayDefaultModel)
            if (!defaultModelId.isNullOrBlank()) add(defaultModelId)
            if (isEmpty()) {
                addAll(listOf("gemini-4-neon", "gemini-3.8-flash", "claude-sonnet-5-5"))
            }
        }.distinct()
    }
    val activeFamily = gatewayDefaultModel ?: defaultModelId ?: families.firstOrNull().orEmpty()
    val activeThought = gatewayDefaultThoughtLevel ?: "high"

    val thoughtOptions = listOf(
        "low" to "Low",
        "medium" to "Medium",
        "high" to "High",
        "xhigh" to "Max",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shapes.md)
            .background(colors.stratumBase)
            .border(spacing.hairlineWidth, colors.hairline, shapes.md)
            .padding(spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text("Default model", style = type.titleBar, color = colors.inkPrimary)
        Text(
            text = "Used automatically when starting new chats.",
            style = type.bodyCompact,
            color = colors.inkSecondary,
        )

        families.take(6).forEach { fam ->
            val selected = fam == activeFamily
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
                    .clickable(enabled = !isMutating) { onSetDefaultModel(fam, activeThought) }
                    .padding(horizontal = spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = formatModelDisplayName(fam),
                    style = type.bodyCompact,
                    color = if (selected) colors.signalCyan else colors.inkPrimary,
                )
                if (selected) {
                    Text("Default", style = type.labelSmallUi, color = colors.signalCyan)
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shapes.pill)
                .background(colors.stratumRaised)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            thoughtOptions.forEach { (lvl, label) ->
                val selected = activeThought.lowercase() == lvl
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 40.dp)
                        .clip(shapes.pill)
                        .background(if (selected) colors.signalCyanDim else colors.stratumRaised)
                        .clickable(enabled = !isMutating && activeFamily.isNotBlank()) {
                            onSetDefaultModel(activeFamily, lvl)
                        },
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
}

@Composable
private fun HostManagerPanel(
    hosts: List<CachedHost>,
    activeHost: CachedHost?,
    onSelectHost: (String) -> Unit,
    onReverifyHost: (CachedHost) -> Unit,
    onAddNewHost: () -> Unit,
    onRequestDeleteHost: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shapes.md)
            .background(colors.stratumBase)
            .border(spacing.hairlineWidth, colors.hairline, shapes.md)
            .padding(spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Connected hosts (${hosts.size})", style = type.titleBar, color = colors.inkPrimary)
            OutlinedButton(
                onClick = onAddNewHost,
                shape = shapes.pill,
                border = BorderStroke(spacing.hairlineWidth, colors.signalCyan),
                modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = 40.dp),
            ) {
                Icon(imageVector = Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(spacing.xs))
                Text("Add host", style = type.labelUi, color = colors.signalCyan)
            }
        }

        hosts.forEach { host ->
            val isActive = host.hostId == activeHost?.hostId
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shapes.sm)
                    .background(if (isActive) colors.signalCyanDim else colors.stratumRaised)
                    .border(
                        spacing.hairlineWidth,
                        if (isActive) colors.signalCyan else colors.hairline,
                        shapes.sm,
                    )
                    .padding(spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${host.label} · v${host.agyVersion ?: "unknown"}",
                            style = type.labelUi,
                            color = colors.inkPrimary,
                        )
                        Text(
                            text = host.baseUrl,
                            style = type.monoMicro,
                            color = colors.inkSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (isActive) {
                        Box(
                            modifier = Modifier
                                .clip(shapes.pill)
                                .background(colors.signalEmeraldDim)
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            Text("Active", style = type.labelSmallUi, color = colors.signalEmerald)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs, Alignment.End),
                ) {
                    if (!isActive) {
                        OutlinedButton(
                            onClick = { onSelectHost(host.hostId) },
                            shape = shapes.pill,
                            border = BorderStroke(spacing.hairlineWidth, colors.signalCyan),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = 40.dp),
                        ) {
                            Text("Switch", style = type.labelSmallUi, color = colors.signalCyan)
                        }
                    }
                    OutlinedButton(
                        onClick = { onReverifyHost(host) },
                        shape = shapes.pill,
                        border = BorderStroke(spacing.hairlineWidth, colors.hairlineStrong),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = 40.dp),
                    ) {
                        Text("Re-verify", style = type.labelSmallUi, color = colors.inkPrimary)
                    }
                    OutlinedButton(
                        onClick = { onRequestDeleteHost(host.hostId) },
                        shape = shapes.pill,
                        border = BorderStroke(spacing.hairlineWidth, colors.signalCrimson),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = 40.dp),
                    ) {
                        Text("Remove", style = type.labelSmallUi, color = colors.signalCrimson)
                    }
                }
            }
        }
    }
}
