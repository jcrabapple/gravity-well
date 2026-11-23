package us.snakepit.gravitywell.service

import android.content.Context
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import us.snakepit.gravitywell.cache.CachedHost
import us.snakepit.gravitywell.cache.CachedPendingApproval
import us.snakepit.gravitywell.cache.OfflineCacheStore
import us.snakepit.gravitywell.net.WsServerFrame

data class KeepaliveWatchSnapshot(
    val hostId: String,
    val hostLabel: String,
    val attachedSessionId: String?,
    val liveSessionCount: Int,
    val awaitingGateCount: Int,
) {
    val hasWatchedWork: Boolean
        get() = liveSessionCount > 0 || awaitingGateCount > 0
}

/**
 * Coordinates foreground keepalive service lifecycle and background priority gate alerts
 * from live WebSocket events and SQLite cache state (SPEC.md D16).
 */
class KeepaliveCoordinator(
    private val cacheStore: OfflineCacheStore,
    private val preferences: KeepalivePreferences,
    private val isForegroundProvider: () -> Boolean = { AppForegroundTracker.isAppInForeground },
    private val serviceController: ServiceDriver = AndroidServiceDriver,
) {
    interface ServiceDriver {
        fun startOrUpdateKeepalive(context: Context, snapshot: KeepaliveWatchSnapshot)
        fun stopKeepalive(context: Context)
        fun postGateAlert(
            context: Context,
            hostId: String,
            sessionId: String,
            sessionTitle: String,
            requestId: String,
            kind: String,
            summary: String,
        )
        fun cancelGateAlert(context: Context, hostId: String, sessionId: String, requestId: String)
        fun cancelAllNotifications(context: Context)
    }

    object AndroidServiceDriver : ServiceDriver {
        override fun startOrUpdateKeepalive(context: Context, snapshot: KeepaliveWatchSnapshot) {
            GravityWellKeepaliveService.startOrUpdate(context, snapshot)
        }

        override fun stopKeepalive(context: Context) {
            GravityWellKeepaliveService.stop(context)
        }

        override fun postGateAlert(
            context: Context,
            hostId: String,
            sessionId: String,
            sessionTitle: String,
            requestId: String,
            kind: String,
            summary: String,
        ) {
            GravityWellNotifications.postPriorityGateNotification(
                context = context,
                hostId = hostId,
                sessionId = sessionId,
                sessionTitle = sessionTitle,
                requestId = requestId,
                kind = kind,
                summary = summary,
            )
        }

        override fun cancelGateAlert(
            context: Context,
            hostId: String,
            sessionId: String,
            requestId: String,
        ) {
            GravityWellNotifications.cancelGateNotification(
                context = context,
                hostId = hostId,
                sessionId = sessionId,
                requestId = requestId,
            )
        }

        override fun cancelAllNotifications(context: Context) {
            GravityWellNotifications.cancelAll(context)
        }
    }

    private data class WatchSyncKey(
        val hostId: String,
        val attachedSessionId: String?,
        val liveSessionCount: Int,
        val awaitingGateCount: Int,
        val keepaliveEnabled: Boolean,
    )

    @Volatile
    private var lastSyncedKey: WatchSyncKey? = null

    fun computeWatchSnapshot(
        host: CachedHost,
        attachedSessionId: String? = null,
    ): KeepaliveWatchSnapshot {
        val liveSessions = cacheStore.listSessions(host.hostId, isLiveFilter = true)
        val pendingApprovalSessionIds = LinkedHashSet<String>()
        for (sess in cacheStore.listSessions(host.hostId)) {
            if (sess.status == "awaiting_approval" || sess.status == "awaiting_question") {
                pendingApprovalSessionIds += sess.sessionId
            }
            val approvals = cacheStore.listPendingApprovals(host.hostId, sess.sessionId)
            if (approvals.isNotEmpty()) {
                pendingApprovalSessionIds += sess.sessionId
            }
        }
        return KeepaliveWatchSnapshot(
            hostId = host.hostId,
            hostLabel = host.label,
            attachedSessionId = attachedSessionId,
            liveSessionCount = liveSessions.size,
            awaitingGateCount = pendingApprovalSessionIds.size,
        )
    }

    fun syncServiceState(
        context: Context?,
        host: CachedHost?,
        attachedSessionId: String? = null,
    ) {
        val ctx = context ?: return
        if (!preferences.isKeepaliveEnabled || host == null) {
            lastSyncedKey = null
            serviceController.stopKeepalive(ctx)
            if (!preferences.isKeepaliveEnabled) {
                serviceController.cancelAllNotifications(ctx)
            }
            return
        }
        val snapshot = computeWatchSnapshot(host, attachedSessionId)
        lastSyncedKey = WatchSyncKey(
            hostId = snapshot.hostId,
            attachedSessionId = snapshot.attachedSessionId,
            liveSessionCount = snapshot.liveSessionCount,
            awaitingGateCount = snapshot.awaitingGateCount,
            keepaliveEnabled = preferences.isKeepaliveEnabled,
        )
        if (snapshot.hasWatchedWork) {
            serviceController.startOrUpdateKeepalive(ctx, snapshot)
        } else {
            serviceController.stopKeepalive(ctx)
        }
    }

    fun setKeepaliveEnabled(
        context: Context?,
        enabled: Boolean,
        activeHost: CachedHost?,
        attachedSessionId: String? = null,
    ) {
        preferences.setKeepaliveEnabled(enabled)
        if (!enabled && context != null) {
            serviceController.stopKeepalive(context)
            serviceController.cancelAllNotifications(context)
            return
        }
        syncServiceState(context, activeHost, attachedSessionId)
    }

    fun onWebSocketEvent(
        context: Context?,
        host: CachedHost,
        attachedSessionId: String?,
        frame: WsServerFrame.Event,
    ) {
        val sid = frame.sessionId
        when (frame.type) {
            "permission_requested" -> {
                if (!sid.isNullOrBlank()) {
                    val reqId = frame.payload["request_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val summary = extractPermissionSummary(frame.payload)
                    if (reqId.isNotBlank()) {
                        if (sid != attachedSessionId) {
                            cacheStore.upsertPendingApproval(
                                CachedPendingApproval(
                                    hostId = host.hostId,
                                    sessionId = sid,
                                    requestId = reqId,
                                    kind = "permission",
                                    title = summary,
                                    payloadJson = frame.payload.toString(),
                                    createdAt = frame.ts.ifBlank { Instant.now().toString() },
                                ),
                            )
                        }
                        if (context != null && preferences.isKeepaliveEnabled && !isForegroundProvider()) {
                            val sessTitle = cacheStore.getSession(host.hostId, sid)?.title ?: "Live Session"
                            serviceController.postGateAlert(
                                context = context,
                                hostId = host.hostId,
                                sessionId = sid,
                                sessionTitle = sessTitle,
                                requestId = reqId,
                                kind = "permission",
                                summary = summary,
                            )
                        }
                    }
                }
            }

            "question_requested" -> {
                if (!sid.isNullOrBlank()) {
                    val reqId = frame.payload["request_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val summary = extractQuestionSummary(frame.payload)
                    if (reqId.isNotBlank()) {
                        if (sid != attachedSessionId) {
                            cacheStore.upsertPendingApproval(
                                CachedPendingApproval(
                                    hostId = host.hostId,
                                    sessionId = sid,
                                    requestId = reqId,
                                    kind = "question",
                                    title = summary,
                                    payloadJson = frame.payload.toString(),
                                    createdAt = frame.ts.ifBlank { Instant.now().toString() },
                                ),
                            )
                        }
                        if (context != null && preferences.isKeepaliveEnabled && !isForegroundProvider()) {
                            val sessTitle = cacheStore.getSession(host.hostId, sid)?.title ?: "Live Session"
                            serviceController.postGateAlert(
                                context = context,
                                hostId = host.hostId,
                                sessionId = sid,
                                sessionTitle = sessTitle,
                                requestId = reqId,
                                kind = "question",
                                summary = summary,
                            )
                        }
                    }
                }
            }

            "permission_resolved", "question_resolved" -> {
                if (!sid.isNullOrBlank()) {
                    val reqId = frame.payload["request_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (reqId.isNotBlank()) {
                        cacheStore.deletePendingApproval(host.hostId, sid, reqId)
                        if (context != null) {
                            serviceController.cancelGateAlert(context, host.hostId, sid, reqId)
                        }
                    }
                }
            }

            "session_closed" -> {
                if (!sid.isNullOrBlank()) {
                    val existingApprovals = cacheStore.listPendingApprovals(host.hostId, sid)
                    cacheStore.replacePendingApprovals(host.hostId, sid, emptyList())
                    if (context != null) {
                        existingApprovals.forEach { ap ->
                            serviceController.cancelGateAlert(context, host.hostId, sid, ap.requestId)
                        }
                    }
                }
            }
        }
        if (frame.type in LIFECYCLE_OR_GATE_EVENTS) {
            val snapshot = computeWatchSnapshot(host, attachedSessionId)
            val key = WatchSyncKey(
                hostId = snapshot.hostId,
                attachedSessionId = snapshot.attachedSessionId,
                liveSessionCount = snapshot.liveSessionCount,
                awaitingGateCount = snapshot.awaitingGateCount,
                keepaliveEnabled = preferences.isKeepaliveEnabled,
            )
            if (key != lastSyncedKey) {
                syncServiceState(context, host, attachedSessionId)
            }
        }
    }

    companion object {
        private val LIFECYCLE_OR_GATE_EVENTS = setOf(
            "session_started",
            "session_status",
            "session_closed",
            "permission_requested",
            "permission_resolved",
            "question_requested",
            "question_resolved",
        )
        fun extractPermissionSummary(payload: JsonObject): String {
            val toolObj = runCatching { payload["tool_call"]?.jsonObject }.getOrNull()
            val title = toolObj?.get("title")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val kind = toolObj?.get("kind")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val rawInput = toolObj?.get("raw_input")
            val cmdLine = runCatching {
                rawInput?.jsonObject?.get("CommandLine")?.jsonPrimitive?.contentOrNull
                    ?: rawInput?.jsonObject?.get("command")?.jsonPrimitive?.contentOrNull
                    ?: rawInput?.jsonPrimitive?.contentOrNull
            }.getOrNull()?.trim().orEmpty()

            val detail = when {
                cmdLine.isNotEmpty() -> cmdLine
                title.isNotEmpty() -> title
                else -> "Tool permission requested"
            }
            val prefix = kind.uppercase().ifBlank { "TOOL" }
            return GravityWellNotifications.sanitizeSummaryText("$prefix · $detail")
        }

        fun extractQuestionSummary(payload: JsonObject): String {
            val message = payload["message"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            return GravityWellNotifications.sanitizeSummaryText(
                message.ifBlank { "Session requires user input" },
            )
        }
    }
}
