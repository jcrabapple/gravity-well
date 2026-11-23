package us.snakepit.gravitywell.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

@Serializable
data class HealthResponse(
    val status: String,
    val service: String = "",
    @SerialName("gateway_version") val gatewayVersion: String = "",
    @SerialName("agy_version") val agyVersion: String = "",
    @SerialName("protocol_version") val protocolVersion: Int = 0,
    @SerialName("auth_required") val authRequired: Boolean = true,
    @SerialName("uptime_seconds") val uptimeSeconds: Long = 0L,
)

@Serializable
data class CapabilitiesDto(
    @SerialName("load_session") val loadSession: Boolean = false,
    @SerialName("resume_session") val resumeSession: Boolean = false,
    val fork: Boolean = false,
    @SerialName("acp_rename") val acpRename: Boolean = false,
    @SerialName("gateway_title_override") val gatewayTitleOverride: Boolean = false,
    @SerialName("acp_delete") val acpDelete: Boolean = false,
    @SerialName("set_additional_directories_live") val setAdditionalDirectoriesLive: Boolean = false,
    @SerialName("spawn_add_dir") val spawnAddDir: Boolean = false,
    val elicitation: Boolean = false,
    @SerialName("remote_control") val remoteControl: Boolean = false,
)

@Serializable
data class ConfigOptionChoiceDto(
    val value: String,
    val name: String = "",
    val description: String? = null,
)

@Serializable
data class ConfigOptionDto(
    val id: String,
    val name: String = "",
    val category: String = "",
    val currentValue: String = "",
    val options: List<ConfigOptionChoiceDto> = emptyList(),
)

@Serializable
data class GatewayInfoResponse(
    @SerialName("gateway_version") val gatewayVersion: String = "",
    @SerialName("agy_version") val agyVersion: String = "",
    @SerialName("acp_protocol_version") val acpProtocolVersion: Int = 1,
    val hostname: String = "",
    @SerialName("default_cwd") val defaultCwd: String = "",
    val capabilities: CapabilitiesDto = CapabilitiesDto(),
    @SerialName("default_config_options") val defaultConfigOptions: List<ConfigOptionDto> = emptyList(),
)

@Serializable
data class WsTicketResponse(
    val ticket: String,
    @SerialName("ttl_seconds") val ttlSeconds: Int = 30,
    @SerialName("expires_at") val expiresAt: String = "",
)

@Serializable
data class AvailableModelDto(
    @SerialName("model_id") val modelId: String,
    val name: String = "",
)

@Serializable
data class AvailableCommandDto(
    val name: String,
    val description: String = "",
)

@Serializable
data class UsageDto(
    val used: Long = 0L,
    val size: Long = 0L,
)

@Serializable
data class QueueItemDto(
    @SerialName("item_id") val itemId: String,
    val position: Int = 0,
    val text: String = "",
    @SerialName("attachment_ids") val attachmentIds: List<String> = emptyList(),
    val status: String = "queued",
    @SerialName("dispatch_on_settle") val dispatchOnSettle: Boolean = false,
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class PermissionOptionDto(
    @SerialName("optionId") val optionId: String = "",
    val name: String = "",
    val kind: String = "",
)

@Serializable
data class PendingPermissionDto(
    @SerialName("request_id") val requestId: String,
    @SerialName("tool_call") val toolCall: JsonObject = JsonObject(emptyMap()),
    val options: List<PermissionOptionDto> = emptyList(),
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class PendingQuestionDto(
    @SerialName("request_id") val requestId: String,
    val mode: String = "form",
    val message: String = "",
    @SerialName("requested_schema") val requestedSchema: JsonObject = JsonObject(emptyMap()),
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class SessionSnapshotDto(
    @SerialName("session_id") val sessionId: String,
    val title: String = "",
    @SerialName("title_source") val titleSource: String = "acp",
    val cwd: String = "",
    @SerialName("add_dirs") val addDirs: List<String> = emptyList(),
    val status: String = "idle",
    @SerialName("is_live") val isLive: Boolean = false,
    val agent: String? = null,
    val sandbox: Boolean = false,
    @SerialName("config_options") val configOptions: List<ConfigOptionDto> = emptyList(),
    @SerialName("available_models") val availableModels: List<AvailableModelDto> = emptyList(),
    @SerialName("available_commands") val availableCommands: List<AvailableCommandDto> = emptyList(),
    val usage: UsageDto = UsageDto(),
    @SerialName("pending_permissions") val pendingPermissions: List<PendingPermissionDto> = emptyList(),
    @SerialName("pending_questions") val pendingQuestions: List<PendingQuestionDto> = emptyList(),
    @SerialName("queued_followups") val queuedFollowups: List<QueueItemDto> = emptyList(),
    val cursor: Long = 0L,
    val seq: Long = 0L,
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class SessionListItemDto(
    @SerialName("session_id") val sessionId: String,
    val title: String = "",
    val preview: String = "",
    val cwd: String = "",
    @SerialName("is_live") val isLive: Boolean = false,
    val status: String = "archived",
    @SerialName("step_count") val stepCount: Int = 0,
    @SerialName("model_id") val modelId: String? = null,
    @SerialName("thought_level") val thoughtLevel: String? = null,
    @SerialName("mode_id") val modeId: String? = null,
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class SessionListResponse(
    val items: List<SessionListItemDto> = emptyList(),
    val total: Int = 0,
    val limit: Int = 50,
    val offset: Int = 0,
)

@Serializable
data class SessionEventDto(
    val frame: String = "event",
    val cursor: Long = 0L,
    val seq: Long = 0L,
    @SerialName("session_id") val sessionId: String? = null,
    val type: String = "",
    val ts: String = "",
    val payload: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class SessionEventsResponse(
    @SerialName("session_id") val sessionId: String,
    val after: Long = 0L,
    @SerialName("min_cursor_retained") val minCursorRetained: Long = 0L,
    @SerialName("latest_cursor") val latestCursor: Long = 0L,
    val truncated: Boolean = false,
    val events: List<SessionEventDto> = emptyList(),
)

@Serializable
data class CreateSessionRequest(
    val cwd: String,
    @SerialName("add_dirs") val addDirs: List<String> = emptyList(),
    val title: String? = null,
    val model: String? = null,
    @SerialName("thought_level") val thoughtLevel: String? = null,
    val mode: String? = null,
    val agent: String? = null,
    val sandbox: Boolean = false,
)

@Serializable
data class ResumeSessionRequest(
    val cwd: String? = null,
    @SerialName("load_history") val loadHistory: Boolean = false,
    @SerialName("add_dirs") val addDirs: List<String> = emptyList(),
    val sandbox: Boolean = false,
)

@Serializable
data class RenameSessionRequest(
    val title: String,
)

@Serializable
data class UpdateConfigRequest(
    val model: String? = null,
    @SerialName("thought_level") val thoughtLevel: String? = null,
    val mode: String? = null,
)

@Serializable
data class PromptPartDto(
    val type: String = "text",
    val text: String = "",
)

@Serializable
data class SubmitPromptRequest(
    val text: String? = null,
    val parts: List<PromptPartDto>? = null,
    @SerialName("attachment_ids") val attachmentIds: List<String> = emptyList(),
    @SerialName("enqueue_if_busy") val enqueueIfBusy: Boolean = true,
    @SerialName("auto_resume") val autoResume: Boolean = false,
    val cwd: String? = null,
)

@Serializable
data class SubmitPromptResponse(
    val status: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("turn_id") val turnId: String? = null,
    @SerialName("queue_item") val queueItem: QueueItemDto? = null,
)

@Serializable
data class EnqueueRequest(
    val text: String,
    @SerialName("attachment_ids") val attachmentIds: List<String> = emptyList(),
)

@Serializable
data class EditQueueItemRequest(
    val text: String,
)

@Serializable
data class SendNowRequest(
    val interrupt: Boolean = true,
)

@Serializable
data class ResolvePermissionRequest(
    val decision: String,
    @SerialName("option_id") val optionId: String? = null,
)

@Serializable
data class ResolveQuestionRequest(
    val action: String,
    val answers: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class DeleteSessionResponse(
    @SerialName("session_id") val sessionId: String,
    val status: String,
)

@Serializable
data class CancelSessionResponse(
    @SerialName("session_id") val sessionId: String,
    val status: String,
)

@Serializable
data class QueueListResponse(
    val items: List<QueueItemDto> = emptyList(),
    val paused: Boolean = false,
)

@Serializable
data class WsClientHello(
    val frame: String = "hello",
    val client: String = "gravity-well-android",
    val version: String = "0.1.0",
)

@Serializable
data class WsClientPing(
    val frame: String = "ping",
)

@Serializable
data class WsClientSubscribe(
    val frame: String = "subscribe",
    @SerialName("session_ids") val sessionIds: List<String>? = null,
)

sealed interface WsServerFrame {
    data class Hello(
        val gatewayVersion: String,
        val agyVersion: String,
        val serverTime: String,
        val latestCursor: Long,
        val capabilities: CapabilitiesDto,
    ) : WsServerFrame

    data class Event(
        val cursor: Long,
        val seq: Long,
        val sessionId: String?,
        val type: String,
        val ts: String,
        val payload: JsonObject,
    ) : WsServerFrame

    data class ReplayComplete(
        val after: Long,
        val replayedCount: Int,
        val latestCursor: Long,
    ) : WsServerFrame

    data class ResyncRequired(
        val after: Long,
        val minCursorRetained: Long,
        val latestCursor: Long,
    ) : WsServerFrame

    data class Pong(
        val ts: String,
    ) : WsServerFrame

    data class Error(
        val code: String,
        val message: String,
    ) : WsServerFrame

    data class Unknown(
        val frame: String,
        val raw: JsonObject,
    ) : WsServerFrame
}

object WsFrameParser {
    fun parse(rawText: String, json: Json): WsServerFrame {
        val obj = json.parseToJsonElement(rawText).jsonObject
        val frameType = obj["frame"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return when (frameType) {
            "hello" -> {
                val capElement: JsonElement? = obj["capabilities"]
                val caps = if (capElement != null) {
                    json.decodeFromJsonElement<CapabilitiesDto>(capElement)
                } else {
                    CapabilitiesDto()
                }
                WsServerFrame.Hello(
                    gatewayVersion = obj["gateway_version"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    agyVersion = obj["agy_version"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    serverTime = obj["server_time"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    latestCursor = obj["latest_cursor"]?.jsonPrimitive?.longOrNull ?: 0L,
                    capabilities = caps,
                )
            }
            "event" -> {
                WsServerFrame.Event(
                    cursor = obj["cursor"]?.jsonPrimitive?.longOrNull ?: 0L,
                    seq = obj["seq"]?.jsonPrimitive?.longOrNull ?: 0L,
                    sessionId = obj["session_id"]?.jsonPrimitive?.contentOrNull,
                    type = obj["type"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    ts = obj["ts"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    payload = obj["payload"]?.jsonObject ?: JsonObject(emptyMap()),
                )
            }
            "replay_complete" -> {
                WsServerFrame.ReplayComplete(
                    after = obj["after"]?.jsonPrimitive?.longOrNull ?: 0L,
                    replayedCount = obj["replayed_count"]?.jsonPrimitive?.intOrNull ?: 0,
                    latestCursor = obj["latest_cursor"]?.jsonPrimitive?.longOrNull ?: 0L,
                )
            }
            "resync_required" -> {
                WsServerFrame.ResyncRequired(
                    after = obj["after"]?.jsonPrimitive?.longOrNull ?: 0L,
                    minCursorRetained = obj["min_cursor_retained"]?.jsonPrimitive?.longOrNull ?: 0L,
                    latestCursor = obj["latest_cursor"]?.jsonPrimitive?.longOrNull ?: 0L,
                )
            }
            "pong" -> {
                WsServerFrame.Pong(
                    ts = obj["ts"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            }
            "error" -> {
                WsServerFrame.Error(
                    code = obj["code"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    message = obj["message"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            }
            else -> WsServerFrame.Unknown(frame = frameType, raw = obj)
        }
    }
}
