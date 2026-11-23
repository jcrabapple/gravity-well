package us.snakepit.gravitywell.ui.hosts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import us.snakepit.gravitywell.cache.GravityWellDbHelper
import us.snakepit.gravitywell.cache.OfflineCacheStore
import us.snakepit.gravitywell.net.HostVerificationReport
import us.snakepit.gravitywell.net.HostVerifier
import us.snakepit.gravitywell.net.NetworkModule
import us.snakepit.gravitywell.net.VerificationLegId
import us.snakepit.gravitywell.net.VerificationLegStatus
import us.snakepit.gravitywell.security.AndroidKeyStoreCipherEngine
import us.snakepit.gravitywell.security.EncryptedPayload
import us.snakepit.gravitywell.security.TokenVault
import us.snakepit.gravitywell.security.VaultCipherEngine

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HostOnboardingControllerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var dbFile: File
    private lateinit var dbHelper: GravityWellDbHelper
    private lateinit var cacheStore: OfflineCacheStore
    private lateinit var tokenVault: TokenVault
    private lateinit var controllerScope: CoroutineScope

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

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        dbFile = File(tempFolder.root, "onboarding_test.db")
        dbHelper = GravityWellDbHelper(context, dbFile.absolutePath)
        cacheStore = OfflineCacheStore(dbHelper)
        tokenVault = TokenVault(File(tempFolder.root, "hosts_vault.json"), TestAesGcmEngine())
        controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        controllerScope.cancel()
        dbHelper.close()
        server.close()
    }

    @Test
    fun `release mode rejects cleartext http while debug mode surfaces amber warning`() {
        val verifier = HostVerifier(NetworkModule.createOkHttpClient())
        val releaseCtrl = HostOnboardingController(
            verifier = verifier,
            cacheStore = cacheStore,
            tokenVault = tokenVault,
            scope = controllerScope,
            isDebugBuild = false,
        )
        releaseCtrl.updateBaseUrl("http://192.168.1.10:9340")
        assertEquals(
            "HTTPS required in release builds (Tailscale Serve / TLS proxy)",
            releaseCtrl.state.value.urlValidationError,
        )
        assertFalse(releaseCtrl.state.value.showDebugCleartextWarning)

        val debugCtrl = HostOnboardingController(
            verifier = verifier,
            cacheStore = cacheStore,
            tokenVault = tokenVault,
            scope = controllerScope,
            isDebugBuild = true,
        )
        debugCtrl.updateBaseUrl("http://192.168.1.10:9340")
        assertNull(debugCtrl.state.value.urlValidationError)
        assertTrue(debugCtrl.state.value.showDebugCleartextWarning)
    }

    @Test
    fun `three-leg preflight gates save button, invalidates on edit, and stores token only in vault`() = runBlocking {
        val verifier = HostVerifier(NetworkModule.createOkHttpClient(), wsHelloTimeoutMs = 3_000L)
        val ctrl = HostOnboardingController(
            verifier = verifier,
            cacheStore = cacheStore,
            tokenVault = tokenVault,
            scope = controllerScope,
            isDebugBuild = true,
        )

        val rawSecret = "gw_super_secret_token_do_not_leak_9911"
        ctrl.updateBaseUrl(server.url("/").toString())
        ctrl.updateBearerToken(rawSecret)
        assertFalse(ctrl.state.value.canSaveVerifiedHost)
        assertNull("Cannot save before preflight passes", ctrl.saveVerifiedHost())
        assertTrue("Vault must remain empty before Leg 3 succeeds", tokenVault.listHostIds().isEmpty())

        // Enqueue 3 passing legs
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"status":"ok","agy_version":"1.5.15","protocol_version":1}""")
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"gateway_version":"0.1.0","agy_version":"1.5.15","hostname":"aurora","default_cwd":"/home/jason","capabilities":{"resume_session":true,"fork":false,"gateway_title_override":true,"elicitation":true}}""")
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(201)
                .body("""{"ticket":"gwt_ok_1","ttl_seconds":30}""")
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                            webSocket.send("""{"frame":"hello","gateway_version":"0.1.0","agy_version":"1.5.15","server_time":"2026-11-23T15:00:00Z","latest_cursor":77,"capabilities":{"fork":false}}""")
                        }
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            webSocket.close(1000, null)
                        }
                    },
                )
                .build(),
        )

        val done = CompletableDeferred<HostVerificationReport?>()
        ctrl.runPreflightCheck { done.complete(it) }
        val report = withTimeout(5_000L) { done.await() }
        assertNotNull(report)
        assertTrue(report!!.isAllVerified)
        assertTrue(ctrl.state.value.canSaveVerifiedHost)
        assertEquals("aurora", ctrl.state.value.label)
        assertEquals(VerificationLegStatus.VERIFIED, ctrl.state.value.legs[VerificationLegId.WS_TICKET_HELLO]?.status)

        // Mutating token after verification MUST invalidate canSaveVerifiedHost
        ctrl.updateBearerToken("tampered_unverified_token")
        assertFalse("Editing token after preflight must revoke save gate", ctrl.state.value.canSaveVerifiedHost)
        assertNull(ctrl.saveVerifiedHost())

        // Re-verify with genuine token and save
        ctrl.updateBearerToken(rawSecret)
        server.enqueue(MockResponse.Builder().code(200).body("""{"status":"ok","agy_version":"1.5.15","protocol_version":1}""").build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"hostname":"aurora","default_cwd":"/home/jason","agy_version":"1.5.15","capabilities":{"fork":false}}""").build())
        server.enqueue(MockResponse.Builder().code(201).body("""{"ticket":"gwt_ok_2","ttl_seconds":30}""").build())
        server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                            webSocket.send("""{"frame":"hello","latest_cursor":88,"capabilities":{"fork":false}}""")
                        }
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            webSocket.close(1000, null)
                        }
                    },
                )
                .build(),
        )
        val done2 = CompletableDeferred<HostVerificationReport?>()
        ctrl.runPreflightCheck { done2.complete(it) }
        withTimeout(5_000L) { done2.await() }
        assertTrue(ctrl.state.value.canSaveVerifiedHost)

        val saved = ctrl.saveVerifiedHost()
        assertNotNull(saved)
        assertEquals("aurora", saved!!.label)
        assertEquals(88L, saved.lastCursor)
        assertEquals(rawSecret, tokenVault.getToken(saved.hostId))

        // Verify SQLite DB file on disk contains zero bytes of the bearer token
        dbHelper.writableDatabase.rawQuery("PRAGMA wal_checkpoint(FULL);", null).close()
        val sqliteBytes = dbFile.readBytes().decodeToString()
        assertFalse("SQLite cache must never contain bearer token", sqliteBytes.contains(rawSecret))
    }
}
