package us.snakepit.gravitywell.ui.sessions

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import us.snakepit.gravitywell.cache.CachedHost
import us.snakepit.gravitywell.cache.GravityWellDbHelper
import us.snakepit.gravitywell.cache.OfflineCacheStore
import us.snakepit.gravitywell.net.NetworkModule
import us.snakepit.gravitywell.net.WsServerFrame

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SessionBrowserControllerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var dbHelper: GravityWellDbHelper
    private lateinit var cacheStore: OfflineCacheStore
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        server = MockWebServer()
        val context = ApplicationProvider.getApplicationContext<Context>()
        dbHelper = GravityWellDbHelper(context, File(tempFolder.root, "sessions_test.db").absolutePath)
        cacheStore = OfflineCacheStore(dbHelper)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        scope.cancel()
        dbHelper.close()
        server.close()
    }

    @Test
    fun `browser queries live and archived fts5, tracks flock external radar, resumes and handles unsupported fork`() = runBlocking {
        val observedTargets = java.util.concurrent.CopyOnWriteArrayList<String>()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                observedTargets += request.target
                val path = request.url.encodedPath
                return when {
                    path == "/api/sessions" -> {
                        val q = request.url.queryParameter("q")
                        val status = request.url.queryParameter("status")
                        if (q == "auth") {
                            MockResponse.Builder().code(200).body(
                                """{"items":[{"session_id":"arch_auth","title":"Auth Audit","preview":"hmac.compare_digest","cwd":"/home/jason/projects/gravity-well","is_live":false,"status":"archived","step_count":9,"updated_at":"2026-11-23T14:00:00Z"}],"total":1,"limit":50,"offset":0}""",
                            ).build()
                        } else if (status == "live") {
                            MockResponse.Builder().code(200).body(
                                """{"items":[{"session_id":"live_1","title":"Live Rig","preview":"Streaming","cwd":"/home/jason/projects/gravity-well","is_live":true,"status":"running","step_count":4,"model_id":"gemini-4-neon","thought_level":"high","mode_id":"default","updated_at":"2026-11-23T15:00:00Z"}],"total":1,"limit":50,"offset":0}""",
                            ).build()
                        } else {
                            MockResponse.Builder().code(200).body(
                                """{"items":[
                                  {"session_id":"live_1","title":"Live Rig","preview":"Streaming","cwd":"/home/jason/projects/gravity-well","is_live":true,"status":"running","step_count":4,"model_id":"gemini-4-neon","thought_level":"high","mode_id":"default","updated_at":"2026-11-23T15:00:00Z"},
                                  {"session_id":"arch_1","title":"Old Trajectory","preview":"Done","cwd":"/home/jason/projects/other","is_live":false,"status":"archived","step_count":14,"updated_at":"2026-11-22T10:00:00Z"}
                                ],"total":2,"limit":50,"offset":0}""",
                            ).build()
                        }
                    }

                    path == "/api/observatory/status" -> {
                        // Note stale_lock_count = 710 vs active_external_count = 1 (D10)
                        MockResponse.Builder().code(200).body(
                            """{
                              "host":{"cpu_percent":14.2,"ram_used_mb":12000,"ram_total_mb":64000},
                              "external_sessions":[{"session_id":"ext_99","title":"CLI Terminal Run","cwd":"/home/jason/projects/gravity-well","supervised":false,"pid":4242}],
                              "active_external_count":1,
                              "stale_lock_count":710,
                              "checked_at":"2026-11-23T15:00:00Z"
                            }""",
                        ).build()
                    }

                    path == "/api/sessions/arch_auth/resume" -> {
                        MockResponse.Builder().code(200).body(
                            """{"session_id":"arch_auth","title":"Auth Audit","title_source":"acp","cwd":"/home/jason/projects/gravity-well","status":"idle","is_live":true,"cursor":12,"seq":9,"updated_at":"2026-11-23T15:01:00Z"}""",
                        ).build()
                    }

                    path == "/api/sessions/live_1" && request.method == "PATCH" -> {
                        MockResponse.Builder().code(200).body(
                            """{"session_id":"live_1","title":"Renamed By Gateway","title_source":"gateway","cwd":"/home/jason/projects/gravity-well","status":"running","is_live":true,"cursor":15,"seq":5,"updated_at":"2026-11-23T15:02:00Z"}""",
                        ).build()
                    }

                    else -> MockResponse.Builder().code(404).body("""{"error":{"code":"not_found","message":"Not found","status":404}}""").build()
                }
            }
        }
        server.start()

        val host = CachedHost(
            hostId = "h_aurora",
            label = "aurora",
            baseUrl = server.url("/").toString(),
            hostname = "aurora",
            defaultCwd = "/home/jason/projects/gravity-well",
            agyVersion = "1.5.15",
            capabilitiesJson = """{"fork":false,"resume_session":true,"load_session":true,"gateway_title_override":true}""",
            isActive = true,
            createdAt = "2026-11-23T14:00:00Z",
            lastVerifiedAt = "2026-11-23T14:00:00Z",
        )
        cacheStore.upsertHost(host)

        val okHttp = NetworkModule.createOkHttpClient(tokenProvider = { "gw_tok_test" })
        val gwApi = NetworkModule.createGatewayApi(server.url("/"), okHttp)
        val suppApi = SessionSupplementaryApi.create(server.url("/"), okHttp)

        val ctrl = SessionBrowserController(
            host = host,
            gatewayApi = gwApi,
            supplementaryApi = suppApi,
            cacheStore = cacheStore,
            scope = scope,
            searchDebounceMs = 0L,
        )

        ctrl.refresh()
        awaitCondition { ctrl.state.value.sessions.size == 2 && ctrl.state.value.externalSessions.size == 1 }

        // D10: Only the 1 FLOCK-verified external session is shown, never the 710 stale lock files
        assertEquals(1, ctrl.state.value.externalSessions.size)
        assertEquals("ext_99", ctrl.state.value.externalSessions.first().sessionId)
        assertFalse(ctrl.state.value.externalSessions.first().supervised)

        // Unsupported fork surfaces D4 -32601 guidance without hitting /fork when capabilities.fork == false
        ctrl.attemptForkSession(ctrl.state.value.sessions.first())
        assertTrue(ctrl.state.value.infoNotice.orEmpty().contains("-32601 unsupported on agy 1.5.15"))

        // Rename persists gateway title_source
        ctrl.openRenameDialog(ctrl.state.value.sessions.first())
        ctrl.updateRenameDraft("Renamed By Gateway")
        ctrl.submitRename()
        awaitCondition { ctrl.state.value.sessions.firstOrNull()?.title == "Renamed By Gateway" }
        assertEquals("gateway", ctrl.state.value.sessions.first().titleSource)

        // FTS5 search query forwarding
        ctrl.setSearchQuery("auth")
        awaitCondition { ctrl.state.value.sessions.size == 1 && ctrl.state.value.sessions.first().sessionId == "arch_auth" }
        assertTrue(observedTargets.any { it.contains("q=auth") })

        // Clicking archived session opens resume prompt -> confirm resume with load_history=true
        var openedConsoleId: String? = null
        ctrl.onSessionRowClick(ctrl.state.value.sessions.first()) { openedConsoleId = it }
        assertEquals("arch_auth", ctrl.state.value.resumePromptTarget?.sessionId)

        val resumedId = CompletableDeferred<String>()
        ctrl.confirmResumeSession("arch_auth", "/home/jason/projects/gravity-well", loadHistory = true) {
            resumedId.complete(it)
        }
        assertEquals("arch_auth", withTimeout(3_000L) { resumedId.await() })
        assertNullOrCleared(openedConsoleId)

        // Live WS presence_update frame updates radar
        val wsFrame = WsServerFrame.Event(
            cursor = 50L,
            seq = 0L,
            sessionId = null,
            type = "presence_update",
            ts = "2026-11-23T15:05:00Z",
            payload = Json.parseToJsonElement(
                """{"external_sessions":[{"session_id":"ext_1","title":"IDE Agent","cwd":"/tmp","supervised":false},{"session_id":"live_1","title":"Live Rig","cwd":"/tmp","supervised":true}],"active_count":2}""",
            ).jsonObject,
        )
        ctrl.onWebSocketFrame(wsFrame)
        assertEquals(2, ctrl.state.value.externalSessions.size)
        assertEquals(1, ctrl.state.value.unsupervisedExternalCount)
    }

    private fun assertNullOrCleared(v: String?) {
        assertEquals(null, v)
    }

    private suspend fun awaitCondition(timeoutMs: Long = 3_000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            delay(20L)
        }
        assertTrue("Condition timed out after ${timeoutMs}ms", predicate())
    }
}
