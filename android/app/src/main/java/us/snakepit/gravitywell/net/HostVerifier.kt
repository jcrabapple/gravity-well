package us.snakepit.gravitywell.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

enum class VerificationLegId {
    REACHABILITY_TLS,
    AUTH_CAPABILITIES,
    WS_TICKET_HELLO,
}

enum class VerificationLegStatus {
    NOT_RUN,
    RUNNING,
    VERIFIED,
    FAILED,
}

data class VerificationLegState(
    val leg: VerificationLegId,
    val status: VerificationLegStatus = VerificationLegStatus.NOT_RUN,
    val latencyMs: Long? = null,
    val summary: String? = null,
    val errorStatus: Int? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
)

data class HostVerificationReport(
    val baseUrl: HttpUrl,
    val legs: Map<VerificationLegId, VerificationLegState>,
    val health: HealthResponse? = null,
    val info: GatewayInfoResponse? = null,
    val hello: WsServerFrame.Hello? = null,
) {
    val isAllVerified: Boolean
        get() = VerificationLegId.entries.all { legs[it]?.status == VerificationLegStatus.VERIFIED }
}

/**
 * 3-leg sequential pre-flight host verifier (docs/SPEC.md D13):
 *   1. GET /api/health (unauthenticated reachability + protocol_version == 1)
 *   2. GET /api/info (Authorization: Bearer <token> + capability discovery)
 *   3. POST /api/ws-ticket -> WS /api/ws?ticket=... -> await hello frame
 */
class HostVerifier(
    private val okHttpClient: OkHttpClient,
    private val json: Json = NetworkModule.defaultJson,
    private val wsHelloTimeoutMs: Long = 8_000L,
    private val clockNanos: () -> Long = { System.nanoTime() },
) {

    suspend fun verify(
        baseUrl: HttpUrl,
        bearerToken: String,
        onProgress: (HostVerificationReport) -> Unit = {},
    ): HostVerificationReport = withContext(Dispatchers.IO) {
        val cleanToken = bearerToken.trim()
        val api = NetworkModule.createGatewayApi(baseUrl, okHttpClient, json)

        val legStates = linkedMapOf(
            VerificationLegId.REACHABILITY_TLS to VerificationLegState(VerificationLegId.REACHABILITY_TLS),
            VerificationLegId.AUTH_CAPABILITIES to VerificationLegState(VerificationLegId.AUTH_CAPABILITIES),
            VerificationLegId.WS_TICKET_HELLO to VerificationLegState(VerificationLegId.WS_TICKET_HELLO),
        )
        var healthResult: HealthResponse? = null
        var infoResult: GatewayInfoResponse? = null
        var helloResult: WsServerFrame.Hello? = null

        fun snapshot() = HostVerificationReport(
            baseUrl = baseUrl,
            legs = legStates.toMap(),
            health = healthResult,
            info = infoResult,
            hello = helloResult,
        )

        fun markRunning(leg: VerificationLegId) {
            legStates[leg] = VerificationLegState(leg = leg, status = VerificationLegStatus.RUNNING)
            onProgress(snapshot())
        }

        fun failLeg(leg: VerificationLegId, latencyMs: Long, err: Throwable): HostVerificationReport {
            val (status, code, msg) = when (err) {
                is GatewayHttpException -> Triple(err.status, err.code, err.message)
                is GatewayTlsException -> Triple(null, "tls_error", err.message)
                is GatewayNetworkException -> Triple(null, "network_error", err.message)
                is GatewayProtocolException -> Triple(null, "protocol_error", err.message)
                else -> Triple(null, "verification_error", err.message ?: err.javaClass.simpleName)
            }
            legStates[leg] = VerificationLegState(
                leg = leg,
                status = VerificationLegStatus.FAILED,
                latencyMs = latencyMs,
                errorStatus = status,
                errorCode = code,
                errorMessage = msg,
            )
            return snapshot().also(onProgress)
        }

        // ── Leg 1: GET /api/health ──────────────────────────────────────────
        markRunning(VerificationLegId.REACHABILITY_TLS)
        val t1 = clockNanos()
        try {
            val health = api.getHealth()
            if (health.status != "ok") {
                throw GatewayProtocolException("Unexpected health status: ${health.status}")
            }
            if (health.protocolVersion != EXPECTED_PROTOCOL_VERSION) {
                throw GatewayProtocolException(
                    "Unsupported protocol_version=${health.protocolVersion} (expected $EXPECTED_PROTOCOL_VERSION)",
                )
            }
            healthResult = health
            val ms1 = elapsedMs(t1)
            legStates[VerificationLegId.REACHABILITY_TLS] = VerificationLegState(
                leg = VerificationLegId.REACHABILITY_TLS,
                status = VerificationLegStatus.VERIFIED,
                latencyMs = ms1,
                summary = "protocol v${health.protocolVersion} · agy ${health.agyVersion}",
            )
            onProgress(snapshot())
        } catch (t: Throwable) {
            return@withContext failLeg(VerificationLegId.REACHABILITY_TLS, elapsedMs(t1), t)
        }

        // ── Leg 2: GET /api/info ────────────────────────────────────────────
        markRunning(VerificationLegId.AUTH_CAPABILITIES)
        val t2 = clockNanos()
        try {
            if (cleanToken.isEmpty()) {
                throw GatewayHttpException(401, "unauthorized", "Bearer token must not be empty.")
            }
            val info = api.getInfo(authorization = "Bearer $cleanToken")
            infoResult = info
            val ms2 = elapsedMs(t2)
            legStates[VerificationLegId.AUTH_CAPABILITIES] = VerificationLegState(
                leg = VerificationLegId.AUTH_CAPABILITIES,
                status = VerificationLegStatus.VERIFIED,
                latencyMs = ms2,
                summary = "${info.hostname} · ${info.defaultCwd}",
            )
            onProgress(snapshot())
        } catch (t: Throwable) {
            return@withContext failLeg(VerificationLegId.AUTH_CAPABILITIES, elapsedMs(t2), t)
        }

        // ── Leg 3: POST /api/ws-ticket -> WS /api/ws?ticket=... -> hello ────
        markRunning(VerificationLegId.WS_TICKET_HELLO)
        val t3 = clockNanos()
        try {
            val ticket = api.mintWsTicket(authorization = "Bearer $cleanToken")
            if (ticket.ticket.isBlank()) {
                throw GatewayProtocolException("Gateway returned empty WebSocket ticket")
            }
            val wsUrl = UrlSecurityPolicy.buildWebSocketUrl(
                baseUrl = baseUrl,
                endpointPath = "api/ws",
                ticket = ticket.ticket,
                afterCursor = null,
            )
            val hello = awaitWsHello(wsUrl)
            helloResult = hello
            val ms3 = elapsedMs(t3)
            legStates[VerificationLegId.WS_TICKET_HELLO] = VerificationLegState(
                leg = VerificationLegId.WS_TICKET_HELLO,
                status = VerificationLegStatus.VERIFIED,
                latencyMs = ms3,
                summary = "hello · cursor #${hello.latestCursor}",
            )
            return@withContext snapshot().also(onProgress)
        } catch (t: Throwable) {
            return@withContext failLeg(VerificationLegId.WS_TICKET_HELLO, elapsedMs(t3), t)
        }
    }

    private suspend fun awaitWsHello(wsUrl: String): WsServerFrame.Hello {
        val helloDeferred = CompletableDeferred<WsServerFrame.Hello>()
        val request = Request.Builder().url(wsUrl).build()
        var activeSocket: WebSocket? = null

        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (helloDeferred.isCompleted) return
                try {
                    when (val frame = WsFrameParser.parse(text, json)) {
                        is WsServerFrame.Hello -> helloDeferred.complete(frame)
                        is WsServerFrame.Error -> helloDeferred.completeExceptionally(
                            GatewayProtocolException("WS error (${frame.code}): ${frame.message}"),
                        )
                        else -> helloDeferred.completeExceptionally(
                            GatewayProtocolException("Expected hello frame first, got $frame"),
                        )
                    }
                } catch (t: Throwable) {
                    helloDeferred.completeExceptionally(
                        GatewayProtocolException("Malformed WS frame: ${t.message}", t),
                    )
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                if (!helloDeferred.isCompleted) {
                    helloDeferred.completeExceptionally(
                        GatewayProtocolException("WS closed ($code) before hello: $reason"),
                    )
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!helloDeferred.isCompleted) {
                    val mapped = when {
                        t is GatewayException -> t
                        response != null -> GatewayHttpException(
                            status = response.code,
                            code = GatewayStatusCodes.defaultCodeForStatus(response.code),
                            message = response.message.ifBlank { "WebSocket upgrade failed (${response.code})" },
                        )
                        else -> GatewayNetworkException(t.message ?: "WebSocket connection failed", t)
                    }
                    helloDeferred.completeExceptionally(mapped)
                }
            }
        }

        activeSocket = okHttpClient.newWebSocket(request, listener)
        return try {
            withTimeout(wsHelloTimeoutMs) {
                helloDeferred.await()
            }
        } finally {
            activeSocket.close(1000, "preflight_complete")
        }
    }

    private fun elapsedMs(startNanos: Long): Long =
        ((clockNanos() - startNanos) / 1_000_000L).coerceAtLeast(0L)

    companion object {
        const val EXPECTED_PROTOCOL_VERSION = 1
    }
}
