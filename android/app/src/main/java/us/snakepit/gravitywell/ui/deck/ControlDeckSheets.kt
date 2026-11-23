package us.snakepit.gravitywell.ui.deck

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import us.snakepit.gravitywell.theme.GravityWellTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddMcpServerSheet(
    form: AddMcpFormState,
    isMutating: Boolean,
    onUpdateForm: ((AddMcpFormState) -> AddMcpFormState) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = colors.stratumWell,
        unfocusedContainerColor = colors.stratumWell,
        focusedBorderColor = colors.signalCyan,
        unfocusedBorderColor = colors.hairline,
        focusedTextColor = colors.inkPrimary,
        unfocusedTextColor = colors.inkPrimary,
    )

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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text("ADD MCP SERVER", style = type.headlineSection, color = colors.inkPrimary)
            Text(
                text = "Invokes `agy mcp add` with flags strictly before `<name>`. Secret values are masked on subsequent reads.",
                style = type.bodyCompact,
                color = colors.inkSecondary,
            )

            // Type segmented toggle: stdio | http
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shapes.sm)
                    .border(spacing.hairlineWidth, colors.hairlineStrong, shapes.sm),
            ) {
                listOf("stdio", "http").forEach { kind ->
                    val selected = form.type == kind
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = spacing.touchFloor)
                            .background(if (selected) colors.signalCyanDim else colors.stratumRaised)
                            .clickable(role = Role.Tab) { onUpdateForm { it.copy(type = kind) } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = kind.uppercase(),
                            style = type.labelTelemetry,
                            color = if (selected) colors.signalCyan else colors.inkSecondary,
                        )
                    }
                }
            }

            OutlinedTextField(
                value = form.name,
                onValueChange = { v -> onUpdateForm { it.copy(name = v) } },
                singleLine = true,
                textStyle = type.monoCode,
                label = { Text("Server Name", style = type.labelTelemetry, color = colors.inkSecondary) },
                placeholder = { Text("e.g. codegraph", style = type.bodyCompact, color = colors.inkMuted) },
                colors = textFieldColors,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = form.target,
                onValueChange = { v -> onUpdateForm { it.copy(target = v) } },
                singleLine = true,
                textStyle = type.monoCode,
                label = {
                    Text(
                        text = if (form.type == "http") "Server URL (https://...)" else "Command Binary",
                        style = type.labelTelemetry,
                        color = colors.inkSecondary,
                    )
                },
                placeholder = {
                    Text(
                        text = if (form.type == "http") "https://mcp.example.internal/sse" else "uvx",
                        style = type.bodyCompact,
                        color = colors.inkMuted,
                    )
                },
                colors = textFieldColors,
                modifier = Modifier.fillMaxWidth(),
            )

            if (form.type == "stdio") {
                OutlinedTextField(
                    value = form.argsLine,
                    onValueChange = { v -> onUpdateForm { it.copy(argsLine = v) } },
                    singleLine = true,
                    textStyle = type.monoCode,
                    label = { Text("Arguments (space-separated)", style = type.labelTelemetry, color = colors.inkSecondary) },
                    placeholder = { Text("mcp-server-git --repository .", style = type.bodyCompact, color = colors.inkMuted) },
                    colors = textFieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            OutlinedTextField(
                value = form.envLines,
                onValueChange = { v -> onUpdateForm { it.copy(envLines = v) } },
                minLines = 2,
                maxLines = 4,
                textStyle = type.monoCode,
                visualTransformation = PasswordVisualTransformation(),
                label = { Text("Env Vars (one NAME=value per line, masked)", style = type.labelTelemetry, color = colors.inkSecondary) },
                colors = textFieldColors,
                modifier = Modifier.fillMaxWidth(),
            )

            if (form.type == "http") {
                OutlinedTextField(
                    value = form.headerLines,
                    onValueChange = { v -> onUpdateForm { it.copy(headerLines = v) } },
                    minLines = 2,
                    maxLines = 4,
                    textStyle = type.monoCode,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text("HTTP Headers (one Header: value per line, masked)", style = type.labelTelemetry, color = colors.inkSecondary) },
                    colors = textFieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    enabled = !isMutating,
                    shape = shapes.sm,
                    border = BorderStroke(spacing.hairlineWidth, colors.hairlineStrong),
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = spacing.touchFloor),
                ) {
                    Text("CANCEL", style = type.labelTelemetry, color = colors.inkSecondary)
                }
                Button(
                    onClick = onSubmit,
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
                    if (isMutating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = colors.inkInverse,
                        )
                        Spacer(modifier = Modifier.width(spacing.xs))
                    }
                    Text("SAVE MCP", style = type.labelTelemetry)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentEditorSheet(
    form: AgentEditorFormState,
    isMutating: Boolean,
    onUpdateForm: ((AgentEditorFormState) -> AgentEditorFormState) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    val slugValid = ControlDeckValidation.isValidAgentSlug(form.slug)
    val canSubmit = slugValid && form.prompt.isNotBlank() && !isMutating

    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = colors.stratumWell,
        unfocusedContainerColor = colors.stratumWell,
        focusedBorderColor = colors.signalCyan,
        unfocusedBorderColor = colors.hairline,
        focusedTextColor = colors.inkPrimary,
        unfocusedTextColor = colors.inkPrimary,
    )

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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                text = if (form.isEditingExisting) "EDIT CUSTOM AGENT" else "NEW CUSTOM AGENT",
                style = type.headlineSection,
                color = colors.inkPrimary,
            )
            Text(
                text = "Stored atomically in `~/.gemini/antigravity-cli/agents/<slug>.md`.",
                style = type.bodyCompact,
                color = colors.inkSecondary,
            )

            OutlinedTextField(
                value = form.slug,
                onValueChange = { v ->
                    if (!form.isEditingExisting) {
                        onUpdateForm { it.copy(slug = v) }
                    }
                },
                readOnly = form.isEditingExisting,
                singleLine = true,
                textStyle = type.monoCode,
                label = { Text("Slug ([a-z0-9][a-z0-9_-]{0,63})", style = type.labelTelemetry, color = colors.inkSecondary) },
                placeholder = { Text("code-auditor", style = type.bodyCompact, color = colors.inkMuted) },
                colors = textFieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
            if (form.slug.isNotEmpty() && !slugValid) {
                Text(
                    text = "Slug must start with lowercase letter/digit and use only [a-z0-9_-] (max 64 chars).",
                    style = type.monoMicro,
                    color = colors.signalCrimson,
                )
            }

            OutlinedTextField(
                value = form.description,
                onValueChange = { v -> onUpdateForm { it.copy(description = v) } },
                singleLine = true,
                textStyle = type.bodyCompact,
                label = { Text("Description", style = type.labelTelemetry, color = colors.inkSecondary) },
                colors = textFieldColors,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = form.prompt,
                onValueChange = { v -> onUpdateForm { it.copy(prompt = v) } },
                minLines = 5,
                maxLines = 10,
                textStyle = type.monoCode,
                label = { Text("Markdown System Prompt", style = type.labelTelemetry, color = colors.inkSecondary) },
                colors = textFieldColors,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    enabled = !isMutating,
                    shape = shapes.sm,
                    border = BorderStroke(spacing.hairlineWidth, colors.hairlineStrong),
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = spacing.touchFloor),
                ) {
                    Text("CANCEL", style = type.labelTelemetry, color = colors.inkSecondary)
                }
                Button(
                    onClick = onSubmit,
                    enabled = canSubmit,
                    shape = shapes.sm,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.signalCyan,
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
                    Text("SAVE AGENT", style = type.labelTelemetry)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DestructiveActionConfirmSheet(
    title: String,
    detail: String,
    confirmLabel: String,
    isBusy: Boolean,
    onConfirm: () -> Unit,
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
                    tint = colors.signalCrimson,
                )
                Text(title, style = type.headlineSection, color = colors.inkPrimary)
            }
            Text(detail, style = type.bodyCompact, color = colors.inkSecondary)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    enabled = !isBusy,
                    shape = shapes.sm,
                    border = BorderStroke(spacing.hairlineWidth, colors.hairlineStrong),
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = spacing.touchFloor),
                ) {
                    Text("CANCEL", style = type.labelTelemetry, color = colors.inkSecondary)
                }
                Button(
                    onClick = onConfirm,
                    enabled = !isBusy,
                    shape = shapes.sm,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.signalCrimson,
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
                    Text(confirmLabel, style = type.labelTelemetry)
                }
            }
        }
    }
}
