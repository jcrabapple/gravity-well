package us.snakepit.gravitywell.ui.shell

import android.content.Context
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import us.snakepit.gravitywell.BuildConfig
import us.snakepit.gravitywell.cache.CachedHost
import us.snakepit.gravitywell.cache.CachedSession
import us.snakepit.gravitywell.cache.CachedSessionEvent
import us.snakepit.gravitywell.cache.OfflineCacheStore
import us.snakepit.gravitywell.net.BackoffConfig
import us.snakepit.gravitywell.net.CapabilitiesDto
import us.snakepit.gravitywell.net.GatewayApi
import us.snakepit.gravitywell.net.GatewayWebSocketClient
import us.snakepit.gravitywell.net.HostVerifier
import us.snakepit.gravitywell.net.NetworkModule
import us.snakepit.gravitywell.net.WsConnectionState
import us.snakepit.gravitywell.net.WsServerFrame
import us.snakepit.gravitywell.net.WsTarget
import us.snakepit.gravitywell.security.TokenVault
import us.snakepit.gravitywell.service.AppForegroundTracker
import us.snakepit.gravitywell.service.KeepaliveCoordinator
import us.snakepit.gravitywell.service.KeepalivePreferences
import us.snakepit.gravitywell.ui.console.SessionConsoleController
import us.snakepit.gravitywell.ui.deck.ControlDeckController
import us.snakepit.gravitywell.ui.deck.EcosystemApi
import us.snakepit.gravitywell.ui.hosts.HostOnboardingController
import us.snakepit.gravitywell.ui.observatory.ObservatoryApi
import us.snakepit.gravitywell.ui.observatory.ObservatoryController
import us.snakepit.gravitywell.ui.sessions.SessionBrowserController
import us.snakepit.gravitywell.ui.sessions.SessionSupplementaryApi

/**
 * Coordinates multi-host selection, shared OkHttp/WebSocket lifecycle, Session Browser,
 * Live Session Console, Observatory, Control Deck, and Keepalive across the Adaptive Shell.
 */
class ShellController(
    private val cacheStore: OfflineCacheStore,
    private val tokenVault: TokenVault,
    private val scope: CoroutineScope,
    private val isDebugBuild: Boolean = BuildConfig.DEBUG,
    private val json: Json = NetworkModule.defaultJson,
    private val backoffConfig: BackoffConfig = BackoffConfig(),
    private val autoConnectWebSocket: Boolean = true,
    private val clientFactory: ((() -> String?) -> OkHttpClient) = { tokenProvider ->
        NetworkModule.createOkHttpClient(tokenProvider = tokenProvider, json = json)
    },
    initialContext: Context? = null,
    initialKeepalivePreferences: KeepalivePreferences? = null,
    private val keepaliveServiceDriver: KeepaliveCoordinator.ServiceDriver = KeepaliveCoordinator.AndroidServiceDriver,
    private val isForegroundProvider: () -> Boolean = { AppForegroundTracker.isAppInForeground },
) {
    private val preflightHttpClient: OkHttpClient by lazy { clientFactory { null } }

    private var boundAppContext: Context? = initialContext?.applicationContext
    private var keepalivePrefs: KeepalivePreferences = initialKeepalivePreferences
        ?: initialContext?.let { KeepalivePreferences.get(it) }
        ?: KeepalivePreferences.inMemory(true)
    private var keepaliveCoordinator: KeepaliveCoordinator = KeepaliveCoordinator(
        cacheStore = cacheStore,
        preferences = keepalivePrefs,
        isForegroundProvider = isForegroundProvider,
        serviceController = keepaliveServiceDriver,
    )

    private val _state = MutableStateFlow(ShellUiState())
    val state: StateFlow<ShellUiState> = _state.asStateFlow()

    private val _keepaliveEnabled = MutableStateFlow(keepalivePrefs.isKeepaliveEnabled)
    val keepaliveEnabled: StateFlow<Boolean> = _keepaliveEnabled.asStateFlow()

    private val _onboardingController = MutableStateFlow(createOnboardingController(null))
    val onboardingController: StateFlow<HostOnboardingController> = _onboardingController.asStateFlow()

    private val _sessionBrowserController = MutableStateFlow<SessionBrowserController?>(null)
    val sessionBrowserController: StateFlow<SessionBrowserController?> = _sessionBrowserController.asStateFlow()

    private val _sessionConsoleController = MutableStateFlow<SessionConsoleController?>(null)
    val sessionConsoleController: StateFlow<SessionConsoleController?> = _sessionConsoleController.asStateFlow()

    private val _observatoryController = MutableStateFlow<ObservatoryController?>(null)
    val observatoryController: StateFlow<ObservatoryController?> = _observatoryController.asStateFlow()

    private val _controlDeckController = MutableStateFlow<ControlDeckController?>(null)
    val controlDeckController: StateFlow<ControlDeckController?> = _controlDeckController.asStateFlow()

    private var activeHostClient: OkHttpClient? = null
    private var activeGatewayApi: GatewayApi? = null
    private var activeSupplementaryApi: SessionSupplementaryApi? = null
    private var activeObservatoryApi: ObservatoryApi? = null
    private var activeEcosystemApi: EcosystemApi? = null
    private var activeWsClient: GatewayWebSocketClient? = null
    private var wsCollectorJob: Job? = null
    private var browserWatchJob: Job? = null
    private var prefsWatchJob: Job? = null
    private var boundHostId: String? = null
    private var boundBaseUrl: String? = null
    private var boundLastVerifiedAt: String? = null
    private var boundTokenDigest: String? = null
    private var deferredDeepLink: PendingDeepLink? = null

    init {
        observeKeepalivePrefs()
        reloadHostsAndBindActive()
    }

    private fun observeKeepalivePrefs() {
        prefsWatchJob?.cancel()
        prefsWatchJob = scope.launch {
            keepalivePrefs.keepaliveEnabled.collect { enabled ->
                _keepaliveEnabled.value = enabled
            }
        }
    }

    fun bindApplicationContext(context: Context) {
        val appCtx = context.applicationContext
        if (boundAppContext === appCtx) {
            keepalivePrefs.reload()
            _keepaliveEnabled.value = keepalivePrefs.isKeepaliveEnabled
            keepaliveCoordinator.syncServiceState(
                context = boundAppContext,
                host = _state.value.activeHost,
                attachedSessionId = _state.value.attachedSessionId,
            )
            return
        }
        boundAppContext = appCtx
        keepalivePrefs = KeepalivePreferences.get(appCtx)
        keepaliveCoordinator = KeepaliveCoordinator(
            cacheStore = cacheStore,
            preferences = keepalivePrefs,
            isForegroundProvider = isForegroundProvider,
            serviceController = keepaliveServiceDriver,
        )
        observeKeepalivePrefs()
        keepaliveCoordinator.syncServiceState(
            context = boundAppContext,
            host = _state.value.activeHost,
            attachedSessionId = _state.value.attachedSessionId,
        )
    }

    fun setKeepaliveEnabled(enabled: Boolean) {
        keepaliveCoordinator.setKeepaliveEnabled(
            context = boundAppContext,
            enabled = enabled,
            activeHost = _state.value.activeHost,
            attachedSessionId = _state.value.attachedSessionId,
        )
        _keepaliveEnabled.value = keepalivePrefs.isKeepaliveEnabled
    }

    private fun createOnboardingController(targetHost: CachedHost?): HostOnboardingController =
        HostOnboardingController(
            verifier = HostVerifier(okHttpClient = preflightHttpClient, json = json),
            cacheStore = cacheStore,
            tokenVault = tokenVault,
            scope = scope,
            isDebugBuild = isDebugBuild,
            json = json,
            initialHost = targetHost,
        )

    fun reloadHostsAndBindActive(forceRebind: Boolean = false) {
        val allHosts = cacheStore.listHosts()
        var active = cacheStore.getActiveHost()
        if (active == null && allHosts.isNotEmpty()) {
            val first = allHosts.first()
            cacheStore.setActiveHost(first.hostId)
            active = cacheStore.getHost(first.hostId) ?: first
        }
        val caps = active?.capabilitiesJson?.let {
            runCatching { json.decodeFromString<CapabilitiesDto>(it) }.getOrNull()
        } ?: CapabilitiesDto()

        _state.update {
            it.copy(
                initialized = true,
                hosts = cacheStore.listHosts(),
                activeHost = active,
                activeCapabilities = caps,
                latestCursor = active?.lastCursor ?: 0L,
            )
        }

        if (active == null) {
            teardownActiveHostRuntime()
            _onboardingController.value = createOnboardingController(null)
            return
        }

        val currentTokenDigest = digestToken(tokenVault.getToken(active.hostId))
        val shouldRebind = forceRebind ||
            _sessionBrowserController.value == null ||
            active.hostId != boundHostId ||
            active.baseUrl != boundBaseUrl ||
            active.lastVerifiedAt != boundLastVerifiedAt ||
            currentTokenDigest != boundTokenDigest

        if (shouldRebind) {
            bindHostRuntime(active)
        }

        val pendingLink = deferredDeepLink
        if (pendingLink != null) {
            deferredDeepLink = null
            consumeDeepLink(pendingLink)
        }
    }

    private fun bindHostRuntime(host: CachedHost) {
        teardownActiveHostRuntime()
        val parsedBaseUrl = host.baseUrl.toHttpUrlOrNull() ?: return

        boundHostId = host.hostId
        boundBaseUrl = host.baseUrl
        boundLastVerifiedAt = host.lastVerifiedAt
        boundTokenDigest = digestToken(tokenVault.getToken(host.hostId))
        AppForegroundTracker.setShellStreamActive(true)

        val okHttp = clientFactory { tokenVault.getToken(host.hostId) }
        val gwApi = NetworkModule.createGatewayApi(parsedBaseUrl, okHttp, json)
        val suppApi = SessionSupplementaryApi.create(parsedBaseUrl, okHttp, json)
        val obsApi = ObservatoryApi.create(parsedBaseUrl, okHttp, json)
        val ecoApi = EcosystemApi.create(parsedBaseUrl, okHttp, json)

        activeHostClient = okHttp
        activeGatewayApi = gwApi
        activeSupplementaryApi = suppApi
        activeObservatoryApi = obsApi
        activeEcosystemApi = ecoApi

        val browser = SessionBrowserController(
            host = host,
            gatewayApi = gwApi,
            supplementaryApi = suppApi,
            cacheStore = cacheStore,
            scope = scope,
            json = json,
        )
        _sessionBrowserController.value = browser

        val observatory = ObservatoryController(
            host = host,
            api = obsApi,
            cacheStore = cacheStore,
            scope = scope,
            json = json,
        )
        _observatoryController.value = observatory

        val deck = ControlDeckController(
            host = host,
            ecosystemApi = ecoApi,
            supplementaryApi = suppApi,
            scope = scope,
        )
        _controlDeckController.value = deck

        browser.refresh()
        if (_state.value.destination == TopLevelDestination.OBSERVATORY) {
            observatory.refreshAll()
        } else if (_state.value.destination == TopLevelDestination.DECK) {
            deck.refreshAll()
        }

        browserWatchJob = scope.launch {
            browser.state.collect {
                keepaliveCoordinator.syncServiceState(
                    context = boundAppContext,
                    host = host,
                    attachedSessionId = _state.value.attachedSessionId,
                )
            }
        }

        val ws = GatewayWebSocketClient(
            okHttpClient = okHttp,
            baseUrl = parsedBaseUrl,
            tokenProvider = { tokenVault.getToken(host.hostId).orEmpty() },
            target = WsTarget.Global,
            scope = scope,
            backoffConfig = backoffConfig,
            json = json,
            initialCursor = host.lastCursor,
        )
        activeWsClient = ws

        wsCollectorJob = scope.launch {
            launch {
                ws.state.collect { wsSt ->
                    _state.update { it.copy(wsState = wsSt) }
                    _sessionConsoleController.value?.onWsConnectionStateChanged(wsSt)
                }
            }
            launch {
                ws.frames.collect { frame ->
                    handleGlobalWsFrame(host, frame)
                }
            }
        }

        if (autoConnectWebSocket) {
            ws.connect(afterCursor = if (host.lastCursor > 0L) host.lastCursor else null)
        }

        val currentSessionId = _state.value.attachedSessionId
        if (!currentSessionId.isNullOrBlank()) {
            bindConsoleForSession(host, currentSessionId)
        }
        keepaliveCoordinator.syncServiceState(
            context = boundAppContext,
            host = host,
            attachedSessionId = currentSessionId,
        )
    }

    private fun handleGlobalWsFrame(host: CachedHost, frame: WsServerFrame) {
        when (frame) {
            is WsServerFrame.Hello -> {
                cacheStore.updateHostLastCursor(host.hostId, frame.latestCursor)
                _state.update {
                    it.copy(
                        latestCursor = maxOf(it.latestCursor, frame.latestCursor),
                        activeCapabilities = frame.capabilities,
                    )
                }
            }
            is WsServerFrame.Event -> {
                cacheStore.updateHostLastCursor(host.hostId, frame.cursor)
                _state.update { it.copy(latestCursor = maxOf(it.latestCursor, frame.cursor)) }
                val attachedSid = _state.value.attachedSessionId
                val consoleCtrl = _sessionConsoleController.value
                val isAttachedConsoleEvent = frame.sessionId != null &&
                    consoleCtrl != null &&
                    (frame.sessionId == attachedSid || frame.sessionId == consoleCtrl.state.value.session.sessionId)
                if (isAttachedConsoleEvent) {
                    consoleCtrl.onWebSocketFrame(frame)
                } else {
                    persistBackgroundSessionEvent(host, frame)
                }
                _sessionBrowserController.value?.onWebSocketFrame(frame)
                _observatoryController.value?.onWebSocketFrame(frame)
                keepaliveCoordinator.onWebSocketEvent(
                    context = boundAppContext,
                    host = host,
                    attachedSessionId = attachedSid,
                    frame = frame,
                )
            }
            is WsServerFrame.ReplayComplete -> {
                cacheStore.updateHostLastCursor(host.hostId, frame.latestCursor)
                _state.update { it.copy(latestCursor = maxOf(it.latestCursor, frame.latestCursor)) }
                _sessionConsoleController.value?.onWebSocketFrame(frame)
            }
            is WsServerFrame.ResyncRequired -> {
                cacheStore.updateHostLastCursor(host.hostId, frame.latestCursor)
                _state.update { it.copy(latestCursor = maxOf(it.latestCursor, frame.latestCursor)) }
                _sessionBrowserController.value?.refresh()
                _sessionConsoleController.value?.onWebSocketFrame(frame)
            }
            else -> Unit
        }
    }

    private fun persistBackgroundSessionEvent(host: CachedHost, frame: WsServerFrame.Event) {
        val sid = frame.sessionId ?: return
        val activeConsoleSid = _state.value.attachedSessionId
        if (sid == activeConsoleSid && _sessionConsoleController.value != null) {
            return
        }
        val existing = cacheStore.getSession(host.hostId, sid)
        val updatedStatus = when (frame.type) {
            "session_status" -> frame.payload["status"]?.jsonPrimitive?.contentOrNull ?: existing?.status ?: "running"
            "session_closed" -> "closed"
            "permission_requested" -> "awaiting_approval"
            "question_requested" -> "awaiting_question"
            else -> existing?.status ?: "running"
        }
        val updatedTitle = if (frame.type == "session_info_update") {
            frame.payload["title"]?.jsonPrimitive?.contentOrNull ?: existing?.title ?: "Live Session"
        } else {
            existing?.title ?: "Live Session"
        }
        val updatedSource = if (frame.type == "session_info_update") {
            frame.payload["title_source"]?.jsonPrimitive?.contentOrNull ?: existing?.titleSource ?: "acp"
        } else {
            existing?.titleSource ?: "acp"
        }
        cacheStore.upsertSession(
            (existing ?: CachedSession(
                hostId = host.hostId,
                sessionId = sid,
                title = updatedTitle,
                titleSource = updatedSource,
                cwd = host.defaultCwd.orEmpty(),
                status = updatedStatus,
                isLive = updatedStatus != "closed",
                updatedAt = frame.ts.ifBlank { Instant.now().toString() },
            )).copy(
                title = updatedTitle,
                titleSource = updatedSource,
                status = updatedStatus,
                isLive = updatedStatus != "closed" && updatedStatus != "archived",
                lastCursor = maxOf(existing?.lastCursor ?: 0L, frame.cursor),
                lastSeq = maxOf(existing?.lastSeq ?: 0L, frame.seq),
                updatedAt = frame.ts.ifBlank { existing?.updatedAt ?: Instant.now().toString() },
            ),
        )
        if (frame.seq > 0L) {
            cacheStore.insertSessionEvent(
                CachedSessionEvent(
                    hostId = host.hostId,
                    sessionId = sid,
                    seq = frame.seq,
                    cursor = frame.cursor,
                    eventType = frame.type,
                    payloadJson = frame.payload.toString(),
                    ts = frame.ts,
                ),
            )
        }
    }

    private fun bindConsoleForSession(host: CachedHost, sessionId: String) {
        val baseUrl = host.baseUrl.toHttpUrlOrNull() ?: return
        val okHttp = activeHostClient ?: return
        val gwApi = activeGatewayApi ?: return
        val suppApi = activeSupplementaryApi ?: return

        val current = _sessionConsoleController.value
        if (current != null && current.state.value.session.sessionId == sessionId && current.state.value.hostId == host.hostId) {
            return
        }

        val controller = SessionConsoleController(
            host = host,
            sessionId = sessionId,
            baseUrl = baseUrl,
            okHttpClient = okHttp,
            gatewayApi = gwApi,
            supplementaryApi = suppApi,
            cacheStore = cacheStore,
            scope = scope,
            json = json,
        )
        controller.onWsConnectionStateChanged(_state.value.wsState)
        _sessionConsoleController.value = controller
        controller.syncWithGateway()
    }

    fun selectDestination(destination: TopLevelDestination) {
        _state.update { it.copy(destination = destination) }
        when (destination) {
            TopLevelDestination.OBSERVATORY -> {
                _observatoryController.value?.refreshAll()
            }
            TopLevelDestination.DECK -> {
                _controlDeckController.value?.refreshAll()
            }
            else -> Unit
        }
    }

    fun openSessionInConsole(sessionId: String) {
        val host = _state.value.activeHost ?: return
        _state.update {
            it.copy(
                attachedSessionId = sessionId,
                destination = TopLevelDestination.CONSOLE,
                showHostSwitcherSheet = false,
                showAddHostModal = false,
                reverifyHostTarget = null,
            )
        }
        bindConsoleForSession(host, sessionId)
        keepaliveCoordinator.syncServiceState(
            context = boundAppContext,
            host = host,
            attachedSessionId = sessionId,
        )
    }

    fun exitCompactConsoleToSessions() {
        _state.update {
            it.copy(destination = TopLevelDestination.SESSIONS)
        }
    }

    fun detachActiveConsoleSession() {
        _sessionConsoleController.value = null
        _state.update {
            it.copy(
                attachedSessionId = null,
                destination = TopLevelDestination.SESSIONS,
            )
        }
        keepaliveCoordinator.syncServiceState(
            context = boundAppContext,
            host = _state.value.activeHost,
            attachedSessionId = null,
        )
    }

    fun consumeDeepLink(deepLink: PendingDeepLink) {
        val cleanSessionId = deepLink.sessionId.trim()
        if (cleanSessionId.isEmpty()) return

        if (!_state.value.initialized) {
            deferredDeepLink = deepLink.copy(sessionId = cleanSessionId)
            return
        }

        val targetHostId = deepLink.hostId?.trim()?.ifEmpty { null }
        if (targetHostId != null) {
            val matchedHost = cacheStore.getHost(targetHostId) ?: return
            if (matchedHost.hostId != _state.value.activeHost?.hostId) {
                cacheStore.setActiveHost(matchedHost.hostId)
                _state.update {
                    it.copy(
                        attachedSessionId = cleanSessionId,
                        destination = TopLevelDestination.CONSOLE,
                        showHostSwitcherSheet = false,
                        showAddHostModal = false,
                        reverifyHostTarget = null,
                    )
                }
                reloadHostsAndBindActive()
                return
            }
        }
        openSessionInConsole(cleanSessionId)
    }

    // ── Host Switcher & Onboarding Modals ───────────────────────────────────

    fun setShowHostSwitcher(show: Boolean) {
        _state.update { it.copy(showHostSwitcherSheet = show) }
    }

    fun selectActiveHost(hostId: String) {
        if (_state.value.activeHost?.hostId == hostId) {
            _state.update { it.copy(showHostSwitcherSheet = false) }
            return
        }
        cacheStore.setActiveHost(hostId)
        _sessionConsoleController.value = null
        _state.update {
            it.copy(
                showHostSwitcherSheet = false,
                attachedSessionId = null,
                destination = TopLevelDestination.SESSIONS,
            )
        }
        reloadHostsAndBindActive()
    }

    fun openAddHostModal() {
        _onboardingController.value = createOnboardingController(null)
        _state.update {
            it.copy(
                showHostSwitcherSheet = false,
                showAddHostModal = true,
                reverifyHostTarget = null,
            )
        }
    }

    fun openReverifyHostModal(host: CachedHost) {
        _onboardingController.value = createOnboardingController(host)
        _state.update {
            it.copy(
                showHostSwitcherSheet = false,
                showAddHostModal = true,
                reverifyHostTarget = host,
            )
        }
    }

    fun dismissAddHostModal() {
        _state.update {
            it.copy(
                showAddHostModal = false,
                reverifyHostTarget = null,
            )
        }
    }

    fun onHostOnboardingSaved(saved: CachedHost) {
        _state.update {
            it.copy(
                showAddHostModal = false,
                reverifyHostTarget = null,
                attachedSessionId = null,
                destination = TopLevelDestination.SESSIONS,
            )
        }
        cacheStore.setActiveHost(saved.hostId)
        reloadHostsAndBindActive(forceRebind = true)
    }

    fun deleteHost(hostId: String) {
        val wasActive = _state.value.activeHost?.hostId == hostId
        cacheStore.deleteHost(hostId)
        tokenVault.removeToken(hostId)
        if (wasActive) {
            _sessionConsoleController.value = null
            _state.update { it.copy(attachedSessionId = null, showHostSwitcherSheet = false) }
        }
        reloadHostsAndBindActive()
    }

    fun shutdown() {
        prefsWatchJob?.cancel()
        prefsWatchJob = null
        teardownActiveHostRuntime()
    }

    private fun teardownActiveHostRuntime() {
        AppForegroundTracker.setShellStreamActive(false)
        browserWatchJob?.cancel()
        browserWatchJob = null
        wsCollectorJob?.cancel()
        wsCollectorJob = null
        activeWsClient?.disconnect()
        activeWsClient = null
        activeHostClient = null
        activeGatewayApi = null
        activeSupplementaryApi = null
        activeObservatoryApi = null
        activeEcosystemApi = null
        boundHostId = null
        boundBaseUrl = null
        boundLastVerifiedAt = null
        boundTokenDigest = null
        _sessionBrowserController.value = null
        _sessionConsoleController.value = null
        _observatoryController.value = null
        _controlDeckController.value = null
        _state.update { it.copy(wsState = WsConnectionState.Disconnected) }
    }

    private fun digestToken(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
