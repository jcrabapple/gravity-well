package us.snakepit.gravitywell.service

import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import us.snakepit.gravitywell.MainActivity
import us.snakepit.gravitywell.ui.shell.PendingDeepLink

/**
 * Thread-safe FIFO queue buffering `(hostId, sessionId)` deep-link intents across both
 * cold start (`MainActivity.onCreate`) and warm `singleTask` resumes (`MainActivity.onNewIntent`)
 * until the Compose shell acknowledges consumption (SPEC.md D16).
 */
class DeepLinkIntentQueue {
    private val lock = Any()
    private val queue = ArrayDeque<PendingDeepLink>()

    private val _head = MutableStateFlow<PendingDeepLink?>(null)
    val head: StateFlow<PendingDeepLink?> = _head.asStateFlow()

    fun enqueue(deepLink: PendingDeepLink): Boolean {
        val cleanSessionId = deepLink.sessionId.trim()
        if (cleanSessionId.isEmpty()) return false
        val normalized = PendingDeepLink(
            hostId = deepLink.hostId?.trim()?.ifEmpty { null },
            sessionId = cleanSessionId,
        )
        synchronized(lock) {
            if (queue.lastOrNull() == normalized) {
                return false
            }
            queue.addLast(normalized)
            _head.value = queue.firstOrNull()
            return true
        }
    }

    fun enqueueIntent(intent: Intent?): Boolean {
        val parsed = MainActivity.extractDeepLink(intent) ?: return false
        return enqueue(parsed)
    }

    fun acknowledge(consumed: PendingDeepLink) {
        synchronized(lock) {
            if (queue.firstOrNull() == consumed) {
                queue.removeFirst()
            } else {
                queue.remove(consumed)
            }
            _head.value = queue.firstOrNull()
        }
    }

    fun acknowledgeHead(): PendingDeepLink? {
        synchronized(lock) {
            val removed = if (queue.isEmpty()) null else queue.removeFirst()
            _head.value = queue.firstOrNull()
            return removed
        }
    }

    fun size(): Int = synchronized(lock) { queue.size }

    fun snapshot(): List<PendingDeepLink> = synchronized(lock) { queue.toList() }

    fun saveToBundle(outState: Bundle) {
        val items = snapshot()
        if (items.isEmpty()) return
        outState.putStringArrayList(
            STATE_HOST_IDS,
            ArrayList(items.map { it.hostId.orEmpty() }),
        )
        outState.putStringArrayList(
            STATE_SESSION_IDS,
            ArrayList(items.map { it.sessionId }),
        )
    }

    fun restoreFromBundle(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) return
        val sessionIds = savedInstanceState.getStringArrayList(STATE_SESSION_IDS) ?: return
        val hostIds = savedInstanceState.getStringArrayList(STATE_HOST_IDS).orEmpty()
        sessionIds.forEachIndexed { idx, sid ->
            val hid = hostIds.getOrNull(idx)?.ifBlank { null }
            enqueue(PendingDeepLink(hostId = hid, sessionId = sid))
        }
    }

    companion object {
        private const val STATE_HOST_IDS = "gw_deeplink_queue_hosts"
        private const val STATE_SESSION_IDS = "gw_deeplink_queue_sessions"
    }
}
