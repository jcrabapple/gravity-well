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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
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
        focusedContainerColor = colors.stratumBase,
        unfocusedContainerColor = colors.stratumBase,
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
            .padding(horizontal = spacing.xl, vertical = spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
                modifier = Modifier.weight(1f),
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(colors.signalCyanDim),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = colors.signalCyan,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Column {
                    Text(
                        text = if (state.editingHostId != null) "Re-verify host" else "Connect to Antigravity",
                        style = type.headlineSection,
                        color = colors.inkPrimary,
                    )
                    Text(
                        text = "Gravity Well gateway",
                        style = type.bodyCompact,
                        color = colors.inkSecondary,
                    )
                }
            }
            if (onCancel != null) {
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close host setup",
                        tint = colors.inkSecondary,
                    )
                }
            }
        }

        Text(
            text = "Enter your gateway address and token. We'll run a quick 3-step check before storing your token securely in Android Keystore.",
            style = type.bodyCompact,
            color = colors.inkSecondary,
        )

        OutlinedTextField(
            value = state.label,
            onValueChange = onLabelChange,
            label = { Text("Host name (optional)", style = type.labelSmallUi) },
            placeholder = { Text("aurora", style = type.bodyProse, color = colors.inkMuted) },
            textStyle = type.bodyProse,
            singleLine = true,
            shape = shapes.sm,
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = state.baseUrl,
            onValueChange = onBaseUrlChange,
            label = { Text("Gateway URL", style = type.labelSmallUi) },
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
                text = "Debug HTTP mode — use only on trusted local networks.",
                bgColor = colors.signalAmberDim,
                borderColor = colors.signalAmber,
                inkColor = colors.signalAmber,
            )
        }

        OutlinedTextField(
            value = state.bearerToken,
            onValueChange = onTokenChange,
            label = { Text("Access token", style = type.labelSmallUi) },
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
                        imageVector = if (state.isTokenVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = if (state.isTokenVisible) "Hide token" else "Show token",
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
            shape = shapes.pill,
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
                Text("Checking connection...", style = type.labelUi)
            } else {
                Text("Test connection", style = type.labelUi)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text(
                text = "Connection checks",
                style = type.labelUi,
                color = colors.inkSecondary,
            )

            PreflightLegRow(
                stepTitle = "1. Server & TLS",
                endpointHint = "GET /api/health",
                legState = state.legs[VerificationLegId.REACHABILITY_TLS]
                    ?: VerificationLegState(VerificationLegId.REACHABILITY_TLS),
                capabilities = null,
            )

            PreflightLegRow(
                stepTitle = "2. Authentication & capabilities",
                endpointHint = "GET /api/info",
                legState = state.legs[VerificationLegId.AUTH_CAPABILITIES]
                    ?: VerificationLegState(VerificationLegId.AUTH_CAPABILITIES),
                capabilities = state.lastReport?.info?.capabilities,
            )

            PreflightLegRow(
                stepTitle = "3. Live chat stream",
                endpointHint = "POST /api/ws-ticket · WS /api/ws",
                legState = state.legs[VerificationLegId.WS_TICKET_HELLO]
                    ?: VerificationLegState(VerificationLegId.WS_TICKET_HELLO),
                capabilities = null,
            )
        }

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
            shape = shapes.pill,
            elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.signalEmerald,
                contentColor = colors.inkInverse,
                disabledContainerColor = colors.stratumRaised,
                disabledContentColor = colors.inkMuted,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = spacing.touchFloor),
        ) {
            Text("Save & connect", style = type.labelUi)
        }

        Spacer(modifier = Modifier.height(spacing.md))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreflightLegRow(
    stepTitle: String,
    endpointHint: String,
    legState: VerificationLegState,
    capabilities: CapabilitiesDto?,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    val statusColor = when (legState.status) {
        VerificationLegStatus.NOT_RUN -> colors.inkMuted
        VerificationLegStatus.RUNNING -> colors.signalCyan
        VerificationLegStatus.VERIFIED -> colors.signalEmerald
        VerificationLegStatus.FAILED -> colors.signalCrimson
    }

    val statusText = when (legState.status) {
        VerificationLegStatus.NOT_RUN -> "Waiting"
        VerificationLegStatus.RUNNING -> "Checking..."
        VerificationLegStatus.VERIFIED -> "Verified"
        VerificationLegStatus.FAILED -> "Failed"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shapes.md)
            .background(colors.stratumBase)
            .border(
                spacing.hairlineWidth,
                if (legState.status == VerificationLegStatus.FAILED) colors.signalCrimson else colors.hairline,
                shapes.md,
            )
            .padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stepTitle,
                style = type.labelUi,
                color = colors.inkPrimary,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (legState.latencyMs != null) {
                    Text(
                        text = "${legState.latencyMs} ms",
                        style = type.monoMicro,
                        color = colors.inkMuted,
                    )
                }
                when (legState.status) {
                    VerificationLegStatus.VERIFIED -> Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = "Verified",
                        tint = colors.signalEmerald,
                        modifier = Modifier.size(16.dp),
                    )
                    VerificationLegStatus.FAILED -> Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = "Failed",
                        tint = colors.signalCrimson,
                        modifier = Modifier.size(16.dp),
                    )
                    VerificationLegStatus.RUNNING -> CircularProgressIndicator(
                        modifier = Modifier.size(13.dp),
                        strokeWidth = 2.dp,
                        color = colors.signalCyan,
                    )
                    VerificationLegStatus.NOT_RUN -> Unit
                }
                Text(
                    text = statusText,
                    style = type.labelSmallUi,
                    color = statusColor,
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
                style = type.bodyCompact,
                color = colors.inkSecondary,
            )
        }

        if (legState.status == VerificationLegStatus.VERIFIED && capabilities != null) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                CapabilityChip("Resume", capabilities.resumeSession)
                CapabilityChip("History", capabilities.loadSession)
                CapabilityChip("Questions", capabilities.elicitation)
                CapabilityChip("Rename", capabilities.gatewayTitleOverride)
                CapabilityChip(
                    label = if (capabilities.fork) "Fork" else "Fork unavailable",
                    supported = capabilities.fork,
                )
            }
        }

        if (legState.status == VerificationLegStatus.FAILED) {
            val diagStatus = legState.errorStatus?.toString() ?: "IO"
            val diagCode = legState.errorCode ?: "error"
            val diagMsg = legState.errorMessage ?: "Connection check failed"
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
                    text = "$diagStatus · $diagCode",
                    style = type.monoCode,
                    color = colors.signalCrimson,
                )
                Text(
                    text = diagMsg,
                    style = type.bodyCompact,
                    color = colors.inkPrimary,
                )
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

    Box(
        modifier = Modifier
            .clip(shapes.pill)
            .background(if (supported) colors.signalEmeraldDim else colors.stratumRaised)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            style = type.labelSmallUi,
            color = if (supported) colors.signalEmerald else colors.inkMuted,
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
            .padding(spacing.md),
    ) {
        Text(
            text = text,
            style = type.bodyCompact,
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
        shape = shapes.sheetTop,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.xl, vertical = spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Hosts",
                    style = type.headlineSection,
                    color = colors.inkPrimary,
                )
                OutlinedButton(
                    onClick = onAddNewHost,
                    shape = shapes.pill,
                    border = BorderStroke(spacing.hairlineWidth, colors.signalCyan),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = colors.signalCyanDim,
                        contentColor = colors.signalCyan,
                    ),
                    modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = 40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(spacing.xs))
                    Text("Add host", style = type.labelUi)
                }
            }

            HorizontalDivider(thickness = spacing.hairlineWidth, color = colors.hairline)

            hosts.forEach { host ->
                val isSelected = host.hostId == activeHostId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shapes.md)
                        .background(if (isSelected) colors.signalCyanDim else colors.stratumRaised)
                        .border(
                            width = spacing.hairlineWidth,
                            color = if (isSelected) colors.signalCyan else colors.hairline,
                            shape = shapes.md,
                        )
                        .clickable { onSelectHost(host.hostId) }
                        .defaultMinSize(minHeight = spacing.touchFloor)
                        .padding(start = spacing.md, end = spacing.xs, top = spacing.sm, bottom = spacing.sm)
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
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Re-verify ${host.label}",
                            tint = colors.signalCyan,
                        )
                    }
                    IconButton(
                        onClick = { onDeleteHost(host.hostId) },
                        modifier = Modifier.defaultMinSize(minWidth = spacing.touchFloor, minHeight = spacing.touchFloor),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
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
