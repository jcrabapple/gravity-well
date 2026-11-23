package us.snakepit.gravitywell.ui.hosts

import androidx.compose.runtime.Immutable
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import us.snakepit.gravitywell.BuildConfig
import us.snakepit.gravitywell.cache.CachedHost
import us.snakepit.gravitywell.cache.OfflineCacheStore
import us.snakepit.gravitywell.net.HostVerificationReport
import us.snakepit.gravitywell.net.HostVerifier
import us.snakepit.gravitywell.net.NetworkModule
import us.snakepit.gravitywell.net.UrlSecurityPolicy
import us.snakepit.gravitywell.net.VerificationLegId
import us.snakepit.gravitywell.net.VerificationLegState
import us.snakepit.gravitywell.net.VerificationLegStatus
import us.snakepit.gravitywell.security.TokenVault

@Immutable
data class HostOnboardingUiState(
    val editingHostId: String? = null,
    val label: String = "",
    val baseUrl: String = "",
    val bearerToken: String = "",
    val isTokenVisible: Boolean = false,
    val isDebugBuild: Boolean = BuildConfig.DEBUG,
    val urlValidationError: String? = null,
    val showDebugCleartextWarning: Boolean = false,
    val isRunningPreflight: Boolean = false,
    val legs: Map<VerificationLegId, VerificationLegState> = defaultLegMap(),
    val lastReport: HostVerificationReport? = null,
    val verifiedSignature: String? = null,
    val saveError: String? = null,
    val savedHost: CachedHost? = null,
) {
    val isAllLegsVerified: Boolean
        get() = VerificationLegId.entries.all { legs[it]?.status == VerificationLegStatus.VERIFIED }

    val canSaveVerifiedHost: Boolean
        get() = !isRunningPreflight &&
            urlValidationError == null &&
            isAllLegsVerified &&
            lastReport?.isAllVerified == true &&
            verifiedSignature != null &&
            verifiedSignature == currentSignature(baseUrl, bearerToken)

    companion object {
        fun defaultLegMap(): Map<VerificationLegId, VerificationLegState> = mapOf(
            VerificationLegId.REACHABILITY_TLS to VerificationLegState(VerificationLegId.REACHABILITY_TLS),
            VerificationLegId.AUTH_CAPABILITIES to VerificationLegState(VerificationLegId.AUTH_CAPABILITIES),
            VerificationLegId.WS_TICKET_HELLO to VerificationLegState(VerificationLegId.WS_TICKET_HELLO),
        )

        fun currentSignature(rawUrl: String, rawToken: String): String =
            "${rawUrl.trim()}|${rawToken.trim()}"
    }
}

/**
 * Controller / ViewModel for 3-stage sequential gateway host onboarding (SPEC D13).
 * Never persists bearer tokens until Leg 3 succeeds, and stores tokens exclusively
 * in TokenVault (AndroidKeyStore AES-GCM), never in SQLite.
 */
class HostOnboardingController(
    private val verifier: HostVerifier,
    private val cacheStore: OfflineCacheStore,
    private val tokenVault: TokenVault,
    private val scope: CoroutineScope,
    private val isDebugBuild: Boolean = BuildConfig.DEBUG,
    private val json: Json = NetworkModule.defaultJson,
    private val nowIso: () -> String = { Instant.now().toString() },
    initialHost: CachedHost? = null,
) {
    private val _state = MutableStateFlow(
        buildInitialState(initialHost, tokenVault, isDebugBuild),
    )
    val state: StateFlow<HostOnboardingUiState> = _state.asStateFlow()

    fun updateLabel(value: String) {
        _state.update { it.copy(label = value, saveError = null) }
    }

    fun updateBaseUrl(value: String) {
        val (urlErr, cleartextWarn) = evaluateUrlInput(value, isDebugBuild)
        _state.update { prev ->
            val invalidated = HostOnboardingUiState.currentSignature(value, prev.bearerToken) != prev.verifiedSignature
            prev.copy(
                baseUrl = value,
                urlValidationError = urlErr,
                showDebugCleartextWarning = cleartextWarn,
                legs = if (invalidated) HostOnboardingUiState.defaultLegMap() else prev.legs,
                lastReport = if (invalidated) null else prev.lastReport,
                verifiedSignature = if (invalidated) null else prev.verifiedSignature,
                saveError = null,
            )
        }
    }

    fun updateBearerToken(value: String) {
        _state.update { prev ->
            val invalidated = HostOnboardingUiState.currentSignature(prev.baseUrl, value) != prev.verifiedSignature
            prev.copy(
                bearerToken = value,
                legs = if (invalidated) HostOnboardingUiState.defaultLegMap() else prev.legs,
                lastReport = if (invalidated) null else prev.lastReport,
                verifiedSignature = if (invalidated) null else prev.verifiedSignature,
                saveError = null,
            )
        }
    }

    fun toggleTokenVisibility() {
        _state.update { it.copy(isTokenVisible = !it.isTokenVisible) }
    }

    fun resetForHost(host: CachedHost? = null) {
        _state.value = buildInitialState(host, tokenVault, isDebugBuild)
    }

    fun runPreflightCheck(onFinished: (HostVerificationReport?) -> Unit = {}) {
        val snap = _state.value
        if (snap.isRunningPreflight) return

        val validation = UrlSecurityPolicy.validateBaseUrl(snap.baseUrl, isDebug = isDebugBuild)
        val validated = validation.getOrElse { err ->
            _state.update {
                it.copy(
                    urlValidationError = err.message ?: "Invalid gateway URL",
                    showDebugCleartextWarning = false,
                    legs = HostOnboardingUiState.defaultLegMap(),
                    lastReport = null,
                    verifiedSignature = null,
                )
            }
            onFinished(null)
            return
        }

        val testedUrlRaw = snap.baseUrl
        val testedTokenRaw = snap.bearerToken
        val testedSignature = HostOnboardingUiState.currentSignature(testedUrlRaw, testedTokenRaw)

        _state.update {
            it.copy(
                urlValidationError = null,
                showDebugCleartextWarning = validated.isCleartext && isDebugBuild,
                isRunningPreflight = true,
                legs = HostOnboardingUiState.defaultLegMap(),
                lastReport = null,
                verifiedSignature = null,
                saveError = null,
            )
        }

        scope.launch {
            val report = verifier.verify(
                baseUrl = validated.httpUrl,
                bearerToken = testedTokenRaw,
                onProgress = { progress ->
                    _state.update { cur ->
                        cur.copy(
                            legs = progress.legs,
                            lastReport = progress,
                        )
                    }
                },
            )
            _state.update { cur ->
                val stillMatchesTestedInputs =
                    HostOnboardingUiState.currentSignature(cur.baseUrl, cur.bearerToken) == testedSignature
                val detectedHostname = report.info?.hostname.orEmpty()
                val autoLabel = if (cur.label.isBlank() && detectedHostname.isNotBlank()) {
                    detectedHostname
                } else {
                    cur.label
                }
                cur.copy(
                    label = autoLabel,
                    isRunningPreflight = false,
                    legs = if (stillMatchesTestedInputs) report.legs else HostOnboardingUiState.defaultLegMap(),
                    lastReport = if (stillMatchesTestedInputs) report else null,
                    verifiedSignature = if (report.isAllVerified && stillMatchesTestedInputs) testedSignature else null,
                )
            }
            onFinished(report)
        }
    }

    fun saveVerifiedHost(onSaved: (CachedHost) -> Unit = {}): CachedHost? {
        val snap = _state.value
        if (!snap.canSaveVerifiedHost) {
            _state.update { it.copy(saveError = "Complete all 3 pre-flight legs before saving.") }
            return null
        }
        val report = snap.lastReport ?: return null
        val cleanToken = snap.bearerToken.trim()
        if (cleanToken.isEmpty()) {
            _state.update { it.copy(saveError = "Bearer token must not be empty.") }
            return null
        }

        val hostId = snap.editingHostId ?: "host_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val existing = snap.editingHostId?.let { cacheStore.getHost(it) }
        val resolvedLabel = snap.label.trim()
            .ifEmpty { report.info?.hostname?.trim().orEmpty() }
            .ifEmpty { report.baseUrl.host }

        val capabilitiesJson = report.info?.capabilities?.let { json.encodeToString(it) }
            ?: report.hello?.capabilities?.let { json.encodeToString(it) }
            ?: "{}"
        val agyVer = report.info?.agyVersion?.ifBlank { null }
            ?: report.health?.agyVersion?.ifBlank { null }
            ?: report.hello?.agyVersion?.ifBlank { null }
        val now = nowIso()

        return try {
            tokenVault.putToken(hostId, cleanToken)
            val cached = CachedHost(
                hostId = hostId,
                label = resolvedLabel,
                baseUrl = report.baseUrl.toString(),
                hostname = report.info?.hostname?.ifBlank { null },
                defaultCwd = report.info?.defaultCwd?.ifBlank { null },
                agyVersion = agyVer,
                capabilitiesJson = capabilitiesJson,
                lastCursor = report.hello?.latestCursor ?: existing?.lastCursor ?: 0L,
                isActive = true,
                createdAt = existing?.createdAt ?: now,
                lastVerifiedAt = now,
            )
            cacheStore.upsertHost(cached)
            _state.update { it.copy(savedHost = cached, saveError = null) }
            onSaved(cached)
            cached
        } catch (t: Throwable) {
            _state.update { it.copy(saveError = t.message ?: "Failed to save host credentials") }
            null
        }
    }

    companion object {
        internal fun evaluateUrlInput(rawUrl: String, isDebugBuild: Boolean): Pair<String?, Boolean> {
            if (rawUrl.isBlank()) return null to false
            val res = UrlSecurityPolicy.validateBaseUrl(rawUrl, isDebug = isDebugBuild)
            return res.fold(
                onSuccess = { v -> null to (v.isCleartext && isDebugBuild) },
                onFailure = { e -> (e.message ?: "Malformed gateway URL") to false },
            )
        }

        private fun buildInitialState(
            initialHost: CachedHost?,
            tokenVault: TokenVault,
            isDebugBuild: Boolean,
        ): HostOnboardingUiState {
            if (initialHost == null) {
                return HostOnboardingUiState(isDebugBuild = isDebugBuild)
            }
            val savedToken = runCatching { tokenVault.getToken(initialHost.hostId).orEmpty() }.getOrDefault("")
            val (urlErr, cleartextWarn) = evaluateUrlInput(initialHost.baseUrl, isDebugBuild)
            return HostOnboardingUiState(
                editingHostId = initialHost.hostId,
                label = initialHost.label,
                baseUrl = initialHost.baseUrl,
                bearerToken = savedToken,
                isDebugBuild = isDebugBuild,
                urlValidationError = urlErr,
                showDebugCleartextWarning = cleartextWarn,
            )
        }
    }
}
