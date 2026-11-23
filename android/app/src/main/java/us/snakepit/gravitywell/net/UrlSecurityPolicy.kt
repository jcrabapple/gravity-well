package us.snakepit.gravitywell.net

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class ValidatedBaseUrl(
    val httpUrl: HttpUrl,
    val isCleartext: Boolean,
)

object UrlSecurityPolicy {

    /**
     * Validates and normalizes a gateway base URL.
     * Release builds strictly reject http:// URLs; debug builds permit http://
     * while flagging isCleartext = true for UI warning display.
     */
    fun validateBaseUrl(
        rawUrl: String,
        isDebug: Boolean,
    ): Result<ValidatedBaseUrl> {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Enter a gateway URL"))
        }
        val normalizedInput = if (trimmed.endsWith("/")) trimmed else "$trimmed/"
        val parsed = normalizedInput.toHttpUrlOrNull()
            ?: return Result.failure(IllegalArgumentException("Malformed gateway URL"))

        if (parsed.scheme != "https" && parsed.scheme != "http") {
            return Result.failure(IllegalArgumentException("Unsupported URL scheme: ${parsed.scheme}"))
        }
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            return Result.failure(IllegalArgumentException("URL must not embed credentials"))
        }
        if (parsed.encodedQuery != null || parsed.fragment != null) {
            return Result.failure(IllegalArgumentException("Base URL must not include query or fragment"))
        }

        val cleartext = !parsed.isHttps
        if (cleartext && !isDebug) {
            return Result.failure(
                IllegalArgumentException(
                    "HTTPS required in release builds (Tailscale Serve / TLS proxy)",
                ),
            )
        }

        val cleanBase = parsed.newBuilder()
            .encodedPath(if (parsed.encodedPath.endsWith("/")) parsed.encodedPath else "${parsed.encodedPath}/")
            .build()

        return Result.success(ValidatedBaseUrl(httpUrl = cleanBase, isCleartext = cleartext))
    }

    /**
     * Builds a ws:// or wss:// URL from baseUrl using ONLY a single-use ?ticket=
     * and optional ?after=<cursor> (never permanent bearer tokens in query strings).
     */
    fun buildWebSocketUrl(
        baseUrl: HttpUrl,
        endpointPath: String,
        ticket: String,
        afterCursor: Long? = null,
    ): String {
        require(ticket.isNotBlank()) { "WS ticket must not be blank" }
        val cleanEndpoint = endpointPath.trimStart('/')
        val resolved = baseUrl.resolve(cleanEndpoint)
            ?: throw IllegalArgumentException("Invalid WS endpoint path: $endpointPath")

        val urlBuilder = resolved.newBuilder()
            .addQueryParameter("ticket", ticket)
        if (afterCursor != null && afterCursor >= 0L) {
            urlBuilder.addQueryParameter("after", afterCursor.toString())
        }
        val httpBuilt = urlBuilder.build().toString()
        return when {
            httpBuilt.startsWith("https://") -> "wss://${httpBuilt.removePrefix("https://")}"
            httpBuilt.startsWith("http://") -> "ws://${httpBuilt.removePrefix("http://")}"
            else -> throw IllegalArgumentException("Unexpected scheme in WS URL: $httpBuilt")
        }
    }
}
