package us.snakepit.gravitywell.service

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import us.snakepit.gravitywell.GravityWellRuntime
import us.snakepit.gravitywell.MainActivity
import us.snakepit.gravitywell.cache.CachedHost
import us.snakepit.gravitywell.cache.CachedPendingApproval
import us.snakepit.gravitywell.cache.CachedSession
import us.snakepit.gravitywell.cache.GravityWellDbHelper
import us.snakepit.gravitywell.cache.OfflineCacheStore
import us.snakepit.gravitywell.net.WsServerFrame
import us.snakepit.gravitywell.security.AndroidKeyStoreCipherEngine
import us.snakepit.gravitywell.security.EncryptedPayload
import us.snakepit.gravitywell.security.TokenVault
import us.snakepit.gravitywell.security.VaultCipherEngine
import us.snakepit.gravitywell.ui.shell.PendingDeepLink
import us.snakepit.gravitywell.ui.shell.ShellController
import us.snakepit.gravitywell.ui.shell.TopLevelDestination

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class KeepaliveAndNotificationTest {

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
    fun `manifest declares dataSync foreground service non-exported receiver and monochrome 24dp ic_notification`() {
        val appDir = findAppDir()
        val manifestDoc = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(File(appDir, "src/main/AndroidManifest.xml"))

        val permissions = (0 until manifestDoc.getElementsByTagName("uses-permission").length).map { i ->
            manifestDoc.getElementsByTagName("uses-permission").item(i).attributes
                .getNamedItem("android:name")?.nodeValue
        }.toSet()
        assertTrue(permissions.contains("android.permission.FOREGROUND_SERVICE"))
        assertTrue(permissions.contains("android.permission.FOREGROUND_SERVICE_DATA_SYNC"))
        assertTrue(permissions.contains("android.permission.POST_NOTIFICATIONS"))

        val serviceEl = manifestDoc.getElementsByTagName("service").item(0)
        assertNotNull("Expected GravityWellKeepaliveService in AndroidManifest.xml", serviceEl)
        assertEquals(".service.GravityWellKeepaliveService", serviceEl.attributes.getNamedItem("android:name")?.nodeValue)
        assertEquals("false", serviceEl.attributes.getNamedItem("android:exported")?.nodeValue)
        assertEquals("dataSync", serviceEl.attributes.getNamedItem("android:foregroundServiceType")?.nodeValue)

        val receiverEl = manifestDoc.getElementsByTagName("receiver").item(0)
        assertNotNull("Expected KeepaliveActionReceiver in AndroidManifest.xml", receiverEl)
        assertEquals(".service.KeepaliveActionReceiver", receiverEl.attributes.getNamedItem("android:name")?.nodeValue)
        assertEquals("false", receiverEl.attributes.getNamedItem("android:exported")?.nodeValue)

        // Audit ic_notification.xml: 24dp x 24dp monochrome #FFFFFF vector
        val iconFile = File(appDir, "src/main/res/drawable/ic_notification.xml")
        assertTrue("Expected ic_notification.xml drawable", iconFile.isFile)
        val iconDoc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(iconFile)
        val vectorEl = iconDoc.documentElement
        assertEquals("vector", vectorEl.tagName)
        assertEquals("24dp", vectorEl.attributes.getNamedItem("android:width")?.nodeValue)
        assertEquals("24dp", vectorEl.attributes.getNamedItem("android:height")?.nodeValue)
        assertEquals("24", vectorEl.attributes.getNamedItem("android:viewportWidth")?.nodeValue)
        assertEquals("24", vectorEl.attributes.getNamedItem("android:viewportHeight")?.nodeValue)
        assertEquals(0, iconDoc.getElementsByTagName("gradient").length)

        val paths = iconDoc.getElementsByTagName("path")
        assertTrue("Expected orbital vector paths in ic_notification.xml", paths.length >= 4)
        for (i in 0 until paths.length) {
            val attrs = paths.item(i).attributes
            val fill = attrs.getNamedItem("android:fillColor")?.nodeValue
            val stroke = attrs.getNamedItem("android:strokeColor")?.nodeValue
            assertTrue("Each path must set #FFFFFF fill or stroke", fill != null || stroke != null)
            if (fill != null) assertEquals("#FFFFFF", fill.uppercase())
            if (stroke != null) assertEquals("#FFFFFF", stroke.uppercase())
        }
    }

    @Test
    fun `source audit enforces inline TIRAMISU POST_NOTIFICATIONS and SecurityException guard at every notify call`() {
        val appDir = findAppDir()
        val javaRoot = File(appDir, "src/main/java/us/snakepit/gravitywell")
        val ktFiles = javaRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        var notifySitesFound = 0

        for (file in ktFiles) {
            val lines = file.readLines()
            lines.forEachIndexed { idx, line ->
                if (line.contains(".notify(")) {
                    notifySitesFound++
                    val windowStart = (idx - 16).coerceAtLeast(0)
                    val windowEnd = (idx + 8).coerceAtMost(lines.lastIndex)
                    val windowText = lines.subList(windowStart, windowEnd + 1).joinToString("\n")
                    val rel = file.relativeTo(javaRoot).invariantSeparatorsPath
                    assertTrue(
                        "Missing inline TIRAMISU check around notify() at $rel:${idx + 1}",
                        windowText.contains("Build.VERSION_CODES.TIRAMISU"),
                    )
                    assertTrue(
                        "Missing inline POST_NOTIFICATIONS checkSelfPermission around notify() at $rel:${idx + 1}",
                        windowText.contains("Manifest.permission.POST_NOTIFICATIONS") &&
                            windowText.contains("checkSelfPermission"),
                    )
                    assertTrue(
                        "Missing inline SecurityException catch around notify() at $rel:${idx + 1}",
                        windowText.contains("SecurityException"),
                    )
                }
            }
        }
        assertTrue("Expected at least 2 guarded notify() sites", notifySitesFound >= 2)
    }

    @Test
    fun `notifications scrub secrets omit allow_rule from shade actions and build explicit non-exported intents`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val ongoing = GravityWellNotifications.buildOngoingNotification(
            context = context,
            hostId = "h1",
            hostLabel = "aurora",
            attachedSessionId = "sess_1",
            liveSessionCount = 2,
            awaitingGateCount = 1,
        )
        assertEquals("Gravity Well · aurora", ongoing.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("2 live sessions · 1 awaiting gate", ongoing.extras.getString(Notification.EXTRA_TEXT))
        assertEquals(1, ongoing.actions.size)
        assertEquals("Stop", ongoing.actions[0].title.toString())

        val secretBearingSummary = "EXECUTE · curl -H 'Authorization: Bearer ya29.super_secret_val' sk-1234567890abcdef"
        val permNotif = GravityWellNotifications.buildPriorityGateNotification(
            context = context,
            hostId = "h1",
            sessionId = "sess_1",
            sessionTitle = "Refactor Auth",
            requestId = "req_42",
            kind = "permission",
            summary = secretBearingSummary,
        )
        val renderedText = permNotif.extras.getString(Notification.EXTRA_TEXT).orEmpty()
        assertFalse("Notification text must never leak ya29 token", renderedText.contains("ya29.super_secret_val"))
        assertFalse("Notification text must never leak sk- token", renderedText.contains("sk-1234567890abcdef"))
        assertTrue(renderedText.contains("[REDACTED]"))

        // Permission notification offers ONLY Approve Once & Deny — NEVER allow_rule!
        val actionTitles = permNotif.actions.map { it.title.toString() }
        assertEquals(listOf("Approve Once", "Deny"), actionTitles)
        assertFalse(actionTitles.any { it.contains("Rule", ignoreCase = true) || it.contains("Always", ignoreCase = true) })

        val approveShadowPi = shadowOf(permNotif.actions[0].actionIntent)
        val approveSavedIntent = approveShadowPi.savedIntent
        assertEquals(
            KeepaliveActionReceiver::class.java.name,
            approveSavedIntent.component?.className,
        )
        assertEquals(KeepaliveActionReceiver.ACTION_APPROVE_ONCE, approveSavedIntent.action)
        assertFalse(
            "Broadcast Intent extras must never carry bearer tokens",
            approveSavedIntent.extras?.keySet().orEmpty().any { it.contains("token", ignoreCase = true) },
        )

        // Question notification omits broadcast Approve/Deny actions and deep-links to MainActivity
        val questionNotif = GravityWellNotifications.buildPriorityGateNotification(
            context = context,
            hostId = "h1",
            sessionId = "sess_q",
            sessionTitle = "Schema Prompt",
            requestId = "req_q1",
            kind = "question",
            summary = "Which environment?",
        )
        assertTrue(questionNotif.actions.isNullOrEmpty())
        val contentIntent = shadowOf(questionNotif.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, contentIntent.component?.className)
        assertEquals("h1", contentIntent.getStringExtra(MainActivity.EXTRA_HOST_ID))
        assertEquals("sess_q", contentIntent.getStringExtra(MainActivity.EXTRA_SESSION_ID))

        val settingsIntent = GravityWellNotifications.buildSystemNotificationSettingsIntent(context)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, settingsIntent.action)
        assertEquals(context.packageName, settingsIntent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    @Config(sdk = [33])
    fun `api 33 POST_NOTIFICATIONS runtime permission gate suppresses notify when denied and delivers when granted`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        val shadowNm = shadowOf(manager)

        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val postedWhenDenied = GravityWellNotifications.postPriorityGateNotification(
            context = app,
            hostId = "h1",
            sessionId = "s1",
            sessionTitle = "Denied Test",
            requestId = "r1",
            kind = "permission",
            summary = "pytest -q",
        )
        assertFalse(postedWhenDenied)
        assertEquals(0, shadowNm.allNotifications.size)

        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val postedWhenGranted = GravityWellNotifications.postPriorityGateNotification(
            context = app,
            hostId = "h1",
            sessionId = "s1",
            sessionTitle = "Granted Test",
            requestId = "r1",
            kind = "permission",
            summary = "pytest -q",
        )
        assertTrue(postedWhenGranted)
        assertEquals(1, shadowNm.allNotifications.size)
    }

    @Test
    fun `KeepaliveActionReceiver resolves Approve Once and Deny via TokenVault rejects allow_rule and handles Stop`() = runBlocking {
        val server = MockWebServer()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbHelper = GravityWellDbHelper(context, File(tempFolder.root, "receiver_test.db").absolutePath)
        val cacheStore = OfflineCacheStore(dbHelper)
        val tokenVault = TokenVault(File(tempFolder.root, "receiver_vault.json"), TestAesGcmEngine())
        val prefs = KeepalivePreferences.inMemory(initialEnabled = true)
        val recordedDecisions = CopyOnWriteArrayList<Pair<String, String>>()

        try {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val auth = request.headers["Authorization"].orEmpty()
                    if (auth != "Bearer gw_receiver_secret") {
                        return MockResponse.Builder().code(401).body("""{"error":{"code":"unauthorized","message":"bad","status":401}}""").build()
                    }
                    val body = request.body?.utf8().orEmpty()
                    recordedDecisions += (request.url.encodedPath to body)
                    return MockResponse.Builder()
                        .code(200)
                        .body("""{"request_id":"req_1","resolved":true,"option_id":"allow-once"}""")
                        .build()
                }
            }
            server.start()

            val host = CachedHost(
                hostId = "h1",
                label = "aurora",
                baseUrl = server.url("/").toString(),
                defaultCwd = "/home/jason",
                isActive = true,
                createdAt = "2026-11-23T14:00:00Z",
                lastVerifiedAt = "2026-11-23T14:00:00Z",
            )
            cacheStore.upsertHost(host)
            tokenVault.putToken("h1", "gw_receiver_secret")
            cacheStore.upsertSession(
                CachedSession(
                    hostId = "h1",
                    sessionId = "sess_1",
                    title = "Build Lane",
                    cwd = "/home/jason",
                    status = "awaiting_approval",
                    isLive = true,
                    updatedAt = "2026-11-23T14:05:00Z",
                ),
            )
            cacheStore.upsertPendingApproval(
                CachedPendingApproval(
                    hostId = "h1",
                    sessionId = "sess_1",
                    requestId = "req_1",
                    kind = "permission",
                    title = "EXECUTE · ./gradlew test",
                    payloadJson = "{}",
                    createdAt = "2026-11-23T14:05:00Z",
                ),
            )
            cacheStore.upsertPendingApproval(
                CachedPendingApproval(
                    hostId = "h1",
                    sessionId = "sess_1",
                    requestId = "req_2",
                    kind = "permission",
                    title = "EXECUTE · rm -rf build",
                    payloadJson = "{}",
                    createdAt = "2026-11-23T14:05:01Z",
                ),
            )

            // Reject spoofed / allow_rule action
            assertNull(KeepaliveActionReceiver.decisionForAction("us.snakepit.gravitywell.action.ALLOW_RULE"))
            val spoofedRes = KeepaliveActionReceiver.processActionIntent(
                context = context,
                intent = Intent("us.snakepit.gravitywell.action.ALLOW_RULE").apply {
                    putExtra(KeepaliveActionReceiver.EXTRA_HOST_ID, "h1")
                    putExtra(KeepaliveActionReceiver.EXTRA_SESSION_ID, "sess_1")
                    putExtra(KeepaliveActionReceiver.EXTRA_REQUEST_ID, "req_1")
                },
                cacheStore = cacheStore,
                tokenVault = tokenVault,
                preferences = prefs,
            )
            assertFalse(spoofedRes)
            assertTrue(recordedDecisions.isEmpty())

            // Approve Once -> allow_once + removes req_1 from pending_approvals
            val approveRes = KeepaliveActionReceiver.processActionIntent(
                context = context,
                intent = Intent(KeepaliveActionReceiver.ACTION_APPROVE_ONCE).apply {
                    putExtra(KeepaliveActionReceiver.EXTRA_HOST_ID, "h1")
                    putExtra(KeepaliveActionReceiver.EXTRA_SESSION_ID, "sess_1")
                    putExtra(KeepaliveActionReceiver.EXTRA_REQUEST_ID, "req_1")
                },
                cacheStore = cacheStore,
                tokenVault = tokenVault,
                preferences = prefs,
            )
            assertTrue(approveRes)
            assertEquals(1, recordedDecisions.size)
            assertEquals("/api/sessions/sess_1/permissions/req_1", recordedDecisions[0].first)
            assertTrue(recordedDecisions[0].second.contains("\"decision\":\"allow_once\""))
            assertEquals(listOf("req_2"), cacheStore.listPendingApprovals("h1", "sess_1").map { it.requestId })

            // Deny -> deny + clears last pending_approval and flips session status back to running
            val denyRes = KeepaliveActionReceiver.processActionIntent(
                context = context,
                intent = Intent(KeepaliveActionReceiver.ACTION_DENY).apply {
                    putExtra(KeepaliveActionReceiver.EXTRA_HOST_ID, "h1")
                    putExtra(KeepaliveActionReceiver.EXTRA_SESSION_ID, "sess_1")
                    putExtra(KeepaliveActionReceiver.EXTRA_REQUEST_ID, "req_2")
                },
                cacheStore = cacheStore,
                tokenVault = tokenVault,
                preferences = prefs,
            )
            assertTrue(denyRes)
            assertTrue(recordedDecisions[1].second.contains("\"decision\":\"deny\""))
            assertTrue(cacheStore.listPendingApprovals("h1", "sess_1").isEmpty())
            assertEquals("running", cacheStore.getSession("h1", "sess_1")?.status)

            // Stop -> disables keepalive preference
            assertTrue(prefs.isKeepaliveEnabled)
            val stopRes = KeepaliveActionReceiver.processActionIntent(
                context = context,
                intent = Intent(KeepaliveActionReceiver.ACTION_STOP),
                cacheStore = cacheStore,
                tokenVault = tokenVault,
                preferences = prefs,
            )
            assertTrue(stopRes)
            assertFalse(prefs.isKeepaliveEnabled)
        } finally {
            dbHelper.close()
            server.close()
        }
    }

    @Test
    fun `KeepaliveCoordinator alerts only when backgrounded and DeepLinkIntentQueue drains FIFO across cold and warm intents`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbHelper = GravityWellDbHelper(context, File(tempFolder.root, "coord_test.db").absolutePath)
        val cacheStore = OfflineCacheStore(dbHelper)
        val tokenVault = TokenVault(File(tempFolder.root, "coord_vault.json"), TestAesGcmEngine())
        val prefs = KeepalivePreferences.inMemory(initialEnabled = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        val postedAlerts = CopyOnWriteArrayList<Triple<String, String, String>>()
        val cancelledAlerts = CopyOnWriteArrayList<String>()
        var serviceRunning = false
        var cancelAllCalls = 0
        var inForeground = true

        val fakeDriver = object : KeepaliveCoordinator.ServiceDriver {
            override fun startOrUpdateKeepalive(context: Context, snapshot: KeepaliveWatchSnapshot) {
                serviceRunning = true
            }
            override fun stopKeepalive(context: Context) {
                serviceRunning = false
            }
            override fun postGateAlert(
                context: Context,
                hostId: String,
                sessionId: String,
                sessionTitle: String,
                requestId: String,
                kind: String,
                summary: String,
            ) {
                postedAlerts += Triple(sessionId, requestId, kind)
            }
            override fun cancelGateAlert(context: Context, hostId: String, sessionId: String, requestId: String) {
                cancelledAlerts += requestId
            }
            override fun cancelAllNotifications(context: Context) {
                cancelAllCalls++
            }
        }

        try {
            val h1 = CachedHost(
                hostId = "h1",
                label = "aurora-1",
                baseUrl = "https://aurora1.tail.ts.net/",
                defaultCwd = "/home/jason",
                isActive = true,
                createdAt = "2026-11-23T14:00:00Z",
                lastVerifiedAt = "2026-11-23T14:00:00Z",
            )
            val h2 = CachedHost(
                hostId = "h2",
                label = "aurora-2",
                baseUrl = "https://aurora2.tail.ts.net/",
                defaultCwd = "/srv/work",
                isActive = false,
                createdAt = "2026-11-23T14:00:00Z",
                lastVerifiedAt = "2026-11-23T14:00:00Z",
            )
            cacheStore.upsertHost(h1)
            cacheStore.upsertHost(h2)
            tokenVault.putToken("h1", "tok1")
            tokenVault.putToken("h2", "tok2")

            cacheStore.upsertSession(
                CachedSession(
                    hostId = "h1",
                    sessionId = "s_live",
                    title = "Active Session",
                    cwd = "/home/jason",
                    status = "running",
                    isLive = true,
                    updatedAt = "2026-11-23T15:00:00Z",
                ),
            )

            val coordinator = KeepaliveCoordinator(
                cacheStore = cacheStore,
                preferences = prefs,
                isForegroundProvider = { inForeground },
                serviceController = fakeDriver,
            )
            coordinator.syncServiceState(context, h1, attachedSessionId = null)
            assertTrue("Keepalive service should start when live session exists", serviceRunning)

            // Foreground permission_requested -> does NOT post shade alert
            val permFrame = WsServerFrame.Event(
                cursor = 10L,
                seq = 1L,
                sessionId = "s_live",
                type = "permission_requested",
                ts = "2026-11-23T15:01:00Z",
                payload = buildJsonObject {
                    put("request_id", "req_fg")
                    put("tool_call", buildJsonObject { put("kind", "execute"); put("title", "ls") })
                    put("options", buildJsonArray {})
                },
            )
            coordinator.onWebSocketEvent(context, h1, attachedSessionId = "s_live", frame = permFrame)
            assertTrue("Foreground app must not spam priority shade alert", postedAlerts.isEmpty())

            // Background permission_requested -> posts priority shade alert
            inForeground = false
            val bgPermFrame = permFrame.copy(
                cursor = 11L,
                seq = 2L,
                payload = buildJsonObject {
                    put("request_id", "req_bg")
                    put("tool_call", buildJsonObject { put("kind", "execute"); put("title", "pytest") })
                    put("options", buildJsonArray {})
                },
            )
            coordinator.onWebSocketEvent(context, h1, attachedSessionId = null, frame = bgPermFrame)
            assertEquals(listOf(Triple("s_live", "req_bg", "permission")), postedAlerts.toList())

            // Resolution cancels alert
            val resolvedFrame = WsServerFrame.Event(
                cursor = 12L,
                seq = 3L,
                sessionId = "s_live",
                type = "permission_resolved",
                ts = "2026-11-23T15:01:05Z",
                payload = buildJsonObject { put("request_id", "req_bg") },
            )
            coordinator.onWebSocketEvent(context, h1, attachedSessionId = null, frame = resolvedFrame)
            assertEquals(listOf("req_bg"), cancelledAlerts.toList())

            // Disabling keepalive stops service and cancels all notifications immediately
            coordinator.setKeepaliveEnabled(context, enabled = false, activeHost = h1)
            assertFalse(serviceRunning)
            assertEquals(1, cancelAllCalls)

            // FIFO DeepLinkIntentQueue cold-start + singleTask warm resume + Bundle save/restore
            val queue = DeepLinkIntentQueue()
            val coldIntent = Intent().apply {
                putExtra(MainActivity.EXTRA_HOST_ID, "h1")
                putExtra(MainActivity.EXTRA_SESSION_ID, "s_cold")
            }
            val warmIntent = Intent().apply {
                putExtra(MainActivity.EXTRA_HOST_ID, "h2")
                putExtra(MainActivity.EXTRA_SESSION_ID, "s_warm_on_h2")
            }
            assertTrue(queue.enqueueIntent(coldIntent))
            assertFalse("Duplicate consecutive deep-link is coalesced", queue.enqueueIntent(coldIntent))
            assertTrue(queue.enqueueIntent(warmIntent))
            assertEquals(2, queue.size())
            assertEquals(PendingDeepLink("h1", "s_cold"), queue.head.value)

            val savedBundle = Bundle()
            queue.saveToBundle(savedBundle)
            val restoredQueue = DeepLinkIntentQueue().apply { restoreFromBundle(savedBundle) }
            assertEquals(2, restoredQueue.size())

            val shell = ShellController(
                cacheStore = cacheStore,
                tokenVault = tokenVault,
                scope = scope,
                isDebugBuild = true,
                autoConnectWebSocket = false,
            )

            // Drain head #1 (h1 / s_cold)
            val first = restoredQueue.head.value!!
            shell.consumeDeepLink(first)
            restoredQueue.acknowledge(first)
            assertEquals("h1", shell.state.value.activeHost?.hostId)
            assertEquals("s_cold", shell.state.value.attachedSessionId)
            assertEquals(TopLevelDestination.CONSOLE, shell.state.value.destination)

            // Drain head #2 (h2 / s_warm_on_h2) -> switches active host to h2 and opens console
            val second = restoredQueue.head.value!!
            shell.consumeDeepLink(second)
            restoredQueue.acknowledge(second)
            assertNull(restoredQueue.head.value)
            assertEquals("h2", shell.state.value.activeHost?.hostId)
            assertEquals("s_warm_on_h2", shell.state.value.attachedSessionId)
            assertEquals(TopLevelDestination.CONSOLE, shell.state.value.destination)

            // Unknown hostId in deep-link must NOT hijack current session onto wrong host
            shell.consumeDeepLink(PendingDeepLink(hostId = "unknown_host", sessionId = "s_hijack"))
            assertEquals("h2", shell.state.value.activeHost?.hostId)
            assertEquals("s_warm_on_h2", shell.state.value.attachedSessionId)

            shell.shutdown()
        } finally {
            scope.cancel()
            dbHelper.close()
        }
    }

    @Test
    fun `GravityWellKeepaliveService promotes to foreground before stopSelf on disabled keepalive missing host and zero watched work`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbHelper = GravityWellDbHelper(context, File(tempFolder.root, "fgs_early_stop.db").absolutePath)
        val cacheStore = OfflineCacheStore(dbHelper)
        val tokenVault = TokenVault(File(tempFolder.root, "fgs_early_stop_vault.json"), TestAesGcmEngine())
        val prefs = KeepalivePreferences.get(context)
        GravityWellRuntime.setForTest(GravityWellRuntime(dbHelper, cacheStore, tokenVault))
        AppForegroundTracker.setShellStreamActive(true)

        try {
            // Case 1: isKeepaliveEnabled = false
            prefs.setKeepaliveEnabled(false)
            val svcDisabled = Robolectric.buildService(GravityWellKeepaliveService::class.java).create().get()
            svcDisabled.onStartCommand(
                Intent(context, GravityWellKeepaliveService::class.java).apply {
                    action = GravityWellKeepaliveService.ACTION_START_OR_UPDATE
                },
                0,
                1,
            )
            assertEquals("Disabled keepalive must still promote before stopSelf", 1, svcDisabled.foregroundPromotionsForTest)
            assertTrue("Disabled keepalive must stopSelf", shadowOf(svcDisabled).isStoppedBySelf)
            assertFalse(GravityWellKeepaliveService.isRunning)
            svcDisabled.onDestroy()

            // Case 2: keepalive enabled, host == null
            prefs.setKeepaliveEnabled(true)
            val svcNoHost = Robolectric.buildService(GravityWellKeepaliveService::class.java).create().get()
            svcNoHost.onStartCommand(
                Intent(context, GravityWellKeepaliveService::class.java).apply {
                    action = GravityWellKeepaliveService.ACTION_START_OR_UPDATE
                },
                0,
                2,
            )
            assertEquals("Missing host must still promote before stopSelf", 1, svcNoHost.foregroundPromotionsForTest)
            assertTrue("Missing host must stopSelf", shadowOf(svcNoHost).isStoppedBySelf)
            assertFalse(GravityWellKeepaliveService.isRunning)
            svcNoHost.onDestroy()

            // Case 3: host present, liveCount == 0 && gateCount == 0
            cacheStore.upsertHost(
                CachedHost(
                    hostId = "h_idle",
                    label = "idle-host",
                    baseUrl = "https://idle.example/",
                    defaultCwd = "/tmp",
                    isActive = true,
                    createdAt = "2026-11-23T14:00:00Z",
                    lastVerifiedAt = "2026-11-23T14:00:00Z",
                ),
            )
            val svcZeroWork = Robolectric.buildService(GravityWellKeepaliveService::class.java).create().get()
            svcZeroWork.onStartCommand(
                Intent(context, GravityWellKeepaliveService::class.java).apply {
                    action = GravityWellKeepaliveService.ACTION_START_OR_UPDATE
                    putExtra(GravityWellKeepaliveService.EXTRA_HOST_ID, "h_idle")
                },
                0,
                3,
            )
            assertEquals("Zero watched work must still promote before stopSelf", 1, svcZeroWork.foregroundPromotionsForTest)
            assertTrue("Zero watched work must stopSelf", shadowOf(svcZeroWork).isStoppedBySelf)
            assertFalse(GravityWellKeepaliveService.isRunning)
            svcZeroWork.onDestroy()
        } finally {
            prefs.setKeepaliveEnabled(true)
            AppForegroundTracker.setShellStreamActive(false)
            GravityWellRuntime.setForTest(null)
            dbHelper.close()
        }
    }

    @Test
    fun `KeepaliveCoordinator throttles streaming chunks and only syncs on lifecycle or count transitions`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbHelper = GravityWellDbHelper(context, File(tempFolder.root, "coord_throttle.db").absolutePath)
        val cacheStore = OfflineCacheStore(dbHelper)
        val prefs = KeepalivePreferences.inMemory(initialEnabled = true)
        val startOrUpdateCalls = AtomicInteger(0)

        val driver = object : KeepaliveCoordinator.ServiceDriver {
            override fun startOrUpdateKeepalive(context: Context, snapshot: KeepaliveWatchSnapshot) {
                startOrUpdateCalls.incrementAndGet()
            }
            override fun stopKeepalive(context: Context) = Unit
            override fun postGateAlert(
                context: Context,
                hostId: String,
                sessionId: String,
                sessionTitle: String,
                requestId: String,
                kind: String,
                summary: String,
            ) = Unit
            override fun cancelGateAlert(context: Context, hostId: String, sessionId: String, requestId: String) = Unit
            override fun cancelAllNotifications(context: Context) = Unit
        }

        try {
            val h1 = CachedHost(
                hostId = "h1",
                label = "aurora-1",
                baseUrl = "https://aurora1.tail.ts.net/",
                defaultCwd = "/home/jason",
                isActive = true,
                createdAt = "2026-11-23T14:00:00Z",
                lastVerifiedAt = "2026-11-23T14:00:00Z",
            )
            cacheStore.upsertHost(h1)
            cacheStore.upsertSession(
                CachedSession(
                    hostId = "h1",
                    sessionId = "s_stream",
                    title = "Stream",
                    cwd = "/home/jason",
                    status = "running",
                    isLive = true,
                    updatedAt = "2026-11-23T15:00:00Z",
                ),
            )
            val coordinator = KeepaliveCoordinator(
                cacheStore = cacheStore,
                preferences = prefs,
                isForegroundProvider = { true },
                serviceController = driver,
            )

            coordinator.syncServiceState(context, h1, attachedSessionId = "s_stream")
            assertEquals(1, startOrUpdateCalls.get())

            // 30 high-frequency streaming chunks must NOT trigger startOrUpdateKeepalive
            for (i in 1..30) {
                val type = if (i % 2 == 0) "agent_thought_chunk" else "agent_message_chunk"
                coordinator.onWebSocketEvent(
                    context = context,
                    host = h1,
                    attachedSessionId = "s_stream",
                    frame = WsServerFrame.Event(
                        cursor = i.toLong(),
                        seq = i.toLong(),
                        sessionId = "s_stream",
                        type = type,
                        ts = "2026-11-23T15:01:00Z",
                        payload = buildJsonObject { put("text", "chunk $i") },
                    ),
                )
            }
            assertEquals("Streaming chunks must not invoke startOrUpdateKeepalive", 1, startOrUpdateCalls.get())

            // Lifecycle / gate change (awaitingGateCount 0 -> 1) DOES sync once
            coordinator.onWebSocketEvent(
                context = context,
                host = h1,
                attachedSessionId = null,
                frame = WsServerFrame.Event(
                    cursor = 31L,
                    seq = 31L,
                    sessionId = "s_stream",
                    type = "permission_requested",
                    ts = "2026-11-23T15:01:10Z",
                    payload = buildJsonObject {
                        put("request_id", "req_gate_1")
                        put("tool_call", buildJsonObject { put("kind", "execute"); put("title", "ls") })
                    },
                ),
            )
            assertEquals("Gate count transition must sync once", 2, startOrUpdateCalls.get())
        } finally {
            dbHelper.close()
        }
    }

    @Test
    fun `GravityWellKeepaliveService reactively connects and tears down fallback WS on shellStreamActiveFlow transitions`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbHelper = GravityWellDbHelper(context, File(tempFolder.root, "fgs_handoff.db").absolutePath)
        val cacheStore = OfflineCacheStore(dbHelper)
        val tokenVault = TokenVault(File(tempFolder.root, "fgs_handoff_vault.json"), TestAesGcmEngine())
        val prefs = KeepalivePreferences.get(context)
        prefs.setKeepaliveEnabled(true)
        GravityWellRuntime.setForTest(GravityWellRuntime(dbHelper, cacheStore, tokenVault))
        AppForegroundTracker.setShellStreamActive(true)

        val h1 = CachedHost(
            hostId = "h_handoff",
            label = "aurora-handoff",
            baseUrl = "http://127.0.0.1:1/",
            defaultCwd = "/home/jason",
            isActive = true,
            createdAt = "2026-11-23T14:00:00Z",
            lastVerifiedAt = "2026-11-23T14:00:00Z",
        )
        cacheStore.upsertHost(h1)
        tokenVault.putToken("h_handoff", "gw_tok_handoff")
        cacheStore.upsertSession(
            CachedSession(
                hostId = "h_handoff",
                sessionId = "s_handoff",
                title = "Handoff Session",
                cwd = "/home/jason",
                status = "running",
                isLive = true,
                updatedAt = "2026-11-23T15:00:00Z",
            ),
        )

        val service = Robolectric.buildService(GravityWellKeepaliveService::class.java).create().get()
        try {
            service.onStartCommand(
                Intent(context, GravityWellKeepaliveService::class.java).apply {
                    action = GravityWellKeepaliveService.ACTION_START_OR_UPDATE
                    putExtra(GravityWellKeepaliveService.EXTRA_HOST_ID, "h_handoff")
                },
                0,
                1,
            )
            assertTrue(GravityWellKeepaliveService.isRunning)
            assertFalse("Fallback WS must stay inactive while shellStreamActive is true", service.isFallbackWsActive)

            // Transition shellStreamActive true -> false WITHOUT a second onStartCommand
            AppForegroundTracker.setShellStreamActive(false)
            val activateDeadline = System.currentTimeMillis() + 3_000L
            while (!service.isFallbackWsActive && System.currentTimeMillis() < activateDeadline) {
                Thread.sleep(20L)
            }
            assertTrue("Fallback WS must activate reactively when shellStreamActive becomes false", service.isFallbackWsActive)

            // Transition shellStreamActive false -> true tears down fallback WS
            AppForegroundTracker.setShellStreamActive(true)
            val teardownDeadline = System.currentTimeMillis() + 3_000L
            while (service.isFallbackWsActive && System.currentTimeMillis() < teardownDeadline) {
                Thread.sleep(20L)
            }
            assertFalse("Fallback WS must tear down reactively when shellStreamActive becomes true", service.isFallbackWsActive)
        } finally {
            service.onDestroy()
            AppForegroundTracker.setShellStreamActive(false)
            GravityWellRuntime.setForTest(null)
            prefs.setKeepaliveEnabled(true)
            dbHelper.close()
        }
    }

    private fun findAppDir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (dir != null) {
            if (File(dir, "src/main/AndroidManifest.xml").isFile) return dir
            if (File(dir, "app/src/main/AndroidManifest.xml").isFile) return File(dir, "app")
            dir = dir.parentFile
        }
        error("Could not locate android/app module root")
    }
}
