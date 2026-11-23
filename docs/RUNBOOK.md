<!--
  Authority: Operations & Deployment Runbook
  Deliverable: Gravity Well
  Status: Verified (Wave 4)
-->
# Gravity Well — Operations, Deployment & Release Runbook

## 1. System Overview & Baseline

- **Repository:** `/home/jason/projects/gravity-well`
- **Gateway (`gateway/`):** Python 3.15 + FastAPI/Uvicorn ACP gateway listening on `0.0.0.0:9340` (production `systemd` user unit) or `127.0.0.1:9341` (isolated loopback E2E bench).
- **Antigravity CLI:** `/home/jason/.local/bin/agy` (`1.5.15`), CLI state under `~/.gemini/antigravity-cli/`.
- **Android Client (`android/`):** Native Kotlin + Jetpack Compose (`us.snakepit.gravitywell` release, `us.snakepit.gravitywell.debug` debug, `minSdk = 26`, `targetSdk = 36`, `compileSdk = 36`).
- **Toolchain Paths (Aurora Host):**
  - `JAVA_HOME=/home/jason/.jdks/ms-21.0.9`
  - `ANDROID_HOME=/home/jason/Android/Sdk` (`build-tools/36.1.0`, `platforms/android-36`)
  - Python interpreter: `/usr/bin/python3` (`3.15.2`), `uv`: `/home/jason/.local/bin/uv`

> **Python `PYTHONPATH` Rule:** Always prefix `uv` and `.venv/bin/pytest` invocations with `env -u PYTHONPATH` when running inside managed shells that export `PYTHONPATH`.

---

## 2. Gateway Installation & `systemd` User Service

### 2.1 Dry-Run & Install

The repository includes `gateway/systemd/gravity-well-gateway.service` and `gateway/scripts/install_service.sh`.

1. Preview what the installer will configure without modifying files or `systemd`:
   ```bash
   cd /home/jason/projects/gravity-well
   ./gateway/scripts/install_service.sh --dry-run
   ```

2. Sync the virtualenv pinned to `/usr/bin/python3`, initialize `~/.config/gravity-well/gateway.env` (`0600`) with a random `GRAVITY_WELL_TOKEN` if absent, install the user unit to `~/.config/systemd/user/gravity-well-gateway.service`, and enable/start it immediately:
   ```bash
   cd /home/jason/projects/gravity-well
   ./gateway/scripts/install_service.sh
   ```

   To stage the files and virtualenv without enabling or starting the service yet:
   ```bash
   ./gateway/scripts/install_service.sh --no-enable
   ```

### 2.2 Exact `systemd` Management & Verification Commands

```bash
# Reload user unit definitions after any unit edit
systemctl --user daemon-reload

# Enable on login and start immediately
systemctl --user enable --now gravity-well-gateway.service

# Inspect service health and recent journal output
systemctl --user status --no-pager gravity-well-gateway.service
journalctl --user -u gravity-well-gateway.service -n 50 --no-pager

# Follow live gateway logs
journalctl --user -u gravity-well-gateway.service -f

# Restart or stop the gateway
systemctl --user restart gravity-well-gateway.service
systemctl --user stop gravity-well-gateway.service
```

Optional: keep the user service running even when no interactive login session is active (survives logout/reboot):
```bash
loginctl enable-linger "$USER"
```

### 2.3 Verify Live Gateway Endpoints Locally

1. Verify the unit file and port binding:
   ```bash
   systemd-analyze verify --user ~/.config/systemd/user/gravity-well-gateway.service
   ss -tlnp | grep 9340
   ```

2. Probe the unauthenticated health endpoint (`GET /api/health`):
   ```bash
   curl -fsS http://127.0.0.1:9340/api/health
   ```
   Expected response contains `"status":"ok"`, `"service":"gravity-well-gateway"`, `"protocol_version":1`, and `"auth_required":true` with zero paths or secrets.

3. Probe authenticated metadata (`GET /api/info`) using the `0600` token without leaking it into shell history:
   ```bash
   (
     set -a
     . ~/.config/gravity-well/gateway.env
     set +a
     curl -fsS -H "Authorization: Bearer ${GRAVITY_WELL_TOKEN}" http://127.0.0.1:9340/api/info
   )
   ```

4. Confirm unauthenticated access to `/api/info` is rejected with `401`:
   ```bash
   curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:9340/api/info
   # Expected: 401
   ```

### 2.4 Configuration File (`~/.config/gravity-well/gateway.env`)

`gateway/gateway.env.example` documents supported variables. Keep permissions strictly `0600` (`chmod 0600 ~/.config/gravity-well/gateway.env`):

```ini
GRAVITY_WELL_TOKEN=<auto-minted-urlsafe-token>
GRAVITY_WELL_HOST=0.0.0.0
GRAVITY_WELL_PORT=9340
# GRAVITY_WELL_AGY_BIN=/home/jason/.local/bin/agy
# GRAVITY_WELL_DEFAULT_CWD=/home/jason
# GRAVITY_WELL_ALLOWED_ROOTS=/home/jason
```

- If using **Tailscale Serve exclusively** (recommended), you may bind the gateway strictly to loopback by setting `GRAVITY_WELL_HOST=127.0.0.1` in `~/.config/gravity-well/gateway.env` and updating `ExecStart` (or drop-in override) `--host 127.0.0.1`, then restarting the service.
- To rotate the bearer token, update `GRAVITY_WELL_TOKEN` in `gateway.env` (preserving `0600`), run `systemctl --user restart gravity-well-gateway.service`, and re-onboard or update the token on paired Android clients.

---

## 3. Tailscale / HTTPS & WSS Setup

Release builds of the Android app enforce `android:usesCleartextTraffic="false"` in `AndroidManifest.xml`. All remote connections from the Android release APK **must** use `https://` and `wss://`. (Debug builds allow cleartext HTTP/WS **only** to `10.0.2.2`, `127.0.0.1`, and `localhost` via `android/app/src/debug/res/xml/network_security_config.xml`.)

### 3.1 Expose Gateway Over Tailscale HTTPS/WSS (`tailscale serve`)

`tailscale serve` terminates TLS with a valid Let's Encrypt certificate for your tailnet MagicDNS hostname (`https://<machine>.<tailnet>.ts.net`) and forwards both HTTPS REST calls and WSS WebSocket upgrades directly to the local gateway on `127.0.0.1:9340`, without exposing port `9340` to the public internet.

1. Verify Tailscale is connected and check your MagicDNS hostname:
   ```bash
   tailscale status
   ```

2. Configure Tailscale Serve in the background to proxy HTTPS `:443` -> `http://127.0.0.1:9340`:
   ```bash
   tailscale serve --bg http://127.0.0.1:9340
   tailscale serve status
   ```

3. Verify external TLS + WebSocket-capable HTTPS reachability from the tailnet URL:
   ```bash
   TS_HOST="$(tailscale status --json | python3 -c 'import json,sys; print(json.load(sys.stdin)["Self"]["DNSName"].rstrip("."))')"
   curl -fsS "https://${TS_HOST}/api/health"
   ```

4. To tear down the Tailscale Serve mapping later:
   ```bash
   tailscale serve --https=443 off
   ```

### 3.2 Pair the Android Client (3-Leg Host Onboarding)

In the Gravity Well Android app:
1. Open **Hosts -> Add Host**.
2. Enter:
   - **Label:** e.g. `Aurora`
   - **Base URL:** `https://<machine>.<tailnet>.ts.net` (no trailing slash)
   - **Bearer Token:** value of `GRAVITY_WELL_TOKEN` from `~/.config/gravity-well/gateway.env`
3. Tap **Verify & Save**. The app executes the 3-leg verification sequence:
   - **Leg 1:** `GET /api/health` (TLS reachability + `protocol_version == 1`)
   - **Leg 2:** `GET /api/info` (bearer token verification + host capabilities)
   - **Leg 3:** `POST /api/ws-ticket` -> `WS /api/ws?ticket=...` -> awaits `hello` frame

The bearer token is encrypted at rest using `AndroidKeyStore` (`AES/GCM/NoPadding`, random 12-byte IV) and is never written to SQLite.

---

## 4. Android Build, Release Signing & Sideload Instructions

### 4.1 Aurora Gradle Memory Guard

Before running any `./gradlew` command on Aurora:
1. Check free host memory:
   ```bash
   free -h
   ```
2. Export `JAVA_HOME` and `ANDROID_HOME`, pass `--no-daemon --max-workers=1 -Dorg.gradle.parallel=false`, and stop any Gradle daemon immediately after:
   ```bash
   cd /home/jason/projects/gravity-well/android
   export JAVA_HOME=/home/jason/.jdks/ms-21.0.9
   export ANDROID_HOME=/home/jason/Android/Sdk
   ```

### 4.2 Create Outside-Repo Release Signing Key (First-Time Setup)

`android/app/build.gradle.kts` reads signing credentials from `~/.config/gravity-well-signing/key.properties` (never committed to git) and falls back to the Android debug keystore only when that file is absent.

> **Important:** Android rejects updates signed with a different certificate (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). Create the real release keystore **before** your first release sideload; otherwise switching from a debug-signed release APK to a real release key later requires uninstalling the app first (clearing local KeyStore tokens and cache).

1. Create the signing directory (`0700`) and generate a 2048-bit RSA (or EC) release keystore:
   ```bash
   install -d -m 0700 ~/.config/gravity-well-signing
   /home/jason/.jdks/ms-21.0.9/bin/keytool -genkeypair \
     -v \
     -keystore ~/.config/gravity-well-signing/gravity-well-release.jks \
     -alias gravitywell \
     -keyalg RSA \
     -keysize 2048 \
     -validity 10000
   chmod 0600 ~/.config/gravity-well-signing/gravity-well-release.jks
   ```

2. Write `~/.config/gravity-well-signing/key.properties` (`0600`) with the exact property names expected by `android/app/build.gradle.kts` (replace the placeholders locally without printing secrets):
   ```ini
   storeFile=/home/jason/.config/gravity-well-signing/gravity-well-release.jks
   storePassword=<YOUR_STORE_PASSWORD>
   keyAlias=gravitywell
   keyPassword=<YOUR_KEY_PASSWORD>
   ```
   ```bash
   chmod 0600 ~/.config/gravity-well-signing/key.properties
   ```

### 4.3 Build & Verify Debug and Release APKs

```bash
cd /home/jason/projects/gravity-well/android
JAVA_HOME=/home/jason/.jdks/ms-21.0.9 ANDROID_HOME=/home/jason/Android/Sdk \
  ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease \
  --no-daemon --max-workers=1 -Dorg.gradle.parallel=false
JAVA_HOME=/home/jason/.jdks/ms-21.0.9 ANDROID_HOME=/home/jason/Android/Sdk ./gradlew --stop
```

Artifacts produced:
- Debug APK (`us.snakepit.gravitywell.debug`): `android/app/build/outputs/apk/debug/app-debug.apk`
- Release APK (`us.snakepit.gravitywell`): `android/app/build/outputs/apk/release/app-release.apk`

Inspect the release APK before sideloading:
```bash
AAPT2=/home/jason/Android/Sdk/build-tools/36.1.0/aapt2
APKSIGNER=/home/jason/Android/Sdk/build-tools/36.1.0/apksigner
REL_APK=/home/jason/projects/gravity-well/android/app/build/outputs/apk/release/app-release.apk

# 1. Verify applicationId, versionCode, minSdk=26, targetSdk=36, and INTERNET permission
$AAPT2 dump badging "$REL_APK" | head -n 15
$AAPT2 dump permissions "$REL_APK" | grep android.permission.INTERNET

# 2. Verify usesCleartextTraffic=false in compiled release manifest
$AAPT2 dump xmltree --file AndroidManifest.xml "$REL_APK" | grep -E 'package=|usesCleartextTraffic|launchMode|foregroundServiceType'

# 3. Verify signing certificate (for production sideload, ensure DN is NOT CN=Android Debug)
PATH=/home/jason/.jdks/ms-21.0.9/bin:$PATH $APKSIGNER verify --print-certs "$REL_APK"
sha256sum "$REL_APK"
```

### 4.4 Sideload to Android Device

Debug builds use `us.snakepit.gravitywell.debug`, so they install side-by-side without overwriting the production `us.snakepit.gravitywell` release install.

#### Option A: Wireless ADB (Samsung / Android 11+)
```bash
export PATH="$HOME/Android/Sdk/platform-tools:$PATH"

# Discover current wireless debugging port via mDNS (avahi)
avahi-browse -rtp _adb-tls-connect._tcp | grep '^=' | grep IPv4
adb connect <PHONE_IP>:<PORT>
adb devices -l

# Install to personal profile (--user 0 avoids work-profile SecurityException on Samsung devices)
adb -s <PHONE_IP>:<PORT> install --user 0 -r /home/jason/projects/gravity-well/android/app/build/outputs/apk/release/app-release.apk
```

#### Option B: USB ADB or Direct File Transfer
- **USB:** Connect phone with USB debugging enabled, verify `adb devices -l`, and run `adb -s <SERIAL> install --user 0 -r android/app/build/outputs/apk/release/app-release.apk`.
- **Manual sideload:** Copy `app-release.apk` to the phone (e.g., over Taildrop `tailscale file cp android/app/build/outputs/apk/release/app-release.apk <phone>:`) and open it from the Files app to install.

---

## 5. Verification, E2E Loopback Bench & Operational Controls

### 5.1 Run Hermetic Gateway Unit & Live Loopback E2E Suite

The full `pytest` suite includes `gateway/tests/test_e2e_live.py`, which launches a hermetic `uvicorn` instance on `127.0.0.1:9341` backed by a `$TMPDIR` `HOME` sandbox and `FakeAgy` (never touching port `9340`, `~/.gemini/`, or live `systemd`):

```bash
cd /home/jason/projects/gravity-well/gateway
env -u PYTHONPATH uv run pytest -v
```

Verify port `9341` is cleanly released afterward:
```bash
ss -tlnp | grep 9341 || echo "9341 free"
```

### 5.2 Security & Operational Controls

- **Sanctioned Antigravity Posture:** Gateway only invokes `/home/jason/.local/bin/agy` (`agy acp --stdio` and documented CLI subcommands) with `shell=False` and an allowlisted child environment (`ANTIGRAVITY_ENV_STRICT=1`, stripping `*_KEY`, `*_TOKEN`, `*_SECRET`, `PYTHONPATH`, `GRAVITY_WELL_*`). All SQLite reads against `conversation_summaries.db` use `?mode=ro` + `PRAGMA query_only = ON`.
- **Secret Redaction:** Observatory logs (`/api/observatory/logs`), updater output, ACP error events, and `brain/` text/log artifacts pass through `redaction.redact_text()` (`?raw=1` only bypasses redaction for non-log binary artifacts such as PNG/PDF).
- **Allowlisted Settings:** `/api/ecosystem/settings` only reads/writes `general.previewFeatures` and `general.enablePromptCompletion` atomically in `~/.gemini/settings.json`; `security.*`, `oauth*`, and `auth*` keys are forbidden (`403`).
- **In-App Revocation Paths:**
  - **Workspace Permission Rules:** Control Deck -> Permission Rules -> Revoke (`DELETE /api/permissions/rules/{rule_id}`).
  - **Keepalive & Notifications:** Control Deck -> Background Keepalive toggle (stops `GravityWellKeepaliveService`, cancels active notifications, and provides direct link to Android App Notification Settings).
