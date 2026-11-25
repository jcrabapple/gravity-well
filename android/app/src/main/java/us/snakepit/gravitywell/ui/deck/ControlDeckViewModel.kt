package us.snakepit.gravitywell.ui.deck

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import us.snakepit.gravitywell.cache.CachedHost
import us.snakepit.gravitywell.net.GatewayException
import us.snakepit.gravitywell.net.GatewayHttpException
import us.snakepit.gravitywell.ui.sessions.SessionSupplementaryApi

/**
 * Stateful controller for Control Deck: MCP servers, plugins, custom agents,
 * revocable workspace allow-rules, allowlisted `settings.json` keys, and default model (SPEC.md D7, D12, D16).
 */
class ControlDeckController(
    private val host: CachedHost,
    private val ecosystemApi: EcosystemApi,
    private val supplementaryApi: SessionSupplementaryApi,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(ControlDeckUiState(hostId = host.hostId))
    val state: StateFlow<ControlDeckUiState> = _state.asStateFlow()

    fun selectTab(tab: ControlDeckTab) {
        _state.update { it.copy(activeTab = tab, errorBanner = null, infoBanner = null) }
        if (tab == ControlDeckTab.AGENTS_AND_RULES) {
            refreshPermissionRules()
        }
    }

    fun refreshAll() {
        scope.launch {
            _state.update { it.copy(isLoading = true, errorBanner = null) }
            val mcpDef = async { runCatching { ecosystemApi.listMcpServers() } }
            val pluginsDef = async { runCatching { ecosystemApi.listPlugins() } }
            val agentsDef = async { runCatching { ecosystemApi.listAgents() } }
            val rulesDef = async { runCatching { supplementaryApi.listPermissionRules(workspace = null) } }
            val settingsDef = async { runCatching { ecosystemApi.getSettings() } }
            val modelsDef = async { runCatching { ecosystemApi.listModels() } }

            val mcpRes = mcpDef.await()
            val pluginsRes = pluginsDef.await()
            val agentsRes = agentsDef.await()
            val rulesRes = rulesDef.await()
            val settingsRes = settingsDef.await()
            val modelsRes = modelsDef.await()

            val firstErr = mcpRes.exceptionOrNull()
                ?: pluginsRes.exceptionOrNull()
                ?: agentsRes.exceptionOrNull()
                ?: rulesRes.exceptionOrNull()
                ?: settingsRes.exceptionOrNull()
                ?: modelsRes.exceptionOrNull()

            _state.update { st ->
                val settingsDto = settingsRes.getOrNull()
                val modelsDto = modelsRes.getOrNull()
                st.copy(
                    isLoading = false,
                    mcpServers = mcpRes.getOrNull()?.items ?: st.mcpServers,
                    plugins = pluginsRes.getOrNull()?.let(ControlDeckValidation::mergePluginCatalog) ?: st.plugins,
                    agents = agentsRes.getOrNull()?.items ?: st.agents,
                    permissionRules = rulesRes.getOrNull()?.items ?: st.permissionRules,
                    previewFeaturesEnabled = settingsDto?.settings?.general?.previewFeatures
                        ?: st.previewFeaturesEnabled,
                    promptCompletionEnabled = settingsDto?.settings?.general?.enablePromptCompletion
                        ?: st.promptCompletionEnabled,
                    availableModels = modelsDto?.items ?: st.availableModels,
                    defaultModelId = modelsDto?.defaultModelId ?: st.defaultModelId,
                    gatewayDefaultModel = settingsDto?.gatewayPrefs?.model
                        ?: modelsDto?.gatewayDefaultModel
                        ?: st.gatewayDefaultModel,
                    gatewayDefaultThoughtLevel = settingsDto?.gatewayPrefs?.thoughtLevel
                        ?: modelsDto?.gatewayDefaultThoughtLevel
                        ?: st.gatewayDefaultThoughtLevel,
                    errorBanner = firstErr?.let(::formatError),
                )
            }
        }
    }

    // ── 1. MCP Servers ──────────────────────────────────────────────────────

    fun openAddMcpSheet() {
        _state.update {
            it.copy(
                showAddMcpSheet = true,
                addMcpForm = AddMcpFormState(),
                errorBanner = null,
            )
        }
    }

    fun dismissAddMcpSheet() {
        _state.update { it.copy(showAddMcpSheet = false) }
    }

    fun updateAddMcpForm(transform: (AddMcpFormState) -> AddMcpFormState) {
        _state.update { it.copy(addMcpForm = transform(it.addMcpForm)) }
    }

    fun submitAddMcpServer(authenticateAfterSave: Boolean = false) {
        val form = _state.value.addMcpForm
        val cleanName = form.name.trim()
        val cleanTarget = form.target.trim()
        val cleanType = form.type.trim().lowercase()
        if (!ControlDeckValidation.isValidMcpName(cleanName)) {
            _state.update { it.copy(errorBanner = "Invalid MCP name. Use letters, digits, '.', '_', or '-'.") }
            return
        }
        if (cleanTarget.isEmpty() || cleanTarget.startsWith("-")) {
            _state.update { it.copy(errorBanner = "MCP target command or URL is required and cannot start with '-'.") }
            return
        }
        if (cleanType == "http" && !cleanTarget.startsWith("https://") && !cleanTarget.startsWith("http://")) {
            _state.update { it.copy(errorBanner = "HTTP MCP target must start with https:// or http://.") }
            return
        }
        val args = if (cleanType == "stdio" && form.argsLine.isNotBlank()) {
            form.argsLine.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        } else {
            emptyList()
        }
        val envMap = ControlDeckValidation.parseKeyValueLines(form.envLines, '=')
        for (k in envMap.keys) {
            if (!ControlDeckValidation.isValidEnvKey(k)) {
                _state.update { it.copy(errorBanner = "Invalid env variable key: $k") }
                return
            }
        }
        val headerMap = ControlDeckValidation.parseKeyValueLines(form.headerLines, ':')
        val oauthClientId = form.oauthClientId.trim().ifEmpty { null }
        val oauthClientSecret = form.oauthClientSecret.trim().ifEmpty { null }

        scope.launch {
            _state.update { it.copy(isMutating = true, errorBanner = null) }
            runCatching {
                ecosystemApi.addMcpServer(
                    CreateMcpServerRequestDto(
                        name = cleanName,
                        type = cleanType,
                        target = cleanTarget,
                        args = args,
                        env = envMap,
                        headers = headerMap,
                        oauthClientId = oauthClientId,
                        oauthClientSecret = oauthClientSecret,
                    ),
                )
                ecosystemApi.listMcpServers()
            }.onSuccess { refreshed ->
                _state.update {
                    it.copy(
                        isMutating = false,
                        showAddMcpSheet = false,
                        mcpServers = refreshed.items,
                        infoBanner = "Added MCP server '$cleanName'.",
                    )
                }
                if (authenticateAfterSave && cleanType == "http") {
                    openMcpOAuthFlow(
                        serverName = cleanName,
                        initialClientId = oauthClientId.orEmpty(),
                        initialClientSecret = oauthClientSecret.orEmpty(),
                        autoStart = true,
                    )
                }
            }.onFailure { err ->
                _state.update { it.copy(isMutating = false, errorBanner = formatError(err)) }
            }
        }
    }

    fun openMcpOAuthFlow(
        serverName: String,
        initialClientId: String = "",
        initialClientSecret: String = "",
        autoStart: Boolean = true,
    ) {
        _state.update {
            it.copy(
                activeOAuthFlow = McpOAuthFlowUiState(
                    serverName = serverName,
                    isStarting = autoStart,
                    clientIdDraft = initialClientId,
                    clientSecretDraft = initialClientSecret,
                ),
                errorBanner = null,
            )
        }
        if (autoStart) {
            startMcpOAuthDiscovery()
        }
    }

    fun dismissMcpOAuthSheet() {
        _state.update { it.copy(activeOAuthFlow = null) }
    }

    fun updateMcpOAuthFlow(transform: (McpOAuthFlowUiState) -> McpOAuthFlowUiState) {
        _state.update { st ->
            val cur = st.activeOAuthFlow ?: return@update st
            st.copy(activeOAuthFlow = transform(cur))
        }
    }

    fun startMcpOAuthDiscovery() {
        val flow = _state.value.activeOAuthFlow ?: return
        scope.launch {
            _state.update { st ->
                st.copy(
                    activeOAuthFlow = st.activeOAuthFlow?.copy(
                        isStarting = true,
                        error = null,
                    ),
                )
            }
            runCatching {
                ecosystemApi.startMcpOAuth(
                    name = flow.serverName,
                    body = StartMcpOAuthRequestDto(
                        clientId = flow.clientIdDraft.trim().ifEmpty { null },
                        clientSecret = flow.clientSecretDraft.trim().ifEmpty { null },
                    ),
                )
            }.onSuccess { started ->
                _state.update { st ->
                    st.copy(
                        activeOAuthFlow = st.activeOAuthFlow?.copy(
                            isStarting = false,
                            flowId = started.flowId,
                            authorizationUrl = started.authorizationUrl,
                            redirectUri = started.redirectUri.ifBlank { "https://antigravity.google/oauth-callback" },
                            registrationMethod = started.registrationMethod,
                            error = null,
                        ),
                    )
                }
            }.onFailure { err ->
                _state.update { st ->
                    st.copy(
                        activeOAuthFlow = st.activeOAuthFlow?.copy(
                            isStarting = false,
                            error = formatError(err),
                        ),
                    )
                }
            }
        }
    }

    fun submitMcpOAuthCode() {
        val flow = _state.value.activeOAuthFlow ?: return
        val flowId = flow.flowId
        val code = flow.codeDraft.trim()
        if (flowId.isNullOrBlank()) {
            updateMcpOAuthFlow { it.copy(error = "Start OAuth discovery first.") }
            return
        }
        if (code.isEmpty()) {
            updateMcpOAuthFlow { it.copy(error = "Paste the authorization code or callback URL.") }
            return
        }
        scope.launch {
            updateMcpOAuthFlow { it.copy(isCompleting = true, error = null) }
            runCatching {
                ecosystemApi.completeMcpOAuth(
                    name = flow.serverName,
                    body = CompleteMcpOAuthRequestDto(flowId = flowId, code = code),
                )
                ecosystemApi.listMcpServers()
            }.onSuccess { refreshed ->
                _state.update {
                    it.copy(
                        activeOAuthFlow = null,
                        mcpServers = refreshed.items,
                        infoBanner = "Authenticated '${flow.serverName}' via OAuth.",
                    )
                }
            }.onFailure { err ->
                updateMcpOAuthFlow { it.copy(isCompleting = false, error = formatError(err)) }
            }
        }
    }

    fun refreshMcpOAuthToken(serverName: String) {
        scope.launch {
            _state.update { it.copy(isMutating = true, errorBanner = null) }
            runCatching {
                ecosystemApi.refreshMcpOAuth(serverName)
                ecosystemApi.listMcpServers()
            }.onSuccess { refreshed ->
                _state.update {
                    it.copy(
                        isMutating = false,
                        mcpServers = refreshed.items,
                        infoBanner = "Refreshed OAuth token for '$serverName'.",
                    )
                }
            }.onFailure { err ->
                _state.update { it.copy(isMutating = false, errorBanner = formatError(err)) }
            }
        }
    }

    fun signOutMcpOAuth(serverName: String) {
        scope.launch {
            _state.update { it.copy(isMutating = true, errorBanner = null) }
            runCatching {
                ecosystemApi.signOutMcpOAuth(serverName)
                ecosystemApi.listMcpServers()
            }.onSuccess { refreshed ->
                _state.update {
                    it.copy(
                        isMutating = false,
                        mcpServers = refreshed.items,
                        infoBanner = "Signed out OAuth session for '$serverName'.",
                    )
                }
            }.onFailure { err ->
                _state.update { it.copy(isMutating = false, errorBanner = formatError(err)) }
            }
        }
    }

    fun toggleMcpServerEnabled(name: String, enabled: Boolean) {
        scope.launch {
            _state.update { it.copy(isMutating = true, errorBanner = null) }
            runCatching {
                if (enabled) {
                    ecosystemApi.enableMcpServer(name)
                } else {
                    ecosystemApi.disableMcpServer(name)
                }
            }.onSuccess { res ->
                _state.update { st ->
                    st.copy(
                        isMutating = false,
                        mcpServers = st.mcpServers.map { srv ->
                            if (srv.name == name) {
                                srv.copy(disabled = res.disabled, status = res.status)
                            } else {
                                srv
                            }
                        },
                    )
                }
            }.onFailure { err ->
                _state.update { it.copy(isMutating = false, errorBanner = formatError(err)) }
            }
        }
    }

    fun requestRemoveMcpServer(name: String) {
        _state.update { it.copy(pendingRemoveMcpName = name) }
    }

    fun cancelRemoveMcpServer() {
        _state.update { it.copy(pendingRemoveMcpName = null) }
    }

    fun confirmRemoveMcpServer() {
        val targetName = _state.value.pendingRemoveMcpName ?: return
        scope.launch {
            _state.update { it.copy(isMutating = true, errorBanner = null) }
            runCatching { ecosystemApi.removeMcpServer(targetName) }
                .onSuccess {
                    _state.update { st ->
                        st.copy(
                            isMutating = false,
                            pendingRemoveMcpName = null,
                            mcpServers = st.mcpServers.filterNot { it.name == targetName },
                            infoBanner = "Removed MCP server '$targetName'.",
                        )
                    }
                }
                .onFailure { err ->
                    _state.update {
                        it.copy(
                            isMutating = false,
                            pendingRemoveMcpName = null,
                            errorBanner = formatError(err),
                        )
                    }
                }
        }
    }

    // ── 2. Plugins ──────────────────────────────────────────────────────────

    fun updatePluginTargetDraft(value: String) {
        _state.update { it.copy(pluginTargetDraft = value) }
    }

    fun setPluginValidateStrict(strict: Boolean) {
        _state.update { it.copy(pluginValidateStrict = strict) }
    }

    fun validatePluginTarget() {
        val target = _state.value.pluginTargetDraft.trim()
        if (target.isEmpty()) {
            _state.update { it.copy(errorBanner = "Enter a plugin directory path or URL to validate.") }
            return
        }
        val strict = _state.value.pluginValidateStrict
        scope.launch {
            _state.update { it.copy(isMutating = true, errorBanner = null, lastPluginValidation = null) }
            runCatching {
                ecosystemApi.validatePlugin(ValidatePluginRequestDto(target = target, strict = strict))
            }.onSuccess { result ->
                _state.update { it.copy(isMutating = false, lastPluginValidation = result) }
            }.onFailure { err ->
                _state.update { it.copy(isMutating = false, errorBanner = formatError(err)) }
            }
        }
    }

    fun installPluginTarget() {
        val target = _state.value.pluginTargetDraft.trim()
        if (target.isEmpty()) {
            _state.update { it.copy(errorBanner = "Enter a plugin directory path or URL to install.") }
            return
        }
        scope.launch {
            _state.update { it.copy(isMutating = true, errorBanner = null) }
            runCatching {
                val installRes = ecosystemApi.installPlugin(InstallPluginRequestDto(target = target))
                val listRes = ecosystemApi.listPlugins()
                installRes to listRes
            }.onSuccess { (installRes, listRes) ->
                _state.update {
                    it.copy(
                        isMutating = false,
                        pluginTargetDraft = "",
                        plugins = ControlDeckValidation.mergePluginCatalog(listRes),
                        lastPluginInstallMessage = installRes.output.ifBlank { "Installed ${installRes.target}" },
                        infoBanner = "Plugin installed.",
                    )
                }
            }.onFailure { err ->
                _state.update { it.copy(isMutating = false, errorBanner = formatError(err)) }
            }
        }
    }

    fun togglePluginEnabled(name: String, enabled: Boolean) {
        scope.launch {
            _state.update { it.copy(isMutating = true, errorBanner = null) }
            runCatching {
                if (enabled) {
                    ecosystemApi.enablePlugin(name)
                } else {
                    ecosystemApi.disablePlugin(name)
                }
            }.onSuccess { res ->
                _state.update { st ->
                    st.copy(
                        isMutating = false,
                        plugins = st.plugins.map { p ->
                            if (p.name == name) p.copy(enabled = res.enabled) else p
                        },
                    )
                }
            }.onFailure { err ->
                _state.update { it.copy(isMutating = false, errorBanner = formatError(err)) }
            }
        }
    }

    fun requestUninstallPlugin(name: String) {
        _state.update { it.copy(pendingUninstallPluginName = name) }
    }

    fun cancelUninstallPlugin() {
        _state.update { it.copy(pendingUninstallPluginName = null) }
    }

    fun confirmUninstallPlugin() {
        val targetName = _state.value.pendingUninstallPluginName ?: return
        scope.launch {
            _state.update { it.copy(isMutating = true, errorBanner = null) }
            runCatching { ecosystemApi.uninstallPlugin(targetName) }
                .onSuccess {
                    _state.update { st ->
                        st.copy(
                            isMutating = false,
                            pendingUninstallPluginName = null,
                            plugins = st.plugins.filterNot { it.name == targetName && !it.builtin },
                            infoBanner = "Uninstalled plugin '$targetName'.",
                        )
                    }
                }
                .onFailure { err ->
                    _state.update {
                        it.copy(
                            isMutating = false,
                            pendingUninstallPluginName = null,
                            errorBanner = formatError(err),
                        )
                    }
                }
        }
    }

    // ── 3. Agents & Workspace Allow-Rules ───────────────────────────────────

    fun openNewAgentSheet() {
        _state.update {
            it.copy(
                showAgentEditorSheet = true,
                agentForm = AgentEditorFormState(),
                errorBanner = null,
            )
        }
    }

    fun openEditAgentSheet(agent: AgentItemDto) {
        if (!agent.editable) return
        _state.update {
            it.copy(
                showAgentEditorSheet = true,
                agentForm = AgentEditorFormState(
                    isEditingExisting = true,
                    slug = agent.slug.ifBlank { agent.name },
                    description = agent.description,
                    prompt = agent.prompt,
                ),
                errorBanner = null,
            )
        }
    }

    fun dismissAgentEditorSheet() {
        _state.update { it.copy(showAgentEditorSheet = false) }
    }

    fun updateAgentForm(transform: (AgentEditorFormState) -> AgentEditorFormState) {
        _state.update { it.copy(agentForm = transform(it.agentForm)) }
    }

    fun submitAgentForm() {
        val form = _state.value.agentForm
        val cleanSlug = form.slug.trim()
        if (!ControlDeckValidation.isValidAgentSlug(cleanSlug)) {
            _state.update {
                it.copy(errorBanner = "Agent slug must match [a-z0-9][a-z0-9_-]{0,63}.")
            }
            return
        }
        if (form.prompt.isBlank()) {
            _state.update { it.copy(errorBanner = "Agent markdown prompt cannot be empty.") }
            return
        }

        scope.launch {
            _state.update { it.copy(isMutating = true, errorBanner = null) }
            runCatching {
                ecosystemApi.upsertAgent(
                    UpsertAgentRequestDto(
                        name = cleanSlug,
                        description = form.description.trim(),
                        prompt = form.prompt,
                    ),
                )
                ecosystemApi.listAgents()
            }.onSuccess { refreshed ->
                _state.update {
                    it.copy(
                        isMutating = false,
                        showAgentEditorSheet = false,
                        agents = refreshed.items,
                        infoBanner = "Saved agent '$cleanSlug'.",
                    )
                }
            }.onFailure { err ->
                _state.update { it.copy(isMutating = false, errorBanner = formatError(err)) }
            }
        }
    }

    fun requestDeleteAgent(slug: String) {
        _state.update { it.copy(pendingDeleteAgentSlug = slug) }
    }

    fun cancelDeleteAgent() {
        _state.update { it.copy(pendingDeleteAgentSlug = null) }
    }

    fun confirmDeleteAgent() {
        val slug = _state.value.pendingDeleteAgentSlug ?: return
        scope.launch {
            _state.update { it.copy(isMutating = true, errorBanner = null) }
            runCatching { ecosystemApi.deleteAgent(slug) }
                .onSuccess {
                    _state.update { st ->
                        st.copy(
                            isMutating = false,
                            pendingDeleteAgentSlug = null,
                            agents = st.agents.filterNot { (it.slug.ifBlank { it.name }) == slug && it.editable },
                            infoBanner = "Deleted agent '$slug'.",
                        )
                    }
                }
                .onFailure { err ->
                    _state.update {
                        it.copy(
                            isMutating = false,
                            pendingDeleteAgentSlug = null,
                            errorBanner = formatError(err),
                        )
                    }
                }
        }
    }

    fun refreshPermissionRules() {
        scope.launch {
            runCatching { supplementaryApi.listPermissionRules(workspace = null) }
                .onSuccess { res ->
                    _state.update { it.copy(permissionRules = res.items) }
                }
                .onFailure { err ->
                    _state.update { it.copy(errorBanner = formatError(err)) }
                }
        }
    }

    fun revokePermissionRule(ruleId: String) {
        scope.launch {
            _state.update { it.copy(isMutating = true, errorBanner = null) }
            runCatching { supplementaryApi.revokePermissionRule(ruleId) }
                .onSuccess {
                    _state.update { st ->
                        st.copy(
                            isMutating = false,
                            permissionRules = st.permissionRules.filterNot { it.ruleId == ruleId },
                            infoBanner = "Revoked workspace allow-rule.",
                        )
                    }
                }
                .onFailure { err ->
                    _state.update { it.copy(isMutating = false, errorBanner = formatError(err)) }
                }
        }
    }

    // ── 4. Allowlisted Settings & Gateway Default Model ─────────────────────

    fun updateAllowlistedSetting(settingKey: String, enabled: Boolean) {
        val patchBody = runCatching {
            ControlDeckValidation.buildAllowlistedSettingsPatch(settingKey, enabled)
        }.getOrElse { err ->
            _state.update { it.copy(errorBanner = err.message) }
            return
        }

        scope.launch {
            _state.update { it.copy(isMutating = true, errorBanner = null) }
            runCatching { ecosystemApi.patchSettings(patchBody) }
                .onSuccess { res ->
                    _state.update { st ->
                        st.copy(
                            isMutating = false,
                            previewFeaturesEnabled = res.settings.general.previewFeatures
                                ?: st.previewFeaturesEnabled,
                            promptCompletionEnabled = res.settings.general.enablePromptCompletion
                                ?: st.promptCompletionEnabled,
                        )
                    }
                }
                .onFailure { err ->
                    _state.update { it.copy(isMutating = false, errorBanner = formatError(err)) }
                }
        }
    }

    fun updateGatewayDefaultModel(model: String, thoughtLevel: String?) {
        scope.launch {
            _state.update { it.copy(isMutating = true, errorBanner = null) }
            runCatching {
                ecosystemApi.setDefaultModel(
                    SetDefaultModelRequestDto(
                        model = model,
                        thoughtLevel = thoughtLevel,
                    ),
                )
            }.onSuccess { res ->
                _state.update {
                    it.copy(
                        isMutating = false,
                        gatewayDefaultModel = res.model ?: model,
                        gatewayDefaultThoughtLevel = res.thoughtLevel ?: thoughtLevel,
                        infoBanner = "Updated gateway default model.",
                    )
                }
            }.onFailure { err ->
                _state.update { it.copy(isMutating = false, errorBanner = formatError(err)) }
            }
        }
    }

    fun requestDeleteHost(hostId: String) {
        _state.update { it.copy(pendingDeleteHostId = hostId) }
    }

    fun cancelDeleteHost() {
        _state.update { it.copy(pendingDeleteHostId = null) }
    }

    fun consumeDeleteHostTarget(): String? {
        val hid = _state.value.pendingDeleteHostId
        _state.update { it.copy(pendingDeleteHostId = null) }
        return hid
    }

    fun dismissBanners() {
        _state.update { it.copy(errorBanner = null, infoBanner = null) }
    }

    private fun formatError(err: Throwable): String = when (err) {
        is GatewayHttpException -> "${err.status} ${err.code}: ${err.message}"
        is GatewayException -> err.message
        else -> err.message ?: "Control Deck request failed"
    }
}
