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
    fun `color tokens match Vernier Rig creative direction hex palette`() {
        val c = DefaultGravityWellColors
        assertEquals(Color(0xFF0B0D10), c.chassisVoid)
        assertEquals(Color(0xFF101318), c.stratumBase)
        assertEquals(Color(0xFF161B22), c.stratumRaised)
        assertEquals(Color(0xFF171D26), c.stratumUser)
        assertEquals(Color(0xFF080A0D), c.stratumWell)

        assertEquals(Color(0xFF242B36), c.hairline)
        assertEquals(Color(0xFF343E4F), c.hairlineStrong)

        assertEquals(Color(0xFFE6EDF3), c.inkPrimary)
        assertEquals(Color(0xFF9BA7B8), c.inkSecondary)
        assertEquals(Color(0xFF657183), c.inkMuted)
        assertEquals(Color(0xFF0B0D10), c.inkInverse)

        assertEquals(Color(0xFF38BDF8), c.signalCyan)
        assertEquals(Color(0xFF122634), c.signalCyanDim)
        assertEquals(Color(0xFFF59E0B), c.signalAmber)
        assertEquals(Color(0xFF2A1F0D), c.signalAmberDim)
        assertEquals(Color(0xFF34D399), c.signalEmerald)
        assertEquals(Color(0xFF112A22), c.signalEmeraldDim)
        assertEquals(Color(0xFFF87171), c.signalCrimson)
        assertEquals(Color(0xFF33171B), c.signalCrimsonDim)
        assertEquals(Color(0xFFA78BFA), c.signalViolet)
        assertEquals(Color(0xFF1F1933), c.signalVioletDim)

        assertEquals(Color(0xFF112A22), c.diffAddBg)
        assertEquals(Color(0xFF1B4737), c.diffAddWordBg)
        assertEquals(Color(0xFF34D399), c.diffAddInk)
        assertEquals(Color(0xFF33171B), c.diffDelBg)
        assertEquals(Color(0xFF542128), c.diffDelWordBg)
        assertEquals(Color(0xFFF87171), c.diffDelInk)
        assertEquals(Color(0xFF141A24), c.diffHunkBg)
    }

    @Test
    fun `material dark color scheme maps Vernier Rig semantic tokens`() {
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
        listOf(t.headlineSection, t.titleBar, t.bodyProse, t.bodyCompact).forEach { style ->
            assertEquals(FontFamily.SansSerif, style.fontFamily)
        }
    }

    @Test
    fun `shapes and spacing disallow chat bubbles and preserve touch floor`() {
        val shapes = DefaultGravityWellShapes
        val spacing = DefaultGravityWellSpacing
        assertEquals(0.dp, spacing.elevationZero)
        assertEquals(48.dp, spacing.touchFloor)
        assertEquals(16.dp, spacing.proseHorizontalMargin)
        assertEquals(1.dp, spacing.hairlineWidth)
        assertEquals(3.dp, spacing.statusRailWidth)
        assertTrue(shapes.none != shapes.md)
    }
}
