"""Ecosystem CRUD & allowlisted settings: models, agents, MCP servers, plugins, and ~/.gemini/settings.json."""

from __future__ import annotations

import asyncio
import json
import os
from pathlib import Path
import re
import tempfile
from typing import Any
from urllib.parse import unquote, urlparse

from fastapi import APIRouter, Body, Depends, Request
from pydantic import BaseModel, Field

from .auth import require_auth
from .config import (
    GatewayConfig,
    GatewayError,
    atomic_write_json,
    build_child_env,
    is_subpath,
    read_json_file,
)
from .redaction import REDACTED, redact_text
from .supervisor import SessionSupervisor

AGENT_SLUG_RE = re.compile(r"^[a-z0-9][a-z0-9_-]{0,63}$")
MCP_NAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
PLUGIN_NAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._@-]{0,127}$")
ENV_KEY_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")
THOUGHT_LEVELS = frozenset({"low", "medium", "high", "xhigh"})
SETTINGS_ALLOWLIST: dict[str, frozenset[str]] = {
    "general": frozenset({"previewFeatures", "enablePromptCompletion"}),
}
SYSTEM_DENIED_DIRS: tuple[str, ...] = (
    "/etc",
    "/proc",
    "/sys",
    "/dev",
    "/boot",
    "/root",
    "/var/run",
    "/run",
)


class SetDefaultModelRequest(BaseModel):
    model: str
    thought_level: str | None = None


class CreateAgentRequest(BaseModel):
    name: str
    description: str = ""
    prompt: str


class CreateMcpRequest(BaseModel):
    name: str
    type: str
    target: str
    args: list[str] = Field(default_factory=list)
    env: dict[str, str] = Field(default_factory=dict)
    headers: dict[str, str] = Field(default_factory=dict)


class ValidatePluginRequest(BaseModel):
    target: str
    strict: bool = False


class InstallPluginRequest(BaseModel):
    target: str


def _validate_flag_safe_name(name: str, *, pattern: re.Pattern[str], label: str) -> str:
    if not isinstance(name, str) or not name:
        raise GatewayError(400, "bad_request", f"{label} must be non-empty.")
    if "\x00" in name or "\n" in name or "\r" in name:
        raise GatewayError(400, "bad_request", f"{label} contains invalid control characters.")
    if name.startswith("-"):
        raise GatewayError(400, "bad_request", f"{label} must not start with '-'.")
    if "/" in name or "\\" in name or ".." in name:
        raise GatewayError(403, "forbidden", f"{label} contains forbidden path characters.")
    if not pattern.match(name):
        raise GatewayError(422, "validation_error", f"Malformed {label}: {name!r}")
    return name


def _validate_plugin_target(target: str, config: GatewayConfig) -> str:
    """Validate plugin install/validate target against flag injection and local filesystem escapes."""
    if not isinstance(target, str):
        raise GatewayError(400, "bad_request", "Plugin target must be a string.")
    cleaned = target.strip()
    if not cleaned:
        raise GatewayError(400, "bad_request", "Plugin target must be non-empty.")
    if "\x00" in cleaned or "\n" in cleaned or "\r" in cleaned:
        raise GatewayError(400, "bad_request", "Plugin target contains invalid control characters.")
    if cleaned.startswith("-"):
        raise GatewayError(400, "bad_request", "Plugin target must not start with '-'.")

    if cleaned.startswith(("https://", "http://")):
        parsed = urlparse(cleaned)
        if not parsed.netloc or any(ch.isspace() for ch in cleaned):
            raise GatewayError(400, "bad_request", "Malformed plugin target URL.")
        return cleaned

    local_raw = unquote(urlparse(cleaned).path) if cleaned.startswith("file://") else cleaned
    expanded = os.path.expanduser(local_raw)
    if not os.path.isabs(expanded):
        expanded = os.path.join(str(config.default_cwd), expanded)
    real = os.path.realpath(expanded)

    for denied in config.denied_dirs:
        if is_subpath(real, denied):
            raise GatewayError(
                403,
                "forbidden",
                "Plugin target points to a protected directory.",
                details={"target": target},
            )
    for sys_dir in SYSTEM_DENIED_DIRS:
        if is_subpath(real, sys_dir):
            raise GatewayError(
                403,
                "forbidden",
                "Plugin target points to a protected system directory.",
                details={"target": target},
            )
    if not any(is_subpath(real, root) for root in config.allowed_roots):
        raise GatewayError(
            403,
            "forbidden",
            "Plugin target is outside allowed roots.",
            details={"target": target},
        )
    return real


def _parse_models_output(text: str) -> tuple[str | None, list[dict[str, Any]]]:
    items: list[dict[str, Any]] = []
    default_id: str | None = None
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line:
            continue
        parts = re.split(r"\t+|\s{2,}", line, maxsplit=1)
        model_id = parts[0].strip()
        if not model_id or model_id.lower() in ("id", "model", "models"):
            continue
        display_raw = parts[1].strip() if len(parts) > 1 else model_id
        is_default = False
        m_def = re.search(r"\s*\(default\)\s*$", display_raw, flags=re.IGNORECASE)
        if m_def:
            is_default = True
            display_name = display_raw[: m_def.start()].strip()
            default_id = model_id
        else:
            display_name = display_raw

        family = model_id
        thought_level: str | None = None
        for suffix in ("xhigh", "high", "medium", "low"):
            tag = f"-{suffix}"
            if model_id.endswith(tag):
                family = model_id[: -len(tag)]
                thought_level = suffix
                break

        items.append(
            {
                "model_id": model_id,
                "display_name": display_name,
                "family": family,
                "thought_level": thought_level,
                "is_default": is_default,
            }
        )
    return default_id, items


def _parse_agent_markdown(text: str, fallback_name: str) -> dict[str, Any]:
    """Parse optional YAML frontmatter + markdown prompt body safely."""
    meta: dict[str, str] = {}
    body = text
    lines = text.splitlines()
    if lines and lines[0].strip() == "---":
        end_idx = None
        for idx in range(1, len(lines)):
            if lines[idx].strip() == "---":
                end_idx = idx
                break
        if end_idx is not None:
            for fm_line in lines[1:end_idx]:
                if ":" in fm_line:
                    k, v = fm_line.split(":", 1)
                    meta[k.strip()] = v.strip().strip('"').strip("'")
            body = "\n".join(lines[end_idx + 1 :]).lstrip("\n")

    return {
        "name": meta.get("name") or fallback_name,
        "description": meta.get("description") or "",
        "prompt": body,
    }


def _atomic_write_text(path: Path, content: str, mode: int = 0o600) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp_path = tempfile.mkstemp(prefix=f".{path.name}.", dir=str(path.parent))
    try:
        os.chmod(fd, mode)
        os.write(fd, content.encode("utf-8"))
        os.fsync(fd)
        os.close(fd)
        fd = -1
        os.replace(tmp_path, path)
    except Exception:
        if fd >= 0:
            try:
                os.close(fd)
            except OSError:
                pass
        try:
            os.unlink(tmp_path)
        except OSError:
            pass
        raise


def _parse_mcp_list_output(text: str) -> dict[str, dict[str, str]]:
    """Parse `agy mcp list` tabular output into name -> {type, status, target}."""
    out: dict[str, dict[str, str]] = {}
    lines = [ln.rstrip() for ln in text.splitlines() if ln.strip()]
    if not lines or lines[0].strip().lower().startswith("no mcp servers"):
        return out
    for line in lines:
        parts = re.split(r"\s{2,}|\t+", line.strip(), maxsplit=3)
        if len(parts) < 3:
            continue
        if parts[0].upper() == "NAME" and parts[1].upper() == "TYPE":
            continue
        out[parts[0]] = {
            "type": parts[1].lower(),
            "status": parts[2].lower(),
            "target": parts[3] if len(parts) > 3 else "",
        }
    return out


class EcosystemManager:
    """Manages models, agents, MCP servers, plugins, and allowlisted settings.json keys."""

    def __init__(self, config: GatewayConfig, supervisor: SessionSupervisor) -> None:
        self._config = config
        self._supervisor = supervisor

    @property
    def gateway_prefs_path(self) -> Path:
        return self._config.config_dir / "gateway_prefs.json"

    @property
    def mcp_config_path(self) -> Path:
        return self._config.gemini_dir / "config" / "mcp_config.json"

    @property
    def settings_path(self) -> Path:
        return self._config.gemini_dir / "settings.json"

    async def _run_agy(
        self,
        args: list[str],
        *,
        timeout: float = 15.0,
    ) -> tuple[int, str, str]:
        cwd = str(self._config.default_cwd if self._config.default_cwd.is_dir() else self._config.home_dir)
        try:
            proc = await asyncio.create_subprocess_exec(
                self._config.agy_bin,
                *args,
                cwd=cwd,
                env=build_child_env(home_override=self._config.home_dir),
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )
        except FileNotFoundError as exc:
            raise GatewayError(
                503,
                "service_unavailable",
                f"Antigravity CLI binary not found: {self._config.agy_bin}",
            ) from exc
        except OSError as exc:
            raise GatewayError(
                502,
                "acp_upstream_error",
                f"Failed to launch agy CLI: {exc}",
            ) from exc

        try:
            out_b, err_b = await asyncio.wait_for(proc.communicate(), timeout=timeout)
        except asyncio.TimeoutError as exc:
            try:
                proc.kill()
                await proc.wait()
            except ProcessLookupError:
                pass
            raise GatewayError(
                502,
                "acp_upstream_error",
                f"agy {' '.join(args[:2])} timed out after {timeout:.0f}s.",
            ) from exc

        return (
            int(proc.returncode or 0),
            out_b.decode("utf-8", errors="replace"),
            err_b.decode("utf-8", errors="replace"),
        )

    # ── Models ───────────────────────────────────────────────────────────────

    async def list_models(self) -> dict[str, Any]:
        rc, stdout, stderr = await self._run_agy(["models"])
        if rc != 0:
            raise GatewayError(
                502,
                "acp_upstream_error",
                redact_text((stderr or stdout or "agy models failed").strip()),
            )
        default_id, items = _parse_models_output(stdout)
        prefs = read_json_file(self.gateway_prefs_path, {})
        gw_model = prefs.get("model") if isinstance(prefs, dict) else None
        gw_thought = prefs.get("thought_level") if isinstance(prefs, dict) else None
        return {
            "default_model_id": default_id,
            "gateway_default_model": gw_model,
            "gateway_default_thought_level": gw_thought,
            "items": items,
        }

    def set_default_model(self, model: str, thought_level: str | None = None) -> dict[str, Any]:
        cleaned_model = (model or "").strip()
        if not cleaned_model or "\x00" in cleaned_model or "\n" in cleaned_model or "\r" in cleaned_model:
            raise GatewayError(422, "validation_error", "model must be a non-empty string.")
        if cleaned_model.startswith("-"):
            raise GatewayError(400, "bad_request", "model must not start with '-'.")
        cleaned_thought: str | None = None
        if thought_level is not None:
            cleaned_thought = thought_level.strip().lower()
            if cleaned_thought not in THOUGHT_LEVELS:
                raise GatewayError(
                    422,
                    "validation_error",
                    f"thought_level must be one of {sorted(THOUGHT_LEVELS)}.",
                )

        prefs = read_json_file(self.gateway_prefs_path, {})
        if not isinstance(prefs, dict):
            prefs = {}
        prefs["model"] = cleaned_model
        if cleaned_thought is not None:
            prefs["thought_level"] = cleaned_thought
        atomic_write_json(self.gateway_prefs_path, prefs, mode=0o600)
        return {
            "model": prefs.get("model"),
            "thought_level": prefs.get("thought_level"),
        }

    # ── Agents ───────────────────────────────────────────────────────────────

    def _scan_agents_dir(self, directory: Path, source: str, editable: bool) -> dict[str, dict[str, Any]]:
        found: dict[str, dict[str, Any]] = {}
        if not directory.is_dir():
            return found
        real_dir = os.path.realpath(directory)
        for entry in sorted(os.listdir(real_dir)):
            if not entry.endswith(".md"):
                continue
            full = os.path.join(real_dir, entry)
            if os.path.islink(full):
                continue
            real_file = os.path.realpath(full)
            if not is_subpath(real_file, real_dir) or real_file == real_dir:
                continue
            if not os.path.isfile(real_file):
                continue
            slug = entry[:-3]
            try:
                raw = Path(real_file).read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue
            parsed = _parse_agent_markdown(raw, slug)
            found[parsed["name"]] = {
                "name": parsed["name"],
                "slug": slug,
                "description": parsed["description"],
                "prompt": parsed["prompt"],
                "source": source,
                "editable": editable,
            }
        return found

    async def list_agents(self) -> dict[str, Any]:
        rc, stdout, _stderr = await self._run_agy(["agent"])
        cli_names: list[str] = []
        if rc == 0:
            for ln in stdout.splitlines():
                val = ln.strip()
                if val:
                    cli_names.append(val)

        merged: dict[str, dict[str, Any]] = {}
        merged.update(self._scan_agents_dir(self._config.gemini_dir / "agents", "gemini", False))
        merged.update(self._scan_agents_dir(self._config.agy_cli_dir / "agents", "antigravity-cli", True))
        for name in cli_names:
            if name not in merged:
                merged[name] = {
                    "name": name,
                    "slug": name,
                    "description": "",
                    "prompt": "",
                    "source": "cli",
                    "editable": False,
                }
        items = sorted(merged.values(), key=lambda x: str(x["name"]))
        return {"items": items}

    def upsert_agent(self, *, name: str, description: str, prompt: str) -> dict[str, Any]:
        slug = _validate_flag_safe_name(name, pattern=AGENT_SLUG_RE, label="agent name")
        if not isinstance(prompt, str) or not prompt.strip():
            raise GatewayError(422, "validation_error", "Agent prompt must be non-empty.")
        clean_desc = (description or "").replace("\r", " ").replace("\n", " ").replace("\x00", "").strip()

        agents_dir = self._config.agy_cli_dir / "agents"
        agents_dir.mkdir(parents=True, exist_ok=True)
        real_dir = os.path.realpath(agents_dir)
        target = Path(real_dir) / f"{slug}.md"
        if os.path.islink(target):
            raise GatewayError(403, "forbidden", "Agent path must not be a symlink.")
        real_target = os.path.realpath(target)
        if not is_subpath(real_target, real_dir) or real_target == real_dir:
            raise GatewayError(403, "forbidden", "Agent path escapes agents directory.")

        body = prompt.rstrip() + "\n"
        content = f"---\nname: {slug}\ndescription: {clean_desc}\n---\n{body}"
        _atomic_write_text(Path(real_target), content, mode=0o600)
        return {
            "name": slug,
            "slug": slug,
            "description": clean_desc,
            "prompt": body.rstrip("\n"),
            "source": "antigravity-cli",
            "editable": True,
        }

    def delete_agent(self, name: str) -> dict[str, Any]:
        slug = _validate_flag_safe_name(name, pattern=AGENT_SLUG_RE, label="agent name")
        agents_dir = self._config.agy_cli_dir / "agents"
        real_dir = os.path.realpath(agents_dir)
        target = Path(real_dir) / f"{slug}.md"
        if os.path.islink(target):
            raise GatewayError(403, "forbidden", "Agent path must not be a symlink.")
        real_target = os.path.realpath(target)
        if not is_subpath(real_target, real_dir) or real_target == real_dir:
            raise GatewayError(403, "forbidden", "Agent path escapes agents directory.")
        if not os.path.isfile(real_target):
            raise GatewayError(404, "not_found", f"Agent not found: {slug}")
        os.unlink(real_target)
        return {"deleted": True, "name": slug}

    # ── MCP Servers ──────────────────────────────────────────────────────────

    def _read_masked_mcp_config(self) -> dict[str, dict[str, Any]]:
        raw = read_json_file(self.mcp_config_path, {})
        if not isinstance(raw, dict):
            return {}
        servers = raw.get("mcpServers")
        if not isinstance(servers, dict):
            return {}
        out: dict[str, dict[str, Any]] = {}
        for name, entry in servers.items():
            if not isinstance(name, str) or not isinstance(entry, dict):
                continue
            disabled = bool(entry.get("disabled", False))
            raw_env = entry.get("env")
            env_raw: dict[str, Any] = raw_env if isinstance(raw_env, dict) else {}
            raw_hdr = entry.get("headers")
            hdr_raw: dict[str, Any] = raw_hdr if isinstance(raw_hdr, dict) else {}
            masked_env = {str(k): REDACTED for k in sorted(env_raw.keys())}
            masked_hdrs = {str(k): REDACTED for k in sorted(hdr_raw.keys())}
            server_url = entry.get("serverUrl")
            command = entry.get("command")
            raw_args = entry.get("args")
            args: list[Any] = raw_args if isinstance(raw_args, list) else []
            srv_type = "http" if server_url else "stdio"
            out[name] = {
                "name": name,
                "type": srv_type,
                "disabled": disabled,
                "status": "disabled" if disabled else "enabled",
                "command": command,
                "args": [str(a) for a in args],
                "server_url": server_url,
                "env": masked_env,
                "headers": masked_hdrs,
                "env_keys": sorted(masked_env.keys()),
                "header_keys": sorted(masked_hdrs.keys()),
            }
        return out

    async def list_mcp_servers(self) -> dict[str, Any]:
        rc, stdout, stderr = await self._run_agy(["mcp", "list"])
        if rc != 0:
            raise GatewayError(
                502,
                "acp_upstream_error",
                redact_text((stderr or stdout or "agy mcp list failed").strip()),
            )
        cli_map = _parse_mcp_list_output(stdout)
        cfg_map = self._read_masked_mcp_config()

        all_names = sorted(set(cli_map.keys()) | set(cfg_map.keys()))
        items: list[dict[str, Any]] = []
        for name in all_names:
            if name in cfg_map:
                item = dict(cfg_map[name])
                if name in cli_map:
                    item["status"] = cli_map[name]["status"]
                    item["disabled"] = cli_map[name]["status"] == "disabled"
                items.append(item)
            else:
                cli_entry = cli_map[name]
                items.append(
                    {
                        "name": name,
                        "type": cli_entry["type"],
                        "disabled": cli_entry["status"] == "disabled",
                        "status": cli_entry["status"],
                        "command": cli_entry["target"] if cli_entry["type"] == "stdio" else None,
                        "args": [],
                        "server_url": cli_entry["target"] if cli_entry["type"] == "http" else None,
                        "env": {},
                        "headers": {},
                        "env_keys": [],
                        "header_keys": [],
                    }
                )
        return {"items": items}

    async def add_mcp_server(self, req: CreateMcpRequest) -> dict[str, Any]:
        safe_name = _validate_flag_safe_name(req.name, pattern=MCP_NAME_RE, label="MCP server name")
        srv_type = (req.type or "").strip().lower()
        if srv_type not in ("stdio", "http"):
            raise GatewayError(422, "validation_error", "MCP server type must be 'stdio' or 'http'.")

        target = (req.target or "").strip()
        if not target:
            raise GatewayError(400, "bad_request", "MCP server target must be non-empty.")
        if target.startswith("-"):
            raise GatewayError(400, "bad_request", "MCP server target must not start with '-'.")
        if "\x00" in target or "\n" in target or "\r" in target:
            raise GatewayError(400, "bad_request", "MCP server target contains invalid control characters.")
        if srv_type == "http" and not target.startswith(("https://", "http://")):
            raise GatewayError(422, "validation_error", "HTTP MCP target must start with http:// or https://.")

        # Flags MUST precede <name> per R7
        argv: list[str] = ["mcp", "add", "--type", srv_type]
        for k, v in req.env.items():
            if not ENV_KEY_RE.match(k):
                raise GatewayError(422, "validation_error", f"Invalid env var name: {k!r}")
            if "\x00" in v or "\n" in v or "\r" in v:
                raise GatewayError(400, "bad_request", "Env value contains invalid control characters.")
            argv.extend(["-e", f"{k}={v}"])
        for k, v in req.headers.items():
            if not k or ":" in k or "\x00" in k or "\n" in k or "\r" in k:
                raise GatewayError(422, "validation_error", f"Invalid header name: {k!r}")
            if "\x00" in v or "\n" in v or "\r" in v:
                raise GatewayError(400, "bad_request", "Header value contains invalid control characters.")
            argv.extend(["-H", f"{k}: {v}"])

        for arg in req.args:
            if "\x00" in arg:
                raise GatewayError(400, "bad_request", "MCP arg contains null byte.")

        argv.extend([safe_name, target, *req.args])
        rc, stdout, stderr = await self._run_agy(argv)
        if rc != 0:
            raise GatewayError(
                502,
                "acp_upstream_error",
                redact_text((stderr or stdout or "agy mcp add failed").strip()),
            )
        cfg_map = self._read_masked_mcp_config()
        return cfg_map.get(
            safe_name,
            {
                "name": safe_name,
                "type": srv_type,
                "disabled": False,
                "status": "enabled",
                "command": target if srv_type == "stdio" else None,
                "args": list(req.args),
                "server_url": target if srv_type == "http" else None,
                "env": {k: REDACTED for k in sorted(req.env.keys())},
                "headers": {k: REDACTED for k in sorted(req.headers.keys())},
                "env_keys": sorted(req.env.keys()),
                "header_keys": sorted(req.headers.keys()),
            },
        )

    async def set_mcp_enabled(self, name: str, *, enabled: bool) -> dict[str, Any]:
        safe_name = _validate_flag_safe_name(name, pattern=MCP_NAME_RE, label="MCP server name")
        subcmd = "enable" if enabled else "disable"
        rc, stdout, stderr = await self._run_agy(["mcp", subcmd, safe_name])
        if rc != 0:
            err_msg = redact_text((stderr or stdout or f"agy mcp {subcmd} failed").strip())
            if "not found" in err_msg.lower():
                raise GatewayError(404, "not_found", err_msg)
            raise GatewayError(502, "acp_upstream_error", err_msg)
        return {"name": safe_name, "disabled": not enabled, "status": "enabled" if enabled else "disabled"}

    async def remove_mcp_server(self, name: str) -> dict[str, Any]:
        safe_name = _validate_flag_safe_name(name, pattern=MCP_NAME_RE, label="MCP server name")
        rc, stdout, stderr = await self._run_agy(["mcp", "remove", safe_name])
        if rc != 0:
            err_msg = redact_text((stderr or stdout or "agy mcp remove failed").strip())
            if "not found" in err_msg.lower():
                raise GatewayError(404, "not_found", err_msg)
            raise GatewayError(502, "acp_upstream_error", err_msg)
        return {"deleted": True, "name": safe_name}

    # ── Plugins ──────────────────────────────────────────────────────────────

    def _scan_builtin_plugins(self) -> list[dict[str, Any]]:
        builtin_dir = self._config.agy_cli_dir / "builtin" / "plugins"
        if not builtin_dir.is_dir():
            return []
        real_base = os.path.realpath(builtin_dir)
        items: list[dict[str, Any]] = []
        for entry in sorted(os.listdir(real_base)):
            p_dir = os.path.join(real_base, entry)
            if os.path.islink(p_dir) or not os.path.isdir(p_dir):
                continue
            real_pdir = os.path.realpath(p_dir)
            if not is_subpath(real_pdir, real_base) or real_pdir == real_base:
                continue
            manifest: dict[str, Any] = {}
            for rel in (
                "plugin.json",
                os.path.join(".gemini-plugin", "plugin.json"),
                os.path.join(".claude-plugin", "plugin.json"),
            ):
                cand = os.path.join(real_pdir, rel)
                if os.path.islink(cand):
                    continue
                real_cand = os.path.realpath(cand)
                if is_subpath(real_cand, real_pdir) and os.path.isfile(real_cand):
                    loaded = read_json_file(Path(real_cand), {})
                    if isinstance(loaded, dict):
                        manifest = loaded
                        break
            items.append(
                {
                    "name": str(manifest.get("name") or entry),
                    "display_name": str(manifest.get("displayName") or manifest.get("name") or entry),
                    "description": str(manifest.get("description") or ""),
                    "version": manifest.get("version"),
                    "builtin": True,
                    "enabled": True,
                }
            )
        return items

    async def list_plugins(self) -> dict[str, Any]:
        rc, stdout, stderr = await self._run_agy(["plugin", "list", "--json"])
        if rc != 0:
            raise GatewayError(
                502,
                "acp_upstream_error",
                redact_text((stderr or stdout or "agy plugin list failed").strip()),
            )
        try:
            parsed = json.loads(stdout) if stdout.strip() else []
        except json.JSONDecodeError as exc:
            raise GatewayError(502, "acp_upstream_error", "Invalid JSON from `agy plugin list --json`.") from exc
        items = parsed if isinstance(parsed, list) else []
        return {
            "items": items,
            "builtins": self._scan_builtin_plugins(),
        }

    async def validate_plugin(self, *, target: str, strict: bool = False) -> dict[str, Any]:
        safe_target = _validate_plugin_target(target, self._config)
        args = ["plugin", "validate", safe_target]
        if strict:
            args.append("--strict")
        args.append("--json")
        rc, stdout, stderr = await self._run_agy(args, timeout=30.0)
        parsed_report: Any = None
        if stdout.strip():
            try:
                parsed_report = json.loads(stdout)
            except json.JSONDecodeError:
                parsed_report = None
        if parsed_report is None and rc != 0:
            raise GatewayError(
                400,
                "bad_request",
                redact_text((stderr or stdout or "Plugin validation failed").strip()),
            )
        return {
            "valid": rc == 0,
            "strict": strict,
            "report": parsed_report,
            "errors": redact_text(stderr.strip()),
        }

    async def install_plugin(self, *, target: str) -> dict[str, Any]:
        safe_target = _validate_plugin_target(target, self._config)
        rc, stdout, stderr = await self._run_agy(["plugin", "install", safe_target], timeout=30.0)
        if rc != 0:
            err_msg = redact_text((stderr or stdout or "agy plugin install failed").strip())
            if "must be a directory" in err_msg.lower() or "no such file" in err_msg.lower():
                raise GatewayError(400, "bad_request", err_msg)
            raise GatewayError(502, "acp_upstream_error", err_msg)
        return {
            "installed": True,
            "target": safe_target,
            "output": redact_text(stdout.strip()),
        }

    async def set_plugin_enabled(self, name: str, *, enabled: bool) -> dict[str, Any]:
        safe_name = _validate_flag_safe_name(name, pattern=PLUGIN_NAME_RE, label="plugin name")
        subcmd = "enable" if enabled else "disable"
        rc, stdout, stderr = await self._run_agy(["plugin", subcmd, safe_name])
        if rc != 0:
            err_msg = redact_text((stderr or stdout or f"agy plugin {subcmd} failed").strip())
            if "not found" in err_msg.lower() or "not installed" in err_msg.lower():
                raise GatewayError(404, "not_found", err_msg)
            raise GatewayError(502, "acp_upstream_error", err_msg)
        return {"name": safe_name, "enabled": enabled, "status": "enabled" if enabled else "disabled"}

    async def uninstall_plugin(self, name: str) -> dict[str, Any]:
        safe_name = _validate_flag_safe_name(name, pattern=PLUGIN_NAME_RE, label="plugin name")
        rc, stdout, stderr = await self._run_agy(["plugin", "uninstall", safe_name])
        if rc != 0:
            err_msg = redact_text((stderr or stdout or "agy plugin uninstall failed").strip())
            if "not found" in err_msg.lower() or "not installed" in err_msg.lower():
                raise GatewayError(404, "not_found", err_msg)
            raise GatewayError(502, "acp_upstream_error", err_msg)
        return {"deleted": True, "name": safe_name}

    # ── Allowlisted Settings ─────────────────────────────────────────────────

    def get_settings(self) -> dict[str, Any]:
        """Return ONLY allowlisted settings keys plus gateway preferences."""
        raw = read_json_file(self.settings_path, {})
        if not isinstance(raw, dict):
            raw = {}
        raw_general = raw.get("general")
        general_out: dict[str, bool] = {}
        if isinstance(raw_general, dict):
            for key in sorted(SETTINGS_ALLOWLIST["general"]):
                if key in raw_general and isinstance(raw_general[key], bool):
                    general_out[key] = raw_general[key]

        prefs = read_json_file(self.gateway_prefs_path, {})
        if not isinstance(prefs, dict):
            prefs = {}
        return {
            "settings": {"general": general_out},
            "gateway_prefs": {
                "model": prefs.get("model"),
                "thought_level": prefs.get("thought_level"),
            },
            "allowlist": [f"general.{k}" for k in sorted(SETTINGS_ALLOWLIST["general"])],
        }

    def patch_settings(self, payload: dict[str, Any]) -> dict[str, Any]:
        """Validate strictly against allowlist and atomically rewrite ~/.gemini/settings.json."""
        if not isinstance(payload, dict):
            raise GatewayError(422, "validation_error", "Settings payload must be a JSON object.")
        if not payload:
            raise GatewayError(400, "bad_request", "No allowlisted settings provided.")

        updates: dict[str, dict[str, bool]] = {}
        for top_key, top_val in payload.items():
            if top_key not in SETTINGS_ALLOWLIST:
                raise GatewayError(
                    403,
                    "forbidden",
                    f"Setting key '{top_key}' is not allowlisted.",
                )
            if not isinstance(top_val, dict):
                raise GatewayError(
                    422,
                    "validation_error",
                    f"Setting section '{top_key}' must be a JSON object.",
                )
            if not top_val:
                raise GatewayError(400, "bad_request", f"Setting section '{top_key}' cannot be empty.")
            allowed_subkeys = SETTINGS_ALLOWLIST[top_key]
            sec_updates: dict[str, bool] = {}
            for sub_key, sub_val in top_val.items():
                if sub_key not in allowed_subkeys:
                    raise GatewayError(
                        403,
                        "forbidden",
                        f"Setting key '{top_key}.{sub_key}' is not allowlisted.",
                    )
                if not isinstance(sub_val, bool):
                    raise GatewayError(
                        422,
                        "validation_error",
                        f"Setting '{top_key}.{sub_key}' must be a boolean.",
                    )
                sec_updates[sub_key] = sub_val
            updates[top_key] = sec_updates

        existing: dict[str, Any]
        if self.settings_path.exists():
            if not self.settings_path.is_file():
                raise GatewayError(
                    409,
                    "conflict",
                    "Existing ~/.gemini/settings.json is not a regular file; refusing to overwrite.",
                )
            try:
                raw_settings_text = self.settings_path.read_text(encoding="utf-8")
            except OSError as exc:
                raise GatewayError(
                    502,
                    "acp_upstream_error",
                    f"Failed to read existing ~/.gemini/settings.json: {redact_text(str(exc))}",
                ) from exc
            try:
                parsed_existing = json.loads(raw_settings_text)
            except json.JSONDecodeError as exc:
                raise GatewayError(
                    409,
                    "conflict",
                    "Existing ~/.gemini/settings.json contains malformed JSON; refusing to overwrite.",
                ) from exc
            if not isinstance(parsed_existing, dict):
                raise GatewayError(
                    409,
                    "conflict",
                    "Existing ~/.gemini/settings.json root must be a JSON object; refusing to overwrite.",
                )
            existing = dict(parsed_existing)
        else:
            existing = {}

        for top_key, sec_updates in updates.items():
            section = existing.get(top_key)
            if not isinstance(section, dict):
                section = {}
            else:
                section = dict(section)
            section.update(sec_updates)
            existing[top_key] = section

        atomic_write_json(self.settings_path, existing, mode=0o600)
        return self.get_settings()


def create_ecosystem_router() -> APIRouter:
    """Create the authenticated `/api/ecosystem` FastAPI router."""
    router = APIRouter(
        prefix="/api/ecosystem",
        dependencies=[Depends(require_auth)],
        tags=["ecosystem"],
    )

    @router.get("/models")
    async def list_models(request: Request) -> dict[str, Any]:
        eco: EcosystemManager = request.app.state.ecosystem
        return await eco.list_models()

    @router.put("/models/default")
    async def set_default_model(
        request: Request,
        body: SetDefaultModelRequest,
    ) -> dict[str, Any]:
        eco: EcosystemManager = request.app.state.ecosystem
        return eco.set_default_model(model=body.model, thought_level=body.thought_level)

    @router.get("/agents")
    async def list_agents(request: Request) -> dict[str, Any]:
        eco: EcosystemManager = request.app.state.ecosystem
        return await eco.list_agents()

    @router.post("/agents", status_code=201)
    async def upsert_agent(
        request: Request,
        body: CreateAgentRequest,
    ) -> dict[str, Any]:
        eco: EcosystemManager = request.app.state.ecosystem
        return eco.upsert_agent(name=body.name, description=body.description, prompt=body.prompt)

    @router.delete("/agents/{name}")
    async def delete_agent(request: Request, name: str) -> dict[str, Any]:
        eco: EcosystemManager = request.app.state.ecosystem
        return eco.delete_agent(name=name)

    @router.get("/mcp")
    async def list_mcp_servers(request: Request) -> dict[str, Any]:
        eco: EcosystemManager = request.app.state.ecosystem
        return await eco.list_mcp_servers()

    @router.post("/mcp", status_code=201)
    async def add_mcp_server(
        request: Request,
        body: CreateMcpRequest,
    ) -> dict[str, Any]:
        eco: EcosystemManager = request.app.state.ecosystem
        return await eco.add_mcp_server(body)

    @router.post("/mcp/{name}/enable")
    async def enable_mcp_server(request: Request, name: str) -> dict[str, Any]:
        eco: EcosystemManager = request.app.state.ecosystem
        return await eco.set_mcp_enabled(name=name, enabled=True)

    @router.post("/mcp/{name}/disable")
    async def disable_mcp_server(request: Request, name: str) -> dict[str, Any]:
        eco: EcosystemManager = request.app.state.ecosystem
        return await eco.set_mcp_enabled(name=name, enabled=False)

    @router.delete("/mcp/{name}")
    async def remove_mcp_server(request: Request, name: str) -> dict[str, Any]:
        eco: EcosystemManager = request.app.state.ecosystem
        return await eco.remove_mcp_server(name=name)

    @router.get("/plugins")
    async def list_plugins(request: Request) -> dict[str, Any]:
        eco: EcosystemManager = request.app.state.ecosystem
        return await eco.list_plugins()

    @router.post("/plugins/validate")
    async def validate_plugin(
        request: Request,
        body: ValidatePluginRequest,
    ) -> dict[str, Any]:
        eco: EcosystemManager = request.app.state.ecosystem
        return await eco.validate_plugin(target=body.target, strict=body.strict)

    @router.post("/plugins/install")
    async def install_plugin(
        request: Request,
        body: InstallPluginRequest,
    ) -> dict[str, Any]:
        eco: EcosystemManager = request.app.state.ecosystem
        return await eco.install_plugin(target=body.target)

    @router.post("/plugins/{name}/enable")
    async def enable_plugin(request: Request, name: str) -> dict[str, Any]:
        eco: EcosystemManager = request.app.state.ecosystem
        return await eco.set_plugin_enabled(name=name, enabled=True)

    @router.post("/plugins/{name}/disable")
    async def disable_plugin(request: Request, name: str) -> dict[str, Any]:
        eco: EcosystemManager = request.app.state.ecosystem
        return await eco.set_plugin_enabled(name=name, enabled=False)

    @router.delete("/plugins/{name}")
    async def uninstall_plugin(request: Request, name: str) -> dict[str, Any]:
        eco: EcosystemManager = request.app.state.ecosystem
        return await eco.uninstall_plugin(name=name)

    @router.get("/settings")
    async def get_settings(request: Request) -> dict[str, Any]:
        eco: EcosystemManager = request.app.state.ecosystem
        return eco.get_settings()

    @router.patch("/settings")
    async def patch_settings(
        request: Request,
        body: dict[str, Any] = Body(...),
    ) -> dict[str, Any]:
        eco: EcosystemManager = request.app.state.ecosystem
        return eco.patch_settings(body)

    return router
