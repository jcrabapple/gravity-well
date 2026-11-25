package us.snakepit.gravitywell.ui.console

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import us.snakepit.gravitywell.theme.GravityWellTheme

@Composable
fun VoiceModeCallPanel(
    voiceState: VoiceModeUiState,
    onToggleMicMuted: () -> Unit,
    onToggleAgentMuted: () -> Unit,
    onCycleSpeed: () -> Unit,
    onOpenVoicePicker: () -> Unit,
    onToggleMinimized: () -> Unit,
    onInterruptSpeaking: () -> Unit,
    onEndCall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GravityWellTheme.colors
    val type = GravityWellTheme.typography
    val shapes = GravityWellTheme.shapes
    val spacing = GravityWellTheme.spacing

    val isUserTurn = voiceState.phase == VoiceModePhase.LISTENING && voiceState.partialTranscript.isNotBlank()
    val speakerLabel = if (isUserTurn) "You" else "Antigravity"
    val speakerAccent = when {
        voiceState.micMuted -> colors.signalCrimson
        isUserTurn -> colors.signalEmerald
        voiceState.phase == VoiceModePhase.SPEAKING -> colors.signalCyan
        else -> colors.signalViolet
    }

    val pulseTransition = rememberInfiniteTransition(label = "voice_pulse")
    val idleWave by pulseTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(720), RepeatMode.Reverse),
        label = "voice_wave",
    )
    val smoothRms by animateFloatAsState(
        targetValue = voiceState.rmsLevel.coerceIn(0f, 1f),
        animationSpec = tween(110),
        label = "voice_rms",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.md, vertical = spacing.xs)
            .clip(shapes.lg)
            .background(colors.stratumBase)
            .border(spacing.hairlineWidth, speakerAccent.copy(alpha = 0.65f), shapes.lg),
    ) {
        // Call Control Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.sm, vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Speaker Turn Pill + Elapsed Timer
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable {
                        if (voiceState.phase == VoiceModePhase.SPEAKING) onInterruptSpeaking()
                    }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(speakerAccent.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isUserTurn) Icons.Rounded.Mic else Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        tint = speakerAccent,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Column {
                    Text(
                        text = speakerLabel,
                        style = type.labelUi,
                        color = colors.inkPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = formatVoiceElapsed(voiceState.elapsedSeconds),
                        style = type.monoMicro,
                        color = colors.inkMuted,
                    )
                }
            }

            VoiceControlCircle(
                icon = if (voiceState.micMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                contentDescription = if (voiceState.micMuted) "Unmute microphone" else "Mute microphone",
                tint = if (voiceState.micMuted) colors.inkInverse else colors.inkPrimary,
                containerColor = if (voiceState.micMuted) colors.signalCrimson else colors.stratumRaised,
                onClick = onToggleMicMuted,
            )

            VoiceControlCircle(
                icon = if (voiceState.agentMuted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                contentDescription = if (voiceState.agentMuted) "Unmute Antigravity" else "Mute Antigravity",
                tint = if (voiceState.agentMuted) colors.signalAmber else colors.inkPrimary,
                containerColor = if (voiceState.agentMuted) colors.signalAmberDim else colors.stratumRaised,
                onClick = onToggleAgentMuted,
            )

            Box(
                modifier = Modifier
                    .height(34.dp)
                    .clip(shapes.pill)
                    .background(colors.stratumRaised)
                    .clickable(onClick = onCycleSpeed)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = String.format(Locale.US, "%.2gx", voiceState.speed),
                    style = type.monoMicro,
                    color = colors.signalCyan,
                )
            }

            VoiceControlCircle(
                icon = Icons.Rounded.RecordVoiceOver,
                contentDescription = "Choose voice",
                tint = colors.inkSecondary,
                containerColor = colors.stratumRaised,
                onClick = onOpenVoicePicker,
            )

            VoiceControlCircle(
                icon = if (voiceState.minimized) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = if (voiceState.minimized) "Expand voice panel" else "Minimize voice panel",
                tint = colors.inkSecondary,
                containerColor = colors.stratumRaised,
                onClick = onToggleMinimized,
            )

            Button(
                onClick = onEndCall,
                shape = shapes.pill,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.signalCrimson,
                    contentColor = colors.inkInverse,
                ),
                modifier = Modifier.height(34.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.CallEnd,
                    contentDescription = "End voice mode",
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        if (!voiceState.minimized) {
            HorizontalDivider(thickness = spacing.hairlineWidth, color = colors.hairline)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = voiceState.phase == VoiceModePhase.SPEAKING, onClick = onInterruptSpeaking)
                    .padding(horizontal = spacing.md, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                // Live reactive 5-bar waveform
                Row(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.height(26.dp),
                ) {
                    val multipliers = listOf(0.55f, 0.9f, 1.0f, 0.8f, 0.5f)
                    val energy = when (voiceState.phase) {
                        VoiceModePhase.SPEAKING -> idleWave
                        VoiceModePhase.LISTENING -> if (voiceState.micMuted) 0.15f else maxOf(0.25f, smoothRms)
                        VoiceModePhase.PROCESSING -> idleWave * 0.6f
                        else -> 0.2f
                    }
                    multipliers.forEach { mult ->
                        val barDp = (6f + 18f * energy * mult).dp
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(barDp)
                                .clip(shapes.pill)
                                .background(speakerAccent),
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    val primaryLine = when {
                        voiceState.errorMessage != null -> voiceState.errorMessage
                        voiceState.partialTranscript.isNotBlank() -> voiceState.partialTranscript
                        voiceState.phase == VoiceModePhase.SPEAKING && voiceState.lastSpokenReply.isNotBlank() ->
                            voiceState.lastSpokenReply
                        else -> voiceState.statusHint
                    }
                    Text(
                        text = primaryLine,
                        style = type.bodyCompact,
                        color = if (voiceState.errorMessage != null) colors.signalCrimson else colors.inkPrimary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val hintSub = when (voiceState.phase) {
                        VoiceModePhase.SPEAKING -> "Tap here to interrupt and speak"
                        VoiceModePhase.PROCESSING -> "Say \"stop\" to cancel · \"bye\" to end"
                        else -> "Say \"bye\" to end voice chat"
                    }
                    Text(
                        text = hintSub,
                        style = type.labelSmallUi,
                        color = colors.inkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceControlCircle(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    containerColor: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(containerColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicePickerSheet(
    voices: List<VoiceOption>,
    selectedVoiceId: String?,
    onSelectVoice: (String) -> Unit,
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
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Choose Antigravity's voice", style = type.headlineSection, color = colors.inkPrimary)
                Text(
                    text = "Tap a voice to preview and apply immediately.",
                    style = type.bodyCompact,
                    color = colors.inkSecondary,
                )
            }

            if (voices.isEmpty()) {
                Text(
                    text = "Using system default speech voice.",
                    style = type.bodyCompact,
                    color = colors.inkMuted,
                    modifier = Modifier.padding(vertical = spacing.md),
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(spacing.xs),
                    modifier = Modifier.fillMaxWidth().height(260.dp),
                ) {
                    items(voices, key = { it.id }) { opt ->
                        val selected = opt.id == selectedVoiceId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = spacing.touchFloor)
                                .clip(shapes.md)
                                .background(if (selected) colors.signalCyanDim else colors.stratumRaised)
                                .border(
                                    spacing.hairlineWidth,
                                    if (selected) colors.signalCyan else colors.hairline,
                                    shapes.md,
                                )
                                .clickable { onSelectVoice(opt.id) }
                                .padding(horizontal = spacing.md, vertical = spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = opt.displayName,
                                    style = type.bodyCompact,
                                    color = if (selected) colors.signalCyan else colors.inkPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = opt.localeTag,
                                    style = type.monoMicro,
                                    color = colors.inkMuted,
                                )
                            }
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = "Selected",
                                    tint = colors.signalCyan,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(spacing.md))
        }
    }
}

internal fun formatVoiceElapsed(totalSeconds: Long): String {
    val mins = totalSeconds / 60L
    val secs = totalSeconds % 60L
    return String.format(Locale.US, "%d:%02d", mins, secs)
}
