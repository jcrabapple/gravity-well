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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WarningAmber
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
        shape = shapes.sheetTop,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.xl, vertical = spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Add MCP server", style = type.headlineSection, color = colors.inkPrimary)
                Text(
                    text = "Secret env vars and headers are masked after saving.",
                    style = type.bodyCompact,
                    color = colors.inkSecondary,
                )
            }

            // Type pill toggle: stdio | http
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shapes.pill)
                    .background(colors.stratumRaised)
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                listOf("stdio" to "Local (stdio)", "http" to "Remote (HTTP)").forEach { (kind, label) ->
                    val selected = form.type == kind
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 40.dp)
                            .clip(shapes.pill)
                            .background(if (selected) colors.signalCyanDim else colors.stratumRaised)
                            .clickable(role = Role.Tab) { onUpdateForm { it.copy(type = kind) } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            style = type.labelUi,
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
                label = { Text("Server name", style = type.labelSmallUi, color = colors.inkSecondary) },
                placeholder = { Text("e.g. codegraph", style = type.bodyCompact, color = colors.inkMuted) },
                shape = shapes.sm,
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
                        text = if (form.type == "http") "Server URL" else "Command",
                        style = type.labelSmallUi,
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
                shape = shapes.sm,
                colors = textFieldColors,
                modifier = Modifier.fillMaxWidth(),
            )

            if (form.type == "stdio") {
                OutlinedTextField(
                    value = form.argsLine,
                    onValueChange = { v -> onUpdateForm { it.copy(argsLine = v) } },
                    singleLine = true,
                    textStyle = type.monoCode,
                    label = { Text("Arguments (space-separated)", style = type.labelSmallUi, color = colors.inkSecondary) },
                    placeholder = { Text("mcp-server-git --repository .", style = type.bodyCompact, color = colors.inkMuted) },
                    shape = shapes.sm,
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
                label = { Text("Environment variables (NAME=value per line)", style = type.labelSmallUi, color = colors.inkSecondary) },
                shape = shapes.sm,
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
                    label = { Text("HTTP headers (Header: value per line)", style = type.labelSmallUi, color = colors.inkSecondary) },
                    shape = shapes.sm,
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
                    shape = shapes.pill,
                    border = BorderStroke(spacing.hairlineWidth, colors.hairlineStrong),
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = spacing.touchFloor),
                ) {
                    Text("Cancel", style = type.labelUi, color = colors.inkSecondary)
                }
                Button(
                    onClick = onSubmit,
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
                    if (isMutating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = colors.inkInverse,
                        )
                        Spacer(modifier = Modifier.width(spacing.xs))
                    }
                    Text("Save server", style = type.labelUi)
                }
            }
            Spacer(modifier = Modifier.height(spacing.md))
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
        shape = shapes.sheetTop,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.xl, vertical = spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = if (form.isEditingExisting) "Edit custom agent" else "New custom agent",
                    style = type.headlineSection,
                    color = colors.inkPrimary,
                )
                Text(
                    text = "Saved to ~/.gemini/antigravity-cli/agents/<slug>.md",
                    style = type.bodyCompact,
                    color = colors.inkSecondary,
                )
            }

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
                label = { Text("Agent slug", style = type.labelSmallUi, color = colors.inkSecondary) },
                placeholder = { Text("code-auditor", style = type.bodyCompact, color = colors.inkMuted) },
                shape = shapes.sm,
                colors = textFieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
            if (form.slug.isNotEmpty() && !slugValid) {
                Text(
                    text = "Start with a lowercase letter or digit and use only a-z, 0-9, _ or - (max 64 chars).",
                    style = type.bodyCompact,
                    color = colors.signalCrimson,
                )
            }

            OutlinedTextField(
                value = form.description,
                onValueChange = { v -> onUpdateForm { it.copy(description = v) } },
                singleLine = true,
                textStyle = type.bodyProse,
                label = { Text("Description", style = type.labelSmallUi, color = colors.inkSecondary) },
                shape = shapes.sm,
                colors = textFieldColors,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = form.prompt,
                onValueChange = { v -> onUpdateForm { it.copy(prompt = v) } },
                minLines = 5,
                maxLines = 10,
                textStyle = type.monoCode,
                label = { Text("System prompt (Markdown)", style = type.labelSmallUi, color = colors.inkSecondary) },
                shape = shapes.sm,
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
                    shape = shapes.pill,
                    border = BorderStroke(spacing.hairlineWidth, colors.hairlineStrong),
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = spacing.touchFloor),
                ) {
                    Text("Cancel", style = type.labelUi, color = colors.inkSecondary)
                }
                Button(
                    onClick = onSubmit,
                    enabled = canSubmit,
                    shape = shapes.pill,
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
                    Text("Save agent", style = type.labelUi)
                }
            }
            Spacer(modifier = Modifier.height(spacing.md))
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
                    shape = shapes.pill,
                    border = BorderStroke(spacing.hairlineWidth, colors.hairlineStrong),
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = spacing.touchFloor),
                ) {
                    Text("Cancel", style = type.labelUi, color = colors.inkSecondary)
                }
                Button(
                    onClick = onConfirm,
                    enabled = !isBusy,
                    shape = shapes.pill,
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
                    Text(confirmLabel, style = type.labelUi)
                }
            }
            Spacer(modifier = Modifier.height(spacing.md))
        }
    }
}
