package us.snakepit.gravitywell.ui.console

import androidx.compose.runtime.Immutable
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
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
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import us.snakepit.gravitywell.cache.CachedComposerDraft
import us.snakepit.gravitywell.cache.CachedHost
import us.snakepit.gravitywell.cache.CachedPendingApproval
import us.snakepit.gravitywell.cache.CachedQueuedFollowup
import us.snakepit.gravitywell.cache.CachedSessionEvent
import us.snakepit.gravitywell.cache.OfflineCacheStore
import us.snakepit.gravitywell.net.EditQueueItemRequest
import us.snakepit.gravitywell.net.GatewayApi
import us.snakepit.gravitywell.net.GatewayHttpException
import us.snakepit.gravitywell.net.NetworkModule
import us.snakepit.gravitywell.net.RenameSessionRequest
import us.snakepit.gravitywell.net.ResolvePermissionRequest
import us.snakepit.gravitywell.net.ResolveQuestionRequest
import us.snakepit.gravitywell.net.SendNowRequest
import us.snakepit.gravitywell.net.SubmitPromptRequest
import us.snakepit.gravitywell.net.UpdateConfigRequest
import us.snakepit.gravitywell.net.WsConnectionState
import us.snakepit.gravitywell.net.WsServerFrame
import us.snakepit.gravitywell.ui.sessions.ArtifactItemDto
import us.snakepit.gravitywell.ui.sessions.PermissionRuleDto
import us.snakepit.gravitywell.ui.sessions.SessionSupplementaryApi
import us.snakepit.gravitywell.ui.sessions.WorkspaceFileItemDto
import us.snakepit.gravitywell.ui.sessions.downloadBrainArtifact
import us.snakepit.gravitywell.ui.sessions.toCachedSession

@Immutable
data class LoadedBrainArtifact(
    val item: ArtifactItemDto,
    val mediaType: String,
    val textContent: String? = null,
    val imageBytes: ByteArray? = null,
    val isSecretRedactedText: Boolean = false,
)

@Immutable
data class SessionConsoleUiState(
    val hostId: String = "",
    val session: ReducedSessionState = ReducedSessionState(),
    val streamIndicator: ConsoleStreamIndicator = ConsoleStreamIndicator.OFFLINE,
    val draftText: String = "",
    val draftCursorIndex: Int = 0,
    val draftAttachments: List<DraftAttachmentRecord> = emptyList(),
    val attachmentThumbnails: Map<String, ByteArray> = emptyMap(),
    val activeAtMention: AtFileMentionQuery? = null,
    val atFileCandidates: List<WorkspaceFileItemDto> = emptyList(),
    val isSearchingAtFiles: Boolean = false,
    val forceShowSlashChips: Boolean = false,
    val expandedThoughtIds: Set<String> = emptySet(),
    val expandedToolIds: Set<String> = emptySet(),
    val toolDiffModes: Map<String, DiffDisplayMode> = emptyMap(),
    val questionAnswersDraft: Map<String, Map<String, String>> = emptyMap(),
    val editingQueueItemId: String? = null,
    val editingQueueText: String = "",
    val showRenameDialog: Boolean = false,
    val renameDraft: String = "",
    val showConfigSheet: Boolean = false,
    val showArtifactsSheet: Boolean = false,
    val artifacts: List<ArtifactItemDto> = emptyList(),
    val isLoadingArtifacts: Boolean = false,
    val selectedArtifact: LoadedBrainArtifact? = null,
    val showWorkspaceRulesSheet: Boolean = false,
    val workspaceRules: List<PermissionRuleDto> = emptyList(),
    val isSubmittingPrompt: Boolean = false,
    val errorBanner: String? = null,
)

class SessionConsoleController(
    private val host: CachedHost,
    private val sessionId: String,
    private val baseUrl: HttpUrl,
    private val okHttpClient: OkHttpClient,
    private val gatewayApi: GatewayApi,
    private val supplementaryApi: SessionSupplementaryApi,
    private val cacheStore: OfflineCacheStore,
    private val scope: CoroutineScope,
    private val json: Json = NetworkModule.defaultJson,
    private val fileSearchDebounceMs: Long = 180L,
    private val nowIso: () -> String = { Instant.now().toString() },
) {
    private val _state = MutableStateFlow(
        SessionConsoleUiState(
            hostId = host.hostId,
            session = ReducedSessionState(sessionId = sessionId),
        ),
    )
    val state: StateFlow<SessionConsoleUiState> = _state.asStateFlow()

    private var atFileSearchJob: Job? = null
    private var sawResyncLatch: Boolean = false

    init {
        hydrateFromCache()
    }

    fun hydrateFromCache() {
        val cachedSession = cacheStore.getSession(host.hostId, sessionId)
        val initialReduced = if (cachedSession != null) {
            ConsoleStreamReducer.fromCachedSession(cachedSession, json)
        } else {
            ReducedSessionState(sessionId = sessionId, cwd = host.defaultCwd.orEmpty())
        }
        val cachedEvents = cacheStore.listSessionEvents(host.hostId, sessionId, afterCursor = 0L)
        val reducedWithEvents = ConsoleStreamReducer.applyCachedEvents(initialReduced, cachedEvents, json)
        val cachedDraft = cacheStore.getComposerDraft(host.hostId, sessionId)
        val restoredAttachments = cachedDraft?.attachmentsJson?.let { raw ->
            runCatching { json.decodeFromString<List<DraftAttachmentRecord>>(raw) }.getOrNull()
        }.orEmpty()

        _state.update { cur ->
            cur.copy(
                session = reducedWithEvents,
                draftText = cachedDraft?.draftText ?: cur.draftText,
                draftCursorIndex = (cachedDraft?.draftText ?: cur.draftText).length,
                draftAttachments = if (cur.draftAttachments.isEmpty()) restoredAttachments else cur.draftAttachments,
            )
        }
    }

    fun syncWithGateway() {
        scope.launch {
            try {
                val snap = gatewayApi.getSession(sessionId)
                val existingCached = cacheStore.getSession(host.hostId, sessionId)
                cacheStore.upsertSession(snap.toCachedSession(host.hostId, json, existingCached))
                persistQueueAndApprovalsToCache(snap)

                val afterCursor = _state.value.session.lastCursor
                val eventsResp = runCatching {
                    gatewayApi.getSessionEvents(sessionId = sessionId, after = afterCursor, limit = 2000)
                }.getOrNull()

                if (eventsResp != null && eventsResp.truncated) {
                    sawResyncLatch = true
                    val fullEvents = runCatching {
                        gatewayApi.getSessionEvents(sessionId = sessionId, after = 0L, limit = 2000)
                    }.getOrNull()
                    if (fullEvents != null) {
                        persistHttpEvents(fullEvents.events)
                        _state.update { cur ->
                            val resetBase = ConsoleStreamReducer.mergeSnapshot(
                                ReducedSessionState(sessionId = sessionId),
                                snap,
                            )
                            cur.copy(
                                session = ConsoleStreamReducer.applyHttpEvents(resetBase, fullEvents.events, json),
                                streamIndicator = ConsoleStreamIndicator.RESYNCED,
                                errorBanner = null,
                            )
                        }
                    }
                } else {
                    if (eventsResp != null && eventsResp.events.isNotEmpty()) {
                        persistHttpEvents(eventsResp.events)
                    }
                    val queueResp = runCatching {
                        if (snap.isLive) gatewayApi.getQueue(sessionId) else null
                    }.getOrNull()

                    _state.update { cur ->
                        var nextSession = ConsoleStreamReducer.mergeSnapshot(cur.session, snap)
                        if (eventsResp != null && eventsResp.events.isNotEmpty()) {
                            nextSession = ConsoleStreamReducer.applyHttpEvents(nextSession, eventsResp.events, json)
                        }
                        if (queueResp != null) {
                            nextSession = nextSession.copy(
                                queuedFollowups = queueResp.items,
                                queuePaused = queueResp.paused,
                            )
                        }
                        cur.copy(
                            session = nextSession,
                            errorBanner = null,
                        )
                    }
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(errorBanner = t.message ?: "Offline mode — showing cached trajectory")
                }
            }
        }
    }

    fun onWsConnectionStateChanged(wsState: WsConnectionState) {
        val indicator = when (wsState) {
            is WsConnectionState.Live -> {
                if (sawResyncLatch) ConsoleStreamIndicator.RESYNCED else ConsoleStreamIndicator.LIVE
            }
            is WsConnectionState.Replaying -> ConsoleStreamIndicator.REPLAYING
            is WsConnectionState.Connecting,
            is WsConnectionState.Reconnecting,
            -> ConsoleStreamIndicator.RECONNECTING
            is WsConnectionState.Disconnected,
            is WsConnectionState.AuthFailed,
            -> ConsoleStreamIndicator.OFFLINE
        }
        _state.update { it.copy(streamIndicator = indicator) }
    }

    fun onWebSocketFrame(frame: WsServerFrame) {
        when (frame) {
            is WsServerFrame.Event -> {
                if (frame.sessionId != sessionId) return
                sawResyncLatch = false
                val nextSession = ConsoleStreamReducer.applyWsEvent(_state.value.session, frame, json)
                val existing = cacheStore.getSession(host.hostId, sessionId)
                val modelVal = nextSession.configOptions.firstOrNull { it.id == "model" }?.currentValue ?: existing?.modelId
                val thoughtVal = nextSession.configOptions.firstOrNull { it.id == "thought_level" }?.currentValue ?: existing?.thoughtLevel
                val modeVal = nextSession.configOptions.firstOrNull { it.id == "mode" }?.currentValue ?: existing?.modeId
                cacheStore.upsertSession(
                    us.snakepit.gravitywell.cache.CachedSession(
                        hostId = host.hostId,
                        sessionId = sessionId,
                        title = nextSession.title.ifBlank { existing?.title ?: "Live Session" },
                        titleSource = nextSession.titleSource,
                        preview = existing?.preview.orEmpty(),
                        cwd = nextSession.cwd.ifBlank { existing?.cwd ?: host.defaultCwd.orEmpty() },
                        addDirsJson = if (nextSession.addDirs.isNotEmpty()) {
                            json.encodeToString(nextSession.addDirs)
                        } else {
                            existing?.addDirsJson ?: "[]"
                        },
                        status = nextSession.status,
                        isLive = nextSession.isLive,
                        isPinned = nextSession.isPinned,
                        pinnedAt = existing?.pinnedAt,
                        isArchived = nextSession.isArchived,
                        modelId = modelVal,
                        thoughtLevel = thoughtVal,
                        modeId = modeVal,
                        configJson = if (nextSession.configOptions.isNotEmpty()) {
                            json.encodeToString(nextSession.configOptions)
                        } else {
                            existing?.configJson ?: "[]"
                        },
                        commandsJson = if (nextSession.availableCommands.isNotEmpty()) {
                            json.encodeToString(nextSession.availableCommands)
                        } else {
                            existing?.commandsJson ?: "[]"
                        },
                        usedTokens = if (nextSession.usage.used > 0L) nextSession.usage.used else (existing?.usedTokens ?: 0L),
                        contextSize = if (nextSession.usage.size > 0L) nextSession.usage.size else (existing?.contextSize ?: 0L),
                        lastCursor = maxOf(existing?.lastCursor ?: 0L, nextSession.lastCursor, frame.cursor),
                        lastSeq = maxOf(existing?.lastSeq ?: 0L, nextSession.lastSeq, frame.seq),
                        updatedAt = frame.ts.ifBlank { existing?.updatedAt ?: nowIso() },
                    ),
                )
                if (frame.seq > 0L) {
                    cacheStore.insertSessionEvent(
                        CachedSessionEvent(
                            hostId = host.hostId,
                            sessionId = sessionId,
                            seq = frame.seq,
                            cursor = frame.cursor,
                            eventType = frame.type,
                            payloadJson = frame.payload.toString(),
                            ts = frame.ts,
                        ),
                    )
                }
                if (
                    frame.type == "permission_requested" ||
                    frame.type == "permission_resolved" ||
                    frame.type == "question_requested" ||
                    frame.type == "question_resolved" ||
                    frame.type == "session_closed"
                ) {
                    persistPendingApprovalsList(
                        permissions = nextSession.pendingPermissions,
                        questions = nextSession.pendingQuestions,
                    )
                }
                cacheStore.updateHostLastCursor(host.hostId, frame.cursor)
                _state.update { cur ->
                    cur.copy(
                        session = nextSession,
                        streamIndicator = if (cur.streamIndicator == ConsoleStreamIndicator.RESYNCED) {
                            ConsoleStreamIndicator.LIVE
                        } else {
                            cur.streamIndicator
                        },
                    )
                }
            }
            is WsServerFrame.ResyncRequired -> {
                sawResyncLatch = true
                _state.update { it.copy(streamIndicator = ConsoleStreamIndicator.RESYNCED) }
                syncWithGateway()
            }
            is WsServerFrame.ReplayComplete -> {
                _state.update {
                    it.copy(
                        streamIndicator = if (sawResyncLatch) ConsoleStreamIndicator.RESYNCED else ConsoleStreamIndicator.LIVE,
                    )
                }
            }
            else -> Unit
        }
    }

    // ── Composer Draft, @file Autocomplete & Slash Chips ────────────────────

    fun updateDraft(newText: String, cursorIndex: Int = newText.length) {
        val safeCursor = cursorIndex.coerceIn(0, newText.length)
        val mention = ComposerAutocompleteHelper.detectAtMention(newText, safeCursor)
        _state.update { cur ->
            cur.copy(
                draftText = newText,
                draftCursorIndex = safeCursor,
                activeAtMention = mention,
                atFileCandidates = if (mention == null) emptyList() else cur.atFileCandidates,
                forceShowSlashChips = if (newText.isEmpty()) false else cur.forceShowSlashChips,
            )
        }
        persistComposerDraftToCache()

        atFileSearchJob?.cancel()
        if (mention != null) {
            atFileSearchJob = scope.launch {
                if (fileSearchDebounceMs > 0L) {
                    delay(fileSearchDebounceMs)
                }
                _state.update { it.copy(isSearchingAtFiles = true) }
                val res = runCatching {
                    supplementaryApi.searchWorkspaceFiles(
                        sessionId = sessionId,
                        query = mention.query,
                        limit = 30,
                    )
                }.getOrNull()
                _state.update { cur ->
                    if (cur.activeAtMention?.query == mention.query) {
                        cur.copy(
                            atFileCandidates = res?.items.orEmpty(),
                            isSearchingAtFiles = false,
                        )
                    } else {
                        cur.copy(isSearchingAtFiles = false)
                    }
                }
            }
        }
    }

    fun selectAtFileCandidate(item: WorkspaceFileItemDto) {
        val snap = _state.value
        val mention = snap.activeAtMention ?: return
        val (updatedText, newCursor) = ComposerAutocompleteHelper.applyAtMentionReplacement(
            text = snap.draftText,
            mention = mention,
            selectedPath = item.path,
        )
        _state.update {
            it.copy(
                draftText = updatedText,
                draftCursorIndex = newCursor,
                activeAtMention = null,
                atFileCandidates = emptyList(),
            )
        }
        persistComposerDraftToCache()
    }

    fun dismissAtFileAutocomplete() {
        _state.update { it.copy(activeAtMention = null, atFileCandidates = emptyList()) }
    }

    fun toggleSlashCommandChips() {
        _state.update { it.copy(forceShowSlashChips = !it.forceShowSlashChips) }
    }

    fun selectSlashCommandChip(commandName: String) {
        val prefix = "/$commandName "
        val current = _state.value.draftText
        val trimmed = current.trimStart()
        val updated = if (trimmed.startsWith("/") && !trimmed.contains(' ')) {
            prefix
        } else if (current.isBlank()) {
            prefix
        } else {
            "$prefix$current"
        }
        updateDraft(updated, updated.length)
        _state.update { it.copy(forceShowSlashChips = false) }
    }

    // ── Magic-Byte Attachments (<= 10 MiB + Magic Verification) ─────────────

    fun rejectOversizedAttachment(filename: String, totalBytes: Long) {
        val rejected = AttachmentSniffer.validateSizeOnly(totalBytes) ?: SniffedAttachmentResult.Rejected(
            status = 413,
            code = "payload_too_large",
            message = "Attachment exceeds 10 MiB limit.",
        )
        val localId = "att_local_${UUID.randomUUID().toString().take(8)}"
        val record = DraftAttachmentRecord(
            localId = localId,
            filename = filename,
            mimeType = "application/octet-stream",
            sizeBytes = totalBytes,
            state = AttachmentUploadState.FAILED,
            errorStatus = rejected.status,
            errorCode = rejected.code,
            errorMessage = rejected.message,
        )
        _state.update { it.copy(draftAttachments = it.draftAttachments + record) }
        persistComposerDraftToCache()
    }

    fun attachFileBytes(
        filename: String,
        declaredContentType: String?,
        bytes: ByteArray,
    ) {
        val localId = "att_local_${UUID.randomUUID().toString().take(8)}"
        when (val sniffed = AttachmentSniffer.sniffAndValidate(bytes, filename, declaredContentType)) {
            is SniffedAttachmentResult.Rejected -> {
                val failed = DraftAttachmentRecord(
                    localId = localId,
                    filename = filename,
                    mimeType = declaredContentType ?: "application/octet-stream",
                    sizeBytes = bytes.size.toLong(),
                    state = AttachmentUploadState.FAILED,
                    errorStatus = sniffed.status,
                    errorCode = sniffed.code,
                    errorMessage = sniffed.message,
                )
                _state.update { it.copy(draftAttachments = it.draftAttachments + failed) }
                persistComposerDraftToCache()
            }

            is SniffedAttachmentResult.Valid -> {
                val pending = DraftAttachmentRecord(
                    localId = localId,
                    filename = filename,
                    mimeType = sniffed.mimeType,
                    sizeBytes = sniffed.sizeBytes,
                    state = AttachmentUploadState.UPLOADING,
                )
                val isImage = sniffed.mimeType.startsWith("image/")
                _state.update { cur ->
                    cur.copy(
                        draftAttachments = cur.draftAttachments + pending,
                        attachmentThumbnails = if (isImage) {
                            cur.attachmentThumbnails + (localId to bytes)
                        } else {
                            cur.attachmentThumbnails
                        },
                    )
                }
                persistComposerDraftToCache()

                scope.launch {
                    try {
                        val body = bytes.toRequestBody(sniffed.mimeType.toMediaType())
                        val part = MultipartBody.Part.createFormData("file", filename, body)
                        val uploaded = supplementaryApi.uploadAttachment(sessionId = sessionId, file = part)
                        _state.update { cur ->
                            cur.copy(
                                draftAttachments = cur.draftAttachments.map { rec ->
                                    if (rec.localId == localId) {
                                        rec.copy(
                                            attachmentId = uploaded.attachmentId,
                                            filename = uploaded.filename.ifBlank { rec.filename },
                                            mimeType = uploaded.mimeType.ifBlank { rec.mimeType },
                                            sizeBytes = uploaded.sizeBytes,
                                            state = AttachmentUploadState.READY,
                                            errorStatus = null,
                                            errorCode = null,
                                            errorMessage = null,
                                        )
                                    } else {
                                        rec
                                    }
                                },
                            )
                        }
                        persistComposerDraftToCache()
                    } catch (http: GatewayHttpException) {
                        _state.update { cur ->
                            cur.copy(
                                draftAttachments = cur.draftAttachments.map { rec ->
                                    if (rec.localId == localId) {
                                        rec.copy(
                                            state = AttachmentUploadState.FAILED,
                                            errorStatus = http.status,
                                            errorCode = http.code,
                                            errorMessage = http.message,
                                        )
                                    } else {
                                        rec
                                    }
                                },
                            )
                        }
                        persistComposerDraftToCache()
                    } catch (t: Throwable) {
                        _state.update { cur ->
                            cur.copy(
                                draftAttachments = cur.draftAttachments.map { rec ->
                                    if (rec.localId == localId) {
                                        rec.copy(
                                            state = AttachmentUploadState.FAILED,
                                            errorStatus = null,
                                            errorCode = "upload_error",
                                            errorMessage = t.message ?: "Attachment upload failed",
                                        )
                                    } else {
                                        rec
                                    }
                                },
                            )
                        }
                        persistComposerDraftToCache()
                    }
                }
            }
        }
    }

    fun removeAttachment(localId: String) {
        _state.update { cur ->
            cur.copy(
                draftAttachments = cur.draftAttachments.filterNot { it.localId == localId },
                attachmentThumbnails = cur.attachmentThumbnails - localId,
            )
        }
        persistComposerDraftToCache()
    }

    // ── Prompt Submit & Cancel Turn ─────────────────────────────────────────

    fun submitComposerPrompt() {
        val snap = _state.value
        val trimmedText = snap.draftText.trim()
        val readyAttachmentIds = snap.draftAttachments
            .filter { it.state == AttachmentUploadState.READY && !it.attachmentId.isNullOrBlank() }
            .mapNotNull { it.attachmentId }
        val hasUploading = snap.draftAttachments.any { it.state == AttachmentUploadState.UPLOADING }
        if (hasUploading || snap.isSubmittingPrompt) return
        if (trimmedText.isEmpty() && readyAttachmentIds.isEmpty()) return

        val promptText = trimmedText.ifEmpty { "Inspect attached file(s)." }
        _state.update { it.copy(isSubmittingPrompt = true, errorBanner = null) }

        scope.launch {
            try {
                val res = gatewayApi.submitPrompt(
                    sessionId = sessionId,
                    body = SubmitPromptRequest(
                        text = promptText,
                        attachmentIds = readyAttachmentIds,
                        enqueueIfBusy = true,
                        autoResume = !snap.session.isLive,
                        cwd = snap.session.cwd.ifBlank { host.defaultCwd },
                    ),
                )
                cacheStore.clearComposerDraft(host.hostId, sessionId)
                _state.update { cur ->
                    val nextQueued = if (res.status == "queued" && res.queueItem != null) {
                        (cur.session.queuedFollowups.filterNot { it.itemId == res.queueItem.itemId } + res.queueItem)
                            .sortedBy { it.position }
                    } else {
                        cur.session.queuedFollowups
                    }
                    cur.copy(
                        isSubmittingPrompt = false,
                        draftText = "",
                        draftCursorIndex = 0,
                        draftAttachments = emptyList(),
                        attachmentThumbnails = emptyMap(),
                        activeAtMention = null,
                        atFileCandidates = emptyList(),
                        session = cur.session.copy(queuedFollowups = nextQueued),
                    )
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        isSubmittingPrompt = false,
                        errorBanner = t.message ?: "Failed to submit prompt",
                    )
                }
            }
        }
    }

    fun submitSpokenPrompt(spokenText: String) {
        val clean = spokenText.trim()
        if (clean.isEmpty()) return
        val snap = _state.value
        scope.launch {
            try {
                gatewayApi.submitPrompt(
                    sessionId = sessionId,
                    body = SubmitPromptRequest(
                        text = clean,
                        enqueueIfBusy = true,
                        autoResume = !snap.session.isLive,
                        cwd = snap.session.cwd.ifBlank { host.defaultCwd },
                    ),
                )
            } catch (t: Throwable) {
                _state.update {
                    it.copy(errorBanner = t.message ?: "Failed to send spoken prompt")
                }
            }
        }
    }

    fun appendVoiceNotice(label: String, message: String) {
        _state.update { cur ->
            val notice = LedgerItem.SystemNotice(
                id = "voice_${UUID.randomUUID().toString().take(8)}",
                seq = cur.session.lastSeq,
                ts = nowIso(),
                label = label,
                message = message,
                isError = false,
            )
            cur.copy(session = cur.session.copy(ledger = cur.session.ledger + notice))
        }
    }

    fun togglePinSession(onChanged: () -> Unit = {}) {
        val nextPinned = !_state.value.session.isPinned
        scope.launch {
            try {
                val snap = gatewayApi.renameSession(sessionId, RenameSessionRequest(pinned = nextPinned))
                val existing = cacheStore.getSession(host.hostId, sessionId)
                cacheStore.upsertSession(snap.toCachedSession(host.hostId, json, existing))
                _state.update { cur ->
                    cur.copy(session = cur.session.copy(isPinned = snap.pinned))
                }
                onChanged()
            } catch (t: Throwable) {
                _state.update { it.copy(errorBanner = t.message ?: "Failed to update pin state") }
            }
        }
    }

    fun toggleArchiveSession(onArchived: () -> Unit = {}) {
        val nextArchived = !_state.value.session.isArchived
        scope.launch {
            try {
                val snap = gatewayApi.renameSession(sessionId, RenameSessionRequest(archived = nextArchived))
                val existing = cacheStore.getSession(host.hostId, sessionId)
                cacheStore.upsertSession(snap.toCachedSession(host.hostId, json, existing))
                _state.update { cur ->
                    cur.copy(
                        session = cur.session.copy(
                            isArchived = snap.archived,
                            isLive = snap.isLive,
                            status = snap.status,
                        ),
                    )
                }
                if (nextArchived) onArchived()
            } catch (t: Throwable) {
                _state.update { it.copy(errorBanner = t.message ?: "Failed to update archive state") }
            }
        }
    }

    fun deleteSessionPermanently(onDeleted: () -> Unit = {}) {
        scope.launch {
            try {
                gatewayApi.deleteSessionPermanently(sessionId)
                cacheStore.deleteSession(host.hostId, sessionId)
                onDeleted()
            } catch (t: Throwable) {
                _state.update { it.copy(errorBanner = t.message ?: "Failed to delete chat") }
            }
        }
    }

    fun cancelActiveTurn() {
        scope.launch {
            try {
                gatewayApi.cancelTurn(sessionId)
            } catch (t: Throwable) {
                _state.update { it.copy(errorBanner = t.message ?: "Failed to cancel active turn") }
            }
        }
    }

    // ── Queued Follow-Up Tray (STRICT Draft Preservation per SPEC D6) ───────

    fun sendQueueItemNow(itemId: String, interrupt: Boolean = true) {
        scope.launch {
            try {
                gatewayApi.sendQueueItemNow(
                    sessionId = sessionId,
                    itemId = itemId,
                    body = SendNowRequest(interrupt = interrupt),
                )
                // Refresh queue snapshot; MUST NEVER touch draftText or composer_drafts!
                val queueResp = runCatching { gatewayApi.getQueue(sessionId) }.getOrNull()
                if (queueResp != null) {
                    _state.update { cur ->
                        cur.copy(
                            session = cur.session.copy(
                                queuedFollowups = queueResp.items,
                                queuePaused = queueResp.paused,
                            ),
                        )
                    }
                }
            } catch (t: Throwable) {
                _state.update { it.copy(errorBanner = t.message ?: "Failed to dispatch queued item") }
            }
        }
    }

    fun resumePausedQueue() {
        val head = _state.value.session.queuedFollowups.minByOrNull { it.position } ?: return
        sendQueueItemNow(itemId = head.itemId, interrupt = false)
    }

    fun startEditingQueueItem(itemId: String, currentText: String) {
        _state.update { it.copy(editingQueueItemId = itemId, editingQueueText = currentText) }
    }

    fun updateEditingQueueText(text: String) {
        _state.update { it.copy(editingQueueText = text) }
    }

    fun cancelEditingQueueItem() {
        _state.update { it.copy(editingQueueItemId = null, editingQueueText = "") }
    }

    fun saveEditedQueueItem() {
        val itemId = _state.value.editingQueueItemId ?: return
        val newText = _state.value.editingQueueText.trim()
        if (newText.isEmpty()) return
        scope.launch {
            try {
                val updated = gatewayApi.editQueueItem(
                    sessionId = sessionId,
                    itemId = itemId,
                    body = EditQueueItemRequest(text = newText),
                )
                _state.update { cur ->
                    cur.copy(
                        editingQueueItemId = null,
                        editingQueueText = "",
                        session = cur.session.copy(
                            queuedFollowups = cur.session.queuedFollowups.map {
                                if (it.itemId == itemId) updated else it
                            },
                        ),
                    )
                }
            } catch (t: Throwable) {
                _state.update { it.copy(errorBanner = t.message ?: "Failed to edit queue item") }
            }
        }
    }

    fun deleteQueueItem(itemId: String) {
        scope.launch {
            try {
                gatewayApi.deleteQueueItem(sessionId = sessionId, itemId = itemId)
                _state.update { cur ->
                    cur.copy(
                        session = cur.session.copy(
                            queuedFollowups = cur.session.queuedFollowups.filterNot { it.itemId == itemId },
                        ),
                    )
                }
            } catch (t: Throwable) {
                _state.update { it.copy(errorBanner = t.message ?: "Failed to delete queue item") }
            }
        }
    }

    // ── Permission & Question Brokers (SPEC D7, R6) ─────────────────────────

    fun resolvePermission(
        requestId: String,
        decision: String,
        optionId: String? = null,
    ) {
        scope.launch {
            try {
                gatewayApi.resolvePermission(
                    sessionId = sessionId,
                    requestId = requestId,
                    body = ResolvePermissionRequest(decision = decision, optionId = optionId),
                )
                cacheStore.deletePendingApproval(host.hostId, sessionId, requestId)
                _state.update { cur ->
                    cur.copy(
                        session = cur.session.copy(
                            pendingPermissions = cur.session.pendingPermissions.filterNot { it.requestId == requestId },
                        ),
                    )
                }
            } catch (t: Throwable) {
                _state.update { it.copy(errorBanner = t.message ?: "Failed to resolve permission") }
            }
        }
    }

    fun updateQuestionDraftAnswer(requestId: String, propKey: String, value: String) {
        _state.update { cur ->
            val currentMap = cur.questionAnswersDraft[requestId].orEmpty()
            val nextForReq = currentMap + (propKey to value)
            cur.copy(questionAnswersDraft = cur.questionAnswersDraft + (requestId to nextForReq))
        }
    }

    fun resolveQuestion(
        requestId: String,
        action: String,
    ) {
        val pending = _state.value.session.pendingQuestions.firstOrNull { it.requestId == requestId } ?: return
        val fields = QuestionSchemaParser.parseFields(pending.requestedSchema)
        val draftMap = _state.value.questionAnswersDraft[requestId].orEmpty()
        if (action == "accept" && !QuestionSchemaParser.areRequiredAnswersSatisfied(fields, draftMap)) {
            _state.update { it.copy(errorBanner = "Complete all required fields before submitting.") }
            return
        }
        val answersJson = if (action == "accept") {
            QuestionSchemaParser.buildAnswersPayload(fields, draftMap)
        } else {
            kotlinx.serialization.json.JsonObject(emptyMap())
        }

        scope.launch {
            try {
                gatewayApi.resolveQuestion(
                    sessionId = sessionId,
                    requestId = requestId,
                    body = ResolveQuestionRequest(action = action, answers = answersJson),
                )
                cacheStore.deletePendingApproval(host.hostId, sessionId, requestId)
                _state.update { cur ->
                    cur.copy(
                        questionAnswersDraft = cur.questionAnswersDraft - requestId,
                        session = cur.session.copy(
                            pendingQuestions = cur.session.pendingQuestions.filterNot { it.requestId == requestId },
                        ),
                    )
                }
            } catch (t: Throwable) {
                _state.update { it.copy(errorBanner = t.message ?: "Failed to submit answer") }
            }
        }
    }

    // ── Collapsible Thought & Tool Gutter UI Toggles ────────────────────────

    fun toggleThoughtExpanded(block: LedgerItem.ThoughtBlock) {
        _state.update { cur ->
            val expanded = if (block.id in cur.expandedThoughtIds) {
                cur.expandedThoughtIds - block.id
            } else {
                cur.expandedThoughtIds + block.id
            }
            cur.copy(expandedThoughtIds = expanded)
        }
    }

    fun toggleToolExpanded(itemId: String) {
        _state.update { cur ->
            val next = if (itemId in cur.expandedToolIds) cur.expandedToolIds - itemId else cur.expandedToolIds + itemId
            cur.copy(expandedToolIds = next)
        }
    }

    fun expandToolAndFocus(itemId: String) {
        _state.update { cur -> cur.copy(expandedToolIds = cur.expandedToolIds + itemId) }
    }

    fun setToolDiffMode(itemId: String, mode: DiffDisplayMode) {
        _state.update { cur -> cur.copy(toolDiffModes = cur.toolDiffModes + (itemId to mode)) }
    }

    // ── Rename, Close, Config Sheet, Brain Artifacts & Workspace Rules ──────

    fun openRenameDialog() {
        _state.update { it.copy(showRenameDialog = true, renameDraft = it.session.title) }
    }

    fun updateRenameDraft(value: String) {
        _state.update { it.copy(renameDraft = value) }
    }

    fun dismissRenameDialog() {
        _state.update { it.copy(showRenameDialog = false) }
    }

    fun submitRenameSession() {
        val clean = _state.value.renameDraft.trim()
        if (clean.isEmpty()) return
        scope.launch {
            try {
                val snap = gatewayApi.renameSession(sessionId, RenameSessionRequest(title = clean))
                val existing = cacheStore.getSession(host.hostId, sessionId)
                cacheStore.upsertSession(snap.toCachedSession(host.hostId, json, existing))
                _state.update { cur ->
                    cur.copy(
                        showRenameDialog = false,
                        session = cur.session.copy(title = snap.title, titleSource = snap.titleSource),
                    )
                }
            } catch (t: Throwable) {
                _state.update { it.copy(errorBanner = t.message ?: "Failed to rename session") }
            }
        }
    }

    fun closeSession(onClosed: () -> Unit = {}) {
        scope.launch {
            try {
                gatewayApi.closeSession(sessionId)
                cacheStore.getSession(host.hostId, sessionId)?.let { existing ->
                    cacheStore.upsertSession(existing.copy(isLive = false, status = "closed"))
                }
                _state.update { cur ->
                    cur.copy(session = cur.session.copy(isLive = false, status = "closed"))
                }
                onClosed()
            } catch (t: Throwable) {
                _state.update { it.copy(errorBanner = t.message ?: "Failed to close session") }
            }
        }
    }

    fun setShowConfigSheet(show: Boolean) {
        _state.update { it.copy(showConfigSheet = show) }
    }

    fun updateLiveConfig(
        model: String? = null,
        thoughtLevel: String? = null,
        mode: String? = null,
    ) {
        scope.launch {
            try {
                val snap = gatewayApi.updateSessionConfig(
                    sessionId = sessionId,
                    body = UpdateConfigRequest(model = model, thoughtLevel = thoughtLevel, mode = mode),
                )
                val existing = cacheStore.getSession(host.hostId, sessionId)
                cacheStore.upsertSession(snap.toCachedSession(host.hostId, json, existing))
                _state.update { cur ->
                    cur.copy(session = ConsoleStreamReducer.mergeSnapshot(cur.session, snap))
                }
            } catch (t: Throwable) {
                _state.update { it.copy(errorBanner = t.message ?: "Failed to update live config") }
            }
        }
    }

    fun openArtifactsSheet() {
        _state.update { it.copy(showArtifactsSheet = true, isLoadingArtifacts = true, selectedArtifact = null) }
        scope.launch {
            try {
                val res = supplementaryApi.listSessionArtifacts(sessionId)
                _state.update { it.copy(artifacts = res.items, isLoadingArtifacts = false) }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        isLoadingArtifacts = false,
                        errorBanner = t.message ?: "Failed to list brain/ artifacts",
                    )
                }
            }
        }
    }

    fun closeArtifactsSheet() {
        _state.update { it.copy(showArtifactsSheet = false, selectedArtifact = null) }
    }

    fun clearSelectedArtifact() {
        _state.update { it.copy(selectedArtifact = null) }
    }

    fun openArtifactItem(item: ArtifactItemDto) {
        scope.launch {
            try {
                val isImage = item.mimeType.startsWith("image/")
                val downloaded = downloadBrainArtifact(
                    baseUrl = baseUrl,
                    okHttpClient = okHttpClient,
                    sessionId = sessionId,
                    artifactPath = item.path,
                    raw = isImage,
                )
                val loaded = if (isImage || downloaded.mediaType.startsWith("image/")) {
                    LoadedBrainArtifact(
                        item = item,
                        mediaType = downloaded.mediaType,
                        imageBytes = downloaded.bytes,
                        isSecretRedactedText = false,
                    )
                } else {
                    LoadedBrainArtifact(
                        item = item,
                        mediaType = downloaded.mediaType,
                        textContent = String(downloaded.bytes, StandardCharsets.UTF_8),
                        isSecretRedactedText = true,
                    )
                }
                _state.update { it.copy(selectedArtifact = loaded) }
            } catch (t: Throwable) {
                _state.update { it.copy(errorBanner = t.message ?: "Failed to load artifact") }
            }
        }
    }

    fun openWorkspaceRulesSheet() {
        _state.update { it.copy(showWorkspaceRulesSheet = true) }
        val cwd = _state.value.session.cwd.ifBlank { null }
        scope.launch {
            runCatching {
                supplementaryApi.listPermissionRules(workspace = cwd)
            }.onSuccess { res ->
                _state.update { it.copy(workspaceRules = res.items) }
            }
        }
    }

    fun closeWorkspaceRulesSheet() {
        _state.update { it.copy(showWorkspaceRulesSheet = false) }
    }

    fun revokeWorkspaceRule(ruleId: String) {
        scope.launch {
            try {
                supplementaryApi.revokePermissionRule(ruleId)
                _state.update { cur ->
                    cur.copy(workspaceRules = cur.workspaceRules.filterNot { it.ruleId == ruleId })
                }
            } catch (t: Throwable) {
                _state.update { it.copy(errorBanner = t.message ?: "Failed to revoke permission rule") }
            }
        }
    }

    fun dismissErrorBanner() {
        _state.update { it.copy(errorBanner = null) }
    }

    // ── Persistence Helpers ─────────────────────────────────────────────────

    private fun persistComposerDraftToCache() {
        val snap = _state.value
        if (cacheStore.getHost(host.hostId) == null) return
        val persistedAttachments = snap.draftAttachments.filter { it.state != AttachmentUploadState.UPLOADING }
        cacheStore.saveComposerDraft(
            CachedComposerDraft(
                hostId = host.hostId,
                sessionId = sessionId,
                draftText = snap.draftText,
                attachmentsJson = json.encodeToString(persistedAttachments),
                updatedAt = nowIso(),
            ),
        )
    }

    private fun persistHttpEvents(events: List<us.snakepit.gravitywell.net.SessionEventDto>) {
        val rows = events.filter { it.seq > 0L }.map { ev ->
            CachedSessionEvent(
                hostId = host.hostId,
                sessionId = sessionId,
                seq = ev.seq,
                cursor = ev.cursor,
                eventType = ev.type,
                payloadJson = ev.payload.toString(),
                ts = ev.ts,
            )
        }
        cacheStore.insertSessionEvents(rows)
    }

    private fun persistQueueAndApprovalsToCache(snap: us.snakepit.gravitywell.net.SessionSnapshotDto) {
        cacheStore.replaceQueuedFollowups(
            hostId = host.hostId,
            sessionId = sessionId,
            items = snap.queuedFollowups.map { q ->
                CachedQueuedFollowup(
                    hostId = host.hostId,
                    sessionId = sessionId,
                    itemId = q.itemId,
                    position = q.position,
                    text = q.text,
                    attachmentsJson = json.encodeToString(q.attachmentIds),
                    status = q.status,
                    createdAt = q.createdAt.ifBlank { nowIso() },
                )
            },
        )
        persistPendingApprovalsList(
            permissions = snap.pendingPermissions,
            questions = snap.pendingQuestions,
        )
    }

    private fun persistPendingApprovalsList(
        permissions: List<us.snakepit.gravitywell.net.PendingPermissionDto>,
        questions: List<us.snakepit.gravitywell.net.PendingQuestionDto>,
    ) {
        val approvals = buildList {
            for (perm in permissions) {
                add(
                    CachedPendingApproval(
                        hostId = host.hostId,
                        sessionId = sessionId,
                        requestId = perm.requestId,
                        kind = "permission",
                        title = perm.toolCall["title"]?.toString()?.trim('"') ?: "Permission Gate",
                        payloadJson = json.encodeToString(perm),
                        createdAt = perm.createdAt.ifBlank { nowIso() },
                    ),
                )
            }
            for (qst in questions) {
                add(
                    CachedPendingApproval(
                        hostId = host.hostId,
                        sessionId = sessionId,
                        requestId = qst.requestId,
                        kind = "question",
                        title = qst.message.ifBlank { "Agent Question" },
                        payloadJson = json.encodeToString(qst),
                        createdAt = qst.createdAt.ifBlank { nowIso() },
                    ),
                )
            }
        }
        cacheStore.replacePendingApprovals(
            hostId = host.hostId,
            sessionId = sessionId,
            items = approvals,
        )
    }
}
