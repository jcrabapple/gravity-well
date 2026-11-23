"""Observatory telemetry: TTL-cached /usage parser, presence radar, host vitals, redacted logs, daemon & update."""

from __future__ import annotations

import asyncio
import errno
import fcntl
import json
import os
from pathlib import Path
import re
import shutil
import time
from typing import Any

from fastapi import APIRouter, Depends, Query, Request
from pydantic import BaseModel

from .auth import require_auth
from .config import (
    SESSION_ID_RE,
    GatewayConfig,
    GatewayError,
    build_child_env,
    is_subpath,
    read_json_file,
    utc_now_iso,
)
from .redaction import redact_text
from .storage import StorageManager
from .supervisor import SessionSupervisor


class RemoteControlStartRequest(BaseModel):
    name: str | None = None
    session_scoped: bool = False


class UpdateRequest(BaseModel):
    confirm: bool = False


def _read_proc_locks() -> dict[int, int]:
    """Read active FLOCK entries from /proc/locks keyed by inode -> pid."""
    mapping: dict[int, int] = {}
    try:
        text = Path("/proc/locks").read_text(encoding="utf-8", errors="replace")
    except OSError:
        return mapping

    for line in text.splitlines():
        parts = line.split()
        if len(parts) < 6 or parts[1] != "FLOCK":
            continue
        try:
            pid = int(parts[4])
        except ValueError:
            continue
        dev_ino = parts[5].split(":")
        if len(dev_ino) != 3:
            continue
        try:
            ino = int(dev_ino[2])
        except ValueError:
            continue
        mapping[ino] = pid
    return mapping


def _read_proc_stat_cpu() -> tuple[int, int] | None:
    """Return (idle_jiffies, total_jiffies) from /proc/stat."""
    try:
        with open("/proc/stat", "r", encoding="utf-8") as fh:
            first = fh.readline()
    except OSError:
        return None
    parts = first.split()
    if len(parts) < 5 or parts[0] != "cpu":
        return None
    try:
        vals = [int(x) for x in parts[1:]]
    except ValueError:
        return None
    idle = vals[3] + (vals[4] if len(vals) > 4 else 0)
    total = sum(vals)
    return idle, total


def _read_meminfo_mb() -> tuple[int, int]:
    """Return (ram_used_mb, ram_total_mb) from /proc/meminfo."""
    info: dict[str, int] = {}
    try:
        text = Path("/proc/meminfo").read_text(encoding="utf-8", errors="replace")
    except OSError:
        return 0, 0
    for line in text.splitlines():
        if ":" not in line:
            continue
        key, rest = line.split(":", 1)
        tok = rest.strip().split()
        if not tok:
            continue
        try:
            info[key] = int(tok[0])
        except ValueError:
            continue
    total_kb = info.get("MemTotal", 0)
    if total_kb <= 0:
        return 0, 0
    if "MemAvailable" in info:
        avail_kb = info["MemAvailable"]
    else:
        avail_kb = info.get("MemFree", 0) + info.get("Buffers", 0) + info.get("Cached", 0)
    used_kb = max(0, total_kb - avail_kb)
    return used_kb // 1024, total_kb // 1024


def _parse_remote_control_output(text: str) -> dict[str, Any]:
    """Parse `agy remote-control status` output into structured status."""
    cleaned = redact_text(text.strip())
    active = False
    instance_name: str | None = None

    m_status = re.search(r"(?im)^Daemon status:\s*(.+)$", cleaned)
    if m_status:
        status_val = m_status.group(1).strip().lower()
        active = status_val in ("active", "running") or status_val.startswith("active ")

    m_inst = re.search(r"(?im)^Instance name:\s*(.+)$", cleaned)
    if m_inst:
        raw_name = m_inst.group(1).strip()
        if raw_name and "not assigned" not in raw_name.lower() and raw_name != "(not assigned)":
            instance_name = raw_name

    return {
        "active": active,
        "status_text": cleaned,
        "instance_name": instance_name,
    }


def _parse_usage_groups(raw_payload: Any) -> tuple[str, str, list[dict[str, Any]]]:
    """Parse `agy -p /usage --output-format json` payload via `command.data.groups` (R8)."""
    if not isinstance(raw_payload, dict):
        raise GatewayError(502, "acp_upstream_error", "Invalid usage JSON response from agy.")
    status = str(raw_payload.get("status") or "SUCCESS")
    command = raw_payload.get("command")
    if not isinstance(command, dict):
        raise GatewayError(502, "acp_upstream_error", "Missing command object in usage output.")
    cmd_data = command.get("data")
    if not isinstance(cmd_data, dict):
        raise GatewayError(502, "acp_upstream_error", "Missing command.data in usage output.")
    raw_groups = cmd_data.get("groups")
    if not isinstance(raw_groups, list):
        raise GatewayError(502, "acp_upstream_error", "Missing command.data.groups in usage output.")

    groups: list[dict[str, Any]] = []
    for g in raw_groups:
        if not isinstance(g, dict):
            continue
        buckets_out: list[dict[str, Any]] = []
        for b in g.get("buckets") or []:
            if not isinstance(b, dict):
                continue
            raw_frac = b.get("remaining_fraction")
            try:
                frac = float(raw_frac) if raw_frac is not None else 0.0
            except (TypeError, ValueError):
                frac = 0.0
            frac = max(0.0, min(1.0, frac))
            buckets_out.append(
                {
                    "id": str(b.get("id") or ""),
                    "name": str(b.get("name") or ""),
                    "description": str(b.get("description") or ""),
                    "window": str(b.get("window") or ""),
                    "remaining_fraction": frac,
                    "remaining_percent": round(frac * 100.0, 1),
                    "reset_time": b.get("reset_time"),
                }
            )
        groups.append(
            {
                "name": str(g.get("name") or ""),
                "description": str(g.get("description") or ""),
                "buckets": buckets_out,
            }
        )
    return status, str(cmd_data.get("description") or ""), groups


class ObservatoryManager:
    """Manages host vitals, presence radar, TTL-cached usage, redacted logs, daemon & update."""

    def __init__(
        self,
        config: GatewayConfig,
        storage: StorageManager,
        supervisor: SessionSupervisor,
        *,
        usage_ttl_seconds: float = 60.0,
    ) -> None:
        self._config = config
        self._storage = storage
        self._supervisor = supervisor
        self._usage_ttl = usage_ttl_seconds
        self._usage_lock = asyncio.Lock()
        self._usage_cache: dict[str, Any] | None = None
        self._usage_cached_mono: float | None = None
        self._prev_cpu_sample: tuple[int, int] | None = None

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

    async def get_usage(self, *, refresh: bool = False) -> dict[str, Any]:
        """Execute `agy -p /usage --output-format json` with 60s TTL cache."""
        async with self._usage_lock:
            now_mono = time.monotonic()
            if (
                not refresh
                and self._usage_cache is not None
                and self._usage_cached_mono is not None
                and (now_mono - self._usage_cached_mono) < self._usage_ttl
            ):
                return dict(self._usage_cache, stale=False)

            try:
                rc, stdout, stderr = await self._run_agy(["-p", "/usage", "--output-format", "json"])
                if rc != 0:
                    raise GatewayError(
                        502,
                        "acp_upstream_error",
                        redact_text((stderr or stdout or "agy /usage failed").strip()),
                    )
                try:
                    raw_payload = json.loads(stdout)
                except json.JSONDecodeError as exc:
                    raise GatewayError(
                        502,
                        "acp_upstream_error",
                        "Invalid JSON from `agy -p /usage --output-format json`.",
                    ) from exc

                status, description, groups = _parse_usage_groups(raw_payload)
                result = {
                    "status": status,
                    "cached_at": utc_now_iso(),
                    "stale": False,
                    "description": description,
                    "groups": groups,
                }
                self._usage_cache = result
                self._usage_cached_mono = time.monotonic()
                return dict(result)
            except GatewayError:
                if not refresh and self._usage_cache is not None:
                    return dict(self._usage_cache, stale=True)
                raise

    async def _sample_cpu_percent(self) -> float:
        first = self._prev_cpu_sample
        if first is None:
            first = _read_proc_stat_cpu()
            if first is None:
                return 0.0
            await asyncio.sleep(0.05)
        second = _read_proc_stat_cpu()
        if second is None:
            return 0.0
        self._prev_cpu_sample = second
        delta_total = second[1] - first[1]
        delta_idle = second[0] - first[0]
        if delta_total <= 0:
            return 0.0
        busy = max(0.0, min(100.0, (1.0 - (delta_idle / delta_total)) * 100.0))
        return round(busy, 1)

    async def get_host_vitals(self) -> dict[str, Any]:
        """Collect host CPU, load averages, RAM, and disk metrics."""
        cpu_pct = await self._sample_cpu_percent()
        try:
            l1, l5, _ = os.getloadavg()
        except OSError:
            l1, l5 = 0.0, 0.0
        ram_used_mb, ram_total_mb = _read_meminfo_mb()
        disk_target = self._config.home_dir if self._config.home_dir.exists() else Path("/")
        try:
            du = shutil.disk_usage(disk_target)
            disk_total_gb = round(du.total / (1024**3), 2)
            disk_used_gb = round(du.used / (1024**3), 2)
        except OSError:
            disk_total_gb = 0.0
            disk_used_gb = 0.0

        return {
            "cpu_percent": cpu_pct,
            "load_avg_1m": round(l1, 2),
            "load_avg_5m": round(l5, 2),
            "ram_used_mb": ram_used_mb,
            "ram_total_mb": ram_total_mb,
            "disk_used_gb": disk_used_gb,
            "disk_total_gb": disk_total_gb,
        }

    def scan_presence_locks(self) -> tuple[list[dict[str, Any]], int]:
        """Scan presence/*.lock and prove liveness via O_RDONLY flock probe + /proc/locks inode match (D10)."""
        presence_dir = self._config.agy_cli_dir / "presence"
        if not presence_dir.is_dir():
            return [], 0

        real_presence_dir = os.path.realpath(presence_dir)
        proc_locks_by_ino = _read_proc_locks()
        active_sessions: list[dict[str, Any]] = []
        stale_count = 0

        try:
            entries = sorted(os.listdir(real_presence_dir))
        except OSError:
            return [], 0

        for name in entries:
            if not name.endswith(".lock"):
                continue
            candidate = os.path.join(real_presence_dir, name)
            if os.path.islink(candidate):
                continue
            real_candidate = os.path.realpath(candidate)
            if not is_subpath(real_candidate, real_presence_dir) or real_candidate == real_presence_dir:
                continue

            fd = -1
            try:
                fd = os.open(real_candidate, os.O_RDONLY | os.O_NOFOLLOW)
                st = os.fstat(fd)
                is_live = False
                try:
                    fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    # Lock succeeded -> no external process holds flock; stale file
                    fcntl.flock(fd, fcntl.LOCK_UN)
                    is_live = False
                except BlockingIOError:
                    is_live = True
                except OSError as exc:
                    if exc.errno in (errno.EWOULDBLOCK, errno.EAGAIN):
                        is_live = True
                    else:
                        is_live = st.st_ino in proc_locks_by_ino

                if not is_live:
                    stale_count += 1
                    continue

                sid = name[:-5]
                live_sess = self._supervisor.get_live_session(sid)
                archived = None
                if live_sess is None and SESSION_ID_RE.match(sid):
                    archived = self._storage.get_archived_conversation(sid)

                title = (
                    live_sess.title
                    if live_sess is not None
                    else (archived["title"] if archived else "External Session")
                )
                cwd = (
                    live_sess.cwd
                    if live_sess is not None
                    else (archived["cwd"] if archived else None)
                )
                active_sessions.append(
                    {
                        "session_id": sid,
                        "title": title,
                        "cwd": cwd,
                        "supervised": bool(live_sess is not None and live_sess.acp.is_running),
                        "pid": proc_locks_by_ino.get(st.st_ino),
                        "lock_file": name,
                    }
                )
            except OSError:
                continue
            finally:
                if fd >= 0:
                    try:
                        os.close(fd)
                    except OSError:
                        pass

        return active_sessions, stale_count

    async def get_remote_control_status(self) -> dict[str, Any]:
        """Query `agy remote-control status` safely."""
        rc, stdout, stderr = await self._run_agy(["remote-control", "status"])
        combined = "\n".join(part for part in (stdout.strip(), stderr.strip()) if part)
        if rc != 0 and not combined:
            raise GatewayError(502, "acp_upstream_error", "agy remote-control status failed.")
        return _parse_remote_control_output(combined)

    async def start_remote_control(
        self,
        *,
        name: str | None = None,
        session_scoped: bool = False,
    ) -> dict[str, Any]:
        """Start remote-control daemon with flag-injection checks."""
        args = ["remote-control", "start"]
        if name is not None:
            cleaned = name.strip()
            if not cleaned or cleaned.startswith("-") or "\x00" in cleaned or "\n" in cleaned or "\r" in cleaned:
                raise GatewayError(400, "bad_request", "Invalid remote-control instance name.")
            args.extend(["--name", cleaned])
        if session_scoped:
            args.append("--session")

        rc, stdout, stderr = await self._run_agy(args)
        if rc != 0:
            raise GatewayError(
                502,
                "acp_upstream_error",
                redact_text((stderr or stdout or "agy remote-control start failed").strip()),
            )
        status = await self.get_remote_control_status()
        status["output"] = redact_text((stdout or stderr).strip())
        return status

    async def stop_remote_control(self) -> dict[str, Any]:
        """Stop remote-control daemon."""
        rc, stdout, stderr = await self._run_agy(["remote-control", "stop"])
        if rc != 0:
            raise GatewayError(
                502,
                "acp_upstream_error",
                redact_text((stderr or stdout or "agy remote-control stop failed").strip()),
            )
        status = await self.get_remote_control_status()
        status["output"] = redact_text((stdout or stderr).strip())
        return status

    def _probe_agy_version_fresh(self) -> str:
        self._supervisor._agy_version_cache = None  # noqa: SLF001
        return self._supervisor.get_agy_version()

    async def get_status(self) -> dict[str, Any]:
        """Aggregate host vitals, live presence radar, remote-control status, and updater status."""
        host = await self.get_host_vitals()
        external_sessions, stale_lock_count = self.scan_presence_locks()
        remote_control = await self.get_remote_control_status()

        updater_path = self._config.agy_cli_dir / "updater" / "update_status.json"
        updater_raw = read_json_file(updater_path, {})
        if not isinstance(updater_raw, dict):
            updater_raw = {}
        updater = {
            "success": updater_raw.get("success"),
            "message": redact_text(str(updater_raw.get("message") or "unknown")),
            "agy_version": self._supervisor.get_agy_version(),
        }

        return {
            "host": host,
            "external_sessions": external_sessions,
            "active_external_count": len(external_sessions),
            "stale_lock_count": stale_lock_count,
            "remote_control": remote_control,
            "updater": updater,
            "checked_at": utc_now_iso(),
        }

    def get_logs(
        self,
        *,
        source: str = "cli",
        tail: int = 200,
        q: str = "",
        file: str | None = None,
    ) -> dict[str, Any]:
        """Tail `cli.log` (contained inside `log/`) or `crashes/` files with mandatory secret redaction."""
        if source not in ("cli", "crashes"):
            raise GatewayError(400, "bad_request", "source must be 'cli' or 'crashes'.")
        clamped_tail = max(1, min(tail, 2000))
        q_lower = q.strip().lower()

        target_real: str | None = None
        selected_name: str | None = None
        available_files: list[str] = []

        if source == "cli":
            log_dir = self._config.agy_cli_dir / "log"
            cli_log = self._config.agy_cli_dir / "cli.log"
            if not cli_log.exists() and not cli_log.is_symlink():
                return {
                    "source": "cli",
                    "file": None,
                    "files": [],
                    "lines": [],
                    "total_matched": 0,
                }
            real_log_dir = os.path.realpath(log_dir)
            real_cli_log = os.path.realpath(cli_log)
            if not is_subpath(real_cli_log, real_log_dir) or real_cli_log == real_log_dir:
                raise GatewayError(
                    403,
                    "forbidden",
                    "cli.log resolves outside log directory.",
                )
            if not os.path.isfile(real_cli_log):
                return {
                    "source": "cli",
                    "file": None,
                    "files": [],
                    "lines": [],
                    "total_matched": 0,
                }
            target_real = real_cli_log
            selected_name = os.path.basename(real_cli_log)
            available_files = [selected_name]
        else:
            crashes_dir = self._config.agy_cli_dir / "crashes"
            real_crashes_dir = os.path.realpath(crashes_dir)
            crash_entries: list[tuple[float, str, str]] = []
            if os.path.isdir(real_crashes_dir):
                for entry in os.listdir(real_crashes_dir):
                    full = os.path.join(real_crashes_dir, entry)
                    if os.path.islink(full):
                        continue
                    real_entry = os.path.realpath(full)
                    if not is_subpath(real_entry, real_crashes_dir) or real_entry == real_crashes_dir:
                        continue
                    if not os.path.isfile(real_entry):
                        continue
                    try:
                        mtime = os.stat(real_entry).st_mtime
                    except OSError:
                        mtime = 0.0
                    crash_entries.append((mtime, entry, real_entry))
            crash_entries.sort(key=lambda item: (item[0], item[1]), reverse=True)
            available_files = [e[1] for e in crash_entries]

            if file is not None and file != "":
                if "\x00" in file or "/" in file or "\\" in file or ".." in file or file.startswith("."):
                    raise GatewayError(403, "forbidden", "Invalid crash log filename.")
                candidate = os.path.join(real_crashes_dir, file)
                real_candidate = os.path.realpath(candidate)
                if not is_subpath(real_candidate, real_crashes_dir) or real_candidate == real_crashes_dir:
                    raise GatewayError(403, "forbidden", "Crash file escapes crashes directory.")
                if not os.path.isfile(real_candidate):
                    raise GatewayError(404, "not_found", f"Crash log not found: {file}")
                target_real = real_candidate
                selected_name = file
            elif crash_entries:
                selected_name = crash_entries[0][1]
                target_real = crash_entries[0][2]

        if target_real is None:
            return {
                "source": source,
                "file": None,
                "files": available_files,
                "lines": [],
                "total_matched": 0,
            }

        raw_text = Path(target_real).read_text(encoding="utf-8", errors="replace")
        # Redact BEFORE filtering so query parameter cannot oracle-probe raw secret values
        redacted_lines = [redact_text(line) for line in raw_text.splitlines()]
        if q_lower:
            matched = [line for line in redacted_lines if q_lower in line.lower()]
        else:
            matched = redacted_lines

        return {
            "source": source,
            "file": selected_name,
            "files": available_files,
            "lines": matched[-clamped_tail:],
            "total_matched": len(matched),
        }

    async def run_update(self, *, confirm: bool) -> dict[str, Any]:
        """Run confirm-gated `agy update` with scrubbed environment."""
        if confirm is not True:
            raise GatewayError(
                400,
                "bad_request",
                'Update requires explicit confirmation body {"confirm": true}.',
            )
        version_before = self._probe_agy_version_fresh()
        rc, stdout, stderr = await self._run_agy(["update"], timeout=60.0)
        version_after = self._probe_agy_version_fresh()
        if rc != 0:
            raise GatewayError(
                502,
                "acp_upstream_error",
                redact_text((stderr or stdout or "agy update failed").strip()),
            )
        combined = redact_text("\n".join(p for p in (stdout.strip(), stderr.strip()) if p))
        updated = (version_before != version_after) or (
            "updated" in combined.lower() and "already" not in combined.lower()
        )
        return {
            "updated": updated,
            "version_before": version_before,
            "version_after": version_after,
            "output": combined,
        }


def create_observatory_router() -> APIRouter:
    """Create the authenticated `/api/observatory` FastAPI router."""
    router = APIRouter(
        prefix="/api/observatory",
        dependencies=[Depends(require_auth)],
        tags=["observatory"],
    )

    @router.get("/usage")
    async def get_usage(
        request: Request,
        refresh: bool = Query(default=False),
    ) -> dict[str, Any]:
        obs: ObservatoryManager = request.app.state.observatory
        return await obs.get_usage(refresh=refresh)

    @router.get("/status")
    async def get_status(request: Request) -> dict[str, Any]:
        obs: ObservatoryManager = request.app.state.observatory
        return await obs.get_status()

    @router.get("/logs")
    async def get_logs(
        request: Request,
        source: str = Query(default="cli"),
        tail: int = Query(default=200, ge=1, le=2000),
        q: str = Query(default=""),
        file: str | None = Query(default=None),
    ) -> dict[str, Any]:
        obs: ObservatoryManager = request.app.state.observatory
        return obs.get_logs(source=source, tail=tail, q=q, file=file)

    @router.get("/remote-control")
    async def get_remote_control(request: Request) -> dict[str, Any]:
        obs: ObservatoryManager = request.app.state.observatory
        return await obs.get_remote_control_status()

    @router.post("/remote-control/start")
    async def start_remote_control(
        request: Request,
        body: RemoteControlStartRequest | None = None,
    ) -> dict[str, Any]:
        obs: ObservatoryManager = request.app.state.observatory
        req = body or RemoteControlStartRequest()
        return await obs.start_remote_control(
            name=req.name,
            session_scoped=req.session_scoped,
        )

    @router.post("/remote-control/stop")
    async def stop_remote_control(request: Request) -> dict[str, Any]:
        obs: ObservatoryManager = request.app.state.observatory
        return await obs.stop_remote_control()

    @router.post("/update")
    async def run_update(
        request: Request,
        body: UpdateRequest | None = None,
    ) -> dict[str, Any]:
        obs: ObservatoryManager = request.app.state.observatory
        confirm = body.confirm if body is not None else False
        return await obs.run_update(confirm=confirm)

    return router
