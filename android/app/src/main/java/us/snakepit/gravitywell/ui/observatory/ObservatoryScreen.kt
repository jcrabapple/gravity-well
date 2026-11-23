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
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import us.snakepit.gravitywell.theme.GravityWellTheme
import us.snakepit.gravitywell.ui.sessions.ExternalSessionPresenceDto
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

    // Live 1Hz clock driving tabular T-XXh YYm ZZs reset countdowns while visible
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(1_000L)
        }
    }

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
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            // ── Top Section Header ───────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "OBSERVATORY · INSTRUMENTATION",
                        style = type.labelTelemetry,
                        color = colors.signalCyan,
                    )
                    Text(
                        text = state.hostLabel.ifBlank { "Host Telemetry" },
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
                            contentDescription = "Refresh telemetry",
                            tint = colors.signalCyan,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(spacing.xs))
                    Text("SYNC", style = type.labelTelemetry, color = colors.inkPrimary)
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
                        .padding(horizontal = spacing.md, vertical = spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = state.errorBanner,
                        style = type.monoCode,
                        color = colors.signalCrimson,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = onDismissErrorBanner,
                        modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss error",
                            tint = colors.signalCrimson,
                        )
                    }
                }
            }

            // ── 1. Host Vitals (2x2 on <600dp, 4x1 on >=600dp) ──────────────
            HostVitalsGridSection(
                status = state.status,
                isExpanded = isExpanded,
            )

            // ── 2. Quota & Credit Gauges (10-segment linear fuel bars) ──────
            QuotaGaugesSection(
                usage = state.usage,
                isRefreshingUsage = state.isRefreshingUsage,
                nowMillis = nowMillis,
                isExpanded = isExpanded,
                onRefreshBypassCache = onRefreshUsageBypassCache,
            )

            // ── 3. External Session Presence Radar (FLOCK-verified) ─────────
            ExternalRadarSection(
                status = state.status,
                onOpenOrResumeSession = onOpenOrResumeExternalSession,
            )

            // ── 4 & 5. Remote-Control Daemon + Confirm-Gated `agy update` ───
            if (isExpanded) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.md),
                    verticalAlignment = Alignment.Top,
                ) {
                    RemoteControlDaemonPanel(
                        remoteControl = state.status?.remoteControl,
                        nameDraft = state.daemonNameDraft,
                        sessionScoped = state.daemonSessionScoped,
                        isBusy = state.isDaemonBusy,
                        onNameDraftChange = onUpdateDaemonNameDraft,
                        onSessionScopedChange = onSetDaemonSessionScoped,
                        onStart = onStartDaemon,
                        onStop = onStopDaemon,
                        modifier = Modifier.weight(1f),
                    )
                    CliUpdaterPanel(
                        updater = state.status?.updater,
                        lastUpdateResult = state.lastUpdateResult,
                        isUpdating = state.isUpdatingCli,
                        onRequestUpdateConfirm = onOpenUpdateConfirmSheet,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                RemoteControlDaemonPanel(
                    remoteControl = state.status?.remoteControl,
                    nameDraft = state.daemonNameDraft,
                    sessionScoped = state.daemonSessionScoped,
                    isBusy = state.isDaemonBusy,
                    onNameDraftChange = onUpdateDaemonNameDraft,
                    onSessionScopedChange = onSetDaemonSessionScoped,
                    onStart = onStartDaemon,
                    onStop = onStopDaemon,
                )
                CliUpdaterPanel(
                    updater = state.status?.updater,
                    lastUpdateResult = state.lastUpdateResult,
                    isUpdating = state.isUpdatingCli,
                    onRequestUpdateConfirm = onOpenUpdateConfirmSheet,
                )
            }

            // ── 6. Searchable `cli.log` & `crashes/` Viewer ─────────────────
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
                onSelectSource = onSelectLogSource,
                onSelectCrashFile = onSelectCrashFile,
                onSelectTail = onSelectLogTail,
                onSearchQueryChange = onUpdateLogSearchQuery,
                onRefreshLogs = onFetchLogsNow,
                onExpandSheet = { onSetShowExpandedLogSheet(true) },
            )
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
        "Load 1m · 5m"
    }
    val ramHeadline = if (vitals != null) {
        ObservatoryTelemetryMath.formatRamReadout(vitals.ramUsedMb, vitals.ramTotalMb)
    } else {
        "—"
    }
    val ramPct = if (vitals != null && vitals.ramTotalMb > 0L) {
        String.format(Locale.US, "%.0f%% RSS/Mem", (vitals.ramUsedMb.toDouble() / vitals.ramTotalMb.toDouble()) * 100.0)
    } else {
        "Host Memory"
    }
    val diskHeadline = if (vitals != null) {
        ObservatoryTelemetryMath.formatDiskReadout(vitals.diskUsedGb, vitals.diskTotalGb)
    } else {
        "—"
    }
    val diskPct = if (vitals != null && vitals.diskTotalGb > 0.0) {
        String.format(Locale.US, "%.0f%% NVMe used", (vitals.diskUsedGb / vitals.diskTotalGb) * 100.0)
    } else {
        "Host Storage"
    }
    val radarCount = status?.activeExternalCount ?: 0
    val staleIgnored = status?.staleLockCount ?: 0
    val radarSub = if (staleIgnored > 0) {
        "FLOCK live ($staleIgnored stale ignored)"
    } else {
        "FLOCK-verified live"
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text("HOST VITALS", style = type.labelTelemetry, color = colors.inkSecondary)
        if (isExpanded) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                VitalsMetricTile("CPU / LOAD", cpuHeadline, cpuSub, Modifier.weight(1f))
                VitalsMetricTile("RAM USED / TOTAL", ramHeadline, ramPct, Modifier.weight(1f))
                VitalsMetricTile("NVME USED / TOTAL", diskHeadline, diskPct, Modifier.weight(1f))
                VitalsMetricTile(
                    label = "EXTERNAL RADAR",
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
                    VitalsMetricTile("CPU / LOAD", cpuHeadline, cpuSub, Modifier.weight(1f))
                    VitalsMetricTile("RAM USED / TOTAL", ramHeadline, ramPct, Modifier.weight(1f))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    VitalsMetricTile("NVME USED / TOTAL", diskHeadline, diskPct, Modifier.weight(1f))
                    VitalsMetricTile(
                        label = "EXTERNAL RADAR",
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
    accentColor: androidx.compose.ui.graphics.Color? = null,
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
            .padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = type.labelTelemetry, color = colors.inkSecondary, maxLines = 1)
        Text(
            text = headline,
            style = type.displayMetric,
            color = accentColor ?: colors.inkPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = subtitle,
            style = type.monoMicro,
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
                Text("QUOTA & CREDIT GAUGES", style = type.labelTelemetry, color = colors.inkSecondary)
                if (usage?.stale == true) {
                    Box(
                        modifier = Modifier
                            .clip(shapes.sm)
                            .background(colors.signalAmberDim)
                            .border(spacing.hairlineWidth, colors.signalAmber, shapes.sm)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text("STALE", style = type.labelTelemetry, color = colors.signalAmber)
                    }
                }
                if (!usage?.cachedAt.isNullOrBlank()) {
                    Text(
                        text = usage.cachedAt,
                        style = type.monoMicro,
                        color = colors.inkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            OutlinedButton(
                onClick = onRefreshBypassCache,
                enabled = !isRefreshingUsage,
                shape = shapes.sm,
                border = BorderStroke(spacing.hairlineWidth, colors.signalCyan),
                contentPadding = PaddingValues(horizontal = spacing.md, vertical = 0.dp),
                modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
            ) {
                if (isRefreshingUsage) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = colors.signalCyan,
                    )
                    Spacer(modifier = Modifier.width(spacing.xs))
                }
                Text("REFRESH /USAGE", style = type.labelTelemetry, color = colors.signalCyan)
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
                    text = "No /usage quota buckets cached yet. Tap REFRESH /USAGE to query Antigravity CLI.",
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
            .padding(spacing.md),
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
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    Text(
                        text = bucket.name.ifBlank { bucket.id.ifBlank { "Quota" } },
                        style = type.bodyCompact,
                        color = colors.inkPrimary,
                    )
                    if (bucket.window.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .clip(shapes.sm)
                                .background(colors.stratumRaised)
                                .border(spacing.hairlineWidth, colors.hairlineStrong, shapes.sm)
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        ) {
                            Text(
                                text = bucket.window.uppercase(),
                                style = type.monoMicro,
                                color = colors.inkSecondary,
                            )
                        }
                    }
                }
                Text(
                    text = countdownText,
                    style = type.monoCode,
                    color = colors.inkSecondary,
                )
            }
            Text(
                text = ObservatoryTelemetryMath.formatPercentReadout(pct),
                style = type.displayMetric,
                color = tierColor,
            )
        }

        // 10-segment discrete linear fuel bar (8.dp height, 2.dp segment corner, zero donuts)
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
                        .clip(shapes.xs)
                        .background(if (isLit) tierColor else colors.stratumWell)
                        .border(
                            width = spacing.hairlineWidth,
                            color = if (isLit) tierColor else colors.hairline,
                            shape = shapes.xs,
                        ),
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
            .padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("EXTERNAL PRESENCE RADAR", style = type.labelTelemetry, color = colors.signalCyan)
            Text(
                text = "${activeSessions.size} ACTIVE · FLOCK VERIFIED",
                style = type.monoMicro,
                color = if (activeSessions.isNotEmpty()) colors.signalEmerald else colors.inkMuted,
            )
        }

        if (activeSessions.isEmpty()) {
            Text(
                text = "Zero active host Antigravity locks detected in presence/ via FLOCK.",
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
                        .border(spacing.hairlineWidth, colors.hairline, shapes.sm)
                        .clickable { onOpenOrResumeSession(ext) }
                        .padding(horizontal = spacing.md, vertical = spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = ext.title.ifBlank { ext.sessionId },
                            style = type.titleBar,
                            color = colors.inkPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = buildString {
                                append(ext.cwd ?: "unknown cwd")
                                if (ext.pid != null) {
                                    append(" · pid ")
                                    append(ext.pid)
                                }
                            },
                            style = type.monoMicro,
                            color = colors.inkSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(modifier = Modifier.width(spacing.sm))
                    Box(
                        modifier = Modifier
                            .clip(shapes.sm)
                            .background(if (ext.supervised) colors.signalEmeraldDim else colors.signalAmberDim)
                            .border(
                                spacing.hairlineWidth,
                                if (ext.supervised) colors.signalEmerald else colors.signalAmber,
                                shapes.sm,
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = if (ext.supervised) "SUPERVISED" else "HOST EXTERNAL",
                            style = type.labelTelemetry,
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
            .border(spacing.hairlineWidth, colors.hairline, shapes.md)
            .padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("REMOTE-CONTROL DAEMON", style = type.labelTelemetry, color = colors.inkSecondary)
            Box(
                modifier = Modifier
                    .clip(shapes.sm)
                    .background(if (active) colors.signalEmeraldDim else colors.stratumRaised)
                    .border(
                        spacing.hairlineWidth,
                        if (active) colors.signalEmerald else colors.hairlineStrong,
                        shapes.sm,
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = if (active) "ACTIVE" else "INACTIVE",
                    style = type.labelTelemetry,
                    color = if (active) colors.signalEmerald else colors.inkMuted,
                )
            }
        }

        if (!remoteControl?.instanceName.isNullOrBlank()) {
            Text(
                text = "Instance: ${remoteControl.instanceName}",
                style = type.monoCode,
                color = colors.inkPrimary,
            )
        }
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
            textStyle = type.monoCode,
            placeholder = {
                Text("Optional --name instance label", style = type.bodyCompact, color = colors.inkMuted)
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
                shape = shapes.sm,
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
                Text("START DAEMON", style = type.labelTelemetry)
            }
            OutlinedButton(
                onClick = onStop,
                enabled = !isBusy && active,
                shape = shapes.sm,
                border = BorderStroke(
                    spacing.hairlineWidth,
                    if (active) colors.signalCrimson else colors.hairline,
                ),
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = spacing.touchFloor),
            ) {
                Text(
                    text = "STOP",
                    style = type.labelTelemetry,
                    color = if (active) colors.signalCrimson else colors.inkMuted,
                )
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
            .padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("ANTIGRAVITY CLI UPDATER", style = type.labelTelemetry, color = colors.inkSecondary)
            Text(
                text = "v${updater?.agyVersion?.ifBlank { "unknown" } ?: "unknown"}",
                style = type.monoCode,
                color = colors.signalCyan,
            )
        }

        Text(
            text = "Status: ${updater?.message?.ifBlank { "Idle" } ?: "Idle"}",
            style = type.bodyCompact,
            color = colors.inkSecondary,
        )

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
                        "UPDATED ${lastUpdateResult.versionBefore} -> ${lastUpdateResult.versionAfter}"
                    } else {
                        "NO CHANGE (${lastUpdateResult.versionAfter})"
                    },
                    style = type.labelTelemetry,
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
            shape = shapes.sm,
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
                imageVector = Icons.Default.SystemUpdateAlt,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(spacing.xs))
            Text("UPDATE ANTIGRAVITY CLI", style = type.labelTelemetry)
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
        shape = shapes.md,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = colors.signalAmber,
                )
                Text(
                    text = "CONFIRM CLI UPDATE INTERLOCK",
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
                Text("HOST COMMAND", style = type.labelTelemetry, color = colors.signalAmber)
                Text("agy update", style = type.monoCode, color = colors.inkPrimary)
                Text(
                    text = "Current binary: v${currentVersion.ifBlank { "unknown" }} · ${updaterMessage.ifBlank { "ready" }}",
                    style = type.monoMicro,
                    color = colors.inkSecondary,
                )
            }

            Text(
                text = "Runs `agy update` on the gateway host with a scrubbed environment (`ANTIGRAVITY_ENV_STRICT=1`) and explicit `{\"confirm\": true}` authorization.",
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
                    shape = shapes.sm,
                    border = BorderStroke(spacing.hairlineWidth, colors.hairlineStrong),
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = spacing.touchFloor),
                ) {
                    Text("CANCEL", style = type.labelTelemetry, color = colors.inkSecondary)
                }
                Button(
                    onClick = onConfirmRun,
                    enabled = !isUpdating,
                    shape = shapes.sm,
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
                    Text("CONFIRM & RUN UPDATE", style = type.labelTelemetry)
                }
            }
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
                Text("CRASH & LOG VIEWER · SECRET REDACTED", style = type.labelTelemetry, color = colors.signalViolet)
                Text(
                    text = "${activeFile ?: source.label} · $totalMatched matched lines",
                    style = type.monoMicro,
                    color = colors.inkSecondary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(
                    onClick = onRefreshLogs,
                    modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh logs",
                        tint = colors.inkSecondary,
                    )
                }
                IconButton(
                    onClick = onExpandSheet,
                    modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                ) {
                    Icon(
                        imageVector = Icons.Default.OpenInFull,
                        contentDescription = "Expand log sheet",
                        tint = colors.signalCyan,
                    )
                }
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
        shape = shapes.md,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg, vertical = spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("LOG & CRASH INSPECTOR", style = type.headlineSection, color = colors.inkPrimary)
                    Text(
                        text = "${activeFile ?: source.label} · $totalMatched lines (secret-redacted)",
                        style = type.monoMicro,
                        color = colors.inkSecondary,
                    )
                }
                IconButton(
                    onClick = onRefreshLogs,
                    modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
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
            )

            RedactedLogViewport(
                lines = lines,
                isLoading = isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp),
            )
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
                    .clip(shapes.sm)
                    .border(spacing.hairlineWidth, colors.hairlineStrong, shapes.sm),
            ) {
                LogSourceTab.entries.forEach { tab ->
                    val selected = tab == source
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = spacing.touchFloor)
                            .background(if (selected) colors.signalCyanDim else colors.stratumRaised)
                            .clickable(role = Role.Tab) { onSelectSource(tab) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = tab.label,
                            style = type.monoCode,
                            color = if (selected) colors.signalCyan else colors.inkSecondary,
                        )
                    }
                }
            }

            // Tail selector: 200 | 500 | 2000
            Row(
                modifier = Modifier
                    .clip(shapes.sm)
                    .border(spacing.hairlineWidth, colors.hairlineStrong, shapes.sm),
            ) {
                ObservatoryTelemetryMath.TAIL_OPTIONS.forEach { opt ->
                    val selected = opt == tailLines
                    Box(
                        modifier = Modifier
                            .defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor)
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
                            .defaultMinSize(minHeight = spacing.touchFloor)
                            .clip(shapes.sm)
                            .background(if (selected) colors.signalCyanDim else colors.stratumRaised)
                            .border(
                                spacing.hairlineWidth,
                                if (selected) colors.signalCyan else colors.hairline,
                                shapes.sm,
                            )
                            .clickable { onSelectCrashFile(file) }
                            .padding(horizontal = spacing.md),
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

        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            singleLine = true,
            textStyle = type.monoCode,
            placeholder = {
                Text("Filter redacted log lines...", style = type.bodyCompact, color = colors.inkMuted)
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
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
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Clear log filter",
                            tint = colors.inkMuted,
                        )
                    }
                }
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
                    text = if (isLoading) "Loading redacted host logs..." else "No matching log lines.",
                    style = type.monoCode,
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
