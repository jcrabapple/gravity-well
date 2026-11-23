package us.snakepit.gravitywell.service

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide foreground visibility tracker updated by [us.snakepit.gravitywell.MainActivity]
 * (`onStart` / `onStop`) so background priority gate alerts fire only when the user is not
 * actively viewing the app.
 */
object AppForegroundTracker {
    private val startedActivities = AtomicInteger(0)
    private val shellStreamActive = AtomicBoolean(false)
    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    val isAppInForeground: Boolean
        get() = _isForeground.value

    val isShellStreamActive: Boolean
        get() = shellStreamActive.get()

    fun onActivityStarted() {
        val count = startedActivities.incrementAndGet()
        _isForeground.value = count > 0
    }

    fun onActivityStopped() {
        val count = startedActivities.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
        _isForeground.value = count > 0
    }

    fun setShellStreamActive(active: Boolean) {
        shellStreamActive.set(active)
    }

    /** Test-only deterministic override for unit tests. */
    fun setForegroundForTest(inForeground: Boolean) {
        startedActivities.set(if (inForeground) 1 else 0)
        _isForeground.value = inForeground
    }
}
