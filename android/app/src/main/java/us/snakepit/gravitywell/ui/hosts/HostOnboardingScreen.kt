package us.snakepit.gravitywell.ui.hosts

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import us.snakepit.gravitywell.cache.CachedHost
import us.snakepit.gravitywell.net.CapabilitiesDto
import us.snakepit.gravitywell.net.VerificationLegId
import us.snakepit.gravitywell.net.VerificationLegState
import us.snakepit.gravitywell.net.VerificationLegStatus
import us.snakepit.gravitywell.theme.GravityWellTheme

@Composable
fun HostOnboardingScreen(
    state: HostOnboardingUiState,
    onLabelChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onToggleTokenVisibility: () -> Unit,
    onRunPreflight: () -> Unit,
    onSaveVerifiedHost: () -> Unit,
    onCancel: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
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
        errorBorderColor = colors.signalCrimson,
        focusedTextColor = colors.inkPrimary,
        unfocusedTextColor = colors.inkPrimary,
        cursorColor = colors.signalCyan,
        focusedLabelColor = colors.signalCyan,
        unfocusedLabelColor = colors.inkSecondary,
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.chassisVoid)
            .verticalScroll(rememberScrollState())
            .padding(spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "GATEWAY PRE-FLIGHT SEQUENCER",
                    style = type.labelTelemetry,
                    color = colors.signalCyan,
                )
                Spacer(modifier = Modifier.height(spacing.xs))
                Text(
                    text = if (state.editingHostId != null) "Re-Verify Antigravity Host" else "Connect Antigravity Host",
                    style = type.headlineSection,
                    color = colors.inkPrimary,
                )
            }
            if (onCancel != null) {
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close host onboarding",
                        tint = colors.inkSecondary,
                    )
                }
            }
        }

        Text(
            text = "All 3 diagnostic legs must pass sequentially before a bearer token is sealed into AndroidKeyStore.",
            style = type.bodyCompact,
            color = colors.inkSecondary,
        )

        OutlinedTextField(
            value = state.label,
            onValueChange = onLabelChange,
            label = { Text("HOST LABEL (OPTIONAL — DEFAULTS TO HOSTNAME)", style = type.labelTelemetry) },
            placeholder = { Text("aurora", style = type.monoCode, color = colors.inkMuted) },
            textStyle = type.bodyProse,
            singleLine = true,
            shape = shapes.sm,
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = state.baseUrl,
            onValueChange = onBaseUrlChange,
            label = { Text("GATEWAY BASE URL", style = type.labelTelemetry) },
            placeholder = { Text("https://aurora.tailnet.ts.net:9340", style = type.monoCode, color = colors.inkMuted) },
            textStyle = type.monoCode,
            singleLine = true,
            isError = state.urlValidationError != null,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
            ),
            shape = shapes.sm,
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth(),
        )

        if (state.urlValidationError != null) {
            DiagnosticNoticeBanner(
                text = state.urlValidationError,
                bgColor = colors.signalCrimsonDim,
                borderColor = colors.signalCrimson,
                inkColor = colors.signalCrimson,
            )
        } else if (state.showDebugCleartextWarning) {
            DiagnosticNoticeBanner(
                text = "DEBUG CLEARTEXT LAN MODE - never use over untrusted networks",
                bgColor = colors.signalAmberDim,
                borderColor = colors.signalAmber,
                inkColor = colors.signalAmber,
            )
        }

        OutlinedTextField(
            value = state.bearerToken,
            onValueChange = onTokenChange,
            label = { Text("BEARER TOKEN (KEYSTORE AES-GCM)", style = type.labelTelemetry) },
            placeholder = { Text("Paste GRAVITY_WELL_TOKEN", style = type.monoCode, color = colors.inkMuted) },
            textStyle = type.monoCode,
            singleLine = true,
            visualTransformation = if (state.isTokenVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
            ),
            trailingIcon = {
                IconButton(
                    onClick = onToggleTokenVisibility,
                    modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                ) {
                    Icon(
                        imageVector = if (state.isTokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (state.isTokenVisible) "Hide bearer token" else "Show bearer token",
                        tint = colors.inkSecondary,
                    )
                }
            },
            shape = shapes.sm,
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedButton(
            onClick = onRunPreflight,
            enabled = !state.isRunningPreflight && state.baseUrl.isNotBlank(),
            shape = shapes.sm,
            border = BorderStroke(spacing.hairlineWidth, colors.signalCyan),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = colors.signalCyanDim,
                contentColor = colors.signalCyan,
                disabledContainerColor = colors.stratumRaised,
                disabledContentColor = colors.inkMuted,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = spacing.touchFloor),
        ) {
            if (state.isRunningPreflight) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = colors.signalCyan,
                )
                Spacer(modifier = Modifier.width(spacing.sm))
                Text("RUNNING SEQUENTIAL PRE-FLIGHT...", style = type.labelTelemetry)
            } else {
                Text("Run Pre-Flight Check", style = type.labelTelemetry)
            }
        }

        HorizontalDivider(thickness = spacing.hairlineWidth, color = colors.hairline)

        Text(
            text = "DIAGNOSTIC TELEMETRY LEGS",
            style = type.labelTelemetry,
            color = colors.inkSecondary,
        )

        PreflightLegRow(
            legNumber = "LEG 01 · REACHABILITY & TLS",
            endpointHint = "GET /api/health",
            legState = state.legs[VerificationLegId.REACHABILITY_TLS]
                ?: VerificationLegState(VerificationLegId.REACHABILITY_TLS),
            capabilities = null,
        )

        PreflightLegRow(
            legNumber = "LEG 02 · AUTH & CAPABILITIES",
            endpointHint = "GET /api/info",
            legState = state.legs[VerificationLegId.AUTH_CAPABILITIES]
                ?: VerificationLegState(VerificationLegId.AUTH_CAPABILITIES),
            capabilities = state.lastReport?.info?.capabilities,
        )

        PreflightLegRow(
            legNumber = "LEG 03 · LIVE STREAM TICKET",
            endpointHint = "POST /api/ws-ticket -> WS /api/ws",
            legState = state.legs[VerificationLegId.WS_TICKET_HELLO]
                ?: VerificationLegState(VerificationLegId.WS_TICKET_HELLO),
            capabilities = null,
        )

        if (state.saveError != null) {
            DiagnosticNoticeBanner(
                text = state.saveError,
                bgColor = colors.signalCrimsonDim,
                borderColor = colors.signalCrimson,
                inkColor = colors.signalCrimson,
            )
        }

        Button(
            onClick = onSaveVerifiedHost,
            enabled = state.canSaveVerifiedHost,
            shape = shapes.sm,
            elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.signalEmerald,
                contentColor = colors.inkInverse,
                disabledContainerColor = colors.stratumRaised,
                disabledContentColor = colors.inkMuted,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = spacing.touchFloor)
                .border(
                    width = spacing.hairlineWidth,
                    color = if (state.canSaveVerifiedHost) colors.signalEmerald else colors.hairline,
                    shape = shapes.sm,
                ),
        ) {
            Text("Save Verified Host", style = type.labelTelemetry)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreflightLegRow(
    legNumber: String,
    endpointHint: String,
    legState: VerificationLegState,
    capabilities: CapabilitiesDto?,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    val railColor = when (legState.status) {
        VerificationLegStatus.NOT_RUN -> colors.hairlineStrong
        VerificationLegStatus.RUNNING -> colors.signalCyan
        VerificationLegStatus.VERIFIED -> colors.signalEmerald
        VerificationLegStatus.FAILED -> colors.signalCrimson
    }

    val statusText = when (legState.status) {
        VerificationLegStatus.NOT_RUN -> "STANDBY"
        VerificationLegStatus.RUNNING -> "PROBING"
        VerificationLegStatus.VERIFIED -> "VERIFIED"
        VerificationLegStatus.FAILED -> "FAILED"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(shapes.sm)
            .background(colors.stratumRaised)
            .border(spacing.hairlineWidth, colors.hairline, shapes.sm),
    ) {
        Box(
            modifier = Modifier
                .width(spacing.statusRailWidth)
                .fillMaxHeight()
                .background(railColor),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = legNumber,
                    style = type.labelTelemetry,
                    color = colors.inkPrimary,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    if (legState.latencyMs != null) {
                        Text(
                            text = "${legState.latencyMs} ms",
                            style = type.monoMicro,
                            color = colors.inkSecondary,
                        )
                    }
                    when (legState.status) {
                        VerificationLegStatus.VERIFIED -> Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Verified",
                            tint = colors.signalEmerald,
                            modifier = Modifier.size(14.dp),
                        )
                        VerificationLegStatus.FAILED -> Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = "Failed",
                            tint = colors.signalCrimson,
                            modifier = Modifier.size(14.dp),
                        )
                        else -> Unit
                    }
                    Text(
                        text = statusText,
                        style = type.monoMicro,
                        color = railColor,
                    )
                }
            }

            Text(
                text = endpointHint,
                style = type.monoMicro,
                color = colors.inkMuted,
            )

            if (!legState.summary.isNullOrBlank()) {
                Text(
                    text = legState.summary,
                    style = type.monoCode,
                    color = colors.inkSecondary,
                )
            }

            if (legState.status == VerificationLegStatus.VERIFIED && capabilities != null) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    CapabilityChip("resume: ${if (capabilities.resumeSession) "on" else "off"}", capabilities.resumeSession)
                    CapabilityChip("load: ${if (capabilities.loadSession) "on" else "off"}", capabilities.loadSession)
                    CapabilityChip("elicitation: ${if (capabilities.elicitation) "on" else "off"}", capabilities.elicitation)
                    CapabilityChip(
                        "title_override: ${if (capabilities.gatewayTitleOverride) "on" else "off"}",
                        capabilities.gatewayTitleOverride,
                    )
                    CapabilityChip(
                        label = if (capabilities.fork) "fork: supported" else "fork: unsupported",
                        supported = capabilities.fork,
                    )
                }
            }

            if (legState.status == VerificationLegStatus.FAILED) {
                val diagStatus = legState.errorStatus?.toString() ?: "IO"
                val diagCode = legState.errorCode ?: "error"
                val diagMsg = legState.errorMessage ?: "Diagnostic leg failed"
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shapes.sm)
                        .background(colors.stratumWell)
                        .border(spacing.hairlineWidth, colors.signalCrimson, shapes.sm)
                        .padding(spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "$diagStatus $diagCode",
                        style = type.monoCode,
                        color = colors.signalCrimson,
                    )
                    Text(
                        text = diagMsg,
                        style = type.monoMicro,
                        color = colors.inkPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun CapabilityChip(
    label: String,
    supported: Boolean,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    Box(
        modifier = Modifier
            .clip(shapes.sm)
            .background(if (supported) colors.signalEmeraldDim else colors.signalAmberDim)
            .border(
                width = spacing.hairlineWidth,
                color = if (supported) colors.signalEmerald else colors.signalAmber,
                shape = shapes.sm,
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = type.monoMicro,
            color = if (supported) colors.signalEmerald else colors.signalAmber,
        )
    }
}

@Composable
private fun DiagnosticNoticeBanner(
    text: String,
    bgColor: Color,
    borderColor: Color,
    inkColor: Color,
) {
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shapes.sm)
            .background(bgColor)
            .border(spacing.hairlineWidth, borderColor, shapes.sm)
            .padding(spacing.sm),
    ) {
        Text(
            text = text,
            style = type.monoMicro,
            color = inkColor,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostSwitcherSheet(
    hosts: List<CachedHost>,
    activeHostId: String?,
    onSelectHost: (String) -> Unit,
    onReverifyHost: (CachedHost) -> Unit,
    onDeleteHost: (String) -> Unit,
    onAddNewHost: () -> Unit,
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "GATEWAY HOSTS",
                    style = type.headlineSection,
                    color = colors.inkPrimary,
                )
                OutlinedButton(
                    onClick = onAddNewHost,
                    shape = shapes.sm,
                    border = BorderStroke(spacing.hairlineWidth, colors.signalCyan),
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
                    Text("ADD HOST", style = type.labelTelemetry)
                }
            }

            HorizontalDivider(thickness = spacing.hairlineWidth, color = colors.hairline)

            hosts.forEach { host ->
                val isSelected = host.hostId == activeHostId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shapes.sm)
                        .background(if (isSelected) colors.signalCyanDim else colors.stratumRaised)
                        .border(
                            width = spacing.hairlineWidth,
                            color = if (isSelected) colors.signalCyan else colors.hairline,
                            shape = shapes.sm,
                        )
                        .clickable { onSelectHost(host.hostId) }
                        .defaultMinSize(minHeight = spacing.touchFloor)
                        .padding(horizontal = spacing.md, vertical = spacing.sm)
                        .semantics { contentDescription = "Select host ${host.label}" },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = buildString {
                                append(host.label)
                                if (!host.agyVersion.isNullOrBlank()) {
                                    append(" · v")
                                    append(host.agyVersion)
                                }
                            },
                            style = type.titleBar,
                            color = colors.inkPrimary,
                        )
                        Text(
                            text = host.baseUrl,
                            style = type.monoMicro,
                            color = colors.inkSecondary,
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        )
                    }
                    IconButton(
                        onClick = { onReverifyHost(host) },
                        modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Re-verify ${host.label}",
                            tint = colors.signalCyan,
                        )
                    }
                    IconButton(
                        onClick = { onDeleteHost(host.hostId) },
                        modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Remove ${host.label}",
                            tint = colors.signalCrimson,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(spacing.lg))
        }
    }
}
