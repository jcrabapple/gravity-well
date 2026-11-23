package us.snakepit.gravitywell.net

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HostVerifierTest {

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
    fun `three-leg host verifier succeeds when health info and ws hello pass`() = runTest {
        val client = NetworkModule.createOkHttpClient()
        val verifier = HostVerifier(client, wsHelloTimeoutMs = 3_000L)

        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"status":"ok","service":"gravity-well-gateway","gateway_version":"0.1.0","agy_version":"1.5.15","protocol_version":1,"auth_required":true,"uptime_seconds":10}""")
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "gateway_version": "0.1.0",
                      "agy_version": "1.5.15",
                      "acp_protocol_version": 1,
                      "hostname": "aurora",
                      "default_cwd": "/home/jason",
                      "capabilities": {
                        "load_session": true,
                        "resume_session": true,
                        "fork": false,
                        "gateway_title_override": true
                      }
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(201)
                .body("""{"ticket":"gwt_preflight_99","ttl_seconds":30,"expires_at":"2026-11-23T15:00:30Z"}""")
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                            webSocket.send(
                                """{"frame":"hello","gateway_version":"0.1.0","agy_version":"1.5.15","server_time":"2026-11-23T15:00:00Z","latest_cursor":42,"capabilities":{"fork":false,"gateway_title_override":true}}""",
                            )
                        }

                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            webSocket.close(1000, null)
                        }
                    },
                )
                .build(),
        )

        val report = verifier.verify(server.url("/"), "gw_secret_token")
        assertTrue(report.isAllVerified)
        assertEquals(VerificationLegStatus.VERIFIED, report.legs[VerificationLegId.REACHABILITY_TLS]?.status)
        assertEquals(VerificationLegStatus.VERIFIED, report.legs[VerificationLegId.AUTH_CAPABILITIES]?.status)
        assertEquals(VerificationLegStatus.VERIFIED, report.legs[VerificationLegId.WS_TICKET_HELLO]?.status)
        assertEquals("aurora", report.info?.hostname)
        assertEquals(42L, report.hello?.latestCursor)
        assertFalse(report.info?.capabilities?.fork ?: true)

        val r1 = server.takeRequest(2, TimeUnit.SECONDS)!!
        val r2 = server.takeRequest(2, TimeUnit.SECONDS)!!
        val r3 = server.takeRequest(2, TimeUnit.SECONDS)!!
        val r4 = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/api/health", r1.target)
        assertNull(r1.headers["Authorization"])
        assertEquals("/api/info", r2.target)
        assertEquals("Bearer gw_secret_token", r2.headers["Authorization"])
        assertEquals("/api/ws-ticket", r3.target)
        assertEquals("Bearer gw_secret_token", r3.headers["Authorization"])
        assertEquals("/api/ws?ticket=gwt_preflight_99", r4.target)
        assertFalse(r4.target.contains("gw_secret_token"))
    }

    @Test
    fun `leg 1 protocol mismatch halts verification before leg 2`() = runTest {
        val client = NetworkModule.createOkHttpClient()
        val verifier = HostVerifier(client)

        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"status":"ok","protocol_version":99}""")
                .build(),
        )

        val report = verifier.verify(server.url("/"), "gw_secret_token")
        assertFalse(report.isAllVerified)
        assertEquals(VerificationLegStatus.FAILED, report.legs[VerificationLegId.REACHABILITY_TLS]?.status)
        assertEquals("protocol_error", report.legs[VerificationLegId.REACHABILITY_TLS]?.errorCode)
        assertEquals(VerificationLegStatus.NOT_RUN, report.legs[VerificationLegId.AUTH_CAPABILITIES]?.status)
        assertEquals(VerificationLegStatus.NOT_RUN, report.legs[VerificationLegId.WS_TICKET_HELLO]?.status)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `leg 2 401 unauthorized halts before ws ticket leg`() = runTest {
        val client = NetworkModule.createOkHttpClient()
        val verifier = HostVerifier(client)

        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"status":"ok","agy_version":"1.5.15","protocol_version":1}""")
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(401)
                .body("""{"error":{"code":"unauthorized","message":"Bad bearer token.","status":401}}""")
                .build(),
        )

        val report = verifier.verify(server.url("/"), "wrong_token")
        assertFalse(report.isAllVerified)
        assertEquals(VerificationLegStatus.VERIFIED, report.legs[VerificationLegId.REACHABILITY_TLS]?.status)
        assertEquals(VerificationLegStatus.FAILED, report.legs[VerificationLegId.AUTH_CAPABILITIES]?.status)
        assertEquals(401, report.legs[VerificationLegId.AUTH_CAPABILITIES]?.errorStatus)
        assertEquals("unauthorized", report.legs[VerificationLegId.AUTH_CAPABILITIES]?.errorCode)
        assertEquals(VerificationLegStatus.NOT_RUN, report.legs[VerificationLegId.WS_TICKET_HELLO]?.status)
        assertEquals(2, server.requestCount)
    }
}
