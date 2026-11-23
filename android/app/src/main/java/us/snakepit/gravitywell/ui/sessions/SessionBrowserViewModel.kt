package us.snakepit.gravitywell.ui.sessions

import androidx.compose.runtime.Immutable
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import us.snakepit.gravitywell.cache.CachedHost
import us.snakepit.gravitywell.cache.CachedSession
import us.snakepit.gravitywell.cache.OfflineCacheStore
import us.snakepit.gravitywell.net.CapabilitiesDto
import us.snakepit.gravitywell.net.CreateSessionRequest
import us.snakepit.gravitywell.net.GatewayApi
import us.snakepit.gravitywell.net.GatewayHttpException
import us.snakepit.gravitywell.net.NetworkModule
import us.snakepit.gravitywell.net.RenameSessionRequest
import us.snakepit.gravitywell.net.ResumeSessionRequest
import us.snakepit.gravitywell.net.SessionListItemDto
import us.snakepit.gravitywell.net.SessionSnapshotDto
import us.snakepit.gravitywell.net.WsServerFrame

enum class SessionScopeFilter(val queryParam: String, val label: String) {
    ALL("all", "ALL"),
    LIVE("live", "LIVE"),
    ARCHIVED("archived", "ARCHIVED"),
}

@Immutable
data class SessionBrowserItem(
    val sessionId: String,
    val title: String,
    val titleSource: String = "acp",
    val preview: String = "",
    val cwd: String,
    val isLive: Boolean,
    val status: String,
    val stepCount: Int = 0,
    val modelId: String? = null,
    val thoughtLevel: String? = null,
    val modeId: String? = null,
    val updatedAt: String = "",
) {
    val cwdBasename: String
        get() = cwd.trimEnd('/').substringAfterLast('/').ifBlank { cwd }

    val hasPendingGate: Boolean
        get() = status == "awaiting_approval" || status == "awaiting_question"
}

@Immutable
data class NewSessionFormState(
    val cwd: String = "",
    val addDirs: List<String> = emptyList(),
    val addDirDraft: String = "",
    val title: String = "",
    val model: String = "gemini-4-neon",
    val thoughtLevel: String = "high",
    val mode: String = "default",
    val agent: String = "",
    val sandbox: Boolean = false,
    val isSubmitting: Boolean = false,
    val error: String? = null,
)

@Immutable
data class SessionBrowserUiState(
    val hostId: String = "",
    val agyVersion: String = "1.5.15",
    val defaultCwd: String = "",
    val capabilities: CapabilitiesDto = CapabilitiesDto(),
    val scope: SessionScopeFilter = SessionScopeFilter.ALL,
    val searchQuery: String = "",
    val selectedWorkspaceCwd: String? = null,
    val availableWorkspaces: List<String> = emptyList(),
    val sessions: List<SessionBrowserItem> = emptyList(),
    val externalSessions: List<ExternalSessionPresenceDto> = emptyList(),
    val radarExpanded: Boolean = false,
    val isLoading: Boolean = false,
    val errorBanner: String? = null,
    val infoNotice: String? = null,
    val resumePromptTarget: SessionBrowserItem? = null,
    val isResuming: Boolean = false,
    val renameTarget: SessionBrowserItem? = null,
    val renameDraft: String = "",
    val showNewSessionSheet: Boolean = false,
    val newSessionForm: NewSessionFormState = NewSessionFormState(),
) {
    val unsupervisedExternalCount: Int
        get() = externalSessions.count { !it.supervised }
}

class SessionBrowserController(
    private val host: CachedHost,
    private val gatewayApi: GatewayApi,
    private val supplementaryApi: SessionSupplementaryApi,
    private val cacheStore: OfflineCacheStore,
    private val scope: CoroutineScope,
    private val json: Json = NetworkModule.defaultJson,
    private val searchDebounceMs: Long = 250L,
) {
    private val hostCaps: CapabilitiesDto = runCatching {
        json.decodeFromString<CapabilitiesDto>(host.capabilitiesJson)
    }.getOrDefault(CapabilitiesDto())

    private val _state = MutableStateFlow(
        SessionBrowserUiState(
            hostId = host.hostId,
            agyVersion = host.agyVersion ?: "1.5.15",
            defaultCwd = host.defaultCwd.orEmpty(),
            capabilities = hostCaps,
            newSessionForm = NewSessionFormState(cwd = host.defaultCwd.orEmpty()),
        ),
    )
    val state: StateFlow<SessionBrowserUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        hydrateFromCache()
    }

    fun hydrateFromCache() {
        val snap = _state.value
        val isLiveFilter = when (snap.scope) {
            SessionScopeFilter.ALL -> null
            SessionScopeFilter.LIVE -> true
            SessionScopeFilter.ARCHIVED -> false
        }
        val cached = cacheStore.listSessions(host.hostId, isLiveFilter = isLiveFilter)
        val filtered = cached.filter { row ->
            val matchesCwd = snap.selectedWorkspaceCwd == null || row.cwd == snap.selectedWorkspaceCwd
            val q = snap.searchQuery.trim().lowercase()
            val matchesQ = q.isEmpty() ||
                row.title.lowercase().contains(q) ||
                row.preview.lowercase().contains(q) ||
                row.cwd.lowercase().contains(q)
            matchesCwd && matchesQ
        }
        val items = filtered.map { it.toBrowserItem() }
        val workspaces = collectWorkspaces(cacheStore.listSessions(host.hostId).map { it.cwd }, host.defaultCwd)
        _state.update {
            it.copy(
                sessions = items,
                availableWorkspaces = workspaces,
            )
        }
    }

    fun refresh() {
        val snap = _state.value
        _state.update { it.copy(isLoading = true, errorBanner = null) }
        scope.launch {
            try {
                val res = gatewayApi.listSessions(
                    status = snap.scope.queryParam,
                    query = snap.searchQuery.trim().ifEmpty { null },
                    cwd = snap.selectedWorkspaceCwd,
                    limit = 100,
                    offset = 0,
                )
                val existingById = cacheStore.listSessions(host.hostId).associateBy { it.sessionId }
                val mapped = res.items.map { dto ->
                    val existing = existingById[dto.sessionId]
                    val titleSource = existing?.titleSource ?: "acp"
                    cacheStore.upsertSession(dto.toCachedSession(host.hostId, existing))
                    dto.toBrowserItem(titleSource = titleSource)
                }
                val allKnownCwds = (cacheStore.listSessions(host.hostId).map { it.cwd } + mapped.map { it.cwd })
                val workspaces = collectWorkspaces(allKnownCwds, host.defaultCwd)
                _state.update {
                    it.copy(
                        sessions = mapped,
                        availableWorkspaces = workspaces,
                        isLoading = false,
                        errorBanner = null,
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                hydrateFromCache()
                _state.update {
                    it.copy(
                        isLoading = false,
                        errorBanner = t.message ?: "Unable to refresh sessions (showing offline cache)",
                    )
                }
            }

            // Refresh FLOCK-verified External Session Radar (D10)
            runCatching {
                val obs = supplementaryApi.getObservatoryStatus()
                _state.update { it.copy(externalSessions = obs.externalSessions) }
            }
        }
    }

    fun setScope(newScope: SessionScopeFilter) {
        if (_state.value.scope == newScope) return
        _state.update { it.copy(scope = newScope) }
        hydrateFromCache()
        refresh()
    }

    fun setSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
        hydrateFromCache()
        searchJob?.cancel()
        searchJob = scope.launch {
            if (searchDebounceMs > 0L) {
                delay(searchDebounceMs)
            }
            refresh()
        }
    }

    fun selectWorkspace(cwd: String?) {
        _state.update { it.copy(selectedWorkspaceCwd = cwd) }
        hydrateFromCache()
        refresh()
    }

    fun toggleRadarExpanded() {
        _state.update { it.copy(radarExpanded = !it.radarExpanded) }
    }

    fun dismissNotices() {
        _state.update { it.copy(errorBanner = null, infoNotice = null) }
    }

    fun onSessionRowClick(
        item: SessionBrowserItem,
        onOpenLiveConsole: (String) -> Unit,
    ) {
        if (item.isLive && item.status != "archived" && item.status != "closed") {
            onOpenLiveConsole(item.sessionId)
        } else {
            _state.update { it.copy(resumePromptTarget = item) }
        }
    }

    fun dismissResumePrompt() {
        _state.update { it.copy(resumePromptTarget = null, isResuming = false) }
    }

    fun confirmResumeSession(
        sessionId: String,
        cwd: String?,
        loadHistory: Boolean,
        onResumed: (String) -> Unit,
    ) {
        _state.update { it.copy(isResuming = true, errorBanner = null) }
        scope.launch {
            try {
                val snap = gatewayApi.resumeSession(
                    sessionId = sessionId,
                    body = ResumeSessionRequest(
                        cwd = cwd?.ifBlank { null },
                        loadHistory = loadHistory,
                    ),
                )
                cacheStore.upsertSession(snap.toCachedSession(host.hostId))
                _state.update { it.copy(resumePromptTarget = null, isResuming = false) }
                refresh()
                onResumed(snap.sessionId)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        isResuming = false,
                        errorBanner = t.message ?: "Failed to resume session",
                    )
                }
            }
        }
    }

    fun openRenameDialog(item: SessionBrowserItem) {
        _state.update { it.copy(renameTarget = item, renameDraft = item.title) }
    }

    fun updateRenameDraft(value: String) {
        _state.update { it.copy(renameDraft = value) }
    }

    fun dismissRenameDialog() {
        _state.update { it.copy(renameTarget = null, renameDraft = "") }
    }

    fun submitRename() {
        val target = _state.value.renameTarget ?: return
        val cleanTitle = _state.value.renameDraft.trim()
        if (cleanTitle.isEmpty()) return

        scope.launch {
            try {
                val snap = gatewayApi.renameSession(
                    sessionId = target.sessionId,
                    body = RenameSessionRequest(title = cleanTitle),
                )
                cacheStore.upsertSession(snap.toCachedSession(host.hostId))
                _state.update { cur ->
                    cur.copy(
                        renameTarget = null,
                        renameDraft = "",
                        sessions = cur.sessions.map { row ->
                            if (row.sessionId == target.sessionId) {
                                row.copy(title = snap.title, titleSource = snap.titleSource)
                            } else {
                                row
                            }
                        },
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _state.update { it.copy(errorBanner = t.message ?: "Failed to rename session") }
            }
        }
    }

    fun closeLiveSession(sessionId: String) {
        scope.launch {
            try {
                gatewayApi.closeSession(sessionId)
                cacheStore.getSession(host.hostId, sessionId)?.let { existing ->
                    cacheStore.upsertSession(existing.copy(isLive = false, status = "closed"))
                }
                refresh()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _state.update { it.copy(errorBanner = t.message ?: "Failed to close session") }
            }
        }
    }

    fun attemptForkSession(
        item: SessionBrowserItem,
        onForked: (String) -> Unit = {},
    ) {
        if (!_state.value.capabilities.fork) {
            _state.update {
                it.copy(
                    infoNotice = "-32601 unsupported on agy ${it.agyVersion} — create a new session in ${item.cwdBasename} or resume with Load History.",
                )
            }
            return
        }
        scope.launch {
            try {
                val forked = supplementaryApi.forkSession(
                    sessionId = item.sessionId,
                    body = ForkSessionRequestDto(cwd = item.cwd),
                )
                cacheStore.upsertSession(forked.toCachedSession(host.hostId))
                refresh()
                onForked(forked.sessionId)
            } catch (http: GatewayHttpException) {
                if (http.status == 501 || http.code == "acp_method_unsupported") {
                    _state.update {
                        it.copy(
                            infoNotice = "${http.message} Create a new session in ${item.cwdBasename} or resume with Load History.",
                        )
                    }
                } else {
                    _state.update { it.copy(errorBanner = http.message) }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _state.update { it.copy(errorBanner = t.message ?: "Fork failed") }
            }
        }
    }

    // ── New Session Sheet ───────────────────────────────────────────────────

    fun openNewSessionSheet() {
        val defaultCwd = _state.value.selectedWorkspaceCwd
            ?: host.defaultCwd
            ?: _state.value.availableWorkspaces.firstOrNull()
            ?: ""
        _state.update {
            it.copy(
                showNewSessionSheet = true,
                newSessionForm = NewSessionFormState(cwd = defaultCwd),
            )
        }
    }

    fun dismissNewSessionSheet() {
        _state.update { it.copy(showNewSessionSheet = false) }
    }

    fun updateNewSessionForm(transform: (NewSessionFormState) -> NewSessionFormState) {
        _state.update { it.copy(newSessionForm = transform(it.newSessionForm)) }
    }

    fun addExtraDirectoryChip() {
        val draft = _state.value.newSessionForm.addDirDraft.trim()
        if (draft.isEmpty()) return
        _state.update { cur ->
            val updated = (cur.newSessionForm.addDirs + draft).distinct()
            cur.copy(newSessionForm = cur.newSessionForm.copy(addDirs = updated, addDirDraft = ""))
        }
    }

    fun removeExtraDirectoryChip(dir: String) {
        _state.update { cur ->
            cur.copy(newSessionForm = cur.newSessionForm.copy(addDirs = cur.newSessionForm.addDirs - dir))
        }
    }

    fun submitCreateSession(onCreated: (String) -> Unit) {
        val form = _state.value.newSessionForm
        val cleanCwd = form.cwd.trim()
        if (cleanCwd.isEmpty()) {
            updateNewSessionForm { it.copy(error = "Workspace cwd is required.") }
            return
        }
        updateNewSessionForm { it.copy(isSubmitting = true, error = null) }
        scope.launch {
            try {
                val created = gatewayApi.createSession(
                    CreateSessionRequest(
                        cwd = cleanCwd,
                        addDirs = form.addDirs,
                        title = form.title.trim().ifEmpty { null },
                        model = form.model.trim().ifEmpty { null },
                        thoughtLevel = form.thoughtLevel.trim().ifEmpty { null },
                        mode = form.mode.trim().ifEmpty { null },
                        agent = form.agent.trim().ifEmpty { null },
                        sandbox = form.sandbox,
                    ),
                )
                cacheStore.upsertSession(created.toCachedSession(host.hostId))
                _state.update {
                    it.copy(
                        showNewSessionSheet = false,
                        newSessionForm = NewSessionFormState(cwd = host.defaultCwd.orEmpty()),
                    )
                }
                refresh()
                onCreated(created.sessionId)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                updateNewSessionForm {
                    it.copy(
                        isSubmitting = false,
                        error = t.message ?: "Failed to create session",
                    )
                }
            }
        }
    }

    fun onWebSocketFrame(frame: WsServerFrame) {
        when (frame) {
            is WsServerFrame.Event -> {
                when (frame.type) {
                    "presence_update" -> {
                        val arr = frame.payload["external_sessions"] as? JsonArray ?: return
                        val parsed = runCatching {
                            json.decodeFromJsonElement<List<ExternalSessionPresenceDto>>(arr)
                        }.getOrNull() ?: return
                        _state.update { it.copy(externalSessions = parsed) }
                    }
                    "session_started",
                    "session_status",
                    "session_info_update",
                    "session_closed",
                    "permission_requested",
                    "permission_resolved",
                    "question_requested",
                    "question_resolved",
                    "turn_completed",
                    -> hydrateFromCache()
                }
            }
            else -> Unit
        }
    }

    companion object {
        internal fun collectWorkspaces(cwds: List<String>, defaultCwd: String?): List<String> {
            val ordered = LinkedHashSet<String>()
            if (!defaultCwd.isNullOrBlank()) ordered += defaultCwd.trim()
            cwds.map { it.trim() }.filter { it.isNotEmpty() }.forEach { ordered += it }
            return ordered.toList()
        }
    }
}

internal fun CachedSession.toBrowserItem(): SessionBrowserItem = SessionBrowserItem(
    sessionId = sessionId,
    title = title,
    titleSource = titleSource,
    preview = preview,
    cwd = cwd,
    isLive = isLive,
    status = status,
    stepCount = lastSeq.toInt(),
    modelId = modelId,
    thoughtLevel = thoughtLevel,
    modeId = modeId,
    updatedAt = updatedAt,
)

internal fun SessionListItemDto.toBrowserItem(titleSource: String = "acp"): SessionBrowserItem =
    SessionBrowserItem(
        sessionId = sessionId,
        title = title.ifBlank { "Untitled Session" },
        titleSource = titleSource,
        preview = preview,
        cwd = cwd,
        isLive = isLive,
        status = status,
        stepCount = stepCount,
        modelId = modelId,
        thoughtLevel = thoughtLevel,
        modeId = modeId,
        updatedAt = updatedAt,
    )

internal fun SessionListItemDto.toCachedSession(
    hostId: String,
    existing: CachedSession? = null,
): CachedSession = CachedSession(
    hostId = hostId,
    sessionId = sessionId,
    title = title.ifBlank { existing?.title ?: "Untitled Session" },
    titleSource = existing?.titleSource ?: "acp",
    preview = preview.ifBlank { existing?.preview.orEmpty() },
    cwd = cwd.ifBlank { existing?.cwd.orEmpty() },
    addDirsJson = existing?.addDirsJson ?: "[]",
    status = status,
    isLive = isLive,
    modelId = modelId ?: existing?.modelId,
    thoughtLevel = thoughtLevel ?: existing?.thoughtLevel,
    modeId = modeId ?: existing?.modeId,
    configJson = existing?.configJson ?: "[]",
    commandsJson = existing?.commandsJson ?: "[]",
    usedTokens = existing?.usedTokens ?: 0L,
    contextSize = existing?.contextSize ?: 0L,
    lastCursor = existing?.lastCursor ?: 0L,
    lastSeq = maxOf(existing?.lastSeq ?: 0L, stepCount.toLong()),
    updatedAt = updatedAt.ifBlank { existing?.updatedAt ?: Instant.now().toString() },
)

internal fun SessionSnapshotDto.toCachedSession(
    hostId: String,
    json: Json = NetworkModule.defaultJson,
): CachedSession {
    val modelVal = configOptions.firstOrNull { it.id == "model" }?.currentValue
    val thoughtVal = configOptions.firstOrNull { it.id == "thought_level" }?.currentValue
    val modeVal = configOptions.firstOrNull { it.id == "mode" }?.currentValue
    return CachedSession(
        hostId = hostId,
        sessionId = sessionId,
        title = title.ifBlank { "Untitled Session" },
        titleSource = titleSource,
        preview = "",
        cwd = cwd,
        addDirsJson = json.encodeToString(addDirs),
        status = status,
        isLive = isLive,
        modelId = modelVal,
        thoughtLevel = thoughtVal,
        modeId = modeVal,
        configJson = json.encodeToString(configOptions),
        commandsJson = json.encodeToString(availableCommands),
        usedTokens = usage.used,
        contextSize = usage.size,
        lastCursor = cursor,
        lastSeq = seq,
        updatedAt = updatedAt.ifBlank { Instant.now().toString() },
    )
}

internal fun basenameOfPath(path: String): String {
    val clean = path.trimEnd('/')
    if (clean.isEmpty()) return "/"
    return File(clean).name.ifEmpty { clean }
}
