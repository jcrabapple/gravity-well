# Gravity Well Implementation Plan

> **For Hermes Orchestrator & Workers:** This document is the authoritative decomposition contract for board `gravity-well` in shared workspace `/home/jason/projects/gravity-well`.

**Goal:** Build Gravity Well, a remote control and telemetry system for a Google Antigravity (`agy`) host consisting of a self-hosted Python FastAPI/Uvicorn ACP gateway (`gateway/`, port `9340`) and a native Kotlin + Jetpack Compose Android client (`android/`, `us.snakepit.gravitywell`).

**Architecture:** One supervised `agy acp --stdio` child process per active session in its workspace directory, paired with documented `agy` CLI subcommands (`models`, `agents`, `mcp`, `plugin`, `remote-control`, `update`, `-p /usage --output-format json`) and read-only (`?mode=ro`) reads of `~/.gemini/antigravity-cli/`. The Android app connects over HTTPS/WSS (Tailscale/tunnel), stores multi-host bearer tokens in `AndroidKeyStore` (`AES/GCM/NoPadding`), maintains a `SQLiteOpenHelper` (`WAL`) offline cache, streams live ACP sessions with cursor replay, handles inline permissions/questions/follow-up queues, renders Observatory telemetry and Control Deck ecosystem management, and runs a plain-Kotlin `dataSync` foreground service with notification quick-actions.

**Authority Order (when docs conflict):** `docs/SPEC.md` + `docs/API.md` > `docs/creative/DIRECTION-gravity-well.md` (for UI/UX shape) > `docs/PLAN.md` > `docs/RESEARCH.md`.

---

## Verified Host Baseline (2026-11-23)

- **Repo root:** `/home/jason/projects/gravity-well` (branch `main`, clean Gradle `9.8.2` wrapper in `android/`)
- **Board:** `gravity-well` (`~/.hermes/kanban/boards/gravity-well/kanban.db`)
- **Antigravity CLI:** `/home/jason/.local/bin/agy` (`1.5.15`), CLI state `~/.gemini/antigravity-cli/` (distinct from desktop IDE `~/.gemini/antigravity/` — do NOT confuse them)
- **Python / uv:** `/home/jason/.local/bin/uv`, `/usr/bin/python3` (`3.15.2`), always invoke `env -u PYTHONPATH` around `uv` / `pytest`
- **Java / Android SDK:** `JAVA_HOME=/home/jason/.jdks/ms-21.0.9` (or `/home/jason/.local/share/jbr`), `ANDROID_HOME=/home/jason/Android/Sdk` (`platforms/android-36`, `build-tools/36.1.0`), `AGP = 9.4.1`, `Kotlin = 2.4.21` (sibling baseline: `/home/jason/projects/home-radio-android/gradle/libs.versions.toml`)
- **Ports:** `9340` (gateway production `0.0.0.0:9340`), `9341` (loopback E2E test bench `127.0.0.1:9341`) — both verified free

---

## Global Hard Guardrails (Binding on Every Card)

1. **Sanctioned Antigravity Posture:** Only invoke `/home/jason/.local/bin/agy` via `agy acp --stdio` and documented CLI subcommands, plus `?mode=ro` SQLite/file reads under `~/.gemini/antigravity-cli/` and `~/.gemini/config/mcp_config.json`. Never read, print, copy, or proxy OAuth tokens, keyrings, `~/.hermes/`, or direct `cloudcode-pa` HTTP endpoints.
2. **Child Environment Scrub:** Gateway subprocess spawns MUST pass an explicit minimal allowlisted environment (`PATH`, `HOME`, `USER`, `LANG`, `LC_ALL`, `TERM`, `DBUS_SESSION_BUS_ADDRESS`, `XDG_RUNTIME_DIR`, `ANTIGRAVITY_ENV_STRICT=1`) and MUST strip all host secrets (`*_KEY`, `*_TOKEN`, `*_SECRET`, `PYTHONPATH`, etc.).
3. **Hermetic Mutating Tests:** All mutating gateway/CLI tests MUST run against a temporary `HOME` under `$TMPDIR` and deterministic `FakeAgy` shims. Never mutate real `~/.gemini/`, `~/.config/gravity-well/`, or live `systemd` services during tests or reviews.
4. **No Physical Device Commands:** Never run mutating `adb`, `fastboot`, or `scrcpy` commands (`shell`, `install`, `push`, `pull`, `input`, etc.). Build and verify via JVM unit tests, `:app:lintDebug`, `:app:assembleDebug`, `:app:assembleRelease`, and `aapt2`.
5. **Aurora Gradle Memory Guard:** Before every `./gradlew` run, check `free -h`. Run single-module or bounded tasks with `--no-daemon --max-workers=1 -Dorg.gradle.parallel=false`, and run `./gradlew --stop` afterward.
6. **Clean Python Runs:** Always prefix `uv` / `.venv/bin/pytest` runs with `env -u PYTHONPATH`.
7. **REDCHECK Mandate:** Every code-implementing card (`coder`) must perform at least 2 explicit red-checks (temporarily mutate production logic so a key unit/widget/integration test fails, verify the test fails, restore, re-verify green, grep `REDCHECK` empty) and record the exact mutation + failing test output in completion metadata.
8. **Kanban Tooling & CLI Fallback:** Workers with `kanban_*` tools use them directly. If a worker lacks `kanban_*` tools in its runtime, shelling out to `hermes kanban --board gravity-well complete|block <id> ...` is **EXPLICITLY PERMITTED**.

---

## Kanban Wave Graph & Card Specifications

**Dispatch Discipline:** Create every card with `workspace_kind="dir"`, `workspace_path="/home/jason/projects/gravity-well"`, explicit `idempotency_key`, and `initial_status="blocked"` for all non-root cards so no child races before `parents=[...]` links land. After creating all 15 cards and verifying `parents` links via `kanban_list(board="gravity-well")`, unblock the cards in dependency order (`unblock` on a card with unfinished parents moves it to `todo`, while `w0-research` moves to `ready`).

**Standalone Review/Audit Verdict Protocol:** Review/critique/security cards are gated children (`parents=[...]`), NOT claimed from the `review` column:
- On **PASS** (zero blocking/must-fix findings): write report to `.plan/reviews/<card-key>.md` and call `kanban_complete(summary="PASS ...", metadata={...})`.
- On **CHANGES_REQUIRED** (any blocking/must-fix finding): write numbered findings (`file:line`, reproduction/evidence, required failing-before regression test) to `.plan/reviews/<card-key>.md` and call `kanban_block(kind="needs_input", reason="CHANGES_REQUIRED: see .plan/reviews/<card-key>.md")`. The orchestrator then creates a linked fix card assigned to `coder`, links the fix card as a parent of the review card, and unblocks the review card after the fix commits.

### Wave 0 — Grounding, Specification & Creative Direction (Serial Docs Chain)

1. **`w0-research`** (`assignee="researcher"`, `parents=[]`, `goal_mode=True`, `goal_max_turns=20`, `idempotency_key="gw-w0-research-v2"`)
   - **Owned files:** `docs/RESEARCH.md`
   - **Brief:** Empirically probe `/home/jason/.local/bin/agy` (`1.5.15`) and read-only `~/.gemini/antigravity-cli/` state without mutating real user state (use `$TMPDIR` `HOME` sandboxes for any stateful probes). Capture exact JSON-RPC `initialize`, `session/new`, `session/prompt`, `session/resume`, `session/load`, `session/cancel`, `session/set_config_option`, `session/request_permission`, and `elicitation/create` payloads from `agy acp --stdio`; inspect exact CLI outputs/JSON schemas for `agy models`, `agy agents`, `agy mcp list`, `agy plugin list --json`, `agy remote-control status`, `agy update --help`, and `agy -p /usage --output-format json`; inspect `?mode=ro` SQLite schema of `~/.gemini/antigravity-cli/conversation_summaries.db`, `brain/`, `conversations/`, `presence/`, `cli.log`, `crashes/`, and `~/.gemini/config/mcp_config.json`.
   - **Gate:** Commit `docs/RESEARCH.md` with header `Authority: Empirical Baseline` and numbered findings `R1..Rn`.

2. **`w0-spec`** (`assignee="specifier"`, `parents=[w0-research]`, `goal_mode=True`, `goal_max_turns=16`, `idempotency_key="gw-w0-spec-v2"`)
   - **Owned files:** `docs/API.md`, `docs/SPEC.md`
   - **Brief:** Read `docs/RESEARCH.md` and `docs/PLAN.md`. Lock the canonical REST + WebSocket wire contract in `docs/API.md` and numbered architectural decisions (`D1..Dn`) + Android SQLite WAL cache DDL + file ownership map in `docs/SPEC.md`. Must cover:
     - Auth: `GET /api/health` unauthenticated; all other REST/WS routes require `hmac.compare_digest` bearer token (`~/.config/gravity-well/gateway.env`, `0600` auto-minted); WebSockets accept `Authorization: Bearer` header or single-use 30s `POST /api/ws-ticket` (query-string permanent tokens MUST `401`).
     - Sessions & ACP Supervisor: create, list (live + archived FTS5), resume, fork, rename, close, cancel turn, live config (`model`, `thought_level`, `mode`), FIFO follow-up queue (`enqueue`, `edit`, `cancel`, `send-now`, auto-drain on clean `end_turn`), interactive permission (`allow_once`, workspace-scoped `allow_rule`, `deny`) + `elicitation/create` question broker, monotonic `cursor` + per-session `seq` ring-buffer replay (`WS /api/ws`, `WS /api/sessions/{id}/ws`, `GET /api/sessions/{id}/events?after=<cursor>`).
     - Storage, Observatory & Ecosystem: `?mode=ro` `conversation_summaries.db` FTS5 search, symlink-contained `brain/<id>/` artifact listing/serving and `@file` workspace search, TTL-cached `/usage` quota/credits parser, `presence/` external-session radar + host CPU/RAM/disk + secret-redacted `cli.log`/`crashes/` tail, `remote-control` daemon start/stop/status, confirm-gated `agy update`, CRUD/toggles for `models`, `agents`, `mcp`, `plugins`, and allowlisted `settings.json` keys.
     - Android contract: `us.snakepit.gravitywell` (`.debug` suffix), `AndroidKeyStore` (`AES/GCM/NoPadding`, random 12B IV), 3-leg host onboarding (`/api/health` -> `/api/info` -> `WS hello`), `SQLiteOpenHelper` WAL DDL, OkHttp error interceptor, `GravityWellKeepaliveService` (`dataSync`), `KeepaliveActionReceiver` (`Approve Once` / `Deny` / `Stop`).
   - **Gate:** Commit `docs/API.md` and `docs/SPEC.md` with verified host-baseline table and zero open TBDs.

3. **`w0-direction`** (`assignee="creative-director"`, `parents=[w0-spec]`, `goal_mode=True`, `goal_max_turns=12`, `idempotency_key="gw-w0-direction-v2"`)
   - **Owned files:** `docs/creative/DIRECTION-gravity-well.md`
   - **Brief:** Read `docs/SPEC.md`, `docs/API.md`, and `docs/PLAN.md`. Define the complete visual, typographic, color-token, layout, and interaction direction for Gravity Well (native Kotlin + Jetpack Compose, `dynamicColor = false`, zero stock Material 3 chat bubbles). Cover: Adaptive Shell (`<600dp` bottom `NavigationBar` + `>=600dp` `NavigationRail`), Host Onboarding 3-leg check, Workspace & Session Browser (live + archived + external radar), Live Session Console (streaming Markdown, collapsible `/thinking`, collapsible tool gutters with unified/word diffs, subagent/swarm strip, `brain/` artifact viewer, docked Approval/Question bar, queued follow-up tray with `Send Now`, model/effort/mode/sandbox sheet, slash command chips, `@file` autocomplete, magic-byte attachment preview), Observatory (quota gauges + reset countdowns, host vitals, daemon toggle, confirm-gated update, crash/log viewer), Control Deck (MCP, plugins, agents, allowlisted settings), and Notification/Keepalive surface. Must refuse the safe generic chat-app option explicitly and select one concrete direction with hex/token specs.
   - **Gate:** Commit `docs/creative/DIRECTION-gravity-well.md`.

### Wave 1 — Host Gateway (`gateway/`)

4. **`w1-gateway-core`** (`assignee="coder"`, `parents=[w0-direction]`, `goal_mode=True`, `goal_max_turns=25`, `idempotency_key="gw-w1-gateway-core-v2"`)
   - **Owned files:** `gateway/pyproject.toml`, `gateway/requirements.txt`, `gateway/gateway.env.example`, `gateway/gravity_well_gateway/__init__.py`, `gateway/gravity_well_gateway/main.py`, `gateway/gravity_well_gateway/config.py`, `gateway/gravity_well_gateway/auth.py`, `gateway/gravity_well_gateway/acp_client.py`, `gateway/gravity_well_gateway/supervisor.py`, `gateway/gravity_well_gateway/replay.py`, `gateway/gravity_well_gateway/storage.py`, `gateway/tests/conftest.py`, `gateway/tests/fake_agy.py`, `gateway/tests/test_auth.py`, `gateway/tests/test_acp_supervisor.py`, `gateway/tests/test_replay_queue.py`, `gateway/tests/test_storage_ro.py`
   - **Brief:** Implement `gateway/` core per `docs/API.md` and `docs/SPEC.md`: Python 3.15 venv via `uv`, bearer auth + 30s single-use WS tickets, allowlisted child env (`ANTIGRAVITY_ENV_STRICT=1`), per-session `agy acp --stdio` process supervisor (create/resume/load/fork/rename/close/cancel/config), permission + `elicitation/create` broker, serialized FIFO follow-up queue with `send-now` and clean-turn drain, monotonic cursor + per-session seq ring buffer + `WS /api/ws` + `WS /api/sessions/{id}/ws` + HTTP event catch-up, and `?mode=ro` SQLite FTS5 + symlink-contained `brain/` + `@file` search + attachments (magic-byte check).
   - **Gate:** `cd gateway && env -u PYTHONPATH uv run pytest -v` all green, `grep -rn REDCHECK gateway/` empty, 2+ executed `REDCHECK` proofs recorded in metadata, commit clean.

5. **`w1-gateway-ops`** (`assignee="coder"`, `parents=[w1-gateway-core]`, `goal_mode=True`, `goal_max_turns=22`, `idempotency_key="gw-w1-gateway-ops-v2"`)
   - **Owned files:** `gateway/gravity_well_gateway/observatory.py`, `gateway/gravity_well_gateway/ecosystem.py`, `gateway/gravity_well_gateway/redaction.py`, `gateway/gravity_well_gateway/main.py` (wire routers), `gateway/systemd/gravity-well-gateway.service`, `gateway/scripts/install_service.sh`, `gateway/tests/test_observatory.py`, `gateway/tests/test_ecosystem.py`, `gateway/tests/test_redaction.py`
   - **Brief:** Implement Observatory (`/api/observatory/usage` TTL-cached `/usage` parser, `presence/` external-session radar, host CPU/RAM/disk, secret-redacted `cli.log`/`crashes/` tail, `remote-control` daemon start/stop/status, confirm-gated `agy update`), Ecosystem CRUD/toggles (`models`, `agents`, `mcp`, `plugins`, allowlisted `settings.json` keys using temp atomic rewrites in sandboxed tests), and `systemd` user service unit pinning `/usr/bin/python3` / `.venv/bin/uvicorn` on `0.0.0.0:9340` (do NOT install or enable the live unit during this card).
   - **Gate:** `cd gateway && env -u PYTHONPATH uv run pytest -v` all green, `grep -rn REDCHECK gateway/` empty, 2+ executed `REDCHECK` proofs recorded, commit clean.

6. **`w1-gateway-review`** (`assignee="reviewer"`, `parents=[w1-gateway-ops]`, `goal_mode=True`, `goal_max_turns=14`, `idempotency_key="gw-w1-gateway-review-v2"`)
   - **Owned files:** `.plan/reviews/w1-gateway-review.md` (read-only on `gateway/`)
   - **Brief:** Blind read-only review of `gateway/` against `docs/API.md` and `docs/SPEC.md` (auth, WS ticket lifecycle, ACP process cleanup/zombies, permission/question race conditions, queue drain on error vs end_turn, FTS5/symlink containment, systemd ExecStart). Run `cd gateway && env -u PYTHONPATH uv run pytest -v`. Write verdict to `.plan/reviews/w1-gateway-review.md` and complete (`PASS`) or block (`CHANGES_REQUIRED`).

7. **`w1-gateway-security`** (`assignee="security"`, `parents=[w1-gateway-ops]`, `goal_mode=True`, `goal_max_turns=14`, `idempotency_key="gw-w1-gateway-security-v2"`)
   - **Owned files:** `.plan/reviews/w1-gateway-security.md` (read-only on `gateway/`)
   - **Brief:** Parallel read-only security audit of `gateway/`: `hmac.compare_digest` timing safety, WS ticket single-use/TTL/no-query-token, path traversal & symlink escape on `brain/`/`@file`/cwd, argv-only subprocess safety (`shell=False`), child env allowlist + `ANTIGRAVITY_ENV_STRICT=1`, secret redaction in logs/crashes, allowlisted settings keys, OAuth/ToS non-extraction compliance. Write verdict to `.plan/reviews/w1-gateway-security.md` and complete (`PASS`) or block (`CHANGES_REQUIRED`).

### Wave 2 — Android Foundation & Live Session Console (`android/`)

8. **`w2-android-foundation`** (`assignee="coder"`, `parents=[w1-gateway-review, w1-gateway-security]`, `goal_mode=True`, `goal_max_turns=28`, `idempotency_key="gw-w2-android-foundation-v2"`)
   - **Owned files:** `android/settings.gradle.kts`, `android/build.gradle.kts`, `android/gradle.properties`, `android/gradle/libs.versions.toml`, `android/app/build.gradle.kts`, `android/app/proguard-rules.pro`, `android/app/src/main/AndroidManifest.xml`, `android/app/src/main/res/**`, `android/app/src/main/java/us/snakepit/gravitywell/theme/**`, `android/app/src/main/java/us/snakepit/gravitywell/security/**`, `android/app/src/main/java/us/snakepit/gravitywell/cache/**`, `android/app/src/main/java/us/snakepit/gravitywell/net/**`, `android/app/src/test/**`
   - **Brief:** Set up Android project matching `home-radio-android` toolchain (`AGP 9.4.1`, `Kotlin 2.4.21`, `compileSdk = 36`, `minSdk = 26`, `targetSdk = 36`, `applicationId = "us.snakepit.gravitywell"`, `.debug` suffix), custom theme tokens implementing `docs/creative/DIRECTION-gravity-well.md` (`dynamicColor = false`), `AndroidKeyStore` (`AES/GCM/NoPadding`, random 12-byte IV) multi-host token vault, `SQLiteOpenHelper` (`WAL`) offline cache matching `docs/SPEC.md` DDL, shared OkHttp client + error-mapping interceptor (reused in tests!), 3-leg host verifier, and exponential-backoff WebSocket client with `?after=<cursor>` replay and `AtomicLong` generation counter.
   - **Gate:** `free -h`, then `cd android && JAVA_HOME=/home/jason/.jdks/ms-21.0.9 ANDROID_HOME=/home/jason/Android/Sdk ./gradlew :app:testDebugUnitTest --no-daemon --max-workers=1 -Dorg.gradle.parallel=false` + `./gradlew --stop`, parse XML test counts, 2+ `REDCHECK` proofs, `grep -rn REDCHECK android/` empty, commit clean.

9. **`w2-android-session`** (`assignee="coder"`, `parents=[w2-android-foundation]`, `goal_mode=True`, `goal_max_turns=35`, `idempotency_key="gw-w2-android-session-v2"`)
   - **Owned files:** `android/app/src/main/java/us/snakepit/gravitywell/MainActivity.kt`, `android/app/src/main/java/us/snakepit/gravitywell/GravityWellApp.kt`, `android/app/src/main/java/us/snakepit/gravitywell/ui/shell/**`, `android/app/src/main/java/us/snakepit/gravitywell/ui/hosts/**`, `android/app/src/main/java/us/snakepit/gravitywell/ui/sessions/**`, `android/app/src/main/java/us/snakepit/gravitywell/ui/console/**`, `android/app/src/test/**`
   - **Brief:** Build Adaptive Shell (`NavigationBar` `<600dp` / `NavigationRail` `>=600dp`), Host Onboarding (3-leg diagnostic step UI), Workspace & Session Browser (live + archived + FTS5 search + external presence radar), and Live Session Console strictly following `docs/creative/DIRECTION-gravity-well.md`: streaming Markdown, collapsible `/thinking`, collapsible tool gutters with unified/word diffs, subagent/swarm progress strip, `brain/` artifact viewer, docked Approval & Question bar, queued follow-up tray (`Send Now` preserving draft), model/effort/mode/sandbox sheet, slash command chips, IME-bounded `@file` autocomplete, and magic-byte MIME-sniffed attachments.
   - **Gate:** `free -h`, then `:app:testDebugUnitTest :app:assembleDebug --no-daemon --max-workers=1 -Dorg.gradle.parallel=false`, `./gradlew --stop`, parse XML counts, 2+ `REDCHECK` proofs, commit clean.

10. **`w2-android-review`** (`assignee="reviewer"`, `parents=[w2-android-session]`, `goal_mode=True`, `goal_max_turns=16`, `idempotency_key="gw-w2-android-review-v2"`)
    - **Owned files:** `.plan/reviews/w2-android-review.md` (read-only on `android/`)
    - **Brief:** Blind read-only review of Wave 2 (`theme`, `security`, `cache`, `net`, `ui/shell`, `ui/hosts`, `ui/sessions`, `ui/console`) against `docs/API.md`, `docs/SPEC.md`, and `docs/creative/DIRECTION-gravity-well.md`. Check KeyStore IV uniqueness, SQLite WAL migrations/bounds, WS reconnect race/generation guard, OkHttp error-pipeline test parity, Compose state-hoisting/recomposition, and run bounded `:app:testDebugUnitTest` + `./gradlew --stop`. Write `.plan/reviews/w2-android-review.md` and complete (`PASS`) or block (`CHANGES_REQUIRED`).

### Wave 3 — Observatory, Control Deck, Keepalive Service & Full Audits

11. **`w3-android-ops`** (`assignee="coder"`, `parents=[w2-android-review]`, `goal_mode=True`, `goal_max_turns=30`, `idempotency_key="gw-w3-android-ops-v2"`)
    - **Owned files:** `android/app/src/main/java/us/snakepit/gravitywell/ui/observatory/**`, `android/app/src/main/java/us/snakepit/gravitywell/ui/deck/**`, `android/app/src/main/java/us/snakepit/gravitywell/service/**`, `android/app/src/main/AndroidManifest.xml`, `android/app/src/main/java/us/snakepit/gravitywell/MainActivity.kt`, `android/app/src/main/java/us/snakepit/gravitywell/ui/shell/**`, `android/app/src/main/res/drawable/ic_notification.xml`, `android/app/src/test/**`
    - **Brief:** Implement Observatory UI (quota/credit gauges + reset countdowns, host CPU/RAM/disk, external presence radar, `remote-control` daemon toggle, confirm-gated `agy update`, searchable `cli.log`/crash sheet), Control Deck UI (`mcp`, `plugins`, `agents`, allowlisted settings + revoke notification permission link), and Android keepalive/notification stack: `GravityWellKeepaliveService` (`dataSync`), `KeepaliveActionReceiver` (`Approve Once` / `Deny` / `Stop`), `singleTask` cold-start deep-link intent queue in `MainActivity`, dedicated 24dp monochrome `ic_notification.xml`, inline `SDK_INT >= 33` + `checkSelfPermission` + `SecurityException` guards at every `notify()` site.
    - **Gate:** `free -h`, then `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease --no-daemon --max-workers=1 -Dorg.gradle.parallel=false`, `./gradlew --stop`, 0 Lint Errors/Fatals in owned files, XML test counts, 2+ `REDCHECK` proofs, commit clean.

12. **`w3-full-review`** (`assignee="reviewer"`, `parents=[w3-android-ops]`, `goal_mode=True`, `goal_max_turns=18`, `idempotency_key="gw-w3-full-review-v2"`)
    - **Owned files:** `.plan/reviews/w3-full-review.md` (read-only on code)
    - **Brief:** Whole-repo blind correctness review of `gateway/` + `android/` against `docs/API.md` and `docs/SPEC.md` (end-to-end wire field parity, lifecycle/backgrounding, `singleTask` cold-start routing, `KeepaliveService` permission & `MissingPermission` guards, queue/approval races). Run `pytest` and bounded `:app:testDebugUnitTest` + `./gradlew --stop`. Write `.plan/reviews/w3-full-review.md` and complete (`PASS`) or block (`CHANGES_REQUIRED`).

13. **`w3-creative-critique`** (`assignee="creative-director"`, `parents=[w3-android-ops]`, `goal_mode=True`, `goal_max_turns=14`, `idempotency_key="gw-w3-creative-critique-v2"`)
    - **Owned files:** `.plan/reviews/w3-creative-critique.md` (read-only on `android/`)
    - **Brief:** Static UI/UX fidelity audit of `android/app/src/main/java/us/snakepit/gravitywell/theme/**` and `ui/**` against `docs/creative/DIRECTION-gravity-well.md`. Verify token parity (`dynamicColor = false`, zero literal hex/generic M3 bubbles), adaptive navigation, Observatory/Control Deck hierarchy, and Live Session Console ergonomics. Write `.plan/reviews/w3-creative-critique.md` with `HOLDS` or `DRIFTED` + numbered findings (`kills-the-direction` / `weakens` / `nit`), and complete (`HOLDS`) or block (`DRIFTED`).

14. **`w3-security-audit`** (`assignee="security"`, `parents=[w3-android-ops]`, `goal_mode=True`, `goal_max_turns=14`, `idempotency_key="gw-w3-security-audit-v2"`)
    - **Owned files:** `.plan/reviews/w3-security-audit.md` (read-only on code)
    - **Brief:** End-to-end security audit across `gateway/` + `android/`: release manifest (`usesCleartextTraffic=false`, exported components/receivers, permissions), KeyStore AES-GCM IV/key handling, SQLite cache secret non-persistence, WS ticket/header auth, path traversal/redaction, `ANTIGRAVITY_ENV_STRICT=1`, and zero Google OAuth / `cloudcode-pa` ToS violations. Write `.plan/reviews/w3-security-audit.md` and complete (`PASS`) or block (`CHANGES_REQUIRED`).

### Wave 4 — Live Loopback Integration & Release Verification

15. **`w4-integration`** (`assignee="coder"`, `parents=[w3-full-review, w3-creative-critique, w3-security-audit]`, `goal_mode=True`, `goal_max_turns=25`, `idempotency_key="gw-w4-integration-v2"`)
    - **Owned files:** `gateway/tests/test_e2e_live.py`, `docs/RUNBOOK.md`
    - **Brief:** Stand up an isolated loopback gateway bench (`127.0.0.1:9341`) backed by a `$TMPDIR` `HOME` sandbox and `FakeAgy` (never touching `9340` or real `~/.gemini/`), run live HTTP + WebSocket E2E wire-contract verification (health, auth 401s, 30s single-use WS ticket, query-token rejection, session lifecycle, streaming diffs/thoughts, permission approval/deny, question response, queue `send-now`, cursor replay across disconnect, observatory + ecosystem CRUD, redaction), verify all Gradle gates (`:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`, `:app:assembleRelease`) + `./gradlew --stop`, inspect release APK manifest via `aapt2 dump badging`/`xmltree` (`INTERNET` present, `usesCleartextTraffic=false`, `us.snakepit.gravitywell`), and write `docs/RUNBOOK.md` (exact `systemd` install/verify commands, Tailscale/HTTPS setup, Android release signing & sideload instructions).
    - **Gate:** Full `pytest` green, full Gradle debug/release/lint green, `aapt2` release checks verified, `127.0.0.1:9341` bench cleanly stopped, `git status` clean, commit clean.
