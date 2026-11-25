package us.snakepit.gravitywell.widget

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale
import kotlin.math.roundToInt
import us.snakepit.gravitywell.ui.observatory.ObservatoryTelemetryMath
import us.snakepit.gravitywell.ui.observatory.ObservatoryUsageResponseDto
import us.snakepit.gravitywell.ui.observatory.QuotaHealthTier
import us.snakepit.gravitywell.ui.observatory.UsageQuotaBucketDto
import us.snakepit.gravitywell.ui.observatory.UsageQuotaGroupDto

enum class QuotaWidgetKind(
    val title: String,
) {
    GEMINI("Gemini Models"),
    THIRD_PARTY("Claude & GPT"),
}

data class WidgetBucketDisplay(
    val percentText: String,
    val progress: Int,
    val resetText: String,
    val tier: QuotaHealthTier,
    val hasData: Boolean,
)

data class QuotaWidgetDisplayState(
    val kind: QuotaWidgetKind,
    val title: String,
    val subtitle: String,
    val fiveHour: WidgetBucketDisplay,
    val weekly: WidgetBucketDisplay,
    val hasHost: Boolean,
    val isRefreshing: Boolean = false,
)

object QuotaWidgetExtractor {

    fun buildDisplayState(
        kind: QuotaWidgetKind,
        hostLabel: String?,
        usage: ObservatoryUsageResponseDto?,
        fetchedAtIso: String?,
        nowEpochMillis: Long = System.currentTimeMillis(),
        isRefreshing: Boolean = false,
        offlineNotice: Boolean = false,
    ): QuotaWidgetDisplayState {
        if (hostLabel.isNullOrBlank()) {
            val emptyBucket = placeholderBucket("Connect a host in Gravity Well")
            return QuotaWidgetDisplayState(
                kind = kind,
                title = kind.title,
                subtitle = "Tap to connect host",
                fiveHour = emptyBucket,
                weekly = placeholderBucket("Open app to get started"),
                hasHost = false,
                isRefreshing = false,
            )
        }

        val group = usage?.let { resolveGroup(it, kind) }
        val fiveHourBucket = group?.let { findBucket(it.buckets, isFiveHour = true) }
        val weeklyBucket = group?.let { findBucket(it.buckets, isFiveHour = false) }

        val updatedStamp = usage?.cachedAt?.ifBlank { null } ?: fetchedAtIso
        val subtitle = when {
            isRefreshing -> "$hostLabel · Refreshing..."
            offlineNotice || usage?.stale == true -> {
                val rel = formatRelativeAge(updatedStamp, nowEpochMillis)
                if (rel != null) "$hostLabel · Cached ($rel)" else "$hostLabel · Offline"
            }
            updatedStamp != null -> {
                val rel = formatRelativeAge(updatedStamp, nowEpochMillis)
                if (rel != null) "$hostLabel · $rel" else hostLabel
            }
            else -> "$hostLabel · Tap refresh"
        }

        return QuotaWidgetDisplayState(
            kind = kind,
            title = kind.title,
            subtitle = subtitle,
            fiveHour = fiveHourBucket?.toDisplay(nowEpochMillis)
                ?: placeholderBucket(if (usage == null) "Tap refresh to sync" else "No 5h limit"),
            weekly = weeklyBucket?.toDisplay(nowEpochMillis)
                ?: placeholderBucket(if (usage == null) "Tap refresh to sync" else "No weekly limit"),
            hasHost = true,
            isRefreshing = isRefreshing,
        )
    }

    internal fun resolveGroup(
        usage: ObservatoryUsageResponseDto,
        kind: QuotaWidgetKind,
    ): UsageQuotaGroupDto? {
        val groups = usage.groups
        if (groups.isEmpty()) return null

        return when (kind) {
            QuotaWidgetKind.GEMINI -> {
                groups.firstOrNull { g ->
                    g.buckets.any { b ->
                        val id = b.id.lowercase(Locale.US)
                        id.startsWith("gemini-") && !id.contains("argon")
                    }
                } ?: groups.firstOrNull { g ->
                    val name = g.name.lowercase(Locale.US)
                    name.contains("gemini") && !name.contains("argon")
                } ?: groups.firstOrNull { g ->
                    g.name.lowercase(Locale.US).contains("gemini")
                }
            }

            QuotaWidgetKind.THIRD_PARTY -> {
                groups.firstOrNull { g ->
                    g.buckets.any { b ->
                        val id = b.id.lowercase(Locale.US)
                        id.startsWith("3p-") || id.contains("claude") || id.contains("gpt")
                    }
                } ?: groups.firstOrNull { g ->
                    val name = g.name.lowercase(Locale.US)
                    name.contains("claude") ||
                        name.contains("gpt") ||
                        name.contains("third") ||
                        name.contains("3p")
                } ?: groups.firstOrNull { g ->
                    !g.name.lowercase(Locale.US).contains("gemini")
                }
            }
        }
    }

    internal fun findBucket(
        buckets: List<UsageQuotaBucketDto>,
        isFiveHour: Boolean,
    ): UsageQuotaBucketDto? {
        if (buckets.isEmpty()) return null
        return if (isFiveHour) {
            buckets.firstOrNull { b ->
                val win = b.window.trim().lowercase(Locale.US)
                val id = b.id.trim().lowercase(Locale.US)
                val name = b.name.trim().lowercase(Locale.US)
                win == "5h" ||
                    win == "5hr" ||
                    win == "5-hour" ||
                    id.endsWith("-5h") ||
                    id.contains("5h") ||
                    name.contains("five hour") ||
                    name.contains("5-hour") ||
                    name.contains("5 hour") ||
                    name.contains("5h")
            }
        } else {
            buckets.firstOrNull { b ->
                val win = b.window.trim().lowercase(Locale.US)
                val id = b.id.trim().lowercase(Locale.US)
                val name = b.name.trim().lowercase(Locale.US)
                win == "weekly" ||
                    win == "week" ||
                    win == "7d" ||
                    id.endsWith("-weekly") ||
                    id.contains("weekly") ||
                    name.contains("weekly") ||
                    name.contains("week")
            }
        }
    }

    internal fun formatRemainingPercent(pct: Double): String {
        val clamped = pct.coerceIn(0.0, 100.0)
        return if (clamped > 0.0 && clamped < 10.0) {
            String.format(Locale.US, "%.1f%%", clamped)
        } else {
            "${clamped.roundToInt()}%"
        }
    }

    internal fun formatFriendlyReset(resetTimeIso: String?, nowEpochMillis: Long): String {
        val raw = resetTimeIso?.trim().orEmpty()
        if (raw.isEmpty()) return "No reset window"
        val targetMillis = parseIsoMillisOrNull(raw) ?: return raw
        val remainingSec = (targetMillis - nowEpochMillis) / 1_000L
        if (remainingSec <= 0L) return "Resetting soon"

        val days = remainingSec / 86_400L
        val hours = (remainingSec % 86_400L) / 3_600L
        val minutes = (remainingSec % 3_600L) / 60L

        return when {
            days > 0L && hours > 0L -> "Resets in ${days}d ${hours}h"
            days > 0L -> "Resets in ${days}d"
            hours > 0L && minutes > 0L -> "Resets in ${hours}h ${minutes}m"
            hours > 0L -> "Resets in ${hours}h"
            minutes > 0L -> "Resets in ${minutes}m"
            else -> "Resets in <1m"
        }
    }

    internal fun formatRelativeAge(iso: String?, nowEpochMillis: Long): String? {
        val raw = iso?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val tsMillis = parseIsoMillisOrNull(raw) ?: return null
        val elapsedMin = Duration.ofMillis((nowEpochMillis - tsMillis).coerceAtLeast(0L)).toMinutes()
        return when {
            elapsedMin < 1L -> "Just now"
            elapsedMin < 60L -> "${elapsedMin}m ago"
            elapsedMin < 24L * 60L -> "${elapsedMin / 60L}h ago"
            else -> "${elapsedMin / (24L * 60L)}d ago"
        }
    }

    internal fun isCacheFresh(fetchedAtIso: String?, nowEpochMillis: Long, maxAgeMillis: Long = 5 * 60_000L): Boolean {
        val tsMillis = parseIsoMillisOrNull(fetchedAtIso?.trim().orEmpty()) ?: return false
        return (nowEpochMillis - tsMillis) in 0L..maxAgeMillis
    }

    private fun UsageQuotaBucketDto.toDisplay(nowEpochMillis: Long): WidgetBucketDisplay {
        val pct = if (remainingPercent > 0.0 || remainingFraction <= 0.0) {
            remainingPercent
        } else {
            remainingFraction * 100.0
        }.coerceIn(0.0, 100.0)
        return WidgetBucketDisplay(
            percentText = formatRemainingPercent(pct),
            progress = pct.roundToInt().coerceIn(0, 100),
            resetText = formatFriendlyReset(resetTime, nowEpochMillis),
            tier = ObservatoryTelemetryMath.resolveQuotaTier(pct),
            hasData = true,
        )
    }

    private fun placeholderBucket(subtitle: String): WidgetBucketDisplay = WidgetBucketDisplay(
        percentText = "—",
        progress = 0,
        resetText = subtitle,
        tier = QuotaHealthTier.HEALTHY,
        hasData = false,
    )

    private fun parseIsoMillisOrNull(iso: String): Long? {
        if (iso.isEmpty()) return null
        return runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
    }
}
