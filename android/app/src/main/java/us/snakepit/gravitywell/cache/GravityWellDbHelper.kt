package us.snakepit.gravitywell.cache

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Offline SQLite WAL cache matching docs/SPEC.md Section 3 DDL (D15).
 * Stores non-secret host metadata, session snapshots, bounded event history
 * (max 1000 rows per (host_id, session_id)), follow-up queue, pending
 * approvals/questions, composer drafts, and observatory snapshots.
 * Bearer tokens and credentials are NEVER stored in SQLite.
 */
class GravityWellDbHelper(
    context: Context,
    databaseName: String? = DATABASE_NAME,
) : SQLiteOpenHelper(context, databaseName, null, SCHEMA_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
        db.setForeignKeyConstraintsEnabled(true)
        db.execSQL("PRAGMA synchronous=NORMAL;")
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS hosts (
                host_id           TEXT PRIMARY KEY,
                label             TEXT NOT NULL,
                base_url          TEXT NOT NULL,
                hostname          TEXT,
                default_cwd       TEXT,
                agy_version       TEXT,
                capabilities_json TEXT NOT NULL DEFAULT '{}',
                last_cursor       INTEGER NOT NULL DEFAULT 0,
                is_active         INTEGER NOT NULL DEFAULT 0,
                created_at        TEXT NOT NULL,
                last_verified_at  TEXT NOT NULL
            );
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sessions (
                host_id           TEXT NOT NULL,
                session_id        TEXT NOT NULL,
                title             TEXT NOT NULL,
                title_source      TEXT NOT NULL DEFAULT 'acp',
                preview           TEXT NOT NULL DEFAULT '',
                cwd               TEXT NOT NULL,
                add_dirs_json     TEXT NOT NULL DEFAULT '[]',
                status            TEXT NOT NULL,
                is_live           INTEGER NOT NULL DEFAULT 0,
                model_id          TEXT,
                thought_level     TEXT,
                mode_id           TEXT,
                config_json       TEXT NOT NULL DEFAULT '[]',
                commands_json     TEXT NOT NULL DEFAULT '[]',
                used_tokens       INTEGER NOT NULL DEFAULT 0,
                context_size      INTEGER NOT NULL DEFAULT 0,
                last_cursor       INTEGER NOT NULL DEFAULT 0,
                last_seq          INTEGER NOT NULL DEFAULT 0,
                updated_at        TEXT NOT NULL,
                PRIMARY KEY (host_id, session_id),
                FOREIGN KEY (host_id) REFERENCES hosts(host_id) ON DELETE CASCADE
            );
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_sessions_host_updated
                ON sessions(host_id, is_live DESC, updated_at DESC);
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS session_events (
                host_id           TEXT NOT NULL,
                session_id        TEXT NOT NULL,
                seq               INTEGER NOT NULL,
                cursor            INTEGER NOT NULL,
                event_type        TEXT NOT NULL,
                payload_json      TEXT NOT NULL,
                ts                TEXT NOT NULL,
                PRIMARY KEY (host_id, session_id, seq),
                FOREIGN KEY (host_id, session_id) REFERENCES sessions(host_id, session_id) ON DELETE CASCADE
            );
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_events_cursor
                ON session_events(host_id, session_id, cursor);
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS queued_followups (
                host_id           TEXT NOT NULL,
                session_id        TEXT NOT NULL,
                item_id           TEXT NOT NULL,
                position          INTEGER NOT NULL,
                text              TEXT NOT NULL,
                attachments_json  TEXT NOT NULL DEFAULT '[]',
                status            TEXT NOT NULL DEFAULT 'queued',
                created_at        TEXT NOT NULL,
                PRIMARY KEY (host_id, session_id, item_id),
                FOREIGN KEY (host_id, session_id) REFERENCES sessions(host_id, session_id) ON DELETE CASCADE
            );
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS pending_approvals (
                host_id           TEXT NOT NULL,
                session_id        TEXT NOT NULL,
                request_id        TEXT NOT NULL,
                kind              TEXT NOT NULL,
                title             TEXT NOT NULL,
                payload_json      TEXT NOT NULL,
                created_at        TEXT NOT NULL,
                PRIMARY KEY (host_id, session_id, request_id),
                FOREIGN KEY (host_id, session_id) REFERENCES sessions(host_id, session_id) ON DELETE CASCADE
            );
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS composer_drafts (
                host_id           TEXT NOT NULL,
                session_id        TEXT NOT NULL,
                draft_text        TEXT NOT NULL DEFAULT '',
                attachments_json  TEXT NOT NULL DEFAULT '[]',
                updated_at        TEXT NOT NULL,
                PRIMARY KEY (host_id, session_id),
                FOREIGN KEY (host_id) REFERENCES hosts(host_id) ON DELETE CASCADE
            );
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS observatory_snapshots (
                host_id           TEXT PRIMARY KEY,
                usage_json        TEXT NOT NULL DEFAULT '{}',
                status_json       TEXT NOT NULL DEFAULT '{}',
                fetched_at        TEXT NOT NULL,
                FOREIGN KEY (host_id) REFERENCES hosts(host_id) ON DELETE CASCADE
            );
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Schema version 1 initial baseline.
    }

    companion object {
        const val DATABASE_NAME = "gravity_well_cache.db"
        const val SCHEMA_VERSION = 1
        const val MAX_EVENTS_PER_SESSION = 1000
    }
}
