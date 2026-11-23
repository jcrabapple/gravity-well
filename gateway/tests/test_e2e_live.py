"""Live loopback E2E bench (127.0.0.1:9341) verifying full HTTP + WebSocket wire contract."""

from __future__ import annotations

import asyncio
from dataclasses import dataclass
import json
import os
from pathlib import Path
import socket
import stat
import subprocess
import sys
import time
from typing import Any, AsyncIterator

import httpx
import pytest
import websockets
from websockets.exceptions import InvalidStatus

from conftest import seed_summaries_db
from gravity_well_gateway.config import ALLOWED_CHILD_ENV_KEYS, TOKEN_VAR_NAME

BENCH_HOST = "127.0.0.1"
BENCH_PORT = 9341

PNG_1X1 = (
    b"\x89PNG\r\n\x1a\n"
    b"\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08\x06\x00\x00\x00\x1f\x15\xc4\x89"
    b"\x00\x00\x00\nIDATx\x9cc\x00\x01\x00\x00\x05\x00\x01\r\n-\xb4"
    b"\x00\x00\x00\x00IEND\xaeB`\x82"
)


@dataclass
class LiveBench:
    base_url: str
    ws_url: str
    home: Path
    workspace: Path
    extra_dir: Path
    config_dir: Path
    gemini_dir: Path
    agy_cli_dir: Path
    env_file: Path
    token: str
    trace_file: Path
    proc: subprocess.Popen[str]


def _port_is_open(host: str, port: int) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.settimeout(0.25)
        return s.connect_ex((host, port)) == 0


def _install_unified_fake_agy(bin_dir: Path, tmp_path: Path) -> tuple[Path, Path, Path]:
    """Install unified FakeAgy supporting ACP stdio + all CLI subcommands with strict env checks."""
    bin_dir.mkdir(parents=True, exist_ok=True)
    orig_fake = bin_dir / "fake_agy_acp.py"
    orig_src = Path(__file__).parent / "fake_agy.py"
    orig_fake.write_text(orig_src.read_text(encoding="utf-8"), encoding="utf-8")
    orig_fake.chmod(orig_fake.stat().st_mode | stat.S_IXUSR)

    trace_file = tmp_path / "e2e_cli_trace.jsonl"
    state_file = tmp_path / "e2e_cli_state.json"
    state_file.write_text(
        json.dumps(
            {
                "version": "1.5.15",
                "remote_active": False,
                "remote_name": None,
                "usage_calls": 0,
                "plugins": [],
            }
        ),
        encoding="utf-8",
    )

    shim = bin_dir / "fake_agy"
    shim_code = f'''#!{sys.executable}
import json, os, sys
from pathlib import Path

TRACE = Path({str(trace_file)!r})
STATE = Path({str(state_file)!r})
ORIG = {str(orig_fake)!r}

assert os.environ.get("ANTIGRAVITY_ENV_STRICT") == "1", "Missing ANTIGRAVITY_ENV_STRICT=1"
for forbidden in (
    "OPENAI_API_" + "KEY",
    "AWS_SECRET_ACCESS_" + "KEY",
    "GITHUB_" + "TOKEN",
    "MY_CUSTOM_" + "SECRET",
    "PYTHONPATH",
    "GRAVITY_WELL_" + "TOKEN",
):
    assert forbidden not in os.environ, f"Secret env leaked to agy child: {{forbidden}}"

argv = sys.argv[1:]
with TRACE.open("a", encoding="utf-8") as fh:
    fh.write(json.dumps({{"argv": argv, "cwd": os.getcwd(), "home": os.environ.get("HOME")}}) + "\\n")

if argv and argv[0] == "acp":
    os.execv(ORIG, [ORIG, *argv])

st = json.loads(STATE.read_text(encoding="utf-8"))
home = Path(os.environ["HOME"])
mcp_cfg_path = home / ".gemini" / "config" / "mcp_config.json"

if argv == ["--version"]:
    print(f"agy {{st['version']}}")
    sys.exit(0)

if argv == ["-p", "/usage", "--output-format", "json"]:
    st["usage_calls"] = int(st.get("usage_calls", 0)) + 1
    STATE.write_text(json.dumps(st), encoding="utf-8")
    print(json.dumps({{
        "conversation_id": "",
        "status": "SUCCESS",
        "command": {{
            "name": "usage",
            "data": {{
                "description": "Within each group, models share a weekly limit.",
                "groups": [
                    {{
                        "name": "Gemini Models",
                        "description": "Gemini 4 Neon, Gemini 3.8 Flash",
                        "buckets": [
                            {{
                                "id": "gemini-weekly",
                                "name": "Weekly Limit Remaining",
                                "window": "weekly",
                                "remaining_fraction": 0.912,
                                "reset_time": "2026-11-28T15:37:52Z",
                            }}
                        ],
                    }}
                ],
            }},
        }},
    }}))
    sys.exit(0)

if argv[:2] == ["remote-control", "status"]:
    status_str = "active" if st.get("remote_active") else "inactive"
    inst = st.get("remote_name") or "(not assigned)"
    print(f"Daemon status: {{status_str}}")
    print(f"Instance name: {{inst}}")
    sys.exit(0)

if argv[:2] == ["remote-control", "start"]:
    st["remote_active"] = True
    if "--name" in argv:
        st["remote_name"] = argv[argv.index("--name") + 1]
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
    leak_val = "live_" + "update_secret_9911"
    print(f"Updated agy to 1.5.16 Authorization: Bearer {{leak_val}}")
    sys.exit(0)

if argv == ["models"]:
    print("gemini-4-neon-high\\tGemini 4 Neon (High) (default)")
    print("gemini-3.8-flash-medium\\tGemini 3.8 Flash (Medium)")
    sys.exit(0)

if argv == ["agent"]:
    print("builtin-planner")
    sys.exit(0)

if argv[:2] == ["mcp", "list"]:
    cfg = json.loads(mcp_cfg_path.read_text(encoding="utf-8")) if mcp_cfg_path.is_file() else {{"mcpServers": {{}}}}
    servers = cfg.get("mcpServers", {{}})
    if not servers:
        print("No MCP servers configured.")
    else:
        print("NAME    TYPE    STATUS    COMMAND/URL")
        for n, entry in servers.items():
            stype = "http" if "serverUrl" in entry else "stdio"
            st_lbl = "disabled" if entry.get("disabled") else "enabled"
            tgt = entry.get("serverUrl") or entry.get("command") or ""
            print(f"{{n}}  {{stype}}  {{st_lbl}}  {{tgt}}")
    sys.exit(0)

if argv[:2] == ["mcp", "add"]:
    idx = 2
    srv_type = "stdio"
    env_map = {{}}
    hdr_map = {{}}
    while idx < len(argv) and argv[idx].startswith("-"):
        flag = argv[idx]
        if flag in ("--type", "-t"):
            srv_type = argv[idx + 1]
            idx += 2
        elif flag in ("-e", "--env"):
            k, v = argv[idx + 1].split("=", 1)
            env_map[k] = v
            idx += 2
        elif flag in ("-H", "--header"):
            k, v = argv[idx + 1].split(":", 1)
            hdr_map[k.strip()] = v.strip()
            idx += 2
        else:
            sys.stderr.write(f"unknown flag {{flag}}\\n")
            sys.exit(2)
    name = argv[idx]
    target = argv[idx + 1]
    extra_args = argv[idx + 2:]
    cfg = json.loads(mcp_cfg_path.read_text(encoding="utf-8")) if mcp_cfg_path.is_file() else {{"mcpServers": {{}}}}
    servers = cfg.setdefault("mcpServers", {{}})
    if srv_type == "http":
        servers[name] = {{"disabled": False, "serverUrl": target, "headers": hdr_map}}
    else:
        servers[name] = {{"disabled": False, "command": target, "args": extra_args, "env": env_map}}
    mcp_cfg_path.parent.mkdir(parents=True, exist_ok=True)
    mcp_cfg_path.write_text(json.dumps(cfg, indent=2), encoding="utf-8")
    print(f'Added MCP server "{{name}}" ({{srv_type}})')
    sys.exit(0)

if argv[:2] in (["mcp", "enable"], ["mcp", "disable"], ["mcp", "remove"]):
    action, name = argv[1], argv[2]
    cfg = json.loads(mcp_cfg_path.read_text(encoding="utf-8")) if mcp_cfg_path.is_file() else {{"mcpServers": {{}}}}
    servers = cfg.setdefault("mcpServers", {{}})
    if name not in servers:
        sys.stderr.write(f'Error: MCP server "{{name}}" not found\\n')
        sys.exit(1)
    if action == "remove":
        del servers[name]
    else:
        servers[name]["disabled"] = (action == "disable")
    mcp_cfg_path.write_text(json.dumps(cfg, indent=2), encoding="utf-8")
    sys.exit(0)

if argv == ["plugin", "list", "--json"]:
    print(json.dumps(st.get("plugins", [])))
    sys.exit(0)

if argv[:2] == ["plugin", "validate"]:
    target = [a for a in argv[2:] if not a.startswith("-")][0]
    print(json.dumps({{
        "dir": target,
        "manifest": "plugin.json",
        "name": Path(target).name or "demo-plugin",
        "findings": [],
    }}))
    sys.exit(0)

if argv[:2] == ["plugin", "install"]:
    target = argv[2]
    pname = Path(target).name
    items = [x for x in st.get("plugins", []) if x["name"] != pname]
    items.append({{"name": pname, "enabled": True, "version": "1.0.0", "source": "antigravity", "path": target}})
    st["plugins"] = items
    STATE.write_text(json.dumps(st), encoding="utf-8")
    print(f"  [ok]    {{pname}}")
    sys.exit(0)

if argv[:2] in (["plugin", "enable"], ["plugin", "disable"], ["plugin", "uninstall"]):
    action, name = argv[1], argv[2]
    items = st.get("plugins", [])
    if not any(x["name"] == name for x in items):
        sys.stderr.write(f'Error: plugin "{{name}}" not found\\n')
        sys.exit(1)
    if action == "uninstall":
        items = [x for x in items if x["name"] != name]
    else:
        for x in items:
            if x["name"] == name:
                x["enabled"] = (action == "enable")
    st["plugins"] = items
    STATE.write_text(json.dumps(st), encoding="utf-8")
    sys.exit(0)

sys.stderr.write(f"Unsupported fake_agy subcommand: {{argv}}\\n")
sys.exit(2)
'''
    shim.write_text(shim_code, encoding="utf-8")
    shim.chmod(shim.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
    return shim, trace_file, state_file


def _read_minted_token(env_file: Path) -> str:
    for line in env_file.read_text(encoding="utf-8").splitlines():
        if line.startswith(f"{TOKEN_VAR_NAME}="):
            return line.split("=", 1)[1].strip()
    raise AssertionError(f"{TOKEN_VAR_NAME} not found in {env_file}")


async def _recv_until(ws: Any, predicate: Any, timeout: float = 8.0) -> list[dict[str, Any]]:
    frames: list[dict[str, Any]] = []
    deadline = time.monotonic() + timeout
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError(f"Timed out waiting for WS frame; seen={[f.get('type') or f.get('frame') for f in frames]}")
        raw = await asyncio.wait_for(ws.recv(), timeout=remaining)
        frame = json.loads(raw)
        frames.append(frame)
        if predicate(frame):
            return frames


async def _wait_status_http(
    hc: httpx.AsyncClient,
    headers: dict[str, str],
    sid: str,
    targets: set[str],
    timeout: float = 8.0,
) -> dict[str, Any]:
    deadline = time.monotonic() + timeout
    last: dict[str, Any] = {}
    while time.monotonic() < deadline:
        r = await hc.get(f"/api/sessions/{sid}", headers=headers)
        assert r.status_code == 200
        last = r.json()
        if last.get("status") in targets:
            return last
        await asyncio.sleep(0.03)
    raise AssertionError(f"Session {sid} stayed in {last.get('status')}, expected {targets}")


@pytest.fixture
async def live_bench(tmp_path: Path) -> AsyncIterator[LiveBench]:
    """Start an isolated loopback uvicorn gateway on 127.0.0.1:9341 backed by tmp_path HOME."""
    assert not _port_is_open(BENCH_HOST, BENCH_PORT), (
        f"Loopback bench port {BENCH_HOST}:{BENCH_PORT} is already in use"
    )

    home = tmp_path / "bench_home"
    workspace = home / "projects" / "bench-app"
    extra_dir = home / "projects" / "shared-lib"
    config_dir = home / ".config" / "gravity-well"
    gemini_dir = home / ".gemini"
    agy_cli_dir = gemini_dir / "antigravity-cli"
    env_file = config_dir / "gateway.env"

    for d in (
        home,
        workspace,
        extra_dir,
        config_dir,
        gemini_dir / "agents",
        agy_cli_dir / "brain",
        agy_cli_dir / "presence",
        agy_cli_dir / "log",
        agy_cli_dir / "crashes",
        agy_cli_dir / "updater",
        agy_cli_dir / "builtin" / "plugins" / "swarm",
    ):
        d.mkdir(parents=True, exist_ok=True)

    (workspace / "src").mkdir(parents=True, exist_ok=True)
    (workspace / "src" / "main.py").write_text("print('gravity well')\n", encoding="utf-8")
    outside_secret = tmp_path / "outside_host_secret.txt"
    outside_secret.write_text("DO_NOT_ESCAPE", encoding="utf-8")
    (workspace / "src" / "escape.py").symlink_to(outside_secret)

    plugin_dir = workspace / "demo-plugin"
    plugin_dir.mkdir(parents=True, exist_ok=True)
    (plugin_dir / "plugin.json").write_text(
        json.dumps({"name": "demo-plugin", "displayName": "Demo Plugin"}),
        encoding="utf-8",
    )
    (agy_cli_dir / "builtin" / "plugins" / "swarm" / "plugin.json").write_text(
        json.dumps({"name": "swarm", "displayName": "Swarm"}),
        encoding="utf-8",
    )

    # Seed archived FTS5 conversation DB (?mode=ro)
    seed_summaries_db(
        agy_cli_dir / "conversation_summaries.db",
        [
            {
                "conversation_id": "arch-e2e-0001",
                "title": "Archived Loopback Baseline",
                "preview": "Checked HMAC bearer validation",
                "user_body": "Inspect gateway auth ticket lifecycle",
                "agent_body": "Single use 30s ticket verified",
                "step_count": 5,
                "last_modified_time": "2026-11-22T18:00:00Z",
                "workspace_uris": json.dumps([f"file://{workspace.resolve()}"]),
            }
        ],
    )

    # Seed settings.json with both allowlisted keys and forbidden secrets
    (gemini_dir / "settings.json").write_text(
        json.dumps(
            {
                "security": {"auth": {"selectedType": "oauth-personal", "token": "oauth-raw-do-not-leak"}},
                "general": {"previewFeatures": True, "enablePromptCompletion": False},
                "customUntouched": {"keep": "preserved"},
            },
            indent=2,
        ),
        encoding="utf-8",
    )

    # Seed cli.log, crashes/, and updater/ with planted secrets for redaction proof
    fake_bearer = "e2e_bearer_" + "secret998877"
    fake_aiza = "AIza" + "SyE2eSecretKey1234567890abcdefg"
    fake_oauth = "ya29." + "a0AfH6SMBxE2eOauthSecret12345"
    fake_sk = "sk-ant-" + "api03-e2eSecretAnthropicKey1234"
    log_file = agy_cli_dir / "log" / "cli-e2e.log"
    log_file.write_text(
        "\n".join(
            [
                "2026-11-23T17:00:00Z INFO gateway bench booting",
                f"2026-11-23T17:00:01Z WARN Authorization: Bearer {fake_bearer}",
                f"2026-11-23T17:00:02Z WARN key={fake_aiza} oauth={fake_oauth} sk={fake_sk}",
            ]
        )
        + "\n",
        encoding="utf-8",
    )
    (agy_cli_dir / "cli.log").symlink_to(log_file)
    (agy_cli_dir / "crashes" / "crash-e2e.txt").write_text(
        f"panic MY_SECRET_TOKEN={fake_bearer}\nstack frame 1\n",
        encoding="utf-8",
    )
    (agy_cli_dir / "updater" / "update_status.json").write_text(
        json.dumps({"success": True, "message": "Up to date"}),
        encoding="utf-8",
    )

    fake_agy_bin, trace_file, _ = _install_unified_fake_agy(tmp_path / "bin", tmp_path)

    gateway_root = Path(__file__).resolve().parent.parent
    srv_env = {
        "PATH": os.environ["PATH"],
        "HOME": str(home),
        "USER": os.environ.get("USER", "jason"),
        "LANG": "C.UTF-8",
        "LC_ALL": "C.UTF-8",
        "GRAVITY_WELL_CONFIG_DIR": str(config_dir),
        "GRAVITY_WELL_ENV_FILE": str(env_file),
        "GRAVITY_WELL_GEMINI_DIR": str(gemini_dir),
        "GRAVITY_WELL_AGY_CLI_DIR": str(agy_cli_dir),
        "GRAVITY_WELL_AGY_BIN": str(fake_agy_bin),
        "GRAVITY_WELL_DEFAULT_CWD": str(workspace),
        "GRAVITY_WELL_ALLOWED_ROOTS": str(home),
        # Canary host secrets that MUST be stripped before spawning FakeAgy
        "OPENAI_API_" + "KEY": "canary-live-openai",
        "AWS_SECRET_ACCESS_" + "KEY": "canary-live-aws",
        "GITHUB_" + "TOKEN": "canary-live-gh",
        "PYTHONPATH": "/tmp/poisoned-live-pythonpath",
    }

    proc = subprocess.Popen(
        [
            sys.executable,
            "-m",
            "uvicorn",
            "gravity_well_gateway.main:app",
            "--host",
            BENCH_HOST,
            "--port",
            str(BENCH_PORT),
            "--log-level",
            "warning",
        ],
        cwd=str(gateway_root),
        env=srv_env,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )

    base_url = f"http://{BENCH_HOST}:{BENCH_PORT}"
    ws_url = f"ws://{BENCH_HOST}:{BENCH_PORT}"

    try:
        deadline = time.monotonic() + 15.0
        async with httpx.AsyncClient(base_url=base_url, timeout=3.0) as probe:
            while True:
                if proc.poll() is not None:
                    out, err = proc.communicate(timeout=2)
                    raise AssertionError(
                        f"Loopback gateway exited prematurely rc={proc.returncode}\nstdout={out}\nstderr={err}"
                    )
                try:
                    r = await probe.get("/api/health")
                    if r.status_code == 200 and env_file.is_file():
                        break
                except httpx.HTTPError:
                    pass
                if time.monotonic() > deadline:
                    raise TimeoutError("Timed out waiting for 127.0.0.1:9341 /api/health")
                await asyncio.sleep(0.05)

        assert stat.S_IMODE(os.stat(env_file).st_mode) == 0o600
        token = _read_minted_token(env_file)
        assert len(token) >= 32

        yield LiveBench(
            base_url=base_url,
            ws_url=ws_url,
            home=home,
            workspace=workspace,
            extra_dir=extra_dir,
            config_dir=config_dir,
            gemini_dir=gemini_dir,
            agy_cli_dir=agy_cli_dir,
            env_file=env_file,
            token=token,
            trace_file=trace_file,
            proc=proc,
        )
    finally:
        if proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait(timeout=5)
        assert not _port_is_open(BENCH_HOST, BENCH_PORT), "127.0.0.1:9341 remained bound after teardown"


@pytest.mark.asyncio
async def test_live_loopback_e2e_wire_contract(live_bench: LiveBench, tmp_path: Path) -> None:
    headers = {"Authorization": f"Bearer {live_bench.token}"}

    async with httpx.AsyncClient(base_url=live_bench.base_url, timeout=10.0) as hc:
        # ── 1. Health & Auth 401s + Query-Token Rejection ─────────────────────
        health = await hc.get("/api/health")
        assert health.status_code == 200
        h_json = health.json()
        assert h_json["status"] == "ok"
        assert h_json["service"] == "gravity-well-gateway"
        assert h_json["agy_version"] == "1.5.15"
        assert h_json["protocol_version"] == 1
        assert h_json["auth_required"] is True
        assert str(live_bench.home) not in health.text
        assert live_bench.token not in health.text

        assert (await hc.get("/api/info")).status_code == 401
        assert (await hc.get("/api/info", headers={"Authorization": "Bearer wrong"})).status_code == 401
        for qs_key in ("token", "bearer", "auth", "ticket"):
            q_bad = await hc.get(f"/api/info?{qs_key}={live_bench.token}", headers=headers)
            assert q_bad.status_code == 401
            assert q_bad.json()["error"]["code"] == "unauthorized"
            assert (await hc.get(f"/api/health?{qs_key}={live_bench.token}")).status_code == 401

        info = await hc.get("/api/info", headers=headers)
        assert info.status_code == 200
        info_json = info.json()
        assert info_json["default_cwd"] == str(live_bench.workspace.resolve())
        assert info_json["capabilities"]["fork"] is False
        assert info_json["capabilities"]["gateway_title_override"] is True

        # ── 2. 30s Single-Use WS Ticket & Pre-Upgrade 401s ────────────────────
        t1_resp = await hc.post("/api/ws-ticket", headers=headers)
        assert t1_resp.status_code == 201
        t1_data = t1_resp.json()
        t1 = t1_data["ticket"]
        assert t1.startswith("gwt_")
        assert t1_data["ttl_seconds"] == 30

        async with websockets.connect(f"{live_bench.ws_url}/api/ws?ticket={t1}") as ws_hello:
            hello = json.loads(await ws_hello.recv())
            assert hello["frame"] == "hello"
            assert hello["agy_version"] == "1.5.15"
            await ws_hello.send(json.dumps({"frame": "ping"}))
            pong = json.loads(await ws_hello.recv())
            assert pong["frame"] == "pong"

        # Reuse of single-use ticket -> HTTP 401
        with pytest.raises(InvalidStatus) as reuse_exc:
            async with websockets.connect(f"{live_bench.ws_url}/api/ws?ticket={t1}"):
                pass
        assert reuse_exc.value.response.status_code == 401

        # Permanent token in WS query string -> HTTP 401
        for bad_ws_qs in (
            f"token={live_bench.token}",
            f"bearer={live_bench.token}",
            f"auth={live_bench.token}",
            f"ticket={live_bench.token}",
        ):
            with pytest.raises(InvalidStatus) as q_exc:
                async with websockets.connect(f"{live_bench.ws_url}/api/ws?{bad_ws_qs}"):
                    pass
            assert q_exc.value.response.status_code == 401

        # Authorization header on WS upgrade succeeds
        async with websockets.connect(
            f"{live_bench.ws_url}/api/ws",
            additional_headers=headers,
        ) as ws_hdr:
            hdr_hello = json.loads(await ws_hdr.recv())
            assert hdr_hello["frame"] == "hello"

        # ── 3. Session Lifecycle + Streaming Thoughts/Diffs + Storage/Files ───
        sess_r = await hc.post(
            "/api/sessions",
            headers=headers,
            json={
                "cwd": str(live_bench.workspace),
                "add_dirs": [str(live_bench.extra_dir)],
                "title": "Live E2E Bench",
                "model": "gemini-4-neon",
                "thought_level": "high",
                "mode": "default",
            },
        )
        assert sess_r.status_code == 201
        snap = sess_r.json()
        sid = snap["session_id"]
        assert snap["is_live"] is True
        assert snap["title"] == "Live E2E Bench"

        # Fork returns 501 acp_method_unsupported
        fork_r = await hc.post(f"/api/sessions/{sid}/fork", headers=headers, json={})
        assert fork_r.status_code == 501
        assert fork_r.json()["error"]["code"] == "acp_method_unsupported"

        # Live config mutation
        cfg_r = await hc.patch(
            f"/api/sessions/{sid}/config",
            headers=headers,
            json={"model": "gemini-3.8-flash", "thought_level": "medium", "mode": "accept-edits"},
        )
        assert cfg_r.status_code == 200
        cfg_map = {o["id"]: o["currentValue"] for o in cfg_r.json()["config_options"]}
        assert cfg_map == {"model": "gemini-3.8-flash", "thought_level": "medium", "mode": "accept-edits"}

        # Upload PNG attachment & reject ELF binary
        up_ok = await hc.post(
            f"/api/sessions/{sid}/attachments",
            headers=headers,
            files={"file": ("probe.png", PNG_1X1, "image/png")},
        )
        assert up_ok.status_code == 201
        att_id = up_ok.json()["attachment_id"]
        up_bad = await hc.post(
            f"/api/sessions/{sid}/attachments",
            headers=headers,
            files={"file": ("evil.bin", b"\x7fELF\x02\x01\x01\x00", "application/octet-stream")},
        )
        assert up_bad.status_code == 415

        # Workspace @file search skips escaped symlink
        files_r = await hc.get(f"/api/sessions/{sid}/files?q=", headers=headers)
        assert files_r.status_code == 200
        file_paths = [x["path"] for x in files_r.json()["items"]]
        assert "src/main.py" in file_paths
        assert "src/escape.py" not in file_paths

        # Archived FTS5 search (?mode=ro) + merged session list
        arch_r = await hc.get("/api/storage/conversations?q=Loopback", headers=headers)
        assert arch_r.status_code == 200
        assert arch_r.json()["items"][0]["conversation_id"] == "arch-e2e-0001"
        all_s = await hc.get("/api/sessions?status=all", headers=headers)
        all_ids = [x["session_id"] for x in all_s.json()["items"]]
        assert all_ids[0] == sid and "arch-e2e-0001" in all_ids

        # Stream thoughts + large tool diff over single-session WS
        t_sess = (await hc.post("/api/ws-ticket", headers=headers)).json()["ticket"]
        async with websockets.connect(f"{live_bench.ws_url}/api/sessions/{sid}/ws?ticket={t_sess}") as ws_s:
            s_hello = json.loads(await ws_s.recv())
            assert s_hello["frame"] == "hello"

            p_resp = await hc.post(
                f"/api/sessions/{sid}/prompt",
                headers=headers,
                json={"text": "[LARGE_FRAME] Inspect diff", "attachment_ids": [att_id]},
            )
            assert p_resp.status_code == 202
            frames = await _recv_until(
                ws_s,
                lambda f: f.get("frame") == "event" and f.get("type") == "turn_completed",
            )
            ev_types = [f["type"] for f in frames if f.get("frame") == "event"]
            assert "agent_thought_chunk" in ev_types
            assert "tool_call" in ev_types
            assert "tool_call_update" in ev_types
            assert "agent_message_chunk" in ev_types
            diff_ev = next(f for f in frames if f.get("type") == "tool_call_update")
            assert len(diff_ev["payload"]["diff"]) > 130_000
            disconnect_cursor = int(frames[-1]["cursor"])

        # ── 4. Cursor Replay Across WS Disconnect ─────────────────────────────
        # Mutate title + run another turn while WS is disconnected
        ren_r = await hc.patch(
            f"/api/sessions/{sid}",
            headers=headers,
            json={"title": "Renamed While Disconnected"},
        )
        assert ren_r.status_code == 200
        assert ren_r.json()["title_source"] == "gateway"

        await hc.post(
            f"/api/sessions/{sid}/prompt",
            headers=headers,
            json={"text": "Missed turn while offline"},
        )
        await _wait_status_http(hc, headers, sid, {"idle"})

        t_replay = (await hc.post("/api/ws-ticket", headers=headers)).json()["ticket"]
        async with websockets.connect(
            f"{live_bench.ws_url}/api/sessions/{sid}/ws?after={disconnect_cursor}&ticket={t_replay}"
        ) as ws_rep:
            rep_hello = json.loads(await ws_rep.recv())
            assert rep_hello["frame"] == "hello"
            rep_frames = await _recv_until(
                ws_rep,
                lambda f: f.get("frame") == "replay_complete",
            )
            replayed_evs = [f for f in rep_frames if f.get("frame") == "event"]
            assert rep_frames[-1]["replayed_count"] == len(replayed_evs)
            assert len(replayed_evs) >= 4
            assert all(int(f["cursor"]) > disconnect_cursor for f in replayed_evs)
            rep_cursors = [int(f["cursor"]) for f in replayed_evs]
            assert rep_cursors == sorted(rep_cursors) and len(set(rep_cursors)) == len(rep_cursors)
            assert any(f["type"] == "session_info_update" for f in replayed_evs)
            assert any(
                f["type"] == "agent_message_chunk" and "Missed turn while offline" in f["payload"]["text"]
                for f in replayed_evs
            )

        # ── 5. Permission Approval (allow_once, allow_rule + revoke, deny) ────
        t_perm_ws = (await hc.post("/api/ws-ticket", headers=headers)).json()["ticket"]
        async with websockets.connect(f"{live_bench.ws_url}/api/ws?ticket={t_perm_ws}") as ws_mux:
            mux_hello = json.loads(await ws_mux.recv())
            assert mux_hello["frame"] == "hello"
            await ws_mux.send(json.dumps({"frame": "subscribe", "session_ids": [sid]}))

            await hc.post(
                f"/api/sessions/{sid}/prompt",
                headers=headers,
                json={"text": "[ASK_PERMISSION:pytest -q]"},
            )
            perm_req_frames = await _recv_until(
                ws_mux,
                lambda f: f.get("frame") == "event" and f.get("type") == "permission_requested",
            )
            req_p1 = perm_req_frames[-1]["payload"]["request_id"]
            res_p1 = await hc.post(
                f"/api/sessions/{sid}/permissions/{req_p1}",
                headers=headers,
                json={"decision": "allow_once"},
            )
            assert res_p1.status_code == 200
            assert res_p1.json()["option_id"] == "allow-once"
            perm_done_frames = await _recv_until(
                ws_mux,
                lambda f: f.get("frame") == "event" and f.get("type") == "turn_completed",
            )
            assert any(f.get("type") == "permission_resolved" for f in perm_done_frames)

        # allow_rule -> auto-approve -> revoke -> deny
        await hc.post(
            f"/api/sessions/{sid}/prompt",
            headers=headers,
            json={"text": "[ASK_PERMISSION:pytest -k smoke]"},
        )
        snap_p2 = await _wait_status_http(hc, headers, sid, {"awaiting_approval"})
        req_p2 = snap_p2["pending_permissions"][0]["request_id"]
        res_p2 = await hc.post(
            f"/api/sessions/{sid}/permissions/{req_p2}",
            headers=headers,
            json={"decision": "allow_rule"},
        )
        assert res_p2.status_code == 200
        rule_id = res_p2.json()["rule_id"]
        assert isinstance(rule_id, str) and rule_id.startswith("rule_")
        await _wait_status_http(hc, headers, sid, {"idle"})

        rules = await hc.get(f"/api/permissions/rules?workspace={live_bench.workspace}", headers=headers)
        assert any(r["rule_id"] == rule_id for r in rules.json()["items"])
        assert (await hc.delete(f"/api/permissions/rules/{rule_id}", headers=headers)).status_code == 200

        await hc.post(
            f"/api/sessions/{sid}/prompt",
            headers=headers,
            json={"text": "[ASK_PERMISSION:rm -rf /tmp/x]"},
        )
        snap_p3 = await _wait_status_http(hc, headers, sid, {"awaiting_approval"})
        req_p3 = snap_p3["pending_permissions"][0]["request_id"]
        res_p3 = await hc.post(
            f"/api/sessions/{sid}/permissions/{req_p3}",
            headers=headers,
            json={"decision": "deny"},
        )
        assert res_p3.status_code == 200
        assert res_p3.json()["option_id"] == "reject-once"
        await _wait_status_http(hc, headers, sid, {"idle"})

        # ── 6. Question Response (elicitation/create) ─────────────────────────
        await hc.post(
            f"/api/sessions/{sid}/prompt",
            headers=headers,
            json={"text": "[ASK_QUESTION]"},
        )
        snap_q = await _wait_status_http(hc, headers, sid, {"awaiting_question"})
        q_id = snap_q["pending_questions"][0]["request_id"]

        q_bad = await hc.post(
            f"/api/sessions/{sid}/questions/{q_id}",
            headers=headers,
            json={"action": "accept", "answers": {"wrong_key": "Blue"}},
        )
        assert q_bad.status_code == 422

        q_ok = await hc.post(
            f"/api/sessions/{sid}/questions/{q_id}",
            headers=headers,
            json={"action": "accept", "answers": {"q_0": "Blue"}},
        )
        assert q_ok.status_code == 200
        assert q_ok.json() == {"request_id": q_id, "resolved": True, "action": "accept"}
        await _wait_status_http(hc, headers, sid, {"idle"})

        # ── 7. Queue send-now (interrupt=true) ────────────────────────────────
        await hc.post(
            f"/api/sessions/{sid}/prompt",
            headers=headers,
            json={"text": "[HOLD:e2e_sendnow] Long turn"},
        )
        q_first = (
            await hc.post(
                f"/api/sessions/{sid}/queue",
                headers=headers,
                json={"text": "Queued normal item"},
            )
        ).json()
        q_urgent = (
            await hc.post(
                f"/api/sessions/{sid}/queue",
                headers=headers,
                json={"text": "Queued urgent send-now"},
            )
        ).json()
        assert q_first["item_id"] != q_urgent["item_id"]

        sn_r = await hc.post(
            f"/api/sessions/{sid}/queue/{q_urgent['item_id']}/send-now",
            headers=headers,
            json={"interrupt": True},
        )
        assert sn_r.status_code == 202
        assert sn_r.json()["status"] == "interrupting"
        await _wait_status_http(hc, headers, sid, {"idle"})

        evs_after_sn = (
            await hc.get(f"/api/sessions/{sid}/events?after=0", headers=headers)
        ).json()["events"]
        user_texts = [e["payload"]["text"] for e in evs_after_sn if e["type"] == "user_message_chunk"]
        idx_urgent = user_texts.index("Queued urgent send-now")
        idx_normal = user_texts.index("Queued normal item")
        assert idx_urgent < idx_normal

        # Close + Resume with load_history=True
        assert (await hc.delete(f"/api/sessions/{sid}", headers=headers)).status_code == 200
        resumed = await hc.post(
            f"/api/sessions/{sid}/resume",
            headers=headers,
            json={"cwd": str(live_bench.workspace), "load_history": True},
        )
        assert resumed.status_code == 200
        assert resumed.json()["is_live"] is True
        assert (await hc.delete(f"/api/sessions/{sid}", headers=headers)).status_code == 200

        # ── 8. Artifact & Log Redaction ───────────────────────────────────────
        art_dir = live_bench.agy_cli_dir / "brain" / sid / ".system_generated" / "logs"
        art_dir.mkdir(parents=True, exist_ok=True)
        planted_secret = "art_secret_" + "xyz987654"
        (art_dir / "run.jsonl").write_text(
            json.dumps({"auth": "Authorization: Bearer " + planted_secret}) + "\n",
            encoding="utf-8",
        )
        art_r = await hc.get(
            f"/api/sessions/{sid}/artifacts/.system_generated/logs/run.jsonl?raw=1",
            headers=headers,
        )
        assert art_r.status_code == 200
        assert art_r.headers.get("x-content-type-options") == "nosniff"
        assert planted_secret not in art_r.text
        assert "[REDACTED]" in art_r.text

        cli_logs = await hc.get("/api/observatory/logs?source=cli&tail=50", headers=headers)
        assert cli_logs.status_code == 200
        cli_joined = "\n".join(cli_logs.json()["lines"])
        assert "e2e_bearer_secret998877" not in cli_joined
        assert "SyE2eSecretKey1234567890abcdefg" not in cli_joined
        assert "a0AfH6SMBxE2eOauthSecret12345" not in cli_joined
        assert "api03-e2eSecretAnthropicKey1234" not in cli_joined
        assert "[REDACTED]" in cli_joined

        # Oracle probe against redacted secret returns empty list
        cli_oracle = await hc.get("/api/observatory/logs?source=cli&q=e2e_bearer_secret998877", headers=headers)
        assert cli_oracle.status_code == 200
        assert cli_oracle.json()["lines"] == []

        crash_logs = await hc.get("/api/observatory/logs?source=crashes&tail=50", headers=headers)
        assert crash_logs.status_code == 200
        assert "e2e_bearer_secret998877" not in "\n".join(crash_logs.json()["lines"])
        assert "[REDACTED]" in "\n".join(crash_logs.json()["lines"])

        # ── 9. Observatory Usage, Presence Radar, Daemon & Update ─────────────
        usage1 = await hc.get("/api/observatory/usage", headers=headers)
        assert usage1.status_code == 200
        assert usage1.json()["groups"][0]["buckets"][0]["remaining_percent"] == 91.2

        # Seed 1 stale lock + 1 live flocked lock for presence radar
        (live_bench.agy_cli_dir / "presence" / "stale-e2e.lock").write_bytes(b"")
        live_lock = live_bench.agy_cli_dir / "presence" / "arch-e2e-0001.lock"
        live_lock.write_bytes(b"")
        locker = subprocess.Popen(
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
            assert locker.stdout is not None and locker.stdout.readline().strip() == "LOCKED"
            status_r = await hc.get("/api/observatory/status", headers=headers)
            assert status_r.status_code == 200
            st_data = status_r.json()
            assert st_data["active_external_count"] == 1
            assert st_data["stale_lock_count"] == 1
            assert st_data["external_sessions"][0]["session_id"] == "arch-e2e-0001"
        finally:
            locker.terminate()
            locker.wait(timeout=5)

        rc_start = await hc.post(
            "/api/observatory/remote-control/start",
            headers=headers,
            json={"name": "e2e-rig"},
        )
        assert rc_start.status_code == 200
        assert rc_start.json()["active"] is True
        assert rc_start.json()["instance_name"] == "e2e-rig"
        rc_stop = await hc.post("/api/observatory/remote-control/stop", headers=headers)
        assert rc_stop.status_code == 200
        assert rc_stop.json()["active"] is False

        assert (await hc.post("/api/observatory/update", headers=headers, json={})).status_code == 400
        upd_ok = await hc.post("/api/observatory/update", headers=headers, json={"confirm": True})
        assert upd_ok.status_code == 200
        assert upd_ok.json()["updated"] is True
        assert upd_ok.json()["version_after"] == "1.5.16"
        assert "live_update_secret_9911" not in upd_ok.json()["output"]
        assert "Bearer [REDACTED]" in upd_ok.json()["output"]

        # ── 10. Ecosystem CRUD (Models, Agents, MCP, Plugins, Settings) ───────
        models_r = await hc.get("/api/ecosystem/models", headers=headers)
        assert models_r.status_code == 200
        assert models_r.json()["default_model_id"] == "gemini-4-neon-high"
        assert (
            await hc.put(
                "/api/ecosystem/models/default",
                headers=headers,
                json={"model": "gemini-3.8-flash", "thought_level": "medium"},
            )
        ).status_code == 200

        ag_c = await hc.post(
            "/api/ecosystem/agents",
            headers=headers,
            json={"name": "e2e-auditor", "description": "E2E Auditor", "prompt": "Audit carefully."},
        )
        assert ag_c.status_code == 201
        ag_l = await hc.get("/api/ecosystem/agents", headers=headers)
        assert any(a["name"] == "e2e-auditor" for a in ag_l.json()["items"])
        assert (await hc.delete("/api/ecosystem/agents/e2e-auditor", headers=headers)).status_code == 200

        mcp_secret = "mcp_e2e_secret_12345"
        mcp_c = await hc.post(
            "/api/ecosystem/mcp",
            headers=headers,
            json={
                "name": "e2e-mcp",
                "type": "stdio",
                "target": "/usr/bin/env",
                "env": {"SERVICE_KEY": mcp_secret},
            },
        )
        assert mcp_c.status_code == 201
        assert mcp_secret not in mcp_c.text
        mcp_l = await hc.get("/api/ecosystem/mcp", headers=headers)
        assert mcp_secret not in mcp_l.text
        assert (await hc.post("/api/ecosystem/mcp/e2e-mcp/disable", headers=headers)).json()["disabled"] is True
        assert (await hc.post("/api/ecosystem/mcp/e2e-mcp/enable", headers=headers)).json()["disabled"] is False
        assert (await hc.delete("/api/ecosystem/mcp/e2e-mcp", headers=headers)).status_code == 200

        pl_dir = live_bench.workspace / "demo-plugin"
        pl_v = await hc.post(
            "/api/ecosystem/plugins/validate",
            headers=headers,
            json={"target": str(pl_dir)},
        )
        assert pl_v.status_code == 200 and pl_v.json()["valid"] is True
        pl_i = await hc.post(
            "/api/ecosystem/plugins/install",
            headers=headers,
            json={"target": str(pl_dir)},
        )
        assert pl_i.status_code == 200 and pl_i.json()["installed"] is True
        assert (await hc.post("/api/ecosystem/plugins/demo-plugin/disable", headers=headers)).json()["enabled"] is False
        assert (await hc.post("/api/ecosystem/plugins/demo-plugin/enable", headers=headers)).json()["enabled"] is True
        assert (await hc.delete("/api/ecosystem/plugins/demo-plugin", headers=headers)).status_code == 200

        set_g = await hc.get("/api/ecosystem/settings", headers=headers)
        assert set_g.status_code == 200
        assert "oauth-raw-do-not-leak" not in set_g.text
        assert (
            await hc.patch(
                "/api/ecosystem/settings",
                headers=headers,
                json={"security": {"auth": {"selectedType": "x"}}},
            )
        ).status_code == 403
        set_p = await hc.patch(
            "/api/ecosystem/settings",
            headers=headers,
            json={"general": {"enablePromptCompletion": True}},
        )
        assert set_p.status_code == 200
        assert set_p.json()["settings"] == {
            "general": {"previewFeatures": True, "enablePromptCompletion": True}
        }
        on_disk = json.loads((live_bench.gemini_dir / "settings.json").read_text(encoding="utf-8"))
        assert on_disk["security"]["auth"]["token"] == "oauth-raw-do-not-leak"
        assert on_disk["customUntouched"] == {"keep": "preserved"}

        # ── 11. Verify FakeAgy Spawns Received Only Scrubbed Env & Sandbox HOME ──
        traces = [
            json.loads(line)
            for line in live_bench.trace_file.read_text(encoding="utf-8").splitlines()
            if line.strip()
        ]
        assert len(traces) >= 10
        assert all(t["home"] == str(live_bench.home.resolve()) for t in traces)

        acp_spawns_path = live_bench.home / ".fake_agy_spawns.jsonl"
        acp_spawns = [
            json.loads(line)
            for line in acp_spawns_path.read_text(encoding="utf-8").splitlines()
            if line.strip()
        ]
        assert len(acp_spawns) >= 1
        allowed_set = set(ALLOWED_CHILD_ENV_KEYS) | {"ANTIGRAVITY_ENV_STRICT", "LC_CTYPE"}
        for rec in acp_spawns:
            assert set(rec["env"].keys()).issubset(allowed_set)
            assert rec["env"]["ANTIGRAVITY_ENV_STRICT"] == "1"
