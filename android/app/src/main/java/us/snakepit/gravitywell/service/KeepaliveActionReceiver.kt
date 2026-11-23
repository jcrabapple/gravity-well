package us.snakepit.gravitywell.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import us.snakepit.gravitywell.GravityWellRuntime
import us.snakepit.gravitywell.cache.OfflineCacheStore
import us.snakepit.gravitywell.net.NetworkModule
import us.snakepit.gravitywell.net.ResolvePermissionRequest
import us.snakepit.gravitywell.security.TokenVault

/**
 * Non-exported BroadcastReceiver (`android:exported="false"`) handling notification shade
 * quick-actions: `Approve Once`, `Deny`, and `Stop` (SPEC.md D16).
 *
 * Security invariants:
 * - Never accepts or dispatches `allow_rule` from a notification action.
 * - Never reads bearer tokens from `Intent` extras; tokens are fetched exclusively from
 *   [TokenVault] (`AndroidKeyStore`).
 */
class KeepaliveActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val appCtx = context.applicationContext
        if (intent.action == ACTION_STOP) {
            val prefs = KeepalivePreferences.get(appCtx)
            prefs.setKeepaliveEnabled(false)
            GravityWellKeepaliveService.stop(appCtx)
            GravityWellNotifications.cancelAll(appCtx)
            return
        }

        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val runtime = GravityWellRuntime.get(appCtx)
                processActionIntent(
                    context = appCtx,
                    intent = intent,
                    cacheStore = runtime.cacheStore,
                    tokenVault = runtime.tokenVault,
                    preferences = KeepalivePreferences.get(appCtx),
                )
            } finally {
                pendingResult?.finish()
            }
        }
    }

    companion object {
        const val ACTION_APPROVE_ONCE = "us.snakepit.gravitywell.action.KEEPALIVE_APPROVE_ONCE"
        const val ACTION_DENY = "us.snakepit.gravitywell.action.KEEPALIVE_DENY"
        const val ACTION_STOP = "us.snakepit.gravitywell.action.KEEPALIVE_STOP"

        const val EXTRA_HOST_ID = "us.snakepit.gravitywell.extra.ACTION_HOST_ID"
        const val EXTRA_SESSION_ID = "us.snakepit.gravitywell.extra.ACTION_SESSION_ID"
        const val EXTRA_REQUEST_ID = "us.snakepit.gravitywell.extra.ACTION_REQUEST_ID"

        /**
         * Maps a notification broadcast action to the strictly permitted ACP permission decision.
         * `allow_rule` is strictly forbidden on notification actions (DIRECTION Section 9).
         */
        fun decisionForAction(action: String?): String? = when (action) {
            ACTION_APPROVE_ONCE -> "allow_once"
            ACTION_DENY -> "deny"
            else -> null
        }

        suspend fun processActionIntent(
            context: Context,
            intent: Intent,
            cacheStore: OfflineCacheStore,
            tokenVault: TokenVault,
            preferences: KeepalivePreferences,
            json: Json = NetworkModule.defaultJson,
            clientFactory: ((() -> String?) -> OkHttpClient) = { tokenProvider ->
                NetworkModule.createOkHttpClient(tokenProvider = tokenProvider, json = json)
            },
        ): Boolean {
            if (intent.action == ACTION_STOP) {
                preferences.setKeepaliveEnabled(false)
                GravityWellKeepaliveService.stop(context)
                GravityWellNotifications.cancelAll(context)
                return true
            }

            val decision = decisionForAction(intent.action) ?: return false
            val hostId = intent.getStringExtra(EXTRA_HOST_ID)?.trim().orEmpty()
            val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)?.trim().orEmpty()
            val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)?.trim().orEmpty()
            if (hostId.isEmpty() || sessionId.isEmpty() || requestId.isEmpty()) {
                return false
            }

            val host = cacheStore.getHost(hostId) ?: return false
            val parsedBaseUrl = host.baseUrl.toHttpUrlOrNull() ?: return false
            val token = tokenVault.getToken(hostId)
            if (token.isNullOrBlank()) return false

            val okHttp = clientFactory { tokenVault.getToken(hostId) }
            val api = NetworkModule.createGatewayApi(parsedBaseUrl, okHttp, json)

            val resolved = runCatching {
                api.resolvePermission(
                    sessionId = sessionId,
                    requestId = requestId,
                    body = ResolvePermissionRequest(decision = decision, optionId = null),
                )
                true
            }.getOrDefault(false)

            if (resolved) {
                cacheStore.deletePendingApproval(hostId, sessionId, requestId)
                val existingSession = cacheStore.getSession(hostId, sessionId)
                if (existingSession != null && cacheStore.listPendingApprovals(hostId, sessionId).isEmpty()) {
                    cacheStore.upsertSession(existingSession.copy(status = "running"))
                }
                GravityWellNotifications.cancelGateNotification(context, hostId, sessionId, requestId)
                KeepaliveCoordinator(
                    cacheStore = cacheStore,
                    preferences = preferences,
                ).syncServiceState(context = context, host = host)
            }
            return resolved
        }
    }
}
