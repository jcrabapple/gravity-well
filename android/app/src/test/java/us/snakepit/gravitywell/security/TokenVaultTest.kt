package us.snakepit.gravitywell.security

import java.io.File
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TokenVaultTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /**
     * JVM-compatible AES-256-GCM cipher engine mirroring AndroidKeyStoreCipherEngine:
     * omits caller GCMParameterSpec on encrypt so JCA generates a random 12-byte IV,
     * and passes GCMParameterSpec(128, iv) on decrypt.
     */
    private class JvmAesGcmCipherEngine : VaultCipherEngine {
        private val key: SecretKey = KeyGenerator.getInstance("AES").apply {
            init(AndroidKeyStoreCipherEngine.AES_KEY_BITS)
        }.generateKey()

        override fun encrypt(plaintext: ByteArray): EncryptedPayload {
            val cipher = Cipher.getInstance(AndroidKeyStoreCipherEngine.TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val ciphertext = cipher.doFinal(plaintext)
            val iv = cipher.iv
            check(iv != null && iv.size == AndroidKeyStoreCipherEngine.GCM_IV_BYTES)
            return EncryptedPayload(iv = iv, ciphertext = ciphertext)
        }

        override fun decrypt(payload: EncryptedPayload): ByteArray {
            require(payload.iv.size == AndroidKeyStoreCipherEngine.GCM_IV_BYTES)
            val cipher = Cipher.getInstance(AndroidKeyStoreCipherEngine.TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(AndroidKeyStoreCipherEngine.GCM_TAG_BITS, payload.iv),
            )
            return cipher.doFinal(payload.ciphertext)
        }
    }

    @Test
    fun `keystore engine constants match D13 AES-GCM specification`() {
        assertEquals("AES/GCM/NoPadding", AndroidKeyStoreCipherEngine.TRANSFORMATION)
        assertEquals(256, AndroidKeyStoreCipherEngine.AES_KEY_BITS)
        assertEquals(12, AndroidKeyStoreCipherEngine.GCM_IV_BYTES)
        assertEquals(128, AndroidKeyStoreCipherEngine.GCM_TAG_BITS)
    }

    @Test
    fun `multi-host store retrieves removes and isolates tokens without plaintext on disk`() {
        val vaultFile = File(tempFolder.root, TokenVault.DEFAULT_VAULT_FILENAME)
        val vault = TokenVault(vaultFile, JvmAesGcmCipherEngine())

        val tokenAurora = "gw_tok_aurora_secret_alpha_9981"
        val tokenBench = "gw_tok_bench_secret_beta_4412"

        vault.putToken("aurora", tokenAurora)
        vault.putToken("bench", tokenBench)

        assertEquals(setOf("aurora", "bench"), vault.listHostIds())
        assertTrue(vault.hasToken("aurora"))
        assertEquals(tokenAurora, vault.getToken("aurora"))
        assertEquals(tokenBench, vault.getToken("bench"))
        assertNull(vault.getToken("unknown"))

        val diskContents = vaultFile.readText()
        assertFalse("Raw token must never appear in vault file", diskContents.contains(tokenAurora))
        assertFalse("Raw token must never appear in vault file", diskContents.contains(tokenBench))
        assertFalse("toString must never leak secrets", vault.toString().contains(tokenAurora))

        assertTrue(vault.removeToken("aurora"))
        assertFalse(vault.hasToken("aurora"))
        assertNull(vault.getToken("aurora"))
        assertEquals(tokenBench, vault.getToken("bench"))

        vault.clearAll()
        assertTrue(vault.listHostIds().isEmpty())
    }

    @Test
    fun `repeated encryption of identical token uses fresh random 12-byte IV`() {
        val vaultFile = File(tempFolder.root, TokenVault.DEFAULT_VAULT_FILENAME)
        val engine = JvmAesGcmCipherEngine()
        val vault = TokenVault(vaultFile, engine)

        val secret = "gw_tok_identical_value_12345"
        vault.putToken("host-a", secret)
        val firstJson = Json.parseToJsonElement(vaultFile.readText()).jsonObject
        val firstEntry = firstJson["entries"]!!.jsonObject["host-a"]!!.jsonObject
        val iv1 = firstEntry["iv"]!!.jsonPrimitive.content
        val ct1 = firstEntry["ciphertext"]!!.jsonPrimitive.content

        vault.putToken("host-a", secret)
        val secondJson = Json.parseToJsonElement(vaultFile.readText()).jsonObject
        val secondEntry = secondJson["entries"]!!.jsonObject["host-a"]!!.jsonObject
        val iv2 = secondEntry["iv"]!!.jsonPrimitive.content
        val ct2 = secondEntry["ciphertext"]!!.jsonPrimitive.content

        assertNotEquals("Each encryption must generate a fresh random IV", iv1, iv2)
        assertNotEquals("Distinct IVs must produce distinct ciphertexts", ct1, ct2)
        assertEquals(secret, vault.getToken("host-a"))
    }

    @Test(expected = AEADBadTagException::class)
    fun `tampered ciphertext fails GCM tag verification`() {
        val vaultFile = File(tempFolder.root, TokenVault.DEFAULT_VAULT_FILENAME)
        val engine = JvmAesGcmCipherEngine()
        val encrypted = engine.encrypt("gw_tok_tamper_check".toByteArray())
        val corrupted = encrypted.ciphertext.copyOf()
        corrupted[0] = (corrupted[0].toInt() xor 0xFF).toByte()
        engine.decrypt(EncryptedPayload(iv = encrypted.iv, ciphertext = corrupted))
    }
}
