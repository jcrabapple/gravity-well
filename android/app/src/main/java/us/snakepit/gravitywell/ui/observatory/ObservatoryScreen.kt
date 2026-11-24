package us.snakepit.gravitywell.ui.observatory

import java.util.Locale
import kotlinx.coroutines.delay
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import us.snakepit.gravitywell.theme.GravityWellTheme
import us.snakepit.gravitywell.ui.sessions.ExternalSessionPresenceDto
import us.snakepit.gravitywell.ui.sessions.basenameOfPath
import us.snakepit.gravitywell.ui.shell.AdaptiveShellBreakpoints

@Composable
fun ObservatoryScreen(
    state: ObservatoryUiState,
    onRefreshAll: () -> Unit,
    onRefreshUsageBypassCache: () -> Unit,
    onUpdateDaemonNameDraft: (String) -> Unit,
    onSetDaemonSessionScoped: (Boolean) -> Unit,
    onStartDaemon: () -> Unit,
    onStopDaemon: () -> Unit,
    onOpenUpdateConfirmSheet: () -> Unit,
    onDismissUpdateConfirmSheet: () -> Unit,
    onConfirmAndRunUpdate: () -> Unit,
    onSelectLogSource: (LogSourceTab) -> Unit,
    onSelectCrashFile: (String) -> Unit,
    onSelectLogTail: (Int) -> Unit,
    onUpdateLogSearchQuery: (String) -> Unit,
    onFetchLogsNow: () -> Unit,
    onSetShowExpandedLogSheet: (Boolean) -> Unit,
    onOpenOrResumeExternalSession: (ExternalSessionPresenceDto) -> Unit,
    onDismissErrorBanner: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    // Live 1Hz clock driving tabular reset countdowns while visible
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    var daemonExpanded by remember { mutableStateOf(false) }
    var logsExpanded by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(colors.chassisVoid),
    ) {
        val isExpanded = maxWidth.value.toInt() >= AdaptiveShellBreakpoints.EXPANDED_WIDTH_DP

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.lg, vertical = spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            // ── Compact Subtitle & Refresh Pill ──────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = state.hostLabel.ifBlank { "Host overview" },
                    style = type.bodyCompact,
                    color = colors.inkSecondary,
                )
                OutlinedButton(
                    onClick = onRefreshAll,
                    shape = shapes.pill,
                    border = BorderStroke(spacing.hairlineWidth, colors.hairlineStrong),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                    modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = 40.dp),
                ) {
                    if (state.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = colors.signalCyan,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Refresh status",
                            tint = colors.signalCyan,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Refresh", style = type.labelUi, color = colors.inkPrimary)
                }
            }

            // ── Error Banner ─────────────────────────────────────────────────
            if (!state.errorBanner.isNullOrBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
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
                        )
                    }
                }
            }

            // ── 1. Usage & Quotas (Front & Center) ──────────────────────────
            QuotaGaugesSection(
                usage = state.usage,
                isRefreshingUsage = state.isRefreshingUsage,
                nowMillis = nowMillis,
                isExpanded = isExpanded,
                onRefreshBypassCache = onRefreshUsageBypassCache,
            )

            // ── 2. Active Host Sessions (FLOCK-verified) ────────────────────
            ExternalRadarSection(
                status = state.status,
                onOpenOrResumeSession = onOpenOrResumeExternalSession,
            )

            // ── 3. Host Health (2x2 on <600dp, 4x1 on >=600dp) ──────────────
            HostVitalsGridSection(
                status = state.status,
                isExpanded = isExpanded,
            )

            // ── 4. Host Tools (Updater, Remote-Control Daemon, Logs) ────────
            Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                Text("Host tools", style = type.titleBar, color = colors.inkPrimary)

                if (isExpanded) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.md),
                        verticalAlignment = Alignment.Top,
                    ) {
                        CliUpdaterPanel(
                            updater = state.status?.updater,
                            lastUpdateResult = state.lastUpdateResult,
                            isUpdating = state.isUpdatingCli,
                            onRequestUpdateConfirm = onOpenUpdateConfirmSheet,
                            modifier = Modifier.weight(1f),
                        )
                        RemoteControlDaemonPanel(
                            remoteControl = state.status?.remoteControl,
                            nameDraft = state.daemonNameDraft,
                            sessionScoped = state.daemonSessionScoped,
                            isBusy = state.isDaemonBusy,
                            expanded = daemonExpanded,
                            onToggleExpanded = { daemonExpanded = !daemonExpanded },
                            onNameDraftChange = onUpdateDaemonNameDraft,
                            onSessionScopedChange = onSetDaemonSessionScoped,
                            onStart = onStartDaemon,
                            onStop = onStopDaemon,
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    CliUpdaterPanel(
                        updater = state.status?.updater,
                        lastUpdateResult = state.lastUpdateResult,
                        isUpdating = state.isUpdatingCli,
                        onRequestUpdateConfirm = onOpenUpdateConfirmSheet,
                    )
                    RemoteControlDaemonPanel(
                        remoteControl = state.status?.remoteControl,
                        nameDraft = state.daemonNameDraft,
                        sessionScoped = state.daemonSessionScoped,
                        isBusy = state.isDaemonBusy,
                        expanded = daemonExpanded,
                        onToggleExpanded = { daemonExpanded = !daemonExpanded },
                        onNameDraftChange = onUpdateDaemonNameDraft,
                        onSessionScopedChange = onSetDaemonSessionScoped,
                        onStart = onStartDaemon,
                        onStop = onStopDaemon,
                    )
                }

                // 5. Searchable `cli.log` & `crashes/` Viewer (Collapsible)
                ObservatoryLogsPanel(
                    source = state.logSource,
                    activeFile = state.activeLogFile,
                    availableCrashFiles = state.availableCrashFiles,
                    selectedCrashFile = state.selectedCrashFile,
                    searchQuery = state.logSearchQuery,
                    tailLines = state.logTailLines,
                    lines = state.logLines,
                    totalMatched = state.logTotalMatched,
                    isLoading = state.isLogsLoading,
                    expanded = logsExpanded,
                    onToggleExpanded = { logsExpanded = !logsExpanded },
                    onSelectSource = onSelectLogSource,
                    onSelectCrashFile = onSelectCrashFile,
                    onSelectTail = onSelectLogTail,
                    onSearchQueryChange = onUpdateLogSearchQuery,
                    onRefreshLogs = onFetchLogsNow,
                    onExpandSheet = { onSetShowExpandedLogSheet(true) },
                )
            }

            Spacer(modifier = Modifier.height(spacing.md))
        }
    }

    // ── Mandatory 2-Step `agy update` Confirmation Sheet ─────────────────────
    if (state.showUpdateConfirmSheet) {
        ConfirmAgyUpdateSheet(
            currentVersion = state.status?.updater?.agyVersion.orEmpty(),
            updaterMessage = state.status?.updater?.message.orEmpty(),
            isUpdating = state.isUpdatingCli,
            onConfirmRun = onConfirmAndRunUpdate,
            onDismiss = onDismissUpdateConfirmSheet,
        )
    }

    // ── Full-Height Searchable Log / Crash Sheet ─────────────────────────────
    if (state.showExpandedLogSheet) {
        ExpandedLogViewerSheet(
            source = state.logSource,
            activeFile = state.activeLogFile,
            availableCrashFiles = state.availableCrashFiles,
            selectedCrashFile = state.selectedCrashFile,
            searchQuery = state.logSearchQuery,
            tailLines = state.logTailLines,
            lines = state.logLines,
            totalMatched = state.logTotalMatched,
            isLoading = state.isLogsLoading,
            onSelectSource = onSelectLogSource,
            onSelectCrashFile = onSelectCrashFile,
            onSelectTail = onSelectLogTail,
            onSearchQueryChange = onUpdateLogSearchQuery,
            onRefreshLogs = onFetchLogsNow,
            onDismiss = { onSetShowExpandedLogSheet(false) },
        )
    }
}

@Composable
private fun HostVitalsGridSection(
    status: ObservatoryFullStatusDto?,
    isExpanded: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val spacing = GravityWellTheme.spacing

    val vitals = status?.host
    val cpuHeadline = if (vitals != null) {
        String.format(Locale.US, "%.1f%%", vitals.cpuPercent)
    } else {
        "—"
    }
    val cpuSub = if (vitals != null) {
        String.format(Locale.US, "Load %.2f · %.2f", vitals.loadAvg1m, vitals.loadAvg5m)
    } else {
        "Load average"
    }
    val ramHeadline = if (vitals != null) {
        ObservatoryTelemetryMath.formatRamReadout(vitals.ramUsedMb, vitals.ramTotalMb)
    } else {
        "—"
    }
    val ramPct = if (vitals != null && vitals.ramTotalMb > 0L) {
        String.format(Locale.US, "%.0f%% used", (vitals.ramUsedMb.toDouble() / vitals.ramTotalMb.toDouble()) * 100.0)
    } else {
        "Memory"
    }
    val diskHeadline = if (vitals != null) {
        ObservatoryTelemetryMath.formatDiskReadout(vitals.diskUsedGb, vitals.diskTotalGb)
    } else {
        "—"
    }
    val diskPct = if (vitals != null && vitals.diskTotalGb > 0.0) {
        String.format(Locale.US, "%.0f%% used", (vitals.diskUsedGb / vitals.diskTotalGb) * 100.0)
    } else {
        "Storage"
    }
    val radarCount = status?.activeExternalCount ?: 0
    val staleIgnored = status?.staleLockCount ?: 0
    val radarSub = if (staleIgnored > 0) {
        "Active ($staleIgnored stale ignored)"
    } else {
        "Active on host"
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text("Host health", style = type.titleBar, color = colors.inkPrimary)
        if (isExpanded) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                VitalsMetricTile("CPU", cpuHeadline, cpuSub, Modifier.weight(1f))
                VitalsMetricTile("Memory", ramHeadline, ramPct, Modifier.weight(1f))
                VitalsMetricTile("Storage", diskHeadline, diskPct, Modifier.weight(1f))
                VitalsMetricTile(
                    label = "Host sessions",
                    headline = radarCount.toString(),
                    subtitle = radarSub,
                    modifier = Modifier.weight(1f),
                    accentColor = if (radarCount > 0) colors.signalCyan else colors.inkPrimary,
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    VitalsMetricTile("CPU", cpuHeadline, cpuSub, Modifier.weight(1f))
                    VitalsMetricTile("Memory", ramHeadline, ramPct, Modifier.weight(1f))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    VitalsMetricTile("Storage", diskHeadline, diskPct, Modifier.weight(1f))
                    VitalsMetricTile(
                        label = "Host sessions",
                        headline = radarCount.toString(),
                        subtitle = radarSub,
                        modifier = Modifier.weight(1f),
                        accentColor = if (radarCount > 0) colors.signalCyan else colors.inkPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun VitalsMetricTile(
    label: String,
    headline: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    accentColor: Color? = null,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    Column(
        modifier = modifier
            .clip(shapes.md)
            .background(colors.stratumBase)
            .border(spacing.hairlineWidth, colors.hairline, shapes.md)
            .padding(spacing.lg),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = type.labelSmallUi, color = colors.inkSecondary, maxLines = 1)
        Text(
            text = headline,
            style = type.displayMetric,
            color = accentColor ?: colors.inkPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = subtitle,
            style = type.labelSmallUi,
            color = colors.inkMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun QuotaGaugesSection(
    usage: ObservatoryUsageResponseDto?,
    isRefreshingUsage: Boolean,
    nowMillis: Long,
    isExpanded: Boolean,
    onRefreshBypassCache: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    Column(
        modifier = modifier.fillMaxWidth(),
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
                Text("Usage & quota", style = type.titleBar, color = colors.inkPrimary)
                if (usage?.stale == true) {
                    Box(
                        modifier = Modifier
                            .clip(shapes.pill)
                            .background(colors.signalAmberDim)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text("Stale", style = type.labelSmallUi, color = colors.signalAmber)
                    }
                }
            }

            OutlinedButton(
                onClick = onRefreshBypassCache,
                enabled = !isRefreshingUsage,
                shape = shapes.pill,
                border = BorderStroke(spacing.hairlineWidth, colors.signalCyan),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = 40.dp),
            ) {
                if (isRefreshingUsage) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = colors.signalCyan,
                    )
                    Spacer(modifier = Modifier.width(spacing.xs))
                }
                Text("Check quota", style = type.labelUi, color = colors.signalCyan)
            }
        }

        val groups = usage?.groups.orEmpty()
        if (groups.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shapes.md)
                    .background(colors.stratumBase)
                    .border(spacing.hairlineWidth, colors.hairline, shapes.md)
                    .padding(spacing.lg),
            ) {
                Text(
                    text = "No usage data cached yet. Tap Check quota to fetch your current Antigravity limits.",
                    style = type.bodyCompact,
                    color = colors.inkMuted,
                )
            }
        } else if (isExpanded && groups.size > 1) {
            groups.chunked(2).forEach { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.md),
                    verticalAlignment = Alignment.Top,
                ) {
                    QuotaGroupCard(
                        group = pair[0],
                        nowMillis = nowMillis,
                        modifier = Modifier.weight(1f),
                    )
                    if (pair.size > 1) {
                        QuotaGroupCard(
                            group = pair[1],
                            nowMillis = nowMillis,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        } else {
            groups.forEach { group ->
                QuotaGroupCard(group = group, nowMillis = nowMillis)
            }
        }
    }
}

@Composable
private fun QuotaGroupCard(
    group: UsageQuotaGroupDto,
    nowMillis: Long,
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
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = group.name.ifBlank { "Model Group" },
                style = type.titleBar,
                color = colors.inkPrimary,
            )
            if (group.description.isNotBlank()) {
                Text(
                    text = group.description,
                    style = type.bodyCompact,
                    color = colors.inkSecondary,
                )
            }
        }

        group.buckets.forEachIndexed { idx, bucket ->
            if (idx > 0) {
                HorizontalDivider(thickness = spacing.hairlineWidth, color = colors.hairline)
            }
            QuotaBucketFuelGauge(bucket = bucket, nowMillis = nowMillis)
        }
    }
}

@Composable
fun QuotaBucketFuelGauge(
    bucket: UsageQuotaBucketDto,
    nowMillis: Long,
    modifier: Modifier = Modifier,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    val pct = if (bucket.remainingPercent > 0.0 || bucket.remainingFraction <= 0.0) {
        bucket.remainingPercent
    } else {
        bucket.remainingFraction * 100.0
    }
    val tier = ObservatoryTelemetryMath.resolveQuotaTier(pct)
    val litSegments = ObservatoryTelemetryMath.computeLitFuelSegments(pct)
    val tierColor = when (tier) {
        QuotaHealthTier.HEALTHY -> colors.signalEmerald
        QuotaHealthTier.WARNING -> colors.signalAmber
        QuotaHealthTier.CRITICAL -> colors.signalCrimson
    }
    val countdownText = ObservatoryTelemetryMath.formatResetCountdown(bucket.resetTime, nowMillis)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    Text(
                        text = bucket.name.ifBlank { bucket.id.ifBlank { "Quota" } },
                        style = type.labelUi,
                        color = colors.inkPrimary,
                    )
                    if (bucket.window.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .clip(shapes.pill)
                                .background(colors.stratumRaised)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = bucket.window,
                                style = type.labelSmallUi,
                                color = colors.inkSecondary,
                            )
                        }
                    }
                }
                Text(
                    text = "Resets in $countdownText",
                    style = type.monoMicro,
                    color = colors.inkMuted,
                )
            }
            Text(
                text = ObservatoryTelemetryMath.formatPercentReadout(pct),
                style = type.displayMetric,
                color = tierColor,
            )
        }

        // Clean 10-segment rounded linear gauge
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (segIdx in 0 until ObservatoryTelemetryMath.FUEL_SEGMENT_COUNT) {
                val isLit = segIdx < litSegments
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp)
                        .clip(shapes.pill)
                        .background(if (isLit) tierColor else colors.stratumRaised),
                )
            }
        }
    }
}

@Composable
private fun ExternalRadarSection(
    status: ObservatoryFullStatusDto?,
    onOpenOrResumeSession: (ExternalSessionPresenceDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    val activeSessions = status?.externalSessions.orEmpty()

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
            Text("Active on host", style = type.titleBar, color = colors.inkPrimary)
            Box(
                modifier = Modifier
                    .clip(shapes.pill)
                    .background(if (activeSessions.isNotEmpty()) colors.signalEmeraldDim else colors.stratumRaised)
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            ) {
                Text(
                    text = "${activeSessions.size} running",
                    style = type.labelSmallUi,
                    color = if (activeSessions.isNotEmpty()) colors.signalEmerald else colors.inkMuted,
                )
            }
        }

        if (activeSessions.isEmpty()) {
            Text(
                text = "No active Antigravity sessions detected on the host.",
                style = type.bodyCompact,
                color = colors.inkMuted,
            )
        } else {
            activeSessions.forEach { ext ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = spacing.touchFloor)
                        .clip(shapes.sm)
                        .background(colors.stratumRaised)
                        .clickable { onOpenOrResumeSession(ext) }
                        .padding(horizontal = spacing.md, vertical = spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = ext.title.ifBlank { ext.sessionId },
                            style = type.labelUi,
                            color = colors.inkPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = ext.cwd?.let { basenameOfPath(it) } ?: "Unknown folder",
                            style = type.labelSmallUi,
                            color = colors.inkSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(modifier = Modifier.width(spacing.sm))
                    Box(
                        modifier = Modifier
                            .clip(shapes.pill)
                            .background(if (ext.supervised) colors.signalEmeraldDim else colors.signalAmberDim)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = if (ext.supervised) "Open" else "Resume",
                            style = type.labelSmallUi,
                            color = if (ext.supervised) colors.signalEmerald else colors.signalAmber,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteControlDaemonPanel(
    remoteControl: RemoteControlStatusDto?,
    nameDraft: String,
    sessionScoped: Boolean,
    isBusy: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onNameDraftChange: (String) -> Unit,
    onSessionScopedChange: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    val active = remoteControl?.active == true

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shapes.md)
            .background(colors.stratumBase)
            .border(spacing.hairlineWidth, colors.hairline, shapes.md),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 56.dp)
                .clickable(onClick = onToggleExpanded)
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Remote-control daemon", style = type.labelUi, color = colors.inkPrimary)
                Text(
                    text = remoteControl?.instanceName?.let { "Instance: $it" }
                        ?: if (active) "Running on host" else "Stopped",
                    style = type.bodyCompact,
                    color = colors.inkSecondary,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Box(
                    modifier = Modifier
                        .clip(shapes.pill)
                        .background(if (active) colors.signalEmeraldDim else colors.stratumRaised)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = if (active) "Active" else "Inactive",
                        style = type.labelSmallUi,
                        color = if (active) colors.signalEmerald else colors.inkMuted,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Collapse daemon controls" else "Expand daemon controls",
                    tint = colors.inkMuted,
                )
            }
        }

        if (expanded) {
            HorizontalDivider(thickness = spacing.hairlineWidth, color = colors.hairline)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(spacing.lg),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                if (!remoteControl?.statusText.isNullOrBlank()) {
                    Text(
                        text = remoteControl.statusText,
                        style = type.monoMicro,
                        color = colors.inkSecondary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                OutlinedTextField(
                    value = nameDraft,
                    onValueChange = onNameDraftChange,
                    singleLine = true,
                    textStyle = type.bodyProse,
                    placeholder = {
                        Text("Optional instance name", style = type.bodyCompact, color = colors.inkMuted)
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
                        .clickable(role = Role.Checkbox) { onSessionScopedChange(!sessionScoped) }
                        .padding(horizontal = spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = sessionScoped,
                        onCheckedChange = null,
                        colors = CheckboxDefaults.colors(
                            checkedColor = colors.signalCyan,
                            checkmarkColor = colors.inkInverse,
                            uncheckedColor = colors.inkSecondary,
                        ),
                    )
                    Spacer(modifier = Modifier.width(spacing.sm))
                    Text(
                        text = "Session-scoped (--session)",
                        style = type.bodyCompact,
                        color = colors.inkPrimary,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    Button(
                        onClick = onStart,
                        enabled = !isBusy && !active,
                        shape = shapes.pill,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.signalEmerald,
                            contentColor = colors.inkInverse,
                            disabledContainerColor = colors.stratumRaised,
                            disabledContentColor = colors.inkMuted,
                        ),
                        elevation = ButtonDefaults.buttonElevation(
                            defaultElevation = spacing.elevationZero,
                            pressedElevation = spacing.elevationZero,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = spacing.touchFloor),
                    ) {
                        Text("Start daemon", style = type.labelUi)
                    }
                    OutlinedButton(
                        onClick = onStop,
                        enabled = !isBusy && active,
                        shape = shapes.pill,
                        border = BorderStroke(
                            spacing.hairlineWidth,
                            if (active) colors.signalCrimson else colors.hairline,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = spacing.touchFloor),
                    ) {
                        Text(
                            text = "Stop",
                            style = type.labelUi,
                            color = if (active) colors.signalCrimson else colors.inkMuted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CliUpdaterPanel(
    updater: UpdaterStatusDto?,
    lastUpdateResult: RunAgyUpdateResponseDto?,
    isUpdating: Boolean,
    onRequestUpdateConfirm: () -> Unit,
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
            Column(modifier = Modifier.weight(1f)) {
                Text("Antigravity CLI", style = type.labelUi, color = colors.inkPrimary)
                Text(
                    text = updater?.message?.ifBlank { "Up to date" } ?: "Up to date",
                    style = type.bodyCompact,
                    color = colors.inkSecondary,
                )
            }
            Box(
                modifier = Modifier
                    .clip(shapes.pill)
                    .background(colors.signalCyanDim)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "v${updater?.agyVersion?.ifBlank { "unknown" } ?: "unknown"}",
                    style = type.monoMicro,
                    color = colors.signalCyan,
                )
            }
        }

        if (lastUpdateResult != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shapes.sm)
                    .background(colors.stratumWell)
                    .border(spacing.hairlineWidth, colors.hairline, shapes.sm)
                    .padding(spacing.sm),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = if (lastUpdateResult.updated) {
                        "Updated ${lastUpdateResult.versionBefore} -> ${lastUpdateResult.versionAfter}"
                    } else {
                        "Already current (${lastUpdateResult.versionAfter})"
                    },
                    style = type.labelSmallUi,
                    color = colors.signalEmerald,
                )
                if (lastUpdateResult.output.isNotBlank()) {
                    Text(
                        text = lastUpdateResult.output,
                        style = type.monoCode,
                        color = colors.inkSecondary,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        OutlinedButton(
            onClick = onRequestUpdateConfirm,
            enabled = !isUpdating,
            shape = shapes.pill,
            border = BorderStroke(spacing.hairlineWidth, colors.signalAmber),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = colors.signalAmberDim,
                contentColor = colors.signalAmber,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = spacing.touchFloor),
        ) {
            Icon(
                imageVector = Icons.Rounded.SystemUpdateAlt,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(spacing.xs))
            Text("Check & update CLI", style = type.labelUi)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfirmAgyUpdateSheet(
    currentVersion: String,
    updaterMessage: String,
    isUpdating: Boolean,
    onConfirmRun: () -> Unit,
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = colors.signalAmber,
                )
                Text(
                    text = "Confirm CLI update",
                    style = type.headlineSection,
                    color = colors.inkPrimary,
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shapes.sm)
                    .background(colors.stratumWell)
                    .border(spacing.hairlineWidth, colors.signalAmber, shapes.sm)
                    .padding(spacing.md),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Host command", style = type.labelSmallUi, color = colors.signalAmber)
                Text("agy update", style = type.monoCode, color = colors.inkPrimary)
                Text(
                    text = "Current version: v${currentVersion.ifBlank { "unknown" }} · ${updaterMessage.ifBlank { "ready" }}",
                    style = type.monoMicro,
                    color = colors.inkSecondary,
                )
            }

            Text(
                text = "Runs `agy update` on the gateway host in a scrubbed environment with explicit confirmation.",
                style = type.bodyCompact,
                color = colors.inkSecondary,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    enabled = !isUpdating,
                    shape = shapes.pill,
                    border = BorderStroke(spacing.hairlineWidth, colors.hairlineStrong),
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = spacing.touchFloor),
                ) {
                    Text("Cancel", style = type.labelUi, color = colors.inkSecondary)
                }
                Button(
                    onClick = onConfirmRun,
                    enabled = !isUpdating,
                    shape = shapes.pill,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.signalAmber,
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
                    if (isUpdating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = colors.inkInverse,
                        )
                        Spacer(modifier = Modifier.width(spacing.xs))
                    }
                    Text("Confirm & update", style = type.labelUi)
                }
            }
            Spacer(modifier = Modifier.height(spacing.md))
        }
    }
}

@Composable
private fun ObservatoryLogsPanel(
    source: LogSourceTab,
    activeFile: String?,
    availableCrashFiles: List<String>,
    selectedCrashFile: String?,
    searchQuery: String,
    tailLines: Int,
    lines: List<String>,
    totalMatched: Int,
    isLoading: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onSelectSource: (LogSourceTab) -> Unit,
    onSelectCrashFile: (String) -> Unit,
    onSelectTail: (Int) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onRefreshLogs: () -> Unit,
    onExpandSheet: () -> Unit,
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
            .border(spacing.hairlineWidth, colors.hairline, shapes.md),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 56.dp)
                .clickable(onClick = onToggleExpanded)
                .padding(start = spacing.lg, end = spacing.xs, top = spacing.sm, bottom = spacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Host logs & crashes", style = type.labelUi, color = colors.inkPrimary)
                Text(
                    text = "${activeFile ?: source.label} · $totalMatched lines · secrets redacted",
                    style = type.bodyCompact,
                    color = colors.inkSecondary,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onExpandSheet,
                    modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.OpenInFull,
                        contentDescription = "Open full log viewer",
                        tint = colors.signalCyan,
                    )
                }
                IconButton(
                    onClick = onToggleExpanded,
                    modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = if (expanded) "Collapse logs" else "Expand logs",
                        tint = colors.inkMuted,
                    )
                }
            }
        }

        if (expanded) {
            HorizontalDivider(thickness = spacing.hairlineWidth, color = colors.hairline)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(spacing.lg),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                LogFilterToolbar(
                    source = source,
                    availableCrashFiles = availableCrashFiles,
                    selectedCrashFile = selectedCrashFile,
                    searchQuery = searchQuery,
                    tailLines = tailLines,
                    onSelectSource = onSelectSource,
                    onSelectCrashFile = onSelectCrashFile,
                    onSelectTail = onSelectTail,
                    onSearchQueryChange = onSearchQueryChange,
                    onRefreshLogs = onRefreshLogs,
                )

                RedactedLogViewport(
                    lines = lines,
                    isLoading = isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 320.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpandedLogViewerSheet(
    source: LogSourceTab,
    activeFile: String?,
    availableCrashFiles: List<String>,
    selectedCrashFile: String?,
    searchQuery: String,
    tailLines: Int,
    lines: List<String>,
    totalMatched: Int,
    isLoading: Boolean,
    onSelectSource: (LogSourceTab) -> Unit,
    onSelectCrashFile: (String) -> Unit,
    onSelectTail: (Int) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onRefreshLogs: () -> Unit,
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
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Host logs & crashes", style = type.headlineSection, color = colors.inkPrimary)
                    Text(
                        text = "${activeFile ?: source.label} · $totalMatched lines (secrets redacted)",
                        style = type.bodyCompact,
                        color = colors.inkSecondary,
                    )
                }
                IconButton(
                    onClick = onRefreshLogs,
                    modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Refresh logs",
                        tint = colors.signalCyan,
                    )
                }
            }

            LogFilterToolbar(
                source = source,
                availableCrashFiles = availableCrashFiles,
                selectedCrashFile = selectedCrashFile,
                searchQuery = searchQuery,
                tailLines = tailLines,
                onSelectSource = onSelectSource,
                onSelectCrashFile = onSelectCrashFile,
                onSelectTail = onSelectTail,
                onSearchQueryChange = onSearchQueryChange,
                onRefreshLogs = onRefreshLogs,
            )

            RedactedLogViewport(
                lines = lines,
                isLoading = isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp),
            )
            Spacer(modifier = Modifier.height(spacing.md))
        }
    }
}

@Composable
private fun LogFilterToolbar(
    source: LogSourceTab,
    availableCrashFiles: List<String>,
    selectedCrashFile: String?,
    searchQuery: String,
    tailLines: Int,
    onSelectSource: (LogSourceTab) -> Unit,
    onSelectCrashFile: (String) -> Unit,
    onSelectTail: (Int) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onRefreshLogs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Source toggle: cli.log | crashes/
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(shapes.pill)
                    .background(colors.stratumRaised)
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                LogSourceTab.entries.forEach { tab ->
                    val selected = tab == source
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 40.dp)
                            .clip(shapes.pill)
                            .background(if (selected) colors.signalCyanDim else colors.stratumRaised)
                            .clickable(role = Role.Tab) { onSelectSource(tab) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = tab.label,
                            style = type.labelSmallUi,
                            color = if (selected) colors.signalCyan else colors.inkSecondary,
                        )
                    }
                }
            }

            // Tail selector: 200 | 500 | 2000
            Row(
                modifier = Modifier
                    .clip(shapes.pill)
                    .background(colors.stratumRaised)
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                ObservatoryTelemetryMath.TAIL_OPTIONS.forEach { opt ->
                    val selected = opt == tailLines
                    Box(
                        modifier = Modifier
                            .defaultMinSize(minWidth = 44.dp, minHeight = 40.dp)
                            .clip(shapes.pill)
                            .background(if (selected) colors.signalCyanDim else colors.stratumRaised)
                            .clickable { onSelectTail(opt) }
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = opt.toString(),
                            style = type.monoMicro,
                            color = if (selected) colors.signalCyan else colors.inkSecondary,
                        )
                    }
                }
            }
        }

        if (source == LogSourceTab.CRASHES && availableCrashFiles.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                availableCrashFiles.forEach { file ->
                    val selected = file == selectedCrashFile
                    Box(
                        modifier = Modifier
                            .defaultMinSize(minHeight = 36.dp)
                            .clip(shapes.pill)
                            .background(if (selected) colors.signalCyanDim else colors.stratumRaised)
                            .border(
                                spacing.hairlineWidth,
                                if (selected) colors.signalCyan else colors.hairline,
                                shapes.pill,
                            )
                            .clickable { onSelectCrashFile(file) }
                            .padding(horizontal = spacing.md, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = file,
                            style = type.monoMicro,
                            color = if (selected) colors.signalCyan else colors.inkSecondary,
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                singleLine = true,
                textStyle = type.monoCode,
                placeholder = {
                    Text("Filter log lines...", style = type.bodyCompact, color = colors.inkMuted)
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = colors.inkMuted,
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { onSearchQueryChange("") },
                            modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Clear,
                                contentDescription = "Clear log filter",
                                tint = colors.inkMuted,
                            )
                        }
                    }
                },
                shape = shapes.pill,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = colors.stratumWell,
                    unfocusedContainerColor = colors.stratumWell,
                    focusedBorderColor = colors.signalCyan,
                    unfocusedBorderColor = colors.hairline,
                    focusedTextColor = colors.inkPrimary,
                    unfocusedTextColor = colors.inkPrimary,
                ),
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onRefreshLogs,
                modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "Refresh logs",
                    tint = colors.inkSecondary,
                )
            }
        }
    }
}

@Composable
private fun RedactedLogViewport(
    lines: List<String>,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    Box(
        modifier = modifier
            .clip(shapes.sm)
            .background(colors.stratumWell)
            .border(spacing.hairlineWidth, colors.hairline, shapes.sm),
    ) {
        if (lines.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(spacing.lg),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (isLoading) "Loading redacted logs..." else "No matching log lines.",
                    style = type.bodyCompact,
                    color = colors.inkMuted,
                )
            }
        } else {
            SelectionContainer {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = spacing.xs),
                ) {
                    itemsIndexed(lines) { index, line ->
                        val severity = ObservatoryTelemetryMath.classifyLogSeverity(line)
                        val baseTextColor = when (severity) {
                            LogLineSeverity.ERROR -> colors.signalCrimson
                            LogLineSeverity.WARN -> colors.signalAmber
                            LogLineSeverity.INFO -> colors.inkPrimary
                        }
                        val rowTint = when (severity) {
                            LogLineSeverity.ERROR -> colors.signalCrimsonDim
                            LogLineSeverity.WARN -> colors.signalAmberDim
                            LogLineSeverity.INFO -> colors.stratumWell
                        }
                        val segments = ObservatoryTelemetryMath.splitRedactedSegments(line)
                        val annotated = buildAnnotatedString {
                            segments.forEach { seg ->
                                if (seg.isRedactedToken) {
                                    withStyle(
                                        SpanStyle(
                                            color = colors.signalViolet,
                                            background = colors.signalVioletDim,
                                        ),
                                    ) {
                                        append(" ")
                                        append(seg.text)
                                        append(" ")
                                    }
                                } else {
                                    withStyle(SpanStyle(color = baseTextColor)) {
                                        append(seg.text)
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(rowTint)
                                .padding(horizontal = spacing.sm, vertical = 2.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = String.format(Locale.US, "%04d", index + 1),
                                style = type.monoMicro,
                                color = colors.inkMuted,
                                modifier = Modifier.width(36.dp),
                            )
                            Text(
                                text = annotated,
                                style = type.monoCode,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}
