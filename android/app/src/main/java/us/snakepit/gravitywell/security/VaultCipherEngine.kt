package us.snakepit.gravitywell.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class EncryptedPayload(
    val iv: ByteArray,
    val ciphertext: ByteArray,
)

interface VaultCipherEngine {
    fun encrypt(plaintext: ByteArray): EncryptedPayload

    fun decrypt(payload: EncryptedPayload): ByteArray
}

/**
 * AndroidKeyStore-backed AES-256-GCM cipher engine (docs/SPEC.md D13).
 * Omits caller GCMParameterSpec on encrypt so KeyStore generates a fresh
 * randomized 12-byte IV on every call.
 */
class AndroidKeyStoreCipherEngine(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : VaultCipherEngine {

    override fun encrypt(plaintext: ByteArray): EncryptedPayload {
        val secretKey = getOrCreateSecretKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val ciphertext = cipher.doFinal(plaintext)
        val iv = cipher.iv
        check(iv != null && iv.size == GCM_IV_BYTES) {
            "Expected $GCM_IV_BYTES-byte GCM IV from KeyStore, got ${iv?.size}"
        }
        return EncryptedPayload(iv = iv, ciphertext = ciphertext)
    }

    override fun decrypt(payload: EncryptedPayload): ByteArray {
        require(payload.iv.size == GCM_IV_BYTES) {
            "Invalid GCM IV length: ${payload.iv.size} (expected $GCM_IV_BYTES)"
        }
        val secretKey = getOrCreateSecretKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, payload.iv))
        return cipher.doFinal(payload.ciphertext)
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply { load(null) }
        val existing = keyStore.getKey(keyAlias, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE_PROVIDER,
        )
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(AES_KEY_BITS)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    companion object {
        const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val DEFAULT_KEY_ALIAS = "gravity_well_host_tokens_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val AES_KEY_BITS = 256
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
