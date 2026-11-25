"""Per-session ACP process supervisor, permission/elicitation broker, and FIFO follow-up queue."""

from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
import os
from pathlib import Path
import re
import secrets
import shlex
import subprocess
from typing import Any, Sequence

from .acp_client import AcpClient, AcpRpcError, AcpUpstreamError
from .config import (
    ACP_PROTOCOL_VERSION,
    GATEWAY_VERSION,
    GatewayConfig,
    GatewayError,
    atomic_write_json,
    build_child_env,
    read_json_file,
    utc_now_iso,
    validate_session_id,
    validate_workspace_dir,
)
from .redaction import redact_text
from .replay import ReplayHub
from .storage import StorageManager

DEFAULT_CONFIG_OPTIONS: list[dict[str, Any]] = [
    {
        "id": "model",
        "name": "Model",
        "category": "model",
        "currentValue": "gemini-4-neon",
        "options": [],
    },
    {
        "id": "thought_level",
        "name": "Thought Level",
        "category": "thought_level",
        "currentValue": "high",
        "options": [],
    },
    {
        "id": "mode",
        "name": "Mode",
        "category": "mode",
        "currentValue": "default",
        "options": [],
    },
]


@dataclass
class QueueItem:
    item_id: str
    position: int
    text: str
    attachment_ids: list[str]
    status: str = "queued"
    dispatch_on_settle: bool = False
    created_at: str = field(default_factory=utc_now_iso)

    def to_dict(self) -> dict[str, Any]:
        return {
            "item_id": self.item_id,
            "position": self.position,
            "text": self.text,
            "attachment_ids": list(self.attachment_ids),
            "status": self.status,
            "dispatch_on_settle": self.dispatch_on_settle,
            "created_at": self.created_at,
        }


@dataclass
class PendingPermission:
    request_id: str
    tool_call: dict[str, Any]
    options: list[dict[str, Any]]
    future: asyncio.Future[dict[str, Any]]
    created_at: str = field(default_factory=utc_now_iso)

    def to_dict(self) -> dict[str, Any]:
        return {
            "request_id": self.request_id,
            "tool_call": self.tool_call,
            "options": self.options,
            "created_at": self.created_at,
        }


@dataclass
class PendingQuestion:
    request_id: str
    mode: str
    message: str
    requested_schema: dict[str, Any]
    future: asyncio.Future[dict[str, Any]]
    created_at: str = field(default_factory=utc_now_iso)

    def to_dict(self) -> dict[str, Any]:
        return {
            "request_id": self.request_id,
            "mode": self.mode,
            "message": self.message,
            "requested_schema": self.requested_schema,
            "created_at": self.created_at,
        }


def _extract_config_val(config_options: list[dict[str, Any]], config_id: str) -> str | None:
    for opt in config_options:
        if isinstance(opt, dict) and opt.get("id") == config_id:
            val = opt.get("currentValue")
            return str(val) if val is not None else None
    return None


def _normalize_available_models(raw_models: Any) -> list[dict[str, Any]]:
    if not isinstance(raw_models, dict):
        return []
    arr = raw_models.get("availableModels")
    if not isinstance(arr, list):
        return []
    out: list[dict[str, Any]] = []
    for item in arr:
        if not isinstance(item, dict):
            continue
        mid = item.get("modelId") or item.get("model_id")
        if mid:
            out.append({"model_id": str(mid), "name": str(item.get("name") or mid)})
    return out


AGENT_SLUG_RE = re.compile(r"^[a-z0-9][a-z0-9_-]{0,63}$")

COMMAND_EXEC_TOOL_KINDS: frozenset[str] = frozenset(
    {"execute", "bash", "shell", "command", "terminal", "run"}
)

SHELL_FORBIDDEN_OPERATORS: tuple[str, ...] = (
    "&&",
    "||",
    ";",
    "|",
    "&",
    "`",
    "$(",
    "\n",
    "\r",
    ">",
    "<",
    "\x00",
)


def _validate_agent_slug(agent: str) -> str:
    """Validate optional `--agent` CLI slug against flag, path, and control-char injection."""
    if not isinstance(agent, str):
        raise GatewayError(422, "validation_error", "Agent slug must be a string.")
    if any(ord(ch) < 32 or ord(ch) == 127 for ch in agent):
        raise GatewayError(400, "bad_request", "Agent slug contains invalid control characters.")
    if agent.startswith("-"):
        raise GatewayError(400, "bad_request", "Agent slug must not start with '-'.")
    if not AGENT_SLUG_RE.fullmatch(agent):
        raise GatewayError(
            422,
            "validation_error",
            f"Agent slug must match ^[a-z0-9][a-z0-9_-]{{0,63}}$: {agent!r}",
        )
    return agent


def _redact_nested(value: Any) -> Any:
    if isinstance(value, str):
        return redact_text(value)
    if isinstance(value, dict):
        return {k: _redact_nested(v) for k, v in value.items()}
    if isinstance(value, list):
        return [_redact_nested(item) for item in value]
    if isinstance(value, tuple):
        return tuple(_redact_nested(item) for item in value)
    return value


def _has_forbidden_shell_operators(cmd_line: str) -> bool:
    return any(op in cmd_line for op in SHELL_FORBIDDEN_OPERATORS)


def _extract_raw_command_line(tool_call: dict[str, Any]) -> str | None:
    raw_input = tool_call.get("rawInput")
    if not isinstance(raw_input, dict):
        return None
    for key in ("CommandLine", "command", "cmd"):
        val = raw_input.get(key)
        if isinstance(val, str) and val != "":
            return val
    return None


def _parse_safe_argv(cmd_line: str) -> list[str] | None:
    if not cmd_line or _has_forbidden_shell_operators(cmd_line):
        return None
    trimmed = cmd_line.strip(" \t")
    if not trimmed:
        return None
    try:
        tokens = shlex.split(trimmed, posix=True)
    except ValueError:
        return None
    if not tokens or not tokens[0] or _has_forbidden_shell_operators(tokens[0]):
        return None
    return tokens


def _is_command_tool(tool_call: dict[str, Any], tool_kind: str | None = None) -> bool:
    kind = (tool_kind if tool_kind is not None else str(tool_call.get("kind") or "other")).strip().lower()
    return kind in COMMAND_EXEC_TOOL_KINDS or _extract_raw_command_line(tool_call) is not None


def _derive_command_prefix(tool_call: dict[str, Any]) -> str:
    raw_cmd = _extract_raw_command_line(tool_call)
    if raw_cmd is None:
        return ""
    tokens = _parse_safe_argv(raw_cmd)
    if not tokens:
        return ""
    return tokens[0]


def _command_matches_prefix(
    tool_call: dict[str, Any],
    prefix: str,
    *,
    tool_kind: str = "execute",
) -> bool:
    if not prefix:
        if _is_command_tool(tool_call, tool_kind):
            return False
        return True
    if (
        _has_forbidden_shell_operators(prefix)
        or prefix != prefix.strip()
        or any(ch.isspace() for ch in prefix)
    ):
        return False
    raw_cmd = _extract_raw_command_line(tool_call)
    if raw_cmd is None:
        return False
    tokens = _parse_safe_argv(raw_cmd)
    if not tokens:
        return False
    return tokens[0] == prefix


@dataclass
class LiveSession:
    session_id: str
    cwd: str
    add_dirs: list[str]
    acp: AcpClient
    title: str = "New Conversation"
    title_source: str = "acp"
    status: str = "starting"
    agent: str | None = None
    sandbox: bool = False
    config_options: list[dict[str, Any]] = field(
        default_factory=lambda: [dict(x) for x in DEFAULT_CONFIG_OPTIONS]
    )
    available_models: list[dict[str, Any]] = field(default_factory=list)
    available_commands: list[dict[str, Any]] = field(default_factory=list)
    usage: dict[str, int] = field(default_factory=lambda: {"used": 0, "size": 1048576})
    preview: str = ""
    step_count: int = 0
    pending_permissions: dict[str, PendingPermission] = field(default_factory=dict)
    pending_questions: dict[str, PendingQuestion] = field(default_factory=dict)
    queue: list[QueueItem] = field(default_factory=list)
    queue_paused: bool = False
    prompt_lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    active_turn_id: str | None = None
    turn_task: asyncio.Task[None] | None = None
    ready_for_events: bool = False
    early_updates: list[dict[str, Any]] = field(default_factory=list)
    pinned: bool = False
    pinned_at: str | None = None
    archived: bool = False
    updated_at: str = field(default_factory=utc_now_iso)

    def renumber_queue(self) -> None:
        for idx, item in enumerate(self.queue):
            item.position = idx

    def to_snapshot(self, replay: ReplayHub) -> dict[str, Any]:
        return {
            "session_id": self.session_id,
            "title": self.title,
            "title_source": self.title_source,
            "cwd": self.cwd,
            "add_dirs": list(self.add_dirs),
            "status": self.status,
            "is_live": self.acp.is_running and self.status not in ("closed", "archived", "error"),
            "pinned": self.pinned,
            "pinned_at": self.pinned_at,
            "archived": self.archived,
            "agent": self.agent,
            "sandbox": self.sandbox,
            "config_options": list(self.config_options),
            "available_models": list(self.available_models),
            "available_commands": list(self.available_commands),
            "usage": dict(self.usage),
            "pending_permissions": [p.to_dict() for p in self.pending_permissions.values()],
            "pending_questions": [q.to_dict() for q in self.pending_questions.values()],
            "queued_followups": [item.to_dict() for item in self.queue],
            "cursor": replay.session_cursor(self.session_id),
            "seq": replay.session_seq(self.session_id),
            "updated_at": self.updated_at,
        }

    def to_list_item(self) -> dict[str, Any]:
        return {
            "session_id": self.session_id,
            "title": self.title,
            "preview": self.preview,
            "cwd": self.cwd,
            "is_live": self.acp.is_running and self.status not in ("closed", "archived", "error"),
            "status": self.status,
            "pinned": self.pinned,
            "pinned_at": self.pinned_at,
            "archived": self.archived,
            "step_count": self.step_count,
            "model_id": _extract_config_val(self.config_options, "model"),
            "thought_level": _extract_config_val(self.config_options, "thought_level"),
            "mode_id": _extract_config_val(self.config_options, "mode"),
            "updated_at": self.updated_at,
        }


class SessionSupervisor:
    """Supervises per-session `agy acp --stdio` child processes and session state."""

    def __init__(
        self,
        config: GatewayConfig,
        replay: ReplayHub,
        storage: StorageManager,
    ) -> None:
        self._config = config
        self._replay = replay
        self._storage = storage
        self._sessions: dict[str, LiveSession] = {}
        self._agy_version_cache: str | None = None

    @property
    def permission_rules_path(self) -> Path:
        return self._config.config_dir / "permission_rules.json"

    @property
    def gateway_prefs_path(self) -> Path:
        return self._config.config_dir / "gateway_prefs.json"

    def get_agy_version(self) -> str:
        """Probe `agy --version` once with scrubbed child env."""
        if self._agy_version_cache is not None:
            return self._agy_version_cache
        try:
            proc = subprocess.run(
                [self._config.agy_bin, "--version"],
                capture_output=True,
                text=True,
                timeout=5.0,
                env=build_child_env(home_override=self._config.home_dir),
                check=False,
            )
            raw = (proc.stdout or proc.stderr or "").strip().splitlines()
            first = raw[0].strip() if raw else ""
            if first.lower().startswith("agy "):
                first = first[4:].strip()
            version = first or "unknown"
            self._agy_version_cache = version
            return version
        except OSError:
            return "unavailable"

    def get_capabilities(self) -> dict[str, bool]:
        return {
            "load_session": True,
            "resume_session": True,
            "fork": False,
            "acp_rename": False,
            "gateway_title_override": True,
            "acp_delete": False,
            "set_additional_directories_live": False,
            "spawn_add_dir": True,
            "elicitation": True,
            "remote_control": True,
        }

    def get_default_config_options(self) -> list[dict[str, Any]]:
        prefs = read_json_file(self.gateway_prefs_path, {})
        opts = [dict(x) for x in DEFAULT_CONFIG_OPTIONS]
        if isinstance(prefs, dict):
            for opt in opts:
                cid = opt.get("id")
                if cid == "model" and prefs.get("model"):
                    opt["currentValue"] = str(prefs["model"])
                elif cid == "thought_level" and prefs.get("thought_level"):
                    opt["currentValue"] = str(prefs["thought_level"])
        return opts

    def has_live_session(self, session_id: str) -> bool:
        return session_id in self._sessions

    def get_live_session(self, session_id: str) -> LiveSession | None:
        return self._sessions.get(session_id)

    def _emit_session_event(
        self,
        session: LiveSession,
        event_type: str,
        payload: dict[str, Any],
    ) -> dict[str, Any]:
        session.updated_at = utc_now_iso()
        return self._replay.emit(session.session_id, event_type, payload)

    def _load_permission_rules(self) -> list[dict[str, Any]]:
        raw = read_json_file(self.permission_rules_path, [])
        return [r for r in raw if isinstance(r, dict)] if isinstance(raw, list) else []

    def _save_permission_rules(self, rules: list[dict[str, Any]]) -> None:
        atomic_write_json(self.permission_rules_path, rules, mode=0o600)

    def list_permission_rules(self, workspace: str | None = None) -> list[dict[str, Any]]:
        rules = self._load_permission_rules()
        if workspace:
            ws_real = validate_workspace_dir(workspace, self._config)
            rules = [r for r in rules if r.get("workspace_root") == ws_real]
        return rules

    def revoke_permission_rule(self, rule_id: str) -> dict[str, Any]:
        rules = self._load_permission_rules()
        kept = [r for r in rules if r.get("rule_id") != rule_id]
        if len(kept) == len(rules):
            raise GatewayError(404, "not_found", f"Permission rule not found: {rule_id}")
        self._save_permission_rules(kept)
        return {"revoked": True, "rule_id": rule_id}

    def _match_workspace_rule(
        self,
        workspace_root: str,
        tool_call: dict[str, Any],
    ) -> dict[str, Any] | None:
        tool_kind = str(tool_call.get("kind") or "other")
        is_cmd = _is_command_tool(tool_call, tool_kind)
        for rule in self._load_permission_rules():
            if rule.get("workspace_root") != workspace_root:
                continue
            if rule.get("tool_kind") != tool_kind:
                continue
            prefix = str(rule.get("command_prefix") or "")
            if is_cmd and not prefix:
                continue
            if _command_matches_prefix(tool_call, prefix, tool_kind=tool_kind):
                return rule
        return None

    async def _initialize_acp(self, acp: AcpClient) -> dict[str, Any]:
        res = await acp.request(
            "initialize",
            {
                "protocolVersion": ACP_PROTOCOL_VERSION,
                "clientCapabilities": {
                    "fs": {"readTextFile": True, "writeTextFile": True},
                    "terminal": True,
                    "elicitation": {"form": {}, "url": {}},
                },
                "clientInfo": {
                    "name": "gravity-well-gateway",
                    "version": GATEWAY_VERSION,
                },
            },
            timeout=15.0,
        )
        return res if isinstance(res, dict) else {}

    def _apply_acp_session_metadata(self, session: LiveSession, result: Any) -> None:
        if not isinstance(result, dict):
            return
        if isinstance(result.get("configOptions"), list):
            session.config_options = result["configOptions"]
        models = _normalize_available_models(result.get("models"))
        if models:
            session.available_models = models

    async def _handle_acp_update(self, session: LiveSession, update: dict[str, Any]) -> None:
        kind = str(update.get("sessionUpdate") or "")
        if not kind:
            return

        if kind in ("user_message_chunk", "agent_thought_chunk", "agent_message_chunk"):
            content = update.get("content")
            text = ""
            if isinstance(content, dict):
                text = str(content.get("text") or "")
            elif isinstance(update.get("text"), str):
                text = str(update["text"])
            if text and kind in ("user_message_chunk", "agent_message_chunk"):
                session.preview = (session.preview + text)[-200:].strip() if kind == "agent_message_chunk" else text[:200]
            self._emit_session_event(session, kind, {"text": text})

        elif kind == "tool_call":
            payload = {
                "tool_call_id": str(update.get("toolCallId") or ""),
                "title": str(update.get("title") or ""),
                "kind": str(update.get("kind") or "other"),
                "status": str(update.get("status") or "pending"),
                "raw_input": update.get("rawInput") if isinstance(update.get("rawInput"), dict) else {},
            }
            self._emit_session_event(session, "tool_call", payload)

        elif kind == "tool_call_update":
            payload = {
                "tool_call_id": str(update.get("toolCallId") or ""),
                "status": str(update.get("status") or ""),
                "title": str(update.get("title") or ""),
                "kind": str(update.get("kind") or ""),
                "raw_output": update.get("rawOutput") if isinstance(update.get("rawOutput"), dict) else {},
            }
            if "diff" in update:
                payload["diff"] = update["diff"]
            elif isinstance(payload["raw_output"], dict) and "diff" in payload["raw_output"]:
                payload["diff"] = payload["raw_output"]["diff"]
            self._emit_session_event(session, "tool_call_update", payload)

        elif kind == "usage_update":
            used = int(update.get("used") or 0)
            size = int(update.get("size") or 1048576)
            session.usage = {"used": used, "size": size}
            self._emit_session_event(session, "usage_update", {"used": used, "size": size})

        elif kind == "config_option_update":
            opts = update.get("configOptions")
            if isinstance(opts, list):
                session.config_options = opts
            self._emit_session_event(
                session,
                "config_option_update",
                {"config_options": list(session.config_options)},
            )

        elif kind == "available_commands_update":
            cmds = update.get("availableCommands")
            if isinstance(cmds, list):
                session.available_commands = cmds
            self._emit_session_event(
                session,
                "available_commands_update",
                {"commands": list(session.available_commands)},
            )

        elif kind == "session_info_update":
            new_title = str(update.get("title") or "").strip()
            if new_title and session.title_source != "gateway":
                session.title = new_title
                session.title_source = "acp"
                self._emit_session_event(
                    session,
                    "session_info_update",
                    {"title": session.title, "title_source": "acp"},
                )

    async def _on_session_notification(
        self,
        session: LiveSession,
        method: str,
        params: dict[str, Any],
    ) -> None:
        if method != "session/update":
            return
        update = params.get("update")
        if not isinstance(update, dict):
            return
        if not session.ready_for_events:
            session.early_updates.append(update)
            return
        await self._handle_acp_update(session, update)

    async def _on_session_server_request(
        self,
        session: LiveSession,
        method: str,
        params: dict[str, Any],
    ) -> dict[str, Any]:
        if method == "session/request_permission":
            return await self._broker_permission(session, params)
        if method == "elicitation/create":
            return await self._broker_question(session, params)
        raise AcpRpcError(-32601, f"Unsupported client method: {method}")

    async def _broker_permission(
        self,
        session: LiveSession,
        params: dict[str, Any],
    ) -> dict[str, Any]:
        raw_tc = params.get("toolCall")
        tool_call: dict[str, Any] = raw_tc if isinstance(raw_tc, dict) else {}
        raw_opts = params.get("options")
        options: list[dict[str, Any]] = (
            [o for o in raw_opts if isinstance(o, dict)] if isinstance(raw_opts, list) else []
        )
        request_id = f"perm_{secrets.token_hex(8)}"

        matched_rule = self._match_workspace_rule(session.cwd, tool_call)
        if matched_rule is not None:
            chosen_id = _select_option_id(options, ("allow_always", "allow_once")) or "allow-once"
            self._emit_session_event(
                session,
                "permission_auto_approved",
                {
                    "request_id": request_id,
                    "rule_id": matched_rule.get("rule_id"),
                    "tool_call": tool_call,
                    "option_id": chosen_id,
                },
            )
            self._emit_session_event(
                session,
                "permission_resolved",
                {
                    "request_id": request_id,
                    "decision": "allow_rule",
                    "option_id": chosen_id,
                    "auto_approved": True,
                    "rule_id": matched_rule.get("rule_id"),
                },
            )
            return {"outcome": {"outcome": "selected", "optionId": chosen_id}}

        loop = asyncio.get_running_loop()
        fut: asyncio.Future[dict[str, Any]] = loop.create_future()
        bound_turn_id = session.active_turn_id
        pending = PendingPermission(
            request_id=request_id,
            tool_call=tool_call,
            options=options,
            future=fut,
        )
        session.pending_permissions[request_id] = pending
        session.status = "awaiting_approval"
        self._emit_session_event(session, "session_status", {"status": "awaiting_approval"})
        self._emit_session_event(
            session,
            "permission_requested",
            {
                "request_id": request_id,
                "tool_call": tool_call,
                "options": options,
            },
        )
        try:
            return await fut
        finally:
            session.pending_permissions.pop(request_id, None)
            if (
                bound_turn_id is not None
                and session.active_turn_id == bound_turn_id
                and session.status == "awaiting_approval"
            ):
                if session.pending_questions:
                    session.status = "awaiting_question"
                    self._emit_session_event(session, "session_status", {"status": "awaiting_question"})
                elif not session.pending_permissions:
                    session.status = "running"
                    self._emit_session_event(session, "session_status", {"status": "running"})

    async def _broker_question(
        self,
        session: LiveSession,
        params: dict[str, Any],
    ) -> dict[str, Any]:
        mode = str(params.get("mode") or "form")
        message = str(params.get("message") or "")
        raw_schema = params.get("requestedSchema")
        schema: dict[str, Any] = raw_schema if isinstance(raw_schema, dict) else {}
        request_id = f"qst_{secrets.token_hex(8)}"
        loop = asyncio.get_running_loop()
        fut: asyncio.Future[dict[str, Any]] = loop.create_future()
        bound_turn_id = session.active_turn_id
        pending = PendingQuestion(
            request_id=request_id,
            mode=mode,
            message=message,
            requested_schema=schema,
            future=fut,
        )
        session.pending_questions[request_id] = pending
        session.status = "awaiting_question"
        self._emit_session_event(session, "session_status", {"status": "awaiting_question"})
        self._emit_session_event(
            session,
            "question_requested",
            {
                "request_id": request_id,
                "mode": mode,
                "message": message,
                "requested_schema": schema,
            },
        )
        try:
            return await fut
        finally:
            session.pending_questions.pop(request_id, None)
            if (
                bound_turn_id is not None
                and session.active_turn_id == bound_turn_id
                and session.status == "awaiting_question"
            ):
                if session.pending_permissions:
                    session.status = "awaiting_approval"
                    self._emit_session_event(session, "session_status", {"status": "awaiting_approval"})
                elif not session.pending_questions:
                    session.status = "running"
                    self._emit_session_event(session, "session_status", {"status": "running"})

    def _cancel_pending_gates(
        self,
        live: LiveSession,
        reason: str = "cancelled",
    ) -> None:
        """Resolve and clear any pending permission/question gates on turn cancel or end."""
        for req_id, perm in list(live.pending_permissions.items()):
            live.pending_permissions.pop(req_id, None)
            if not perm.future.done():
                perm.future.set_result({"outcome": {"outcome": "cancelled"}})
            self._emit_session_event(
                live,
                "permission_resolved",
                {
                    "request_id": req_id,
                    "decision": reason,
                    "option_id": None,
                    "auto_approved": False,
                    "rule_id": None,
                },
            )
        live.pending_permissions.clear()

        event_action = "cancelled" if reason == "cancelled" else reason
        for req_id, qst in list(live.pending_questions.items()):
            live.pending_questions.pop(req_id, None)
            if not qst.future.done():
                qst.future.set_result({"action": "cancel", "content": {}})
            self._emit_session_event(
                live,
                "question_resolved",
                {
                    "request_id": req_id,
                    "action": event_action,
                    "answers": {},
                },
            )
        live.pending_questions.clear()

    async def _on_session_exit(
        self,
        session: LiveSession,
        returncode: int | None,
        reason: str,
    ) -> None:
        if session.status in ("closing", "closed"):
            return
        safe_reason = redact_text(reason)
        session.queue_paused = True
        session.active_turn_id = None
        session.status = "error"
        for perm in list(session.pending_permissions.values()):
            if not perm.future.done():
                perm.future.set_exception(AcpUpstreamError(safe_reason, returncode=returncode))
        session.pending_permissions.clear()
        for qst in list(session.pending_questions.values()):
            if not qst.future.done():
                qst.future.set_exception(AcpUpstreamError(safe_reason, returncode=returncode))
        session.pending_questions.clear()

        self._emit_session_event(
            session,
            "session_status",
            {"status": "error", "reason": safe_reason},
        )
        self._emit_session_event(
            session,
            "queue_updated",
            {"items": [i.to_dict() for i in session.queue], "paused": session.queue_paused},
        )
        self._emit_session_event(
            session,
            "session_closed",
            {"exit_code": returncode, "reason": "unexpected_exit"},
        )

    def _map_acp_error(self, exc: Exception, fallback_method: str = "") -> GatewayError:
        if isinstance(exc, GatewayError):
            return exc
        if isinstance(exc, AcpRpcError):
            safe_msg = redact_text(exc.message)
            if exc.code == -32002:
                return GatewayError(404, "not_found", safe_msg, details={"acp_code": exc.code})
            if exc.code == -32601:
                return GatewayError(
                    501,
                    "acp_method_unsupported",
                    f"Antigravity CLI {redact_text(self.get_agy_version())} does not support {fallback_method or 'method'} (-32601).",
                    details={"acp_method": fallback_method, "acp_code": -32601},
                )
            if exc.code == -32602:
                return GatewayError(
                    422,
                    "validation_error",
                    safe_msg,
                    details={"acp_code": -32602, "data": _redact_nested(exc.data)},
                )
            return GatewayError(
                502,
                "acp_upstream_error",
                safe_msg,
                details={"acp_code": exc.code},
            )
        if isinstance(exc, AcpUpstreamError):
            safe_err = redact_text(str(exc))
            if "not found" in safe_err.lower() and "binary" in safe_err.lower():
                return GatewayError(503, "service_unavailable", safe_err)
            return GatewayError(502, "acp_upstream_error", safe_err)
        return GatewayError(502, "acp_upstream_error", redact_text(str(exc)))

    async def create_session(
        self,
        cwd: str | None = None,
        add_dirs: Sequence[str] = (),
        title: str | None = None,
        model: str | None = None,
        thought_level: str | None = None,
        mode: str | None = None,
        agent: str | None = None,
        sandbox: bool = False,
    ) -> dict[str, Any]:
        """Spawn `agy acp --stdio`, run `initialize` + `session/new`, and apply initial config."""
        real_cwd = validate_workspace_dir(cwd or str(self._config.default_cwd), self._config)
        real_add_dirs = [validate_workspace_dir(d, self._config) for d in add_dirs]
        safe_agent = _validate_agent_slug(agent) if agent is not None else None

        if title is not None:
            trimmed_title = title.strip()
            if not trimmed_title or len(trimmed_title) > 120:
                raise GatewayError(422, "validation_error", "Title must be 1..120 non-empty characters.")
        else:
            trimmed_title = None

        holder: dict[str, LiveSession] = {}

        async def _notif(method: str, params: dict[str, Any]) -> None:
            if "session" in holder:
                await self._on_session_notification(holder["session"], method, params)

        async def _sreq(method: str, params: dict[str, Any]) -> dict[str, Any]:
            return await self._on_session_server_request(holder["session"], method, params)

        async def _exit(rc: int | None, reason: str) -> None:
            if "session" in holder:
                await self._on_session_exit(holder["session"], rc, reason)

        acp = AcpClient(
            config=self._config,
            cwd=real_cwd,
            add_dirs=real_add_dirs,
            agent=safe_agent,
            sandbox=sandbox,
            on_notification=_notif,
            on_server_request=_sreq,
            on_exit=_exit,
        )
        live = LiveSession(
            session_id="",
            cwd=real_cwd,
            add_dirs=real_add_dirs,
            acp=acp,
            agent=safe_agent,
            sandbox=sandbox,
        )
        holder["session"] = live

        try:
            await acp.start()
            await self._initialize_acp(acp)
            new_res = await acp.request("session/new", {"cwd": real_cwd, "mcpServers": []})
            if not isinstance(new_res, dict) or not new_res.get("sessionId"):
                raise AcpUpstreamError("session/new did not return a sessionId.")

            session_id = validate_session_id(str(new_res["sessionId"]))
            live.session_id = session_id
            self._apply_acp_session_metadata(live, new_res)

            prefs = read_json_file(self.gateway_prefs_path, {})
            eff_model = model or (prefs.get("model") if isinstance(prefs, dict) else None)
            eff_thought = thought_level or (
                prefs.get("thought_level") if isinstance(prefs, dict) else None
            )

            for cfg_id, cfg_val in (
                ("model", eff_model),
                ("thought_level", eff_thought),
                ("mode", mode),
            ):
                if cfg_val is not None:
                    cfg_res = await acp.request(
                        "session/set_config_option",
                        {"sessionId": session_id, "configId": cfg_id, "value": cfg_val},
                    )
                    if isinstance(cfg_res, dict) and isinstance(cfg_res.get("configOptions"), list):
                        live.config_options = cfg_res["configOptions"]

            if trimmed_title:
                live.title = trimmed_title
                live.title_source = "gateway"
                self._storage.set_title_override(session_id, trimmed_title, cwd=real_cwd)
            else:
                meta = self._storage.get_session_meta(session_id)
                if meta and meta.get("title"):
                    live.title = str(meta["title"])
                    live.title_source = "gateway"

            live.status = "idle"
            self._sessions[session_id] = live
            self._emit_session_event(
                live,
                "session_started",
                {"snapshot": live.to_snapshot(self._replay)},
            )
            live.ready_for_events = True
            for buffered_update in list(live.early_updates):
                await self._handle_acp_update(live, buffered_update)
            live.early_updates.clear()

            return live.to_snapshot(self._replay)
        except BaseException as exc:
            if live.session_id:
                self._sessions.pop(live.session_id, None)
            await acp.close()
            if isinstance(exc, Exception):
                raise self._map_acp_error(exc, "session/new") from exc
            raise

    async def resume_session(
        self,
        session_id: str,
        cwd: str | None = None,
        load_history: bool = False,
        add_dirs: Sequence[str] = (),
        sandbox: bool = False,
    ) -> dict[str, Any]:
        """Attach a live `agy acp --stdio` supervisor via `session/resume` or `session/load`."""
        validate_session_id(session_id)
        existing = self._sessions.get(session_id)
        carried_queue: list[QueueItem] = []
        if existing is not None and existing.acp.is_running and existing.status != "error" and not load_history:
            return existing.to_snapshot(self._replay)
        if existing is not None:
            carried_queue = list(existing.queue)
            await self.close_session(session_id)

        archived = self._storage.get_archived_conversation(session_id)
        meta = self._storage.get_session_meta(session_id)
        if meta and meta.get("deleted"):
            raise GatewayError(404, "not_found", f"Session was deleted: {session_id}")
        resolved_cwd_input = (
            cwd
            or (meta.get("cwd") if meta and meta.get("cwd") else None)
            or (archived.get("cwd") if archived else None)
            or str(self._config.default_cwd)
        )
        real_cwd = validate_workspace_dir(str(resolved_cwd_input), self._config)
        real_add_dirs = [validate_workspace_dir(d, self._config) for d in add_dirs]

        holder: dict[str, LiveSession] = {}

        async def _notif(method: str, params: dict[str, Any]) -> None:
            if "session" in holder:
                await self._on_session_notification(holder["session"], method, params)

        async def _sreq(method: str, params: dict[str, Any]) -> dict[str, Any]:
            return await self._on_session_server_request(holder["session"], method, params)

        async def _exit(rc: int | None, reason: str) -> None:
            if "session" in holder:
                await self._on_session_exit(holder["session"], rc, reason)

        acp = AcpClient(
            config=self._config,
            cwd=real_cwd,
            add_dirs=real_add_dirs,
            sandbox=sandbox,
            on_notification=_notif,
            on_server_request=_sreq,
            on_exit=_exit,
        )
        initial_title = (
            (str(meta["title"]) if meta and meta.get("title") else None)
            or (str(archived["title"]) if archived and archived.get("title") else None)
            or "Resumed Conversation"
        )
        initial_title_source = "gateway" if (meta and meta.get("title")) else "acp"
        initial_pinned = bool(meta.get("pinned", False)) if meta else False
        initial_pinned_at = str(meta["pinned_at"]) if meta and meta.get("pinned_at") else None

        live = LiveSession(
            session_id=session_id,
            cwd=real_cwd,
            add_dirs=real_add_dirs,
            acp=acp,
            title=initial_title,
            title_source=initial_title_source,
            sandbox=sandbox,
            step_count=int(archived["step_count"]) if archived else 0,
            preview=str(archived["preview"]) if archived else "",
            queue=carried_queue,
            queue_paused=bool(carried_queue),
            ready_for_events=True,
            pinned=initial_pinned,
            pinned_at=initial_pinned_at,
            archived=False,
        )
        holder["session"] = live

        acp_method = "session/load" if load_history else "session/resume"
        acp_params: dict[str, Any] = {"sessionId": session_id, "cwd": real_cwd}
        if load_history:
            acp_params["mcpServers"] = []

        try:
            await acp.start()
            await self._initialize_acp(acp)
            res = await acp.request(acp_method, acp_params, timeout=60.0)
            self._apply_acp_session_metadata(live, res)
            if meta and meta.get("archived"):
                self._storage.update_session_meta(session_id, archived=False, cwd=real_cwd)
            live.status = "idle"
            self._sessions[session_id] = live
            self._emit_session_event(
                live,
                "session_started",
                {"snapshot": live.to_snapshot(self._replay)},
            )
            return live.to_snapshot(self._replay)
        except BaseException as exc:
            await acp.close()
            if isinstance(exc, Exception):
                raise self._map_acp_error(exc, acp_method) from exc
            raise

    async def fork_session(self, session_id: str, cwd: str | None = None) -> dict[str, Any]:
        """Attempt ACP `session/fork`; maps `-32601` to HTTP 501 `acp_method_unsupported` (D4)."""
        validate_session_id(session_id)
        live = self._sessions.get(session_id)
        if live is not None and live.acp.is_running:
            try:
                res = await live.acp.request(
                    "session/fork",
                    {"sessionId": session_id, "cwd": live.cwd},
                    timeout=15.0,
                )
                if isinstance(res, dict) and res.get("sessionId"):
                    new_id = validate_session_id(str(res["sessionId"]))
                    return await self.resume_session(new_id, cwd=live.cwd)
                raise AcpUpstreamError("session/fork returned invalid response.")
            except AcpRpcError as exc:
                if exc.code == -32601:
                    raise GatewayError(
                        501,
                        "acp_method_unsupported",
                        f"Antigravity CLI {self.get_agy_version()} does not support session/fork (-32601).",
                        details={
                            "acp_method": "session/fork",
                            "acp_code": -32601,
                            "fallback": "Create a new session in the same cwd or resume with load_history=true.",
                        },
                    ) from exc
                raise self._map_acp_error(exc, "session/fork") from exc

        archived = self._storage.get_archived_conversation(session_id)
        if archived is None and self._storage.get_session_meta(session_id) is None:
            raise GatewayError(404, "not_found", f"Unknown session: {session_id}")

        target_cwd = validate_workspace_dir(
            cwd or (archived["cwd"] if archived else str(self._config.default_cwd)),
            self._config,
        )
        temp_acp = AcpClient(config=self._config, cwd=target_cwd)
        try:
            await temp_acp.start()
            await self._initialize_acp(temp_acp)
            res = await temp_acp.request(
                "session/fork",
                {"sessionId": session_id, "cwd": target_cwd},
                timeout=15.0,
            )
            if isinstance(res, dict) and res.get("sessionId"):
                new_id = validate_session_id(str(res["sessionId"]))
                await temp_acp.close()
                return await self.resume_session(new_id, cwd=target_cwd)
            raise AcpUpstreamError("session/fork returned invalid response.")
        except AcpRpcError as exc:
            if exc.code == -32601:
                raise GatewayError(
                    501,
                    "acp_method_unsupported",
                    f"Antigravity CLI {self.get_agy_version()} does not support session/fork (-32601).",
                    details={
                        "acp_method": "session/fork",
                        "acp_code": -32601,
                        "fallback": "Create a new session in the same cwd or resume with load_history=true.",
                    },
                ) from exc
            raise self._map_acp_error(exc, "session/fork") from exc
        finally:
            await temp_acp.close()

    def rename_session(self, session_id: str, title: str) -> dict[str, Any]:
        """Persist custom title override in session_meta.json and emit synthetic session_info_update."""
        validate_session_id(session_id)
        trimmed = title.strip()
        if not trimmed or len(trimmed) > 120:
            raise GatewayError(
                422,
                "validation_error",
                "Title must be between 1 and 120 non-whitespace characters.",
            )

        live = self._sessions.get(session_id)
        archived = None if live is not None else self._storage.get_archived_conversation(session_id)
        existing_meta = self._storage.get_session_meta(session_id)
        if (existing_meta and existing_meta.get("deleted")) or (
            live is None and archived is None and existing_meta is None
        ):
            raise GatewayError(404, "not_found", f"Unknown session: {session_id}")

        cwd = live.cwd if live is not None else (archived["cwd"] if archived else None)
        self._storage.update_session_meta(session_id, title=trimmed, cwd=cwd)

        if live is not None:
            live.title = trimmed
            live.title_source = "gateway"
            self._emit_session_event(
                live,
                "session_info_update",
                {
                    "title": trimmed,
                    "title_source": "gateway",
                    "pinned": live.pinned,
                    "archived": live.archived,
                },
            )
            return live.to_snapshot(self._replay)

        self._replay.emit(
            session_id,
            "session_info_update",
            {"title": trimmed, "title_source": "gateway"},
        )
        return self.get_session(session_id)

    async def update_session(
        self,
        session_id: str,
        *,
        title: str | None = None,
        pinned: bool | None = None,
        archived: bool | None = None,
    ) -> dict[str, Any]:
        """Update session title, pin state, and/or archive state."""
        validate_session_id(session_id)
        if title is None and pinned is None and archived is None:
            raise GatewayError(400, "bad_request", "At least one of title, pinned, or archived must be provided.")

        trimmed_title: str | None = None
        if title is not None:
            trimmed_title = title.strip()
            if not trimmed_title or len(trimmed_title) > 120:
                raise GatewayError(
                    422,
                    "validation_error",
                    "Title must be between 1 and 120 non-whitespace characters.",
                )

        live = self._sessions.get(session_id)
        arch_row = None if live is not None else self._storage.get_archived_conversation(session_id)
        existing_meta = self._storage.get_session_meta(session_id)
        if (existing_meta and existing_meta.get("deleted")) or (
            live is None and arch_row is None and existing_meta is None
        ):
            raise GatewayError(404, "not_found", f"Unknown session: {session_id}")

        cwd = (
            live.cwd
            if live is not None
            else (
                arch_row["cwd"]
                if arch_row
                else (existing_meta.get("cwd") if existing_meta else None)
            )
        )
        effective_title = trimmed_title
        if effective_title is None and live is not None and not (existing_meta and existing_meta.get("title")):
            effective_title = live.title

        # Archiving a live session gracefully tears down its ACP subprocess
        if archived is True and live is not None:
            await self.close_session(session_id)
            live = None

        updated_meta = self._storage.update_session_meta(
            session_id,
            title=effective_title if (trimmed_title is not None or (archived is True and effective_title)) else None,
            cwd=cwd,
            pinned=pinned,
            archived=archived,
        )

        if live is not None:
            if trimmed_title is not None:
                live.title = trimmed_title
                live.title_source = "gateway"
            if pinned is not None:
                live.pinned = bool(pinned)
                live.pinned_at = updated_meta.get("pinned_at")
            if archived is not None:
                live.archived = bool(archived)
            self._emit_session_event(
                live,
                "session_info_update",
                {
                    "title": live.title,
                    "title_source": live.title_source,
                    "pinned": live.pinned,
                    "pinned_at": live.pinned_at,
                    "archived": live.archived,
                },
            )
            return live.to_snapshot(self._replay)

        snap = self.get_session(session_id)
        self._replay.emit(
            session_id,
            "session_info_update",
            {
                "title": snap["title"],
                "title_source": snap["title_source"],
                "pinned": snap["pinned"],
                "pinned_at": snap["pinned_at"],
                "archived": snap["archived"],
            },
        )
        return snap

    async def delete_session(self, session_id: str) -> dict[str, Any]:
        """Permanently delete/tombstone a live or archived session in Gravity Well."""
        validate_session_id(session_id)
        live = self._sessions.get(session_id)
        arch_row = None if live is not None else self._storage.get_archived_conversation(session_id)
        existing_meta = self._storage.get_session_meta(session_id)
        if (existing_meta and existing_meta.get("deleted")) or (
            live is None and arch_row is None and existing_meta is None
        ):
            raise GatewayError(404, "not_found", f"Session not found: {session_id}")

        if live is not None:
            await self.close_session(session_id)

        self._storage.update_session_meta(session_id, deleted=True)
        self._storage.delete_session_uploads(session_id)
        self._replay.emit(
            session_id,
            "session_deleted",
            {"session_id": session_id, "deleted": True},
        )
        return {"session_id": session_id, "status": "deleted", "deleted": True}

    async def update_session_config(
        self,
        session_id: str,
        model: str | None = None,
        thought_level: str | None = None,
        mode: str | None = None,
    ) -> dict[str, Any]:
        """Update live session config options via ACP `session/set_config_option` (D5)."""
        validate_session_id(session_id)
        live = self._sessions.get(session_id)
        if live is None or not live.acp.is_running:
            raise GatewayError(404, "not_found", f"Live session not found: {session_id}")

        updates = [
            ("model", model),
            ("thought_level", thought_level),
            ("mode", mode),
        ]
        non_empty = [(cid, val) for cid, val in updates if val is not None]
        if not non_empty:
            raise GatewayError(400, "bad_request", "At least one config option must be provided.")

        try:
            for cid, val in non_empty:
                res = await live.acp.request(
                    "session/set_config_option",
                    {"sessionId": session_id, "configId": cid, "value": val},
                    timeout=15.0,
                )
                if isinstance(res, dict) and isinstance(res.get("configOptions"), list):
                    live.config_options = res["configOptions"]
            live.updated_at = utc_now_iso()
            return live.to_snapshot(self._replay)
        except Exception as exc:
            raise self._map_acp_error(exc, "session/set_config_option") from exc

    async def close_session(self, session_id: str) -> dict[str, Any]:
        """Gracefully tear down a live session without deleting archived history."""
        validate_session_id(session_id)
        live = self._sessions.get(session_id)
        if live is None:
            raise GatewayError(404, "not_found", f"Live session not found: {session_id}")
        if live.status == "closing":
            raise GatewayError(409, "conflict", f"Session {session_id} is already closing.")

        live.status = "closing"
        self._emit_session_event(live, "session_status", {"status": "closing"})

        for perm in list(live.pending_permissions.values()):
            if not perm.future.done():
                perm.future.cancel()
        live.pending_permissions.clear()
        for qst in list(live.pending_questions.values()):
            if not qst.future.done():
                qst.future.cancel()
        live.pending_questions.clear()

        rc = await live.acp.close(session_id=session_id)
        if live.turn_task is not None and not live.turn_task.done():
            live.turn_task.cancel()
            await asyncio.gather(live.turn_task, return_exceptions=True)

        live.status = "closed"
        self._sessions.pop(session_id, None)
        self._emit_session_event(
            live,
            "session_closed",
            {"exit_code": rc if rc is not None else 0, "reason": "closed"},
        )
        return {"session_id": session_id, "status": "closed"}

    def get_session(self, session_id: str) -> dict[str, Any]:
        """Return live SessionSnapshot or archived summary snapshot (404 if unknown)."""
        validate_session_id(session_id)
        live = self._sessions.get(session_id)
        if live is not None:
            return live.to_snapshot(self._replay)

        archived = self._storage.get_archived_conversation(session_id)
        meta = self._storage.get_session_meta(session_id)
        if (meta and meta.get("deleted")) or (archived is None and meta is None):
            raise GatewayError(404, "not_found", f"Session not found: {session_id}")

        title = (
            (str(meta["title"]) if meta and meta.get("title") else None)
            or (str(archived["title"]) if archived else "Archived Conversation")
        )
        title_source = "gateway" if (meta and meta.get("title")) else "acp"
        cwd = (
            (str(meta["cwd"]) if meta and meta.get("cwd") else None)
            or (str(archived["cwd"]) if archived else str(self._config.default_cwd))
        )
        updated_at = (
            (str(archived["last_modified_time"]) if archived and archived.get("last_modified_time") else None)
            or (str(meta["updated_at"]) if meta and meta.get("updated_at") else utc_now_iso())
        )
        is_pinned = bool(meta.get("pinned", False)) if meta else False
        pinned_at = str(meta["pinned_at"]) if meta and meta.get("pinned_at") else None
        is_archived = bool(meta.get("archived", False)) if meta else False
        return {
            "session_id": session_id,
            "title": title,
            "title_source": title_source,
            "cwd": cwd,
            "add_dirs": [],
            "status": "archived",
            "is_live": False,
            "pinned": is_pinned,
            "pinned_at": pinned_at,
            "archived": is_archived,
            "agent": None,
            "sandbox": False,
            "config_options": self.get_default_config_options(),
            "available_models": [],
            "available_commands": [],
            "usage": {"used": 0, "size": 1048576},
            "pending_permissions": [],
            "pending_questions": [],
            "queued_followups": [],
            "cursor": self._replay.session_cursor(session_id),
            "seq": self._replay.session_seq(session_id),
            "updated_at": updated_at,
        }

    def list_sessions(
        self,
        status: str = "all",
        q: str | None = None,
        cwd: str | None = None,
        limit: int = 50,
        offset: int = 0,
    ) -> dict[str, Any]:
        """Merge live supervised sessions with `?mode=ro` archived conversations."""
        if status not in ("all", "live", "archived"):
            raise GatewayError(400, "bad_request", f"Invalid status filter: {status}")

        clamped_limit = max(1, min(limit, 200))
        clamped_offset = max(0, offset)
        cwd_real = validate_workspace_dir(cwd, self._config) if cwd else None
        q_lower = q.strip().lower() if q and q.strip() else ""

        merged: list[dict[str, Any]] = []
        live_ids: set[str] = set()
        seen_ids: set[str] = set()
        all_meta = self._storage.get_title_overrides()

        if status in ("all", "live"):
            for sid, sess in self._sessions.items():
                meta = all_meta.get(sid) if isinstance(all_meta.get(sid), dict) else None
                if meta and meta.get("deleted"):
                    continue
                live_ids.add(sid)
                seen_ids.add(sid)
                if cwd_real and sess.cwd != cwd_real:
                    continue
                if q_lower:
                    haystack = f"{sess.title}\n{sess.preview}\n{sess.cwd}".lower()
                    if q_lower not in haystack:
                        continue
                merged.append(sess.to_list_item())
        else:
            live_ids = set(self._sessions.keys())

        if status in ("all", "archived"):
            arch_res = self._storage.search_conversations(
                q=q,
                cwd=cwd_real,
                limit=200,
                offset=0,
            )
            for row in arch_res["items"]:
                cid = str(row["conversation_id"])
                if cid in live_ids:
                    continue
                explicit_archived = bool(row.get("explicit_archived", False))
                if status == "all" and explicit_archived:
                    continue
                seen_ids.add(cid)
                ws_paths = row.get("workspace_paths") or []
                merged.append(
                    {
                        "session_id": cid,
                        "title": row["title"],
                        "preview": row.get("preview") or row.get("snippet") or "",
                        "cwd": ws_paths[0] if ws_paths else str(self._config.default_cwd),
                        "is_live": False,
                        "status": "archived",
                        "pinned": bool(row.get("pinned", False)),
                        "pinned_at": row.get("pinned_at"),
                        "archived": explicit_archived,
                        "step_count": int(row.get("step_count") or 0),
                        "model_id": None,
                        "thought_level": None,
                        "mode_id": None,
                        "updated_at": row.get("last_modified_time") or "",
                    }
                )

            # Also include explicitly archived sessions tracked only in session_meta.json
            if status == "archived":
                for cid, meta in all_meta.items():
                    if cid in live_ids or cid in seen_ids or not isinstance(meta, dict):
                        continue
                    if meta.get("deleted") or not meta.get("archived"):
                        continue
                    meta_cwd = str(meta.get("cwd") or self._config.default_cwd)
                    if cwd_real and os.path.realpath(meta_cwd) != cwd_real:
                        continue
                    meta_title = str(meta.get("title") or "Archived Conversation")
                    if q_lower and q_lower not in f"{meta_title}\n{meta_cwd}".lower():
                        continue
                    seen_ids.add(cid)
                    merged.append(
                        {
                            "session_id": cid,
                            "title": meta_title,
                            "preview": "",
                            "cwd": meta_cwd,
                            "is_live": False,
                            "status": "archived",
                            "pinned": bool(meta.get("pinned", False)),
                            "pinned_at": meta.get("pinned_at"),
                            "archived": True,
                            "step_count": 0,
                            "model_id": None,
                            "thought_level": None,
                            "mode_id": None,
                            "updated_at": str(meta.get("updated_at") or ""),
                        }
                    )

        merged.sort(
            key=lambda item: (
                1 if item.get("pinned") else 0,
                str(item.get("pinned_at") or ""),
                1 if item["is_live"] else 0,
                item["updated_at"],
            ),
            reverse=True,
        )
        total = len(merged)
        return {
            "items": merged[clamped_offset : clamped_offset + clamped_limit],
            "total": total,
            "limit": clamped_limit,
            "offset": clamped_offset,
        }

    def get_session_roots(self, session_id: str) -> list[str]:
        validate_session_id(session_id)
        live = self._sessions.get(session_id)
        if live is not None:
            return [live.cwd, *live.add_dirs]
        archived = self._storage.get_archived_conversation(session_id)
        meta = self._storage.get_session_meta(session_id)
        if archived is None and meta is None:
            raise GatewayError(404, "not_found", f"Session not found: {session_id}")
        raw_cwd = (meta.get("cwd") if meta and meta.get("cwd") else None) or (
            archived["cwd"] if archived else str(self._config.default_cwd)
        )
        return [validate_workspace_dir(str(raw_cwd), self._config)]

    def _build_prompt_blocks(
        self,
        session_id: str,
        text: str | None,
        parts: list[dict[str, Any]] | None,
        attachment_ids: Sequence[str],
    ) -> tuple[list[dict[str, Any]], str]:
        blocks: list[dict[str, Any]] = []
        display_chunks: list[str] = []

        if parts:
            for part in parts:
                if not isinstance(part, dict) or not part.get("type"):
                    raise GatewayError(422, "validation_error", "Invalid prompt content part.")
                blocks.append(dict(part))
                if part.get("type") == "text" and part.get("text"):
                    display_chunks.append(str(part["text"]))
        elif text is not None and text.strip():
            blocks.append({"type": "text", "text": text})
            display_chunks.append(text)

        if not blocks:
            raise GatewayError(
                400,
                "bad_request",
                "Either non-empty `text` or non-empty `parts` must be provided.",
            )

        if attachment_ids:
            blocks.extend(self._storage.resolve_attachments(session_id, attachment_ids))

        return blocks, "\n".join(display_chunks)

    def _start_turn_background(
        self,
        live: LiveSession,
        turn_id: str,
        prompt_blocks: list[dict[str, Any]],
        display_text: str,
        parts: list[dict[str, Any]] | None = None,
        attachment_ids: Sequence[str] = (),
    ) -> None:
        live.active_turn_id = turn_id
        live.status = "running"
        live.turn_task = asyncio.create_task(
            self._run_turn_loop(
                live,
                turn_id,
                prompt_blocks,
                display_text,
                parts,
                list(attachment_ids),
            )
        )

    async def _run_turn_loop(
        self,
        live: LiveSession,
        initial_turn_id: str,
        initial_blocks: list[dict[str, Any]],
        initial_text: str,
        initial_parts: list[dict[str, Any]] | None,
        initial_attachment_ids: list[str] | None = None,
    ) -> None:
        turn_id = initial_turn_id
        blocks = initial_blocks
        display_text = initial_text
        parts = initial_parts
        current_attachment_ids = list(initial_attachment_ids or ())

        async with live.prompt_lock:
            while True:
                live.active_turn_id = turn_id
                live.status = "running"
                live.step_count += 1
                if display_text:
                    live.preview = display_text[:200]

                self._emit_session_event(live, "session_status", {"status": "running"})
                user_payload: dict[str, Any] = {"text": display_text}
                if parts:
                    user_payload["parts"] = parts
                if current_attachment_ids:
                    att_meta = self._storage.describe_attachments(
                        live.session_id,
                        current_attachment_ids,
                    )
                    if att_meta:
                        user_payload["attachments"] = att_meta
                self._emit_session_event(live, "user_message_chunk", user_payload)

                try:
                    res = await live.acp.request(
                        "session/prompt",
                        {"sessionId": live.session_id, "prompt": blocks},
                        timeout=None,
                    )
                except Exception as exc:
                    self._cancel_pending_gates(live, reason="cancelled")
                    live.queue_paused = True
                    live.active_turn_id = None
                    if live.status not in ("closing", "closed"):
                        live.status = "error"
                        self._emit_session_event(
                            live,
                            "session_status",
                            {"status": "error", "reason": redact_text(str(exc))},
                        )
                        self._emit_session_event(
                            live,
                            "queue_updated",
                            {
                                "items": [i.to_dict() for i in live.queue],
                                "paused": live.queue_paused,
                            },
                        )
                    return

                self._cancel_pending_gates(live, reason="cancelled")
                stop_reason = (
                    str(res.get("stopReason") or "end_turn")
                    if isinstance(res, dict)
                    else "end_turn"
                )
                turn_usage = res.get("usage") if isinstance(res, dict) and isinstance(res.get("usage"), dict) else {}
                self._emit_session_event(
                    live,
                    "turn_completed",
                    {
                        "turn_id": turn_id,
                        "stop_reason": stop_reason,
                        "usage": turn_usage,
                    },
                )

                # Priority 1: `send-now` item explicitly marked `dispatch_on_settle`
                if live.queue and live.queue[0].dispatch_on_settle:
                    next_item = live.queue.pop(0)
                    live.renumber_queue()
                    self._emit_session_event(
                        live,
                        "queue_updated",
                        {
                            "items": [i.to_dict() for i in live.queue],
                            "paused": live.queue_paused,
                        },
                    )
                    try:
                        blocks, display_text = self._build_prompt_blocks(
                            live.session_id,
                            next_item.text,
                            None,
                            next_item.attachment_ids,
                        )
                    except GatewayError as exc:
                        live.queue_paused = True
                        live.active_turn_id = None
                        live.status = "error"
                        self._emit_session_event(
                            live,
                            "session_status",
                            {"status": "error", "reason": exc.message},
                        )
                        return
                    parts = None
                    current_attachment_ids = list(next_item.attachment_ids)
                    turn_id = f"turn_{secrets.token_hex(8)}"
                    continue

                # Priority 2: Clean `end_turn` auto-drain when queue is unpaused
                if stop_reason == "end_turn" and not live.queue_paused and live.queue:
                    next_item = live.queue.pop(0)
                    live.renumber_queue()
                    self._emit_session_event(
                        live,
                        "queue_updated",
                        {
                            "items": [i.to_dict() for i in live.queue],
                            "paused": live.queue_paused,
                        },
                    )
                    try:
                        blocks, display_text = self._build_prompt_blocks(
                            live.session_id,
                            next_item.text,
                            None,
                            next_item.attachment_ids,
                        )
                    except GatewayError as exc:
                        live.queue_paused = True
                        live.active_turn_id = None
                        live.status = "error"
                        self._emit_session_event(
                            live,
                            "session_status",
                            {"status": "error", "reason": exc.message},
                        )
                        return
                    parts = None
                    current_attachment_ids = list(next_item.attachment_ids)
                    turn_id = f"turn_{secrets.token_hex(8)}"
                    continue

                # Priority 3: Cancelled or non-end_turn -> pause queue, do NOT auto-drain
                if stop_reason != "end_turn":
                    live.queue_paused = True
                    self._emit_session_event(
                        live,
                        "queue_updated",
                        {
                            "items": [i.to_dict() for i in live.queue],
                            "paused": live.queue_paused,
                        },
                    )

                live.active_turn_id = None
                if live.status not in ("closing", "closed"):
                    live.status = "idle"
                    self._emit_session_event(
                        live,
                        "session_status",
                        {"status": "idle", "reason": stop_reason},
                    )
                return

    async def submit_prompt(
        self,
        session_id: str,
        text: str | None = None,
        parts: list[dict[str, Any]] | None = None,
        attachment_ids: Sequence[str] = (),
        enqueue_if_busy: bool = True,
        auto_resume: bool = False,
        cwd: str | None = None,
    ) -> dict[str, Any]:
        """Start a prompt turn if idle, or enqueue/409 when busy."""
        validate_session_id(session_id)
        live = self._sessions.get(session_id)
        if live is None or not live.acp.is_running:
            if auto_resume:
                await self.resume_session(session_id, cwd=cwd, load_history=False)
                live = self._sessions.get(session_id)
            if live is None:
                raise GatewayError(404, "not_found", f"Live session not found: {session_id}")

        blocks, display_text = self._build_prompt_blocks(
            session_id,
            text=text,
            parts=parts,
            attachment_ids=attachment_ids,
        )

        is_busy = live.active_turn_id is not None or live.prompt_lock.locked()
        if is_busy:
            if not enqueue_if_busy:
                raise GatewayError(
                    409,
                    "session_busy",
                    "Session already has an active turn in progress.",
                    details={"session_id": session_id},
                )
            item = QueueItem(
                item_id=f"q_{secrets.token_hex(6)}",
                position=len(live.queue),
                text=display_text,
                attachment_ids=list(attachment_ids),
            )
            live.queue.append(item)
            self._emit_session_event(
                live,
                "queue_updated",
                {"items": [i.to_dict() for i in live.queue], "paused": live.queue_paused},
            )
            return {
                "status": "queued",
                "session_id": session_id,
                "queue_item": item.to_dict(),
            }

        live.queue_paused = False
        turn_id = f"turn_{secrets.token_hex(8)}"
        self._start_turn_background(live, turn_id, blocks, display_text, parts, attachment_ids)
        return {
            "status": "started",
            "session_id": session_id,
            "turn_id": turn_id,
        }

    async def cancel_turn(self, session_id: str) -> dict[str, Any]:
        """Send ACP `session/cancel` notification and pause follow-up auto-drain."""
        validate_session_id(session_id)
        live = self._sessions.get(session_id)
        if live is None or not live.acp.is_running:
            raise GatewayError(404, "not_found", f"Live session not found: {session_id}")

        live.queue_paused = True
        for item in live.queue:
            item.dispatch_on_settle = False
        self._emit_session_event(
            live,
            "queue_updated",
            {"items": [i.to_dict() for i in live.queue], "paused": True},
        )
        self._cancel_pending_gates(live, reason="cancelled")
        if live.active_turn_id is not None:
            try:
                await live.acp.notify("session/cancel", {"sessionId": session_id})
            except Exception as exc:
                raise self._map_acp_error(exc, "session/cancel") from exc
        return {"session_id": session_id, "status": "cancelling"}

    def get_queue(self, session_id: str) -> dict[str, Any]:
        validate_session_id(session_id)
        live = self._sessions.get(session_id)
        if live is None:
            raise GatewayError(404, "not_found", f"Live session not found: {session_id}")
        return {
            "items": [i.to_dict() for i in live.queue],
            "paused": live.queue_paused,
        }

    def enqueue_item(
        self,
        session_id: str,
        text: str,
        attachment_ids: Sequence[str] = (),
    ) -> dict[str, Any]:
        validate_session_id(session_id)
        live = self._sessions.get(session_id)
        if live is None:
            raise GatewayError(404, "not_found", f"Live session not found: {session_id}")
        if not text or not text.strip():
            raise GatewayError(400, "bad_request", "Queue item text must not be empty.")
        if attachment_ids:
            self._storage.resolve_attachments(session_id, attachment_ids)

        item = QueueItem(
            item_id=f"q_{secrets.token_hex(6)}",
            position=len(live.queue),
            text=text,
            attachment_ids=list(attachment_ids),
        )
        live.queue.append(item)
        self._emit_session_event(
            live,
            "queue_updated",
            {"items": [i.to_dict() for i in live.queue], "paused": live.queue_paused},
        )
        return item.to_dict()

    def edit_queue_item(self, session_id: str, item_id: str, text: str) -> dict[str, Any]:
        validate_session_id(session_id)
        live = self._sessions.get(session_id)
        if live is None:
            raise GatewayError(404, "not_found", f"Live session not found: {session_id}")
        if not text or not text.strip():
            raise GatewayError(400, "bad_request", "Queue item text must not be empty.")

        for item in live.queue:
            if item.item_id == item_id:
                item.text = text
                self._emit_session_event(
                    live,
                    "queue_updated",
                    {"items": [i.to_dict() for i in live.queue], "paused": live.queue_paused},
                )
                return item.to_dict()
        raise GatewayError(404, "not_found", f"Queue item not found: {item_id}")

    def delete_queue_item(self, session_id: str, item_id: str) -> dict[str, Any]:
        validate_session_id(session_id)
        live = self._sessions.get(session_id)
        if live is None:
            raise GatewayError(404, "not_found", f"Live session not found: {session_id}")

        for idx, item in enumerate(live.queue):
            if item.item_id == item_id:
                live.queue.pop(idx)
                live.renumber_queue()
                self._emit_session_event(
                    live,
                    "queue_updated",
                    {"items": [i.to_dict() for i in live.queue], "paused": live.queue_paused},
                )
                return {"deleted": True, "item_id": item_id}
        raise GatewayError(404, "not_found", f"Queue item not found: {item_id}")

    async def send_queue_item_now(
        self,
        session_id: str,
        item_id: str,
        interrupt: bool = True,
    ) -> dict[str, Any]:
        """Dispatch a queued follow-up immediately if idle, or interrupt + dispatch on settle."""
        validate_session_id(session_id)
        live = self._sessions.get(session_id)
        if live is None or not live.acp.is_running:
            raise GatewayError(404, "not_found", f"Live session not found: {session_id}")

        target_idx: int | None = None
        for idx, item in enumerate(live.queue):
            if item.item_id == item_id:
                target_idx = idx
                break
        if target_idx is None:
            raise GatewayError(404, "not_found", f"Queue item not found: {item_id}")

        is_busy = live.active_turn_id is not None or live.prompt_lock.locked()
        if not is_busy:
            target = live.queue.pop(target_idx)
            live.renumber_queue()
            live.queue_paused = False
            self._emit_session_event(
                live,
                "queue_updated",
                {"items": [i.to_dict() for i in live.queue], "paused": False},
            )
            blocks, display_text = self._build_prompt_blocks(
                session_id,
                target.text,
                None,
                target.attachment_ids,
            )
            turn_id = f"turn_{secrets.token_hex(8)}"
            self._start_turn_background(
                live,
                turn_id,
                blocks,
                display_text,
                None,
                target.attachment_ids,
            )
            return {
                "status": "started",
                "session_id": session_id,
                "item_id": item_id,
                "turn_id": turn_id,
            }

        target = live.queue.pop(target_idx)
        live.queue.insert(0, target)
        live.renumber_queue()

        if not interrupt:
            live.queue_paused = False
            self._emit_session_event(
                live,
                "queue_updated",
                {"items": [i.to_dict() for i in live.queue], "paused": False},
            )
            return {
                "status": "promoted",
                "session_id": session_id,
                "item_id": item_id,
                "queue_item": target.to_dict(),
            }

        for other in live.queue[1:]:
            other.dispatch_on_settle = False
        target.dispatch_on_settle = True
        live.queue_paused = False
        self._emit_session_event(
            live,
            "queue_updated",
            {"items": [i.to_dict() for i in live.queue], "paused": False},
        )
        self._cancel_pending_gates(live, reason="cancelled")
        try:
            await live.acp.notify("session/cancel", {"sessionId": session_id})
        except Exception as exc:
            raise self._map_acp_error(exc, "session/cancel") from exc
        return {
            "status": "interrupting",
            "session_id": session_id,
            "item_id": item_id,
        }

    def resolve_permission(
        self,
        session_id: str,
        request_id: str,
        decision: str,
        option_id: str | None = None,
    ) -> dict[str, Any]:
        """Resolve a pending ACP `session/request_permission` request (D7, R6)."""
        validate_session_id(session_id)
        if decision not in ("allow_once", "allow_rule", "deny"):
            raise GatewayError(422, "validation_error", f"Invalid permission decision: {decision}")

        live = self._sessions.get(session_id)
        if live is None:
            raise GatewayError(404, "not_found", f"Live session not found: {session_id}")
        pending = live.pending_permissions.get(request_id)
        if pending is None or pending.future.done():
            raise GatewayError(404, "not_found", f"Pending permission not found: {request_id}")

        valid_option_ids = {
            str(o.get("optionId"))
            for o in pending.options
            if isinstance(o, dict) and o.get("optionId")
        }
        if option_id is not None and valid_option_ids and option_id not in valid_option_ids:
            raise GatewayError(
                422,
                "validation_error",
                f"Option ID {option_id!r} is not in offered options.",
            )

        rule_id: str | None = None
        chosen_opt: str | None = option_id

        if decision == "allow_once":
            if chosen_opt is None:
                chosen_opt = _select_option_id(pending.options, ("allow_once",)) or "allow-once"
            acp_outcome = {"outcome": {"outcome": "selected", "optionId": chosen_opt}}

        elif decision == "allow_rule":
            if chosen_opt is None:
                chosen_opt = (
                    _select_option_id(pending.options, ("allow_always", "allow_once"))
                    or "allow-once"
                )
            tool_kind = str(pending.tool_call.get("kind") or "other")
            derived_prefix = _derive_command_prefix(pending.tool_call)
            if _is_command_tool(pending.tool_call, tool_kind) and not derived_prefix:
                if option_id is None:
                    chosen_opt = _select_option_id(pending.options, ("allow_once",)) or "allow-once"
            else:
                rule_id = f"rule_{secrets.token_hex(8)}"
                rules = self._load_permission_rules()
                rules.append(
                    {
                        "rule_id": rule_id,
                        "workspace_root": live.cwd,
                        "tool_kind": tool_kind,
                        "command_prefix": derived_prefix,
                        "created_at": utc_now_iso(),
                    }
                )
                self._save_permission_rules(rules)
            acp_outcome = {"outcome": {"outcome": "selected", "optionId": chosen_opt}}

        else:  # deny
            if chosen_opt is None:
                chosen_opt = _select_option_id(pending.options, ("reject_once", "reject_always"))
            if chosen_opt is not None:
                acp_outcome = {"outcome": {"outcome": "selected", "optionId": chosen_opt}}
            else:
                acp_outcome = {"outcome": {"outcome": "cancelled"}}

        pending.future.set_result(acp_outcome)
        live.pending_permissions.pop(request_id, None)
        self._emit_session_event(
            live,
            "permission_resolved",
            {
                "request_id": request_id,
                "decision": decision,
                "option_id": chosen_opt,
                "auto_approved": False,
                "rule_id": rule_id,
            },
        )
        return {
            "request_id": request_id,
            "resolved": True,
            "option_id": chosen_opt,
            "rule_id": rule_id,
        }

    def resolve_question(
        self,
        session_id: str,
        request_id: str,
        action: str,
        answers: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        """Resolve a pending ACP `elicitation/create` request, validating schema keys (D7, R6)."""
        validate_session_id(session_id)
        if action not in ("accept", "decline", "cancel"):
            raise GatewayError(422, "validation_error", f"Invalid elicitation action: {action}")

        live = self._sessions.get(session_id)
        if live is None:
            raise GatewayError(404, "not_found", f"Live session not found: {session_id}")
        pending = live.pending_questions.get(request_id)
        if pending is None or pending.future.done():
            raise GatewayError(404, "not_found", f"Pending question not found: {request_id}")

        clean_answers = dict(answers) if isinstance(answers, dict) else {}
        if action == "accept":
            schema = pending.requested_schema
            raw_props = schema.get("properties")
            props: dict[str, Any] = raw_props if isinstance(raw_props, dict) else {}
            raw_req = schema.get("required")
            required: list[Any] = raw_req if isinstance(raw_req, list) else []

            if props:
                unknown_keys = [k for k in clean_answers if k not in props]
                if unknown_keys:
                    raise GatewayError(
                        422,
                        "validation_error",
                        f"Unknown answer keys: {unknown_keys}",
                        details={"allowed_keys": list(props.keys())},
                    )
            missing_req = [
                str(rk)
                for rk in required
                if str(rk) not in clean_answers or clean_answers[str(rk)] in (None, "")
            ]
            if missing_req:
                raise GatewayError(
                    422,
                    "validation_error",
                    f"Missing required answer keys: {missing_req}",
                    details={"missing_keys": missing_req},
                )
            for k, val in clean_answers.items():
                prop_spec = props.get(k)
                if isinstance(prop_spec, dict) and isinstance(prop_spec.get("enum"), list):
                    if val not in prop_spec["enum"]:
                        raise GatewayError(
                            422,
                            "validation_error",
                            f"Invalid enum value for {k!r}.",
                            details={"allowed": prop_spec["enum"]},
                        )
            acp_reply: dict[str, Any] = {"action": "accept", "content": clean_answers}
        else:
            clean_answers = {}
            acp_reply = {"action": action, "content": {}}

        pending.future.set_result(acp_reply)
        live.pending_questions.pop(request_id, None)
        self._emit_session_event(
            live,
            "question_resolved",
            {
                "request_id": request_id,
                "action": action,
                "answers": clean_answers,
            },
        )
        return {
            "request_id": request_id,
            "resolved": True,
            "action": action,
        }

    async def shutdown(self) -> None:
        """Close all live ACP child processes cleanly on gateway shutdown."""
        for sid in list(self._sessions.keys()):
            try:
                await self.close_session(sid)
            except Exception:
                sess = self._sessions.pop(sid, None)
                if sess is not None:
                    await sess.acp.close(session_id=sid)


def _select_option_id(
    options: list[dict[str, Any]],
    preferred_kinds: Sequence[str],
) -> str | None:
    for kind in preferred_kinds:
        for opt in options:
            if isinstance(opt, dict) and opt.get("kind") == kind and opt.get("optionId"):
                return str(opt["optionId"])
    return None
