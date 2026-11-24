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
    val xs: RoundedCornerShape = RoundedCornerShape(4.dp),
    val sm: RoundedCornerShape = RoundedCornerShape(10.dp),
    val md: RoundedCornerShape = RoundedCornerShape(16.dp),
    val lg: RoundedCornerShape = RoundedCornerShape(20.dp),
    val pill: RoundedCornerShape = RoundedCornerShape(999.dp),
    val userBubble: RoundedCornerShape = RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 20.dp,
        bottomStart = 20.dp,
        bottomEnd = 6.dp,
    ),
    val sheetTop: RoundedCornerShape = RoundedCornerShape(
        topStart = 24.dp,
        topEnd = 24.dp,
        bottomStart = 0.dp,
        bottomEnd = 0.dp,
    ),
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
    val proseHorizontalMargin: Dp = 18.dp,
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
    large = DefaultGravityWellShapes.lg,
    extraLarge = DefaultGravityWellShapes.sheetTop,
)
