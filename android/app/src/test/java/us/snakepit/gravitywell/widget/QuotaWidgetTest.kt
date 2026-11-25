package us.snakepit.gravitywell.widget

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import us.snakepit.gravitywell.R
import us.snakepit.gravitywell.ui.observatory.ObservatoryUsageResponseDto
import us.snakepit.gravitywell.ui.observatory.QuotaHealthTier
import us.snakepit.gravitywell.ui.observatory.UsageQuotaBucketDto
import us.snakepit.gravitywell.ui.observatory.UsageQuotaGroupDto

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class QuotaWidgetTest {

    private val sampleUsage = ObservatoryUsageResponseDto(
        status = "SUCCESS",
        cachedAt = "2026-11-24T22:55:00Z",
        stale = false,
        groups = listOf(
            UsageQuotaGroupDto(
                name = "Gemini 4 Argon",
                buckets = listOf(
                    UsageQuotaBucketDto(
                        id = "gemini-argon-wtu-weekly",
                        window = "weekly",
                        remainingFraction = 1.0,
                        remainingPercent = 100.0,
                        resetTime = "2026-11-30T14:32:48Z",
                    ),
                ),
            ),
            UsageQuotaGroupDto(
                name = "Gemini Models",
                description = "Gemini 4 Neon, Gemini 4 Hydrogen, Gemini 3.8 Flash",
                buckets = listOf(
                    UsageQuotaBucketDto(
                        id = "gemini-weekly",
                        name = "Weekly Limit Remaining",
                        window = "weekly",
                        remainingFraction = 0.8908,
                        remainingPercent = 89.1,
                        resetTime = "2026-11-29T17:00:00Z",
                    ),
                    UsageQuotaBucketDto(
                        id = "gemini-5h",
                        name = "Five Hour Limit Remaining",
                        window = "5h",
                        remainingFraction = 0.242,
                        remainingPercent = 24.2,
                        resetTime = "2026-11-25T02:20:00Z",
                    ),
                ),
            ),
            UsageQuotaGroupDto(
                name = "Claude and GPT models",
                buckets = listOf(
                    UsageQuotaBucketDto(
                        id = "3p-weekly",
                        window = "weekly",
                        remainingFraction = 0.96,
                        remainingPercent = 96.0,
                        resetTime = "2026-11-30T14:00:00Z",
                    ),
                    UsageQuotaBucketDto(
                        id = "3p-5h",
                        window = "5h",
                        remainingFraction = 0.075,
                        remainingPercent = 7.5,
                        resetTime = "2026-11-24T23:45:00Z",
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `extracts Gemini Models 5h and weekly buckets ignoring Argon group`() {
        val nowMs = Instant.parse("2026-11-24T23:00:00Z").toEpochMilli()
        val state = QuotaWidgetExtractor.buildDisplayState(
            kind = QuotaWidgetKind.GEMINI,
            hostLabel = "aurora",
            usage = sampleUsage,
            fetchedAtIso = null,
            nowEpochMillis = nowMs,
        )

        assertEquals("Gemini Models", state.title)
        assertEquals("aurora · 5m ago", state.subtitle)
        assertTrue(state.hasHost)

        assertEquals("24%", state.fiveHour.percentText)
        assertEquals(24, state.fiveHour.progress)
        assertEquals(QuotaHealthTier.WARNING, state.fiveHour.tier)
        assertEquals("Resets in 3h 20m", state.fiveHour.resetText)

        assertEquals("89%", state.weekly.percentText)
        assertEquals(89, state.weekly.progress)
        assertEquals(QuotaHealthTier.HEALTHY, state.weekly.tier)
        assertEquals("Resets in 4d 18h", state.weekly.resetText)
    }

    @Test
    fun `extracts Third-Party Claude and GPT 5h and weekly buckets with critical precision`() {
        val nowMs = Instant.parse("2026-11-24T23:00:00Z").toEpochMilli()
        val state = QuotaWidgetExtractor.buildDisplayState(
            kind = QuotaWidgetKind.THIRD_PARTY,
            hostLabel = "aurora",
            usage = sampleUsage,
            fetchedAtIso = null,
            nowEpochMillis = nowMs,
        )

        assertEquals("Claude & GPT", state.title)
        assertEquals("7.5%", state.fiveHour.percentText)
        assertEquals(8, state.fiveHour.progress)
        assertEquals(QuotaHealthTier.CRITICAL, state.fiveHour.tier)
        assertEquals("Resets in 45m", state.fiveHour.resetText)

        assertEquals("96%", state.weekly.percentText)
        assertEquals(96, state.weekly.progress)
        assertEquals(QuotaHealthTier.HEALTHY, state.weekly.tier)
        assertEquals("Resets in 5d 15h", state.weekly.resetText)
    }

    @Test
    fun `handles missing host and offline cached states gracefully`() {
        val nowMs = Instant.parse("2026-11-24T23:00:00Z").toEpochMilli()
        val noHost = QuotaWidgetExtractor.buildDisplayState(
            kind = QuotaWidgetKind.GEMINI,
            hostLabel = null,
            usage = null,
            fetchedAtIso = null,
            nowEpochMillis = nowMs,
        )
        assertFalse(noHost.hasHost)
        assertEquals("Tap to connect host", noHost.subtitle)
        assertEquals("—", noHost.fiveHour.percentText)
        assertFalse(noHost.fiveHour.hasData)

        val offline = QuotaWidgetExtractor.buildDisplayState(
            kind = QuotaWidgetKind.THIRD_PARTY,
            hostLabel = "aurora",
            usage = sampleUsage,
            fetchedAtIso = null,
            nowEpochMillis = nowMs,
            offlineNotice = true,
        )
        assertEquals("aurora · Cached (5m ago)", offline.subtitle)
        assertEquals("96%", offline.weekly.percentText)
    }

    @Test
    fun `buildRemoteViews inflates and binds tier progress bars cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val nowMs = Instant.parse("2026-11-24T23:00:00Z").toEpochMilli()
        val state = QuotaWidgetExtractor.buildDisplayState(
            kind = QuotaWidgetKind.GEMINI,
            hostLabel = "aurora",
            usage = sampleUsage,
            fetchedAtIso = null,
            nowEpochMillis = nowMs,
        )

        val remoteViews = QuotaWidgetUpdater.buildRemoteViews(context, state)
        val applied = remoteViews.apply(context, FrameLayout(context))

        assertEquals("Gemini Models", applied.findViewById<TextView>(R.id.widget_title).text.toString())
        assertEquals("24%", applied.findViewById<TextView>(R.id.widget_5h_percent).text.toString())
        assertEquals("89%", applied.findViewById<TextView>(R.id.widget_weekly_percent).text.toString())

        // 5h is WARNING (24%), Weekly is HEALTHY (89%)
        assertEquals(View.GONE, applied.findViewById<ProgressBar>(R.id.widget_5h_bar_healthy).visibility)
        assertEquals(View.VISIBLE, applied.findViewById<ProgressBar>(R.id.widget_5h_bar_warning).visibility)
        assertEquals(24, applied.findViewById<ProgressBar>(R.id.widget_5h_bar_warning).progress)

        assertEquals(View.VISIBLE, applied.findViewById<ProgressBar>(R.id.widget_weekly_bar_healthy).visibility)
        assertEquals(89, applied.findViewById<ProgressBar>(R.id.widget_weekly_bar_healthy).progress)
        assertEquals(View.GONE, applied.findViewById<ProgressBar>(R.id.widget_weekly_bar_warning).visibility)
    }
}
