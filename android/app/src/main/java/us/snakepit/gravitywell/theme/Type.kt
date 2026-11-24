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
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.3).sp,
    ),
    val titleBar: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp,
    ),
    val bodyProse: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 15.5.sp,
        lineHeight = 23.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
    ),
    val bodyCompact: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 13.5.sp,
        lineHeight = 19.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
    ),
    val labelUi: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.1.sp,
    ),
    val labelSmallUi: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 11.5.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.15.sp,
    ),
    val labelTelemetry: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.5.sp,
        fontFeatureSettings = "tnum",
    ),
    val monoCode: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.5.sp,
        lineHeight = 18.5.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
        fontFeatureSettings = "tnum",
    ),
    val monoMicro: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.2.sp,
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
    labelLarge = DefaultGravityWellTypography.labelUi,
    labelMedium = DefaultGravityWellTypography.labelSmallUi,
    labelSmall = DefaultGravityWellTypography.monoMicro,
)
