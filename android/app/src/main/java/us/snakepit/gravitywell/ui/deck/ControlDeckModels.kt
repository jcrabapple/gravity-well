package us.snakepit.gravitywell.ui.deck

import androidx.annotation.Keep
import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import us.snakepit.gravitywell.ui.sessions.PermissionRuleDto

enum class ControlDeckTab(val label: String) {
    MCP("MCP"),
    PLUGINS("PLUGINS"),
    AGENTS_AND_RULES("AGENTS & RULES"),
    SYSTEM("SYSTEM"),
}

// ── Ecosystem Models DTOs ───────────────────────────────────────────────────

@Keep
@Serializable
data class EcosystemModelItemDto(
    @SerialName("model_id") val modelId: String,
    @SerialName("display_name") val displayName: String = "",
    val family: String = "",
    @SerialName("thought_level") val thoughtLevel: String? = null,
    @SerialName("is_default") val isDefault: Boolean = false,
)

@Keep
@Serializable
data class EcosystemModelsResponseDto(
    @SerialName("default_model_id") val defaultModelId: String? = null,
    @SerialName("gateway_default_model") val gatewayDefaultModel: String? = null,
    @SerialName("gateway_default_thought_level") val gatewayDefaultThoughtLevel: String? = null,
    val items: List<EcosystemModelItemDto> = emptyList(),
)

@Keep
@Serializable
data class SetDefaultModelRequestDto(
    val model: String,
    @SerialName("thought_level") val thoughtLevel: String? = null,
)

@Keep
@Serializable
data class SetDefaultModelResponseDto(
    val model: String? = null,
    @SerialName("thought_level") val thoughtLevel: String? = null,
)

// ── Agents DTOs ─────────────────────────────────────────────────────────────

@Keep
@Serializable
data class AgentItemDto(
    val name: String,
    val slug: String = "",
    val description: String = "",
    val prompt: String = "",
    val source: String = "cli",
    val editable: Boolean = false,
)

@Keep
@Serializable
data class AgentsListResponseDto(
    val items: List<AgentItemDto> = emptyList(),
)

@Keep
@Serializable
data class UpsertAgentRequestDto(
    val name: String,
    val description: String = "",
    val prompt: String,
)

@Keep
@Serializable
data class DeleteAgentResponseDto(
    val deleted: Boolean = false,
    val name: String = "",
)

// ── MCP Server DTOs ─────────────────────────────────────────────────────────

@Keep
@Serializable
data class McpServerItemDto(
    val name: String,
    val type: String = "stdio",
    val disabled: Boolean = false,
    val status: String = "enabled",
    val command: String? = null,
    val args: List<String> = emptyList(),
    @SerialName("server_url") val serverUrl: String? = null,
    val env: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    @SerialName("env_keys") val envKeys: List<String> = emptyList(),
    @SerialName("header_keys") val headerKeys: List<String> = emptyList(),
)

@Keep
@Serializable
data class McpServersListResponseDto(
    val items: List<McpServerItemDto> = emptyList(),
)

@Keep
@Serializable
data class CreateMcpServerRequestDto(
    val name: String,
    val type: String,
    val target: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
)

@Keep
@Serializable
data class ToggleMcpServerResponseDto(
    val name: String = "",
    val disabled: Boolean = false,
    val status: String = "enabled",
)

@Keep
@Serializable
data class DeleteMcpServerResponseDto(
    val deleted: Boolean = false,
    val name: String = "",
)

// ── Plugins DTOs ────────────────────────────────────────────────────────────

@Keep
@Serializable
data class BuiltinPluginDto(
    val name: String,
    @SerialName("display_name") val displayName: String = "",
    val description: String = "",
    val version: String? = null,
    val builtin: Boolean = true,
    val enabled: Boolean = true,
)

@Keep
@Serializable
data class PluginsListResponseDto(
    val items: JsonArray = JsonArray(emptyList()),
    val builtins: List<BuiltinPluginDto> = emptyList(),
)

@Immutable
data class PluginUiItem(
    val name: String,
    val displayName: String,
    val description: String,
    val version: String?,
    val builtin: Boolean,
    val enabled: Boolean,
)

@Keep
@Serializable
data class ValidatePluginRequestDto(
    val target: String,
    val strict: Boolean = false,
)

@Keep
@Serializable
data class ValidatePluginResponseDto(
    val valid: Boolean = false,
    val strict: Boolean = false,
    val report: JsonElement? = null,
    val errors: String = "",
)

@Keep
@Serializable
data class InstallPluginRequestDto(
    val target: String,
)

@Keep
@Serializable
data class InstallPluginResponseDto(
    val installed: Boolean = false,
    val target: String = "",
    val output: String = "",
)

@Keep
@Serializable
data class TogglePluginResponseDto(
    val name: String = "",
    val enabled: Boolean = true,
    val status: String = "enabled",
)

@Keep
@Serializable
data class DeletePluginResponseDto(
    val deleted: Boolean = false,
    val name: String = "",
)

// ── Allowlisted Settings DTOs (SPEC.md D12) ─────────────────────────────────

@Keep
@Serializable
data class AllowlistedGeneralSettingsDto(
    val previewFeatures: Boolean? = null,
    val enablePromptCompletion: Boolean? = null,
)

@Keep
@Serializable
data class AllowlistedSettingsRootDto(
    val general: AllowlistedGeneralSettingsDto = AllowlistedGeneralSettingsDto(),
)

@Keep
@Serializable
data class GatewayPrefsDto(
    val model: String? = null,
    @SerialName("thought_level") val thoughtLevel: String? = null,
)

@Keep
@Serializable
data class EcosystemSettingsResponseDto(
    val settings: AllowlistedSettingsRootDto = AllowlistedSettingsRootDto(),
    @SerialName("gateway_prefs") val gatewayPrefs: GatewayPrefsDto = GatewayPrefsDto(),
    val allowlist: List<String> = emptyList(),
)

/**
 * Pure validation & parsing rules for Control Deck forms and allowlisted settings (D12).
 */
object ControlDeckValidation {
    const val KEY_PREVIEW_FEATURES = "general.previewFeatures"
    const val KEY_ENABLE_PROMPT_COMPLETION = "general.enablePromptCompletion"

    val ALLOWED_SETTING_KEYS: Set<String> = setOf(
        KEY_PREVIEW_FEATURES,
        KEY_ENABLE_PROMPT_COMPLETION,
    )

    private val AGENT_SLUG_REGEX = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")
    private val MCP_NAME_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
    private val ENV_KEY_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    fun isValidAgentSlug(slug: String): Boolean = AGENT_SLUG_REGEX.matches(slug.trim())

    fun isValidMcpName(name: String): Boolean = MCP_NAME_REGEX.matches(name.trim())

    fun isValidEnvKey(key: String): Boolean = ENV_KEY_REGEX.matches(key.trim())

    /**
     * Builds a strictly allowlisted PATCH payload for `PATCH /api/ecosystem/settings`.
     * Rejects any `security.*`, `oauth*`, `auth*`, or unknown setting key.
     */
    fun buildAllowlistedSettingsPatch(settingKey: String, value: Boolean): JsonObject {
        require(settingKey in ALLOWED_SETTING_KEYS) {
            "Disallowed setting key: $settingKey"
        }
        val subKey = settingKey.substringAfter("general.")
        return buildJsonObject {
            put(
                "general",
                buildJsonObject {
                    put(subKey, JsonPrimitive(value))
                },
            )
        }
    }

    fun parseKeyValueLines(raw: String, separator: Char): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val idx = trimmed.indexOf(separator)
            if (idx <= 0) continue
            val k = trimmed.substring(0, idx).trim()
            val v = trimmed.substring(idx + 1).trim()
            if (k.isNotEmpty()) {
                out[k] = v
            }
        }
        return out
    }

    fun mergePluginCatalog(dto: PluginsListResponseDto): List<PluginUiItem> {
        val merged = LinkedHashMap<String, PluginUiItem>()
        for (builtin in dto.builtins) {
            merged[builtin.name] = PluginUiItem(
                name = builtin.name,
                displayName = builtin.displayName.ifBlank { builtin.name },
                description = builtin.description,
                version = builtin.version,
                builtin = true,
                enabled = builtin.enabled,
            )
        }
        for (elem in dto.items) {
            val obj = runCatching { elem.jsonObject }.getOrNull()
            if (obj != null) {
                val name = obj["name"]?.jsonPrimitive?.contentOrNull
                    ?: obj["id"]?.jsonPrimitive?.contentOrNull
                    ?: continue
                val displayName = obj["displayName"]?.jsonPrimitive?.contentOrNull
                    ?: obj["display_name"]?.jsonPrimitive?.contentOrNull
                    ?: name
                val desc = obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val version = obj["version"]?.jsonPrimitive?.contentOrNull
                val disabled = obj["disabled"]?.jsonPrimitive?.booleanOrNull ?: false
                val enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull
                    ?: (obj["status"]?.jsonPrimitive?.contentOrNull?.lowercase() != "disabled" && !disabled)
                val isBuiltin = obj["builtin"]?.jsonPrimitive?.booleanOrNull ?: false
                merged[name] = PluginUiItem(
                    name = name,
                    displayName = displayName,
                    description = desc,
                    version = version,
                    builtin = isBuiltin,
                    enabled = enabled,
                )
            } else {
                val primName = runCatching { elem.jsonPrimitive.contentOrNull }.getOrNull()?.trim()
                if (!primName.isNullOrEmpty() && primName !in merged) {
                    merged[primName] = PluginUiItem(
                        name = primName,
                        displayName = primName,
                        description = "",
                        version = null,
                        builtin = false,
                        enabled = true,
                    )
                }
            }
        }
        return merged.values.sortedWith(compareByDescending<PluginUiItem> { it.builtin }.thenBy { it.name })
    }
}

@Immutable
data class AddMcpFormState(
    val name: String = "",
    val type: String = "stdio",
    val target: String = "",
    val argsLine: String = "",
    val envLines: String = "",
    val headerLines: String = "",
)

@Immutable
data class AgentEditorFormState(
    val isEditingExisting: Boolean = false,
    val slug: String = "",
    val description: String = "",
    val prompt: String = "",
)

@Immutable
data class ControlDeckUiState(
    val hostId: String = "",
    val activeTab: ControlDeckTab = ControlDeckTab.MCP,
    val isLoading: Boolean = false,
    val isMutating: Boolean = false,
    val mcpServers: List<McpServerItemDto> = emptyList(),
    val showAddMcpSheet: Boolean = false,
    val addMcpForm: AddMcpFormState = AddMcpFormState(),
    val pendingRemoveMcpName: String? = null,
    val plugins: List<PluginUiItem> = emptyList(),
    val pluginTargetDraft: String = "",
    val pluginValidateStrict: Boolean = false,
    val lastPluginValidation: ValidatePluginResponseDto? = null,
    val lastPluginInstallMessage: String? = null,
    val pendingUninstallPluginName: String? = null,
    val agents: List<AgentItemDto> = emptyList(),
    val showAgentEditorSheet: Boolean = false,
    val agentForm: AgentEditorFormState = AgentEditorFormState(),
    val pendingDeleteAgentSlug: String? = null,
    val permissionRules: List<PermissionRuleDto> = emptyList(),
    val previewFeaturesEnabled: Boolean = false,
    val promptCompletionEnabled: Boolean = false,
    val availableModels: List<EcosystemModelItemDto> = emptyList(),
    val defaultModelId: String? = null,
    val gatewayDefaultModel: String? = null,
    val gatewayDefaultThoughtLevel: String? = null,
    val pendingDeleteHostId: String? = null,
    val errorBanner: String? = null,
    val infoBanner: String? = null,
)
