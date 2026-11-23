package us.snakepit.gravitywell.net

import java.io.IOException
import javax.net.ssl.SSLException
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Attaches Authorization: Bearer <token> to authenticated gateway requests.
 * Leaves explicit per-request Authorization headers intact and skips /api/health.
 */
class AuthHeaderInterceptor(
    private val tokenProvider: () -> String?,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("Authorization") != null) {
            return chain.proceed(request)
        }
        val path = request.url.encodedPath
        if (path == "/api/health" || path.endsWith("/api/health")) {
            return chain.proceed(request)
        }
        val token = tokenProvider()?.trim().orEmpty()
        if (token.isEmpty()) {
            return chain.proceed(request)
        }
        val authenticated = request.newBuilder()
            .header("Authorization", "Bearer $token")
            .build()
        return chain.proceed(authenticated)
    }
}

/**
 * Maps non-2xx HTTP JSON {"error": {...}} responses into GatewayHttpException and
 * normalizes TLS/IO exceptions into GatewayTlsException / GatewayNetworkException.
 * Note: allows 101 Switching Protocols through for WebSocket upgrades.
 */
class GatewayErrorInterceptor(
    private val json: Json = NetworkModule.defaultJson,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = try {
            chain.proceed(chain.request())
        } catch (ssl: SSLException) {
            throw GatewayTlsException(
                message = ssl.message ?: "TLS handshake or certificate verification failed",
                cause = ssl,
            )
        } catch (gw: GatewayException) {
            throw gw
        } catch (io: IOException) {
            throw GatewayNetworkException(
                message = io.message ?: "Network request failed",
                cause = io,
            )
        }

        if (response.code < 400) {
            return response
        }

        val status = response.code
        val fallbackMessage = response.message.ifBlank { "HTTP $status" }
        val rawBody = try {
            response.body.string()
        } catch (_: IOException) {
            ""
        } finally {
            response.close()
        }

        val parsed = if (rawBody.isNotBlank()) {
            runCatching { json.decodeFromString<GatewayErrorEnvelope>(rawBody).error }.getOrNull()
        } else {
            null
        }

        if (parsed != null) {
            throw GatewayHttpException(
                status = if (parsed.status > 0) parsed.status else status,
                code = parsed.code.ifBlank { GatewayStatusCodes.defaultCodeForStatus(status) },
                message = parsed.message.ifBlank { fallbackMessage },
                details = parsed.details,
            )
        }

        throw GatewayHttpException(
            status = status,
            code = GatewayStatusCodes.defaultCodeForStatus(status),
            message = fallbackMessage,
        )
    }
}
