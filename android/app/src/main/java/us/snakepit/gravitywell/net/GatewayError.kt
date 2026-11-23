package us.snakepit.gravitywell.net

import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class GatewayErrorPayload(
    val code: String,
    val message: String,
    val status: Int,
    val details: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class GatewayErrorEnvelope(
    val error: GatewayErrorPayload,
)

sealed class GatewayException(
    override val message: String,
    override val cause: Throwable? = null,
) : IOException(message, cause)

class GatewayHttpException(
    val status: Int,
    val code: String,
    override val message: String,
    val details: JsonObject = JsonObject(emptyMap()),
) : GatewayException(message)

class GatewayTlsException(
    override val message: String,
    override val cause: Throwable? = null,
) : GatewayException(message, cause)

class GatewayNetworkException(
    override val message: String,
    override val cause: Throwable? = null,
) : GatewayException(message, cause)

class GatewayProtocolException(
    override val message: String,
    override val cause: Throwable? = null,
) : GatewayException(message, cause)

object GatewayStatusCodes {
    val STATUS_TO_ERROR_CODE: Map<Int, String> = mapOf(
        400 to "bad_request",
        401 to "unauthorized",
        403 to "forbidden",
        404 to "not_found",
        409 to "conflict",
        413 to "payload_too_large",
        415 to "unsupported_media_type",
        422 to "validation_error",
        501 to "acp_method_unsupported",
        502 to "acp_upstream_error",
        503 to "service_unavailable",
    )

    fun defaultCodeForStatus(status: Int): String =
        STATUS_TO_ERROR_CODE[status] ?: "http_$status"
}
