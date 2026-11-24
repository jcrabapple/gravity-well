# Gravity Well

Native Android chat client and self-hosted ACP gateway for **Google Antigravity (`agy`)**.

Gravity Well pairs a clean, dark-mode Jetpack Compose Android app (`android/`) with a lightweight Python FastAPI/WebSocket gateway (`gateway/`) that supervises `agy acp --stdio` sessions on your Linux workstation—so you can chat with Antigravity, approve tool executions, review diffs, attach files, monitor quotas, and manage MCP servers, plugins, and custom agents from your phone or tablet.

## Highlights

- **Chat-first Android UI:** 3-tab navigation (**Chats**, **Status**, **Settings**), right-aligned user bubbles, full-width streaming Markdown with code copy, collapsible Thinking and Tool cards with Unified/Word diff views, smart auto-scroll, and adaptive tablet two-pane layout (`>=600dp`).
- **Non-blocking Approval & Question Cards:** Tool permissions (`Allow once`, `Always allow in project`, `Deny`) and `elicitation/create` questions dock directly above the composer so you can keep scrolling and reading diffs.
- **Follow-up Queue & Offline Cache:** Enqueue follow-ups while Antigravity is running, reorder/edit/`Send now` without losing your current draft, `@file` autocomplete, `/` slash commands, magic-byte file attachments (`<=10 MiB`), SQLite offline cache, cursor replay/resync, and background approval alerts.
- **Status & Ecosystem Controls:** Live usage quota gauges with reset countdowns, host vitals, active host session radar (`FLOCK`-verified), confirm-gated `agy update`, redacted log/crash viewer, MCP servers, plugins, custom agents, and revocable project permission rules.

---

## Repository Structure

```text
gravity-well/
├── android/      # Native Kotlin + Jetpack Compose Android client (us.snakepit.gravitywell)
├── gateway/      # Self-hosted Python FastAPI/Uvicorn ACP gateway (port 9340)
└── docs/         # Wire contract (API.md), engineering spec (SPEC.md), ops runbook (RUNBOOK.md)
```

---

## Quick Start: Self-Hosted ACP Gateway (`gateway/`)

### Prerequisites

- Linux host with **Google Antigravity CLI (`agy`)** installed and authenticated (e.g., `~/.local/bin/agy`).
- **Python 3.11+** and [`uv`](https://docs.astral.sh/uv/) (or standard `venv` + `pip`).
- An HTTPS reverse proxy such as **Tailscale Serve** (recommended) for connecting from the Android release APK, which enforces HTTPS/WSS (`usesCleartextTraffic="false"`).

### Option A: Automated `systemd --user` Install (Recommended)

```bash
git clone https://github.com/jcrabapple/gravity-well.git
cd gravity-well

# Preview what the installer will configure
./gateway/scripts/install_service.sh --dry-run

# Create venv, mint ~/.config/gravity-well/gateway.env (0600) with a random token,
# install ~/.config/systemd/user/gravity-well-gateway.service, and start it
./gateway/scripts/install_service.sh
```

Useful service commands:
```bash
systemctl --user status gravity-well-gateway.service
journalctl --user -u gravity-well-gateway.service -f
systemctl --user restart gravity-well-gateway.service

# Optional: keep running when logged out
loginctl enable-linger "$USER"
```

### Option B: Run Manually

```bash
cd gravity-well/gateway
env -u PYTHONPATH uv sync
env -u PYTHONPATH uv run uvicorn gravity_well_gateway.main:app --host 127.0.0.1 --port 9340
```

On first launch, if `~/.config/gravity-well/gateway.env` does not exist, the gateway automatically creates it (`chmod 0600`) with a fresh 256-bit `GRAVITY_WELL_TOKEN`. See [`gateway/gateway.env.example`](gateway/gateway.env.example) for optional environment variables (`GRAVITY_WELL_AGY_BIN`, `GRAVITY_WELL_DEFAULT_CWD`, `GRAVITY_WELL_ALLOWED_ROOTS`, `GRAVITY_WELL_HOST`, `GRAVITY_WELL_PORT`).

### Verify the Gateway Locally

```bash
# Unauthenticated health probe
curl -fsS http://127.0.0.1:9340/api/health

# Authenticated info probe
(
  set -a
  . ~/.config/gravity-well/gateway.env
  set +a
  curl -fsS -H "Authorization: Bearer ${GRAVITY_WELL_TOKEN}" http://127.0.0.1:9340/api/info
)
```

### Expose Over HTTPS/WSS with Tailscale Serve (Recommended)

Release builds of the Android app require TLS (`https://` / `wss://`). Tailscale Serve gives your gateway a valid HTTPS endpoint privately inside your tailnet without opening public firewall ports:

```bash
tailscale serve --bg http://127.0.0.1:9340
tailscale serve status
```

Your gateway URL will be `https://<machine>.<tailnet>.ts.net`.

---

## Installing & Pairing the Android App (`android/`)

### Install the APK

1. Download the latest `gravity-well-v*.apk` from [GitHub Releases](https://github.com/jcrabapple/gravity-well/releases), or build it from source:
   ```bash
   cd android
   ./gradlew :app:testDebugUnitTest :app:assembleRelease
   # Output: android/app/build/outputs/apk/release/app-release.apk
   ```
2. Install on your Android device (Android 8.0 / API 26+):
   - Sideload the APK directly on-device, **or**
   - Install via ADB: `adb install -r gravity-well-v1.0.0.apk`

### Pair With Your Gateway

1. Copy `GRAVITY_WELL_TOKEN` from `~/.config/gravity-well/gateway.env` on your host.
2. Open **Gravity Well** on Android and enter:
   - **Host name:** e.g. `workstation` (optional — defaults to host's hostname)
   - **Gateway URL:** `https://<machine>.<tailnet>.ts.net`
   - **Access token:** your `GRAVITY_WELL_TOKEN`
3. Tap **Test connection** to run the 3-step verification (`Server & TLS`, `Authentication & capabilities`, `Live chat stream`), then tap **Save & connect**.

Tokens are encrypted at rest in `AndroidKeyStore` (`AES/GCM/NoPadding`, random 12-byte IV) and are never stored in SQLite.

---

## Running Tests

### Gateway (Unit + Live Loopback E2E)

```bash
cd gateway
env -u PYTHONPATH uv run pytest -v
```

### Android Unit Tests

```bash
cd android
./gradlew :app:testDebugUnitTest
```

---

## Sanctioned Antigravity Integration Posture

Gravity Well interacts exclusively with the official `agy` CLI binary via `agy acp --stdio` (Agent Client Protocol JSON-RPC 2.0) and documented CLI subcommands (`models`, `agents`, `mcp`, `plugin`, `remote-control`, `update`, `-p /usage --output-format json`), plus read-only (`?mode=ro`) inspection of `~/.gemini/antigravity-cli/` conversation summaries, brain artifacts, logs, and presence markers. Child processes run with `shell=False` and a scrubbed allowlisted environment (`ANTIGRAVITY_ENV_STRICT=1`). It never reads, extracts, or proxies Google OAuth credentials.

## Documentation

- Operations, signing & deployment runbook: [`docs/RUNBOOK.md`](docs/RUNBOOK.md)
- Canonical wire contract: [`docs/API.md`](docs/API.md)
- Engineering specification: [`docs/SPEC.md`](docs/SPEC.md)
- UI/UX creative direction: [`docs/creative/DIRECTION-gravity-well.md`](docs/creative/DIRECTION-gravity-well.md)
