package us.snakepit.gravitywell.ui.observatory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
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
import us.snakepit.gravitywell.cache.CachedHost
import us.snakepit.gravitywell.cache.GravityWellDbHelper
import us.snakepit.gravitywell.cache.OfflineCacheStore
import us.snakepit.gravitywell.net.NetworkModule
import us.snakepit.gravitywell.net.WsServerFrame

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ObservatoryControllerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `telemetry math computes 10-segment fuel ticks quota tiers and reset countdowns`() {
        assertEquals(QuotaHealthTier.HEALTHY, ObservatoryTelemetryMath.resolveQuotaTier(89.1))
        assertEquals(QuotaHealthTier.HEALTHY, ObservatoryTelemetryMath.resolveQuotaTier(30.0))
        assertEquals(QuotaHealthTier.WARNING, ObservatoryTelemetryMath.resolveQuotaTier(29.9))
        assertEquals(QuotaHealthTier.WARNING, ObservatoryTelemetryMath.resolveQuotaTier(10.0))
        assertEquals(QuotaHealthTier.CRITICAL, ObservatoryTelemetryMath.resolveQuotaTier(9.9))
        assertEquals(QuotaHealthTier.CRITICAL, ObservatoryTelemetryMath.resolveQuotaTier(0.0))

        assertEquals(0, ObservatoryTelemetryMath.computeLitFuelSegments(0.0))
        assertEquals(1, ObservatoryTelemetryMath.computeLitFuelSegments(3.2))
        assertEquals(9, ObservatoryTelemetryMath.computeLitFuelSegments(89.1))
        assertEquals(10, ObservatoryTelemetryMath.computeLitFuelSegments(100.0))

        val nowMs = Instant.parse("2026-11-23T15:00:00Z").toEpochMilli()
        assertEquals(
            "T-04h 12m 08s",
            ObservatoryTelemetryMath.formatResetCountdown("2026-11-23T19:12:08Z", nowMs),
        )
        assertEquals(
            "T-05d 00h 37m 52s",
            ObservatoryTelemetryMath.formatResetCountdown("2026-11-28T15:37:52Z", nowMs),
        )
        assertEquals(
            "T-00h 00m 00s",
            ObservatoryTelemetryMath.formatResetCountdown("2026-11-23T14:00:00Z", nowMs),
        )

        assertEquals(LogLineSeverity.ERROR, ObservatoryTelemetryMath.classifyLogSeverity("2026-11-23 ERROR transport failed"))
        assertEquals(LogLineSeverity.WARN, ObservatoryTelemetryMath.classifyLogSeverity("2026-11-23 WARN slow rpc"))
        assertEquals(LogLineSeverity.INFO, ObservatoryTelemetryMath.classifyLogSeverity("2026-11-23 INFO connected"))

        val segments = ObservatoryTelemetryMath.splitRedactedSegments("Authorization: Bearer [REDACTED] ok")
        assertEquals(3, segments.size)
        assertFalse(segments[0].isRedactedToken)
        assertTrue(segments[1].isRedactedToken)
        assertEquals("[REDACTED]", segments[1].text)
        assertFalse(segments[2].isRedactedToken)
    }

    @Test
    fun `observatory controller hydrates sqlite snapshot refreshes usage daemon logs and enforces 2-step agy update confirm`() = runBlocking {
        val server = MockWebServer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(tempFolder.root, "observatory_test.db")
        val dbHelper = GravityWellDbHelper(context, dbFile.absolutePath)
        val cacheStore = OfflineCacheStore(dbHelper)
        val recordedPaths = CopyOnWriteArrayList<String>()
        val recordedBodies = CopyOnWriteArrayList<Pair<String, String>>()
        val daemonRunning = AtomicBoolean(false)

        try {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val pathWithQuery = request.target
                    val path = request.url.encodedPath
                    recordedPaths += pathWithQuery
                    val bodyStr = request.body?.utf8().orEmpty()
                    if (bodyStr.isNotEmpty()) {
                        recordedBodies += (path to bodyStr)
                    }

                    return when (path) {
                        "/api/observatory/usage" -> {
                            val forced = request.url.queryParameter("refresh") == "true"
                            val pct = if (forced) 92.4 else 89.1
                            MockResponse.Builder()
                                .code(200)
                                .body(
                                    """
                                    {
                                      "status": "SUCCESS",
                                      "cached_at": "2026-11-23T15:00:00Z",
                                      "stale": false,
                                      "groups": [
                                        {
                                          "name": "Gemini Models",
                                          "description": "Primary quota",
                                          "buckets": [
                                            {
                                              "id": "gemini-weekly",
                                              "name": "Weekly Limit Remaining",
                                              "window": "weekly",
                                              "remaining_fraction": ${pct / 100.0},
                                              "remaining_percent": $pct,
                                              "reset_time": "2026-11-28T15:37:52Z"
                                            }
                                          ]
                                        }
                                      ]
                                    }
                                    """.trimIndent(),
                                )
                                .build()
                        }

                        "/api/observatory/status" -> {
                            val active = daemonRunning.get()
                            MockResponse.Builder()
                                .code(200)
                                .body(
                                    """
                                    {
                                      "host": {
                                        "cpu_percent": 18.5,
                                        "load_avg_1m": 1.25,
                                        "load_avg_5m": 0.95,
                                        "ram_used_mb": 12288,
                                        "ram_total_mb": 63488,
                                        "disk_used_gb": 980.0,
                                        "disk_total_gb": 2300.0
                                      },
                                      "external_sessions": [
                                        {
                                          "session_id": "ext_1",
                                          "title": "Terminal agy session",
                                          "cwd": "/home/jason/projects/gravity-well",
                                          "supervised": false,
                                          "pid": 44120,
                                          "lock_file": "ext_1.lock"
                                        }
                                      ],
                                      "active_external_count": 1,
                                      "stale_lock_count": 710,
                                      "remote_control": {
                                        "active": $active,
                                        "status_text": "Daemon status: ${if (active) "active" else "inactive"}",
                                        "instance_name": ${if (active) "\"aurora-rig\"" else "null"}
                                      },
                                      "updater": {
                                        "success": true,
                                        "message": "Up to date",
                                        "agy_version": "1.5.15"
                                      },
                                      "checked_at": "2026-11-23T15:00:01Z"
                                    }
                                    """.trimIndent(),
                                )
                                .build()
                        }

                        "/api/observatory/logs" -> {
                            val src = request.url.queryParameter("source") ?: "cli"
                            val q = request.url.queryParameter("q").orEmpty()
                            if (src == "crashes") {
                                MockResponse.Builder()
                                    .code(200)
                                    .body(
                                        """
                                        {
                                          "source": "crashes",
                                          "file": "crash-20261123.log",
                                          "files": ["crash-20261123.log", "crash-20261120.log"],
                                          "lines": ["FATAL panic with token [REDACTED]"],
                                          "total_matched": 1
                                        }
                                        """.trimIndent(),
                                    )
                                    .build()
                            } else {
                                val lines = listOf(
                                    "INFO startup ok",
                                    "WARN token Bearer [REDACTED] scrubbed",
                                ).filter { q.isEmpty() || it.contains(q, ignoreCase = true) }
                                val linesJson = lines.joinToString(",") { "\"$it\"" }
                                MockResponse.Builder()
                                    .code(200)
                                    .body(
                                        """{"source":"cli","file":"cli.log","files":["cli.log"],"lines":[$linesJson],"total_matched":${lines.size}}""",
                                    )
                                    .build()
                            }
                        }

                        "/api/observatory/remote-control/start" -> {
                            daemonRunning.set(true)
                            MockResponse.Builder()
                                .code(200)
                                .body("""{"active":true,"status_text":"Daemon status: active","instance_name":"aurora-rig"}""")
                                .build()
                        }

                        "/api/observatory/remote-control/stop" -> {
                            daemonRunning.set(false)
                            MockResponse.Builder()
                                .code(200)
                                .body("""{"active":false,"status_text":"Daemon status: inactive","instance_name":null}""")
                                .build()
                        }

                        "/api/observatory/update" -> {
                            if (!bodyStr.contains("\"confirm\":true")) {
                                MockResponse.Builder()
                                    .code(400)
                                    .body("""{"error":{"code":"bad_request","message":"confirm required","status":400}}""")
                                    .build()
                            } else {
                                MockResponse.Builder()
                                    .code(200)
                                    .body("""{"updated":true,"version_before":"1.5.15","version_after":"1.5.16","output":"Updated agy [REDACTED]"}""")
                                    .build()
                            }
                        }

                        else -> MockResponse.Builder().code(404).build()
                    }
                }
            }
            server.start()

            val host = CachedHost(
                hostId = "h_obs",
                label = "aurora",
                baseUrl = server.url("/").toString(),
                defaultCwd = "/home/jason",
                isActive = true,
                createdAt = "2026-11-23T14:00:00Z",
                lastVerifiedAt = "2026-11-23T14:00:00Z",
            )
            cacheStore.upsertHost(host)

            val okHttp = NetworkModule.createOkHttpClient(tokenProvider = { "gw_obs_tok" })
            val api = ObservatoryApi.create(server.url("/"), okHttp)
            val controller = ObservatoryController(
                host = host,
                api = api,
                cacheStore = cacheStore,
                scope = scope,
            )

            controller.refreshAll()
            awaitCondition {
                !controller.state.value.isLoading && controller.state.value.usage != null && controller.state.value.status != null
            }

            assertEquals(89.1, controller.state.value.usage!!.groups.single().buckets.single().remainingPercent, 0.01)
            assertEquals(1, controller.state.value.status!!.activeExternalCount)
            assertEquals(710, controller.state.value.status!!.staleLockCount)
            assertEquals(2, controller.state.value.logLines.size)

            // Verify SQLite snapshot persisted and hydrates a fresh controller offline
            val snapshot = cacheStore.getObservatorySnapshot("h_obs")
            assertNotNull(snapshot)
            val coldController = ObservatoryController(
                host = host,
                api = api,
                cacheStore = cacheStore,
                scope = scope,
            )
            assertNotNull(coldController.state.value.usage)
            assertEquals(18.5, coldController.state.value.status!!.host.cpuPercent, 0.01)

            // Bypass usage TTL cache -> ?refresh=true
            controller.refreshUsageBypassCache()
            awaitCondition {
                !controller.state.value.isRefreshingUsage &&
                    controller.state.value.usage!!.groups.single().buckets.single().remainingPercent == 92.4
            }
            assertTrue(recordedPaths.any { it.contains("/api/observatory/usage?refresh=true") })

            // Start & stop remote-control daemon
            controller.updateDaemonNameDraft("aurora-rig")
            controller.setDaemonSessionScoped(true)
            controller.startRemoteControl()
            awaitCondition { controller.state.value.status?.remoteControl?.active == true }
            assertEquals("aurora-rig", controller.state.value.status?.remoteControl?.instanceName)

            controller.stopRemoteControl()
            awaitCondition { controller.state.value.status?.remoteControl?.active == false }

            // 2-step confirm interlock: calling confirmAndRunUpdate BEFORE opening sheet MUST NOT call /update
            controller.confirmAndRunUpdate()
            assertFalse(recordedPaths.any { it.startsWith("/api/observatory/update") })
            assertNotNull(controller.state.value.errorBanner)

            // Step 1: Open confirm sheet -> Step 2: Confirm & Run Update
            controller.openUpdateConfirmSheet()
            assertTrue(controller.state.value.showUpdateConfirmSheet)
            controller.confirmAndRunUpdate()
            awaitCondition { !controller.state.value.isUpdatingCli && controller.state.value.lastUpdateResult != null }
            assertFalse(controller.state.value.showUpdateConfirmSheet)
            assertEquals("1.5.16", controller.state.value.lastUpdateResult?.versionAfter)
            assertTrue(recordedBodies.any { it.first == "/api/observatory/update" && it.second.contains("\"confirm\":true") })

            // Switch log source to crashes/
            controller.selectLogSource(LogSourceTab.CRASHES)
            awaitCondition {
                !controller.state.value.isLogsLoading && controller.state.value.activeLogFile == "crash-20261123.log"
            }
            assertEquals(listOf("crash-20261123.log", "crash-20261120.log"), controller.state.value.availableCrashFiles)
            assertTrue(controller.state.value.logLines.single().contains("[REDACTED]"))

            // WebSocket presence_update frame updates radar live
            controller.onWebSocketFrame(
                WsServerFrame.Event(
                    cursor = 55L,
                    seq = 0L,
                    sessionId = null,
                    type = "presence_update",
                    ts = "2026-11-23T15:05:00Z",
                    payload = buildJsonObject {
                        put("active_count", 0)
                        put("external_sessions", buildJsonArray {})
                    },
                ),
            )
            assertEquals(0, controller.state.value.status?.activeExternalCount)
            assertNull(controller.state.value.status?.externalSessions?.firstOrNull())
        } finally {
            scope.cancel()
            dbHelper.close()
            server.close()
        }
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
