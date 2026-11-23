package us.snakepit.gravitywell.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Vernier Rig color palette (docs/creative/DIRECTION-gravity-well.md).
 * Literal Color(0x...) declarations live exclusively in this file.
 */
object GravityWellPalette {
    val ChassisVoid = Color(0xFF0B0D10)
    val StratumBase = Color(0xFF101318)
    val StratumRaised = Color(0xFF161B22)
    val StratumUser = Color(0xFF171D26)
    val StratumWell = Color(0xFF080A0D)

    val Hairline = Color(0xFF242B36)
    val HairlineStrong = Color(0xFF343E4F)

    val InkPrimary = Color(0xFFE6EDF3)
    val InkSecondary = Color(0xFF9BA7B8)
    val InkMuted = Color(0xFF657183)
    val InkInverse = Color(0xFF0B0D10)

    val SignalCyan = Color(0xFF38BDF8)
    val SignalCyanDim = Color(0xFF122634)
    val SignalAmber = Color(0xFFF59E0B)
    val SignalAmberDim = Color(0xFF2A1F0D)
    val SignalEmerald = Color(0xFF34D399)
    val SignalEmeraldDim = Color(0xFF112A22)
    val SignalCrimson = Color(0xFFF87171)
    val SignalCrimsonDim = Color(0xFF33171B)
    val SignalViolet = Color(0xFFA78BFA)
    val SignalVioletDim = Color(0xFF1F1933)

    val DiffAddBg = Color(0xFF112A22)
    val DiffAddWordBg = Color(0xFF1B4737)
    val DiffAddInk = Color(0xFF34D399)
    val DiffDelBg = Color(0xFF33171B)
    val DiffDelWordBg = Color(0xFF542128)
    val DiffDelInk = Color(0xFFF87171)
    val DiffHunkBg = Color(0xFF141A24)

    val ScrimOverlay = Color(0xCC050709)
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
