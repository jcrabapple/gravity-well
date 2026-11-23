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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
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

        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            // ── Header + 4-Segment Sub-Tab Strip ─────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.stratumBase)
                    .padding(horizontal = spacing.lg, vertical = spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("CONTROL DECK · ECOSYSTEM", style = type.labelTelemetry, color = colors.signalCyan)
                        Text(
                            text = activeHost?.label ?: "Host Switchboard",
                            style = type.headlineSection,
                            color = colors.inkPrimary,
                        )
                    }
                    OutlinedButton(
                        onClick = onRefreshAll,
                        shape = shapes.sm,
                        border = BorderStroke(spacing.hairlineWidth, colors.hairlineStrong),
                        contentPadding = PaddingValues(horizontal = spacing.md, vertical = 0.dp),
                        modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                    ) {
                        if (state.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = colors.signalCyan,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh Control Deck",
                                tint = colors.signalCyan,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(spacing.xs))
                        Text("SYNC", style = type.labelTelemetry, color = colors.inkPrimary)
                    }
                }

                // 4 Sub-Tabs: MCP | PLUGINS | AGENTS & RULES | SYSTEM
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shapes.sm)
                        .border(spacing.hairlineWidth, colors.hairlineStrong, shapes.sm),
                ) {
                    ControlDeckTab.entries.forEach { tab ->
                        val selected = tab == state.activeTab
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = spacing.touchFloor)
                                .background(if (selected) colors.signalCyanDim else colors.stratumRaised)
                                .clickable(role = Role.Tab) { onSelectTab(tab) }
                                .padding(horizontal = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = tab.label,
                                style = type.labelTelemetry,
                                color = if (selected) colors.signalCyan else colors.inkSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            HorizontalDivider(thickness = spacing.hairlineWidth, color = colors.hairline)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.lg, vertical = spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
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
            }
        }
    }

    // ── Sheets & Destructive Confirm Interlocks ──────────────────────────────
    if (state.showAddMcpSheet) {
        AddMcpServerSheet(
            form = state.addMcpForm,
            isMutating = state.isMutating,
            onUpdateForm = onUpdateAddMcpForm,
            onSubmit = onSubmitAddMcp,
            onDismiss = onDismissAddMcpSheet,
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
            title = "REMOVE MCP SERVER",
            detail = "Runs `agy mcp remove ${state.pendingRemoveMcpName}` on the host.",
            confirmLabel = "REMOVE MCP",
            isBusy = state.isMutating,
            onConfirm = onConfirmRemoveMcp,
            onDismiss = onCancelRemoveMcp,
        )
    }

    if (state.pendingUninstallPluginName != null) {
        DestructiveActionConfirmSheet(
            title = "UNINSTALL PLUGIN",
            detail = "Runs `agy plugin uninstall ${state.pendingUninstallPluginName}` on the host.",
            confirmLabel = "UNINSTALL",
            isBusy = state.isMutating,
            onConfirm = onConfirmUninstallPlugin,
            onDismiss = onCancelUninstallPlugin,
        )
    }

    if (state.pendingDeleteAgentSlug != null) {
        DestructiveActionConfirmSheet(
            title = "DELETE CUSTOM AGENT",
            detail = "Deletes `~/.gemini/antigravity-cli/agents/${state.pendingDeleteAgentSlug}.md`.",
            confirmLabel = "DELETE AGENT",
            isBusy = state.isMutating,
            onConfirm = onConfirmDeleteAgent,
            onDismiss = onCancelDeleteAgent,
        )
    }

    if (state.pendingDeleteHostId != null) {
        val targetLabel = hosts.firstOrNull { it.hostId == state.pendingDeleteHostId }?.label
            ?: state.pendingDeleteHostId
        DestructiveActionConfirmSheet(
            title = "REMOVE GATEWAY HOST",
            detail = "Deletes '$targetLabel' cached metadata from SQLite and wipes its encrypted bearer token from AndroidKeyStore.",
            confirmLabel = "REMOVE HOST",
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
            .padding(horizontal = spacing.md, vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = message,
            style = type.monoCode,
            color = fg,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
        ) {
            Icon(
                imageVector = Icons.Default.Close,
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
                text = "MCP SERVERS (${servers.size})",
                style = type.labelTelemetry,
                color = colors.inkSecondary,
            )
            OutlinedButton(
                onClick = onOpenAdd,
                shape = shapes.sm,
                border = BorderStroke(spacing.hairlineWidth, colors.signalCyan),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = colors.signalCyanDim,
                    contentColor = colors.signalCyan,
                ),
                modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(spacing.xs))
                Text("ADD MCP", style = type.labelTelemetry)
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
                    text = "No MCP servers configured on this host (`agy mcp list`).",
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
                        onRequestRemove = onRequestRemove,
                        modifier = Modifier.weight(1f),
                    )
                    if (pair.size > 1) {
                        McpServerCard(
                            server = pair[1],
                            isMutating = isMutating,
                            onToggleEnabled = onToggleEnabled,
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
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
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
                        .clip(shapes.sm)
                        .background(colors.stratumRaised)
                        .border(spacing.hairlineWidth, colors.hairlineStrong, shapes.sm)
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = server.type.uppercase(),
                        style = type.monoMicro,
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
                            .clip(shapes.sm)
                            .background(colors.stratumWell)
                            .border(spacing.hairlineWidth, colors.hairline, shapes.sm)
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(keyTag, style = type.monoMicro, color = colors.inkSecondary)
                        Box(
                            modifier = Modifier
                                .clip(shapes.xs)
                                .background(colors.signalVioletDim)
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        ) {
                            Text("[REDACTED]", style = type.monoMicro, color = colors.signalViolet)
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            OutlinedButton(
                onClick = { onRequestRemove(server.name) },
                enabled = !isMutating,
                shape = shapes.sm,
                border = BorderStroke(spacing.hairlineWidth, colors.signalCrimson),
                modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
            ) {
                Text("REMOVE", style = type.labelTelemetry, color = colors.signalCrimson)
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
        // Validate & Install Bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shapes.md)
                .background(colors.stratumBase)
                .border(spacing.hairlineWidth, colors.hairline, shapes.md)
                .padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text("PLUGIN VALIDATE & INSTALL", style = type.labelTelemetry, color = colors.signalCyan)
            OutlinedTextField(
                value = targetDraft,
                onValueChange = onTargetChange,
                singleLine = true,
                textStyle = type.monoCode,
                placeholder = {
                    Text(
                        text = "Workspace plugin directory or https:// URL",
                        style = type.bodyCompact,
                        color = colors.inkMuted,
                    )
                },
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
                    shape = shapes.sm,
                    border = BorderStroke(spacing.hairlineWidth, colors.signalCyan),
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = spacing.touchFloor),
                ) {
                    Text("VALIDATE", style = type.labelTelemetry, color = colors.signalCyan)
                }
                Button(
                    onClick = onInstall,
                    enabled = !isMutating,
                    shape = shapes.sm,
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
                    Text("INSTALL", style = type.labelTelemetry)
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
                        text = if (validationResult.valid) "VALIDATION PASSED" else "VALIDATION FAILED",
                        style = type.labelTelemetry,
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
                Text(installMessage, style = type.monoMicro, color = colors.signalEmerald)
            }
        }

        Text("INSTALLED & BUILTIN PLUGINS (${plugins.size})", style = type.labelTelemetry, color = colors.inkSecondary)

        if (plugins.isEmpty()) {
            Text("No plugins reported by `agy plugin list --json`.", style = type.bodyCompact, color = colors.inkMuted)
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
            .padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
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
                                .clip(shapes.sm)
                                .background(colors.signalCyanDim)
                                .border(spacing.hairlineWidth, colors.signalCyan, shapes.sm)
                                .padding(horizontal = 5.dp, vertical = 2.dp),
                        ) {
                            Text("BUILTIN", style = type.monoMicro, color = colors.signalCyan)
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
                    shape = shapes.sm,
                    border = BorderStroke(spacing.hairlineWidth, colors.signalCrimson),
                    modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                ) {
                    Text("UNINSTALL", style = type.labelTelemetry, color = colors.signalCrimson)
                }
            }
        }
    }
}

// ── Tab 3: Agents & Workspace Allow-Rules ────────────────────────────────────

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
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        // Workspace Allow-Rules Safety Table (DIRECTION Section 8.3 & SPEC D7)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shapes.md)
                .background(colors.stratumBase)
                .border(spacing.hairlineWidth, colors.hairline, shapes.md)
                .padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("WORKSPACE ALLOW-RULES", style = type.labelTelemetry, color = colors.signalAmber)
                    Text(
                        text = "${rules.size} active auto-approval rules",
                        style = type.monoMicro,
                        color = colors.inkSecondary,
                    )
                }
                IconButton(
                    onClick = onRefreshRules,
                    modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh allow-rules",
                        tint = colors.inkSecondary,
                    )
                }
            }

            if (rules.isEmpty()) {
                Text(
                    text = "Zero persistent workspace allow-rules active on this host.",
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
                            .border(spacing.hairlineWidth, colors.hairline, shapes.sm)
                            .padding(spacing.sm),
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
                            )
                        }
                        Spacer(modifier = Modifier.width(spacing.sm))
                        OutlinedButton(
                            onClick = { onRevokeRule(rule.ruleId) },
                            enabled = !isMutating,
                            shape = shapes.sm,
                            border = BorderStroke(spacing.hairlineWidth, colors.signalCrimson),
                            modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                        ) {
                            Text("REVOKE", style = type.labelTelemetry, color = colors.signalCrimson)
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
            Text("CUSTOM AGENTS (${agents.size})", style = type.labelTelemetry, color = colors.inkSecondary)
            OutlinedButton(
                onClick = onNewAgent,
                shape = shapes.sm,
                border = BorderStroke(spacing.hairlineWidth, colors.signalCyan),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = colors.signalCyanDim,
                    contentColor = colors.signalCyan,
                ),
                modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(spacing.xs))
                Text("NEW AGENT", style = type.labelTelemetry)
            }
        }

        if (agents.isEmpty()) {
            Text("No custom or host agents registered.", style = type.bodyCompact, color = colors.inkMuted)
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
            .padding(spacing.md),
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
                    .clip(shapes.sm)
                    .background(if (agent.editable) colors.signalCyanDim else colors.stratumRaised)
                    .border(
                        spacing.hairlineWidth,
                        if (agent.editable) colors.signalCyan else colors.hairlineStrong,
                        shapes.sm,
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = if (agent.editable) "EDITABLE (${agent.source})" else "READ-ONLY (${agent.source})",
                    style = type.monoMicro,
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
                    shape = shapes.sm,
                    border = BorderStroke(spacing.hairlineWidth, colors.signalCyan),
                    modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                ) {
                    Text("EDIT", style = type.labelTelemetry, color = colors.signalCyan)
                }
                OutlinedButton(
                    onClick = { onDelete(agent.slug.ifBlank { agent.name }) },
                    enabled = !isMutating,
                    shape = shapes.sm,
                    border = BorderStroke(spacing.hairlineWidth, colors.signalCrimson),
                    modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                ) {
                    Text("DELETE", style = type.labelTelemetry, color = colors.signalCrimson)
                }
            }
        }
    }
}

// ── Tab 4: System (Allowlisted Settings, Keepalive, Default Model, Hosts) ───

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
                AllowlistedSettingsPanel(
                    previewFeaturesEnabled = previewFeaturesEnabled,
                    promptCompletionEnabled = promptCompletionEnabled,
                    isMutating = isMutating,
                    onToggleSetting = onToggleSetting,
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
                DefaultModelPanel(
                    availableModels = availableModels,
                    defaultModelId = defaultModelId,
                    gatewayDefaultModel = gatewayDefaultModel,
                    gatewayDefaultThoughtLevel = gatewayDefaultThoughtLevel,
                    isMutating = isMutating,
                    onSetDefaultModel = onSetDefaultModel,
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
            AllowlistedSettingsPanel(
                previewFeaturesEnabled = previewFeaturesEnabled,
                promptCompletionEnabled = promptCompletionEnabled,
                isMutating = isMutating,
                onToggleSetting = onToggleSetting,
            )
            KeepaliveAndNotificationsPanel(
                keepaliveEnabled = keepaliveEnabled,
                notificationPermissionGranted = notificationPermissionGranted,
                onSetKeepalive = onSetKeepalive,
                onOpenNotificationSettings = onOpenNotificationSettings,
            )
            DefaultModelPanel(
                availableModels = availableModels,
                defaultModelId = defaultModelId,
                gatewayDefaultModel = gatewayDefaultModel,
                gatewayDefaultThoughtLevel = gatewayDefaultThoughtLevel,
                isMutating = isMutating,
                onSetDefaultModel = onSetDefaultModel,
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
            .padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text("ALLOWLISTED SETTINGS.JSON", style = type.labelTelemetry, color = colors.signalCyan)

        SettingSwitchRow(
            keyLabel = ControlDeckValidation.KEY_PREVIEW_FEATURES,
            subtitle = "Enable experimental Antigravity preview features",
            checked = previewFeaturesEnabled,
            enabled = !isMutating,
            onCheckedChange = { next ->
                onToggleSetting(ControlDeckValidation.KEY_PREVIEW_FEATURES, next)
            },
        )

        HorizontalDivider(thickness = spacing.hairlineWidth, color = colors.hairline)

        SettingSwitchRow(
            keyLabel = ControlDeckValidation.KEY_ENABLE_PROMPT_COMPLETION,
            subtitle = "Enable inline CLI prompt completion suggestions",
            checked = promptCompletionEnabled,
            enabled = !isMutating,
            onCheckedChange = { next ->
                onToggleSetting(ControlDeckValidation.KEY_ENABLE_PROMPT_COMPLETION, next)
            },
        )

        Text(
            text = "security.* and auth keys are host-locked and never exposed.",
            style = type.monoMicro,
            color = colors.inkMuted,
        )
    }
}

@Composable
private fun SettingSwitchRow(
    keyLabel: String,
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
        Column(modifier = Modifier.weight(1f)) {
            Text(keyLabel, style = type.monoCode, color = colors.inkPrimary)
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
            .padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("BACKGROUND KEEPALIVE & NOTIFICATIONS", style = type.labelTelemetry, color = colors.signalCyan)
            Box(
                modifier = Modifier
                    .clip(shapes.sm)
                    .background(if (notificationPermissionGranted) colors.signalEmeraldDim else colors.signalAmberDim)
                    .border(
                        spacing.hairlineWidth,
                        if (notificationPermissionGranted) colors.signalEmerald else colors.signalAmber,
                        shapes.sm,
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = if (notificationPermissionGranted) "PERMISSION OK" else "PERMISSION OFF",
                    style = type.monoMicro,
                    color = if (notificationPermissionGranted) colors.signalEmerald else colors.signalAmber,
                )
            }
        }

        SettingSwitchRow(
            keyLabel = "Background Keepalive (dataSync)",
            subtitle = "Maintains watched session stream & background approval alerts; toggling off stops service and cancels notifications immediately",
            checked = keepaliveEnabled,
            enabled = true,
            onCheckedChange = onSetKeepalive,
        )

        OutlinedButton(
            onClick = onOpenNotificationSettings,
            shape = shapes.sm,
            border = BorderStroke(spacing.hairlineWidth, colors.hairlineStrong),
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = spacing.touchFloor),
        ) {
            Icon(
                imageVector = Icons.Default.Notifications,
                contentDescription = null,
                tint = colors.signalCyan,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(spacing.xs))
            Text(
                text = "Open System Notification Settings",
                style = type.labelTelemetry,
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

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shapes.md)
            .background(colors.stratumBase)
            .border(spacing.hairlineWidth, colors.hairline, shapes.md)
            .padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text("GATEWAY DEFAULT MODEL", style = type.labelTelemetry, color = colors.inkSecondary)
        Text(
            text = "Applied to new sessions (`~/.config/gravity-well/gateway_prefs.json`).",
            style = type.bodyCompact,
            color = colors.inkMuted,
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
                    text = fam,
                    style = type.monoCode,
                    color = if (selected) colors.signalCyan else colors.inkPrimary,
                )
                if (selected) {
                    Text("ACTIVE", style = type.monoMicro, color = colors.signalCyan)
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shapes.sm)
                .border(spacing.hairlineWidth, colors.hairlineStrong, shapes.sm),
        ) {
            listOf("low", "medium", "high", "xhigh").forEach { lvl ->
                val selected = activeThought.lowercase() == lvl
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = spacing.touchFloor)
                        .background(if (selected) colors.signalCyanDim else colors.stratumRaised)
                        .clickable(enabled = !isMutating && activeFamily.isNotBlank()) {
                            onSetDefaultModel(activeFamily, lvl)
                        },
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
            .padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("HOST MANAGER (${hosts.size})", style = type.labelTelemetry, color = colors.inkSecondary)
            OutlinedButton(
                onClick = onAddNewHost,
                shape = shapes.sm,
                border = BorderStroke(spacing.hairlineWidth, colors.signalCyan),
                modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(spacing.xs))
                Text("ADD HOST", style = type.labelTelemetry, color = colors.signalCyan)
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
                    .padding(spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${host.label} · agy v${host.agyVersion ?: "unknown"}",
                            style = type.titleBar,
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
                        Text("ACTIVE", style = type.labelTelemetry, color = colors.signalEmerald)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs, Alignment.End),
                ) {
                    if (!isActive) {
                        OutlinedButton(
                            onClick = { onSelectHost(host.hostId) },
                            shape = shapes.sm,
                            border = BorderStroke(spacing.hairlineWidth, colors.signalCyan),
                            modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                        ) {
                            Text("SWITCH", style = type.labelTelemetry, color = colors.signalCyan)
                        }
                    }
                    OutlinedButton(
                        onClick = { onReverifyHost(host) },
                        shape = shapes.sm,
                        border = BorderStroke(spacing.hairlineWidth, colors.hairlineStrong),
                        modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                    ) {
                        Text("RE-VERIFY", style = type.labelTelemetry, color = colors.inkPrimary)
                    }
                    OutlinedButton(
                        onClick = { onRequestDeleteHost(host.hostId) },
                        shape = shapes.sm,
                        border = BorderStroke(spacing.hairlineWidth, colors.signalCrimson),
                        modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                    ) {
                        Text("REMOVE", style = type.labelTelemetry, color = colors.signalCrimson)
                    }
                }
            }
        }
    }
}
