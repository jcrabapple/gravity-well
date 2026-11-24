package us.snakepit.gravitywell.ui.shell

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import us.snakepit.gravitywell.MainActivity
import us.snakepit.gravitywell.cache.CachedHost
import us.snakepit.gravitywell.cache.GravityWellDbHelper
import us.snakepit.gravitywell.cache.OfflineCacheStore
import us.snakepit.gravitywell.net.BackoffConfig
import us.snakepit.gravitywell.net.HostVerificationReport
import us.snakepit.gravitywell.net.WsConnectionState
import us.snakepit.gravitywell.security.AndroidKeyStoreCipherEngine
import us.snakepit.gravitywell.security.EncryptedPayload
import us.snakepit.gravitywell.security.TokenVault
import us.snakepit.gravitywell.security.VaultCipherEngine

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AdaptiveShellAndComplianceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class TestAesGcmEngine : VaultCipherEngine {
        private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun encrypt(plaintext: ByteArray): EncryptedPayload {
            val cipher = Cipher.getInstance(AndroidKeyStoreCipherEngine.TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val ct = cipher.doFinal(plaintext)
            return EncryptedPayload(iv = cipher.iv, ciphertext = ct)
        }
        override fun decrypt(payload: EncryptedPayload): ByteArray {
            val cipher = Cipher.getInstance(AndroidKeyStoreCipherEngine.TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, payload.iv))
            return cipher.doFinal(payload.ciphertext)
        }
    }

    @Test
    fun `compact layout under 600dp shows bottom bar except when inside attached session console`() {
        val sessionsCompact = AdaptiveShellBreakpoints.resolveLayout(
            widthDp = 411,
            destination = TopLevelDestination.SESSIONS,
            attachedSessionId = "sess_1",
        )
        assertFalse(sessionsCompact.isExpanded)
        assertTrue(sessionsCompact.showBottomNavigationBar)
        assertFalse(sessionsCompact.showNavigationRail)
        assertFalse(sessionsCompact.showTwoPaneSessionsConsole)
        assertFalse(sessionsCompact.showCompactConsoleBackButton)

        val unattachedConsoleCompact = AdaptiveShellBreakpoints.resolveLayout(
            widthDp = 599,
            destination = TopLevelDestination.CONSOLE,
            attachedSessionId = null,
        )
        assertTrue(unattachedConsoleCompact.showBottomNavigationBar)
        assertFalse(unattachedConsoleCompact.showCompactConsoleBackButton)

        val attachedConsoleCompact = AdaptiveShellBreakpoints.resolveLayout(
            widthDp = 411,
            destination = TopLevelDestination.CONSOLE,
            attachedSessionId = "sess_1",
        )
        assertFalse("Bottom nav must hide inside compact attached Console", attachedConsoleCompact.showBottomNavigationBar)
        assertTrue(attachedConsoleCompact.showCompactConsoleBackButton)
    }

    @Test
    fun `expanded layout at or above 600dp shows rail and two-pane split for sessions and console`() {
        val expandedSessions = AdaptiveShellBreakpoints.resolveLayout(
            widthDp = 600,
            destination = TopLevelDestination.SESSIONS,
            attachedSessionId = "sess_1",
        )
        assertTrue(expandedSessions.isExpanded)
        assertTrue(expandedSessions.showNavigationRail)
        assertFalse(expandedSessions.showBottomNavigationBar)
        assertTrue(expandedSessions.showTwoPaneSessionsConsole)
        assertFalse(expandedSessions.showCompactConsoleBackButton)

        val expandedDeck = AdaptiveShellBreakpoints.resolveLayout(
            widthDp = 840,
            destination = TopLevelDestination.DECK,
            attachedSessionId = "sess_1",
        )
        assertTrue(expandedDeck.showNavigationRail)
        assertFalse(expandedDeck.showTwoPaneSessionsConsole)
    }

    @Test
    fun `main activity deep-link extractor handles cold-start and warm-intent extras`() {
        assertNull(MainActivity.extractDeepLink(null))
        assertNull(MainActivity.extractDeepLink(Intent()))

        val valid = Intent().apply {
            putExtra(MainActivity.EXTRA_HOST_ID, "host_aurora")
            putExtra(MainActivity.EXTRA_SESSION_ID, "5b1f0b18-1111-2222-3333-444455556666")
        }
        val parsed = MainActivity.extractDeepLink(valid)
        assertEquals(
            PendingDeepLink(hostId = "host_aurora", sessionId = "5b1f0b18-1111-2222-3333-444455556666"),
            parsed,
        )
    }

    @Test
    fun `primary navigation exposes 3 chat-first tabs`() {
        assertEquals(
            listOf(
                TopLevelDestination.SESSIONS,
                TopLevelDestination.OBSERVATORY,
                TopLevelDestination.DECK,
            ),
            TopLevelDestination.primaryTabs,
        )
        assertEquals("Chats", TopLevelDestination.SESSIONS.label)
        assertEquals("Status", TopLevelDestination.OBSERVATORY.label)
        assertEquals("Settings", TopLevelDestination.DECK.label)
    }

    @Test
    fun `design system source audit enforces zero literal hex outside Color_kt and no dynamic wallpaper colors`() {
        val srcRoot = locateMainJavaRoot()
        val ktFiles = srcRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("Expected Kotlin source files under $srcRoot", ktFiles.size >= 15)

        val literalColorRegex = Regex("""\bColor\s*\(\s*0x""")
        val dynamicSchemeRegex = Regex("""\bdynamic(?:Dark|Light)ColorScheme\b""")

        for (file in ktFiles) {
            val rel = file.relativeTo(srcRoot).invariantSeparatorsPath
            val text = file.readText()

            assertFalse(
                "Forbidden dynamic wallpaper color call in $rel",
                dynamicSchemeRegex.containsMatchIn(text),
            )

            if (rel != "theme/Color.kt") {
                assertFalse(
                    "Literal Color(0x...) forbidden outside theme/Color.kt, found in $rel",
                    literalColorRegex.containsMatchIn(text),
                )
            }
        }
    }

    @Test
    fun `re-verifying active host rebinds runtime from AuthFailed serverA to healthy serverB`() = runBlocking {
        val serverA = MockWebServer()
        val serverB = MockWebServer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbHelper = GravityWellDbHelper(context, File(tempFolder.root, "shell_rebind.db").absolutePath)
        val cacheStore = OfflineCacheStore(dbHelper)
        val tokenVault = TokenVault(File(tempFolder.root, "rebind_vault.json"), TestAesGcmEngine())

        try {
            serverA.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse.Builder()
                        .code(401)
                        .body("""{"error":{"code":"unauthorized","message":"Revoked token on A","status":401}}""")
                        .build()
            }
            serverB.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val auth = request.headers["Authorization"].orEmpty()
                    val path = request.url.encodedPath
                    if (path != "/api/health" && path != "/api/ws" && auth != "Bearer gw_valid_b") {
                        return MockResponse.Builder()
                            .code(401)
                            .body("""{"error":{"code":"unauthorized","message":"Bad token on B","status":401}}""")
                            .build()
                    }
                    return when (path) {
                        "/api/health" -> MockResponse.Builder()
                            .code(200)
                            .body("""{"status":"ok","agy_version":"1.5.15","protocol_version":1}""")
                            .build()
                        "/api/info" -> MockResponse.Builder()
                            .code(200)
                            .body("""{"gateway_version":"0.1.0","agy_version":"1.5.15","hostname":"aurora-b","default_cwd":"/home/jason","capabilities":{"resume_session":true,"fork":false}}""")
                            .build()
                        "/api/ws-ticket" -> MockResponse.Builder()
                            .code(201)
                            .body("""{"ticket":"gwt_b_ok","ttl_seconds":30}""")
                            .build()
                        "/api/ws" -> {
                            val afterParam = request.url.queryParameter("after")?.toLongOrNull()
                            MockResponse.Builder()
                                .webSocketUpgrade(
                                    object : WebSocketListener() {
                                        override fun onOpen(webSocket: WebSocket, response: Response) {
                                            webSocket.send("""{"frame":"hello","gateway_version":"0.1.0","agy_version":"1.5.15","latest_cursor":12,"capabilities":{"fork":false}}""")
                                            if (afterParam != null) {
                                                webSocket.send("""{"frame":"replay_complete","after":$afterParam,"replayed_count":0,"latest_cursor":12}""")
                                            }
                                        }
                                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                                            webSocket.close(1000, null)
                                        }
                                    },
                                )
                                .build()
                        }
                        "/api/sessions" -> MockResponse.Builder()
                            .code(200)
                            .body("""{"items":[{"session_id":"sess_on_b","title":"Bound to Server B","preview":"","cwd":"/home/jason","status":"idle","is_live":true,"step_count":2,"updated_at":"2026-11-23T16:00:00Z"}],"total":1}""")
                            .build()
                        "/api/observatory/status" -> MockResponse.Builder()
                            .code(200)
                            .body("""{"external_sessions":[]}""")
                            .build()
                        "/api/permissions/rules" -> MockResponse.Builder()
                            .code(200)
                            .body("""{"items":[]}""")
                            .build()
                        else -> MockResponse.Builder().code(404).build()
                    }
                }
            }
            serverA.start()
            serverB.start()

            val initialHost = CachedHost(
                hostId = "h1",
                label = "aurora",
                baseUrl = serverA.url("/").toString(),
                defaultCwd = "/home/jason",
                isActive = true,
                createdAt = "2026-11-23T14:00:00Z",
                lastVerifiedAt = "2026-11-23T14:00:00Z",
            )
            cacheStore.upsertHost(initialHost)
            tokenVault.putToken("h1", "gw_revoked_a")

            val shell = ShellController(
                cacheStore = cacheStore,
                tokenVault = tokenVault,
                scope = scope,
                isDebugBuild = true,
                backoffConfig = BackoffConfig(maxJitterRatio = 0.0),
            )

            awaitShellCondition { shell.state.value.wsState is WsConnectionState.AuthFailed }

            // User opens Re-Verify on active host h1 and points it to serverB with rotated token
            shell.openReverifyHostModal(initialHost)
            val onboarding = shell.onboardingController.value
            onboarding.updateBaseUrl(serverB.url("/").toString())
            onboarding.updateBearerToken("gw_valid_b")

            val preflightDone = CompletableDeferred<HostVerificationReport?>()
            onboarding.runPreflightCheck { preflightDone.complete(it) }
            val report = withTimeout(5_000L) { preflightDone.await() }
            assertNotNull(report)
            assertTrue(report!!.isAllVerified)

            val saved = onboarding.saveVerifiedHost(onSaved = shell::onHostOnboardingSaved)
            assertNotNull(saved)
            assertEquals("h1", saved!!.hostId)

            // Active host h1 MUST rebind its WS & Retrofit runtime to serverB and leave AuthFailed
            awaitShellCondition { shell.state.value.wsState is WsConnectionState.Live }
            awaitShellCondition {
                shell.sessionBrowserController.value?.state?.value?.sessions?.any { it.sessionId == "sess_on_b" } == true
            }
            assertEquals(serverB.url("/").toString(), shell.state.value.activeHost?.baseUrl)

            shell.shutdown()
        } finally {
            scope.cancel()
            dbHelper.close()
            serverA.close()
            serverB.close()
        }
    }

    @Test
    fun `attached console ws status and title updates propagate immediately to two-pane session browser without http refresh`() = runBlocking {
        val server = MockWebServer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbHelper = GravityWellDbHelper(context, File(tempFolder.root, "shell_twopane.db").absolutePath)
        val cacheStore = OfflineCacheStore(dbHelper)
        val tokenVault = TokenVault(File(tempFolder.root, "twopane_vault.json"), TestAesGcmEngine())
        val listSessionsCalls = AtomicInteger(0)
        val serverWsRef = AtomicReference<WebSocket?>(null)
        val wsConnected = CompletableDeferred<WebSocket>()

        try {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.url.encodedPath
                    return when (path) {
                        "/api/ws-ticket" -> MockResponse.Builder()
                            .code(201)
                            .body("""{"ticket":"gwt_twopane","ttl_seconds":30}""")
                            .build()
                        "/api/ws" -> MockResponse.Builder()
                            .webSocketUpgrade(
                                object : WebSocketListener() {
                                    override fun onOpen(webSocket: WebSocket, response: Response) {
                                        serverWsRef.set(webSocket)
                                        webSocket.send("""{"frame":"hello","gateway_version":"0.1.0","agy_version":"1.5.15","latest_cursor":5,"capabilities":{"fork":false}}""")
                                        wsConnected.complete(webSocket)
                                    }
                                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                                        webSocket.close(1000, null)
                                    }
                                },
                            )
                            .build()
                        "/api/sessions" -> {
                            listSessionsCalls.incrementAndGet()
                            MockResponse.Builder()
                                .code(200)
                                .body("""{"items":[{"session_id":"sess_1","title":"Initial Title","preview":"prev","cwd":"/home/jason/projects/gravity-well","status":"idle","is_live":true,"step_count":1,"updated_at":"2026-11-23T15:00:00Z"}],"total":1}""")
                                .build()
                        }
                        "/api/sessions/sess_1" -> MockResponse.Builder()
                            .code(200)
                            .body("""{"session_id":"sess_1","title":"Initial Title","title_source":"acp","cwd":"/home/jason/projects/gravity-well","status":"idle","is_live":true,"cursor":5,"seq":1,"updated_at":"2026-11-23T15:00:00Z"}""")
                            .build()
                        "/api/sessions/sess_1/events" -> MockResponse.Builder()
                            .code(200)
                            .body("""{"session_id":"sess_1","after":0,"min_cursor_retained":1,"latest_cursor":5,"truncated":false,"events":[]}""")
                            .build()
                        "/api/sessions/sess_1/queue" -> MockResponse.Builder()
                            .code(200)
                            .body("""{"items":[],"paused":false}""")
                            .build()
                        "/api/observatory/status" -> MockResponse.Builder()
                            .code(200)
                            .body("""{"external_sessions":[]}""")
                            .build()
                        "/api/permissions/rules" -> MockResponse.Builder()
                            .code(200)
                            .body("""{"items":[]}""")
                            .build()
                        else -> MockResponse.Builder().code(404).build()
                    }
                }
            }
            server.start()

            val host = CachedHost(
                hostId = "h1",
                label = "aurora",
                baseUrl = server.url("/").toString(),
                defaultCwd = "/home/jason/projects/gravity-well",
                isActive = true,
                createdAt = "2026-11-23T14:00:00Z",
                lastVerifiedAt = "2026-11-23T14:00:00Z",
            )
            cacheStore.upsertHost(host)
            tokenVault.putToken("h1", "gw_tok_twopane")

            val shell = ShellController(
                cacheStore = cacheStore,
                tokenVault = tokenVault,
                scope = scope,
                isDebugBuild = true,
            )

            val serverWs = withTimeout(5_000L) { wsConnected.await() }
            awaitShellCondition { shell.state.value.wsState is WsConnectionState.Live }
            awaitShellCondition {
                shell.sessionBrowserController.value?.state?.value?.sessions?.any { it.sessionId == "sess_1" } == true
            }

            // Attach sess_1 in Console (two-pane >=600dp mode shows both SessionBrowser and SessionConsole)
            shell.openSessionInConsole("sess_1")
            awaitShellCondition {
                shell.sessionConsoleController.value?.state?.value?.session?.title == "Initial Title"
            }
            val refreshesBeforeWs = listSessionsCalls.get()

            // Emit WS session_status (awaiting_approval) and session_info_update for the attached session
            serverWs.send(
                """{"frame":"event","cursor":6,"seq":2,"session_id":"sess_1","type":"session_status","ts":"2026-11-23T15:01:00Z","payload":{"status":"awaiting_approval"}}""",
            )
            serverWs.send(
                """{"frame":"event","cursor":7,"seq":3,"session_id":"sess_1","type":"session_info_update","ts":"2026-11-23T15:01:01Z","payload":{"title":"Live Renamed Session","title_source":"user"}}""",
            )

            awaitShellCondition {
                val browserItem = shell.sessionBrowserController.value?.state?.value?.sessions
                    ?.firstOrNull { it.sessionId == "sess_1" }
                browserItem?.status == "awaiting_approval" && browserItem.title == "Live Renamed Session"
            }

            val updatedBrowserItem = shell.sessionBrowserController.value!!.state.value.sessions
                .single { it.sessionId == "sess_1" }
            assertEquals("awaiting_approval", updatedBrowserItem.status)
            assertTrue(updatedBrowserItem.hasPendingGate)
            assertEquals("Live Renamed Session", updatedBrowserItem.title)
            assertEquals("user", updatedBrowserItem.titleSource)
            assertEquals("Must update without HTTP listSessions refresh", refreshesBeforeWs, listSessionsCalls.get())

            shell.shutdown()
        } finally {
            scope.cancel()
            dbHelper.close()
            server.close()
        }
    }

    private suspend fun awaitShellCondition(timeoutMs: Long = 3_000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            delay(20L)
        }
        assertTrue("Condition timed out after ${timeoutMs}ms", predicate())
    }

    private fun locateMainJavaRoot(): File {
        val candidates = listOf(
            File("src/main/java/us/snakepit/gravitywell"),
            File("app/src/main/java/us/snakepit/gravitywell"),
            File("android/app/src/main/java/us/snakepit/gravitywell"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Could not locate main Kotlin source tree from ${File(".").absolutePath}")
    }
}
