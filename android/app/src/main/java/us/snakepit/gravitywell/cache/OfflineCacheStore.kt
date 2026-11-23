package us.snakepit.gravitywell.cache

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

class OfflineCacheStore(
    private val dbHelper: GravityWellDbHelper,
    private val maxEventsPerSession: Int = GravityWellDbHelper.MAX_EVENTS_PER_SESSION,
) {
    init {
        require(maxEventsPerSession >= 1) { "maxEventsPerSession must be >= 1" }
    }

    fun queryJournalMode(): String {
        val db = dbHelper.readableDatabase
        db.rawQuery("PRAGMA journal_mode;", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0).lowercase() else ""
        }
    }

    fun queryForeignKeysEnabled(): Boolean {
        val db = dbHelper.readableDatabase
        db.rawQuery("PRAGMA foreign_keys;", null).use { cursor ->
            return cursor.moveToFirst() && cursor.getInt(0) == 1
        }
    }

    // ── Hosts ───────────────────────────────────────────────────────────────

    fun upsertHost(host: CachedHost) {
        val db = dbHelper.writableDatabase
        inTransaction(db) {
            if (host.isActive) {
                db.execSQL("UPDATE hosts SET is_active = 0 WHERE host_id != ?", arrayOf(host.hostId))
            }
            val insertValues = ContentValues().apply {
                put("host_id", host.hostId)
                put("label", host.label)
                put("base_url", host.baseUrl)
                put("hostname", host.hostname)
                put("default_cwd", host.defaultCwd)
                put("agy_version", host.agyVersion)
                put("capabilities_json", host.capabilitiesJson)
                put("last_cursor", host.lastCursor)
                put("is_active", if (host.isActive) 1 else 0)
                put("created_at", host.createdAt)
                put("last_verified_at", host.lastVerifiedAt)
            }
            val rowId = db.insertWithOnConflict("hosts", null, insertValues, SQLiteDatabase.CONFLICT_IGNORE)
            if (rowId == -1L) {
                val updateValues = ContentValues(insertValues).apply {
                    remove("host_id")
                    remove("created_at")
                }
                db.update("hosts", updateValues, "host_id = ?", arrayOf(host.hostId))
            }
        }
    }

    fun getHost(hostId: String): CachedHost? {
        val db = dbHelper.readableDatabase
        db.rawQuery(
            "SELECT * FROM hosts WHERE host_id = ? LIMIT 1",
            arrayOf(hostId),
        ).use { c ->
            return if (c.moveToFirst()) c.toCachedHost() else null
        }
    }

    fun getActiveHost(): CachedHost? {
        val db = dbHelper.readableDatabase
        db.rawQuery(
            "SELECT * FROM hosts WHERE is_active = 1 ORDER BY last_verified_at DESC LIMIT 1",
            null,
        ).use { c ->
            return if (c.moveToFirst()) c.toCachedHost() else null
        }
    }

    fun listHosts(): List<CachedHost> {
        val db = dbHelper.readableDatabase
        val out = mutableListOf<CachedHost>()
        db.rawQuery(
            "SELECT * FROM hosts ORDER BY is_active DESC, label ASC",
            null,
        ).use { c ->
            while (c.moveToNext()) out += c.toCachedHost()
        }
        return out
    }

    fun setActiveHost(hostId: String) {
        val db = dbHelper.writableDatabase
        inTransaction(db) {
            db.execSQL("UPDATE hosts SET is_active = 0")
            db.execSQL("UPDATE hosts SET is_active = 1 WHERE host_id = ?", arrayOf(hostId))
        }
    }

    fun updateHostLastCursor(hostId: String, lastCursor: Long) {
        val db = dbHelper.writableDatabase
        db.execSQL(
            "UPDATE hosts SET last_cursor = MAX(last_cursor, ?) WHERE host_id = ?",
            arrayOf<Any>(lastCursor, hostId),
        )
    }

    fun deleteHost(hostId: String): Int {
        val db = dbHelper.writableDatabase
        return db.delete("hosts", "host_id = ?", arrayOf(hostId))
    }

    // ── Sessions ────────────────────────────────────────────────────────────

    fun upsertSession(session: CachedSession) {
        val db = dbHelper.writableDatabase
        inTransaction(db) {
            val insertValues = ContentValues().apply {
                put("host_id", session.hostId)
                put("session_id", session.sessionId)
                put("title", session.title)
                put("title_source", session.titleSource)
                put("preview", session.preview)
                put("cwd", session.cwd)
                put("add_dirs_json", session.addDirsJson)
                put("status", session.status)
                put("is_live", if (session.isLive) 1 else 0)
                put("model_id", session.modelId)
                put("thought_level", session.thoughtLevel)
                put("mode_id", session.modeId)
                put("config_json", session.configJson)
                put("commands_json", session.commandsJson)
                put("used_tokens", session.usedTokens)
                put("context_size", session.contextSize)
                put("last_cursor", session.lastCursor)
                put("last_seq", session.lastSeq)
                put("updated_at", session.updatedAt)
            }
            val rowId = db.insertWithOnConflict("sessions", null, insertValues, SQLiteDatabase.CONFLICT_IGNORE)
            if (rowId == -1L) {
                val updateValues = ContentValues(insertValues).apply {
                    remove("host_id")
                    remove("session_id")
                }
                db.update(
                    "sessions",
                    updateValues,
                    "host_id = ? AND session_id = ?",
                    arrayOf(session.hostId, session.sessionId),
                )
            }
        }
    }

    fun getSession(hostId: String, sessionId: String): CachedSession? {
        val db = dbHelper.readableDatabase
        db.rawQuery(
            "SELECT * FROM sessions WHERE host_id = ? AND session_id = ? LIMIT 1",
            arrayOf(hostId, sessionId),
        ).use { c ->
            return if (c.moveToFirst()) c.toCachedSession() else null
        }
    }

    fun listSessions(hostId: String, isLiveFilter: Boolean? = null): List<CachedSession> {
        val db = dbHelper.readableDatabase
        val (sql, args) = when (isLiveFilter) {
            null -> "SELECT * FROM sessions WHERE host_id = ? ORDER BY is_live DESC, updated_at DESC" to
                arrayOf(hostId)
            true -> "SELECT * FROM sessions WHERE host_id = ? AND is_live = 1 ORDER BY updated_at DESC" to
                arrayOf(hostId)
            false -> "SELECT * FROM sessions WHERE host_id = ? AND is_live = 0 ORDER BY updated_at DESC" to
                arrayOf(hostId)
        }
        val out = mutableListOf<CachedSession>()
        db.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) out += c.toCachedSession()
        }
        return out
    }

    fun deleteSession(hostId: String, sessionId: String): Int {
        val db = dbHelper.writableDatabase
        return db.delete("sessions", "host_id = ? AND session_id = ?", arrayOf(hostId, sessionId))
    }

    // ── Session Events (bounded to maxEventsPerSession = 1000) ──────────────

    fun insertSessionEvent(event: CachedSessionEvent) {
        insertSessionEvents(listOf(event))
    }

    fun insertSessionEvents(events: List<CachedSessionEvent>) {
        if (events.isEmpty()) return
        val db = dbHelper.writableDatabase
        val touchedPairs = LinkedHashSet<Pair<String, String>>()
        inTransaction(db) {
            for (ev in events) {
                val values = ContentValues().apply {
                    put("host_id", ev.hostId)
                    put("session_id", ev.sessionId)
                    put("seq", ev.seq)
                    put("cursor", ev.cursor)
                    put("event_type", ev.eventType)
                    put("payload_json", ev.payloadJson)
                    put("ts", ev.ts)
                }
                db.insertWithOnConflict("session_events", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                touchedPairs += (ev.hostId to ev.sessionId)
            }
            for ((hostId, sessionId) in touchedPairs) {
                pruneSessionEventsLocked(db, hostId, sessionId)
            }
        }
    }

    private fun pruneSessionEventsLocked(db: SQLiteDatabase, hostId: String, sessionId: String) {
        db.execSQL(
            """
            DELETE FROM session_events
            WHERE host_id = ? AND session_id = ?
              AND seq NOT IN (
                  SELECT seq FROM session_events
                  WHERE host_id = ? AND session_id = ?
                  ORDER BY seq DESC
                  LIMIT ?
              )
            """.trimIndent(),
            arrayOf<Any>(hostId, sessionId, hostId, sessionId, maxEventsPerSession),
        )
    }

    fun listSessionEvents(
        hostId: String,
        sessionId: String,
        afterCursor: Long = 0L,
        limit: Int = maxEventsPerSession,
    ): List<CachedSessionEvent> {
        val db = dbHelper.readableDatabase
        val out = mutableListOf<CachedSessionEvent>()
        db.rawQuery(
            """
            SELECT * FROM session_events
            WHERE host_id = ? AND session_id = ? AND cursor > ?
            ORDER BY seq ASC
            LIMIT ?
            """.trimIndent(),
            arrayOf(hostId, sessionId, afterCursor.toString(), limit.toString()),
        ).use { c ->
            while (c.moveToNext()) out += c.toCachedSessionEvent()
        }
        return out
    }

    fun countSessionEvents(hostId: String, sessionId: String): Long {
        val db = dbHelper.readableDatabase
        db.rawQuery(
            "SELECT COUNT(*) FROM session_events WHERE host_id = ? AND session_id = ?",
            arrayOf(hostId, sessionId),
        ).use { c ->
            return if (c.moveToFirst()) c.getLong(0) else 0L
        }
    }

    // ── Queued Followups ────────────────────────────────────────────────────

    fun replaceQueuedFollowups(
        hostId: String,
        sessionId: String,
        items: List<CachedQueuedFollowup>,
    ) {
        val db = dbHelper.writableDatabase
        inTransaction(db) {
            db.delete(
                "queued_followups",
                "host_id = ? AND session_id = ?",
                arrayOf(hostId, sessionId),
            )
            for (item in items) {
                val values = ContentValues().apply {
                    put("host_id", item.hostId)
                    put("session_id", item.sessionId)
                    put("item_id", item.itemId)
                    put("position", item.position)
                    put("text", item.text)
                    put("attachments_json", item.attachmentsJson)
                    put("status", item.status)
                    put("created_at", item.createdAt)
                }
                db.insertWithOnConflict("queued_followups", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }

    fun listQueuedFollowups(hostId: String, sessionId: String): List<CachedQueuedFollowup> {
        val db = dbHelper.readableDatabase
        val out = mutableListOf<CachedQueuedFollowup>()
        db.rawQuery(
            "SELECT * FROM queued_followups WHERE host_id = ? AND session_id = ? ORDER BY position ASC",
            arrayOf(hostId, sessionId),
        ).use { c ->
            while (c.moveToNext()) out += c.toCachedQueuedFollowup()
        }
        return out
    }

    // ── Pending Approvals & Questions ───────────────────────────────────────

    fun replacePendingApprovals(
        hostId: String,
        sessionId: String,
        items: List<CachedPendingApproval>,
    ) {
        val db = dbHelper.writableDatabase
        inTransaction(db) {
            db.delete(
                "pending_approvals",
                "host_id = ? AND session_id = ?",
                arrayOf(hostId, sessionId),
            )
            for (item in items) {
                val values = ContentValues().apply {
                    put("host_id", item.hostId)
                    put("session_id", item.sessionId)
                    put("request_id", item.requestId)
                    put("kind", item.kind)
                    put("title", item.title)
                    put("payload_json", item.payloadJson)
                    put("created_at", item.createdAt)
                }
                db.insertWithOnConflict("pending_approvals", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }

    fun upsertPendingApproval(item: CachedPendingApproval) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("host_id", item.hostId)
            put("session_id", item.sessionId)
            put("request_id", item.requestId)
            put("kind", item.kind)
            put("title", item.title)
            put("payload_json", item.payloadJson)
            put("created_at", item.createdAt)
        }
        db.insertWithOnConflict("pending_approvals", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deletePendingApproval(hostId: String, sessionId: String, requestId: String): Int {
        val db = dbHelper.writableDatabase
        return db.delete(
            "pending_approvals",
            "host_id = ? AND session_id = ? AND request_id = ?",
            arrayOf(hostId, sessionId, requestId),
        )
    }

    fun listPendingApprovals(hostId: String, sessionId: String): List<CachedPendingApproval> {
        val db = dbHelper.readableDatabase
        val out = mutableListOf<CachedPendingApproval>()
        db.rawQuery(
            "SELECT * FROM pending_approvals WHERE host_id = ? AND session_id = ? ORDER BY created_at ASC",
            arrayOf(hostId, sessionId),
        ).use { c ->
            while (c.moveToNext()) out += c.toCachedPendingApproval()
        }
        return out
    }

    // ── Composer Drafts ─────────────────────────────────────────────────────

    fun saveComposerDraft(draft: CachedComposerDraft) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("host_id", draft.hostId)
            put("session_id", draft.sessionId)
            put("draft_text", draft.draftText)
            put("attachments_json", draft.attachmentsJson)
            put("updated_at", draft.updatedAt)
        }
        db.insertWithOnConflict("composer_drafts", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getComposerDraft(hostId: String, sessionId: String): CachedComposerDraft? {
        val db = dbHelper.readableDatabase
        db.rawQuery(
            "SELECT * FROM composer_drafts WHERE host_id = ? AND session_id = ? LIMIT 1",
            arrayOf(hostId, sessionId),
        ).use { c ->
            return if (c.moveToFirst()) c.toCachedComposerDraft() else null
        }
    }

    fun clearComposerDraft(hostId: String, sessionId: String): Int {
        val db = dbHelper.writableDatabase
        return db.delete(
            "composer_drafts",
            "host_id = ? AND session_id = ?",
            arrayOf(hostId, sessionId),
        )
    }

    // ── Observatory Snapshots ───────────────────────────────────────────────

    fun saveObservatorySnapshot(snapshot: CachedObservatorySnapshot) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("host_id", snapshot.hostId)
            put("usage_json", snapshot.usageJson)
            put("status_json", snapshot.statusJson)
            put("fetched_at", snapshot.fetchedAt)
        }
        db.insertWithOnConflict("observatory_snapshots", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getObservatorySnapshot(hostId: String): CachedObservatorySnapshot? {
        val db = dbHelper.readableDatabase
        db.rawQuery(
            "SELECT * FROM observatory_snapshots WHERE host_id = ? LIMIT 1",
            arrayOf(hostId),
        ).use { c ->
            return if (c.moveToFirst()) c.toCachedObservatorySnapshot() else null
        }
    }

    private inline fun inTransaction(db: SQLiteDatabase, block: () -> Unit) {
        db.beginTransactionNonExclusive()
        try {
            block()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}

private fun Cursor.str(col: String): String = getString(getColumnIndexOrThrow(col))

private fun Cursor.strOrNull(col: String): String? {
    val idx = getColumnIndexOrThrow(col)
    return if (isNull(idx)) null else getString(idx)
}

private fun Cursor.lng(col: String): Long = getLong(getColumnIndexOrThrow(col))

private fun Cursor.integer(col: String): Int = getInt(getColumnIndexOrThrow(col))

private fun Cursor.toCachedHost(): CachedHost = CachedHost(
    hostId = str("host_id"),
    label = str("label"),
    baseUrl = str("base_url"),
    hostname = strOrNull("hostname"),
    defaultCwd = strOrNull("default_cwd"),
    agyVersion = strOrNull("agy_version"),
    capabilitiesJson = str("capabilities_json"),
    lastCursor = lng("last_cursor"),
    isActive = integer("is_active") != 0,
    createdAt = str("created_at"),
    lastVerifiedAt = str("last_verified_at"),
)

private fun Cursor.toCachedSession(): CachedSession = CachedSession(
    hostId = str("host_id"),
    sessionId = str("session_id"),
    title = str("title"),
    titleSource = str("title_source"),
    preview = str("preview"),
    cwd = str("cwd"),
    addDirsJson = str("add_dirs_json"),
    status = str("status"),
    isLive = integer("is_live") != 0,
    modelId = strOrNull("model_id"),
    thoughtLevel = strOrNull("thought_level"),
    modeId = strOrNull("mode_id"),
    configJson = str("config_json"),
    commandsJson = str("commands_json"),
    usedTokens = lng("used_tokens"),
    contextSize = lng("context_size"),
    lastCursor = lng("last_cursor"),
    lastSeq = lng("last_seq"),
    updatedAt = str("updated_at"),
)

private fun Cursor.toCachedSessionEvent(): CachedSessionEvent = CachedSessionEvent(
    hostId = str("host_id"),
    sessionId = str("session_id"),
    seq = lng("seq"),
    cursor = lng("cursor"),
    eventType = str("event_type"),
    payloadJson = str("payload_json"),
    ts = str("ts"),
)

private fun Cursor.toCachedQueuedFollowup(): CachedQueuedFollowup = CachedQueuedFollowup(
    hostId = str("host_id"),
    sessionId = str("session_id"),
    itemId = str("item_id"),
    position = integer("position"),
    text = str("text"),
    attachmentsJson = str("attachments_json"),
    status = str("status"),
    createdAt = str("created_at"),
)

private fun Cursor.toCachedPendingApproval(): CachedPendingApproval = CachedPendingApproval(
    hostId = str("host_id"),
    sessionId = str("session_id"),
    requestId = str("request_id"),
    kind = str("kind"),
    title = str("title"),
    payloadJson = str("payload_json"),
    createdAt = str("created_at"),
)

private fun Cursor.toCachedComposerDraft(): CachedComposerDraft = CachedComposerDraft(
    hostId = str("host_id"),
    sessionId = str("session_id"),
    draftText = str("draft_text"),
    attachmentsJson = str("attachments_json"),
    updatedAt = str("updated_at"),
)

private fun Cursor.toCachedObservatorySnapshot(): CachedObservatorySnapshot = CachedObservatorySnapshot(
    hostId = str("host_id"),
    usageJson = str("usage_json"),
    statusJson = str("status_json"),
    fetchedAt = str("fetched_at"),
)
