<!--
  Authority: Architecture Specification
  Deliverable: Gravity Well
  Status: Locked (Wave 0)
-->
# Gravity Well — Architecture Specification

## 1. Verified Host Baseline (2026-11-23)

| Component | Verified Value / Path | Notes |
|---|---|---|
| Host OS / Hardware | Aurora Linux (`7.3.3-300.fc45.x86_64`), 16 cores, `62 GiB` RAM, `2.3T` NVMe (`1.3T` avail) | Immutable Fedora base; never run `dnf`. |
| Repo Workspace | `/home/jason/projects/gravity-well` (branch `main`) | Clean Gradle `9.8.2` wrapper in `android/`. |
| Antigravity CLI | `/home/jason/.local/bin/agy` (`1.5.15`) | CLI state under `~/.gemini/antigravity-cli/` (distinct from IDE `~/.gemini/antigravity/`). |
| Python / `uv` | `/usr/bin/python3` (`3.15.2`), `/home/jason/.local/bin/uv` (`0.10.0`) | Always prefix `uv` / `pytest` with `env -u PYTHONPATH`. |
| Java / Android SDK | `JAVA_HOME=/home/jason/.jdks/ms-21.0.9`, `ANDROID_HOME=/home/jason/Android/Sdk` | `platforms/android-36`, `build-tools/36.1.0`, `AGP 9.4.1`, `Kotlin 2.4.21`. |
| Gateway Ports | `0.0.0.0:9340` (prod), `127.0.0.1:9341` (loopback E2E bench) | Both verified unbound/free. |

---

## 2. Numbered Architectural Decisions (`D1..D16`)

### D1 — Sanctioned Antigravity Posture & Child Environment Scrub
- Gateway invokes **only** `/home/jason/.local/bin/agy` (`agy acp --stdio` and documented CLI subcommands) with `shell=False` and argv arrays, plus strictly read-only (`?mode=ro` SQLite URI / read-only FS) inspection of `~/.gemini/antigravity-cli/` and `~/.gemini/config/mcp_config.json`.
- Gateway MUST NEVER read, copy, print, or proxy `~/.gemini/oauth_creds.json`, `~/.gemini/config/config.json`, `jetbox_summaries_proto.pb`, `jetski_state.pbtxt`, `~/.hermes/`, or `cloudcode-pa` HTTP endpoints.
- Every `agy` subprocess MUST receive an explicit minimal allowlisted environment (`PATH`, `HOME`, `USER`, `LANG`, `LC_ALL`, `TERM`, `DBUS_SESSION_BUS_ADDRESS`, `XDG_RUNTIME_DIR`, `ANTIGRAVITY_ENV_STRICT=1`), stripping all `*_KEY`, `*_TOKEN`, `*_SECRET`, `PYTHONPATH`, and `GRAVITY_WELL_*` host environment variables.

### D2 — Bearer Authentication & 30s Single-Use WebSocket Tickets
- `GET /api/health` is unauthenticated and returns zero host paths or secrets.
- All other REST and WebSocket endpoints require token verification using `hmac.compare_digest` against the active gateway bearer token in `~/.config/gravity-well/gateway.env` (`0600`, auto-minted via `secrets.token_urlsafe(32)` on first run if missing).
- WebSockets (`WS /api/ws`, `WS /api/sessions/{id}/ws`) accept either the `Authorization: Bearer <token>` upgrade header OR a single-use `30s` TTL `?ticket=<ticket>` minted via `POST /api/ws-ticket`. Requests carrying permanent tokens in query parameters (`?token=`, `?bearer=`, `?auth=`, or permanent token inside `?ticket=`) MUST be rejected with HTTP `401 Unauthorized` before upgrade.

### D3 — Per-Session `agy acp --stdio` Process Supervision & Clean Teardown
- Each live session owns one dedicated `agy acp --stdio` child process spawned in its validated `cwd` with newline-delimited JSON-RPC 2.0 over stdin/stdout (64 KiB+ line reader buffer, e.g., `limit=16*1024*1024` on `asyncio.StreamReader` to handle large `session/load` / `tool_call` frames without `LimitOverrunError`).
- On `DELETE /api/sessions/{id}` or gateway shutdown: send ACP `session/close` (best-effort, `1.0s` timeout), close child `stdin` (EOF, waiting `2.0s` for clean `rc=0` exit per R3), then escalate `SIGTERM` (`2.0s`) -> `SIGKILL` and await `wait()` so zero zombie processes remain.

### D4 — Unsupported ACP Methods (`-32601`) Resolution Strategy
Based on empirical baseline R3/R10 (`agy 1.5.15` returns `-32601` for `session/fork`, `session/rename`, `session/set_title`, `session/delete`, `session/set_additional_directories`):
1. `GET /api/info` publishes explicit `capabilities` booleans (`fork: false`, `acp_rename: false`, `gateway_title_override: true`, `acp_delete: false`, `set_additional_directories_live: false`, `spawn_add_dir: true`).
2. `POST /api/sessions/{id}/fork` calls ACP `session/fork` (supporting future `agy` upgrades transparently) and translates `-32601` into HTTP `501 Not Implemented` (`acp_method_unsupported`) with fallback guidance.
3. `PATCH /api/sessions/{id}` (rename) persists custom titles in `~/.config/gravity-well/session_meta.json` (atomic temp-file + `os.replace`) and broadcasts a synthetic `session_info_update` event (`title_source: "gateway"`).
4. `add_dirs` are validated at session creation/resume and passed as `--add-dir <path>` CLI flags at `agy acp` spawn.
5. `DELETE /api/sessions/{id}` closes only the live supervised process; archived disk trajectories are never deleted via ACP.

### D5 — Live Config (`configOptions` vs `availableModels`)
- `PATCH /api/sessions/{id}/config` uses ACP `session/set_config_option` (`configId`: `"model"`, `"thought_level"`, `"mode"`).
- `model` uses base family IDs (`gemini-4-neon`, `gemini-3.8-flash`, `claude-sonnet-5-5`, etc.) paired with `thought_level` (`low|medium|high|xhigh`) and `mode` (`default|accept-edits|auto|plan`) from ACP `configOptions` (R4).

### D6 — Serialized FIFO Follow-Up Queue & Draft Preservation
- Per-session `asyncio.Lock` serializes `session/prompt` dispatch. Prompts submitted while busy (`enqueue_if_busy=true`) enter the session's FIFO queue.
- Queue auto-drain triggers **only** when `session/prompt` completes with `stopReason == "end_turn"` and `queue_paused == false`. Cancellation (`POST /api/sessions/{id}/cancel` -> `stopReason == "cancelled"`) or process/RPC error sets `queue_paused = true` and preserves remaining items without auto-sending.
- `POST /api/sessions/{id}/queue/{item_id}/send-now` moves the item to head; if idle, dispatches immediately; if busy and `interrupt=true`, marks `dispatch_on_settle=true` and sends `session/cancel` so that exact item runs as soon as the cancelled turn resolves. Android's `Send Now` action MUST NOT clear or overwrite the user's active composer draft.

### D7 — Dynamic Permission Broker, `elicitation/create` & Revocable Allow-Rules
- Server-initiated ACP `session/request_permission` and `elicitation/create` requests suspend in an in-memory broker map (`request_id -> asyncio.Future`) and emit `permission_requested` / `question_requested` events.
- Permission resolution inspects the dynamic `options` array (`allow_once`, `allow_always`, `reject_once`, etc.) rather than hardcoding option IDs (R6).
- `allow_rule` persists a workspace-scoped rule `(rule_id, workspace_root, tool_kind, command_prefix)` in `~/.config/gravity-well/permission_rules.json` and exposes `GET /api/permissions/rules` + `DELETE /api/permissions/rules/{rule_id}` so every granted rule has an in-app revoke path.
- `elicitation/create` responses preserve exact `requestedSchema.properties` keys (`q_0`, etc., R6) and validate required fields before answering ACP.

### D8 — Monotonic Cursor, Per-Session `seq` & Ring-Buffer Replay
- Gateway assigns every emitted event a process-monotonic global `cursor` (`1, 2, ...`, seeded/incremented atomically) and per-session `seq` (`1, 2, ...`).
- Each session maintains a bounded `collections.deque(maxlen=2000)` event ring buffer (plus a global `maxlen=4000` multiplexed ring buffer for `/api/ws`).
- On `WS /api/ws?after=<cursor>`, `WS /api/sessions/{id}/ws?after=<cursor>`, or `GET /api/sessions/{id}/events?after=<cursor>`, gateway replays all buffered events with `event.cursor > after`. If `after > 0` and `after < (min_cursor_retained - 1)`, gateway emits `resync_required` (WS) or `"truncated": true` (HTTP) so the client re-fetches `GET /api/sessions/{id}`.

### D9 — Read-Only SQLite FTS5, Symlink Containment & Magic-Byte Uploads
- `~/.gemini/antigravity-cli/conversation_summaries.db` is ALWAYS opened via `sqlite3.connect(f"file:{db_path}?mode=ro", uri=True, check_same_thread=False)` (`PRAGMA query_only = ON`) — zero write locks against active `agy` processes.
- `brain/{session_id}/` artifact browsing/serving and `@file` workspace searches resolve `os.path.realpath` on both the root and every candidate/target path, rejecting any escape outside the allowed root (`403 Forbidden`).
- Attachment uploads verify magic bytes (PNG `\x89PNG\r\n\x1a\n`, JPEG `\xff\xd8\xff`, GIF `GIF87a`/`GIF89a`, WebP `RIFF....WEBP`, PDF `%PDF-`, or valid UTF-8 text free of NUL bytes) and reject mismatched/unsupported binaries (`415`) or `> 10 MiB` (`413`).

### D10 — External-Session Radar (`presence/*.lock`) Liveness Rule
- Because `agy` leaves historical zero-byte `<uuid>.lock` files in `~/.gemini/antigravity-cli/presence/` after exit (R9: 710 stale files observed), gateway MUST NEVER report `.lock` filename counts as active external sessions.
- Liveness is proven **only** when the `.lock` file's `(st_dev, st_ino)` matches an active `FLOCK` entry in `/proc/locks`, OR (fallback when `/proc/locks` lacks visibility) a non-blocking `fcntl.flock(fd, LOCK_EX | LOCK_NB)` probe raises `BlockingIOError`/`EWOULDBLOCK` (and on success immediately calls `LOCK_UN` and closes `fd` without truncating or modifying the file).

### D11 — Observatory Telemetry, Redaction & Confirm-Gated Update
- `/api/observatory/usage` executes `agy -p /usage --output-format json` (`60s` in-memory TTL cache, `refresh=true` bypass) and parses `command.data.groups` directly (R8).
- `/api/observatory/logs` resolves `cli.log` inside `log/` (symlink-contained) and `crashes/` files, filtering all text lines through `redaction.redact_text()` (masking Bearer headers, `AIza*`, `ya29.*`, `sk-*`, `refresh_token`, `client_secret`, and `*_KEY/*_TOKEN/*_SECRET=*`).
- `/api/observatory/update` rejects requests lacking `{"confirm": true}` (`400`) and invokes `agy update` via `subprocess` with `shell=False` and scrubbed env.

### D12 — Ecosystem CRUD & Allowlisted `settings.json` Writes
- `mcp` (`add|enable|disable|remove|list`) and `plugin` (`list --json|validate|install|enable|disable|uninstall`) delegate to `agy` CLI subcommands with `shell=False`; `agy mcp add` places all flags (`--type`, `-e`, `-H`) strictly BEFORE `<name>` (R7). Secrets in `mcp_config.json` `env`/`headers` are masked as `"[REDACTED]"` on `GET`.
- `agents` reads `agy agent` + `~/.gemini/agents/*.md` and manages user-created agents in `~/.gemini/antigravity-cli/agents/<slug>.md` atomically.
- `settings.json` reads/writes are strictly restricted to allowlisted keys (`general.previewFeatures`, `general.enablePromptCompletion`). Writes use atomic temp-file + `fsync` + `os.replace`, preserving untouched keys without reading/exposing `security.auth`.

### D13 — Android Identity, KeyStore Vault & 3-Leg Host Onboarding
- `applicationId = "us.snakepit.gravitywell"`, `debug` `applicationIdSuffix = ".debug"`, `minSdk = 26`, `targetSdk = 36`, `compileSdk = 36`, `AGP = 9.4.1`, `Kotlin = 2.4.21`, `Gradle = 9.8.2`, `Java 17` target (`JAVA_HOME=/home/jason/.jdks/ms-21.0.9`).
- Release `AndroidManifest.xml` enforces `android:usesCleartextTraffic="false"`. Any optional debug-only LAN HTTP exception MUST live solely in `src/debug/res/xml/network_security_config.xml`.
- Multi-host bearer tokens are encrypted with `AndroidKeyStore` (`AES/GCM/NoPadding`, `KeyProperties.PURPOSE_ENCRYPT or PURPOSE_DECRYPT`, default `setRandomizedEncryptionRequired(true)`). On encrypt, omit caller `GCMParameterSpec` so KeyStore generates a fresh random 12-byte `cipher.iv` (assert `iv.size == 12`); store `(iv, ciphertext)` base64-encoded in app-private storage (`hosts_vault.json` or `SharedPreferences`), **never** in SQLite. On decrypt, pass `GCMParameterSpec(128, iv)`.
- Host onboarding executes 3 sequential diagnostic legs before saving a verified host:
  1. `GET /api/health` (reachability + TLS + `protocol_version == 1`)
  2. `GET /api/info` (bearer token validity + capability flags)
  3. `POST /api/ws-ticket` -> `WS /api/ws?ticket=...` -> receive `hello` frame.

### D14 — Android Networking, Error Interceptor & WS Reconnect Guard
- A single `NetworkModule` builds the shared `OkHttpClient` (timeouts, bearer auth header injector, `GatewayErrorInterceptor`) used by Retrofit/REST, artifact downloads, WebSocket upgrades, AND JVM `MockWebServer` tests.
- `GatewayErrorInterceptor` parses non-2xx JSON `{"error": {...}}` bodies into `GatewayException(status, code, message, details)` and normalizes IO/TLS errors into typed failures.
- WebSocket manager mints a fresh `POST /api/ws-ticket` on every connect/reconnect, reconnects with exponential backoff (`1s, 2s, 4s, 8s, 16s, 30s` cap + jitter), and guards callbacks with an `AtomicLong` `connectionGeneration` counter so stale socket callbacks from superseded connections are ignored.

### D15 — Android `SQLiteOpenHelper` (`WAL`) Offline Cache
- Single `GravityWellDbHelper : SQLiteOpenHelper` enables `PRAGMA journal_mode=WAL` and foreign keys in `onConfigure()`, storing ONLY non-secret host metadata, session snapshots, bounded event history (max `1000` rows per `(host_id, session_id)` pruned on insert), queued follow-ups, pending approvals/questions, active composer drafts, and observatory snapshots. Zero tokens or credentials ever touch SQLite.

### D16 — Android Keepalive Service, Notifications & `singleTask` Intent Queue
- `GravityWellKeepaliveService` runs as `android:foregroundServiceType="dataSync"` (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`, `INTERNET` in manifest) while the user has keepalive enabled and an active session/approval is watched.
- `KeepaliveActionReceiver` (`android:exported="false"`) handles notification actions (`Approve Once`, `Deny`, `Stop`) via `PendingIntent.FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT` without storing secrets in `Intent` extras.
- Every `NotificationManager.notify()` call site performs an inline `Build.VERSION.SDK_INT >= 33` + `ContextCompat.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PERMISSION_GRANTED` check AND wraps `notify()` in `try { ... } catch (_: SecurityException) {}` (satisfying Android Lint `MissingPermission` cleanly).
- **In-App Revoke Path:** Control Deck / Settings exposes an explicit toggle to disable Background Keepalive & Notifications (stops `GravityWellKeepaliveService`, cancels notifications, and links to system app notification settings).
- `MainActivity` uses `android:launchMode="singleTask"` and buffers deep-link `(hostId, sessionId)` intents in a pending intent queue across both cold start (`onCreate`) and warm resume (`onNewIntent`) until the Compose navigation graph is ready.

---

## 3. Android `SQLiteOpenHelper` (`WAL`) Cache DDL

Database name: `gravity_well_cache.db` (`SCHEMA_VERSION = 1`).
In `onConfigure(db: SQLiteDatabase)`:
- `db.enableWriteAheadLogging()` (or `PRAGMA journal_mode=WAL;`)
- `db.setForeignKeyConstraintsEnabled(true)`
- `PRAGMA synchronous=NORMAL;`

```sql
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

CREATE INDEX IF NOT EXISTS idx_sessions_host_updated
    ON sessions(host_id, is_live DESC, updated_at DESC);

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

CREATE INDEX IF NOT EXISTS idx_events_cursor
    ON session_events(host_id, session_id, cursor);

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

CREATE TABLE IF NOT EXISTS pending_approvals (
    host_id           TEXT NOT NULL,
    session_id        TEXT NOT NULL,
    request_id        TEXT NOT NULL,
    kind              TEXT NOT NULL, -- 'permission' | 'question'
    title             TEXT NOT NULL,
    payload_json      TEXT NOT NULL,
    created_at        TEXT NOT NULL,
    PRIMARY KEY (host_id, session_id, request_id),
    FOREIGN KEY (host_id, session_id) REFERENCES sessions(host_id, session_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS composer_drafts (
    host_id           TEXT NOT NULL,
    session_id        TEXT NOT NULL,
    draft_text        TEXT NOT NULL DEFAULT '',
    attachments_json  TEXT NOT NULL DEFAULT '[]',
    updated_at        TEXT NOT NULL,
    PRIMARY KEY (host_id, session_id),
    FOREIGN KEY (host_id) REFERENCES hosts(host_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS observatory_snapshots (
    host_id           TEXT PRIMARY KEY,
    usage_json        TEXT NOT NULL DEFAULT '{}',
    status_json       TEXT NOT NULL DEFAULT '{}',
    fetched_at        TEXT NOT NULL,
    FOREIGN KEY (host_id) REFERENCES hosts(host_id) ON DELETE CASCADE
);
```

**Cache Retention & Bounds:**
- On insert into `session_events`, prune oldest rows where `(SELECT COUNT(*) ...) > 1000` for that `(host_id, session_id)`.
- Deleting a host cascades across all child tables; bearer tokens are NEVER stored in `hosts` or any SQLite table.

---

## 4. Repository File Ownership Map

| Wave / Card | Assignee | Owned Paths |
|---|---|---|
| `w0-research` | `researcher` | `docs/RESEARCH.md` |
| `w0-spec` | `specifier` | `docs/API.md`, `docs/SPEC.md` |
| `w0-direction` | `creative-director` | `docs/creative/DIRECTION-gravity-well.md` |
| `w1-gateway-core` | `coder` | `gateway/pyproject.toml`, `gateway/requirements.txt`, `gateway/gateway.env.example`, `gateway/gravity_well_gateway/__init__.py`, `main.py`, `config.py`, `auth.py`, `acp_client.py`, `supervisor.py`, `replay.py`, `storage.py`, `gateway/tests/{conftest.py,fake_agy.py,test_auth.py,test_acp_supervisor.py,test_replay_queue.py,test_storage_ro.py}` |
| `w1-gateway-ops` | `coder` | `gateway/gravity_well_gateway/{observatory.py,ecosystem.py,redaction.py,main.py}`, `gateway/systemd/gravity-well-gateway.service`, `gateway/scripts/install_service.sh`, `gateway/tests/{test_observatory.py,test_ecosystem.py,test_redaction.py}` |
| `w1-gateway-review` | `reviewer` | `.plan/reviews/w1-gateway-review.md` (read-only on code) |
| `w1-gateway-security` | `security` | `.plan/reviews/w1-gateway-security.md` (read-only on code) |
| `w2-android-foundation` | `coder` | `android/{settings.gradle.kts,build.gradle.kts,gradle.properties,gradle/libs.versions.toml}`, `android/app/{build.gradle.kts,proguard-rules.pro,src/main/AndroidManifest.xml,src/main/res/**}`, `android/app/src/main/java/us/snakepit/gravitywell/{theme,security,cache,net}/**`, `android/app/src/test/**` |
| `w2-android-session` | `coder` | `android/app/src/main/java/us/snakepit/gravitywell/{MainActivity.kt,GravityWellApp.kt}`, `.../ui/{shell,hosts,sessions,console}/**`, `android/app/src/test/**` |
| `w2-android-review` | `reviewer` | `.plan/reviews/w2-android-review.md` (read-only on code) |
| `w3-android-ops` | `coder` | `android/app/src/main/java/us/snakepit/gravitywell/{ui/observatory,ui/deck,service}/**`, `AndroidManifest.xml`, `MainActivity.kt`, `ui/shell/**`, `res/drawable/ic_notification.xml`, `android/app/src/test/**` |
| `w3-full-review` | `reviewer` | `.plan/reviews/w3-full-review.md` (read-only on code) |
| `w3-creative-critique` | `creative-director` | `.plan/reviews/w3-creative-critique.md` (read-only on code) |
| `w3-security-audit` | `security` | `.plan/reviews/w3-security-audit.md` (read-only on code) |
| `w4-integration` | `coder` | `gateway/tests/test_e2e_live.py`, `docs/RUNBOOK.md` |


