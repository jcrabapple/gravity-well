package us.snakepit.gravitywell.net

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class NetworkModuleAndInterceptorTest {

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
    fun `auth interceptor omits bearer on health and injects bearer on authenticated routes`() = runTest {
        val client = NetworkModule.createOkHttpClient(tokenProvider = { "gw_tok_shared_123" })
        val api = NetworkModule.createGatewayApi(server.url("/"), client)

        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"status":"ok","service":"gravity-well-gateway","protocol_version":1}""")
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"gateway_version":"0.1.0","agy_version":"1.5.15","acp_protocol_version":1,"hostname":"aurora","default_cwd":"/home/jason"}""")
                .build(),
        )

        val health = api.getHealth()
        assertEquals("ok", health.status)
        val info = api.getInfo()
        assertEquals("aurora", info.hostname)

        val reqHealth = server.takeRequest(2, TimeUnit.SECONDS)!!
        val reqInfo = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertNull("Health route must not receive bearer token", reqHealth.headers["Authorization"])
        assertEquals("Bearer gw_tok_shared_123", reqInfo.headers["Authorization"])
    }

    @Test
    fun `error interceptor parses canonical gateway error json and fallback codes`() = runTest {
        val client = NetworkModule.createOkHttpClient(tokenProvider = { "bad_tok" })
        val api = NetworkModule.createGatewayApi(server.url("/"), client)

        server.enqueue(
            MockResponse.Builder()
                .code(401)
                .body(
                    """
                    {
                      "error": {
                        "code": "unauthorized",
                        "message": "Missing or invalid bearer token.",
                        "status": 401,
                        "details": {"reason": "token_mismatch"}
                      }
                    }
                    """.trimIndent(),
                )
                .build(),
        )

        try {
            api.getInfo()
            fail("Expected GatewayHttpException")
        } catch (ex: GatewayHttpException) {
            assertEquals(401, ex.status)
            assertEquals("unauthorized", ex.code)
            assertEquals("Missing or invalid bearer token.", ex.message)
            assertTrue(ex.details.containsKey("reason"))
        }

        server.enqueue(
            MockResponse.Builder()
                .code(502)
                .body("upstream gateway crashed")
                .build(),
        )
        try {
            api.listSessions()
            fail("Expected GatewayHttpException on non-JSON 502")
        } catch (ex: GatewayHttpException) {
            assertEquals(502, ex.status)
            assertEquals("acp_upstream_error", ex.code)
        }
    }

    @Test
    fun `url security policy enforces HTTPS in release and builds ticket-only WS URLs`() {
        val releaseHttp = UrlSecurityPolicy.validateBaseUrl("http://192.168.1.50:9340", isDebug = false)
        assertTrue(releaseHttp.isFailure)

        val releaseHttps = UrlSecurityPolicy.validateBaseUrl("https://aurora.tailnet.ts.net", isDebug = false)
        assertTrue(releaseHttps.isSuccess)
        val verifiedHttps = releaseHttps.getOrThrow()
        assertFalse(verifiedHttps.isCleartext)
        assertEquals("https://aurora.tailnet.ts.net/", verifiedHttps.httpUrl.toString())

        val debugHttp = UrlSecurityPolicy.validateBaseUrl("http://10.0.2.2:9340", isDebug = true)
        assertTrue(debugHttp.isSuccess)
        assertTrue(debugHttp.getOrThrow().isCleartext)

        val wsUrl = UrlSecurityPolicy.buildWebSocketUrl(
            baseUrl = "https://aurora.tailnet.ts.net/".toHttpUrl(),
            endpointPath = "api/ws",
            ticket = "gwt_single_use_abc",
            afterCursor = 42L,
        )
        assertEquals("wss://aurora.tailnet.ts.net/api/ws?ticket=gwt_single_use_abc&after=42", wsUrl)
        assertFalse(wsUrl.contains("token="))
        assertFalse(wsUrl.contains("bearer="))
    }

    @Test
    fun `error interceptor maps IO failures to GatewayNetworkException and SSL failures to GatewayTlsException`() = runTest {
        val sslFailClient = NetworkModule.createOkHttpClient(
            configure = { builder ->
                builder.addInterceptor { throw javax.net.ssl.SSLHandshakeException("untrusted cert") }
            },
        )
        val sslApi = NetworkModule.createGatewayApi(server.url("/"), sslFailClient)
        try {
            sslApi.getHealth()
            fail("Expected GatewayTlsException")
        } catch (ex: GatewayTlsException) {
            assertTrue(ex.message.contains("untrusted cert"))
        }

        val ioFailClient = NetworkModule.createOkHttpClient(
            configure = { builder ->
                builder.addInterceptor { throw java.net.ConnectException("Connection refused") }
            },
        )
        val ioApi = NetworkModule.createGatewayApi(server.url("/"), ioFailClient)
        try {
            ioApi.getHealth()
            fail("Expected GatewayNetworkException")
        } catch (ex: GatewayNetworkException) {
            assertTrue(ex.message.contains("Connection refused"))
        }
    }
}
