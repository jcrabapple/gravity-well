package us.snakepit.gravitywell.net

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GatewayWebSocketClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `backoff schedule follows 1s 2s 4s 8s 16s 30s cap`() {
        val deterministic = BackoffConfig(maxJitterRatio = 0.0)
        val observed = (0..7).map { deterministic.delayForAttemptMs(it) }
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L),
            observed,
        )

        val jittered = BackoffConfig(maxJitterRatio = 0.2, randomFraction = { 0.5 })
        assertEquals(1_100L, jittered.delayForAttemptMs(0))
        assertEquals(33_000L, jittered.delayForAttemptMs(5))
    }

    @Test
    fun `ws client reconnects with fresh ticket and after cursor replay`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val delays = CopyOnWriteArrayList<Long>()
        val client = NetworkModule.createOkHttpClient()
        val wsClient = GatewayWebSocketClient(
            okHttpClient = client,
            baseUrl = server.url("/"),
            tokenProvider = { "gw_tok_ws_1" },
            target = WsTarget.Global,
            scope = scope,
            backoffConfig = BackoffConfig(maxJitterRatio = 0.0),
            delayFn = { ms -> delays += ms },
        )

        val firstReplayComplete = CompletableDeferred<WsServerFrame.ReplayComplete>()
        val collectorJob = launch {
            wsClient.frames.collect { frame ->
                if (frame is WsServerFrame.ReplayComplete && !firstReplayComplete.isCompleted) {
                    firstReplayComplete.complete(frame)
                }
            }
        }

        // First connection: ticket 1 -> WS /api/ws?ticket=t1 -> hello + event(cursor=7) -> close
        server.enqueue(MockResponse.Builder().code(201).body("""{"ticket":"gwt_t1","ttl_seconds":30}""").build())
        server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send("""{"frame":"hello","gateway_version":"0.1.0","agy_version":"1.5.15","latest_cursor":7}""")
                            webSocket.send("""{"frame":"event","cursor":7,"seq":1,"session_id":"s1","type":"agent_message_chunk","ts":"2026-11-23T15:00:00Z","payload":{"text":"hi"}}""")
                            webSocket.close(1001, "transient_drop")
                        }
                    },
                )
                .build(),
        )

        // Second connection (automatic reconnect): ticket 2 -> WS /api/ws?ticket=t2&after=7
        server.enqueue(MockResponse.Builder().code(201).body("""{"ticket":"gwt_t2","ttl_seconds":30}""").build())
        server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send("""{"frame":"hello","gateway_version":"0.1.0","agy_version":"1.5.15","latest_cursor":9}""")
                            webSocket.send("""{"frame":"event","cursor":8,"seq":2,"session_id":"s1","type":"agent_message_chunk","ts":"2026-11-23T15:00:01Z","payload":{"text":"!"}}""")
                            webSocket.send("""{"frame":"replay_complete","after":7,"replayed_count":1,"latest_cursor":9}""")
                        }

                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            webSocket.close(1000, null)
                        }
                    },
                )
                .build(),
        )

        wsClient.connect()
        val replayFrame = withTimeout(5_000L) { firstReplayComplete.await() }
        assertEquals(7L, replayFrame.after)
        assertEquals(9L, wsClient.latestCursor)
        assertTrue(delays.contains(1_000L))

        val r1Ticket = server.takeRequest(2, TimeUnit.SECONDS)!!
        val r1Ws = server.takeRequest(2, TimeUnit.SECONDS)!!
        val r2Ticket = server.takeRequest(2, TimeUnit.SECONDS)!!
        val r2Ws = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/api/ws-ticket", r1Ticket.target)
        assertEquals("/api/ws?ticket=gwt_t1", r1Ws.target)
        assertEquals("/api/ws-ticket", r2Ticket.target)
        assertEquals("/api/ws?ticket=gwt_t2&after=7", r2Ws.target)

        wsClient.disconnect()
        collectorJob.cancel()
        scope.cancel()
    }

    @Test
    fun `stale socket callbacks from superseded generation are ignored`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val wsClient = GatewayWebSocketClient(
            okHttpClient = NetworkModule.createOkHttpClient(),
            baseUrl = server.url("/"),
            tokenProvider = { "gw_tok_ws_gen" },
            scope = scope,
        )

        val emitted = CopyOnWriteArrayList<WsServerFrame>()
        val collectJob = launch {
            wsClient.frames.collect { emitted += it }
        }

        // Capture current generation, create its listener, then disconnect to supersede it
        val staleGen = wsClient.currentGeneration
        val genListener = wsClient.createListenerForGeneration(myGen = staleGen)
        wsClient.disconnect() // increments generation beyond staleGen
        assertTrue(wsClient.currentGeneration > staleGen)

        // Simulate late callback on superseded generation socket
        val dummySocket = object : WebSocket {
            override fun request() = okhttp3.Request.Builder().url("https://example.com").build()
            override fun queueSize(): Long = 0L
            override fun send(text: String): Boolean = false
            override fun send(bytes: okio.ByteString): Boolean = false
            override fun close(code: Int, reason: String?): Boolean = false
            override fun cancel() {}
        }
        genListener.onMessage(
            dummySocket,
            """{"frame":"event","cursor":999,"seq":99,"session_id":"stale","type":"agent_message_chunk","ts":"2026-11-23T15:00:00Z","payload":{"text":"stale"}}""",
        )

        assertEquals(0, emitted.size)
        assertEquals(0L, wsClient.latestCursor)
        collectJob.cancel()
        scope.cancel()
    }

    @Test
    fun `unauthorized ticket mint transitions to AuthFailed without spin loop`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val wsClient = GatewayWebSocketClient(
            okHttpClient = NetworkModule.createOkHttpClient(),
            baseUrl = server.url("/"),
            tokenProvider = { "revoked_tok" },
            scope = scope,
        )

        server.enqueue(
            MockResponse.Builder()
                .code(401)
                .body("""{"error":{"code":"unauthorized","message":"Invalid token.","status":401}}""")
                .build(),
        )

        wsClient.connect()
        val authFailed = withTimeout(3_000L) {
            wsClient.state.filterIsInstance<WsConnectionState.AuthFailed>().first()
        }
        assertEquals(401, authFailed.error.status)
        assertEquals("unauthorized", authFailed.error.code)
        assertEquals(1, server.requestCount)

        wsClient.disconnect()
        scope.cancel()
    }

    @Test
    fun `single session ws emits resync_required and advances latestCursor`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val wsClient = GatewayWebSocketClient(
            okHttpClient = NetworkModule.createOkHttpClient(),
            baseUrl = server.url("/"),
            tokenProvider = { "gw_tok_ws_2" },
            target = WsTarget.SingleSession("sess-42"),
            scope = scope,
        )

        val resyncDeferred = CompletableDeferred<WsServerFrame.ResyncRequired>()
        val collectJob = launch {
            wsClient.frames.collect { frame ->
                if (frame is WsServerFrame.ResyncRequired && !resyncDeferred.isCompleted) {
                    resyncDeferred.complete(frame)
                }
            }
        }

        server.enqueue(MockResponse.Builder().code(201).body("""{"ticket":"gwt_sess42","ttl_seconds":30}""").build())
        server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send("""{"frame":"hello","gateway_version":"0.1.0","agy_version":"1.5.15","latest_cursor":250}""")
                            webSocket.send("""{"frame":"resync_required","after":2,"min_cursor_retained":100,"latest_cursor":250}""")
                        }

                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            webSocket.close(1000, null)
                        }
                    },
                )
                .build(),
        )

        wsClient.connect(afterCursor = 2L)
        val resync = withTimeout(3_000L) { resyncDeferred.await() }
        assertEquals(2L, resync.after)
        assertEquals(100L, resync.minCursorRetained)
        assertEquals(250L, resync.latestCursor)
        assertEquals(250L, wsClient.latestCursor)

        server.takeRequest(2, TimeUnit.SECONDS)!! // ticket
        val wsReq = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/api/sessions/sess-42/ws?ticket=gwt_sess42&after=2", wsReq.target)

        wsClient.disconnect()
        collectJob.cancel()
        scope.cancel()
    }
}
