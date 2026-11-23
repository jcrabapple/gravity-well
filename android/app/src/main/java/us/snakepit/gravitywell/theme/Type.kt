package us.snakepit.gravitywell.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Immutable
data class GravityWellTypography(
    val displayMetric: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 24.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.5).sp,
        fontFeatureSettings = "tnum",
    ),
    val headlineSection: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp,
    ),
    val titleBar: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp,
    ),
    val bodyProse: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
    ),
    val bodyCompact: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.1.sp,
    ),
    val labelTelemetry: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.6.sp,
        fontFeatureSettings = "tnum",
    ),
    val monoCode: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.5.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
        fontFeatureSettings = "tnum",
    ),
    val monoMicro: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 10.5.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.4.sp,
        fontFeatureSettings = "tnum",
    ),
)

val DefaultGravityWellTypography = GravityWellTypography()

val LocalGravityWellTypography = staticCompositionLocalOf { DefaultGravityWellTypography }

val GravityWellMaterialTypography = Typography(
    displaySmall = DefaultGravityWellTypography.displayMetric,
    headlineSmall = DefaultGravityWellTypography.headlineSection,
    titleMedium = DefaultGravityWellTypography.titleBar,
    bodyLarge = DefaultGravityWellTypography.bodyProse,
    bodyMedium = DefaultGravityWellTypography.bodyCompact,
    labelMedium = DefaultGravityWellTypography.labelTelemetry,
    labelSmall = DefaultGravityWellTypography.monoMicro,
)
