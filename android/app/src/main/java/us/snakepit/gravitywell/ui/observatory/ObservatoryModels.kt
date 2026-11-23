package us.snakepit.gravitywell.ui.observatory

import androidx.annotation.Keep
import androidx.compose.runtime.Immutable
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import us.snakepit.gravitywell.ui.sessions.ExternalSessionPresenceDto
import us.snakepit.gravitywell.ui.sessions.HostVitalsSummaryDto

@Keep
@Serializable
data class UsageQuotaBucketDto(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val window: String = "",
    @SerialName("remaining_fraction") val remainingFraction: Double = 0.0,
    @SerialName("remaining_percent") val remainingPercent: Double = 0.0,
    @SerialName("reset_time") val resetTime: String? = null,
)

@Keep
@Serializable
data class UsageQuotaGroupDto(
    val name: String = "",
    val description: String = "",
    val buckets: List<UsageQuotaBucketDto> = emptyList(),
)

@Keep
@Serializable
data class ObservatoryUsageResponseDto(
    val status: String = "SUCCESS",
    @SerialName("cached_at") val cachedAt: String = "",
    val stale: Boolean = false,
    val description: String = "",
    val groups: List<UsageQuotaGroupDto> = emptyList(),
)

@Keep
@Serializable
data class RemoteControlStatusDto(
    val active: Boolean = false,
    @SerialName("status_text") val statusText: String = "",
    @SerialName("instance_name") val instanceName: String? = null,
    val output: String? = null,
)

@Keep
@Serializable
data class RemoteControlStartRequestDto(
    val name: String? = null,
    @SerialName("session_scoped") val sessionScoped: Boolean = false,
)

@Keep
@Serializable
data class UpdaterStatusDto(
    val success: Boolean? = null,
    val message: String = "unknown",
    @SerialName("agy_version") val agyVersion: String = "",
)

@Keep
@Serializable
data class ObservatoryFullStatusDto(
    val host: HostVitalsSummaryDto = HostVitalsSummaryDto(),
    @SerialName("external_sessions") val externalSessions: List<ExternalSessionPresenceDto> = emptyList(),
    @SerialName("active_external_count") val activeExternalCount: Int = 0,
    @SerialName("stale_lock_count") val staleLockCount: Int = 0,
    @SerialName("remote_control") val remoteControl: RemoteControlStatusDto = RemoteControlStatusDto(),
    val updater: UpdaterStatusDto = UpdaterStatusDto(),
    @SerialName("checked_at") val checkedAt: String = "",
)

@Keep
@Serializable
data class ObservatoryLogsResponseDto(
    val source: String = "cli",
    val file: String? = null,
    val files: List<String> = emptyList(),
    val lines: List<String> = emptyList(),
    @SerialName("total_matched") val totalMatched: Int = 0,
)

@Keep
@Serializable
data class RunAgyUpdateRequestDto(
    val confirm: Boolean = true,
)

@Keep
@Serializable
data class RunAgyUpdateResponseDto(
    val updated: Boolean = false,
    @SerialName("version_before") val versionBefore: String = "",
    @SerialName("version_after") val versionAfter: String = "",
    val output: String = "",
)

enum class QuotaHealthTier {
    HEALTHY,
    WARNING,
    CRITICAL,
}

enum class LogSourceTab(val wireValue: String, val label: String) {
    CLI("cli", "cli.log"),
    CRASHES("crashes", "crashes/"),
}

enum class LogLineSeverity {
    ERROR,
    WARN,
    INFO,
}

@Immutable
data class LogSpanSegment(
    val text: String,
    val isRedactedToken: Boolean,
)

/**
 * Pure telemetry math & formatting for Observatory gauges, reset countdowns, and log highlighting
 * (DIRECTION-gravity-well.md Section 7).
 */
object ObservatoryTelemetryMath {
    const val FUEL_SEGMENT_COUNT: Int = 10
    val TAIL_OPTIONS: List<Int> = listOf(200, 500, 2000)

    private val REDACTED_TOKEN_REGEX = Regex("\\[REDACTED]")
    private val ERROR_LINE_REGEX = Regex("(?i)\\b(?:ERROR|FATAL|CRITICAL|EXCEPTION|TRACEBACK)\\b")
    private val WARN_LINE_REGEX = Regex("(?i)\\b(?:WARN|WARNING)\\b")

    /**
     * Thresholds per DIRECTION-gravity-well.md Section 7.1:
     * - >= 30.0% -> HEALTHY (signalEmerald)
     * - 10.0% .. 29.9% -> WARNING (signalAmber)
     * - < 10.0% -> CRITICAL (signalCrimson)
     */
    fun resolveQuotaTier(remainingPercent: Double): QuotaHealthTier {
        val pct = remainingPercent.coerceIn(0.0, 100.0)
        return when {
            pct >= 30.0 -> QuotaHealthTier.HEALTHY
            pct >= 10.0 -> QuotaHealthTier.WARNING
            else -> QuotaHealthTier.CRITICAL
        }
    }

    /**
     * Computes discrete lit ticks for the 10-segment linear fuel bar (0..10).
     */
    fun computeLitFuelSegments(
        remainingPercent: Double,
        totalSegments: Int = FUEL_SEGMENT_COUNT,
    ): Int {
        require(totalSegments >= 1) { "totalSegments must be >= 1" }
        val clamped = remainingPercent.coerceIn(0.0, 100.0)
        if (clamped <= 0.0) return 0
        if (clamped >= 100.0) return totalSegments
        val rounded = ((clamped / 100.0) * totalSegments).roundToInt()
        return rounded.coerceIn(1, totalSegments)
    }

    fun formatPercentReadout(remainingPercent: Double): String =
        String.format(Locale.US, "%.1f%%", remainingPercent.coerceIn(0.0, 100.0))

    /**
     * Formats ISO-8601 UTC reset timestamp into a live `tnum` countdown (`T-04h 12m 08s`
     * or `T-05d 01h 37m 52s`).
     */
    fun formatResetCountdown(resetTimeIso: String?, nowEpochMillis: Long): String {
        val raw = resetTimeIso?.trim().orEmpty()
        if (raw.isEmpty()) return "NO RESET WINDOW"
        val targetMillis = parseIsoMillisOrNull(raw) ?: return raw
        val remainingSec = ((targetMillis - nowEpochMillis) / 1_000L)
        if (remainingSec <= 0L) return "T-00h 00m 00s"

        val days = remainingSec / 86_400L
        val hours = (remainingSec % 86_400L) / 3_600L
        val minutes = (remainingSec % 3_600L) / 60L
        val seconds = remainingSec % 60L

        return if (days > 0L) {
            String.format(Locale.US, "T-%02dd %02dh %02dm %02ds", days, hours, minutes, seconds)
        } else {
            String.format(Locale.US, "T-%02dh %02dm %02ds", hours, minutes, seconds)
        }
    }

    fun formatRamReadout(usedMb: Long, totalMb: Long): String {
        if (totalMb <= 0L) return "0.0 / 0.0 GiB"
        val usedGib = usedMb.toDouble() / 1024.0
        val totalGib = totalMb.toDouble() / 1024.0
        return String.format(Locale.US, "%.1f / %.1f GiB", usedGib, totalGib)
    }

    fun formatDiskReadout(usedGb: Double, totalGb: Double): String {
        if (totalGb <= 0.0) return "0 / 0 GB"
        return String.format(Locale.US, "%.0f / %.0f GB", usedGb, totalGb)
    }

    fun classifyLogSeverity(line: String): LogLineSeverity = when {
        ERROR_LINE_REGEX.containsMatchIn(line) -> LogLineSeverity.ERROR
        WARN_LINE_REGEX.containsMatchIn(line) -> LogLineSeverity.WARN
        else -> LogLineSeverity.INFO
    }

    fun splitRedactedSegments(line: String): List<LogSpanSegment> {
        if (line.isEmpty()) return listOf(LogSpanSegment("", isRedactedToken = false))
        val out = mutableListOf<LogSpanSegment>()
        var cursor = 0
        for (match in REDACTED_TOKEN_REGEX.findAll(line)) {
            if (match.range.first > cursor) {
                out += LogSpanSegment(
                    text = line.substring(cursor, match.range.first),
                    isRedactedToken = false,
                )
            }
            out += LogSpanSegment(
                text = match.value,
                isRedactedToken = true,
            )
            cursor = match.range.last + 1
        }
        if (cursor < line.length) {
            out += LogSpanSegment(
                text = line.substring(cursor),
                isRedactedToken = false,
            )
        }
        return out
    }

    private fun parseIsoMillisOrNull(iso: String): Long? {
        return runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
    }
}

@Immutable
data class ObservatoryUiState(
    val hostId: String = "",
    val hostLabel: String = "",
    val isLoading: Boolean = false,
    val isRefreshingUsage: Boolean = false,
    val isDaemonBusy: Boolean = false,
    val isUpdatingCli: Boolean = false,
    val isLogsLoading: Boolean = false,
    val usage: ObservatoryUsageResponseDto? = null,
    val status: ObservatoryFullStatusDto? = null,
    val cachedFetchedAt: String? = null,
    val daemonNameDraft: String = "",
    val daemonSessionScoped: Boolean = false,
    val showUpdateConfirmSheet: Boolean = false,
    val lastUpdateResult: RunAgyUpdateResponseDto? = null,
    val logSource: LogSourceTab = LogSourceTab.CLI,
    val selectedCrashFile: String? = null,
    val availableCrashFiles: List<String> = emptyList(),
    val logSearchQuery: String = "",
    val logTailLines: Int = 200,
    val logLines: List<String> = emptyList(),
    val logTotalMatched: Int = 0,
    val activeLogFile: String? = null,
    val showExpandedLogSheet: Boolean = false,
    val errorBanner: String? = null,
)
