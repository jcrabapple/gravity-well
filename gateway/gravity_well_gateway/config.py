"""Gateway configuration, path containment policy, child environment scrubbing, and error types."""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shutil
import tempfile
from typing import Any, Mapping

GATEWAY_VERSION = "1.2.0"
SERVICE_NAME = "gravity-well-gateway"
ACP_PROTOCOL_VERSION = 1

TOKEN_VAR_NAME = "GRAVITY_WELL_" + "TOKEN"

ALLOWED_CHILD_ENV_KEYS: tuple[str, ...] = (
    "PATH",
    "HOME",
    "USER",
    "LANG",
    "LC_ALL",
    "TERM",
    "DBUS_SESSION_BUS_ADDRESS",
    "XDG_RUNTIME_DIR",
)

SENSITIVE_DOTDIR_RELPATHS: tuple[str, ...] = (
    ".hermes",
    ".gemini",
    ".ssh",
    ".gnupg",
    ".config/gravity-well",
    ".local/share/keyrings",
)

SESSION_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")


def utc_now_iso() -> str:
    """Return current UTC timestamp in ISO-8601 Z format."""
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


@dataclass
class GatewayError(Exception):
    """Structured error mapped directly to the canonical JSON error envelope."""

    status: int
    code: str
    message: str
    details: dict[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        super().__init__(self.message)

    def to_dict(self) -> dict[str, Any]:
        return {
            "error": {
                "code": self.code,
                "message": self.message,
                "status": self.status,
                "details": self.details,
            }
        }


STATUS_TO_ERROR_CODE: dict[int, str] = {
    400: "bad_request",
    401: "unauthorized",
    403: "forbidden",
    404: "not_found",
    409: "conflict",
    413: "payload_too_large",
    415: "unsupported_media_type",
    422: "validation_error",
    501: "acp_method_unsupported",
    502: "acp_upstream_error",
    503: "service_unavailable",
}


def is_subpath(candidate_real: str, parent_real: str) -> bool:
    """Return True iff candidate_real equals or is inside parent_real."""
    try:
        return os.path.commonpath([candidate_real, parent_real]) == parent_real
    except ValueError:
        return False


def _parse_allowed_roots(raw: str | None, default_home_real: str) -> tuple[str, ...]:
    if not raw or not raw.strip():
        return (default_home_real,)
    stripped = raw.strip()
    items: list[str]
    if stripped.startswith("["):
        parsed = json.loads(stripped)
        if not isinstance(parsed, list) or not all(isinstance(x, str) for x in parsed):
            raise ValueError("GRAVITY_WELL_ALLOWED_ROOTS JSON must be a list of strings")
        items = [x for x in parsed if x.strip()]
    else:
        items = [p.strip() for p in stripped.split(os.pathsep) if p.strip()]
    if not items:
        return (default_home_real,)
    return tuple(os.path.realpath(os.path.expanduser(p)) for p in items)


@dataclass(frozen=True)
class GatewayConfig:
    """Immutable runtime configuration for a gateway instance."""

    home_dir: Path
    config_dir: Path
    env_file: Path
    gemini_dir: Path
    agy_cli_dir: Path
    agy_bin: str
    default_cwd: Path
    allowed_roots: tuple[str, ...]
    denied_dirs: tuple[str, ...]
    session_ring_maxlen: int = 2000
    global_ring_maxlen: int = 4000

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None) -> GatewayConfig:
        src = os.environ if env is None else env
        home_raw = src.get("HOME") or str(Path.home())
        home_real = os.path.realpath(home_raw)
        home_dir = Path(home_real)

        config_dir = Path(
            os.path.realpath(
                os.path.expanduser(
                    src.get("GRAVITY_WELL_CONFIG_DIR") or str(home_dir / ".config" / "gravity-well")
                )
            )
        )
        env_file = Path(
            os.path.realpath(
                os.path.expanduser(src.get("GRAVITY_WELL_ENV_FILE") or str(config_dir / "gateway.env"))
            )
        )
        gemini_dir = Path(
            os.path.realpath(
                os.path.expanduser(src.get("GRAVITY_WELL_GEMINI_DIR") or str(home_dir / ".gemini"))
            )
        )
        agy_cli_dir = Path(
            os.path.realpath(
                os.path.expanduser(
                    src.get("GRAVITY_WELL_AGY_CLI_DIR") or str(gemini_dir / "antigravity-cli")
                )
            )
        )
        agy_bin = src.get("GRAVITY_WELL_AGY_BIN") or shutil.which("agy") or str(home_dir / ".local" / "bin" / "agy")
        default_cwd = Path(
            os.path.realpath(
                os.path.expanduser(src.get("GRAVITY_WELL_DEFAULT_CWD") or str(home_dir))
            )
        )
        allowed_roots = _parse_allowed_roots(src.get("GRAVITY_WELL_ALLOWED_ROOTS"), home_real)

        denied_set: list[str] = []
        for rel in SENSITIVE_DOTDIR_RELPATHS:
            denied_set.append(os.path.realpath(os.path.join(home_real, rel)))
        for extra in (str(config_dir), str(gemini_dir), str(agy_cli_dir)):
            real_extra = os.path.realpath(extra)
            if real_extra not in denied_set:
                denied_set.append(real_extra)

        session_maxlen = int(src.get("GRAVITY_WELL_SESSION_RING_MAXLEN") or "2000")
        global_maxlen = int(src.get("GRAVITY_WELL_GLOBAL_RING_MAXLEN") or "4000")

        return cls(
            home_dir=home_dir,
            config_dir=config_dir,
            env_file=env_file,
            gemini_dir=gemini_dir,
            agy_cli_dir=agy_cli_dir,
            agy_bin=agy_bin,
            default_cwd=default_cwd,
            allowed_roots=allowed_roots,
            denied_dirs=tuple(denied_set),
            session_ring_maxlen=session_maxlen,
            global_ring_maxlen=global_maxlen,
        )


def build_child_env(
    base_env: Mapping[str, str] | None = None,
    home_override: str | Path | None = None,
) -> dict[str, str]:
    """Build strict minimal allowlisted child environment per D1."""
    src = os.environ if base_env is None else base_env
    clean: dict[str, str] = {}
    for key in ALLOWED_CHILD_ENV_KEYS:
        val = src.get(key)
        if val is not None and val != "":
            clean[key] = val
    if home_override is not None:
        clean["HOME"] = str(home_override)
    clean["ANTIGRAVITY_ENV_STRICT"] = "1"
    return clean


def validate_session_id(session_id: str) -> str:
    """Validate session_id against path traversal and control characters."""
    if not session_id or "\x00" in session_id or ".." in session_id or "/" in session_id or "\\" in session_id:
        raise GatewayError(403, "forbidden", "Invalid session identifier.")
    if not SESSION_ID_RE.match(session_id):
        raise GatewayError(403, "forbidden", "Malformed session identifier.")
    return session_id


def validate_workspace_dir(path_str: str, config: GatewayConfig) -> str:
    """Validate a workspace cwd or add_dirs entry per path containment & sensitive-dotdir policy."""
    if not isinstance(path_str, str) or not path_str.strip() or "\x00" in path_str:
        raise GatewayError(403, "forbidden", "Invalid workspace path.")
    expanded = os.path.expanduser(path_str.strip())
    if not os.path.isabs(expanded):
        raise GatewayError(403, "forbidden", "Workspace path must be absolute.")
    real = os.path.realpath(expanded)

    for denied in config.denied_dirs:
        if is_subpath(real, denied):
            raise GatewayError(
                403,
                "forbidden",
                "Workspace path targets a protected directory.",
                details={"path": path_str},
            )

    if not any(is_subpath(real, root) for root in config.allowed_roots):
        raise GatewayError(
            403,
            "forbidden",
            "Workspace path is outside allowed roots.",
            details={"path": path_str},
        )

    if not os.path.isdir(real):
        raise GatewayError(
            400,
            "bad_request",
            "Workspace directory does not exist.",
            details={"path": path_str},
        )
    return real


def atomic_write_json(path: Path, data: Any, mode: int = 0o600) -> None:
    """Atomically write JSON with fsync and explicit permissions."""
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp_path = tempfile.mkstemp(prefix=f".{path.name}.", dir=str(path.parent))
    try:
        os.chmod(fd, mode)
        encoded = (json.dumps(data, indent=2, sort_keys=True) + "\n").encode("utf-8")
        os.write(fd, encoded)
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


def read_json_file(path: Path, default: Any) -> Any:
    """Read JSON from disk or return default if missing."""
    if not path.is_file():
        return default
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return default


_REDACT_PATTERNS: tuple[tuple[re.Pattern[str], str], ...] = (
    (re.compile(r"(?i)(authorization\s*:\s*bearer\s+)\S+"), r"\1[REDACTED]"),
    (re.compile(r"(?i)\bbearer\s+[A-Za-z0-9._~+/=-]{8,}"), "Bearer [REDACTED]"),
    (re.compile(r"\bAIza[0-9A-Za-z_-]{20,}\b"), "[REDACTED]"),
    (re.compile(r"\bya29\.[0-9A-Za-z._-]+\b"), "[REDACTED]"),
    (re.compile(r"\bsk-[0-9A-Za-z_-]{16,}\b"), "[REDACTED]"),
    (re.compile(r"\bgwt_[0-9A-Za-z_-]{16,}\b"), "[REDACTED]"),
    (
        re.compile(
            r'(?i)("(?:client_secret|refresh_token|access_token|id_token|api_key|bearer_token)"\s*:\s*")[^"]*(")'
        ),
        r"\1[REDACTED]\2",
    ),
    (
        re.compile(
            r"\b([A-Za-z0-9_]*(?:KEY|TOKEN|SECRET|PASSWORD)[A-Za-z0-9_]*)\s*=\s*([^\s\"',;]+)"
        ),
        r"\1=[REDACTED]",
    ),
)


def redact_secrets(text: str) -> str:
    """Redact credentials/tokens from log or text artifacts (delegates to redaction.py when available)."""
    try:
        from .redaction import redact_text  # type: ignore[import-not-found]

        return str(redact_text(text))
    except ImportError:
        out = text
        for pattern, repl in _REDACT_PATTERNS:
            out = pattern.sub(repl, out)
        return out
