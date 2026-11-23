"""Tests for Ecosystem CRUD (models, agents, MCP, plugins), allowlisted settings.json, and systemd unit/installer."""

from __future__ import annotations

import json
import os
from pathlib import Path
import stat
import subprocess
import sys
from typing import Any

import pytest
from starlette.testclient import TestClient

from conftest import SandboxEnv
from gravity_well_gateway.config import GatewayConfig
from gravity_well_gateway.main import create_app


def _install_ecosystem_agy_shim(sandbox: SandboxEnv, tmp_path: Path) -> Path:
    """Install deterministic CLI shim for ecosystem CRUD and assert strict child env scrub."""
    trace_file = tmp_path / "eco_cli_trace.jsonl"
    plugins_state = tmp_path / "eco_plugins.json"
    plugins_state.write_text("[]", encoding="utf-8")

    orig_fake = tmp_path / "bin" / "fake_agy_eco_orig"
    orig_fake.write_text(sandbox.fake_agy_bin.read_text(encoding="utf-8"), encoding="utf-8")
    orig_fake.chmod(orig_fake.stat().st_mode | stat.S_IXUSR)

    shim_code = f'''#!{sys.executable}
import json, os, sys
from pathlib import Path

TRACE = Path({str(trace_file)!r})
PLUGINS_STATE = Path({str(plugins_state)!r})
ORIG = {str(orig_fake)!r}

assert os.environ.get("ANTIGRAVITY_ENV_STRICT") == "1", "Missing ANTIGRAVITY_ENV_STRICT=1"
for forbidden in ("OPENAI_API_KEY", "AWS_SECRET_ACCESS_KEY", "GITHUB_TOKEN", "MY_CUSTOM_SECRET", "PYTHONPATH"):
    assert forbidden not in os.environ, f"Secret env leaked: {{forbidden}}"

argv = sys.argv[1:]
with TRACE.open("a", encoding="utf-8") as fh:
    fh.write(json.dumps({{"argv": argv, "home": os.environ.get("HOME")}}) + "\\n")

if argv and argv[0] == "acp":
    os.execv(ORIG, [ORIG, *argv])

home = Path(os.environ["HOME"])
mcp_cfg_path = home / ".gemini" / "config" / "mcp_config.json"

if argv == ["--version"]:
    print("agy 1.5.15")
    sys.exit(0)

if argv == ["models"]:
    print("gemini-4-neon-high\\tGemini 4 Neon (High) (default)")
    print("gemini-4-neon-xhigh\\tGemini 4 Neon (XHigh)")
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
            st = "disabled" if entry.get("disabled") else "enabled"
            tgt = entry.get("serverUrl") or entry.get("command") or ""
            print(f"{{n}}  {{stype}}  {{st}}  {{tgt}}")
    sys.exit(0)

if argv[:2] == ["mcp", "add"]:
    # Parse flags strictly before <name>
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
    action = argv[1]
    name = argv[2]
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
    print(PLUGINS_STATE.read_text(encoding="utf-8"))
    sys.exit(0)

if argv[:2] == ["plugin", "validate"]:
    strict = "--strict" in argv
    target = [a for a in argv[2:] if not a.startswith("-")][0]
    report = {{
        "dir": target,
        "manifest": "plugin.json",
        "name": Path(target).name or "demo-plugin",
        "agentPluginsSpec": False,
        "components": {{"agents": 0, "hooks": 0, "mcpServers": 0, "rules": 0, "sidecars": 0, "skills": 1}},
        "findings": [{{"severity": "warning", "file": "plugin.json", "message": "no version"}}],
    }}
    print(json.dumps(report))
    if strict:
        sys.stderr.write("Error: validation failed in strict mode: 1 warning\\n")
        sys.exit(1)
    sys.exit(0)

if argv[:2] == ["plugin", "install"]:
    target = argv[2]
    if target.startswith("-") or not Path(target).is_dir():
        sys.stderr.write(f"Error: install target must be a directory: {{target}}\\n")
        sys.exit(1)
    pname = Path(target).name
    items = json.loads(PLUGINS_STATE.read_text(encoding="utf-8"))
    items = [x for x in items if x["name"] != pname]
    items.append({{"name": pname, "enabled": True, "version": "1.0.0", "source": "antigravity", "path": target}})
    PLUGINS_STATE.write_text(json.dumps(items), encoding="utf-8")
    print(f"  [ok]    {{pname}}")
    sys.exit(0)

if argv[:2] in (["plugin", "enable"], ["plugin", "disable"], ["plugin", "uninstall"]):
    action = argv[1]
    name = argv[2]
    items = json.loads(PLUGINS_STATE.read_text(encoding="utf-8"))
    found = [x for x in items if x["name"] == name]
    if not found:
        sys.stderr.write(f'Error: plugin "{{name}}" not found or invalid\\n')
        sys.exit(1)
    if action == "uninstall":
        items = [x for x in items if x["name"] != name]
    else:
        for x in items:
            if x["name"] == name:
                x["enabled"] = (action == "enable")
    PLUGINS_STATE.write_text(json.dumps(items), encoding="utf-8")
    sys.exit(0)

sys.stderr.write(f"Unknown eco args: {{argv}}\\n")
sys.exit(2)
'''
    sandbox.fake_agy_bin.write_text(shim_code, encoding="utf-8")
    sandbox.fake_agy_bin.chmod(sandbox.fake_agy_bin.stat().st_mode | stat.S_IXUSR)
    return trace_file


def _read_trace(trace_file: Path) -> list[dict[str, Any]]:
    if not trace_file.is_file():
        return []
    return [json.loads(line) for line in trace_file.read_text(encoding="utf-8").splitlines() if line.strip()]


def test_ecosystem_models_and_gateway_default_prefs(
    sandbox: SandboxEnv,
    auth_headers: dict[str, str],
    tmp_path: Path,
) -> None:
    _install_ecosystem_agy_shim(sandbox, tmp_path)
    app = create_app(config=GatewayConfig.from_env())

    with TestClient(app) as tc:
        assert tc.get("/api/ecosystem/models").status_code == 401

        r_list = tc.get("/api/ecosystem/models", headers=auth_headers)
        assert r_list.status_code == 200
        d = r_list.json()
        assert d["default_model_id"] == "gemini-4-neon-high"
        assert len(d["items"]) == 3
        assert d["items"][0]["family"] == "gemini-4-neon"
        assert d["items"][0]["thought_level"] == "high"
        assert d["items"][0]["is_default"] is True

        # Reject invalid thought_level & flag injection
        bad_tl = tc.put(
            "/api/ecosystem/models/default",
            headers=auth_headers,
            json={"model": "gemini-4-neon", "thought_level": "ultra"},
        )
        assert bad_tl.status_code == 422

        bad_flag = tc.put(
            "/api/ecosystem/models/default",
            headers=auth_headers,
            json={"model": "--flag"},
        )
        assert bad_flag.status_code == 400

        r_set = tc.put(
            "/api/ecosystem/models/default",
            headers=auth_headers,
            json={"model": "gemini-3.8-flash", "thought_level": "medium"},
        )
        assert r_set.status_code == 200
        assert r_set.json() == {"model": "gemini-3.8-flash", "thought_level": "medium"}

        r_list2 = tc.get("/api/ecosystem/models", headers=auth_headers)
        assert r_list2.json()["gateway_default_model"] == "gemini-3.8-flash"
        assert r_list2.json()["gateway_default_thought_level"] == "medium"

        # Reflects into /api/info default_config_options
        info = tc.get("/api/info", headers=auth_headers).json()
        opts = {o["id"]: o["currentValue"] for o in info["default_config_options"]}
        assert opts["model"] == "gemini-3.8-flash"
        assert opts["thought_level"] == "medium"


def test_ecosystem_agents_crud_and_validation(
    sandbox: SandboxEnv,
    auth_headers: dict[str, str],
    tmp_path: Path,
) -> None:
    _install_ecosystem_agy_shim(sandbox, tmp_path)

    # Seed a read-only ~/.gemini/agents/reviewer.md
    gem_agents = sandbox.gemini_dir / "agents"
    gem_agents.mkdir(parents=True, exist_ok=True)
    (gem_agents / "reviewer.md").write_text(
        "---\nname: reviewer\ndescription: Code review specialist\n---\nReview PRs carefully.\n",
        encoding="utf-8",
    )

    app = create_app(config=GatewayConfig.from_env())
    with TestClient(app) as tc:
        c_res = tc.post(
            "/api/ecosystem/agents",
            headers=auth_headers,
            json={"name": "code-auditor", "description": "Audits diffs", "prompt": "Check every line."},
        )
        assert c_res.status_code == 201
        assert (sandbox.agy_cli_dir / "agents" / "code-auditor.md").is_file()

        l_res = tc.get("/api/ecosystem/agents", headers=auth_headers)
        assert l_res.status_code == 200
        by_name = {item["name"]: item for item in l_res.json()["items"]}
        assert set(by_name.keys()) >= {"builtin-planner", "reviewer", "code-auditor"}
        assert by_name["code-auditor"]["editable"] is True
        assert by_name["reviewer"]["editable"] is False

        # Traversal & leading '-' rejected
        assert tc.post(
            "/api/ecosystem/agents",
            headers=auth_headers,
            json={"name": "-bad", "description": "x", "prompt": "y"},
        ).status_code == 400
        assert tc.delete("/api/ecosystem/agents/..%2Fevil", headers=auth_headers).status_code in (403, 404)

        d_res = tc.delete("/api/ecosystem/agents/code-auditor", headers=auth_headers)
        assert d_res.status_code == 200
        assert d_res.json()["deleted"] is True

        assert tc.delete("/api/ecosystem/agents/code-auditor", headers=auth_headers).status_code == 404


def test_ecosystem_mcp_crud_flag_order_and_secret_masking(
    sandbox: SandboxEnv,
    auth_headers: dict[str, str],
    tmp_path: Path,
) -> None:
    trace_file = _install_ecosystem_agy_shim(sandbox, tmp_path)
    app = create_app(config=GatewayConfig.from_env())

    with TestClient(app) as tc:
        # Reject leading '-' on name and target (flag injection guard)
        assert tc.post(
            "/api/ecosystem/mcp",
            headers=auth_headers,
            json={"name": "--evil", "type": "stdio", "target": "/bin/echo"},
        ).status_code == 400
        assert tc.post(
            "/api/ecosystem/mcp",
            headers=auth_headers,
            json={"name": "goodname", "type": "stdio", "target": "--type=http"},
        ).status_code == 400
        assert tc.post("/api/ecosystem/mcp/--evil/enable", headers=auth_headers).status_code == 400

        # Add stdio & http servers with secret env/headers
        add_http = tc.post(
            "/api/ecosystem/mcp",
            headers=auth_headers,
            json={
                "name": "remote-api",
                "type": "http",
                "target": "https://mcp.example.com/v1",
                "headers": {"Authorization": "Bearer raw-http-secret-xyz"},
            },
        )
        assert add_http.status_code == 201
        assert add_http.json()["headers"] == {"Authorization": "[REDACTED]"}
        assert "raw-http-secret-xyz" not in add_http.text

        add_stdio = tc.post(
            "/api/ecosystem/mcp",
            headers=auth_headers,
            json={
                "name": "local-tool",
                "type": "stdio",
                "target": "/usr/bin/env",
                "args": ["--port", "8080"],
                "env": {"SERVICE_API_KEY": "raw-stdio-secret-abc"},
            },
        )
        assert add_stdio.status_code == 201
        assert add_stdio.json()["env"] == {"SERVICE_API_KEY": "[REDACTED]"}
        assert "raw-stdio-secret-abc" not in add_stdio.text

        # Verify flags preceded <name> on `agy mcp add`
        add_traces = [t["argv"] for t in _read_trace(trace_file) if t["argv"][:2] == ["mcp", "add"]]
        assert len(add_traces) == 2
        for argv in add_traces:
            name_idx = next(i for i, a in enumerate(argv[2:], start=2) if a in ("remote-api", "local-tool"))
            assert all(tok.startswith("-") or idx < name_idx for idx, tok in enumerate(argv[:name_idx]))

        # GET masks secrets as [REDACTED] while preserving keys
        list_r = tc.get("/api/ecosystem/mcp", headers=auth_headers)
        assert list_r.status_code == 200
        assert "raw-http-secret-xyz" not in list_r.text
        assert "raw-stdio-secret-abc" not in list_r.text
        by_name = {x["name"]: x for x in list_r.json()["items"]}
        assert by_name["remote-api"]["header_keys"] == ["Authorization"]
        assert by_name["remote-api"]["headers"]["Authorization"] == "[REDACTED]"
        assert by_name["local-tool"]["env_keys"] == ["SERVICE_API_KEY"]
        assert by_name["local-tool"]["env"]["SERVICE_API_KEY"] == "[REDACTED]"

        # Disable / enable / delete
        assert tc.post("/api/ecosystem/mcp/local-tool/disable", headers=auth_headers).json()["disabled"] is True
        assert tc.post("/api/ecosystem/mcp/local-tool/enable", headers=auth_headers).json()["disabled"] is False
        assert tc.delete("/api/ecosystem/mcp/local-tool", headers=auth_headers).json()["deleted"] is True
        assert tc.delete("/api/ecosystem/mcp/local-tool", headers=auth_headers).status_code == 404


def test_ecosystem_plugins_crud_flag_guard_and_path_containment(
    sandbox: SandboxEnv,
    auth_headers: dict[str, str],
    tmp_path: Path,
) -> None:
    _install_ecosystem_agy_shim(sandbox, tmp_path)

    # Builtin plugin + valid local plugin inside allowed sandbox workspace
    builtin_dir = sandbox.agy_cli_dir / "builtin" / "plugins" / "swarm"
    builtin_dir.mkdir(parents=True, exist_ok=True)
    (builtin_dir / "plugin.json").write_text(
        json.dumps({"name": "swarm", "displayName": "Swarm", "description": "Built-in swarm"}),
        encoding="utf-8",
    )

    plugin_dir = sandbox.workspace / "my-plugin"
    plugin_dir.mkdir(parents=True, exist_ok=True)
    (plugin_dir / "plugin.json").write_text(
        json.dumps({"name": "my-plugin", "displayName": "My Plugin"}),
        encoding="utf-8",
    )

    outside_dir = tmp_path / "outside-plugin"
    outside_dir.mkdir(parents=True, exist_ok=True)
    (sandbox.workspace / "escape-plugin").symlink_to(outside_dir)

    app = create_app(config=GatewayConfig.from_env())
    with TestClient(app) as tc:
        # Security boundaries: leading '-' flag injection -> 400 bad_request
        for ep in ("/api/ecosystem/plugins/validate", "/api/ecosystem/plugins/install"):
            r_flag = tc.post(ep, headers=auth_headers, json={"target": "--help"})
            assert r_flag.status_code == 400
            assert r_flag.json()["error"]["code"] == "bad_request"
        assert tc.post("/api/ecosystem/plugins/--evil/enable", headers=auth_headers).status_code == 400

        # Security boundaries: sensitive dotdirs / /etc / symlink escape / outside allowed_roots -> 403 forbidden
        for forbidden_target in (
            str(sandbox.home / ".hermes"),
            str(sandbox.home / ".ssh"),
            str(sandbox.home / ".gnupg"),
            "/etc/passwd",
            "file:///etc/passwd",
            str(outside_dir),
            str(sandbox.workspace / "escape-plugin"),
        ):
            r_inst = tc.post(
                "/api/ecosystem/plugins/install",
                headers=auth_headers,
                json={"target": forbidden_target},
            )
            assert r_inst.status_code == 403, f"Expected 403 for {forbidden_target}, got {r_inst.status_code}"
            assert r_inst.json()["error"]["code"] == "forbidden"

            r_val = tc.post(
                "/api/ecosystem/plugins/validate",
                headers=auth_headers,
                json={"target": forbidden_target},
            )
            assert r_val.status_code == 403

        # Validate non-strict (valid=True) and strict (valid=False with report)
        v1 = tc.post(
            "/api/ecosystem/plugins/validate",
            headers=auth_headers,
            json={"target": str(plugin_dir), "strict": False},
        )
        assert v1.status_code == 200
        assert v1.json()["valid"] is True
        assert v1.json()["report"]["name"] == "my-plugin"

        v2 = tc.post(
            "/api/ecosystem/plugins/validate",
            headers=auth_headers,
            json={"target": str(plugin_dir), "strict": True},
        )
        assert v2.status_code == 200
        assert v2.json()["valid"] is False
        assert v2.json()["report"]["findings"][0]["severity"] == "warning"

        # Install, list (with builtins), disable, enable, uninstall
        inst = tc.post(
            "/api/ecosystem/plugins/install",
            headers=auth_headers,
            json={"target": str(plugin_dir)},
        )
        assert inst.status_code == 200
        assert inst.json()["installed"] is True

        plist = tc.get("/api/ecosystem/plugins", headers=auth_headers).json()
        assert [x["name"] for x in plist["items"]] == ["my-plugin"]
        assert [b["name"] for b in plist["builtins"]] == ["swarm"]

        assert tc.post("/api/ecosystem/plugins/my-plugin/disable", headers=auth_headers).json()["enabled"] is False
        assert tc.post("/api/ecosystem/plugins/my-plugin/enable", headers=auth_headers).json()["enabled"] is True
        assert tc.delete("/api/ecosystem/plugins/my-plugin", headers=auth_headers).json()["deleted"] is True
        assert tc.post("/api/ecosystem/plugins/my-plugin/enable", headers=auth_headers).status_code == 404


def test_ecosystem_settings_allowlist_and_atomic_preservation(
    sandbox: SandboxEnv,
    auth_headers: dict[str, str],
    tmp_path: Path,
) -> None:
    _install_ecosystem_agy_shim(sandbox, tmp_path)

    settings_path = sandbox.gemini_dir / "settings.json"
    initial_settings = {
        "security": {"auth": {"selectedType": "oauth-personal", "token": "secret-do-not-expose"}},
        "general": {"previewFeatures": True, "enablePromptCompletion": False},
        "customUntouched": {"keep": 42},
    }
    settings_path.write_text(json.dumps(initial_settings, indent=2), encoding="utf-8")

    app = create_app(config=GatewayConfig.from_env())
    with TestClient(app) as tc:
        # GET returns ONLY allowlisted keys
        g_res = tc.get("/api/ecosystem/settings", headers=auth_headers)
        assert g_res.status_code == 200
        assert g_res.json()["settings"] == {
            "general": {"previewFeatures": True, "enablePromptCompletion": False}
        }
        assert "security" not in g_res.text
        assert "oauth-personal" not in g_res.text
        assert "secret-do-not-expose" not in g_res.text
        assert "customUntouched" not in g_res.text

        # Disallowed keys MUST return 403 Forbidden and never mutate disk
        for bad_payload in (
            {"security": {"auth": {"selectedType": "api-key"}}},
            {"oauth": {"token": "x"}},
            {"auth": {"mode": "y"}},
            {"unknownTop": True},
            {"general": {"unknownSubkey": True}},
        ):
            r_bad = tc.patch("/api/ecosystem/settings", headers=auth_headers, json=bad_payload)
            assert r_bad.status_code == 403
            assert r_bad.json()["error"]["code"] == "forbidden"

        # Wrong type for allowlisted key -> 422 validation_error
        r_type = tc.patch(
            "/api/ecosystem/settings",
            headers=auth_headers,
            json={"general": {"previewFeatures": "yes"}},
        )
        assert r_type.status_code == 422

        # Valid PATCH updates allowlisted key atomically and preserves untouched fields on disk
        r_ok = tc.patch(
            "/api/ecosystem/settings",
            headers=auth_headers,
            json={"general": {"enablePromptCompletion": True}},
        )
        assert r_ok.status_code == 200
        assert r_ok.json()["settings"] == {
            "general": {"previewFeatures": True, "enablePromptCompletion": True}
        }
        assert "secret-do-not-expose" not in r_ok.text

        on_disk = json.loads(settings_path.read_text(encoding="utf-8"))
        assert on_disk["security"] == initial_settings["security"]
        assert on_disk["customUntouched"] == initial_settings["customUntouched"]
        assert on_disk["general"] == {"previewFeatures": True, "enablePromptCompletion": True}
        assert stat.S_IMODE(os.stat(settings_path).st_mode) == 0o600


def test_systemd_unit_and_hermetic_installer_script(tmp_path: Path) -> None:
    gateway_root = Path(__file__).resolve().parent.parent
    unit_file = gateway_root / "systemd" / "gravity-well-gateway.service"
    installer = gateway_root / "scripts" / "install_service.sh"

    assert unit_file.is_file()
    assert installer.is_file()
    assert os.access(installer, os.X_OK)

    unit_text = unit_file.read_text(encoding="utf-8")
    assert ".venv/bin/uvicorn" in unit_text
    assert "--host 0.0.0.0 --port 9340" in unit_text
    assert "UnsetEnvironment=PYTHONPATH" in unit_text
    assert "EnvironmentFile=-%h/.config/gravity-well/gateway.env" in unit_text

    # Run install_service.sh --no-enable inside isolated tmp_path HOME/XDG_CONFIG_HOME
    fake_home = tmp_path / "inst_home"
    fake_xdg = fake_home / ".config"
    fake_home.mkdir(parents=True)
    proc = subprocess.run(
        [str(installer), "--no-enable"],
        env={
            "PATH": os.environ["PATH"],
            "HOME": str(fake_home),
            "XDG_CONFIG_HOME": str(fake_xdg),
            "USER": os.environ.get("USER", "jason"),
        },
        capture_output=True,
        text=True,
        check=False,
    )
    assert proc.returncode == 0, f"installer failed: {proc.stderr}"

    env_dest = fake_xdg / "gravity-well" / "gateway.env"
    unit_dest = fake_xdg / "systemd" / "user" / "gravity-well-gateway.service"
    assert env_dest.is_file()
    assert stat.S_IMODE(os.stat(env_dest).st_mode) == 0o600
    assert "GRAVITY_WELL_TOKEN=" in env_dest.read_text(encoding="utf-8")
    assert unit_dest.is_file()
