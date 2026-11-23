package us.snakepit.gravitywell.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import us.snakepit.gravitywell.GravityWellRuntime
import us.snakepit.gravitywell.cache.CachedHost
import us.snakepit.gravitywell.cache.CachedSession
import us.snakepit.gravitywell.net.GatewayWebSocketClient
import us.snakepit.gravitywell.net.NetworkModule
import us.snakepit.gravitywell.net.WsServerFrame
import us.snakepit.gravitywell.net.WsTarget

/**
 * Plain-Kotlin `dataSync` foreground service keeping watched Antigravity sessions and
 * background permission/question alerts alive while the user has keepalive enabled (SPEC.md D16).
 */
class GravityWellKeepaliveService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fallbackLock = Any()
    private var fallbackWsClient: GatewayWebSocketClient? = null
    private var fallbackWsJob: Job? = null
    private var fallbackBoundHostId: String? = null
    private var shellStreamObserverJob: Job? = null
    @Volatile private var activeHost: CachedHost? = null
    @Volatile private var activeRuntime: GravityWellRuntime? = null
    @Volatile internal var foregroundPromotionsForTest: Int = 0
        private set

    internal val isFallbackWsActive: Boolean
        get() = synchronized(fallbackLock) { fallbackWsClient != null }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val prefs = KeepalivePreferences.get(applicationContext)
        if (action == ACTION_STOP || !prefs.isKeepaliveEnabled) {
            ensureForegroundBeforeStop()
            stopKeepaliveSelf()
            return START_NOT_STICKY
        }

        val runtime = GravityWellRuntime.get(applicationContext)
        val hostId = intent?.getStringExtra(EXTRA_HOST_ID)?.ifBlank { null }
            ?: runtime.cacheStore.getActiveHost()?.hostId
        val host = hostId?.let { runtime.cacheStore.getHost(it) } ?: runtime.cacheStore.getActiveHost()
        if (host == null) {
            ensureForegroundBeforeStop()
            stopKeepaliveSelf()
            return START_NOT_STICKY
        }

        val attachedSessionId = intent?.getStringExtra(EXTRA_ATTACHED_SESSION_ID)?.ifBlank { null }
        val coordinator = KeepaliveCoordinator(
            cacheStore = runtime.cacheStore,
            preferences = prefs,
        )
        val computed = coordinator.computeWatchSnapshot(host, attachedSessionId)
        val liveCount = intent?.getIntExtra(EXTRA_LIVE_COUNT, computed.liveSessionCount)
            ?: computed.liveSessionCount
        val gateCount = intent?.getIntExtra(EXTRA_GATE_COUNT, computed.awaitingGateCount)
            ?: computed.awaitingGateCount
        val hostLabel = intent?.getStringExtra(EXTRA_HOST_LABEL)?.ifBlank { null } ?: host.label

        if (liveCount <= 0 && gateCount <= 0) {
            ensureForegroundBeforeStop(
                hostId = host.hostId,
                hostLabel = hostLabel,
                attachedSessionId = attachedSessionId,
                liveCount = 0,
                gateCount = 0,
            )
            stopKeepaliveSelf()
            return START_NOT_STICKY
        }

        val notification = GravityWellNotifications.buildOngoingNotification(
            context = this,
            hostId = host.hostId,
            hostLabel = hostLabel,
            attachedSessionId = attachedSessionId,
            liveSessionCount = liveCount,
            awaitingGateCount = gateCount,
        )

        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }

        try {
            promoteToForeground(notification, fgsType)
            runningFlag.set(true)
            activeStartedHostId = host.hostId
        } catch (_: Exception) {
            stopKeepaliveSelf()
            return START_NOT_STICKY
        }

        // Maintain a fallback background WebSocket whenever ShellController stream is not active
        activeHost = host
        activeRuntime = runtime
        ensureShellStreamObserver()
        if (AppForegroundTracker.isShellStreamActive) {
            teardownFallbackWs()
        } else {
            ensureFallbackWsConnected(host, runtime)
        }

        return START_NOT_STICKY
    }

    private fun ensureShellStreamObserver() {
        synchronized(fallbackLock) {
            if (shellStreamObserverJob?.isActive == true) return
            shellStreamObserverJob = serviceScope.launch {
                AppForegroundTracker.shellStreamActiveFlow.collect { active ->
                    if (active) {
                        teardownFallbackWs()
                    } else if (runningFlag.get()) {
                        val host = activeHost
                        val runtime = activeRuntime
                        if (host != null && runtime != null) {
                            ensureFallbackWsConnected(host, runtime)
                        }
                    }
                }
            }
        }
    }

    private fun ensureForegroundBeforeStop(
        hostId: String = activeStartedHostId ?: "keepalive",
        hostLabel: String = activeHost?.label ?: "Gravity Well",
        attachedSessionId: String? = null,
        liveCount: Int = 0,
        gateCount: Int = 0,
    ) {
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        val notification = GravityWellNotifications.buildOngoingNotification(
            context = this,
            hostId = hostId,
            hostLabel = hostLabel,
            attachedSessionId = attachedSessionId,
            liveSessionCount = liveCount,
            awaitingGateCount = gateCount,
        )
        runCatching {
            promoteToForeground(notification, fgsType)
        }
    }

    private fun promoteToForeground(notification: android.app.Notification, fgsType: Int) {
        ServiceCompat.startForeground(
            this,
            GravityWellNotifications.NOTIFICATION_ID_KEEPALIVE,
            notification,
            fgsType,
        )
        foregroundPromotionsForTest += 1
    }

    private fun ensureFallbackWsConnected(host: CachedHost, runtime: GravityWellRuntime) {
        val wsToConnect: GatewayWebSocketClient
        synchronized(fallbackLock) {
            if (fallbackWsClient != null && fallbackBoundHostId == host.hostId) return
            teardownFallbackWsLocked()
            val parsedBaseUrl = host.baseUrl.toHttpUrlOrNull() ?: return
            val okHttp = NetworkModule.createOkHttpClient(
                tokenProvider = { runtime.tokenVault.getToken(host.hostId) },
            )
            val ws = GatewayWebSocketClient(
                okHttpClient = okHttp,
                baseUrl = parsedBaseUrl,
                tokenProvider = { runtime.tokenVault.getToken(host.hostId).orEmpty() },
                target = WsTarget.Global,
                scope = serviceScope,
                initialCursor = host.lastCursor,
            )
            fallbackBoundHostId = host.hostId
            fallbackWsClient = ws

            val coordinator = KeepaliveCoordinator(
                cacheStore = runtime.cacheStore,
                preferences = KeepalivePreferences.get(applicationContext),
            )

            fallbackWsJob = serviceScope.launch {
                ws.frames.collect { frame ->
                    if (AppForegroundTracker.isShellStreamActive) {
                        teardownFallbackWs()
                        return@collect
                    }
                    if (frame is WsServerFrame.Event) {
                        runtime.cacheStore.updateHostLastCursor(host.hostId, frame.cursor)
                        persistFallbackSessionState(runtime, host, frame)
                        coordinator.onWebSocketEvent(
                            context = applicationContext,
                            host = host,
                            attachedSessionId = null,
                            frame = frame,
                        )
                    }
                }
            }
            wsToConnect = ws
        }
        wsToConnect.connect(afterCursor = if (host.lastCursor > 0L) host.lastCursor else null)
    }

    private fun persistFallbackSessionState(
        runtime: GravityWellRuntime,
        host: CachedHost,
        frame: WsServerFrame.Event,
    ) {
        val sid = frame.sessionId ?: return
        val existing = runtime.cacheStore.getSession(host.hostId, sid)
        val updatedStatus = when (frame.type) {
            "session_status" -> frame.payload["status"]?.jsonPrimitive?.contentOrNull ?: existing?.status ?: "running"
            "session_closed" -> "closed"
            "permission_requested" -> "awaiting_approval"
            "question_requested" -> "awaiting_question"
            else -> existing?.status ?: "running"
        }
        runtime.cacheStore.upsertSession(
            (existing ?: CachedSession(
                hostId = host.hostId,
                sessionId = sid,
                title = "Live Session",
                cwd = host.defaultCwd.orEmpty(),
                status = updatedStatus,
                isLive = updatedStatus != "closed",
                updatedAt = frame.ts.ifBlank { Instant.now().toString() },
            )).copy(
                status = updatedStatus,
                isLive = updatedStatus != "closed" && updatedStatus != "archived",
                lastCursor = maxOf(existing?.lastCursor ?: 0L, frame.cursor),
                lastSeq = maxOf(existing?.lastSeq ?: 0L, frame.seq),
                updatedAt = frame.ts.ifBlank { existing?.updatedAt ?: Instant.now().toString() },
            ),
        )
    }

    private fun teardownFallbackWs() {
        synchronized(fallbackLock) {
            teardownFallbackWsLocked()
        }
    }

    private fun teardownFallbackWsLocked() {
        fallbackWsJob?.cancel()
        fallbackWsJob = null
        fallbackWsClient?.disconnect()
        fallbackWsClient = null
        fallbackBoundHostId = null
    }

    private fun stopKeepaliveSelf() {
        runningFlag.set(false)
        activeStartedHostId = null
        activeHost = null
        activeRuntime = null
        synchronized(fallbackLock) {
            shellStreamObserverJob?.cancel()
            shellStreamObserverJob = null
            teardownFallbackWsLocked()
        }
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        GravityWellNotifications.cancelOngoingNotification(this)
        stopSelf()
    }

    override fun onDestroy() {
        runningFlag.set(false)
        activeStartedHostId = null
        activeHost = null
        activeRuntime = null
        synchronized(fallbackLock) {
            shellStreamObserverJob?.cancel()
            shellStreamObserverJob = null
            teardownFallbackWsLocked()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START_OR_UPDATE = "us.snakepit.gravitywell.action.KEEPALIVE_START_OR_UPDATE"
        const val ACTION_STOP = "us.snakepit.gravitywell.action.KEEPALIVE_SERVICE_STOP"

        const val EXTRA_HOST_ID = "us.snakepit.gravitywell.extra.KEEPALIVE_HOST_ID"
        const val EXTRA_HOST_LABEL = "us.snakepit.gravitywell.extra.KEEPALIVE_HOST_LABEL"
        const val EXTRA_ATTACHED_SESSION_ID = "us.snakepit.gravitywell.extra.KEEPALIVE_ATTACHED_SESSION_ID"
        const val EXTRA_LIVE_COUNT = "us.snakepit.gravitywell.extra.KEEPALIVE_LIVE_COUNT"
        const val EXTRA_GATE_COUNT = "us.snakepit.gravitywell.extra.KEEPALIVE_GATE_COUNT"

        private val runningFlag = AtomicBoolean(false)
        @Volatile private var activeStartedHostId: String? = null

        val isRunning: Boolean
            get() = runningFlag.get()

        fun startOrUpdate(context: Context, snapshot: KeepaliveWatchSnapshot) {
            val appCtx = context.applicationContext
            if (!KeepalivePreferences.get(appCtx).isKeepaliveEnabled || !snapshot.hasWatchedWork) {
                stop(appCtx)
                return
            }
            val intent = Intent(appCtx, GravityWellKeepaliveService::class.java).apply {
                action = ACTION_START_OR_UPDATE
                putExtra(EXTRA_HOST_ID, snapshot.hostId)
                putExtra(EXTRA_HOST_LABEL, snapshot.hostLabel)
                if (!snapshot.attachedSessionId.isNullOrBlank()) {
                    putExtra(EXTRA_ATTACHED_SESSION_ID, snapshot.attachedSessionId)
                }
                putExtra(EXTRA_LIVE_COUNT, snapshot.liveSessionCount)
                putExtra(EXTRA_GATE_COUNT, snapshot.awaitingGateCount)
            }
            if (runningFlag.get()) {
                // Already in foreground: update ongoing notification directly
                GravityWellNotifications.postOngoingNotification(
                    context = appCtx,
                    hostId = snapshot.hostId,
                    hostLabel = snapshot.hostLabel,
                    attachedSessionId = snapshot.attachedSessionId,
                    liveSessionCount = snapshot.liveSessionCount,
                    awaitingGateCount = snapshot.awaitingGateCount,
                )
                if (activeStartedHostId == snapshot.hostId) {
                    return
                }
            }
            try {
                ContextCompat.startForegroundService(appCtx, intent)
            } catch (_: Exception) {
                // Android 12+ ForegroundServiceStartNotAllowedException when app is background-restricted:
                // still update ongoing notification if permitted without crashing the app.
                GravityWellNotifications.postOngoingNotification(
                    context = appCtx,
                    hostId = snapshot.hostId,
                    hostLabel = snapshot.hostLabel,
                    attachedSessionId = snapshot.attachedSessionId,
                    liveSessionCount = snapshot.liveSessionCount,
                    awaitingGateCount = snapshot.awaitingGateCount,
                )
            }
        }

        fun stop(context: Context) {
            val appCtx = context.applicationContext
            runningFlag.set(false)
            activeStartedHostId = null
            runCatching {
                appCtx.stopService(Intent(appCtx, GravityWellKeepaliveService::class.java))
            }
            GravityWellNotifications.cancelOngoingNotification(appCtx)
        }
    }
}
