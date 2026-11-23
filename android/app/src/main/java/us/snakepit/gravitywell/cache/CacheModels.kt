package us.snakepit.gravitywell.cache

data class CachedHost(
    val hostId: String,
    val label: String,
    val baseUrl: String,
    val hostname: String? = null,
    val defaultCwd: String? = null,
    val agyVersion: String? = null,
    val capabilitiesJson: String = "{}",
    val lastCursor: Long = 0L,
    val isActive: Boolean = false,
    val createdAt: String,
    val lastVerifiedAt: String,
)

data class CachedSession(
    val hostId: String,
    val sessionId: String,
    val title: String,
    val titleSource: String = "acp",
    val preview: String = "",
    val cwd: String,
    val addDirsJson: String = "[]",
    val status: String,
    val isLive: Boolean = false,
    val modelId: String? = null,
    val thoughtLevel: String? = null,
    val modeId: String? = null,
    val configJson: String = "[]",
    val commandsJson: String = "[]",
    val usedTokens: Long = 0L,
    val contextSize: Long = 0L,
    val lastCursor: Long = 0L,
    val lastSeq: Long = 0L,
    val updatedAt: String,
)

data class CachedSessionEvent(
    val hostId: String,
    val sessionId: String,
    val seq: Long,
    val cursor: Long,
    val eventType: String,
    val payloadJson: String,
    val ts: String,
)

data class CachedQueuedFollowup(
    val hostId: String,
    val sessionId: String,
    val itemId: String,
    val position: Int,
    val text: String,
    val attachmentsJson: String = "[]",
    val status: String = "queued",
    val createdAt: String,
)

data class CachedPendingApproval(
    val hostId: String,
    val sessionId: String,
    val requestId: String,
    val kind: String,
    val title: String,
    val payloadJson: String,
    val createdAt: String,
)

data class CachedComposerDraft(
    val hostId: String,
    val sessionId: String,
    val draftText: String = "",
    val attachmentsJson: String = "[]",
    val updatedAt: String,
)

data class CachedObservatorySnapshot(
    val hostId: String,
    val usageJson: String = "{}",
    val statusJson: String = "{}",
    val fetchedAt: String,
)
