package us.snakepit.gravitywell.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

object GravityWellTheme {
    const val DYNAMIC_COLOR: Boolean = false

    val colors: GravityWellColors
        @Composable
        @ReadOnlyComposable
        get() = LocalGravityWellColors.current

    val typography: GravityWellTypography
        @Composable
        @ReadOnlyComposable
        get() = LocalGravityWellTypography.current

    val shapes: GravityWellShapes
        @Composable
        @ReadOnlyComposable
        get() = LocalGravityWellShapes.current

    val spacing: GravityWellSpacingTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalGravityWellSpacing.current
}

/**
 * Canonical dark-only Vernier Rig theme.
 * dynamicColor is strictly disabled (false) so system wallpaper colors never leak in.
 */
@Composable
fun GravityWellTheme(
    @Suppress("UNUSED_PARAMETER") dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    // Hard invariant per DIRECTION-gravity-well.md: dynamicColor is always false.
    val useDynamicColor = false
    check(!useDynamicColor)

    CompositionLocalProvider(
        LocalGravityWellColors provides DefaultGravityWellColors,
        LocalGravityWellTypography provides DefaultGravityWellTypography,
        LocalGravityWellShapes provides DefaultGravityWellShapes,
        LocalGravityWellSpacing provides DefaultGravityWellSpacing,
    ) {
        MaterialTheme(
            colorScheme = GravityWellDarkColorScheme,
            typography = GravityWellMaterialTypography,
            shapes = GravityWellMaterialShapes,
            content = content,
        )
    }
}
