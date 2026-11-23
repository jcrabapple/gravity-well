#!/usr/bin/python3
"""Deterministic hermetic FakeAgy CLI & ACP stdio server for gateway tests."""

from __future__ import annotations

import asyncio
import json
import os
from pathlib import Path
import re
import sys
import uuid
from typing import Any

VALID_MODELS = {
    "gemini-4-neon",
    "gemini-3.8-flash",
    "claude-sonnet-5-5",
}
VALID_THOUGHT_LEVELS = {"low", "medium", "high", "xhigh"}
VALID_MODES = {"default", "accept-edits", "auto", "plan"}


def _default_config_options(
    model: str = "gemini-4-neon",
    thought_level: str = "high",
    mode: str = "default",
) -> list[dict[str, Any]]:
    return [
        {
            "id": "model",
            "name": "Model",
            "category": "model",
            "currentValue": model,
            "options": [{"value": m, "name": m} for m in sorted(VALID_MODELS)],
        },
        {
            "id": "thought_level",
            "name": "Thought Level",
            "category": "thought_level",
            "currentValue": thought_level,
            "options": [{"value": t, "name": t} for t in ("low", "medium", "high", "xhigh")],
        },
        {
            "id": "mode",
            "name": "Mode",
            "category": "mode",
            "currentValue": mode,
            "options": [{"value": md, "name": md} for md in ("default", "accept-edits", "auto", "plan")],
        },
    ]


def _sessions_store_path() -> Path:
    return Path(os.environ["HOME"]) / ".fake_agy_sessions.json"


def _load_sessions_store() -> dict[str, Any]:
    p = _sessions_store_path()
    if not p.is_file():
        return {}
    try:
        return json.loads(p.read_text(encoding="utf-8"))
    except Exception:
        return {}


def _save_sessions_store(data: dict[str, Any]) -> None:
    p = _sessions_store_path()
    p.write_text(json.dumps(data, indent=2), encoding="utf-8")


def _record_spawn() -> None:
    log_path = Path(os.environ["HOME"]) / ".fake_agy_spawns.jsonl"
    record = {
        "pid": os.getpid(),
        "argv": sys.argv[1:],
        "cwd": os.getcwd(),
        "env": dict(os.environ),
    }
    with log_path.open("a", encoding="utf-8") as fh:
        fh.write(json.dumps(record) + "\n")


class FakeAcpServer:
    def __init__(self) -> None:
        self._write_lock = asyncio.Lock()
        self._server_req_seq = 0
        self._pending_server_reqs: dict[int, asyncio.Future[Any]] = {}
        self._cancel_events: dict[str, asyncio.Event] = {}
        self._prompt_tasks: set[asyncio.Task[None]] = set()
        self._session_configs: dict[str, dict[str, str]] = {}

    async def send(self, obj: dict[str, Any]) -> None:
        line = json.dumps(obj, separators=(",", ":")) + "\n"
        async with self._write_lock:
            sys.stdout.write(line)
            sys.stdout.flush()

    async def send_update(self, session_id: str, update: dict[str, Any]) -> None:
        await self.send(
            {
                "jsonrpc": "2.0",
                "method": "session/update",
                "params": {"sessionId": session_id, "update": update},
            }
        )

    async def request_client(self, method: str, params: dict[str, Any]) -> Any:
        self._server_req_seq += 1
        rid = self._server_req_seq
        fut: asyncio.Future[Any] = asyncio.get_running_loop().create_future()
        self._pending_server_reqs[rid] = fut
        await self.send({"jsonrpc": "2.0", "id": rid, "method": method, "params": params})
        return await fut

    async def handle_prompt(self, rpc_id: int | str, params: dict[str, Any]) -> None:
        sid = str(params.get("sessionId") or "")
        prompt_blocks = params.get("prompt") or []
        texts = [str(b.get("text") or "") for b in prompt_blocks if isinstance(b, dict)]
        full_text = "\n".join(t for t in texts if t)

        cancel_ev = asyncio.Event()
        self._cancel_events[sid] = cancel_ev

        try:
            if "[CRASH]" in full_text:
                await self.send_update(
                    sid,
                    {
                        "sessionUpdate": "agent_thought_chunk",
                        "content": {"type": "text", "text": "About to crash..."},
                    },
                )
                os._exit(1)

            await self.send_update(
                sid,
                {
                    "sessionUpdate": "agent_thought_chunk",
                    "content": {"type": "text", "text": f"Thinking about: {full_text[:40]}"},
                },
            )

            if "[HOLD:" in full_text:
                m = re.search(r"\[HOLD:([^\]]+)\]", full_text)
                gate_name = m.group(1) if m else "default"
                gate_file = Path(os.environ["HOME"]) / f".release_{gate_name}"
                while not gate_file.exists():
                    if cancel_ev.is_set():
                        await self.send(
                            {
                                "jsonrpc": "2.0",
                                "id": rpc_id,
                                "result": {
                                    "stopReason": "cancelled",
                                    "usage": {"inputTokens": 10, "outputTokens": 2, "totalTokens": 12},
                                },
                            }
                        )
                        return
                    await asyncio.sleep(0.02)

            if "[SLOW]" in full_text:
                for _ in range(100):
                    if cancel_ev.is_set():
                        await self.send(
                            {
                                "jsonrpc": "2.0",
                                "id": rpc_id,
                                "result": {
                                    "stopReason": "cancelled",
                                    "usage": {"inputTokens": 10, "outputTokens": 2, "totalTokens": 12},
                                },
                            }
                        )
                        return
                    await asyncio.sleep(0.02)

            perm_match = re.search(r"\[ASK_PERMISSION:([^\]]+)\]", full_text)
            if perm_match:
                cmd_str = perm_match.group(1)
                perm_res = await self.request_client(
                    "session/request_permission",
                    {
                        "sessionId": sid,
                        "toolCall": {
                            "toolCallId": "call_perm_01",
                            "title": f"Running command: `{cmd_str}`",
                            "kind": "execute",
                            "status": "pending",
                            "rawInput": {"CommandLine": cmd_str, "Cwd": os.getcwd()},
                        },
                        "options": [
                            {"optionId": "allow-once", "name": "Allow once", "kind": "allow_once"},
                            {"optionId": "allow-always", "name": "Allow for this session", "kind": "allow_always"},
                            {"optionId": "reject-once", "name": "Reject", "kind": "reject_once"},
                        ],
                    },
                )
                await asyncio.sleep(0.01)
                if cancel_ev.is_set():
                    await self.send_update(
                        sid,
                        {
                            "sessionUpdate": "agent_thought_chunk",
                            "content": {
                                "type": "text",
                                "text": f"PERM_CANCEL_ACP:{json.dumps(perm_res, sort_keys=True)}",
                            },
                        },
                    )
                    await self.send(
                        {
                            "jsonrpc": "2.0",
                            "id": rpc_id,
                            "result": {
                                "stopReason": "cancelled",
                                "usage": {"inputTokens": 10, "outputTokens": 2, "totalTokens": 12},
                            },
                        }
                    )
                    return
                reply_text = f"PERMISSION_OUTCOME:{json.dumps(perm_res, sort_keys=True)}"
            elif "[ASK_QUESTION]" in full_text:
                q_res = await self.request_client(
                    "elicitation/create",
                    {
                        "sessionId": sid,
                        "mode": "form",
                        "message": "Which color do you prefer?",
                        "requestedSchema": {
                            "type": "object",
                            "properties": {
                                "q_0": {
                                    "type": "string",
                                    "title": "Which color do you prefer?",
                                    "enum": ["Red", "Blue"],
                                    "enumNames": ["Red", "Blue"],
                                }
                            },
                            "required": ["q_0"],
                        },
                    },
                )
                await asyncio.sleep(0.01)
                if cancel_ev.is_set():
                    await self.send_update(
                        sid,
                        {
                            "sessionUpdate": "agent_thought_chunk",
                            "content": {
                                "type": "text",
                                "text": f"QUESTION_CANCEL_ACP:{json.dumps(q_res, sort_keys=True)}",
                            },
                        },
                    )
                    await self.send(
                        {
                            "jsonrpc": "2.0",
                            "id": rpc_id,
                            "result": {
                                "stopReason": "cancelled",
                                "usage": {"inputTokens": 10, "outputTokens": 2, "totalTokens": 12},
                            },
                        }
                    )
                    return
                reply_text = f"QUESTION_OUTCOME:{json.dumps(q_res, sort_keys=True)}"
            elif "[LARGE_FRAME]" in full_text:
                big_diff = "+" + ("x" * (140 * 1024))
                await self.send_update(
                    sid,
                    {
                        "sessionUpdate": "tool_call",
                        "toolCallId": "call_big_01",
                        "title": "Editing file",
                        "kind": "edit",
                        "status": "pending",
                        "rawInput": {"path": "large.txt"},
                    },
                )
                await self.send_update(
                    sid,
                    {
                        "sessionUpdate": "tool_call_update",
                        "toolCallId": "call_big_01",
                        "status": "completed",
                        "title": "Editing file",
                        "kind": "edit",
                        "rawOutput": {"ok": True},
                        "diff": big_diff,
                    },
                )
                reply_text = "LARGE_FRAME_OK"
            else:
                has_img = any(
                    isinstance(b, dict) and b.get("type") == "image" for b in prompt_blocks
                )
                suffix = " [WITH_IMAGE]" if has_img else ""
                reply_text = f"Echo: {full_text}{suffix}"

            await self.send_update(
                sid,
                {
                    "sessionUpdate": "agent_message_chunk",
                    "content": {"type": "text", "text": reply_text},
                },
            )
            await self.send_update(
                sid,
                {"sessionUpdate": "usage_update", "used": 512, "size": 1048576},
            )
            await self.send_update(
                sid,
                {"sessionUpdate": "session_info_update", "title": f"Auto: {full_text[:24]}"},
            )

            store = _load_sessions_store()
            entry = store.setdefault(sid, {"cwd": os.getcwd(), "turns": []})
            entry["turns"].append({"user": full_text, "assistant": reply_text})
            _save_sessions_store(store)

            await self.send(
                {
                    "jsonrpc": "2.0",
                    "id": rpc_id,
                    "result": {
                        "stopReason": "end_turn",
                        "usage": {"inputTokens": 100, "outputTokens": 20, "totalTokens": 120},
                    },
                }
            )
        finally:
            self._cancel_events.pop(sid, None)

    async def handle_message(self, msg: dict[str, Any]) -> None:
        raw_id = msg.get("id")
        has_id = isinstance(raw_id, (int, str)) and not isinstance(raw_id, bool)
        has_method = "method" in msg

        if has_id and not has_method:
            assert isinstance(raw_id, (int, str))
            fut = self._pending_server_reqs.pop(int(raw_id), None)
            if fut is not None and not fut.done():
                fut.set_result(msg.get("result"))
            return

        method = str(msg.get("method") or "")
        raw_params = msg.get("params")
        params: dict[str, Any] = raw_params if isinstance(raw_params, dict) else {}
        rpc_id = raw_id

        if method == "session/cancel":
            sid = str(params.get("sessionId") or "")
            ev = self._cancel_events.get(sid)
            if ev is not None:
                ev.set()
            return

        if method == "initialize":
            await self.send(
                {
                    "jsonrpc": "2.0",
                    "id": rpc_id,
                    "result": {
                        "protocolVersion": 1,
                        "agentCapabilities": {
                            "loadSession": True,
                            "promptCapabilities": {"image": True, "audio": False, "embeddedContext": True},
                            "sessionCapabilities": {"resume": {}, "close": {}, "load": {}, "list": {}},
                        },
                        "agentInfo": {"name": "Antigravity ACP Server", "version": "1.5.15"},
                    },
                }
            )
        elif method == "session/new":
            sid = str(uuid.uuid4())
            cfg = {"model": "gemini-4-neon", "thought_level": "high", "mode": "default"}
            self._session_configs[sid] = cfg
            store = _load_sessions_store()
            store[sid] = {"cwd": params.get("cwd") or os.getcwd(), "turns": []}
            _save_sessions_store(store)
            await self.send(
                {
                    "jsonrpc": "2.0",
                    "id": rpc_id,
                    "result": {
                        "sessionId": sid,
                        "models": {
                            "availableModels": [
                                {"modelId": "gemini-4-neon-high", "name": "Gemini 4 Neon (High)"},
                                {"modelId": "gemini-3.8-flash-medium", "name": "Gemini 3.8 Flash (Medium)"},
                            ],
                            "currentModelId": "gemini-4-neon-high",
                        },
                        "modes": {"currentModeId": "default"},
                        "configOptions": _default_config_options(**cfg),
                    },
                }
            )
            await self.send_update(
                sid,
                {
                    "sessionUpdate": "available_commands_update",
                    "availableCommands": [{"name": "plan", "description": "Enter plan mode"}],
                },
            )
            await self.send_update(
                sid,
                {"sessionUpdate": "usage_update", "used": 0, "size": 1048576},
            )
        elif method in ("session/resume", "session/load"):
            sid = str(params.get("sessionId") or "")
            store = _load_sessions_store()
            if sid not in store:
                await self.send(
                    {
                        "jsonrpc": "2.0",
                        "id": rpc_id,
                        "error": {
                            "code": -32002,
                            "message": "Resource not found",
                            "data": {"details": "conversation not found"},
                        },
                    }
                )
                return
            cfg = self._session_configs.setdefault(
                sid,
                {"model": "gemini-4-neon", "thought_level": "high", "mode": "default"},
            )
            if method == "session/load":
                for turn in store[sid].get("turns", []):
                    await self.send_update(
                        sid,
                        {
                            "sessionUpdate": "user_message_chunk",
                            "content": {"type": "text", "text": turn["user"]},
                        },
                    )
                    await self.send_update(
                        sid,
                        {
                            "sessionUpdate": "agent_message_chunk",
                            "content": {"type": "text", "text": turn["assistant"]},
                        },
                    )
                await self.send(
                    {
                        "jsonrpc": "2.0",
                        "id": rpc_id,
                        "result": {
                            "models": {"availableModels": []},
                            "modes": {"currentModeId": cfg["mode"]},
                            "configOptions": _default_config_options(
                                cfg["model"], cfg["thought_level"], cfg["mode"]
                            ),
                        },
                    }
                )
            else:
                await self.send(
                    {
                        "jsonrpc": "2.0",
                        "id": rpc_id,
                        "result": {
                            "sessionId": sid,
                            "models": {"availableModels": []},
                            "modes": {"currentModeId": cfg["mode"]},
                            "configOptions": _default_config_options(
                                cfg["model"], cfg["thought_level"], cfg["mode"]
                            ),
                        },
                    }
                )
        elif method == "session/set_config_option":
            sid = str(params.get("sessionId") or "")
            cid = str(params.get("configId") or "")
            val = str(params.get("value") or "")
            valid_map = {
                "model": VALID_MODELS,
                "thought_level": VALID_THOUGHT_LEVELS,
                "mode": VALID_MODES,
            }
            if cid not in valid_map or val not in valid_map[cid]:
                await self.send(
                    {
                        "jsonrpc": "2.0",
                        "id": rpc_id,
                        "error": {
                            "code": -32602,
                            "message": "Invalid params",
                            "data": {"details": f"invalid config {cid}={val}"},
                        },
                    }
                )
                return
            cfg = self._session_configs.setdefault(
                sid,
                {"model": "gemini-4-neon", "thought_level": "high", "mode": "default"},
            )
            cfg[cid] = val
            opts = _default_config_options(cfg["model"], cfg["thought_level"], cfg["mode"])
            await self.send_update(
                sid,
                {"sessionUpdate": "config_option_update", "configOptions": opts},
            )
            await self.send({"jsonrpc": "2.0", "id": rpc_id, "result": {"configOptions": opts}})
        elif method == "session/close":
            await self.send({"jsonrpc": "2.0", "id": rpc_id, "result": None})
        elif method == "session/prompt":
            assert rpc_id is not None
            t = asyncio.create_task(self.handle_prompt(rpc_id, params))
            self._prompt_tasks.add(t)
            t.add_done_callback(self._prompt_tasks.discard)
        elif method in (
            "session/fork",
            "session/rename",
            "session/set_title",
            "session/delete",
            "session/set_additional_directories",
        ):
            await self.send(
                {
                    "jsonrpc": "2.0",
                    "id": rpc_id,
                    "error": {"code": -32601, "message": "Method not found"},
                }
            )
        else:
            await self.send(
                {
                    "jsonrpc": "2.0",
                    "id": rpc_id,
                    "error": {"code": -32601, "message": f"Method not found: {method}"},
                }
            )


async def _run_acp_stdio() -> int:
    _record_spawn()
    server = FakeAcpServer()
    while True:
        line = await asyncio.to_thread(sys.stdin.buffer.readline)
        if not line:
            break
        raw = line.decode("utf-8", errors="replace").strip()
        if not raw:
            continue
        msg = json.loads(raw)
        await server.handle_message(msg)
    return 0


def main() -> int:
    args = sys.argv[1:]
    if not args or args[0] == "--version":
        print("1.5.15")
        return 0
    if args[0] == "acp":
        return asyncio.run(_run_acp_stdio())
    print(f"Unsupported fake_agy subcommand: {args}", file=sys.stderr)
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
