package us.snakepit.gravitywell.ui.console

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import us.snakepit.gravitywell.net.AvailableCommandDto
import us.snakepit.gravitywell.net.AvailableModelDto
import us.snakepit.gravitywell.net.ConfigOptionDto
import us.snakepit.gravitywell.net.PendingPermissionDto
import us.snakepit.gravitywell.net.PendingQuestionDto
import us.snakepit.gravitywell.net.QueueItemDto
import us.snakepit.gravitywell.net.UsageDto

enum class ConsoleStreamIndicator {
    LIVE,
    REPLAYING,
    RECONNECTING,
    RESYNCED,
    OFFLINE,
}

enum class DiffDisplayMode {
    UNIFIED,
    WORD,
}

sealed interface LedgerItem {
    val id: String
    val seq: Long
    val ts: String

    @Immutable
    data class UserTurn(
        override val id: String,
        override val seq: Long,
        override val ts: String,
        val text: String,
        val attachmentLabels: List<String> = emptyList(),
    ) : LedgerItem

    @Immutable
    data class ThoughtBlock(
        override val id: String,
        override val seq: Long,
        override val ts: String,
        val text: String,
        val isStreaming: Boolean,
    ) : LedgerItem {
        val lineCount: Int
            get() = if (text.isEmpty()) 0 else text.count { it == '\n' } + 1
    }

    @Immutable
    data class AgentProse(
        override val id: String,
        override val seq: Long,
        override val ts: String,
        val text: String,
        val isStreaming: Boolean,
    ) : LedgerItem

    @Immutable
    data class ToolCallBlock(
        override val id: String,
        override val seq: Long,
        override val ts: String,
        val toolCallId: String,
        val title: String,
        val kind: String,
        val status: String,
        val rawInputSummary: String,
        val rawOutputSummary: String,
        val parsedDiff: ParsedDiff? = null,
        val isSubagentWorker: Boolean = false,
        val workerLabel: String? = null,
    ) : LedgerItem {
        val normalizedKindBadge: String
            get() = when (kind.lowercase().trim()) {
                "execute", "bash", "shell", "command", "terminal", "run" -> "EXECUTE"
                "edit", "write", "patch", "modify", "replace" -> "EDIT"
                "read", "view", "cat" -> "READ"
                "search", "grep", "find", "glob" -> "SEARCH"
                else -> kind.uppercase().take(10).ifBlank { "TOOL" }
            }
    }

    @Immutable
    data class SystemNotice(
        override val id: String,
        override val seq: Long,
        override val ts: String,
        val label: String,
        val message: String,
        val isError: Boolean = false,
    ) : LedgerItem
}

@Immutable
data class SwarmWorkerBadge(
    val toolCallId: String,
    val label: String,
    val status: String,
    val ledgerItemId: String,
) {
    val isActive: Boolean
        get() = status == "pending" || status == "in_progress" || status == "running"
}

enum class AttachmentUploadState {
    UPLOADING,
    READY,
    FAILED,
}

@Serializable
@Immutable
data class DraftAttachmentRecord(
    val localId: String,
    val attachmentId: String? = null,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val state: AttachmentUploadState = AttachmentUploadState.READY,
    val errorStatus: Int? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
)

@Immutable
sealed interface QuestionFieldSpec {
    val key: String
    val title: String
    val description: String?
    val required: Boolean

    data class EnumSelect(
        override val key: String,
        override val title: String,
        override val description: String?,
        override val required: Boolean,
        val options: List<Pair<String, String>>, // (rawEnumValue, displayLabel)
    ) : QuestionFieldSpec

    data class TextField(
        override val key: String,
        override val title: String,
        override val description: String?,
        override val required: Boolean,
        val numericOnly: Boolean = false,
        val integerOnly: Boolean = false,
    ) : QuestionFieldSpec

    data class BooleanField(
        override val key: String,
        override val title: String,
        override val description: String?,
        override val required: Boolean,
        val defaultValue: Boolean = false,
    ) : QuestionFieldSpec
}

object QuestionSchemaParser {
    /**
     * Parses ACP elicitation/create requestedSchema.properties while preserving exact
     * schema property keys (e.g. q_0, q_1) per SPEC D7 & R6.
     */
    fun parseFields(schema: JsonObject): List<QuestionFieldSpec> {
        val props = schema["properties"] as? JsonObject ?: return emptyList()
        val requiredSet = (schema["required"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.toSet()
            ?: emptySet()

        return props.entries.map { (propKey, rawSpec) ->
            val specObj = rawSpec.jsonObject
            val typeStr = specObj["type"]?.jsonPrimitive?.contentOrNull ?: "string"
            val title = specObj["title"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
                ?: specObj["description"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
                ?: propKey
            val desc = specObj["description"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it != title && it.isNotBlank() }
            val isReq = propKey in requiredSet

            val enumArr = specObj["enum"] as? JsonArray
            val enumNamesArr = specObj["enumNames"] as? JsonArray
            if (enumArr != null && enumArr.isNotEmpty()) {
                val pairs = enumArr.mapIndexedNotNull { idx, elem ->
                    val rawVal = (elem as? JsonPrimitive)?.contentOrNull ?: return@mapIndexedNotNull null
                    val label = (enumNamesArr?.getOrNull(idx) as? JsonPrimitive)?.contentOrNull
                        ?.ifBlank { rawVal }
                        ?: rawVal
                    rawVal to label
                }
                QuestionFieldSpec.EnumSelect(
                    key = propKey,
                    title = title,
                    description = desc,
                    required = isReq,
                    options = pairs,
                )
            } else {
                when (typeStr) {
                    "boolean" -> QuestionFieldSpec.BooleanField(
                        key = propKey,
                        title = title,
                        description = desc,
                        required = isReq,
                        defaultValue = specObj["default"]?.jsonPrimitive?.booleanOrNull ?: false,
                    )
                    "integer" -> QuestionFieldSpec.TextField(
                        key = propKey,
                        title = title,
                        description = desc,
                        required = isReq,
                        numericOnly = true,
                        integerOnly = true,
                    )
                    "number" -> QuestionFieldSpec.TextField(
                        key = propKey,
                        title = title,
                        description = desc,
                        required = isReq,
                        numericOnly = true,
                        integerOnly = false,
                    )
                    else -> QuestionFieldSpec.TextField(
                        key = propKey,
                        title = title,
                        description = desc,
                        required = isReq,
                    )
                }
            }
        }
    }

    fun areRequiredAnswersSatisfied(
        fields: List<QuestionFieldSpec>,
        draftAnswers: Map<String, String>,
    ): Boolean {
        for (field in fields) {
            if (!field.required) continue
            val raw = draftAnswers[field.key]?.trim()?.ifEmpty { null }
                ?: if (field is QuestionFieldSpec.BooleanField) field.defaultValue.toString() else ""
            if (raw.isEmpty()) return false
            if (field is QuestionFieldSpec.BooleanField && raw.toBooleanStrictOrNull() == null) {
                return false
            }
            if (field is QuestionFieldSpec.TextField && field.integerOnly && raw.toLongOrNull() == null) {
                return false
            }
            if (field is QuestionFieldSpec.TextField && field.numericOnly && !field.integerOnly && raw.toDoubleOrNull() == null) {
                return false
            }
        }
        return true
    }

    fun buildAnswersPayload(
        fields: List<QuestionFieldSpec>,
        draftAnswers: Map<String, String>,
    ): JsonObject {
        val out = LinkedHashMap<String, JsonPrimitive>()
        for (field in fields) {
            val raw = draftAnswers[field.key]
            when (field) {
                is QuestionFieldSpec.BooleanField -> {
                    val b = raw?.toBooleanStrictOrNull() ?: field.defaultValue
                    out[field.key] = JsonPrimitive(b)
                }
                is QuestionFieldSpec.TextField -> {
                    val trimmed = raw?.trim().orEmpty()
                    if (trimmed.isEmpty() && !field.required) continue
                    when {
                        field.integerOnly -> trimmed.toLongOrNull()?.let { out[field.key] = JsonPrimitive(it) }
                        field.numericOnly -> trimmed.toDoubleOrNull()?.let { out[field.key] = JsonPrimitive(it) }
                        else -> out[field.key] = JsonPrimitive(raw.orEmpty())
                    }
                }
                is QuestionFieldSpec.EnumSelect -> {
                    if (!raw.isNullOrEmpty()) {
                        out[field.key] = JsonPrimitive(raw)
                    }
                }
            }
        }
        return JsonObject(out)
    }
}

@Immutable
data class ReducedSessionState(
    val sessionId: String = "",
    val title: String = "Session",
    val titleSource: String = "acp",
    val cwd: String = "",
    val addDirs: List<String> = emptyList(),
    val status: String = "idle",
    val isLive: Boolean = false,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val sandbox: Boolean = false,
    val configOptions: List<ConfigOptionDto> = emptyList(),
    val availableModels: List<AvailableModelDto> = emptyList(),
    val availableCommands: List<AvailableCommandDto> = emptyList(),
    val usage: UsageDto = UsageDto(),
    val pendingPermissions: List<PendingPermissionDto> = emptyList(),
    val pendingQuestions: List<PendingQuestionDto> = emptyList(),
    val queuedFollowups: List<QueueItemDto> = emptyList(),
    val queuePaused: Boolean = false,
    val ledger: List<LedgerItem> = emptyList(),
    val swarmWorkers: List<SwarmWorkerBadge> = emptyList(),
    val lastSeq: Long = 0L,
    val lastCursor: Long = 0L,
) {
    val modelId: String
        get() = configOptions.firstOrNull { it.id == "model" }?.currentValue?.ifBlank { null }
            ?: "gemini-4-neon"

    val thoughtLevel: String
        get() = configOptions.firstOrNull { it.id == "thought_level" }?.currentValue?.ifBlank { null }
            ?: "high"

    val modeId: String
        get() = configOptions.firstOrNull { it.id == "mode" }?.currentValue?.ifBlank { null }
            ?: "default"

    val isTurnActive: Boolean
        get() = status == "running" ||
            status == "starting" ||
            status == "awaiting_approval" ||
            status == "awaiting_question"
}
