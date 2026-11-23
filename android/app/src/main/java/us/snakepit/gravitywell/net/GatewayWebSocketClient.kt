package us.snakepit.gravitywell.net

import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToLong
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

sealed interface WsTarget {
    data object Global : WsTarget
    data class SingleSession(val sessionId: String) : WsTarget
}

sealed interface WsConnectionState {
    data object Disconnected : WsConnectionState
    data class Connecting(val attempt: Int) : WsConnectionState
    data object Replaying : WsConnectionState
    data object Live : WsConnectionState
    data class Reconnecting(
        val attempt: Int,
        val delayMs: Long,
        val reason: String,
    ) : WsConnectionState
    data class AuthFailed(val error: GatewayHttpException) : WsConnectionState
}

data class BackoffConfig(
    val stepsMs: List<Long> = DEFAULT_STEPS_MS,
    val maxJitterRatio: Double = 0.2,
    val randomFraction: () -> Double = { Random.nextDouble() },
) {
    init {
        require(stepsMs.isNotEmpty()) { "stepsMs must not be empty" }
        require(maxJitterRatio >= 0.0) { "maxJitterRatio must be >= 0.0" }
    }

    fun delayForAttemptMs(attempt: Int): Long {
        val idx = attempt.coerceIn(0, stepsMs.lastIndex)
        val base = stepsMs[idx]
        if (maxJitterRatio == 0.0) return base
        val jitterFraction = randomFraction().coerceIn(0.0, 1.0) * maxJitterRatio
        return base + (base * jitterFraction).roundToLong()
    }

    companion object {
        val DEFAULT_STEPS_MS = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L)
    }
}

/**
 * Exponential-backoff WebSocket client with single-use ticket minting,
 * ?after=<cursor> replay, and AtomicLong generation guard (docs/SPEC.md D14).
 */
class GatewayWebSocketClient(
    private val okHttpClient: OkHttpClient,
    private val baseUrl: HttpUrl,
    private val tokenProvider: () -> String,
    private val target: WsTarget = WsTarget.Global,
    private val scope: CoroutineScope,
    private val backoffConfig: BackoffConfig = BackoffConfig(),
    private val json: Json = NetworkModule.defaultJson,
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
    initialCursor: Long = 0L,
) {
    private val api: GatewayApi = NetworkModule.createGatewayApi(baseUrl, okHttpClient, json)
    private val connectionGeneration = AtomicLong(0L)
    private val lastSeenCursor = AtomicLong(initialCursor.coerceAtLeast(0L))

    @Volatile
    private var activeWebSocket: WebSocket? = null

    @Volatile
    private var connectionJob: Job? = null

    @Volatile
    private var sessionFilterIds: List<String>? = null

    private val _state = MutableStateFlow<WsConnectionState>(WsConnectionState.Disconnected)
    val state: StateFlow<WsConnectionState> = _state.asStateFlow()

    private val _frames = MutableSharedFlow<WsServerFrame>(extraBufferCapacity = 256)
    val frames: SharedFlow<WsServerFrame> = _frames.asSharedFlow()

    val currentGeneration: Long
        get() = connectionGeneration.get()

    val latestCursor: Long
        get() = lastSeenCursor.get()

    fun connect(
        afterCursor: Long? = null,
        sessionFilter: List<String>? = sessionFilterIds,
    ) {
        sessionFilterIds = sessionFilter
        val sendInitialAfter = if (afterCursor != null) {
            lastSeenCursor.set(afterCursor.coerceAtLeast(0L))
            true
        } else {
            lastSeenCursor.get() > 0L
        }

        val myGen = connectionGeneration.incrementAndGet()
        closeActiveSocket("superseded")
        connectionJob?.cancel()
        connectionJob = scope.launch(Dispatchers.IO) {
            runConnectionLoop(myGen, initialSendAfter = sendInitialAfter)
        }
    }

    fun updateSessionFilter(sessionIds: List<String>?) {
        sessionFilterIds = sessionIds
        val ws = activeWebSocket ?: return
        if (target is WsTarget.Global) {
            ws.send(json.encodeToString(WsClientSubscribe(sessionIds = sessionIds)))
        }
    }

    fun sendPing(): Boolean {
        val ws = activeWebSocket ?: return false
        return ws.send(json.encodeToString(WsClientPing()))
    }

    fun disconnect() {
        connectionGeneration.incrementAndGet()
        connectionJob?.cancel()
        connectionJob = null
        closeActiveSocket("client_disconnect")
        _state.value = WsConnectionState.Disconnected
    }

    private fun closeActiveSocket(reason: String) {
        val socket = activeWebSocket
        activeWebSocket = null
        socket?.close(1000, reason)
    }

    private suspend fun runConnectionLoop(
        myGen: Long,
        initialSendAfter: Boolean,
    ) {
        var attempt = 0
        var shouldSendAfter = initialSendAfter

        while (scope.isActive && connectionGeneration.get() == myGen) {
            _state.value = WsConnectionState.Connecting(attempt)

            val ticketResponse = try {
                val tok = tokenProvider().trim()
                if (tok.isEmpty()) {
                    throw GatewayHttpException(401, "unauthorized", "Missing bearer token.")
                }
                api.mintWsTicket(authorization = "Bearer $tok")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (http: GatewayHttpException) {
                if (connectionGeneration.get() != myGen) return
                if (http.status == 401 || http.status == 403) {
                    _state.value = WsConnectionState.AuthFailed(http)
                    return
                }
                val delayMs = backoffConfig.delayForAttemptMs(attempt)
                _state.value = WsConnectionState.Reconnecting(
                    attempt = attempt + 1,
                    delayMs = delayMs,
                    reason = "${http.code}: ${http.message}",
                )
                delayFn(delayMs)
                attempt++
                continue
            } catch (t: Throwable) {
                if (connectionGeneration.get() != myGen) return
                val delayMs = backoffConfig.delayForAttemptMs(attempt)
                _state.value = WsConnectionState.Reconnecting(
                    attempt = attempt + 1,
                    delayMs = delayMs,
                    reason = t.message ?: t.javaClass.simpleName,
                )
                delayFn(delayMs)
                attempt++
                continue
            }

            if (connectionGeneration.get() != myGen) return

            val afterParam = if (shouldSendAfter) lastSeenCursor.get() else null
            val endpoint = when (target) {
                WsTarget.Global -> "api/ws"
                is WsTarget.SingleSession -> "api/sessions/${target.sessionId}/ws"
            }
            val wsUrl = UrlSecurityPolicy.buildWebSocketUrl(
                baseUrl = baseUrl,
                endpointPath = endpoint,
                ticket = ticketResponse.ticket,
                afterCursor = afterParam,
            )

            val attemptOutcome = openAndAwaitSocket(
                myGen = myGen,
                wsUrl = wsUrl,
                expectingReplay = afterParam != null,
                onHealthyHello = {
                    attempt = 0
                    shouldSendAfter = true
                },
            )

            if (connectionGeneration.get() != myGen) return
            when (attemptOutcome) {
                SocketOutcome.Superseded -> return
                is SocketOutcome.Ended -> {
                    shouldSendAfter = true
                    val delayMs = backoffConfig.delayForAttemptMs(attempt)
                    _state.value = WsConnectionState.Reconnecting(
                        attempt = attempt + 1,
                        delayMs = delayMs,
                        reason = attemptOutcome.reason,
                    )
                    delayFn(delayMs)
                    attempt++
                }
            }
        }
    }

    internal sealed interface SocketOutcome {
        data object Superseded : SocketOutcome
        data class Ended(val reason: String) : SocketOutcome
    }

    private suspend fun openAndAwaitSocket(
        myGen: Long,
        wsUrl: String,
        expectingReplay: Boolean,
        onHealthyHello: () -> Unit,
    ): SocketOutcome {
        val outcome = CompletableDeferred<SocketOutcome>()
        val request = Request.Builder().url(wsUrl).build()

        val listener = createListenerForGeneration(
            myGen = myGen,
            expectingReplay = expectingReplay,
            onHealthyHello = onHealthyHello,
            onOutcome = { outcome.complete(it) },
        )

        val socket = okHttpClient.newWebSocket(request, listener)
        if (connectionGeneration.get() != myGen) {
            socket.close(1000, "superseded")
            return SocketOutcome.Superseded
        }
        activeWebSocket = socket

        return try {
            outcome.await()
        } catch (cancel: CancellationException) {
            socket.close(1000, "cancelled")
            throw cancel
        }
    }

    internal fun createListenerForGeneration(
        myGen: Long,
        expectingReplay: Boolean = false,
        onHealthyHello: () -> Unit = {},
        onOutcome: (SocketOutcome) -> Unit = {},
    ): WebSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (connectionGeneration.get() != myGen) {
                webSocket.close(1000, "stale_generation")
                return
            }
            webSocket.send(json.encodeToString(WsClientHello()))
            val filter = sessionFilterIds
            if (target is WsTarget.Global && filter != null) {
                webSocket.send(json.encodeToString(WsClientSubscribe(sessionIds = filter)))
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (connectionGeneration.get() != myGen) return
            val frame = try {
                WsFrameParser.parse(text, json)
            } catch (t: Throwable) {
                _frames.tryEmit(WsServerFrame.Error(code = "parse_error", message = t.message ?: "Invalid frame"))
                return
            }

            when (frame) {
                is WsServerFrame.Hello -> {
                    onHealthyHello()
                    if (!expectingReplay && lastSeenCursor.get() == 0L && frame.latestCursor > 0L) {
                        lastSeenCursor.accumulateAndGet(frame.latestCursor, ::maxOf)
                    }
                    _state.value = if (expectingReplay) {
                        WsConnectionState.Replaying
                    } else {
                        WsConnectionState.Live
                    }
                }
                is WsServerFrame.Event -> {
                    lastSeenCursor.accumulateAndGet(frame.cursor, ::maxOf)
                }
                is WsServerFrame.ReplayComplete -> {
                    lastSeenCursor.accumulateAndGet(frame.latestCursor, ::maxOf)
                    _state.value = WsConnectionState.Live
                }
                is WsServerFrame.ResyncRequired -> {
                    lastSeenCursor.accumulateAndGet(frame.latestCursor, ::maxOf)
                    _state.value = WsConnectionState.Live
                }
                is WsServerFrame.Pong,
                is WsServerFrame.Error,
                is WsServerFrame.Unknown,
                -> Unit
            }
            _frames.tryEmit(frame)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (connectionGeneration.get() != myGen) {
                onOutcome(SocketOutcome.Superseded)
            } else {
                onOutcome(SocketOutcome.Ended("closed ($code): $reason"))
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (connectionGeneration.get() != myGen) {
                onOutcome(SocketOutcome.Superseded)
            } else {
                val msg = response?.let { "HTTP ${it.code}: ${it.message}" }
                    ?: (t.message ?: t.javaClass.simpleName)
                onOutcome(SocketOutcome.Ended(msg))
            }
        }
    }
}
