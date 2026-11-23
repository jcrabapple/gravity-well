package us.snakepit.gravitywell.ui.observatory

import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import us.snakepit.gravitywell.cache.CachedHost
import us.snakepit.gravitywell.cache.CachedObservatorySnapshot
import us.snakepit.gravitywell.cache.OfflineCacheStore
import us.snakepit.gravitywell.net.GatewayException
import us.snakepit.gravitywell.net.GatewayHttpException
import us.snakepit.gravitywell.net.NetworkModule
import us.snakepit.gravitywell.net.WsServerFrame
import us.snakepit.gravitywell.ui.sessions.ExternalSessionPresenceDto

/**
 * Stateful controller for the Observatory lane: quota/credit fuel gauges, host vitals,
 * FLOCK presence radar, `remote-control` daemon toggle, confirm-gated `agy update`,
 * and redacted `cli.log` / `crashes/` inspection (SPEC.md D10, D11, D15).
 */
class ObservatoryController(
    private val host: CachedHost,
    private val api: ObservatoryApi,
    private val cacheStore: OfflineCacheStore,
    private val scope: CoroutineScope,
    private val json: Json = NetworkModule.defaultJson,
) {
    private val _state = MutableStateFlow(
        ObservatoryUiState(
            hostId = host.hostId,
            hostLabel = host.label,
        ),
    )
    val state: StateFlow<ObservatoryUiState> = _state.asStateFlow()

    private var logSearchDebounceJob: Job? = null

    init {
        hydrateFromCache()
    }

    private fun hydrateFromCache() {
        val cached = cacheStore.getObservatorySnapshot(host.hostId) ?: return
        val cachedUsage = runCatching {
            if (cached.usageJson.isNotBlank() && cached.usageJson != "{}") {
                json.decodeFromString<ObservatoryUsageResponseDto>(cached.usageJson)
            } else {
                null
            }
        }.getOrNull()
        val cachedStatus = runCatching {
            if (cached.statusJson.isNotBlank() && cached.statusJson != "{}") {
                json.decodeFromString<ObservatoryFullStatusDto>(cached.statusJson)
            } else {
                null
            }
        }.getOrNull()

        _state.update {
            it.copy(
                usage = cachedUsage ?: it.usage,
                status = cachedStatus ?: it.status,
                cachedFetchedAt = cached.fetchedAt,
            )
        }
    }

    private fun persistSnapshot(
        usage: ObservatoryUsageResponseDto?,
        status: ObservatoryFullStatusDto?,
    ) {
        val existing = cacheStore.getObservatorySnapshot(host.hostId)
        val usageStr = if (usage != null) {
            json.encodeToString(usage)
        } else {
            existing?.usageJson ?: "{}"
        }
        val statusStr = if (status != null) {
            json.encodeToString(status)
        } else {
            existing?.statusJson ?: "{}"
        }
        val nowIso = Instant.now().toString()
        cacheStore.saveObservatorySnapshot(
            CachedObservatorySnapshot(
                hostId = host.hostId,
                usageJson = usageStr,
                statusJson = statusStr,
                fetchedAt = nowIso,
            ),
        )
        _state.update { it.copy(cachedFetchedAt = nowIso) }
    }

    fun refreshAll(forceRefreshUsage: Boolean = false) {
        scope.launch {
            _state.update { it.copy(isLoading = true, errorBanner = null) }
            val usageDeferred = async { runCatching { api.getUsage(refresh = forceRefreshUsage) } }
            val statusDeferred = async { runCatching { api.getStatus() } }
            val logsDeferred = async {
                val cur = _state.value
                runCatching {
                    api.getLogs(
                        source = cur.logSource.wireValue,
                        tail = cur.logTailLines,
                        query = cur.logSearchQuery,
                        file = if (cur.logSource == LogSourceTab.CRASHES) cur.selectedCrashFile else null,
                    )
                }
            }

            val usageRes = usageDeferred.await()
            val statusRes = statusDeferred.await()
            val logsRes = logsDeferred.await()

            val firstErr = usageRes.exceptionOrNull()
                ?: statusRes.exceptionOrNull()
                ?: logsRes.exceptionOrNull()

            val nextUsage = usageRes.getOrNull() ?: _state.value.usage
            val nextStatus = statusRes.getOrNull() ?: _state.value.status
            if (usageRes.isSuccess || statusRes.isSuccess) {
                persistSnapshot(nextUsage, nextStatus)
            }

            _state.update { st ->
                val logsDto = logsRes.getOrNull()
                st.copy(
                    isLoading = false,
                    usage = nextUsage,
                    status = nextStatus,
                    logLines = logsDto?.lines ?: st.logLines,
                    logTotalMatched = logsDto?.totalMatched ?: st.logTotalMatched,
                    activeLogFile = logsDto?.file ?: st.activeLogFile,
                    availableCrashFiles = if (logsDto != null && st.logSource == LogSourceTab.CRASHES) {
                        logsDto.files
                    } else {
                        st.availableCrashFiles
                    },
                    selectedCrashFile = if (logsDto != null && st.logSource == LogSourceTab.CRASHES) {
                        logsDto.file
                    } else {
                        st.selectedCrashFile
                    },
                    errorBanner = firstErr?.let(::formatError),
                )
            }
        }
    }

    fun refreshUsageBypassCache() {
        scope.launch {
            _state.update { it.copy(isRefreshingUsage = true, errorBanner = null) }
            runCatching { api.getUsage(refresh = true) }
                .onSuccess { freshUsage ->
                    persistSnapshot(freshUsage, _state.value.status)
                    _state.update { it.copy(isRefreshingUsage = false, usage = freshUsage) }
                }
                .onFailure { err ->
                    _state.update {
                        it.copy(
                            isRefreshingUsage = false,
                            errorBanner = formatError(err),
                        )
                    }
                }
        }
    }

    fun onWebSocketFrame(frame: WsServerFrame) {
        if (frame is WsServerFrame.Event && frame.type == "presence_update") {
            val sessionsArr = runCatching { frame.payload["external_sessions"]?.jsonArray }.getOrNull()
            val parsedSessions = sessionsArr?.mapNotNull { elem ->
                runCatching { json.decodeFromJsonElement<ExternalSessionPresenceDto>(elem) }.getOrNull()
            } ?: emptyList()
            val activeCount = frame.payload["active_count"]?.jsonPrimitive?.intOrNull ?: parsedSessions.size

            _state.update { st ->
                val curStatus = st.status ?: ObservatoryFullStatusDto()
                st.copy(
                    status = curStatus.copy(
                        externalSessions = parsedSessions,
                        activeExternalCount = activeCount,
                    ),
                )
            }
        }
    }

    // ── Remote-Control Daemon Controls ──────────────────────────────────────

    fun updateDaemonNameDraft(value: String) {
        _state.update { it.copy(daemonNameDraft = value) }
    }

    fun setDaemonSessionScoped(scoped: Boolean) {
        _state.update { it.copy(daemonSessionScoped = scoped) }
    }

    fun startRemoteControl() {
        val cur = _state.value
        val cleanName = cur.daemonNameDraft.trim().ifEmpty { null }
        scope.launch {
            _state.update { it.copy(isDaemonBusy = true, errorBanner = null) }
            runCatching {
                api.startRemoteControl(
                    RemoteControlStartRequestDto(
                        name = cleanName,
                        sessionScoped = cur.daemonSessionScoped,
                    ),
                )
            }.onSuccess { rcStatus ->
                val updatedStatus = (_state.value.status ?: ObservatoryFullStatusDto()).copy(remoteControl = rcStatus)
                persistSnapshot(_state.value.usage, updatedStatus)
                _state.update { st ->
                    st.copy(isDaemonBusy = false, status = updatedStatus)
                }
            }.onFailure { err ->
                _state.update { it.copy(isDaemonBusy = false, errorBanner = formatError(err)) }
            }
        }
    }

    fun stopRemoteControl() {
        scope.launch {
            _state.update { it.copy(isDaemonBusy = true, errorBanner = null) }
            runCatching { api.stopRemoteControl() }
                .onSuccess { rcStatus ->
                    val updatedStatus = (_state.value.status ?: ObservatoryFullStatusDto()).copy(remoteControl = rcStatus)
                    persistSnapshot(_state.value.usage, updatedStatus)
                    _state.update { st ->
                        st.copy(isDaemonBusy = false, status = updatedStatus)
                    }
                }
                .onFailure { err ->
                    _state.update { it.copy(isDaemonBusy = false, errorBanner = formatError(err)) }
                }
        }
    }

    // ── 2-Step Confirm-Gated `agy update` (D11) ─────────────────────────────

    fun openUpdateConfirmSheet() {
        _state.update { it.copy(showUpdateConfirmSheet = true, errorBanner = null) }
    }

    fun dismissUpdateConfirmSheet() {
        if (_state.value.isUpdatingCli) return
        _state.update { it.copy(showUpdateConfirmSheet = false) }
    }

    /**
     * Executes `POST /api/observatory/update` strictly after the user has opened and
     * confirmed the 2-step confirmation sheet (`showUpdateConfirmSheet == true`).
     */
    fun confirmAndRunUpdate() {
        if (!_state.value.showUpdateConfirmSheet || _state.value.isUpdatingCli) {
            _state.update {
                it.copy(errorBanner = "Confirmation interlock required before running agy update.")
            }
            return
        }
        scope.launch {
            _state.update { it.copy(isUpdatingCli = true, errorBanner = null) }
            runCatching {
                api.runUpdate(RunAgyUpdateRequestDto(confirm = true))
            }.onSuccess { result ->
                val freshStatus = runCatching { api.getStatus() }.getOrNull()
                val curSnapshot = _state.value
                val mergedStatus = freshStatus ?: curSnapshot.status?.copy(
                    updater = curSnapshot.status.updater.copy(agyVersion = result.versionAfter),
                )
                persistSnapshot(curSnapshot.usage, mergedStatus)
                _state.update { st ->
                    st.copy(
                        isUpdatingCli = false,
                        showUpdateConfirmSheet = false,
                        lastUpdateResult = result,
                        status = mergedStatus,
                    )
                }
            }.onFailure { err ->
                _state.update {
                    it.copy(
                        isUpdatingCli = false,
                        showUpdateConfirmSheet = false,
                        errorBanner = formatError(err),
                    )
                }
            }
        }
    }

    // ── Redacted `cli.log` & `crashes/` Log Viewer ──────────────────────────

    fun selectLogSource(source: LogSourceTab) {
        if (_state.value.logSource == source && _state.value.logLines.isNotEmpty()) return
        _state.update {
            it.copy(
                logSource = source,
                selectedCrashFile = if (source == LogSourceTab.CLI) null else it.selectedCrashFile,
            )
        }
        fetchLogsNow()
    }

    fun selectCrashFile(filename: String) {
        _state.update { it.copy(selectedCrashFile = filename) }
        fetchLogsNow()
    }

    fun selectLogTail(tailLines: Int) {
        val clamped = tailLines.coerceIn(1, 2000)
        _state.update { it.copy(logTailLines = clamped) }
        fetchLogsNow()
    }

    fun updateLogSearchQuery(query: String) {
        _state.update { it.copy(logSearchQuery = query) }
        logSearchDebounceJob?.cancel()
        logSearchDebounceJob = scope.launch {
            delay(200L)
            fetchLogsSuspend()
        }
    }

    fun fetchLogsNow() {
        logSearchDebounceJob?.cancel()
        scope.launch { fetchLogsSuspend() }
    }

    fun setShowExpandedLogSheet(show: Boolean) {
        _state.update { it.copy(showExpandedLogSheet = show) }
        if (show && _state.value.logLines.isEmpty()) {
            fetchLogsNow()
        }
    }

    fun dismissErrorBanner() {
        _state.update { it.copy(errorBanner = null) }
    }

    private suspend fun fetchLogsSuspend() {
        val cur = _state.value
        _state.update { it.copy(isLogsLoading = true) }
        runCatching {
            api.getLogs(
                source = cur.logSource.wireValue,
                tail = cur.logTailLines,
                query = cur.logSearchQuery,
                file = if (cur.logSource == LogSourceTab.CRASHES) cur.selectedCrashFile else null,
            )
        }.onSuccess { dto ->
            _state.update { st ->
                st.copy(
                    isLogsLoading = false,
                    logLines = dto.lines,
                    logTotalMatched = dto.totalMatched,
                    activeLogFile = dto.file,
                    availableCrashFiles = if (st.logSource == LogSourceTab.CRASHES) {
                        dto.files
                    } else {
                        st.availableCrashFiles
                    },
                    selectedCrashFile = if (st.logSource == LogSourceTab.CRASHES) {
                        dto.file
                    } else {
                        null
                    },
                )
            }
        }.onFailure { err ->
            _state.update {
                it.copy(
                    isLogsLoading = false,
                    errorBanner = formatError(err),
                )
            }
        }
    }

    private fun formatError(err: Throwable): String = when (err) {
        is GatewayHttpException -> "${err.status} ${err.code}: ${err.message}"
        is GatewayException -> err.message
        else -> err.message ?: "Telemetry request failed"
    }
}
