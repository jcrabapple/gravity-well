package us.snakepit.gravitywell.ui.shell

import androidx.compose.runtime.Immutable
import us.snakepit.gravitywell.cache.CachedHost
import us.snakepit.gravitywell.net.CapabilitiesDto
import us.snakepit.gravitywell.net.WsConnectionState

enum class TopLevelDestination(val label: String) {
    SESSIONS("SESSIONS"),
    CONSOLE("CONSOLE"),
    OBSERVATORY("OBSERVATORY"),
    DECK("DECK"),
}

@Immutable
data class AdaptiveShellLayoutSpec(
    val isExpanded: Boolean,
    val showBottomNavigationBar: Boolean,
    val showNavigationRail: Boolean,
    val showTwoPaneSessionsConsole: Boolean,
    val showCompactConsoleBackButton: Boolean,
)

object AdaptiveShellBreakpoints {
    const val EXPANDED_WIDTH_DP: Int = 600

    /**
     * Resolves adaptive Vernier Rig layout per DIRECTION-gravity-well.md Section 3:
     * - < 600dp: bottom NavigationBar (hidden when actively inside an attached session Console
     *   so the thumb-zone composer + docked approval bar sit flush at the bottom edge).
     * - >= 600dp: left NavigationRail + 360dp Sessions / remaining Console two-pane split.
     */
    fun resolveLayout(
        widthDp: Int,
        destination: TopLevelDestination,
        attachedSessionId: String?,
    ): AdaptiveShellLayoutSpec {
        val isExpanded = widthDp >= EXPANDED_WIDTH_DP
        val inAttachedCompactConsole =
            !isExpanded && destination == TopLevelDestination.CONSOLE && !attachedSessionId.isNullOrBlank()
        return AdaptiveShellLayoutSpec(
            isExpanded = isExpanded,
            showBottomNavigationBar = !isExpanded && !inAttachedCompactConsole,
            showNavigationRail = isExpanded,
            showTwoPaneSessionsConsole = isExpanded && (
                destination == TopLevelDestination.SESSIONS || destination == TopLevelDestination.CONSOLE
            ),
            showCompactConsoleBackButton = inAttachedCompactConsole,
        )
    }
}

@Immutable
data class PendingDeepLink(
    val hostId: String?,
    val sessionId: String,
)

@Immutable
data class ShellUiState(
    val initialized: Boolean = false,
    val hosts: List<CachedHost> = emptyList(),
    val activeHost: CachedHost? = null,
    val activeCapabilities: CapabilitiesDto = CapabilitiesDto(),
    val wsState: WsConnectionState = WsConnectionState.Disconnected,
    val latestCursor: Long = 0L,
    val destination: TopLevelDestination = TopLevelDestination.SESSIONS,
    val attachedSessionId: String? = null,
    val showHostSwitcherSheet: Boolean = false,
    val showAddHostModal: Boolean = false,
    val reverifyHostTarget: CachedHost? = null,
)
