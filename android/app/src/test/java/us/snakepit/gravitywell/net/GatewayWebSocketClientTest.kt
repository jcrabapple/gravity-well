package us.snakepit.gravitywell.net

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `rapid connect disconnect and superseding connect close stale sockets and clear activeWebSocket on close`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val firstUpgradeEntered = CountDownLatch(1)
        val releaseFirstUpgrade = CountDownLatch(1)
        val closedTickets = CopyOnWriteArrayList<String>()
        val latestServerSocket = AtomicReference<WebSocket?>(null)

        server.dispatcher = object : Dispatcher() {
            private var ticketSeq = 0

            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when (path) {
                    "/api/ws-ticket" -> {
                        val n = synchronized(this) { ++ticketSeq }
                        MockResponse.Builder()
                            .code(201)
                            .body("""{"ticket":"gwt_race_$n","ttl_seconds":30}""")
                            .build()
                    }
                    "/api/ws" -> {
                        val ticket = request.url.queryParameter("ticket").orEmpty()
                        MockResponse.Builder()
                            .webSocketUpgrade(
                                object : WebSocketListener() {
                                    override fun onOpen(webSocket: WebSocket, response: Response) {
                                        if (ticket == "gwt_race_1") {
                                            firstUpgradeEntered.countDown()
                                            releaseFirstUpgrade.await(2, TimeUnit.SECONDS)
                                        }
                                        latestServerSocket.set(webSocket)
                                        webSocket.send("""{"frame":"hello","gateway_version":"0.1.0","agy_version":"1.5.15","latest_cursor":10}""")
                                    }

                                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                                        closedTickets += ticket
                                        webSocket.close(1000, null)
                                    }

                                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                                        closedTickets += ticket
                                    }
                                },
                            )
                            .build()
                    }
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }

        val backoffGate = CompletableDeferred<Unit>()
        val enteredBackoff = CompletableDeferred<Unit>()
        val wsClient = GatewayWebSocketClient(
            okHttpClient = NetworkModule.createOkHttpClient(),
            baseUrl = server.url("/"),
            tokenProvider = { "gw_tok_race" },
            target = WsTarget.Global,
            scope = scope,
            backoffConfig = BackoffConfig(maxJitterRatio = 0.0),
            delayFn = {
                enteredBackoff.complete(Unit)
                backoffGate.await()
            },
        )

        // 1. Start gen 1, supersede immediately with gen 2 while gen 1 is opening
        wsClient.connect()
        assertTrue(firstUpgradeEntered.await(3, TimeUnit.SECONDS))
        wsClient.connect(sessionFilter = listOf("s_live"))
        releaseFirstUpgrade.countDown()

        withTimeout(3_000L) {
            wsClient.state.filterIsInstance<WsConnectionState.Live>().first()
        }
        // Stale gen 1 socket must be closed
        val deadline = System.currentTimeMillis() + 2_000L
        while (System.currentTimeMillis() < deadline && "gwt_race_1" !in closedTickets) {
            delay(20L)
        }
        assertTrue("Superseded generation socket must be closed", "gwt_race_1" in closedTickets)
        assertTrue("Live activeWebSocket should accept sendPing", wsClient.sendPing())
        assertTrue("Live activeWebSocket should accept updateSessionFilter", wsClient.updateSessionFilter(listOf("s_live", "s_other")))

        // 2. Server closes active gen 2 socket -> during reconnect backoff activeWebSocket MUST be cleared
        latestServerSocket.get()?.close(1001, "server_drop")
        withTimeout(3_000L) { enteredBackoff.await() }
        assertFalse("sendPing must return false once socket closes during backoff", wsClient.sendPing())
        assertFalse("updateSessionFilter must return false once socket closes during backoff", wsClient.updateSessionFilter(listOf("s_live")))

        // 3. Disconnect clears state and sendPing / updateSessionFilter remain false
        wsClient.disconnect()
        backoffGate.complete(Unit)
        assertFalse("sendPing must return false after disconnect", wsClient.sendPing())
        assertFalse("updateSessionFilter must return false after disconnect", wsClient.updateSessionFilter(listOf("s_live")))

        scope.cancel()
    }

    @Test
    fun `socketLock prevents superseded newWebSocket from overwriting activeWebSocket and clears on failure`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return if (request.url.encodedPath == "/api/ws-ticket") {
                    MockResponse.Builder().code(201).body("""{"ticket":"gwt_stub","ttl_seconds":30}""").build()
                } else {
                    MockResponse.Builder().code(404).build()
                }
            }
        }

        class StubWebSocket(private val req: okhttp3.Request) : WebSocket {
            val closedReasons = CopyOnWriteArrayList<String>()
            val sentMessages = CopyOnWriteArrayList<String>()
            override fun request() = req
            override fun queueSize(): Long = 0L
            // Always returns true if invoked — only activeWebSocket == null makes sendPing/updateSessionFilter return false!
            override fun send(text: String): Boolean {
                sentMessages += text
                return true
            }
            override fun send(bytes: okio.ByteString): Boolean = true
            override fun close(code: Int, reason: String?): Boolean {
                closedReasons += (reason ?: "")
                return true
            }
            override fun cancel() {}
        }

        val inNewWebSocket = CountDownLatch(1)
        val releaseNewWebSocket = CountDownLatch(1)
        val createdSockets = CopyOnWriteArrayList<Pair<StubWebSocket, WebSocketListener>>()
        val backoffBlock = CompletableDeferred<Unit>()
        val enteredBackoff = CompletableDeferred<Unit>()

        val factory = WebSocket.Factory { request, listener ->
            val stub = StubWebSocket(request)
            val idx = synchronized(createdSockets) {
                val cur = createdSockets.size
                createdSockets += (stub to listener)
                cur
            }
            if (idx == 0) {
                inNewWebSocket.countDown()
                releaseNewWebSocket.await(2, TimeUnit.SECONDS)
            }
            stub
        }

        val wsClient = GatewayWebSocketClient(
            okHttpClient = NetworkModule.createOkHttpClient(),
            baseUrl = server.url("/"),
            tokenProvider = { "gw_tok_lock" },
            target = WsTarget.Global,
            scope = scope,
            backoffConfig = BackoffConfig(maxJitterRatio = 0.0),
            delayFn = {
                if (!enteredBackoff.isCompleted) enteredBackoff.complete(Unit)
                backoffBlock.await()
            },
            webSocketFactory = factory,
        )

        // 1. Start gen 1, wait until inside newWebSocket(), supersede with gen 2 and let gen 2 become active FIRST
        wsClient.connect()
        assertTrue(inNewWebSocket.await(3, TimeUnit.SECONDS))
        wsClient.connect()
        val deadlineGen2 = System.currentTimeMillis() + 2_000L
        while (System.currentTimeMillis() < deadlineGen2 && createdSockets.size < 2) {
            delay(10L)
        }
        val deadlineGen2Active = System.currentTimeMillis() + 2_000L
        while (System.currentTimeMillis() < deadlineGen2Active && !wsClient.sendPing()) {
            delay(10L)
        }
        assertTrue("Gen 2 socket must be active before gen 1 newWebSocket unblocks", wsClient.sendPing())

        // Unblock gen 1's newWebSocket AFTER gen 2 is active -> gen 1 MUST NOT clobber gen 2's activeWebSocket!
        releaseNewWebSocket.countDown()
        val firstStub = createdSockets[0].first
        val secondStub = createdSockets[1].first
        val deadlineSuperseded = System.currentTimeMillis() + 2_000L
        while (System.currentTimeMillis() < deadlineSuperseded && firstStub.closedReasons.isEmpty()) {
            delay(10L)
        }
        assertEquals(listOf("superseded"), firstStub.closedReasons)
        assertTrue("Gen 2 activeWebSocket must survive late gen 1 return", wsClient.sendPing())
        assertTrue("Gen 2 activeWebSocket must accept updateSessionFilter", wsClient.updateSessionFilter(listOf("s1")))
        assertTrue("Stale gen 1 socket must receive zero outbound frames", firstStub.sentMessages.isEmpty())
        assertTrue("Active gen 2 socket must receive outbound frames", secondStub.sentMessages.isNotEmpty())

        // 2. Trigger onFailure on active gen 2 socket -> must null activeWebSocket during backoff
        val secondListener = createdSockets[1].second
        secondListener.onFailure(secondStub, java.io.IOException("simulated drop"), null)
        withTimeout(3_000L) { enteredBackoff.await() }
        // Because StubWebSocket.send() unconditionally returns true, these ONLY return false if activeWebSocket was nulled!
        assertFalse("onFailure must clear activeWebSocket during backoff", wsClient.sendPing())
        assertFalse("onFailure must clear activeWebSocket for updateSessionFilter", wsClient.updateSessionFilter(listOf("s1")))

        wsClient.disconnect()
        assertFalse("sendPing must return false after disconnect", wsClient.sendPing())
        assertFalse("updateSessionFilter must return false after disconnect", wsClient.updateSessionFilter(listOf("s1")))
        backoffBlock.complete(Unit)
        scope.cancel()
    }
}
