package us.snakepit.gravitywell.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class GravityWellShapes(
    val none: RoundedCornerShape = RoundedCornerShape(0.dp),
    val xs: RoundedCornerShape = RoundedCornerShape(2.dp),
    val sm: RoundedCornerShape = RoundedCornerShape(4.dp),
    val md: RoundedCornerShape = RoundedCornerShape(8.dp),
)

@Immutable
data class GravityWellSpacingTokens(
    val unit: Dp = 4.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 20.dp,
    val xxl: Dp = 24.dp,
    val proseHorizontalMargin: Dp = 16.dp,
    val touchFloor: Dp = 48.dp,
    val hairlineWidth: Dp = 1.dp,
    val statusRailWidth: Dp = 3.dp,
    val elevationZero: Dp = 0.dp,
)

val DefaultGravityWellShapes = GravityWellShapes()

val DefaultGravityWellSpacing = GravityWellSpacingTokens()

val LocalGravityWellShapes = staticCompositionLocalOf { DefaultGravityWellShapes }

val LocalGravityWellSpacing = staticCompositionLocalOf { DefaultGravityWellSpacing }

val GravityWellMaterialShapes = Shapes(
    extraSmall = DefaultGravityWellShapes.xs,
    small = DefaultGravityWellShapes.sm,
    medium = DefaultGravityWellShapes.md,
    large = DefaultGravityWellShapes.md,
    extraLarge = DefaultGravityWellShapes.md,
)
