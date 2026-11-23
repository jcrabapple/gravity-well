package us.snakepit.gravitywell.ui.console

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

sealed interface SniffedAttachmentResult {
    data class Valid(
        val mimeType: String,
        val sizeBytes: Long,
    ) : SniffedAttachmentResult

    data class Rejected(
        val status: Int,
        val code: String,
        val message: String,
    ) : SniffedAttachmentResult
}

/**
 * Client-side magic-byte MIME sniffer and 10 MiB pre-flight validator matching
 * docs/SPEC.md D9 and gateway/gravity_well_gateway/storage.py.
 */
object AttachmentSniffer {
    const val MAX_ATTACHMENT_BYTES: Long = 10L * 1024L * 1024L // 10 MiB

    private val disallowedExts: Set<String> = setOf(
        ".exe", ".dll", ".so", ".dylib", ".elf", ".bin", ".com", ".msi",
        ".apk", ".jar", ".class", ".pyc", ".pyo", ".wasm",
        ".zip", ".tar", ".gz", ".7z",
    )

    private val binaryExtToMime: Map<String, String> = mapOf(
        ".png" to "image/png",
        ".jpg" to "image/jpeg",
        ".jpeg" to "image/jpeg",
        ".gif" to "image/gif",
        ".webp" to "image/webp",
        ".pdf" to "application/pdf",
    )

    private val textExtToMime: Map<String, String> = mapOf(
        ".txt" to "text/plain",
        ".md" to "text/markdown",
        ".json" to "application/json",
        ".jsonl" to "application/x-ndjson",
        ".py" to "text/x-python",
        ".kt" to "text/x-kotlin",
        ".kts" to "text/x-kotlin",
        ".js" to "text/javascript",
        ".ts" to "text/typescript",
        ".tsx" to "text/typescript",
        ".html" to "text/html",
        ".css" to "text/css",
        ".xml" to "application/xml",
        ".yaml" to "application/yaml",
        ".yml" to "application/yaml",
        ".toml" to "application/toml",
        ".sh" to "text/x-shellscript",
        ".sql" to "application/sql",
        ".csv" to "text/csv",
        ".log" to "text/plain",
    )

    fun validateSizeOnly(totalBytes: Long): SniffedAttachmentResult.Rejected? {
        if (totalBytes <= 0L) {
            return SniffedAttachmentResult.Rejected(
                status = 400,
                code = "bad_request",
                message = "Attachment file must not be empty.",
            )
        }
        if (totalBytes > MAX_ATTACHMENT_BYTES) {
            return SniffedAttachmentResult.Rejected(
                status = 413,
                code = "payload_too_large",
                message = "Attachment exceeds 10 MiB limit.",
            )
        }
        return null
    }

    fun sniffAndValidate(
        data: ByteArray,
        filename: String,
        declaredContentType: String? = null,
    ): SniffedAttachmentResult {
        validateSizeOnly(data.size.toLong())?.let { return it }

        val ext = extractExtension(filename)
        if (ext in disallowedExts || hasDisallowedBinarySignature(data)) {
            return SniffedAttachmentResult.Rejected(
                status = 415,
                code = "unsupported_media_type",
                message = "Executable and archive binary attachments are not supported.",
            )
        }

        val declaredBase = declaredContentType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            ?.let { if (it == "image/jpg") "image/jpeg" else it }
            .orEmpty()

        val expectedBinaryMime = binaryExtToMime[ext]
            ?: declaredBase.takeIf { it in binaryExtToMime.values }
        val sniffedBinary = sniffBinaryMagic(data)

        if (expectedBinaryMime != null) {
            if (sniffedBinary != expectedBinaryMime) {
                return SniffedAttachmentResult.Rejected(
                    status = 415,
                    code = "unsupported_media_type",
                    message = "Magic bytes do not match expected $expectedBinaryMime.",
                )
            }
            if (declaredBase.isNotEmpty() &&
                declaredBase != "application/octet-stream" &&
                declaredBase != "multipart/form-data" &&
                declaredBase != sniffedBinary
            ) {
                return SniffedAttachmentResult.Rejected(
                    status = 415,
                    code = "unsupported_media_type",
                    message = "Declared type $declaredBase mismatches magic bytes $sniffedBinary.",
                )
            }
            return SniffedAttachmentResult.Valid(
                mimeType = sniffedBinary,
                sizeBytes = data.size.toLong(),
            )
        }

        if (sniffedBinary != null) {
            if (ext in textExtToMime || declaredBase.startsWith("text/")) {
                return SniffedAttachmentResult.Rejected(
                    status = 415,
                    code = "unsupported_media_type",
                    message = "Binary media content mismatches declared text file type.",
                )
            }
            return SniffedAttachmentResult.Valid(
                mimeType = sniffedBinary,
                sizeBytes = data.size.toLong(),
            )
        }

        if (data.any { it == 0.toByte() }) {
            return SniffedAttachmentResult.Rejected(
                status = 415,
                code = "unsupported_media_type",
                message = "Unsupported binary file format (contains NUL bytes).",
            )
        }

        if (!isValidUtf8(data)) {
            return SniffedAttachmentResult.Rejected(
                status = 415,
                code = "unsupported_media_type",
                message = "Text attachment must be valid UTF-8.",
            )
        }

        return SniffedAttachmentResult.Valid(
            mimeType = textExtToMime[ext] ?: "text/plain",
            sizeBytes = data.size.toLong(),
        )
    }

    internal fun sniffBinaryMagic(data: ByteArray): String? {
        if (startsWith(data, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return "image/png"
        }
        if (startsWith(data, 0xFF, 0xD8, 0xFF)) {
            return "image/jpeg"
        }
        if (startsWithAscii(data, "GIF87a") || startsWithAscii(data, "GIF89a")) {
            return "image/gif"
        }
        if (data.size >= 12 &&
            startsWithAscii(data, "RIFF") &&
            data[8] == 'W'.code.toByte() &&
            data[9] == 'E'.code.toByte() &&
            data[10] == 'B'.code.toByte() &&
            data[11] == 'P'.code.toByte()
        ) {
            return "image/webp"
        }
        if (startsWithAscii(data, "%PDF-")) {
            return "application/pdf"
        }
        return null
    }

    private fun hasDisallowedBinarySignature(data: ByteArray): Boolean {
        return startsWith(data, 0x7F, 0x45, 0x4C, 0x46) || // ELF
            startsWithAscii(data, "MZ") || // PE/COFF
            startsWith(data, 0xFE, 0xED, 0xFA, 0xCE) || // Mach-O 32
            startsWith(data, 0xFE, 0xED, 0xFA, 0xCF) || // Mach-O 64
            startsWith(data, 0xCF, 0xFA, 0xED, 0xFE) || // Mach-O LE
            startsWith(data, 0xCA, 0xFE, 0xBA, 0xBE) || // Java class / fat binary
            startsWith(data, 0x50, 0x4B, 0x03, 0x04) || // ZIP / APK / JAR
            startsWith(data, 0x00, 0x61, 0x73, 0x6D) || // WASM
            startsWithAscii(data, "SQLite format 3\u0000") ||
            startsWith(data, 0x1F, 0x8B) // GZIP
    }

    private fun isValidUtf8(data: ByteArray): Boolean {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return runCatching { decoder.decode(ByteBuffer.wrap(data)) }.isSuccess
    }

    private fun extractExtension(filename: String): String {
        val leaf = filename.substringAfterLast('/').substringAfterLast('\\')
        val dot = leaf.lastIndexOf('.')
        return if (dot >= 0) leaf.substring(dot).lowercase() else ""
    }

    private fun startsWith(data: ByteArray, vararg bytes: Int): Boolean {
        if (data.size < bytes.size) return false
        for (i in bytes.indices) {
            if (data[i] != bytes[i].toByte()) return false
        }
        return true
    }

    private fun startsWithAscii(data: ByteArray, prefix: String): Boolean {
        val p = prefix.toByteArray(StandardCharsets.US_ASCII)
        if (data.size < p.size) return false
        for (i in p.indices) {
            if (data[i] != p[i]) return false
        }
        return true
    }
}
