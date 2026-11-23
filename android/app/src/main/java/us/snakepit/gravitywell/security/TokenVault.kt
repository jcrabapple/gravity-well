package us.snakepit.gravitywell.security

import android.content.Context
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class VaultFileRecord(
    val version: Int = 1,
    val entries: Map<String, VaultCipherRecord> = emptyMap(),
)

@Serializable
internal data class VaultCipherRecord(
    val iv: String,
    val ciphertext: String,
)

/**
 * Multi-host bearer token vault encrypted via AndroidKeyStore AES/GCM/NoPadding.
 * Stores only base64(iv, ciphertext) in app-private storage (hosts_vault.json),
 * never in SQLite or SharedPreferences plaintext.
 */
class TokenVault(
    private val vaultFile: File,
    private val cipherEngine: VaultCipherEngine,
) {
    constructor(
        context: Context,
        keyAlias: String = AndroidKeyStoreCipherEngine.DEFAULT_KEY_ALIAS,
    ) : this(
        vaultFile = File(context.filesDir, DEFAULT_VAULT_FILENAME),
        cipherEngine = AndroidKeyStoreCipherEngine(keyAlias),
    )

    private val lock = Any()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    fun putToken(hostId: String, token: String) {
        val cleanHostId = hostId.trim()
        require(cleanHostId.isNotEmpty()) { "hostId must not be blank" }
        require(token.isNotEmpty()) { "token must not be empty" }

        val encrypted = cipherEngine.encrypt(token.toByteArray(StandardCharsets.UTF_8))
        val encodedIv = Base64.getEncoder().encodeToString(encrypted.iv)
        val encodedCipher = Base64.getEncoder().encodeToString(encrypted.ciphertext)

        synchronized(lock) {
            val current = readRecordLocked()
            val updated = current.entries.toMutableMap().apply {
                put(cleanHostId, VaultCipherRecord(iv = encodedIv, ciphertext = encodedCipher))
            }
            writeRecordLocked(current.copy(entries = updated))
        }
    }

    fun getToken(hostId: String): String? {
        val cleanHostId = hostId.trim()
        if (cleanHostId.isEmpty()) return null
        val entry = synchronized(lock) { readRecordLocked().entries[cleanHostId] } ?: return null
        val iv = Base64.getDecoder().decode(entry.iv)
        val ciphertext = Base64.getDecoder().decode(entry.ciphertext)
        val plaintext = cipherEngine.decrypt(EncryptedPayload(iv = iv, ciphertext = ciphertext))
        return String(plaintext, StandardCharsets.UTF_8)
    }

    fun hasToken(hostId: String): Boolean {
        val cleanHostId = hostId.trim()
        if (cleanHostId.isEmpty()) return false
        return synchronized(lock) {
            readRecordLocked().entries.containsKey(cleanHostId)
        }
    }

    fun removeToken(hostId: String): Boolean {
        val cleanHostId = hostId.trim()
        if (cleanHostId.isEmpty()) return false
        return synchronized(lock) {
            val current = readRecordLocked()
            if (!current.entries.containsKey(cleanHostId)) {
                false
            } else {
                val updated = current.entries.toMutableMap().apply { remove(cleanHostId) }
                writeRecordLocked(current.copy(entries = updated))
                true
            }
        }
    }

    fun listHostIds(): Set<String> = synchronized(lock) {
        readRecordLocked().entries.keys.toSet()
    }

    fun clearAll() {
        synchronized(lock) {
            if (vaultFile.exists()) {
                vaultFile.delete()
            }
        }
    }

    private fun readRecordLocked(): VaultFileRecord {
        if (!vaultFile.isFile) return VaultFileRecord()
        val raw = vaultFile.readText(StandardCharsets.UTF_8)
        if (raw.isBlank()) return VaultFileRecord()
        return json.decodeFromString<VaultFileRecord>(raw)
    }

    private fun writeRecordLocked(record: VaultFileRecord) {
        val parent = vaultFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        val tmp = File(parent ?: File("."), ".${vaultFile.name}.tmp")
        val payload = json.encodeToString(record)
        tmp.writeText(payload, StandardCharsets.UTF_8)
        restrictOwnerOnlyPermissions(tmp)
        try {
            Files.move(
                tmp.toPath(),
                vaultFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                tmp.toPath(),
                vaultFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        restrictOwnerOnlyPermissions(vaultFile)
    }

    private fun restrictOwnerOnlyPermissions(file: File) {
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
    }

    override fun toString(): String = "TokenVault(hosts=${listHostIds().size})"

    companion object {
        const val DEFAULT_VAULT_FILENAME = "hosts_vault.json"
    }
}
