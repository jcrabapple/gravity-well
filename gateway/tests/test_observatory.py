"""Tests for Observatory: TTL-cached /usage, presence/ liveness radar, vitals, redacted logs, daemon, and confirm-gated update."""

from __future__ import annotations

import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import time
from typing import Any

import pytest
from starlette.testclient import TestClient

from conftest import SandboxEnv, seed_summaries_db
from gravity_well_gateway.config import GatewayConfig
from gravity_well_gateway.main import create_app


def _install_observatory_agy_shim(sandbox: SandboxEnv, tmp_path: Path) -> tuple[Path, Path]:
    """Install a deterministic CLI shim alongside FakeAgy ACP support and record every invocation."""
    trace_file = tmp_path / "obs_cli_trace.jsonl"
    state_file = tmp_path / "obs_cli_state.json"
    state_file.write_text(
        json.dumps(
            {
                "version": "1.5.15",
                "remote_active": False,
                "remote_name": None,
                "usage_calls": 0,
                "usage_fail": False,
            }
        ),
        encoding="utf-8",
    )
    orig_fake = tmp_path / "bin" / "fake_agy_orig"
    orig_fake.write_text(sandbox.fake_agy_bin.read_text(encoding="utf-8"), encoding="utf-8")
    orig_fake.chmod(orig_fake.stat().st_mode | stat.S_IXUSR)

    shim_code = f'''#!{sys.executable}
import json, os, sys
from pathlib import Path

TRACE = Path({str(trace_file)!r})
STATE = Path({str(state_file)!r})
ORIG = {str(orig_fake)!r}

# Enforce child environment scrub contract on every CLI invocation
assert os.environ.get("ANTIGRAVITY_ENV_STRICT") == "1", "Missing ANTIGRAVITY_ENV_STRICT=1"
for forbidden in ("OPENAI_API_KEY", "AWS_SECRET_ACCESS_KEY", "GITHUB_TOKEN", "MY_CUSTOM_SECRET", "PYTHONPATH"):
    assert forbidden not in os.environ, f"Secret env leaked: {{forbidden}}"

argv = sys.argv[1:]
with TRACE.open("a", encoding="utf-8") as fh:
    fh.write(json.dumps({{"argv": argv, "home": os.environ.get("HOME")}}) + "\\n")

if argv and argv[0] == "acp":
    os.execv(ORIG, [ORIG, *argv])

st = json.loads(STATE.read_text(encoding="utf-8"))

if argv == ["--version"]:
    print(f"agy {{st['version']}}")
    sys.exit(0)

if argv == ["-p", "/usage", "--output-format", "json"]:
    st["usage_calls"] = int(st.get("usage_calls", 0)) + 1
    STATE.write_text(json.dumps(st), encoding="utf-8")
    if st.get("usage_fail"):
        sys.stderr.write("upstream quota error\\n")
        sys.exit(1)
    payload = {{
        "conversation_id": "",
        "status": "SUCCESS",
        "response": "Gemini Models\\tWeekly Limit Remaining\\t89%\\t2026-11-28T15:37:52Z",
        "command": {{
            "name": "usage",
            "data": {{
                "description": "Within each group, models share a weekly limit.",
                "groups": [
                    {{
                        "name": "Gemini Models",
                        "description": "Gemini 4 Neon, Gemini 4 Hydrogen",
                        "buckets": [
                            {{
                                "id": "gemini-weekly",
                                "name": "Weekly Limit Remaining",
                                "window": "weekly",
                                "remaining_fraction": 0.890872,
                                "reset_time": "2026-11-28T15:37:52Z",
                            }},
                            {{
                                "id": "gemini-5h",
                                "name": "Five Hour Limit Remaining",
                                "window": "5h",
                                "remaining_fraction": 0.5,
                                "reset_time": "2026-11-23T19:00:00Z",
                            }},
                        ],
                    }}
                ],
            }},
        }},
    }}
    print(json.dumps(payload))
    sys.exit(0)

if argv[:2] == ["remote-control", "status"]:
    status_str = "active" if st.get("remote_active") else "inactive"
    inst = st.get("remote_name") or "(not assigned)"
    print(f"Daemon status: {{status_str}}")
    print(f"Instance name: {{inst}}")
    print("Logs: journalctl --user -u antigravity-cli-daemon.service -n 50")
    sys.exit(0)

if argv[:2] == ["remote-control", "start"]:
    st["remote_active"] = True
    if "--name" in argv:
        idx = argv.index("--name")
        st["remote_name"] = argv[idx + 1]
    STATE.write_text(json.dumps(st), encoding="utf-8")
    print("Remote control daemon started.")
    sys.exit(0)

if argv[:2] == ["remote-control", "stop"]:
    st["remote_active"] = False
    STATE.write_text(json.dumps(st), encoding="utf-8")
    print("Remote control daemon stopped.")
    sys.exit(0)

if argv == ["update"]:
    st["version"] = "1.5.16"
    STATE.write_text(json.dumps(st), encoding="utf-8")
    print("Updated agy to 1.5.16 Authorization: Bearer should_redact_tok_12345")
    sys.exit(0)

sys.stderr.write(f"Unknown args: {{argv}}\\n")
sys.exit(2)
'''
    sandbox.fake_agy_bin.write_text(shim_code, encoding="utf-8")
    sandbox.fake_agy_bin.chmod(sandbox.fake_agy_bin.stat().st_mode | stat.S_IXUSR)
    return trace_file, state_file


def _read_trace(trace_file: Path) -> list[dict[str, Any]]:
    if not trace_file.is_file():
        return []
    return [json.loads(line) for line in trace_file.read_text(encoding="utf-8").splitlines() if line.strip()]


def test_observatory_usage_ttl_cache_and_refresh_bypass(
    sandbox: SandboxEnv,
    auth_headers: dict[str, str],
    tmp_path: Path,
) -> None:
    trace_file, state_file = _install_observatory_agy_shim(sandbox, tmp_path)
    app = create_app(config=GatewayConfig.from_env())

    with TestClient(app) as tc:
        # Requires auth
        assert tc.get("/api/observatory/usage").status_code == 401

        r1 = tc.get("/api/observatory/usage", headers=auth_headers)
        assert r1.status_code == 200
        d1 = r1.json()
        assert d1["status"] == "SUCCESS"
        assert d1["stale"] is False
        assert d1["groups"][0]["name"] == "Gemini Models"
        assert d1["groups"][0]["buckets"][0]["remaining_percent"] == 89.1
        assert d1["groups"][0]["buckets"][1]["remaining_percent"] == 50.0

        # Second call hits TTL cache (no new `agy -p /usage` invocation)
        r2 = tc.get("/api/observatory/usage", headers=auth_headers)
        assert r2.status_code == 200
        assert r2.json()["cached_at"] == d1["cached_at"]
        usage_traces = [t for t in _read_trace(trace_file) if t["argv"][:2] == ["-p", "/usage"]]
        assert len(usage_traces) == 1

        # refresh=true bypasses TTL cache
        r3 = tc.get("/api/observatory/usage?refresh=true", headers=auth_headers)
        assert r3.status_code == 200
        usage_traces_after = [t for t in _read_trace(trace_file) if t["argv"][:2] == ["-p", "/usage"]]
        assert len(usage_traces_after) == 2

        # When cache expires and upstream fails on refresh=false, serves stale cache; refresh=true errors 502
        app.state.observatory._usage_ttl = 0.0
        st = json.loads(state_file.read_text(encoding="utf-8"))
        st["usage_fail"] = True
        state_file.write_text(json.dumps(st), encoding="utf-8")

        r4 = tc.get("/api/observatory/usage", headers=auth_headers)
        assert r4.status_code == 200
        assert r4.json()["stale"] is True

        r5 = tc.get("/api/observatory/usage?refresh=true", headers=auth_headers)
        assert r5.status_code == 502
        assert r5.json()["error"]["code"] == "acp_upstream_error"


def test_observatory_status_vitals_presence_radar_ignores_stale_locks(
    sandbox: SandboxEnv,
    auth_headers: dict[str, str],
    tmp_path: Path,
) -> None:
    _install_observatory_agy_shim(sandbox, tmp_path)

    # Seed 3 stale zero-byte lock files (no process holds flock)
    for stale_id in ("stale-01", "stale-02", "stale-03"):
        (sandbox.agy_cli_dir / "presence" / f"{stale_id}.lock").write_bytes(b"")

    # Seed 1 live flocked lock file held by a child process + archived metadata
    live_sid = "live-ext-session-01"
    live_lock = sandbox.agy_cli_dir / "presence" / f"{live_sid}.lock"
    live_lock.write_bytes(b"")
    live_lock.chmod(0o444)  # Prove O_RDONLY probe works even on read-only lock files

    seed_summaries_db(
        sandbox.agy_cli_dir / "conversation_summaries.db",
        [
            {
                "conversation_id": live_sid,
                "title": "External CLI Refactor",
                "preview": "Running external agy session",
                "workspace_uris": json.dumps([f"file://{sandbox.workspace.resolve()}"]),
            }
        ],
    )
    updater_dir = sandbox.agy_cli_dir / "updater"
    updater_dir.mkdir(parents=True, exist_ok=True)
    (updater_dir / "update_status.json").write_text(
        json.dumps({"success": True, "message": "Already on the latest version."}),
        encoding="utf-8",
    )

    locker_proc = subprocess.Popen(
        [
            sys.executable,
            "-c",
            (
                "import fcntl, os, sys, time; "
                "fd = os.open(sys.argv[1], os.O_RDONLY); "
                "fcntl.flock(fd, fcntl.LOCK_EX); "
                "sys.stdout.write('LOCKED\\n'); sys.stdout.flush(); "
                "time.sleep(30)"
            ),
            str(live_lock),
        ],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    try:
        assert locker_proc.stdout is not None
        assert locker_proc.stdout.readline().strip() == "LOCKED"

        app = create_app(config=GatewayConfig.from_env())
        with TestClient(app) as tc:
            resp = tc.get("/api/observatory/status", headers=auth_headers)
            assert resp.status_code == 200
            data = resp.json()

            host = data["host"]
            assert 0.0 <= host["cpu_percent"] <= 100.0
            assert host["ram_total_mb"] > 0
            assert host["disk_total_gb"] > 0

            assert data["active_external_count"] == 1
            assert data["stale_lock_count"] == 3
            assert len(data["external_sessions"]) == 1
            ext = data["external_sessions"][0]
            assert ext["session_id"] == live_sid
            assert ext["title"] == "External CLI Refactor"
            assert ext["cwd"] == str(sandbox.workspace.resolve())
            assert ext["supervised"] is False

            # Stale locks must remain untouched on disk
            assert (sandbox.agy_cli_dir / "presence" / "stale-01.lock").is_file()
            assert data["remote_control"]["active"] is False
            assert data["updater"]["success"] is True
            assert data["updater"]["agy_version"] == "1.5.15"
    finally:
        live_lock.chmod(0o644)
        locker_proc.terminate()
        locker_proc.wait(timeout=5)


def test_observatory_logs_redaction_and_symlink_containment(
    sandbox: SandboxEnv,
    auth_headers: dict[str, str],
    tmp_path: Path,
) -> None:
    _install_observatory_agy_shim(sandbox, tmp_path)

    log_dir = sandbox.agy_cli_dir / "log"
    log_dir.mkdir(parents=True, exist_ok=True)
    real_log = log_dir / "cli-20261123_100000.log"
    fake_bearer = "secret_bearer_" + "99887766"
    fake_aiza = "AIza" + "SyA1234567890abcdefghijklmnopqr"
    fake_oauth = "ya29." + "a0AfH6SMBx0000000000secret"
    real_log.write_text(
        "\n".join(
            [
                "2026-11-23T10:00:00Z INFO startup complete",
                f"2026-11-23T10:00:01Z WARN header Authorization: Bearer {fake_bearer}",
                f"2026-11-23T10:00:02Z WARN key={fake_aiza} oauth={fake_oauth}",
                '2026-11-23T10:00:03Z ERR {"refresh_token": "rt_secret_val_123"}',
            ]
        )
        + "\n",
        encoding="utf-8",
    )
    cli_link = sandbox.agy_cli_dir / "cli.log"
    cli_link.symlink_to(real_log)

    crashes_dir = sandbox.agy_cli_dir / "crashes"
    crashes_dir.mkdir(parents=True, exist_ok=True)
    crash1 = crashes_dir / "crash-old.txt"
    crash2 = crashes_dir / "crash-new.txt"
    crash1.write_text("old crash", encoding="utf-8")
    crash2.write_text(f"panic MY_SECRET_TOKEN={fake_bearer}\nstack line 2\n", encoding="utf-8")
    os.utime(crash1, (1000, 1000))
    os.utime(crash2, (2000, 2000))

    outside = tmp_path / "outside_secret.log"
    outside.write_text("TOP SECRET OUTSIDE", encoding="utf-8")

    app = create_app(config=GatewayConfig.from_env())
    with TestClient(app) as tc:
        r_cli = tc.get("/api/observatory/logs?source=cli&tail=10", headers=auth_headers)
        assert r_cli.status_code == 200
        body = r_cli.json()
        joined = "\n".join(body["lines"])
        assert fake_bearer not in joined
        assert fake_aiza not in joined
        assert fake_oauth not in joined
        assert "rt_secret_val_123" not in joined
        assert "[REDACTED]" in joined

        # Query filter runs AFTER redaction (cannot oracle-probe raw secrets)
        r_oracle = tc.get(f"/api/observatory/logs?source=cli&q={fake_bearer}", headers=auth_headers)
        assert r_oracle.status_code == 200
        assert r_oracle.json()["lines"] == []

        r_match = tc.get("/api/observatory/logs?source=cli&q=startup", headers=auth_headers)
        assert r_match.status_code == 200
        assert len(r_match.json()["lines"]) == 1

        # Crash logs newest first + redacted + traversal blocked
        r_crash = tc.get("/api/observatory/logs?source=crashes&tail=5", headers=auth_headers)
        assert r_crash.status_code == 200
        cdata = r_crash.json()
        assert cdata["file"] == "crash-new.txt"
        assert cdata["files"][0] == "crash-new.txt"
        assert fake_bearer not in "\n".join(cdata["lines"])
        assert "[REDACTED]" in "\n".join(cdata["lines"])

        r_trav = tc.get("/api/observatory/logs?source=crashes&file=../outside_secret.log", headers=auth_headers)
        assert r_trav.status_code == 403
        assert r_trav.json()["error"]["code"] == "forbidden"

        # Symlink escape on cli.log MUST 403 forbidden
        cli_link.unlink()
        cli_link.symlink_to(outside)
        r_esc = tc.get("/api/observatory/logs?source=cli", headers=auth_headers)
        assert r_esc.status_code == 403
        assert r_esc.json()["error"]["code"] == "forbidden"


def test_observatory_remote_control_and_confirm_gated_update(
    sandbox: SandboxEnv,
    auth_headers: dict[str, str],
    tmp_path: Path,
) -> None:
    trace_file, _ = _install_observatory_agy_shim(sandbox, tmp_path)
    app = create_app(config=GatewayConfig.from_env())

    with TestClient(app) as tc:
        # Reject flag injection on remote-control start name
        bad_rc = tc.post(
            "/api/observatory/remote-control/start",
            headers=auth_headers,
            json={"name": "--evil-flag"},
        )
        assert bad_rc.status_code == 400
        assert bad_rc.json()["error"]["code"] == "bad_request"

        start_r = tc.post(
            "/api/observatory/remote-control/start",
            headers=auth_headers,
            json={"name": "aurora-rig", "session_scoped": True},
        )
        assert start_r.status_code == 200
        assert start_r.json()["active"] is True
        assert start_r.json()["instance_name"] == "aurora-rig"

        stop_r = tc.post("/api/observatory/remote-control/stop", headers=auth_headers)
        assert stop_r.status_code == 200
        assert stop_r.json()["active"] is False

        # Update without confirm=true MUST 400 and never run `agy update`
        no_conf1 = tc.post("/api/observatory/update", headers=auth_headers, json={})
        assert no_conf1.status_code == 400
        assert no_conf1.json()["error"]["code"] == "bad_request"

        no_conf2 = tc.post("/api/observatory/update", headers=auth_headers, json={"confirm": False})
        assert no_conf2.status_code == 400
        assert not any(t["argv"] == ["update"] for t in _read_trace(trace_file))

        # Confirm=true runs update and redacts output
        upd = tc.post("/api/observatory/update", headers=auth_headers, json={"confirm": True})
        assert upd.status_code == 200
        udata = upd.json()
        assert udata["updated"] is True
        assert udata["version_before"] == "1.5.15"
        assert udata["version_after"] == "1.5.16"
        assert "should_redact_tok_12345" not in udata["output"]
        assert "Bearer [REDACTED]" in udata["output"]
