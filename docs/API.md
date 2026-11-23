<!--
  Authority: Canonical Wire Contract
  Deliverable: Gravity Well
  Status: Locked (Wave 0)
-->
# Gravity Well — Canonical REST & WebSocket Wire Contract

**Gateway Listen Default:** `0.0.0.0:9340` (production `systemd` user unit), `127.0.0.1:9341` (isolated loopback E2E bench).  
**Transport:** HTTPS / WSS in production (via Tailscale Serve or reverse proxy); HTTP / WS only on loopback (`127.0.0.1`) during local development and automated tests.  
**Media Type:** `application/json; charset=utf-8` for all REST JSON endpoints; `multipart/form-data` for attachment uploads; raw bytes with sniffed MIME type for artifact downloads.  
**Timestamps:** ISO-8601 UTC (`YYYY-MM-DDTHH:MM:SSZ` or fractional seconds).

---

## 1. Error Envelope & HTTP Status Codes

Every non-2xx JSON response MUST conform to:

```json
{
  "error": {
    "code": "unauthorized",
    "message": "Missing or invalid bearer token.",
    "status": 401,
    "details": {}
  }
}
```

| HTTP Status | Canonical `error.code` | Meaning |
|---|---|---|
| `400` | `bad_request` | Malformed JSON, missing confirmation flag (`{"confirm": true}`), or invalid parameter combination. |
| `401` | `unauthorized` | Missing/invalid `Authorization: Bearer`, expired/used/missing WS ticket, or forbidden query-string permanent token. |
| `403` | `forbidden` | Path traversal / symlink escape attempt, disallowed setting key, or disallowed workspace path. |
| `404` | `not_found` | Unknown `session_id`, `request_id`, `item_id`, `rule_id`, `artifact_path`, or ACP `-32002` (`conversation not found`). |
| `409` | `conflict` | Session already closing, duplicate active request, or `enqueue_if_busy=false` while a turn is active. |
| `413` | `payload_too_large` | Attachment exceeds `10 MiB` (`10485760` bytes). |
| `415` | `unsupported_media_type` | Attachment magic bytes mismatch or unsupported file type. |
| `422` | `validation_error` | Pydantic schema validation failure (e.g., mismatched `elicitation/create` answer keys or invalid config option value). |
| `501` | `acp_method_unsupported` | Underlying `agy` binary returned ACP `-32601 Method not found` (e.g., `session/fork` on `agy 1.5.15`). |
| `502` | `acp_upstream_error` | `agy acp --stdio` or CLI subprocess exited unexpectedly or returned invalid JSON-RPC. |
| `503` | `service_unavailable` | `agy` binary unavailable or subsystem shutting down. |

---

## 2. Authentication & Bootstrap Endpoints

- **Token Source:** `~/.config/gravity-well/gateway.env` (`0600` permissions, auto-created on first gateway startup with `GRAVITY_WELL_TOKEN=<secrets.token_urlsafe(32)>` if absent; overridable via `GRAVITY_WELL_ENV_FILE` or `GRAVITY_WELL_TOKEN` in test harnesses).
- **Comparison:** All bearer token and WS ticket checks MUST use constant-time `hmac.compare_digest`.
- **Query-String Permanent Token Prohibition:** Any HTTP or WebSocket request carrying `?token=`, `?bearer=`, or `?auth=` (or passing the permanent gateway token inside `?ticket=`) MUST be rejected immediately with HTTP `401 Unauthorized` before WebSocket upgrade.

### `GET /api/health` (Unauthenticated)
Liveness probe for Leg 1 of Android host onboarding and `systemd` health checks. Never reveals tokens, user paths, or host secrets.

**Response `200 OK`:**
```json
{
  "status": "ok",
  "service": "gravity-well-gateway",
  "gateway_version": "0.1.0",
  "agy_version": "1.5.15",
  "protocol_version": 1,
  "auth_required": true,
  "uptime_seconds": 42
}
```

### `GET /api/info` (Authenticated)
Capability and host metadata probe for Leg 2 of Android host onboarding.

**Response `200 OK`:**
```json
{
  "gateway_version": "0.1.0",
  "agy_version": "1.5.15",
  "acp_protocol_version": 1,
  "hostname": "aurora",
  "default_cwd": "/home/jason",
  "capabilities": {
    "load_session": true,
    "resume_session": true,
    "fork": false,
    "acp_rename": false,
    "gateway_title_override": true,
    "acp_delete": false,
    "set_additional_directories_live": false,
    "spawn_add_dir": true,
    "elicitation": true,
    "remote_control": true
  },
  "default_config_options": [
    {
      "id": "model",
      "name": "Model",
      "category": "model",
      "currentValue": "gemini-4-neon",
      "options": []
    }
  ]
}
```

### `POST /api/ws-ticket` (Authenticated)
Mints a single-use, `30s` TTL WebSocket upgrade ticket so Android/clients never place permanent bearer tokens in WebSocket URLs.

**Request:** empty body or `{}`  
**Response `201 Created`:**
```json
{
  "ticket": "gwt_9f8a...",
  "ttl_seconds": 30,
  "expires_at": "2026-11-23T15:00:30Z"
}
```
Tickets are stored in memory only, invalidated immediately upon the first valid WS upgrade (or upon expiration), and bounded to a capped store (max `1024` active tickets, pruning expired entries on mint).

---

## 3. Sessions & ACP Supervisor

All session endpoints require `Authorization: Bearer <token>`.

### Canonical `SessionSnapshot` Object
```json
{
  "session_id": "5b1f0b18-...",
  "title": "Gravity Well Spec",
  "title_source": "gateway",
  "cwd": "/home/jason/projects/gravity-well",
  "add_dirs": [],
  "status": "idle",
  "is_live": true,
  "agent": null,
  "sandbox": false,
  "config_options": [
    {"id": "model", "name": "Model", "category": "model", "currentValue": "gemini-4-neon", "options": []},
    {"id": "thought_level", "name": "Thought Level", "category": "thought_level", "currentValue": "high", "options": []},
    {"id": "mode", "name": "Mode", "category": "mode", "currentValue": "default", "options": []}
  ],
  "available_models": [
    {"model_id": "gemini-4-neon-high", "name": "Gemini 4 Neon (High)"}
  ],
  "available_commands": [
    {"name": "plan", "description": "..."}
  ],
  "usage": {"used": 8709, "size": 1048576},
  "pending_permissions": [],
  "pending_questions": [],
  "queued_followups": [],
  "cursor": 14,
  "seq": 14,
  "updated_at": "2026-11-23T14:55:00Z"
}
```
`status` values: `"starting"`, `"idle"`, `"running"`, `"awaiting_approval"`, `"awaiting_question"`, `"closing"`, `"closed"`, `"archived"`, `"error"`.

### `GET /api/sessions`
Lists active supervised sessions merged with archived sessions from `?mode=ro` `conversation_summaries.db` and gateway title overrides.

**Query params:**
- `status`: `all` (default) | `live` | `archived`
- `q`: optional FTS5 search query against `conversations_fts` (plus substring match on live sessions)
- `cwd`: optional workspace path filter
- `limit`: integer `1..200` (default `50`)
- `offset`: integer `>= 0` (default `0`)

**Response `200 OK`:**
```json
{
  "items": [
    {
      "session_id": "5b1f0b18-...",
      "title": "Gravity Well Spec",
      "preview": "Last user or model snippet...",
      "cwd": "/home/jason/projects/gravity-well",
      "is_live": true,
      "status": "idle",
      "step_count": 12,
      "model_id": "gemini-4-neon",
      "thought_level": "high",
      "mode_id": "default",
      "updated_at": "2026-11-23T14:55:00Z"
    }
  ],
  "total": 1,
  "limit": 50,
  "offset": 0
}
```

### `POST /api/sessions`
Spawns a dedicated `agy acp --stdio` child process in `cwd`, performs ACP `initialize` + `session/new`, and applies any initial `model` / `thought_level` / `mode` overrides via `session/set_config_option`.

**Request:**
```json
{
  "cwd": "/home/jason/projects/gravity-well",
  "add_dirs": ["/home/jason/projects/shared-lib"],
  "title": "API Refactor",
  "model": "gemini-4-neon",
  "thought_level": "high",
  "mode": "default",
  "agent": null,
  "sandbox": false
}
```
- `add_dirs`: validated as existing directories, passed at spawn via `--add-dir <dir>` (since `agy 1.5.15` returns `-32601` for `session/set_additional_directories`).
- `title`: optional initial custom title persisted in gateway session metadata store.

**Response `201 Created`:** `SessionSnapshot`

### `GET /api/sessions/{session_id}`
Returns live `SessionSnapshot` if supervised; otherwise returns archived summary (`is_live: false`, `status: "archived"`) from `conversation_summaries.db` (or `404` if unknown).

### `POST /api/sessions/{session_id}/resume`
Attaches a live `agy acp --stdio` supervisor to an existing or archived session using ACP `session/resume` (default) or `session/load` (when `load_history: true`).

**Request:**
```json
{
  "cwd": "/home/jason/projects/gravity-well",
  "load_history": true,
  "add_dirs": [],
  "sandbox": false
}
```
- When `load_history` is `true`, ACP `session/load` replays historical `session/update` notifications (`user_message_chunk`, `agent_thought_chunk`, `agent_message_chunk`, `tool_call`, `tool_call_update`, `usage_update`) into the session's replay ring buffer before returning `200 OK`.
- Maps ACP `-32002` (`conversation not found`) to `404 Not Found`.

**Response `200 OK`:** `SessionSnapshot`

### `POST /api/sessions/{session_id}/fork`
Attempts ACP `session/fork`. Because `agy 1.5.15` returns `-32601 Method not found` (`capabilities.fork == false`), the gateway returns `501 Not Implemented`:
```json
{
  "error": {
    "code": "acp_method_unsupported",
    "message": "Antigravity CLI 1.5.15 does not support session/fork (-32601).",
    "status": 501,
    "details": {
      "acp_method": "session/fork",
      "acp_code": -32601,
      "fallback": "Create a new session in the same cwd or resume with load_history=true."
    }
  }
}
```

### `PATCH /api/sessions/{session_id}`
Updates gateway-managed session metadata (custom title). Because `agy 1.5.15` returns `-32601` for `session/rename` and `session/set_title`, custom titles are persisted in `~/.config/gravity-well/session_meta.json` (atomic temp-file + `os.replace`) and broadcast immediately as a synthetic `session_info_update` event.

**Request:** `{"title": "New Title"}` (1..120 chars, trimmed non-empty)  
**Response `200 OK`:** updated `SessionSnapshot` (or `{session_id, title, title_source: "gateway"}` for archived sessions).

### `PATCH /api/sessions/{session_id}/config`
Mutates live session configuration via ACP `session/set_config_option`. Supports updating one or more of `model` (base family ID, e.g. `gemini-4-neon`, `claude-sonnet-5-5`), `thought_level` (`low`, `medium`, `high`, `xhigh`), and `mode` (`default`, `accept-edits`, `auto`, `plan`).

**Request:**
```json
{
  "model": "gemini-3.8-flash",
  "thought_level": "medium",
  "mode": "accept-edits"
}
```
**Response `200 OK`:** updated `SessionSnapshot` (reflecting ACP's updated `configOptions`). Invalid values map ACP `-32602` to HTTP `422`.

### `DELETE /api/sessions/{session_id}`
Closes an active supervised session gracefully: sends ACP `session/close`, closes subprocess `stdin` (EOF, waiting up to `2.0s` for clean `rc=0` exit), then escalates `SIGTERM` (`2.0s`) -> `SIGKILL` if needed. Does NOT delete archived history on disk.

**Response `200 OK`:** `{"session_id": "...", "status": "closed"}`

### `POST /api/sessions/{session_id}/prompt`
Submits a user prompt to an active session (auto-resuming via `session/resume` only if `auto_resume: true` and `cwd` is known; otherwise `404` if not live).

**Request:**
```json
{
  "text": "Explain gateway/auth.py",
  "parts": [{"type": "text", "text": "Explain gateway/auth.py"}],
  "attachment_ids": ["att_01..."],
  "enqueue_if_busy": true
}
```
Either `text` or non-empty `parts` MUST be provided. Image/context attachments referenced by `attachment_ids` are resolved from the session's upload store and converted into ACP prompt content blocks (`image` / `resource`).

**Response `202 Accepted`:**
- When idle -> starts ACP `session/prompt`:
  `{"status": "started", "session_id": "...", "turn_id": "turn_..."}`
- When busy and `enqueue_if_busy == true` -> appends to FIFO queue:
  `{"status": "queued", "session_id": "...", "queue_item": {...}}`
- When busy and `enqueue_if_busy == false` -> `409 Conflict` (`error.code = "session_busy"`).

### `POST /api/sessions/{session_id}/cancel`
Sends ACP `session/cancel` JSON-RPC notification (no `id`) for the active turn and sets `queue_paused = true` so queued follow-up items do NOT auto-drain on `stopReason == "cancelled"`.

**Response `202 Accepted`:** `{"session_id": "...", "status": "cancelling"}`

---

## 4. Follow-Up Queue, Permissions, Questions, Files & Artifacts

### Follow-Up Queue Endpoints
- `GET /api/sessions/{session_id}/queue` -> `{"items": [QueueItem], "paused": false}`
- `POST /api/sessions/{session_id}/queue` -> body `{"text": "...", "attachment_ids": []}` -> `201 Created` (`QueueItem`)
- `PATCH /api/sessions/{session_id}/queue/{item_id}` -> body `{"text": "..."}` -> `200 OK` (`QueueItem`)
- `DELETE /api/sessions/{session_id}/queue/{item_id}` -> `200 OK` (`{"deleted": true, "item_id": "..."}`)
- `POST /api/sessions/{session_id}/queue/{item_id}/send-now` -> body `{"interrupt": true}` -> `202 Accepted`

**Queue Drain Semantics:**
1. FIFO order (`position = 0, 1, 2, ...`).
2. **Auto-drain on clean `end_turn` ONLY:** When an active ACP `session/prompt` turn completes with `stopReason == "end_turn"` and `queue_paused == false`, the supervisor pops `items[0]` and dispatches `session/prompt` automatically.
3. **No auto-drain on cancel/error:** On `stopReason == "cancelled"` or RPC/process error, remaining items stay in the queue (`paused: true`) until the user explicitly sends or resumes.
4. **`send-now`:** If idle, pops `item_id` and dispatches immediately; if busy and `interrupt == true` (default), moves `item_id` to head of queue, marks it `dispatch_on_settle = true`, sends ACP `session/cancel`, and dispatches that item once the active turn finishes settling (leaving any user composer draft untouched).

### Permission & Question Brokers

#### `POST /api/sessions/{session_id}/permissions/{request_id}`
Resolves a pending ACP `session/request_permission` JSON-RPC request.
```json
{
  "decision": "allow_once",
  "option_id": null
}
```
- `decision`: `"allow_once"` | `"allow_rule"` | `"deny"`
- `option_id`: optional explicit ACP `optionId` from the dynamic `options` list (takes precedence if valid).
- **Mapping to ACP `options` (R6):** Gateway matches against the dynamic `options` list emitted in `session/request_permission`:
  - `"allow_once"` -> option with `kind == "allow_once"` (e.g. `"allow-once"`).
  - `"allow_rule"` -> records a workspace-scoped rule `(workspace_root, tool_kind, command_prefix)` in `~/.config/gravity-well/permission_rules.json` AND replies with `kind == "allow_always"` if present (else `kind == "allow_once"`). Future matching requests in the same workspace are auto-resolved by the gateway while emitting an audit `permission_auto_approved` event.
  - `"deny"` -> option with `kind == "reject_once"` (or `reject_always` if offered; else ACP `{"outcome": {"outcome": "cancelled"}}`).
- **Response `200 OK`:** `{"request_id": "...", "resolved": true, "option_id": "allow-once", "rule_id": null}`

#### Workspace Permission Rules (Revocation Path)
- `GET /api/permissions/rules?workspace=<path>` -> lists active gateway workspace allow-rules.
- `DELETE /api/permissions/rules/{rule_id}` -> revokes a workspace allow-rule (`200 OK` `{"revoked": true}`).

#### `POST /api/sessions/{session_id}/questions/{request_id}`
Resolves a pending ACP `elicitation/create` JSON-RPC request (`ask_user_question`).
```json
{
  "action": "accept",
  "answers": {
    "q_0": "Blue"
  }
}
```
- `action`: `"accept"` | `"decline"` | `"cancel"`.
- **Key Preservation (R6):** Keys in `answers` MUST match `requestedSchema.properties` keys (`q_0`, `q_1`, ...). Gateway validates required keys when `action == "accept"` (returning `422` if missing) and forwards `{"action": action, "content": answers}` to ACP.
- **Response `200 OK`:** `{"request_id": "...", "resolved": true, "action": "accept"}`

### Attachments, Workspace `@file` Search & `brain/` Artifacts

#### `POST /api/sessions/{session_id}/attachments`
`multipart/form-data` with field `file`.
- Max size: `10 MiB` (`413` if larger).
- Magic-byte MIME verification (PNG, JPEG, WebP, GIF, PDF, UTF-8 text/code); rejects mismatched/binary executables (`415`).
- Stored inside `~/.config/gravity-well/uploads/{session_id}/{attachment_id}_{sanitized_name}`.
- **Response `201 Created`:** `{"attachment_id": "att_...", "filename": "screen.png", "mime_type": "image/png", "size_bytes": 12345, "sha256": "..."}`

#### `GET /api/sessions/{session_id}/files?q=<query>&limit=30`
Workspace `@file` autocomplete search across the session's `cwd` and `add_dirs`.
- Skips `.git`, `.venv`, `node_modules`, `build`, `__pycache__`.
- Enforces `os.path.realpath` containment inside allowed workspace roots (symlinks escaping the workspace are never traversed or returned).
- **Response `200 OK`:** `{"items": [{"path": "gateway/auth.py", "root": "/home/jason/projects/gravity-well", "size_bytes": 2410}]}`

#### `GET /api/sessions/{session_id}/artifacts`
Lists files under `~/.gemini/antigravity-cli/brain/{session_id}/` (validating `session_id` UUID/slug and symlink containment via `os.path.realpath`).
- **Response `200 OK`:** `{"session_id": "...", "items": [{"path": ".system_generated/logs/transcript.jsonl", "size_bytes": 4096, "mime_type": "application/x-ndjson", "modified_at": "..."}]}`

#### `GET /api/sessions/{session_id}/artifacts/{artifact_path:path}`
Serves a single file from `~/.gemini/antigravity-cli/brain/{session_id}/`.
- Rejects `..`, null bytes, or `realpath` escaping `brain/{session_id}/` with `403 Forbidden`.
- Sets `X-Content-Type-Options: nosniff`. Text/log artifacts (`.log`, `.txt`, `.jsonl`, `.json`) are passed through the secret redactor before returning (`?raw=1` only allowed for non-log binary images/PDFs; never disables redaction on logs).

### Event Replay (`HTTP`)
#### `GET /api/sessions/{session_id}/events?after=<cursor>&limit=500`
Returns buffered events for `{session_id}` with `event.cursor > after`.
```json
{
  "session_id": "...",
  "after": 10,
  "min_cursor_retained": 1,
  "latest_cursor": 24,
  "truncated": false,
  "events": [ ... ]
}
```
If `after > 0` and `after < (min_cursor_retained - 1)`, sets `"truncated": true` so clients know a ring-buffer gap occurred and should refresh `GET /api/sessions/{session_id}`.

---

## 5. WebSocket Protocol & Event Envelope

### Endpoints & Handshake Auth
- `WS /api/ws?after=<cursor>&ticket=<ticket>`: Multiplexed gateway stream (all sessions + observatory/presence).
- `WS /api/sessions/{session_id}/ws?after=<cursor>&ticket=<ticket>`: Single-session stream.
- **Auth:** Accepted ONLY via `Authorization: Bearer <token>` HTTP upgrade header OR single-use 30s `?ticket=<ticket>` from `POST /api/ws-ticket`. Passing permanent tokens via `?token=`, `?bearer=`, `?auth=`, or `?ticket=<permanent>` MUST fail upgrade with HTTP `401 Unauthorized`.

### Server-to-Client Frames
1. **`hello`** (emitted immediately upon WS connect — Leg 3 of host onboarding):
```json
{
  "frame": "hello",
  "gateway_version": "0.1.0",
  "agy_version": "1.5.15",
  "server_time": "2026-11-23T15:00:00Z",
  "latest_cursor": 42,
  "capabilities": {"fork": false, "acp_rename": false, "gateway_title_override": true}
}
```
2. **`event`** (live or replayed from ring buffer; `cursor` is strictly monotonic globally, `seq` is strictly monotonic per session):
```json
{
  "frame": "event",
  "cursor": 43,
  "seq": 15,
  "session_id": "5b1f0b18-...",
  "type": "agent_message_chunk",
  "ts": "2026-11-23T15:00:01Z",
  "payload": {"text": "Hello"}
}
```
3. **`replay_complete`**: `{"frame": "replay_complete", "after": 10, "replayed_count": 32, "latest_cursor": 42}`
4. **`resync_required`** (when requested `after` is older than retained ring-buffer minimum):
   `{"frame": "resync_required", "after": 2, "min_cursor_retained": 100, "latest_cursor": 250}`
5. **`pong`**: `{"frame": "pong", "ts": "..."}`
6. **`error`**: `{"frame": "error", "code": "...", "message": "..."}`

### Client-to-Server Frames
- `{"frame": "hello", "client": "gravity-well-android", "version": "0.1.0"}` (optional)
- `{"frame": "ping"}` -> server replies `pong`
- `{"frame": "subscribe", "session_ids": ["..."]}` (optional filter on `/api/ws`; omit for all)

### Canonical `event.type` Catalog
| `type` | Source | `payload` keys |
|---|---|---|
| `session_started` | Supervisor | `snapshot` |
| `session_status` | Supervisor | `status`, `reason?` |
| `session_info_update` | ACP / Gateway `PATCH` | `title`, `title_source` (`"acp" \| "gateway"`) |
| `user_message_chunk` | ACP `session/load` or Prompt submit | `text`, `parts?` |
| `agent_thought_chunk` | ACP `session/update` | `text` |
| `agent_message_chunk` | ACP `session/update` | `text` |
| `tool_call` | ACP `session/update` | `tool_call_id`, `title`, `kind`, `status`, `raw_input` |
| `tool_call_update` | ACP `session/update` | `tool_call_id`, `status`, `title`, `kind`, `raw_output`, `diff?` |
| `usage_update` | ACP `session/update` | `used`, `size` |
| `config_option_update` | ACP `session/update` | `config_options` |
| `available_commands_update` | ACP `session/update` | `commands` |
| `permission_requested` | ACP `session/request_permission` | `request_id`, `tool_call`, `options` |
| `permission_resolved` | Broker / Auto-rule | `request_id`, `decision`, `option_id`, `auto_approved` |
| `question_requested` | ACP `elicitation/create` | `request_id`, `mode`, `message`, `requested_schema` |
| `question_resolved` | Broker | `request_id`, `action`, `answers` |
| `queue_updated` | Queue Manager | `items`, `paused` |
| `turn_completed` | ACP `session/prompt` result | `turn_id`, `stop_reason`, `usage` |
| `session_closed` | Supervisor | `exit_code`, `reason` |
| `presence_update` | Observatory (on `/api/ws`) | `external_sessions`, `active_count` |

---

## 6. Storage, Observatory & Ecosystem Endpoints

All routes in this section require `Authorization: Bearer <token>`.

### Storage (`?mode=ro` SQLite FTS5)
- `GET /api/storage/conversations?q=<fts_query>&cwd=<path>&limit=50&offset=0`
  - Opens `~/.gemini/antigravity-cli/conversation_summaries.db` strictly read-only (`file:...?mode=ro`, `uri=True`).
  - Queries `conversations_fts` (`title`, `workspace`, `project`, `user_body`, `agent_body`) joined with `conversation_summaries` and merged with gateway custom title overrides.
  - **Response `200 OK`:** `{"items": [{"conversation_id", "title", "preview", "snippet", "workspace_paths", "step_count", "last_modified_time", "archived"}], "total", "limit", "offset"}`

### Observatory
- `GET /api/observatory/usage?refresh=false`
  - Executes `agy -p /usage --output-format json` (TTL-cached `60s` in memory; `refresh=true` bypasses cache).
  - Parses `command.data.groups` directly (R8).
  - **Response `200 OK`:**
    ```json
    {
      "status": "SUCCESS",
      "cached_at": "2026-11-23T15:00:00Z",
      "stale": false,
      "groups": [
        {
          "name": "Gemini Models",
          "description": "...",
          "buckets": [
            {
              "id": "gemini-weekly",
              "name": "Weekly Limit Remaining",
              "window": "weekly",
              "remaining_fraction": 0.8908,
              "remaining_percent": 89.1,
              "reset_time": "2026-11-28T15:37:52Z"
            }
          ]
        }
      ]
    }
    ```
- `GET /api/observatory/status`
  - **Host vitals:** `cpu_percent`, `load_avg_1m`, `load_avg_5m`, `ram_used_mb`, `ram_total_mb`, `disk_used_gb`, `disk_total_gb`.
  - **External session radar (`presence/`):** Scans `~/.gemini/antigravity-cli/presence/*.lock` ONLY as candidate UUIDs and proves liveness via `/proc/locks` inode match + non-blocking `fcntl.flock(..., LOCK_EX | LOCK_NB)` probe (with immediate `LOCK_UN`, never counting stale `.lock` files alone, per R9). Cross-references `conversation_summaries.db` (`?mode=ro`) for `title` and `cwd`, and marks `supervised: true|false`.
  - **Remote-control daemon:** Parses `agy remote-control status` into `{"active": bool, "status_text": "...", "instance_name": "..."}`.
  - **Updater:** Reads `~/.gemini/antigravity-cli/updater/update_status.json` (`{"success", "message"}`) + current `agy --version`.
- `GET /api/observatory/logs?source=cli&tail=200&q=`
  - `source=cli`: resolves `~/.gemini/antigravity-cli/cli.log` inside `~/.gemini/antigravity-cli/log/` (symlink-contained), returns last `tail` (`1..2000`) lines matching optional `q`.
  - `source=crashes`: lists files under `~/.gemini/antigravity-cli/crashes/` and tails newest or `?file=<name>` (contained inside `crashes/`).
  - **Mandatory Secret Redaction:** Every returned line passes through `redaction.redact_text()` (strips Bearer tokens, `AIza*`, `ya29.*`, `sk-*`, `*_KEY/*_TOKEN/*_SECRET=*`, `client_secret`, `refresh_token`, Authorization headers).
- `GET /api/observatory/remote-control`: returns daemon status.
- `POST /api/observatory/remote-control/start`: body `{"name"?: str, "session_scoped": bool = false}` -> runs `agy remote-control start [--name <name>] [--session]`.
- `POST /api/observatory/remote-control/stop`: runs `agy remote-control stop`.
- `POST /api/observatory/update`: **Confirm-gated** — requires body `{"confirm": true}` (missing/false returns `400`). Runs `agy update`, returns `{"updated": bool, "version_before": "...", "version_after": "...", "output": "<redacted>"}`.

### Ecosystem & Allowlisted Settings
- `GET /api/ecosystem/models`: runs `agy models`, returns `{"default_model_id": "...", "items": [{"model_id", "display_name", "family", "thought_level", "is_default"}], "gateway_default_model": "..."}`.
- `PUT /api/ecosystem/models/default`: sets gateway's default model/thought_level for new sessions (`{"model": "gemini-4-neon", "thought_level": "high"}`) persisted in `~/.config/gravity-well/gateway_prefs.json`.
- `GET /api/ecosystem/agents`: merges `agy agent` output with frontmatter metadata from `~/.gemini/agents/*.md` and `~/.gemini/antigravity-cli/agents/*.md`.
- `POST /api/ecosystem/agents`: creates/updates a custom agent markdown file in `~/.gemini/antigravity-cli/agents/{name}.md` (`{"name": "code-auditor", "description": "...", "prompt": "..."}`, slug `[a-z0-9][a-z0-9_-]{0,63}`, atomic write).
- `DELETE /api/ecosystem/agents/{name}`: removes user-created agent `.md` in `~/.gemini/antigravity-cli/agents/{name}.md` (`404` if missing).
- `GET /api/ecosystem/mcp`: parses `agy mcp list` + `~/.gemini/config/mcp_config.json` (`disabled`, `type`, `command`/`serverUrl`, with `env`/`headers` **keys** listed and **values masked** as `"[REDACTED]"`).
- `POST /api/ecosystem/mcp`: `{"name", "type": "stdio"|"http", "target", "args": [], "env": {}, "headers": {}}` -> invokes `agy mcp add` (flags strictly before `<name>`, per R7).
- `POST /api/ecosystem/mcp/{name}/enable` / `POST /api/ecosystem/mcp/{name}/disable`: invokes `agy mcp enable|disable <name>`.
- `DELETE /api/ecosystem/mcp/{name}`: invokes `agy mcp remove <name>`.
- `GET /api/ecosystem/plugins`: invokes `agy plugin list --json` and merges builtin plugin status from `~/.gemini/antigravity-cli/builtin/plugins/`.
- `POST /api/ecosystem/plugins/validate`: `{"target": "...", "strict": false}` -> invokes `agy plugin validate [--strict] --json <target>`.
- `POST /api/ecosystem/plugins/install`: `{"target": "..."}` -> invokes `agy plugin install <target>` (validating non-empty target).
- `POST /api/ecosystem/plugins/{name}/enable` / `POST /api/ecosystem/plugins/{name}/disable` / `DELETE /api/ecosystem/plugins/{name}` -> invokes `agy plugin enable|disable|uninstall <name>`.
- `GET /api/ecosystem/settings`: returns ONLY allowlisted keys from `~/.gemini/settings.json` plus gateway preferences:
  - Allowlist: `general.previewFeatures` (`bool`), `general.enablePromptCompletion` (`bool`).
  - Forbidden (never read/returned/mutated): `security.*`, `oauth*`, `auth*`, or any unknown top-level key.
- `PATCH /api/ecosystem/settings`: validates keys strictly against allowlist (unknown/disallowed keys return `403 Forbidden`), updates `~/.gemini/settings.json` atomically (`tempfile` + `fsync` + `os.replace`) preserving non-allowlisted fields untouched on disk.




