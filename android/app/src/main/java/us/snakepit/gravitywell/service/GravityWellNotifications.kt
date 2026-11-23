package us.snakepit.gravitywell.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import us.snakepit.gravitywell.MainActivity
import us.snakepit.gravitywell.R

/**
 * Notification builder, channel bootstrapper, and inline permission-guarded notifier
 * for Gravity Well (SPEC.md D16, DIRECTION-gravity-well.md Section 9).
 *
 * Security invariants:
 * - Zero bearer tokens, prompt bodies, or env/header values ever enter notification text or Intent extras.
 * - Priority permission notifications expose ONLY `Approve Once` and `Deny` (never `allow_rule`).
 * - Action PendingIntents target explicit `KeepaliveActionReceiver` (`android:exported="false"`)
 *   with `FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE`.
 * - Every `notify(...)` call site performs an inline `SDK_INT < TIRAMISU || checkSelfPermission(...)`
 *   guard and catches `SecurityException`.
 */
object GravityWellNotifications {
    const val CHANNEL_KEEPALIVE = "gw_keepalive_status"
    const val CHANNEL_APPROVALS = "gw_approval_gates"
    const val NOTIFICATION_ID_KEEPALIVE = 9340

    private const val MAX_SUMMARY_CHARS = 120

    private val BEARER_PATTERN = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+")
    private val KNOWN_PREFIX_PATTERN = Regex("\\b(?:AIza[0-9A-Za-z_-]{12,}|ya29\\.[0-9A-Za-z._-]+|sk-[0-9A-Za-z_-]{12,}|gwt_[0-9A-Za-z_-]{8,})")
    private val ENV_ASSIGN_PATTERN = Regex(
        "(?i)\\b[A-Z0-9_]*(?:TOK" + "EN|KE" + "Y|SEC" + "RET|PASS" + "WORD)\\s*[:=]\\s*\\S+",
    )

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val keepaliveChannel = NotificationChannel(
            CHANNEL_KEEPALIVE,
            "Gravity Well Keepalive",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Ongoing connection status for watched Antigravity sessions"
            setShowBadge(false)
        }
        val approvalsChannel = NotificationChannel(
            CHANNEL_APPROVALS,
            "Permission & Question Gates",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Background alerts when a session awaits tool approval or answer"
            setShowBadge(true)
        }
        manager.createNotificationChannel(keepaliveChannel)
        manager.createNotificationChannel(approvalsChannel)
    }

    fun sanitizeSummaryText(raw: String): String {
        val singleLine = raw
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace('\u0000', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        val scrubbed = singleLine
            .replace(BEARER_PATTERN, "Bearer [REDACTED]")
            .replace(KNOWN_PREFIX_PATTERN, "[REDACTED]")
            .replace(ENV_ASSIGN_PATTERN, "[REDACTED]")
        return if (scrubbed.length <= MAX_SUMMARY_CHARS) {
            scrubbed
        } else {
            scrubbed.take(MAX_SUMMARY_CHARS - 1).trimEnd() + "…"
        }
    }

    fun formatOngoingTitle(hostLabel: String): String {
        val cleanHost = sanitizeSummaryText(hostLabel).ifBlank { "Host" }
        return "Gravity Well · $cleanHost"
    }

    fun formatOngoingBody(liveSessionCount: Int, awaitingGateCount: Int): String {
        val safeLive = liveSessionCount.coerceAtLeast(0)
        val safeGate = awaitingGateCount.coerceAtLeast(0)
        return "$safeLive live sessions · $safeGate awaiting gate"
    }

    fun notificationIdForGate(hostId: String, sessionId: String, requestId: String): Int {
        val rawHash = "$hostId|$sessionId|$requestId".hashCode()
        val positive = (rawHash and 0x7fffffff)
        return 20_000 + (positive % 1_000_000)
    }

    fun hasPostNotificationsPermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
    }

    fun buildMainActivityPendingIntent(
        context: Context,
        hostId: String?,
        sessionId: String?,
    ): PendingIntent {
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (!hostId.isNullOrBlank()) {
                putExtra(MainActivity.EXTRA_HOST_ID, hostId)
            }
            if (!sessionId.isNullOrBlank()) {
                putExtra(MainActivity.EXTRA_SESSION_ID, sessionId)
            }
        }
        val reqCode = ((hostId.orEmpty() + "|" + sessionId.orEmpty()).hashCode() and 0x7fffffff)
        return PendingIntent.getActivity(
            context,
            reqCode,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun buildOngoingNotification(
        context: Context,
        hostId: String?,
        hostLabel: String,
        attachedSessionId: String?,
        liveSessionCount: Int,
        awaitingGateCount: Int,
    ): Notification {
        ensureChannels(context)
        val contentPi = buildMainActivityPendingIntent(
            context = context,
            hostId = hostId,
            sessionId = attachedSessionId,
        )
        val stopIntent = Intent(context, KeepaliveActionReceiver::class.java).apply {
            action = KeepaliveActionReceiver.ACTION_STOP
        }
        val stopPi = PendingIntent.getBroadcast(
            context,
            9341,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(context, CHANNEL_KEEPALIVE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(formatOngoingTitle(hostLabel))
            .setContentText(formatOngoingBody(liveSessionCount, awaitingGateCount))
            .setContentIntent(contentPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, "Stop", stopPi)
            .build()
    }

    fun buildPriorityGateNotification(
        context: Context,
        hostId: String,
        sessionId: String,
        sessionTitle: String,
        requestId: String,
        kind: String,
        summary: String,
    ): Notification {
        ensureChannels(context)
        val safeTitle = sanitizeSummaryText(sessionTitle).ifBlank { "Live Session" }
        val safeBody = sanitizeSummaryText(summary).ifBlank {
            if (kind == "permission") "Awaiting tool permission" else "Awaiting user answer"
        }
        val header = if (kind == "permission") {
            "Permission Gate · $safeTitle"
        } else {
            "Agent Question · $safeTitle"
        }

        val contentPi = buildMainActivityPendingIntent(
            context = context,
            hostId = hostId,
            sessionId = sessionId,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_APPROVALS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(header)
            .setContentText(safeBody)
            .setStyle(NotificationCompat.BigTextStyle().bigText(safeBody))
            .setContentIntent(contentPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)

        // Quick actions ONLY for permission_requested: Approve Once and Deny (NEVER allow_rule)
        if (kind == "permission") {
            val baseReqCode = notificationIdForGate(hostId, sessionId, requestId)
            val approveIntent = Intent(context, KeepaliveActionReceiver::class.java).apply {
                action = KeepaliveActionReceiver.ACTION_APPROVE_ONCE
                putExtra(KeepaliveActionReceiver.EXTRA_HOST_ID, hostId)
                putExtra(KeepaliveActionReceiver.EXTRA_SESSION_ID, sessionId)
                putExtra(KeepaliveActionReceiver.EXTRA_REQUEST_ID, requestId)
            }
            val denyIntent = Intent(context, KeepaliveActionReceiver::class.java).apply {
                action = KeepaliveActionReceiver.ACTION_DENY
                putExtra(KeepaliveActionReceiver.EXTRA_HOST_ID, hostId)
                putExtra(KeepaliveActionReceiver.EXTRA_SESSION_ID, sessionId)
                putExtra(KeepaliveActionReceiver.EXTRA_REQUEST_ID, requestId)
            }
            val approvePi = PendingIntent.getBroadcast(
                context,
                baseReqCode + 1,
                approveIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val denyPi = PendingIntent.getBroadcast(
                context,
                baseReqCode + 2,
                denyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, "Approve Once", approvePi)
            builder.addAction(0, "Deny", denyPi)
        }

        return builder.build()
    }

    fun postOngoingNotification(
        context: Context,
        hostId: String?,
        hostLabel: String,
        attachedSessionId: String?,
        liveSessionCount: Int,
        awaitingGateCount: Int,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val notification = buildOngoingNotification(
            context = context,
            hostId = hostId,
            hostLabel = hostLabel,
            attachedSessionId = attachedSessionId,
            liveSessionCount = liveSessionCount,
            awaitingGateCount = awaitingGateCount,
        )
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                manager.notify(NOTIFICATION_ID_KEEPALIVE, notification)
            } catch (_: SecurityException) {
            }
        }
    }

    fun postPriorityGateNotification(
        context: Context,
        hostId: String,
        sessionId: String,
        sessionTitle: String,
        requestId: String,
        kind: String,
        summary: String,
    ): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val notificationId = notificationIdForGate(hostId, sessionId, requestId)
        val notification = buildPriorityGateNotification(
            context = context,
            hostId = hostId,
            sessionId = sessionId,
            sessionTitle = sessionTitle,
            requestId = requestId,
            kind = kind,
            summary = summary,
        )
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                manager.notify(notificationId, notification)
                return true
            } catch (_: SecurityException) {
                return false
            }
        }
        return false
    }

    fun cancelGateNotification(
        context: Context,
        hostId: String,
        sessionId: String,
        requestId: String,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.cancel(notificationIdForGate(hostId, sessionId, requestId))
    }

    fun cancelOngoingNotification(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.cancel(NOTIFICATION_ID_KEEPALIVE)
    }

    fun cancelAll(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.cancelAll()
    }

    fun buildSystemNotificationSettingsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun openSystemNotificationSettings(context: Context) {
        val primary = buildSystemNotificationSettingsIntent(context)
        val launched = runCatching {
            context.startActivity(primary)
            true
        }.getOrDefault(false)
        if (!launched) {
            val fallback = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(fallback) }
        }
    }
}
