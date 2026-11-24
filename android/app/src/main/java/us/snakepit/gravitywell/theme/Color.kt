package us.snakepit.gravitywell.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Gravity Well modern dark chat palette (docs/creative/DIRECTION-gravity-well.md).
 * Literal Color(0x...) declarations live exclusively in this file.
 */
object GravityWellPalette {
    val ChassisVoid = Color(0xFF0E1117)
    val StratumBase = Color(0xFF151A23)
    val StratumRaised = Color(0xFF1E2533)
    val StratumUser = Color(0xFF1C3554)
    val StratumWell = Color(0xFF0B0E14)

    val Hairline = Color(0xFF252D3D)
    val HairlineStrong = Color(0xFF364257)

    val InkPrimary = Color(0xFFEDF2F7)
    val InkSecondary = Color(0xFF9BA7BA)
    val InkMuted = Color(0xFF6E7B90)
    val InkInverse = Color(0xFF0B0E14)

    val SignalCyan = Color(0xFF38BDF8)
    val SignalCyanDim = Color(0xFF13293D)
    val SignalAmber = Color(0xFFF59E0B)
    val SignalAmberDim = Color(0xFF2E220F)
    val SignalEmerald = Color(0xFF34D399)
    val SignalEmeraldDim = Color(0xFF112C23)
    val SignalCrimson = Color(0xFFF87171)
    val SignalCrimsonDim = Color(0xFF35191F)
    val SignalViolet = Color(0xFFA78BFA)
    val SignalVioletDim = Color(0xFF241C3A)

    val DiffAddBg = Color(0xFF112C23)
    val DiffAddWordBg = Color(0xFF1D4D3C)
    val DiffAddInk = Color(0xFF34D399)
    val DiffDelBg = Color(0xFF35191F)
    val DiffDelWordBg = Color(0xFF5A232B)
    val DiffDelInk = Color(0xFFF87171)
    val DiffHunkBg = Color(0xFF161D29)

    val ScrimOverlay = Color(0xCC07090D)
}

@Immutable
data class GravityWellColors(
    val chassisVoid: Color = GravityWellPalette.ChassisVoid,
    val stratumBase: Color = GravityWellPalette.StratumBase,
    val stratumRaised: Color = GravityWellPalette.StratumRaised,
    val stratumUser: Color = GravityWellPalette.StratumUser,
    val stratumWell: Color = GravityWellPalette.StratumWell,
    val hairline: Color = GravityWellPalette.Hairline,
    val hairlineStrong: Color = GravityWellPalette.HairlineStrong,
    val inkPrimary: Color = GravityWellPalette.InkPrimary,
    val inkSecondary: Color = GravityWellPalette.InkSecondary,
    val inkMuted: Color = GravityWellPalette.InkMuted,
    val inkInverse: Color = GravityWellPalette.InkInverse,
    val signalCyan: Color = GravityWellPalette.SignalCyan,
    val signalCyanDim: Color = GravityWellPalette.SignalCyanDim,
    val signalAmber: Color = GravityWellPalette.SignalAmber,
    val signalAmberDim: Color = GravityWellPalette.SignalAmberDim,
    val signalEmerald: Color = GravityWellPalette.SignalEmerald,
    val signalEmeraldDim: Color = GravityWellPalette.SignalEmeraldDim,
    val signalCrimson: Color = GravityWellPalette.SignalCrimson,
    val signalCrimsonDim: Color = GravityWellPalette.SignalCrimsonDim,
    val signalViolet: Color = GravityWellPalette.SignalViolet,
    val signalVioletDim: Color = GravityWellPalette.SignalVioletDim,
    val diffAddBg: Color = GravityWellPalette.DiffAddBg,
    val diffAddWordBg: Color = GravityWellPalette.DiffAddWordBg,
    val diffAddInk: Color = GravityWellPalette.DiffAddInk,
    val diffDelBg: Color = GravityWellPalette.DiffDelBg,
    val diffDelWordBg: Color = GravityWellPalette.DiffDelWordBg,
    val diffDelInk: Color = GravityWellPalette.DiffDelInk,
    val diffHunkBg: Color = GravityWellPalette.DiffHunkBg,
)

val DefaultGravityWellColors = GravityWellColors()

val LocalGravityWellColors = staticCompositionLocalOf { DefaultGravityWellColors }

val GravityWellDarkColorScheme: ColorScheme = darkColorScheme(
    primary = GravityWellPalette.SignalCyan,
    onPrimary = GravityWellPalette.InkInverse,
    primaryContainer = GravityWellPalette.SignalCyanDim,
    onPrimaryContainer = GravityWellPalette.InkPrimary,
    secondary = GravityWellPalette.SignalAmber,
    onSecondary = GravityWellPalette.InkInverse,
    secondaryContainer = GravityWellPalette.SignalAmberDim,
    onSecondaryContainer = GravityWellPalette.InkPrimary,
    tertiary = GravityWellPalette.SignalViolet,
    onTertiary = GravityWellPalette.InkInverse,
    tertiaryContainer = GravityWellPalette.SignalVioletDim,
    onTertiaryContainer = GravityWellPalette.InkPrimary,
    background = GravityWellPalette.ChassisVoid,
    onBackground = GravityWellPalette.InkPrimary,
    surface = GravityWellPalette.StratumBase,
    onSurface = GravityWellPalette.InkPrimary,
    surfaceVariant = GravityWellPalette.StratumRaised,
    onSurfaceVariant = GravityWellPalette.InkSecondary,
    outline = GravityWellPalette.HairlineStrong,
    outlineVariant = GravityWellPalette.Hairline,
    error = GravityWellPalette.SignalCrimson,
    onError = GravityWellPalette.InkInverse,
    errorContainer = GravityWellPalette.SignalCrimsonDim,
    onErrorContainer = GravityWellPalette.SignalCrimson,
    scrim = GravityWellPalette.ScrimOverlay,
)
