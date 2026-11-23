"""Bearer authentication, 30s single-use WebSocket ticket store, and ASGI pre-upgrade auth gate."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
import hmac
import json
import os
from pathlib import Path
import secrets
import tempfile
import time
from typing import Any, Iterable, Mapping, MutableMapping
from urllib.parse import parse_qsl

from fastapi import Request

from .config import GatewayConfig, GatewayError, TOKEN_VAR_NAME

FORBIDDEN_QUERY_KEYS: frozenset[str] = frozenset(
    {"token", "bearer", "auth", "access_token", "api_key"}
)

WS_TICKET_TTL_SECONDS = 30
WS_TICKET_MAX_ACTIVE = 1024


@dataclass(frozen=True)
class _TicketEntry:
    ticket: str
    expires_mono: float
    expires_at: str


def _parse_env_token(env_path: Path) -> str | None:
    if not env_path.is_file():
        return None
    for raw_line in env_path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[len("export ") :].strip()
        key, sep, val = line.partition("=")
        if not sep or key.strip() != TOKEN_VAR_NAME:
            continue
        cleaned = val.strip()
        if len(cleaned) >= 2 and cleaned[0] == cleaned[-1] and cleaned[0] in ("'", '"'):
            cleaned = cleaned[1:-1]
        if cleaned:
            return cleaned
    return None


def _atomic_mint_env_file(env_path: Path, minted_token: str) -> None:
    env_path.parent.mkdir(parents=True, exist_ok=True)
    try:
        os.chmod(env_path.parent, 0o700)
    except OSError:
        pass
    fd, tmp_path = tempfile.mkstemp(prefix=".gateway.env.", dir=str(env_path.parent))
    try:
        os.chmod(fd, 0o600)
        payload = f"{TOKEN_VAR_NAME}={minted_token}\n".encode("utf-8")
        os.write(fd, payload)
        os.fsync(fd)
        os.close(fd)
        fd = -1
        os.replace(tmp_path, env_path)
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


class AuthManager:
    """Manages gateway bearer token verification and bounded single-use WS tickets."""

    def __init__(
        self,
        config: GatewayConfig,
        env: Mapping[str, str] | None = None,
        ticket_ttl_seconds: int = WS_TICKET_TTL_SECONDS,
        max_tickets: int = WS_TICKET_MAX_ACTIVE,
    ) -> None:
        self._config = config
        self._ticket_ttl_seconds = ticket_ttl_seconds
        self._max_tickets = max_tickets
        self._tickets: dict[str, _TicketEntry] = {}
        self._token = self._load_or_mint_token(os.environ if env is None else env)

    def _load_or_mint_token(self, env: Mapping[str, str]) -> str:
        override = (env.get(TOKEN_VAR_NAME) or "").strip()
        if override:
            return override

        existing = _parse_env_token(self._config.env_file)
        if existing:
            try:
                os.chmod(self._config.env_file, 0o600)
            except OSError:
                pass
            return existing

        minted = secrets.token_urlsafe(32)
        _atomic_mint_env_file(self._config.env_file, minted)
        return minted

    @property
    def token_bytes(self) -> bytes:
        return self._token.encode("utf-8")

    def verify_bearer(self, candidate: str | None) -> bool:
        """Constant-time verification of the gateway bearer token."""
        if not candidate or not isinstance(candidate, str):
            return False
        return hmac.compare_digest(candidate.encode("utf-8"), self.token_bytes)

    def _prune_expired_tickets(self, now_mono: float | None = None) -> None:
        now = time.monotonic() if now_mono is None else now_mono
        expired = [k for k, v in self._tickets.items() if now >= v.expires_mono]
        for k in expired:
            self._tickets.pop(k, None)

    def mint_ws_ticket(self) -> dict[str, Any]:
        """Mint a 30s single-use WS upgrade ticket, pruning expired and capping store at 1024."""
        now_mono = time.monotonic()
        self._prune_expired_tickets(now_mono)
        while len(self._tickets) >= self._max_tickets:
            oldest_key = next(iter(self._tickets))
            self._tickets.pop(oldest_key, None)

        ticket = f"gwt_{secrets.token_urlsafe(24)}"
        expires_dt = datetime.now(timezone.utc) + timedelta(seconds=self._ticket_ttl_seconds)
        expires_at = expires_dt.strftime("%Y-%m-%dT%H:%M:%SZ")
        self._tickets[ticket] = _TicketEntry(
            ticket=ticket,
            expires_mono=now_mono + float(self._ticket_ttl_seconds),
            expires_at=expires_at,
        )
        return {
            "ticket": ticket,
            "ttl_seconds": self._ticket_ttl_seconds,
            "expires_at": expires_at,
        }

    def consume_ws_ticket(self, candidate: str | None) -> bool:
        """Validate and immediately invalidate a single-use WS ticket in constant time."""
        if not candidate or not isinstance(candidate, str):
            return False
        if self.verify_bearer(candidate):
            return False

        now_mono = time.monotonic()
        self._prune_expired_tickets(now_mono)

        cand_bytes = candidate.encode("utf-8")
        matched_key: str | None = None
        for key in list(self._tickets.keys()):
            if hmac.compare_digest(cand_bytes, key.encode("utf-8")):
                matched_key = key

        if matched_key is None:
            return False
        entry = self._tickets.pop(matched_key)
        return now_mono < entry.expires_mono

    def check_query_pairs(
        self,
        pairs: Iterable[tuple[str, str]],
        *,
        allow_ticket: bool,
    ) -> list[str]:
        """Reject forbidden query-string token params and permanent tokens inside ?ticket=."""
        tickets: list[str] = []
        for raw_k, raw_v in pairs:
            k_lower = raw_k.lower()
            if k_lower in FORBIDDEN_QUERY_KEYS:
                raise GatewayError(
                    401,
                    "unauthorized",
                    "Query-string bearer tokens are forbidden.",
                )
            if k_lower == "ticket":
                if not allow_ticket:
                    raise GatewayError(
                        401,
                        "unauthorized",
                        "WebSocket tickets are not accepted on HTTP endpoints.",
                    )
                if self.verify_bearer(raw_v):
                    raise GatewayError(
                        401,
                        "unauthorized",
                        "Permanent bearer token is forbidden inside ?ticket=.",
                    )
                tickets.append(raw_v)
        return tickets

    def authenticate_authorization_value(self, auth_header: str | None) -> None:
        if not auth_header:
            raise GatewayError(401, "unauthorized", "Missing or invalid bearer token.")
        scheme, sep, credentials = auth_header.partition(" ")
        if not sep or scheme.lower() != "bearer" or not credentials.strip():
            raise GatewayError(401, "unauthorized", "Missing or invalid bearer token.")
        if not self.verify_bearer(credentials.strip()):
            raise GatewayError(401, "unauthorized", "Missing or invalid bearer token.")

    def authenticate_websocket_scope(self, scope: Mapping[str, Any]) -> None:
        """Authenticate an incoming ASGI WebSocket upgrade via Bearer header or single-use ticket."""
        raw_qs = (scope.get("query_string") or b"").decode("latin-1")
        pairs = parse_qsl(raw_qs, keep_blank_values=True)
        tickets = self.check_query_pairs(pairs, allow_ticket=True)

        auth_header: str | None = None
        for hk, hv in scope.get("headers") or ():
            if hk.lower() == b"authorization":
                auth_header = hv.decode("latin-1")
                break

        if auth_header is not None:
            self.authenticate_authorization_value(auth_header)
            return

        if len(tickets) != 1 or not self.consume_ws_ticket(tickets[0]):
            raise GatewayError(
                401,
                "unauthorized",
                "Missing, expired, or already-used WebSocket ticket.",
            )


def require_auth(request: Request) -> None:
    """FastAPI dependency enforcing Bearer token authentication on HTTP routes."""
    auth_mgr: AuthManager = request.app.state.auth
    raw_qs = (request.scope.get("query_string") or b"").decode("latin-1")
    auth_mgr.check_query_pairs(parse_qsl(raw_qs, keep_blank_values=True), allow_ticket=False)
    auth_mgr.authenticate_authorization_value(request.headers.get("authorization"))


class AuthGateASGIWrapper:
    """ASGI wrapper rejecting forbidden query tokens on all routes and invalid WS upgrades with HTTP 401."""

    def __init__(self, inner_app: Any, get_auth_manager: Any) -> None:
        self._inner = inner_app
        self._get_auth = get_auth_manager

    async def __call__(self, scope: MutableMapping[str, Any], receive: Any, send: Any) -> None:
        scope_type = scope.get("type")
        if scope_type == "http":
            auth_mgr: AuthManager = self._get_auth()
            raw_qs = (scope.get("query_string") or b"").decode("latin-1")
            try:
                auth_mgr.check_query_pairs(
                    parse_qsl(raw_qs, keep_blank_values=True),
                    allow_ticket=False,
                )
            except GatewayError as exc:
                body = json.dumps(exc.to_dict()).encode("utf-8")
                await send(
                    {
                        "type": "http.response.start",
                        "status": exc.status,
                        "headers": [
                            (b"content-type", b"application/json; charset=utf-8"),
                            (b"content-length", str(len(body)).encode("ascii")),
                        ],
                    }
                )
                await send({"type": "http.response.body", "body": body})
                return

        elif scope_type == "websocket":
            auth_mgr = self._get_auth()
            try:
                auth_mgr.authenticate_websocket_scope(scope)
                scope["gw_ws_authenticated"] = True
            except GatewayError as exc:
                msg = await receive()
                if msg.get("type") == "websocket.disconnect":
                    return
                body = json.dumps(exc.to_dict()).encode("utf-8")
                extensions = scope.get("extensions") or {}
                if "websocket.http.response" in extensions:
                    await send(
                        {
                            "type": "websocket.http.response.start",
                            "status": exc.status,
                            "headers": [
                                (b"content-type", b"application/json; charset=utf-8"),
                                (b"content-length", str(len(body)).encode("ascii")),
                            ],
                        }
                    )
                    await send({"type": "websocket.http.response.body", "body": body})
                else:
                    await send(
                        {
                            "type": "websocket.close",
                            "code": 4401,
                            "reason": exc.message[:120],
                        }
                    )
                return

        await self._inner(scope, receive, send)
