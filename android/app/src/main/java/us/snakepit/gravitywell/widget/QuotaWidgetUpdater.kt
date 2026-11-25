package us.snakepit.gravitywell.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import us.snakepit.gravitywell.GravityWellRuntime
import us.snakepit.gravitywell.MainActivity
import us.snakepit.gravitywell.R
import us.snakepit.gravitywell.cache.CachedObservatorySnapshot
import us.snakepit.gravitywell.net.NetworkModule
import us.snakepit.gravitywell.ui.observatory.ObservatoryApi
import us.snakepit.gravitywell.ui.observatory.ObservatoryUsageResponseDto
import us.snakepit.gravitywell.ui.observatory.QuotaHealthTier

object QuotaWidgetUpdater {

    const val ACTION_REFRESH_QUOTA = "us.snakepit.gravitywell.widget.ACTION_REFRESH_QUOTA"
    const val EXTRA_FORCE_REFRESH = "us.snakepit.gravitywell.widget.EXTRA_FORCE_REFRESH"

    private const val REQUEST_CODE_OPEN_GEMINI = 4101
    private const val REQUEST_CODE_OPEN_THIRD_PARTY = 4102
    private const val REQUEST_CODE_REFRESH_GEMINI = 4201
    private const val REQUEST_CODE_REFRESH_THIRD_PARTY = 4202
    private const val NETWORK_TIMEOUT_MS = 12_000L

    fun renderAllWidgetsFromCache(
        context: Context,
        isRefreshing: Boolean = false,
        offlineNotice: Boolean = false,
    ) {
        val appCtx = context.applicationContext
        val manager = AppWidgetManager.getInstance(appCtx) ?: return
        val geminiIds = manager.getAppWidgetIds(ComponentName(appCtx, GeminiQuotaWidgetProvider::class.java))
        val thirdPartyIds = manager.getAppWidgetIds(ComponentName(appCtx, ThirdPartyQuotaWidgetProvider::class.java))
        if (geminiIds.isEmpty() && thirdPartyIds.isEmpty()) return

        val snapshotData = loadCachedUsage(appCtx)
        val nowMs = System.currentTimeMillis()

        if (geminiIds.isNotEmpty()) {
            val state = QuotaWidgetExtractor.buildDisplayState(
                kind = QuotaWidgetKind.GEMINI,
                hostLabel = snapshotData.hostLabel,
                usage = snapshotData.usage,
                fetchedAtIso = snapshotData.fetchedAtIso,
                nowEpochMillis = nowMs,
                isRefreshing = isRefreshing,
                offlineNotice = offlineNotice,
            )
            val views = buildRemoteViews(appCtx, state)
            manager.updateAppWidget(geminiIds, views)
        }

        if (thirdPartyIds.isNotEmpty()) {
            val state = QuotaWidgetExtractor.buildDisplayState(
                kind = QuotaWidgetKind.THIRD_PARTY,
                hostLabel = snapshotData.hostLabel,
                usage = snapshotData.usage,
                fetchedAtIso = snapshotData.fetchedAtIso,
                nowEpochMillis = nowMs,
                isRefreshing = isRefreshing,
                offlineNotice = offlineNotice,
            )
            val views = buildRemoteViews(appCtx, state)
            manager.updateAppWidget(thirdPartyIds, views)
        }
    }

    fun isCachedUsageFresh(context: Context): Boolean {
        val data = loadCachedUsage(context.applicationContext)
        if (data.usage == null) return false
        return QuotaWidgetExtractor.isCacheFresh(
            fetchedAtIso = data.usage.cachedAt.ifBlank { data.fetchedAtIso },
            nowEpochMillis = System.currentTimeMillis(),
        )
    }

    suspend fun refreshFromNetworkAndRender(
        context: Context,
        forceRefresh: Boolean,
    ) {
        val appCtx = context.applicationContext
        val runtime = runCatching { GravityWellRuntime.get(appCtx) }.getOrNull() ?: return
        val activeHost = runCatching { runtime.cacheStore.getActiveHost() }.getOrNull()
        if (activeHost == null) {
            renderAllWidgetsFromCache(appCtx, isRefreshing = false, offlineNotice = false)
            return
        }

        val parsedBaseUrl = activeHost.baseUrl.toHttpUrlOrNull()
        val token = runCatching { runtime.tokenVault.getToken(activeHost.hostId) }.getOrNull()
        if (parsedBaseUrl == null || token.isNullOrBlank()) {
            renderAllWidgetsFromCache(appCtx, isRefreshing = false, offlineNotice = true)
            return
        }

        val json = NetworkModule.defaultJson
        val freshUsage = withTimeoutOrNull(NETWORK_TIMEOUT_MS) {
            runCatching {
                val okHttp = NetworkModule.createOkHttpClient(
                    tokenProvider = { token },
                    json = json,
                )
                val api = ObservatoryApi.create(parsedBaseUrl, okHttp, json)
                api.getUsage(refresh = forceRefresh)
            }.getOrNull()
        }

        if (freshUsage != null) {
            runCatching {
                val existing = runtime.cacheStore.getObservatorySnapshot(activeHost.hostId)
                runtime.cacheStore.saveObservatorySnapshot(
                    CachedObservatorySnapshot(
                        hostId = activeHost.hostId,
                        usageJson = json.encodeToString(freshUsage),
                        statusJson = existing?.statusJson ?: "{}",
                        fetchedAt = Instant.now().toString(),
                    ),
                )
            }
            renderAllWidgetsFromCache(appCtx, isRefreshing = false, offlineNotice = false)
        } else {
            renderAllWidgetsFromCache(appCtx, isRefreshing = false, offlineNotice = true)
        }
    }

    internal fun buildRemoteViews(
        context: Context,
        state: QuotaWidgetDisplayState,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_quota_card)

        views.setImageViewResource(
            R.id.widget_accent_dot,
            when (state.kind) {
                QuotaWidgetKind.GEMINI -> R.drawable.widget_dot_gemini
                QuotaWidgetKind.THIRD_PARTY -> R.drawable.widget_dot_third_party
            },
        )
        views.setTextViewText(R.id.widget_title, state.title)
        views.setTextViewText(R.id.widget_subtitle, state.subtitle)

        bindBucket(
            context = context,
            views = views,
            bucket = state.fiveHour,
            percentViewId = R.id.widget_5h_percent,
            leftLabelViewId = R.id.widget_5h_left_label,
            resetViewId = R.id.widget_5h_reset,
            healthyBarId = R.id.widget_5h_bar_healthy,
            warningBarId = R.id.widget_5h_bar_warning,
            criticalBarId = R.id.widget_5h_bar_critical,
        )

        bindBucket(
            context = context,
            views = views,
            bucket = state.weekly,
            percentViewId = R.id.widget_weekly_percent,
            leftLabelViewId = R.id.widget_weekly_left_label,
            resetViewId = R.id.widget_weekly_reset,
            healthyBarId = R.id.widget_weekly_bar_healthy,
            warningBarId = R.id.widget_weekly_bar_warning,
            criticalBarId = R.id.widget_weekly_bar_critical,
        )

        // Tap card -> open Gravity Well directly on the Status tab
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_DESTINATION, MainActivity.DESTINATION_STATUS)
        }
        val openRequestCode = when (state.kind) {
            QuotaWidgetKind.GEMINI -> REQUEST_CODE_OPEN_GEMINI
            QuotaWidgetKind.THIRD_PARTY -> REQUEST_CODE_OPEN_THIRD_PARTY
        }
        val openPending = PendingIntent.getActivity(
            context,
            openRequestCode,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        views.setOnClickPendingIntent(R.id.widget_root, openPending)

        // Tap refresh button -> force-refresh /api/observatory/usage?refresh=true
        val providerClass = when (state.kind) {
            QuotaWidgetKind.GEMINI -> GeminiQuotaWidgetProvider::class.java
            QuotaWidgetKind.THIRD_PARTY -> ThirdPartyQuotaWidgetProvider::class.java
        }
        val refreshIntent = Intent(context, providerClass).apply {
            action = ACTION_REFRESH_QUOTA
            putExtra(EXTRA_FORCE_REFRESH, true)
        }
        val refreshRequestCode = when (state.kind) {
            QuotaWidgetKind.GEMINI -> REQUEST_CODE_REFRESH_GEMINI
            QuotaWidgetKind.THIRD_PARTY -> REQUEST_CODE_REFRESH_THIRD_PARTY
        }
        val refreshPending = PendingIntent.getBroadcast(
            context,
            refreshRequestCode,
            refreshIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        views.setOnClickPendingIntent(R.id.widget_refresh_btn, refreshPending)

        return views
    }

    private fun bindBucket(
        context: Context,
        views: RemoteViews,
        bucket: WidgetBucketDisplay,
        percentViewId: Int,
        leftLabelViewId: Int,
        resetViewId: Int,
        healthyBarId: Int,
        warningBarId: Int,
        criticalBarId: Int,
    ) {
        views.setTextViewText(percentViewId, bucket.percentText)
        views.setTextViewText(resetViewId, bucket.resetText)
        views.setViewVisibility(leftLabelViewId, if (bucket.hasData) View.VISIBLE else View.GONE)

        @ColorRes
        val pctColorRes = if (!bucket.hasData) {
            R.color.widget_ink_muted
        } else {
            when (bucket.tier) {
                QuotaHealthTier.HEALTHY -> R.color.widget_healthy
                QuotaHealthTier.WARNING -> R.color.widget_warning
                QuotaHealthTier.CRITICAL -> R.color.widget_critical
            }
        }
        views.setTextColor(percentViewId, ContextCompat.getColor(context, pctColorRes))

        val activeBarId = when {
            !bucket.hasData -> healthyBarId
            bucket.tier == QuotaHealthTier.HEALTHY -> healthyBarId
            bucket.tier == QuotaHealthTier.WARNING -> warningBarId
            else -> criticalBarId
        }
        val progressVal = if (bucket.hasData) bucket.progress else 0

        listOf(healthyBarId, warningBarId, criticalBarId).forEach { barId ->
            val visible = barId == activeBarId
            views.setViewVisibility(barId, if (visible) View.VISIBLE else View.GONE)
            if (visible) {
                views.setProgressBar(barId, 100, progressVal, false)
            }
        }
    }

    private data class CachedUsageRecord(
        val hostLabel: String?,
        val usage: ObservatoryUsageResponseDto?,
        val fetchedAtIso: String?,
    )

    private fun loadCachedUsage(context: Context): CachedUsageRecord {
        val runtime = runCatching { GravityWellRuntime.get(context) }.getOrNull()
            ?: return CachedUsageRecord(null, null, null)
        val activeHost = runCatching { runtime.cacheStore.getActiveHost() }.getOrNull()
            ?: return CachedUsageRecord(null, null, null)
        val snap = runCatching { runtime.cacheStore.getObservatorySnapshot(activeHost.hostId) }.getOrNull()
        val parsedUsage = snap?.usageJson
            ?.takeIf { it.isNotBlank() && it != "{}" }
            ?.let { raw ->
                runCatching {
                    NetworkModule.defaultJson.decodeFromString<ObservatoryUsageResponseDto>(raw)
                }.getOrNull()
            }
        return CachedUsageRecord(
            hostLabel = activeHost.label,
            usage = parsedUsage,
            fetchedAtIso = snap?.fetchedAt,
        )
    }
}

abstract class BaseQuotaWidgetProvider : AppWidgetProvider() {

    protected abstract val kind: QuotaWidgetKind

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val appCtx = context.applicationContext
        QuotaWidgetUpdater.renderAllWidgetsFromCache(appCtx)

        if (!QuotaWidgetUpdater.isCachedUsageFresh(appCtx)) {
            val pendingResult = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    QuotaWidgetUpdater.refreshFromNetworkAndRender(appCtx, forceRefresh = false)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == QuotaWidgetUpdater.ACTION_REFRESH_QUOTA) {
            val appCtx = context.applicationContext
            val forceRefresh = intent.getBooleanExtra(QuotaWidgetUpdater.EXTRA_FORCE_REFRESH, true)
            QuotaWidgetUpdater.renderAllWidgetsFromCache(appCtx, isRefreshing = true)
            val pendingResult = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    QuotaWidgetUpdater.refreshFromNetworkAndRender(appCtx, forceRefresh = forceRefresh)
                } finally {
                    pendingResult.finish()
                }
            }
            return
        }
        super.onReceive(context, intent)
    }
}

class GeminiQuotaWidgetProvider : BaseQuotaWidgetProvider() {
    override val kind: QuotaWidgetKind = QuotaWidgetKind.GEMINI
}

class ThirdPartyQuotaWidgetProvider : BaseQuotaWidgetProvider() {
    override val kind: QuotaWidgetKind = QuotaWidgetKind.THIRD_PARTY
}
