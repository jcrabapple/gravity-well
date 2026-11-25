"""OAuth 2.0 + PKCE manager for remote HTTP MCP servers (RFC 9728 / RFC 8414 / CIMD / RFC 7591)."""

from __future__ import annotations

import base64
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
import hashlib
import secrets
import time
from typing import Any
from urllib.parse import parse_qs, urlencode, urljoin, urlparse

import httpx

from .config import (
    GatewayConfig,
    GatewayError,
    atomic_write_json,
    read_json_file,
    utc_now_iso,
)
from .redaction import redact_text

ANTIGRAVITY_CIMD_CLIENT_ID = "https://antigravity.google/oauth/client-metadata.json"
ANTIGRAVITY_OAUTH_REDIRECT_URI = "https://antigravity.google/oauth-callback"
PENDING_FLOW_TTL_SECONDS = 900.0  # 15 minutes
OAUTH_HTTP_TIMEOUT_SECONDS = 15.0


@dataclass
class PendingOAuthFlow:
    flow_id: str
    server_name: str
    server_url: str
    token_endpoint: str
    client_id: str
    client_secret: str | None
    redirect_uri: str
    code_verifier: str
    state: str
    scope: str | None
    registration_method: str
    created_mono: float


def _b64url_no_pad(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def _generate_pkce_pair() -> tuple[str, str]:
    verifier = _b64url_no_pad(secrets.token_bytes(32))
    digest = hashlib.sha256(verifier.encode("ascii")).digest()
    challenge = _b64url_no_pad(digest)
    return verifier, challenge


def _validate_http_url(raw: str, label: str) -> str:
    cleaned = (raw or "").strip()
    parsed = urlparse(cleaned)
    if parsed.scheme not in ("https", "http") or not parsed.netloc:
        raise GatewayError(422, "validation_error", f"Invalid {label} URL.")
    return cleaned


def _extract_www_authenticate_resource_metadata(header_val: str) -> str | None:
    if not header_val:
        return None
    marker = "resource_metadata="
    low = header_val.lower()
    idx = low.find(marker)
    if idx < 0:
        return None
    rest = header_val[idx + len(marker) :].lstrip()
    if rest.startswith('"'):
        end = rest.find('"', 1)
        return rest[1:end] if end > 1 else None
    end = rest.find(",")
    return (rest[:end] if end >= 0 else rest).strip() or None


def _extract_code_and_state(code_or_url: str) -> tuple[str, str | None]:
    raw = (code_or_url or "").strip()
    if not raw:
        raise GatewayError(422, "validation_error", "Authorization code is required.")
    if raw.startswith(("http://", "https://")) or "?" in raw or "code=" in raw:
        parsed = urlparse(raw) if "://" in raw else urlparse(f"https://example.invalid/?{raw.lstrip('?')}")
        params = parse_qs(parsed.query)
        if not params and parsed.fragment:
            params = parse_qs(parsed.fragment)
        if "error" in params and params["error"]:
            desc = params.get("error_description", [""])[0]
            msg = f"OAuth provider returned error: {params['error'][0]}"
            if desc:
                msg = f"{msg} ({desc})"
            raise GatewayError(400, "bad_request", redact_text(msg))
        codes = params.get("code")
        if not codes or not codes[0].strip():
            raise GatewayError(422, "validation_error", "Callback URL does not contain an authorization code.")
        states = params.get("state")
        return codes[0].strip(), (states[0].strip() if states and states[0].strip() else None)
    return raw, None


def _format_expires_at(expires_in: Any) -> str | None:
    try:
        secs = int(expires_in)
    except (TypeError, ValueError):
        return None
    if secs <= 0:
        return None
    dt = datetime.now(timezone.utc) + timedelta(seconds=secs)
    return dt.strftime("%Y-%m-%dT%H:%M:%SZ")


class McpOAuthManager:
    """Handles OAuth 2.0 + PKCE discovery, token exchange, refresh, and sign-out for HTTP MCP servers."""

    def __init__(
        self,
        config: GatewayConfig,
        *,
        http_client_factory: Any | None = None,
    ) -> None:
        self._config = config
        self._pending: dict[str, PendingOAuthFlow] = {}
        self._http_client_factory = http_client_factory

    @property
    def mcp_config_path(self) -> Any:
        return self._config.gemini_dir / "config" / "mcp_config.json"

    @property
    def oauth_state_path(self) -> Any:
        return self._config.config_dir / "mcp_oauth_state.json"

    def _new_client(self) -> httpx.AsyncClient:
        if self._http_client_factory is not None:
            return self._http_client_factory()
        return httpx.AsyncClient(
            timeout=OAUTH_HTTP_TIMEOUT_SECONDS,
            follow_redirects=True,
            headers={"Accept": "application/json"},
        )

    def _prune_pending(self) -> None:
        now = time.monotonic()
        expired = [fid for fid, fl in self._pending.items() if now - fl.created_mono > PENDING_FLOW_TTL_SECONDS]
        for fid in expired:
            self._pending.pop(fid, None)

    def _read_oauth_store(self) -> dict[str, dict[str, Any]]:
        raw = read_json_file(self.oauth_state_path, {})
        if not isinstance(raw, dict):
            return {}
        return {k: v for k, v in raw.items() if isinstance(k, str) and isinstance(v, dict)}

    def _write_oauth_store(self, data: dict[str, dict[str, Any]]) -> None:
        atomic_write_json(self.oauth_state_path, data, mode=0o600)

    def _read_mcp_config_root(self) -> dict[str, Any]:
        raw = read_json_file(self.mcp_config_path, {})
        return dict(raw) if isinstance(raw, dict) else {}

    def _get_http_server_entry(self, server_name: str) -> tuple[dict[str, Any], dict[str, Any]]:
        root = self._read_mcp_config_root()
        servers = root.get("mcpServers")
        if not isinstance(servers, dict) or server_name not in servers or not isinstance(servers[server_name], dict):
            raise GatewayError(404, "not_found", f"MCP server not found: {server_name}")
        entry = servers[server_name]
        server_url = entry.get("serverUrl") or entry.get("url")
        if not isinstance(server_url, str) or not server_url.strip():
            raise GatewayError(
                422,
                "validation_error",
                f"MCP server '{server_name}' is not a remote HTTP server and does not support OAuth.",
            )
        return root, entry

    def get_server_oauth_status(self, server_name: str, entry: dict[str, Any] | None = None) -> dict[str, Any]:
        """Return strictly non-secret OAuth status metadata for an MCP server."""
        store = self._read_oauth_store()
        rec = store.get(server_name)
        manual_oauth = entry.get("oauth") if isinstance(entry, dict) and isinstance(entry.get("oauth"), dict) else None
        has_manual_client = bool(manual_oauth and manual_oauth.get("clientId"))

        if not isinstance(rec, dict):
            return {
                "authenticated": False,
                "expires_at": None,
                "has_refresh_token": False,
                "has_manual_client": has_manual_client,
                "scope": None,
                "updated_at": None,
            }

        return {
            "authenticated": bool(rec.get("authenticated", True)),
            "expires_at": rec.get("expires_at"),
            "has_refresh_token": bool(rec.get("refresh_token")),
            "has_manual_client": has_manual_client or bool(rec.get("manual_client")),
            "scope": rec.get("scope"),
            "updated_at": rec.get("updated_at"),
        }

    def save_manual_oauth_credentials(
        self,
        server_name: str,
        *,
        client_id: str | None,
        client_secret: str | None,
    ) -> None:
        clean_id = (client_id or "").strip()
        clean_secret = (client_secret or "").strip()
        if not clean_id:
            return
        root = self._read_mcp_config_root()
        servers = root.get("mcpServers")
        if not isinstance(servers, dict) or server_name not in servers or not isinstance(servers[server_name], dict):
            return
        entry = dict(servers[server_name])
        oauth_block: dict[str, str] = {"clientId": clean_id}
        if clean_secret:
            oauth_block["clientSecret"] = clean_secret
        entry["oauth"] = oauth_block
        servers = dict(servers)
        servers[server_name] = entry
        root["mcpServers"] = servers
        atomic_write_json(self.mcp_config_path, root, mode=0o600)

    async def _discover_oauth_metadata(
        self,
        client: httpx.AsyncClient,
        server_url: str,
    ) -> tuple[dict[str, Any], list[str], str]:
        """Discover Protected Resource Metadata (RFC 9728) and Authorization Server Metadata (RFC 8414 / OIDC)."""
        parsed = urlparse(server_url)
        origin = f"{parsed.scheme}://{parsed.netloc}"
        path_suffix = parsed.path.rstrip("/")

        prm_candidates: list[str] = []
        # Optional quick probe to catch WWW-Authenticate: Bearer resource_metadata="..."
        try:
            probe = await client.get(server_url, headers={"Accept": "application/json, text/event-stream"})
            www_auth = probe.headers.get("www-authenticate", "")
            hinted = _extract_www_authenticate_resource_metadata(www_auth)
            if hinted:
                prm_candidates.append(urljoin(server_url, hinted))
        except httpx.HTTPError:
            pass

        if path_suffix:
            prm_candidates.append(f"{origin}/.well-known/oauth-protected-resource{path_suffix}")
        prm_candidates.append(f"{origin}/.well-known/oauth-protected-resource")

        auth_server_urls: list[str] = []
        discovered_scopes: list[str] = []
        resource_url = server_url

        seen_prm: set[str] = set()
        for url in prm_candidates:
            if url in seen_prm:
                continue
            seen_prm.add(url)
            try:
                resp = await client.get(url)
            except httpx.HTTPError:
                continue
            if resp.status_code == 200:
                try:
                    data = resp.json()
                except ValueError:
                    continue
                if isinstance(data, dict):
                    if isinstance(data.get("resource"), str) and data["resource"].strip():
                        resource_url = data["resource"].strip()
                    raw_servers = data.get("authorization_servers")
                    if isinstance(raw_servers, list):
                        auth_server_urls.extend(str(s).strip() for s in raw_servers if isinstance(s, str) and s.strip())
                    raw_scopes = data.get("scopes_supported")
                    if isinstance(raw_scopes, list):
                        discovered_scopes = [str(s).strip() for s in raw_scopes if isinstance(s, str) and s.strip()]
                    if auth_server_urls:
                        break

        if not auth_server_urls:
            auth_server_urls = [origin]

        as_meta: dict[str, Any] | None = None
        for issuer in auth_server_urls:
            iss_parsed = urlparse(issuer)
            iss_origin = f"{iss_parsed.scheme}://{iss_parsed.netloc}"
            iss_path = iss_parsed.path.rstrip("/")
            candidates = []
            if iss_path:
                candidates.append(f"{iss_origin}/.well-known/oauth-authorization-server{iss_path}")
                candidates.append(f"{iss_origin}/.well-known/openid-configuration{iss_path}")
                candidates.append(f"{issuer.rstrip('/')}/.well-known/openid-configuration")
            candidates.append(f"{iss_origin}/.well-known/oauth-authorization-server")
            candidates.append(f"{iss_origin}/.well-known/openid-configuration")

            seen_as: set[str] = set()
            for cand in candidates:
                if cand in seen_as:
                    continue
                seen_as.add(cand)
                try:
                    resp = await client.get(cand)
                except httpx.HTTPError:
                    continue
                if resp.status_code == 200:
                    try:
                        parsed_meta = resp.json()
                    except ValueError:
                        continue
                    if (
                        isinstance(parsed_meta, dict)
                        and isinstance(parsed_meta.get("authorization_endpoint"), str)
                        and isinstance(parsed_meta.get("token_endpoint"), str)
                    ):
                        as_meta = parsed_meta
                        break
            if as_meta is not None:
                break

        if as_meta is None:
            raise GatewayError(
                502,
                "acp_upstream_error",
                "Could not discover OAuth 2.0 metadata (.well-known/oauth-authorization-server) for this MCP server.",
            )

        if not discovered_scopes and isinstance(as_meta.get("scopes_supported"), list):
            discovered_scopes = [str(s).strip() for s in as_meta["scopes_supported"] if isinstance(s, str) and s.strip()]

        return as_meta, discovered_scopes, resource_url

    async def start_oauth(
        self,
        server_name: str,
        *,
        client_id: str | None = None,
        client_secret: str | None = None,
        scopes: list[str] | None = None,
    ) -> dict[str, Any]:
        self._prune_pending()
        _root, entry = self._get_http_server_entry(server_name)
        server_url = _validate_http_url(str(entry.get("serverUrl") or entry.get("url")), "MCP server")

        manual_block = entry.get("oauth") if isinstance(entry.get("oauth"), dict) else {}
        eff_client_id = (client_id or "").strip() or str(manual_block.get("clientId") or "").strip() or None
        eff_client_secret = (client_secret or "").strip() or str(manual_block.get("clientSecret") or "").strip() or None

        if client_id and client_id.strip():
            self.save_manual_oauth_credentials(
                server_name,
                client_id=eff_client_id,
                client_secret=eff_client_secret,
            )

        async with self._new_client() as client:
            as_meta, discovered_scopes, resource_url = await self._discover_oauth_metadata(client, server_url)
            auth_endpoint = _validate_http_url(str(as_meta["authorization_endpoint"]), "authorization_endpoint")
            token_endpoint = _validate_http_url(str(as_meta["token_endpoint"]), "token_endpoint")

            registration_method = "manual"
            if not eff_client_id:
                if as_meta.get("client_id_metadata_document_supported") is True:
                    eff_client_id = ANTIGRAVITY_CIMD_CLIENT_ID
                    registration_method = "cimd"
                elif isinstance(as_meta.get("registration_endpoint"), str) and as_meta["registration_endpoint"].strip():
                    reg_url = _validate_http_url(str(as_meta["registration_endpoint"]), "registration_endpoint")
                    reg_payload = {
                        "client_name": "Gravity Well (Antigravity)",
                        "client_uri": "https://antigravity.google",
                        "redirect_uris": [ANTIGRAVITY_OAUTH_REDIRECT_URI],
                        "grant_types": ["authorization_code", "refresh_token"],
                        "response_types": ["code"],
                        "token_endpoint_auth_method": "none",
                    }
                    try:
                        reg_resp = await client.post(reg_url, json=reg_payload)
                    except httpx.HTTPError as exc:
                        raise GatewayError(
                            502,
                            "acp_upstream_error",
                            f"OAuth dynamic client registration failed: {redact_text(str(exc))}",
                        ) from exc
                    if reg_resp.status_code not in (200, 201):
                        raise GatewayError(
                            502,
                            "acp_upstream_error",
                            f"OAuth dynamic client registration rejected ({reg_resp.status_code}). Provide a manual Client ID if required.",
                        )
                    try:
                        reg_data = reg_resp.json()
                    except ValueError as exc:
                        raise GatewayError(502, "acp_upstream_error", "Invalid JSON from OAuth registration endpoint.") from exc
                    if not isinstance(reg_data, dict) or not reg_data.get("client_id"):
                        raise GatewayError(502, "acp_upstream_error", "OAuth registration endpoint did not return a client_id.")
                    eff_client_id = str(reg_data["client_id"])
                    if isinstance(reg_data.get("client_secret"), str) and reg_data["client_secret"].strip():
                        eff_client_secret = str(reg_data["client_secret"]).strip()
                    registration_method = "dcr"
                else:
                    raise GatewayError(
                        422,
                        "validation_error",
                        "This MCP server does not advertise CIMD or Dynamic Client Registration. Provide a manual OAuth Client ID.",
                    )

        verifier, challenge = _generate_pkce_pair()
        state = _b64url_no_pad(secrets.token_bytes(18))
        flow_id = f"oauth_{secrets.token_hex(8)}"

        requested_scopes = [s.strip() for s in (scopes or []) if isinstance(s, str) and s.strip()]
        scope_str = " ".join(requested_scopes) if requested_scopes else (" ".join(discovered_scopes) if discovered_scopes else None)

        query_params: dict[str, str] = {
            "response_type": "code",
            "client_id": eff_client_id,
            "redirect_uri": ANTIGRAVITY_OAUTH_REDIRECT_URI,
            "code_challenge": challenge,
            "code_challenge_method": "S256",
            "state": state,
            "resource": resource_url,
        }
        if scope_str:
            query_params["scope"] = scope_str

        sep = "&" if "?" in auth_endpoint else "?"
        authorization_url = f"{auth_endpoint}{sep}{urlencode(query_params)}"

        self._pending[flow_id] = PendingOAuthFlow(
            flow_id=flow_id,
            server_name=server_name,
            server_url=resource_url,
            token_endpoint=token_endpoint,
            client_id=eff_client_id,
            client_secret=eff_client_secret,
            redirect_uri=ANTIGRAVITY_OAUTH_REDIRECT_URI,
            code_verifier=verifier,
            state=state,
            scope=scope_str,
            registration_method=registration_method,
            created_mono=time.monotonic(),
        )

        return {
            "flow_id": flow_id,
            "server_name": server_name,
            "authorization_url": authorization_url,
            "redirect_uri": ANTIGRAVITY_OAUTH_REDIRECT_URI,
            "registration_method": registration_method,
            "scope": scope_str,
        }

    def _apply_bearer_token_to_mcp_config(self, server_name: str, access_token: str) -> None:
        root, entry = self._get_http_server_entry(server_name)
        entry_copy = dict(entry)
        raw_headers = entry_copy.get("headers")
        headers = dict(raw_headers) if isinstance(raw_headers, dict) else {}
        headers["Authorization"] = f"Bearer {access_token}"
        entry_copy["headers"] = headers

        servers = dict(root.get("mcpServers") or {})
        servers[server_name] = entry_copy
        root["mcpServers"] = servers
        atomic_write_json(self.mcp_config_path, root, mode=0o600)

    def _remove_bearer_token_from_mcp_config(self, server_name: str) -> None:
        root = self._read_mcp_config_root()
        servers = root.get("mcpServers")
        if not isinstance(servers, dict) or server_name not in servers or not isinstance(servers[server_name], dict):
            return
        entry_copy = dict(servers[server_name])
        raw_headers = entry_copy.get("headers")
        if isinstance(raw_headers, dict):
            headers = {k: v for k, v in raw_headers.items() if str(k).lower() != "authorization"}
            entry_copy["headers"] = headers
            servers = dict(servers)
            servers[server_name] = entry_copy
            root["mcpServers"] = servers
            atomic_write_json(self.mcp_config_path, root, mode=0o600)

    async def complete_oauth(
        self,
        server_name: str,
        *,
        flow_id: str,
        code: str,
    ) -> dict[str, Any]:
        self._prune_pending()
        flow = self._pending.get(flow_id)
        if flow is None or flow.server_name != server_name:
            raise GatewayError(
                404,
                "not_found",
                "OAuth flow expired or not found. Start authentication again.",
            )

        auth_code, returned_state = _extract_code_and_state(code)
        if returned_state is not None and not secrets.compare_digest(returned_state, flow.state):
            raise GatewayError(400, "bad_request", "OAuth state mismatch. Please restart authentication.")

        form_data: dict[str, str] = {
            "grant_type": "authorization_code",
            "code": auth_code,
            "redirect_uri": flow.redirect_uri,
            "client_id": flow.client_id,
            "code_verifier": flow.code_verifier,
            "resource": flow.server_url,
        }
        if flow.client_secret:
            form_data["client_secret"] = flow.client_secret

        async with self._new_client() as client:
            try:
                resp = await client.post(
                    flow.token_endpoint,
                    data=form_data,
                    headers={"Accept": "application/json"},
                )
            except httpx.HTTPError as exc:
                raise GatewayError(
                    502,
                    "acp_upstream_error",
                    f"OAuth token exchange failed: {redact_text(str(exc))}",
                ) from exc

        if resp.status_code not in (200, 201):
            err_detail = ""
            try:
                err_json = resp.json()
                if isinstance(err_json, dict):
                    err_detail = str(err_json.get("error_description") or err_json.get("error") or "")
            except ValueError:
                err_detail = resp.text[:200]
            msg = f"OAuth token exchange rejected ({resp.status_code})"
            if err_detail:
                msg = f"{msg}: {redact_text(err_detail)}"
            raise GatewayError(400 if 400 <= resp.status_code < 500 else 502, "bad_request", msg)

        try:
            token_data = resp.json()
        except ValueError as exc:
            raise GatewayError(502, "acp_upstream_error", "Invalid JSON returned by OAuth token endpoint.") from exc

        access_token = str(token_data.get("access_token") or "").strip() if isinstance(token_data, dict) else ""
        if not access_token:
            raise GatewayError(502, "acp_upstream_error", "OAuth token endpoint did not return an access_token.")

        refresh_token = (
            str(token_data.get("refresh_token")).strip()
            if isinstance(token_data.get("refresh_token"), str) and token_data.get("refresh_token").strip()
            else None
        )
        expires_at = _format_expires_at(token_data.get("expires_in"))
        scope_val = (
            str(token_data.get("scope")).strip()
            if isinstance(token_data.get("scope"), str) and token_data.get("scope").strip()
            else flow.scope
        )

        self._apply_bearer_token_to_mcp_config(server_name, access_token)

        store = self._read_oauth_store()
        store[server_name] = {
            "authenticated": True,
            "server_url": flow.server_url,
            "token_endpoint": flow.token_endpoint,
            "client_id": flow.client_id,
            "client_secret": flow.client_secret,
            "refresh_token": refresh_token,
            "expires_at": expires_at,
            "scope": scope_val,
            "manual_client": flow.registration_method == "manual",
            "registration_method": flow.registration_method,
            "updated_at": utc_now_iso(),
        }
        self._write_oauth_store(store)
        self._pending.pop(flow_id, None)

        return {
            "server_name": server_name,
            "authenticated": True,
            "expires_at": expires_at,
            "has_refresh_token": bool(refresh_token),
            "scope": scope_val,
        }

    async def refresh_oauth(self, server_name: str) -> dict[str, Any]:
        store = self._read_oauth_store()
        rec = store.get(server_name)
        if not isinstance(rec, dict) or not rec.get("refresh_token") or not rec.get("token_endpoint"):
            raise GatewayError(
                404,
                "not_found",
                f"No refreshable OAuth token stored for MCP server '{server_name}'. Please authenticate again.",
            )

        form_data: dict[str, str] = {
            "grant_type": "refresh_token",
            "refresh_token": str(rec["refresh_token"]),
            "client_id": str(rec["client_id"]),
        }
        if rec.get("client_secret"):
            form_data["client_secret"] = str(rec["client_secret"])
        if rec.get("server_url"):
            form_data["resource"] = str(rec["server_url"])

        async with self._new_client() as client:
            try:
                resp = await client.post(
                    str(rec["token_endpoint"]),
                    data=form_data,
                    headers={"Accept": "application/json"},
                )
            except httpx.HTTPError as exc:
                raise GatewayError(
                    502,
                    "acp_upstream_error",
                    f"OAuth token refresh failed: {redact_text(str(exc))}",
                ) from exc

        if resp.status_code not in (200, 201):
            raise GatewayError(
                400 if 400 <= resp.status_code < 500 else 502,
                "bad_request",
                f"OAuth token refresh rejected ({resp.status_code}). Please re-authenticate.",
            )

        try:
            token_data = resp.json()
        except ValueError as exc:
            raise GatewayError(502, "acp_upstream_error", "Invalid JSON from OAuth token refresh endpoint.") from exc

        access_token = str(token_data.get("access_token") or "").strip() if isinstance(token_data, dict) else ""
        if not access_token:
            raise GatewayError(502, "acp_upstream_error", "OAuth refresh response missing access_token.")

        new_refresh = (
            str(token_data.get("refresh_token")).strip()
            if isinstance(token_data.get("refresh_token"), str) and token_data.get("refresh_token").strip()
            else rec.get("refresh_token")
        )
        expires_at = _format_expires_at(token_data.get("expires_in"))
        scope_val = (
            str(token_data.get("scope")).strip()
            if isinstance(token_data.get("scope"), str) and token_data.get("scope").strip()
            else rec.get("scope")
        )

        self._apply_bearer_token_to_mcp_config(server_name, access_token)
        rec_updated = dict(rec)
        rec_updated["authenticated"] = True
        rec_updated["refresh_token"] = new_refresh
        rec_updated["expires_at"] = expires_at
        rec_updated["scope"] = scope_val
        rec_updated["updated_at"] = utc_now_iso()
        store[server_name] = rec_updated
        self._write_oauth_store(store)

        return {
            "server_name": server_name,
            "authenticated": True,
            "expires_at": expires_at,
            "has_refresh_token": bool(new_refresh),
            "scope": scope_val,
        }

    def sign_out_oauth(self, server_name: str) -> dict[str, Any]:
        store = self._read_oauth_store()
        store.pop(server_name, None)
        self._write_oauth_store(store)
        self._remove_bearer_token_from_mcp_config(server_name)
        for fid in [k for k, v in self._pending.items() if v.server_name == server_name]:
            self._pending.pop(fid, None)
        return {"server_name": server_name, "authenticated": False, "signed_out": True}
