"""Per-session JSON-RPC 2.0 stdio client for `agy acp --stdio` (D1, D3)."""

from __future__ import annotations

import asyncio
from collections import deque
from dataclasses import dataclass
import json
from typing import Any, Awaitable, Callable, Sequence

from .config import GatewayConfig, build_child_env, redact_secrets

STDIO_STREAM_LIMIT = 16 * 1024 * 1024  # 16 MiB per line (D3)


@dataclass
class AcpRpcError(Exception):
    """JSON-RPC error returned by `agy acp --stdio`."""

    code: int
    message: str
    data: Any = None

    def __post_init__(self) -> None:
        super().__init__(f"ACP RPC error {self.code}: {self.message}")


class AcpUpstreamError(Exception):
    """Raised when `agy acp --stdio` exits unexpectedly or emits malformed frames."""

    def __init__(self, message: str, returncode: int | None = None) -> None:
        super().__init__(message)
        self.returncode = returncode


NotificationHandler = Callable[[str, dict[str, Any]], Awaitable[None]]
ServerRequestHandler = Callable[[str, dict[str, Any]], Awaitable[dict[str, Any]]]
ExitHandler = Callable[[int | None, str], Awaitable[None]]


class AcpClient:
    """Supervised `agy acp --stdio` child process speaking newline-delimited JSON-RPC 2.0."""

    def __init__(
        self,
        config: GatewayConfig,
        cwd: str,
        add_dirs: Sequence[str] = (),
        agent: str | None = None,
        sandbox: bool = False,
        on_notification: NotificationHandler | None = None,
        on_server_request: ServerRequestHandler | None = None,
        on_exit: ExitHandler | None = None,
    ) -> None:
        self._config = config
        self.cwd = cwd
        self.add_dirs = list(add_dirs)
        self.agent = agent
        self.sandbox = sandbox
        self._on_notification = on_notification
        self._on_server_request = on_server_request
        self._on_exit = on_exit

        self._proc: asyncio.subprocess.Process | None = None
        self._next_id: int = 0
        self._pending: dict[int | str, asyncio.Future[Any]] = {}
        self._write_lock = asyncio.Lock()
        self._stdout_task: asyncio.Task[None] | None = None
        self._stderr_task: asyncio.Task[None] | None = None
        self._server_req_tasks: set[asyncio.Task[None]] = set()
        self._stderr_tail: deque[str] = deque(maxlen=50)
        self._closing: bool = False
        self._closed: bool = False

    @property
    def pid(self) -> int | None:
        return self._proc.pid if self._proc is not None else None

    @property
    def returncode(self) -> int | None:
        return self._proc.returncode if self._proc is not None else None

    @property
    def is_running(self) -> bool:
        return self._proc is not None and self._proc.returncode is None and not self._closing

    async def start(self) -> None:
        """Spawn `agy acp --stdio` with scrubbed allowlist env and shell=False."""
        argv: list[str] = [self._config.agy_bin, "acp", "--stdio"]
        for extra_dir in self.add_dirs:
            argv.extend(["--add-dir", extra_dir])
        if self.agent:
            argv.extend(["--agent", self.agent])
        if self.sandbox:
            argv.append("--sandbox")

        child_env = build_child_env(home_override=self._config.home_dir)
        try:
            self._proc = await asyncio.create_subprocess_exec(
                *argv,
                stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
                cwd=self.cwd,
                env=child_env,
                limit=STDIO_STREAM_LIMIT,
            )
        except FileNotFoundError as exc:
            raise AcpUpstreamError(f"Antigravity binary not found: {self._config.agy_bin}") from exc
        except OSError as exc:
            raise AcpUpstreamError(f"Failed to start Antigravity ACP process: {exc}") from exc

        self._stdout_task = asyncio.create_task(self._read_stdout_loop())
        self._stderr_task = asyncio.create_task(self._read_stderr_loop())

    async def _send_json(self, frame: dict[str, Any]) -> None:
        if self._proc is None or self._proc.stdin is None or self._proc.returncode is not None:
            raise AcpUpstreamError(
                "ACP child process is not running.",
                returncode=self.returncode,
            )
        raw = (json.dumps(frame, separators=(",", ":")) + "\n").encode("utf-8")
        async with self._write_lock:
            if self._proc.stdin.is_closing():
                raise AcpUpstreamError("ACP stdin is closing.", returncode=self.returncode)
            self._proc.stdin.write(raw)
            await self._proc.stdin.drain()

    async def request(
        self,
        method: str,
        params: dict[str, Any] | None = None,
        timeout: float | None = 60.0,
    ) -> Any:
        """Send a JSON-RPC 2.0 request and await the matching response."""
        self._next_id += 1
        req_id = self._next_id
        loop = asyncio.get_running_loop()
        fut: asyncio.Future[Any] = loop.create_future()
        self._pending[req_id] = fut
        frame = {
            "jsonrpc": "2.0",
            "id": req_id,
            "method": method,
            "params": {} if params is None else params,
        }
        try:
            await self._send_json(frame)
            if timeout is not None:
                return await asyncio.wait_for(fut, timeout=timeout)
            return await fut
        except asyncio.TimeoutError as exc:
            self._pending.pop(req_id, None)
            raise AcpUpstreamError(f"ACP request {method} timed out after {timeout}s") from exc
        except Exception:
            self._pending.pop(req_id, None)
            raise

    async def notify(self, method: str, params: dict[str, Any] | None = None) -> None:
        """Send a JSON-RPC 2.0 notification (no id)."""
        frame = {
            "jsonrpc": "2.0",
            "method": method,
            "params": {} if params is None else params,
        }
        await self._send_json(frame)

    async def _dispatch_server_request(
        self,
        rpc_id: int | str,
        method: str,
        params: dict[str, Any],
    ) -> None:
        if self._on_server_request is None:
            resp = {
                "jsonrpc": "2.0",
                "id": rpc_id,
                "error": {"code": -32601, "message": f"Method not handled: {method}"},
            }
            try:
                await self._send_json(resp)
            except Exception:
                pass
            return

        try:
            result = await self._on_server_request(method, params)
            resp = {"jsonrpc": "2.0", "id": rpc_id, "result": result}
        except AcpRpcError as exc:
            resp = {
                "jsonrpc": "2.0",
                "id": rpc_id,
                "error": {"code": exc.code, "message": exc.message, "data": exc.data},
            }
        except asyncio.CancelledError:
            return
        except Exception as exc:
            resp = {
                "jsonrpc": "2.0",
                "id": rpc_id,
                "error": {"code": -32603, "message": redact_secrets(str(exc))},
            }
        try:
            if not self._closing:
                await self._send_json(resp)
        except Exception:
            pass

    async def _read_stdout_loop(self) -> None:
        assert self._proc is not None and self._proc.stdout is not None
        protocol_error: str | None = None
        try:
            while True:
                line = await self._proc.stdout.readline()
                if not line:
                    break
                text = line.decode("utf-8", errors="replace").strip()
                if not text:
                    continue
                try:
                    msg = json.loads(text)
                except json.JSONDecodeError:
                    protocol_error = "Invalid JSON-RPC frame emitted by agy acp."
                    break
                if not isinstance(msg, dict):
                    protocol_error = "Non-object JSON-RPC frame emitted by agy acp."
                    break

                raw_id = msg.get("id")
                has_id = isinstance(raw_id, (int, str)) and not isinstance(raw_id, bool)
                has_method = "method" in msg

                if has_id and not has_method:
                    assert isinstance(raw_id, (int, str))
                    fut = self._pending.pop(raw_id, None)
                    if fut is not None and not fut.done():
                        if "error" in msg and isinstance(msg["error"], dict):
                            err = msg["error"]
                            fut.set_exception(
                                AcpRpcError(
                                    code=int(err.get("code", -32603)),
                                    message=str(err.get("message", "ACP error")),
                                    data=err.get("data"),
                                )
                            )
                        else:
                            fut.set_result(msg.get("result"))
                elif has_method and not has_id:
                    if self._on_notification is not None:
                        params = msg.get("params")
                        await self._on_notification(
                            str(msg["method"]),
                            params if isinstance(params, dict) else {},
                        )
                elif has_method and has_id:
                    assert isinstance(raw_id, (int, str))
                    params = msg.get("params")
                    task = asyncio.create_task(
                        self._dispatch_server_request(
                            raw_id,
                            str(msg["method"]),
                            params if isinstance(params, dict) else {},
                        )
                    )
                    self._server_req_tasks.add(task)
                    task.add_done_callback(self._server_req_tasks.discard)
        except asyncio.CancelledError:
            return
        except Exception as exc:
            protocol_error = redact_secrets(str(exc))

        rc: int | None = None
        if self._proc is not None:
            try:
                rc = await asyncio.wait_for(self._proc.wait(), timeout=0.5)
            except asyncio.TimeoutError:
                rc = self._proc.returncode

        reason = protocol_error or f"ACP process exited with returncode={rc}"
        err = AcpUpstreamError(reason, returncode=rc)
        for fut in list(self._pending.values()):
            if not fut.done():
                fut.set_exception(err)
        self._pending.clear()

        if not self._closing and self._on_exit is not None:
            await self._on_exit(rc, reason)

    async def _read_stderr_loop(self) -> None:
        assert self._proc is not None and self._proc.stderr is not None
        try:
            while True:
                line = await self._proc.stderr.readline()
                if not line:
                    break
                decoded = redact_secrets(line.decode("utf-8", errors="replace").rstrip())
                if decoded:
                    self._stderr_tail.append(decoded)
        except asyncio.CancelledError:
            return
        except Exception:
            return

    async def close(self, session_id: str | None = None) -> int | None:
        """Graceful teardown per D3: session/close (1s) -> stdin EOF (2s) -> SIGTERM (2s) -> SIGKILL."""
        if self._closed:
            return self.returncode
        self._closing = True

        if session_id and self._proc is not None and self._proc.returncode is None:
            try:
                await self.request("session/close", {"sessionId": session_id}, timeout=1.0)
            except Exception:
                pass

        for task in list(self._server_req_tasks):
            task.cancel()
        if self._server_req_tasks:
            await asyncio.gather(*self._server_req_tasks, return_exceptions=True)
            self._server_req_tasks.clear()

        rc: int | None = None
        if self._proc is not None:
            if self._proc.stdin is not None and not self._proc.stdin.is_closing():
                try:
                    self._proc.stdin.close()
                except Exception:
                    pass

            if self._proc.returncode is None:
                try:
                    rc = await asyncio.wait_for(self._proc.wait(), timeout=2.0)
                except asyncio.TimeoutError:
                    try:
                        self._proc.terminate()
                    except ProcessLookupError:
                        pass
                    try:
                        rc = await asyncio.wait_for(self._proc.wait(), timeout=2.0)
                    except asyncio.TimeoutError:
                        try:
                            self._proc.kill()
                        except ProcessLookupError:
                            pass
                        rc = await self._proc.wait()
            else:
                rc = self._proc.returncode

        for bg_task in (self._stdout_task, self._stderr_task):
            if bg_task is not None and not bg_task.done():
                bg_task.cancel()
                await asyncio.gather(bg_task, return_exceptions=True)

        for fut in list(self._pending.values()):
            if not fut.done():
                fut.set_exception(AcpUpstreamError("Session closed.", returncode=rc))
        self._pending.clear()
        self._closed = True
        return rc
