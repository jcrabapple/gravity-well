<!--
  Authority: Empirical Baseline
  Deliverable: Gravity Well
-->
# Gravity Well — Empirical Antigravity CLI & ACP Research

## Methodology & Guardrails

All measurements were collected directly against `/home/jason/.local/bin/agy` (`1.5.15`) on Aurora Linux (`2026-11-23`):
- Stateful probes (`agy acp --stdio`, `agy -p /usage --output-format json`, `agy mcp`) ran inside fresh `$TMPDIR` `HOME` sandboxes with `ANTIGRAVITY_ENV_STRICT=1` and a minimal allowlisted environment (`PATH`, `HOME`, `USER`, `LANG`, `LC_ALL`, `TERM`, `ANTIGRAVITY_ENV_STRICT`).
- Live state under `~/.gemini/antigravity-cli/` and `~/.gemini/config/` was inspected strictly read-only (`file:...?mode=ro` URI for SQLite).
- Credential files (`~/.gemini/oauth_creds.json`, `jetbox_summaries_proto.pb`, `jetski_state.pbtxt`) were not read or printed (`[REDACTED]`).

---

## R1. Binary, Version & Global Flags

- Binary path: `/home/jason/.local/bin/agy`
- `agy --version`: `1.5.15`
- Subcommands: `acp`, `agent` (alias `agents`), `changelog`, `help`, `install`, `mcp`, `mic-serve`, `models`, `plugin` (alias `plugins`), `remote-control`, `update`.
- Relevant `agy acp` flags: `--stdio` (default), `--add-dir`, `--agent`, `--app-data-dir` (default `antigravity-cli`), `--dangerously-skip-permissions`, `--disable-telemetry`, `--gemini-dir` (default `.gemini`), `--ide-mcp-port`, `--include-default-customizations` (default `true`), `--log-file`, `--process-per-session`, `--sandbox`.

---

## R2. ACP `initialize` Handshake & Capabilities

Request (`stdio`, newline-delimited JSON-RPC 2.0):
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {
    "protocolVersion": 1,
    "clientCapabilities": {
      "fs": {"readTextFile": true, "writeTextFile": true},
      "terminal": true,
      "elicitation": {"form": {}, "url": {}}
    },
    "clientInfo": {"name": "gw-probe", "version": "0.1.0"}
  }
}
```

Exact response:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "protocolVersion": 1,
    "agentCapabilities": {
      "loadSession": true,
      "load_session": true,
      "promptCapabilities": {"image": true, "audio": false, "embeddedContext": true},
      "sessionCapabilities": {
        "resume": {},
        "close": {},
        "load": {},
        "list": {},
        "additionalDirectories": {}
      },
      "session_capabilities": {
        "resume": {},
        "close": {},
        "load": {},
        "list": {},
        "additionalDirectories": {}
      },
      "auth": {"logout": {}},
      "_meta": {"antigravity": {"tier": true, "systemPrompt": {"append": true}}}
    },
    "agentInfo": {"name": "Antigravity ACP Server", "version": "1.5.15"},
    "authMethods": [
      {"id": "oauth-personal", "name": "Log in with Google", "description": "Log in with your Google account"},
      {"id": "oauth-business", "name": "Log in with Gemini Enterprise", "description": "Log in with your Gemini Enterprise account"},
      {"id": "gemini-api-key", "name": "Gemini API key", "description": "Use an API key with Gemini Developer API"},
      {"id": "agent-platform", "name": "Gemini Enterprise Agent Platform", "description": "Use Gemini Enterprise Agent Platform (formerly Vertex AI) with Application Default Credentials"}
    ]
  }
}
```

---

## R3. ACP Session Lifecycle & Method Support

### `session/new`
Request: `{"jsonrpc":"2.0","id":2,"method":"session/new","params":{"cwd":"<abs>","mcpServers":[]}}`
Response `result` contains:
- `sessionId` (UUID string)
- `models`: `{ "availableModels": [23 items: {modelId, name}], "currentModelId": "gemini-4-neon-high" }`
- `modes`: `{ "currentModeId": "default", "availableModes": [default, accept-edits, auto, plan] }`
- `configOptions`: array of 3 select controls (`id`: `model`, `thought_level`, `mode`)
- `_meta.antigravity.tier`: `{"name": "Google AI Pro", "id": "g1-pro-tier", "upgradeUri": "https://antigravity.google/g1-upgrade"}`

Immediately after `session/new`, the server emits two `session/update` notifications:
1. `available_commands_update` (65 commands/skills)
2. `usage_update` (`{"sessionUpdate": "usage_update", "used": 0, "size": 1048576}`)

### `session/list`
- Request: `{"jsonrpc":"2.0","id":4,"method":"session/list","params":{}}` (optional `cwd` filter accepted).
- Before any prompt is sent, `sessions` is `[]`. After at least one `session/prompt`, returns:
  `{"sessions": [{"sessionId": "<uuid>", "cwd": "<path>", "title": "New Conversation", "updatedAt": "2026-11-23T14:37:52Z"}]}`
- Works across cold process restarts when sharing the same `HOME`.

### `session/close`, `session/resume`, `session/load`
- `session/close`: `{"sessionId": "<uuid>"}` -> `{"jsonrpc":"2.0","result":null,"id":9}`.
- `session/resume`: `{"sessionId": "<uuid>", "cwd": "<path>"}` -> returns full `{sessionId, models, modes, configOptions, _meta}` without replaying history. Confirmed working both in-process after `session/close` and cold from a fresh `agy acp --stdio` process.
- `session/load`: `{"sessionId": "<uuid>", "cwd": "<path>", "mcpServers": []}` -> replays all historical turns as `session/update` notifications (`user_message_chunk`, `agent_thought_chunk`, `agent_message_chunk`, `tool_call`, `tool_call_update`, `available_commands_update`, `usage_update`), then responds with `{models, modes, configOptions, _meta}` (no `sessionId` field in `session/load` result).
- Missing session error: `{"code": -32002, "message": "Resource not found", "data": {"details": "conversation not found"}}`.

### Process Teardown
- Closing `stdin` (EOF) causes `agy acp --stdio` to exit cleanly with `returncode=0` in `~0.01s` with zero lingering child processes.

### Unsupported Methods (`-32601 Method not found` in `agy 1.5.15`)
- `session/fork` -> `-32601` (`"Method not found"`)
- `session/rename` -> `-32601` (`"Method not found"`)
- `session/set_title` -> `-32601` (`"Method not found"`)
- `session/delete` -> `-32601` (`"Method not found"`)
- `session/set_additional_directories` -> `-32601` (`"Method not found"`)

---

## R4. ACP Config Options, Modes & Models

- `session/set_config_option`: `{"sessionId": "<uuid>", "configId": "model|thought_level|mode", "value": "..."}`
  - `model` values are base families (`gemini-4-neon`, `gemini-4-argon`, `gemini-4-hydrogen`, `gemini-pro-orchestrator-neon`, `gemini-pro-orchestrator-g1-pro`, `gemini-3.8-flash`, `claude-opus-5-5`, `claude-sonnet-5-5`, `gpt-oss-120b-medium`), NOT the suffixed `availableModels` IDs.
  - `thought_level` values: `low`, `medium`, `high`, `xhigh`.
  - `mode` values: `default`, `accept-edits`, `auto`, `plan`.
  - Returns `{"configOptions": [...]}` AND emits `session/update` (`config_option_update`) + `available_commands_update`.
  - Unknown `configId` returns `-32602` (`"Invalid params"`, `details: "unknown config option: ..."`).
- Legacy setters also exist and return `result: null`:
  - `session/set_mode`: `{"sessionId": ..., "modeId": "plan"}` -> `result: null`.
  - `session/set_model`: `{"sessionId": ..., "modelId": "gemini-3.8-flash-low"}` -> `result: null`.

---

## R5. ACP Prompting, Streaming Updates & Cancellation

### `session/prompt`
Request:
```json
{
  "jsonrpc": "2.0",
  "id": 5,
  "method": "session/prompt",
  "params": {
    "sessionId": "<uuid>",
    "prompt": [{"type": "text", "text": "Reply with only the word OK."}]
  }
}
```
Response:
```json
{
  "jsonrpc": "2.0",
  "id": 5,
  "result": {
    "stopReason": "end_turn",
    "usage": {
      "inputTokens": 8698,
      "outputTokens": 11,
      "thoughtTokens": 12,
      "cachedReadTokens": 0,
      "totalTokens": 8709
    }
  }
}
```

### Observed `session/update` Payloads
Every streaming update arrives as notification `method: "session/update"` with `params: {"sessionId": "...", "update": {"sessionUpdate": "<kind>", ...}}`:
- `agent_thought_chunk`: `{"sessionUpdate": "agent_thought_chunk", "content": {"type": "text", "text": "..."}}`
- `agent_message_chunk`: `{"sessionUpdate": "agent_message_chunk", "content": {"type": "text", "text": "..."}}`
- `user_message_chunk` (during `session/load` replay): `{"sessionUpdate": "user_message_chunk", "content": {"type": "text", "text": "..."}}`
- `tool_call`: `{"sessionUpdate": "tool_call", "toolCallId": "call_...", "title": "Running command: ...", "kind": "execute|other|...", "status": "pending", "rawInput": {...}}`
- `tool_call_update`: `{"sessionUpdate": "tool_call_update", "toolCallId": "call_...", "status": "in_progress|completed", "title": "...", "kind": "...", "rawOutput": {...}}`
- `usage_update`: `{"sessionUpdate": "usage_update", "used": 8709, "size": 1048576}`
- `session_info_update`: `{"sessionUpdate": "session_info_update", "title": "Reply With OK"}`
- `available_commands_update`: `{"sessionUpdate": "available_commands_update", "availableCommands": [{"name": "...", "description": "..."}]}`
- `config_option_update`: `{"sessionUpdate": "config_option_update", "configOptions": [...]}`

### `session/cancel`
- Sent as a JSON-RPC **notification** (no `id`): `{"jsonrpc": "2.0", "method": "session/cancel", "params": {"sessionId": "<uuid>"}}`
- Causes the in-flight `session/prompt` request to resolve with `"stopReason": "cancelled"` and accumulated `usage`.

### Slash Commands in ACP
- Sending `"/usage"` inside `session/prompt` does **not** invoke the CLI `/usage` handler; it is passed directly to the model as plain user text. Quota metrics must be queried via `agy -p /usage --output-format json`.

---

## R6. ACP Permission & Elicitation Broker Payloads

### `session/request_permission` (Server -> Client JSON-RPC Request)
Triggered in `default` mode before mutating tool execution (e.g., `run_command`):
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "session/request_permission",
  "params": {
    "sessionId": "<uuid>",
    "toolCall": {
      "toolCallId": "call_ad8fb5e1fc6c4ee8b34b035b0db0c237",
      "title": "Running command: `echo hello > probe.txt`",
      "kind": "execute",
      "status": "pending",
      "rawInput": {"CommandLine": "echo hello > probe.txt", "Cwd": "<path>", "WaitMsBeforeAsync": 5000}
    },
    "options": [
      {"optionId": "allow-once", "name": "Allow once", "kind": "allow_once"},
      {"optionId": "allow-always", "name": "Allow for this session", "kind": "allow_always"},
      {"optionId": "reject-once", "name": "Reject", "kind": "reject_once"}
    ]
  }
}
```
Client response:
```json
{"jsonrpc": "2.0", "id": 1, "result": {"outcome": {"outcome": "selected", "optionId": "allow-once"}}}
```
Note: `agy 1.5.15` offered 3 options (`allow-once`, `allow-always`, `reject-once`); gateway should render and validate against the dynamic `options` array rather than hardcoding `reject-always`.

### `elicitation/create` (Server -> Client JSON-RPC Request)
Triggered when the model calls `ask_user_question`:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "elicitation/create",
  "params": {
    "sessionId": "<uuid>",
    "mode": "form",
    "message": "Which color do you prefer?",
    "requestedSchema": {
      "type": "object",
      "properties": {
        "q_0": {
          "type": "string",
          "title": "Which color do you prefer?",
          "description": "Red: Option Red; Blue: Option Blue",
          "enum": ["Red", "Blue"],
          "enumNames": ["Red", "Blue"]
        }
      },
      "required": ["q_0"]
    }
  }
}
```
Client response (keys inside `content` MUST match `requestedSchema.properties` keys such as `q_0`):
```json
{"jsonrpc": "2.0", "id": 1, "result": {"action": "accept", "content": {"q_0": "Blue"}}}
```
Confirmed `tool_call_update` `rawOutput`: `{"answers": {"0": "Blue"}}`. Sending mismatched keys (e.g., `{"choice": "blue"}`) silently produces `{"answers": {}}`.

---

## R7. CLI Subcommands & Schemas

- `agy models` (`rc=0`): tabular plain text listing 23 models (`<id>  <Display Name> [(default)]`). Default: `gemini-4-neon-high`.
- `agy agents` (`rc=0`): empty stdout when no custom agents exist.
- `agy mcp list` (`rc=0`): `No MCP servers configured.` when empty; otherwise `NAME  TYPE  STATUS  COMMAND/URL` table.
  - `agy mcp add [--type stdio|http] [-e KEY=val] [-H "K: V"] <name> <commandOrUrl> [args...]` writes `~/.gemini/config/mcp_config.json` (`{"mcpServers": {<name>: {"disabled": false, "headers": {...}, "serverUrl": "..."}}}` for http; `command`/`args`/`env` for stdio). Flags MUST precede `<name>`.
  - `agy mcp enable|disable|remove <name>` mutates `mcp_config.json` cleanly (`rc=0`).
- `agy plugin list --json` (`rc=0`): JSON array `[]` when no user plugins installed (builtin `swarm` plugin lives in `~/.gemini/antigravity-cli/builtin/plugins/swarm/`).
  - `agy plugin validate [--strict] [--json] <target>` accepts directory, `plugin.json`, `.zip`, URL, or `marketplace.json`.
  - `agy plugin install --help` exits `2` with `Error: install target must be a directory: --help` (no `--help` handler on `install`).
- `agy remote-control status` (`rc=0`):
  ```text
  Daemon status: inactive
  Instance name: (not assigned)
  Logs: journalctl --user -u antigravity-cli-daemon.service -n 50
  ```
  `agy remote-control start [--name <name>] [--session]` and `stop`. `antigravity-cli-daemon.service` is currently not installed.
- `agy update --help` (`rc=2`): prints only `Usage of update:` on stderr (no documented flags). `agy changelog` (`rc=0`) outputs markdown changelog starting at `## [1.5.15] - 2026-11-20`.

---

## R8. Usage Quota Output (`agy -p /usage --output-format json`)

Command: `/home/jason/.local/bin/agy -p /usage --output-format json` (`rc=0`). Exact top-level JSON keys:
```json
{
  "conversation_id": "",
  "status": "SUCCESS",
  "response": "Gemini Models\tWeekly Limit Remaining\t89%\t2026-11-28T15:37:52Z\n...",
  "duration_seconds": 0,
  "num_turns": 0,
  "usage": {"input_tokens": 0, "output_tokens": 0, "thinking_tokens": 0, "cache_read_tokens": 0, "total_tokens": 0},
  "command": {
    "name": "usage",
    "data": {
      "description": "Within each group, models share a weekly limit...",
      "groups": [
        {
          "name": "Gemini Models",
          "description": "Models within this group: Gemini 4 Neon, Gemini 4 Hydrogen, Orchestrator (Neon), Gemini 3.8 Flash",
          "buckets": [
            {
              "id": "gemini-weekly",
              "name": "Weekly Limit Remaining",
              "description": "...",
              "window": "weekly",
              "remaining_fraction": 0.8908720016479492,
              "reset_time": "2026-11-28T15:37:52Z"
            },
            {
              "id": "gemini-5h",
              "name": "Five Hour Limit Remaining",
              "window": "5h",
              "remaining_fraction": 0.6945812106132507,
              "reset_time": "2026-11-23T15:50:12Z"
            }
          ]
        },
        {
          "name": "Claude and GPT models",
          "buckets": [
            {"id": "3p-weekly", "window": "weekly", "remaining_fraction": 1, "reset_time": "2026-11-30T14:32:48Z"},
            {"id": "3p-5h", "window": "5h", "remaining_fraction": 1, "reset_time": "2026-11-23T19:32:48Z"}
          ]
        },
        {
          "name": "Gemini 4 Argon",
          "buckets": [
            {"id": "gemini-argon-wtu-weekly", "window": "weekly", "remaining_fraction": 1, "reset_time": "2026-11-30T14:32:48Z"}
          ]
        }
      ]
    }
  }
}
```
Gateway Observatory (`GET /api/observatory/usage`) should parse `command.data.groups` directly rather than scraping the TSV `response` string.

---

## R9. Read-Only Filesystem & SQLite State (`~/.gemini/`)

### `~/.gemini/antigravity-cli/conversation_summaries.db` (`?mode=ro`)
- `search_schema_version = 3` (WAL mode enabled).
- `conversation_summaries` (785 rows): `conversation_id`, `title`, `preview`, `step_count`, `last_modified_time`, `workspace_uris` (JSON string array of `file://` URIs), `status` (`2` or `3`), `source` (`1`, `2`, `17`), `project_id`, `agent_name`, `parent_conversation_id`, `nesting_depth`, `battle_id`, `winning_conversation_id`, `not_fully_idle`, `killed`, `last_user_input_time` (sentinel `0001-01-01 00:00:00+00:00` when unset), `last_user_input_step_index`, `app_data_dir` (`''`, `'antigravity'`, `'antigravity-cli'`), `raw_summary` (BLOB, often NULL), `group_id`.
- `conversations_fts` (1,149 FTS5 chunks, tokenizer `unicode61 remove_diacritics 2`, prefix `1 2 3`):
  - `UNINDEXED`: `conversation_id`, `chunk_index`, `chunk_first_step`, `chunk_last_step`, `last_modified_time`, `step_count`, `last_step_created_at`, `archived`, `user_step_byte_offsets`, `agent_step_byte_offsets`
  - Indexed: `title`, `workspace`, `project`, `user_body`, `agent_body`
- `conversation_parents` (0 rows).

### `~/.gemini/antigravity-cli/conversations/<uuid>.db` (`?mode=ro`)
- 500 per-session SQLite DBs. Tables: `trajectory_meta` (`trajectory_id`, `cascade_id`, `trajectory_type`, `source`), `steps` (`idx`, `step_type`, `status`, `has_subtrajectory`, `metadata` BLOB, `error_details`, `permissions`, `task_details`, `render_info`, `step_payload` BLOB, `step_format`), `gen_metadata`, `executor_metadata`, `parent_references`, `trajectory_metadata_blob`, `battle_mode_infos`.
- Note: `step_payload` and `metadata` are binary protobuf blobs; prefer ACP `session/load` for lossless turn replay rather than decoding raw protobufs.

### Directories & Config Files
- `brain/<uuid>/`: 500 dirs containing `.system_generated/{logs/transcript*.jsonl,steps/*/output.txt,tasks/*.log,messages/*.json}`, `.user_uploaded/`, and `scratch/`.
- `presence/`: 710 zero-byte `<uuid>.lock` files (`0` bytes each).
- `log/` & `cli.log`: 821 `cli-YYYYMMDD_HHMMSS.log` files; `cli.log` is a symlink to the latest log.
- `crashes/`: currently empty directory.
- `updater/`: `update_status.json` (`{"success":true,"message":"Already on the latest version."}`) and `update.lock` (`0` bytes).
- `cache/`: `onboarding.json`, `default_project_id.txt` (`default-cli-project`), `last_conversations.json` (105 cwd->uuid mappings), `conversation_metadata.json`, `CHANGELOG.md`.
- `~/.gemini/config/mcp_config.json`: `0` bytes when unconfigured; populated by `agy mcp add`.
- `~/.gemini/settings.json`: `{ "security": {"auth": {"selectedType": "oauth-personal"}}, "general": {"previewFeatures": true, "enablePromptCompletion": true} }`.
- Sensitive files (`[REDACTED]`, never read/printed by gateway): `~/.gemini/oauth_creds.json`, `~/.gemini/config/config.json`, `jetbox_summaries_proto.pb`, `jetski_state.pbtxt`.

---

## R10. Implementation Constraints for `w1-gateway-core` & `w1-gateway-ops`

1. **Unsupported ACP Methods (`-32601`)**: `agy 1.5.15` does NOT implement `session/fork`, `session/rename`, `session/set_title`, `session/delete`, or `session/set_additional_directories`. Gateway must handle `-32601` gracefully (e.g., return `501` / structured error for `POST /api/sessions/{id}/fork`, store/overlay custom session titles in gateway metadata if `PATCH` rename is supported, and pass extra directories via `agy acp --add-dir` at spawn or `session/new` params if supported).
2. **Config Option Value Format**: `session/set_config_option` uses base model family IDs (`gemini-3.8-flash`) + separate `thought_level` (`low|medium|high|xhigh`), whereas `availableModels` / `agy models` use suffixed IDs (`gemini-3.8-flash-high`). Gateway should expose `configOptions` directly for picker controls.
3. **Dynamic Permission & Elicitation Schemas**: `session/request_permission` emits 3 options (`allow-once`, `allow-always`, `reject-once`); `elicitation/create` requires response keys matching `requestedSchema.properties` (such as `q_0`).
4. **Quota & Diagnostics**: `/usage` is not intercepted in ACP `session/prompt`; `w1-gateway-ops` must invoke `agy -p /usage --output-format json` and parse `command.data.groups`. `agy update` has no documented `--help` flags (`rc=2`) and should only run confirm-gated (`{"confirm": true}`).
5. **Clean Subprocess Shutdown**: Closing `stdin` on `agy acp --stdio` causes clean `rc=0` exit in `~10ms`; gateway should close stdin first with a short wait before `SIGTERM`/`SIGKILL`.
