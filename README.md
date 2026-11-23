# Gravity Well

Complete remote control and telemetry console for a Google Antigravity (`agy`) host, built as a self-hosted Python HTTP/WebSocket gateway (`gateway/`, port `9340`) paired with a native Kotlin + Jetpack Compose Android client (`android/`, `us.snakepit.gravitywell`).

- Architecture & wave plan: [`docs/PLAN.md`](docs/PLAN.md)
- Empirical `agy` host findings: `docs/RESEARCH.md` (Wave 0)
- Canonical wire contract & engineering spec: `docs/API.md`, `docs/SPEC.md` (Wave 0)
- Creative direction: `docs/creative/DIRECTION-gravity-well.md` (Wave 0)
- Operator runbook: `docs/RUNBOOK.md` (Wave 4)

## Sanctioned Antigravity Integration Posture

Gravity Well interacts exclusively with the official `agy` CLI binary (`/home/jason/.local/bin/agy`) via `agy acp --stdio` (Agent Client Protocol JSON-RPC 2.0) and documented CLI subcommands (`models`, `agents`, `mcp`, `plugin`, `remote-control`, `update`, `-p /usage --output-format json`), plus read-only (`?mode=ro`) inspection of `~/.gemini/antigravity-cli/` conversation summaries, brain artifacts, logs, and presence markers. It never reads, extracts, or proxies Google OAuth credentials or calls `cloudcode-pa` directly.
