package us.snakepit.gravitywell.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTokensTest {

    @Test
    fun `dynamicColor is strictly disabled`() {
        assertFalse(GravityWellTheme.DYNAMIC_COLOR)
    }

    @Test
    fun `color tokens match refined modern dark chat palette`() {
        val c = DefaultGravityWellColors
        assertEquals(Color(0xFF0E1117), c.chassisVoid)
        assertEquals(Color(0xFF151A23), c.stratumBase)
        assertEquals(Color(0xFF1E2533), c.stratumRaised)
        assertEquals(Color(0xFF1C3554), c.stratumUser)
        assertEquals(Color(0xFF0B0E14), c.stratumWell)

        assertEquals(Color(0xFF252D3D), c.hairline)
        assertEquals(Color(0xFF364257), c.hairlineStrong)

        assertEquals(Color(0xFFEDF2F7), c.inkPrimary)
        assertEquals(Color(0xFF9BA7BA), c.inkSecondary)
        assertEquals(Color(0xFF6E7B90), c.inkMuted)
        assertEquals(Color(0xFF0B0E14), c.inkInverse)

        assertEquals(Color(0xFF38BDF8), c.signalCyan)
        assertEquals(Color(0xFF13293D), c.signalCyanDim)
        assertEquals(Color(0xFFF59E0B), c.signalAmber)
        assertEquals(Color(0xFF2E220F), c.signalAmberDim)
        assertEquals(Color(0xFF34D399), c.signalEmerald)
        assertEquals(Color(0xFF112C23), c.signalEmeraldDim)
        assertEquals(Color(0xFFF87171), c.signalCrimson)
        assertEquals(Color(0xFF35191F), c.signalCrimsonDim)
        assertEquals(Color(0xFFA78BFA), c.signalViolet)
        assertEquals(Color(0xFF241C3A), c.signalVioletDim)

        assertEquals(Color(0xFF112C23), c.diffAddBg)
        assertEquals(Color(0xFF1D4D3C), c.diffAddWordBg)
        assertEquals(Color(0xFF34D399), c.diffAddInk)
        assertEquals(Color(0xFF35191F), c.diffDelBg)
        assertEquals(Color(0xFF5A232B), c.diffDelWordBg)
        assertEquals(Color(0xFFF87171), c.diffDelInk)
        assertEquals(Color(0xFF161D29), c.diffHunkBg)
    }

    @Test
    fun `material dark color scheme maps semantic tokens`() {
        val scheme = GravityWellDarkColorScheme
        assertEquals(GravityWellPalette.SignalCyan, scheme.primary)
        assertEquals(GravityWellPalette.InkInverse, scheme.onPrimary)
        assertEquals(GravityWellPalette.SignalAmber, scheme.secondary)
        assertEquals(GravityWellPalette.SignalViolet, scheme.tertiary)
        assertEquals(GravityWellPalette.ChassisVoid, scheme.background)
        assertEquals(GravityWellPalette.StratumBase, scheme.surface)
        assertEquals(GravityWellPalette.StratumRaised, scheme.surfaceVariant)
        assertEquals(GravityWellPalette.HairlineStrong, scheme.outline)
        assertEquals(GravityWellPalette.Hairline, scheme.outlineVariant)
        assertEquals(GravityWellPalette.SignalCrimson, scheme.error)
    }

    @Test
    fun `telemetry and code typography enforce tabular monospace figures`() {
        val t = DefaultGravityWellTypography
        listOf(t.displayMetric, t.labelTelemetry, t.monoCode, t.monoMicro).forEach { style ->
            assertEquals(FontFamily.Monospace, style.fontFamily)
            assertEquals("tnum", style.fontFeatureSettings)
        }
        listOf(t.headlineSection, t.titleBar, t.bodyProse, t.bodyCompact, t.labelUi, t.labelSmallUi).forEach { style ->
            assertEquals(FontFamily.SansSerif, style.fontFamily)
        }
    }

    @Test
    fun `shapes and spacing provide chat bubble geometry and preserve touch floor`() {
        val shapes = DefaultGravityWellShapes
        val spacing = DefaultGravityWellSpacing
        assertEquals(0.dp, spacing.elevationZero)
        assertEquals(48.dp, spacing.touchFloor)
        assertEquals(18.dp, spacing.proseHorizontalMargin)
        assertEquals(1.dp, spacing.hairlineWidth)
        assertEquals(3.dp, spacing.statusRailWidth)
        assertTrue(shapes.none != shapes.md)
        assertTrue(shapes.userBubble != shapes.sm)
        assertTrue(shapes.pill != shapes.md)
    }
}
