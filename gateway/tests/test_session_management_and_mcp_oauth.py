"""Tests for session pin/archive/delete management and HTTP MCP server OAuth 2.0 + PKCE."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlparse

import httpx
from starlette.testclient import TestClient

from conftest import SandboxEnv, seed_summaries_db
from gravity_well_gateway.config import GatewayConfig
from gravity_well_gateway.main import create_app
from test_ecosystem import _install_ecosystem_agy_shim


def test_session_pin_archive_unarchive_and_permanent_delete(
    client: TestClient,
    auth_headers: dict[str, str],
    sandbox: SandboxEnv,
) -> None:
    seed_summaries_db(
        sandbox.agy_cli_dir / "conversation_summaries.db",
        [
            {
                "conversation_id": "hist-old-1",
                "title": "Historical Alpha",
                "preview": "Alpha preview",
                "step_count": 3,
                "last_modified_time": "2026-11-10T10:00:00Z",
                "workspace_uris": json.dumps([f"file://{sandbox.workspace.resolve()}"]),
            },
            {
                "conversation_id": "hist-old-2",
                "title": "Historical Beta",
                "preview": "Beta preview",
                "step_count": 5,
                "last_modified_time": "2026-11-12T10:00:00Z",
                "workspace_uris": json.dumps([f"file://{sandbox.workspace.resolve()}"]),
            },
        ],
    )

    s1 = client.post(
        "/api/sessions",
        headers=auth_headers,
        json={"cwd": str(sandbox.workspace), "title": "Live One"},
    ).json()["session_id"]
    s2 = client.post(
        "/api/sessions",
        headers=auth_headers,
        json={"cwd": str(sandbox.workspace), "title": "Live Two"},
    ).json()["session_id"]

    # Pin historical Alpha -> MUST float above even live sessions!
    pin_r = client.patch(
        "/api/sessions/hist-old-1",
        headers=auth_headers,
        json={"pinned": True},
    )
    assert pin_r.status_code == 200
    assert pin_r.json()["pinned"] is True
    assert pin_r.json()["pinned_at"] is not None

    listed = client.get("/api/sessions?status=all", headers=auth_headers).json()["items"]
    assert listed[0]["session_id"] == "hist-old-1"
    assert listed[0]["pinned"] is True

    # Pin Live One after Alpha -> newest pinned floats to very top
    pin_live = client.patch(
        f"/api/sessions/{s1}",
        headers=auth_headers,
        json={"pinned": True},
    )
    assert pin_live.status_code == 200
    assert pin_live.json()["pinned"] is True

    listed2 = client.get("/api/sessions?status=all", headers=auth_headers).json()["items"]
    assert listed2[0]["session_id"] == s1
    assert listed2[1]["session_id"] == "hist-old-1"

    # Unpin Live One -> hist-old-1 becomes top again
    unpin_r = client.patch(
        f"/api/sessions/{s1}",
        headers=auth_headers,
        json={"pinned": False},
    )
    assert unpin_r.status_code == 200
    assert unpin_r.json()["pinned"] is False
    listed3 = client.get("/api/sessions?status=all", headers=auth_headers).json()["items"]
    assert listed3[0]["session_id"] == "hist-old-1"

    # Archive live session s2 -> closes ACP process, hides from status=all, shows in status=archived
    arch_r = client.patch(
        f"/api/sessions/{s2}",
        headers=auth_headers,
        json={"archived": True},
    )
    assert arch_r.status_code == 200
    assert arch_r.json()["archived"] is True
    assert arch_r.json()["is_live"] is False

    all_ids = [x["session_id"] for x in client.get("/api/sessions?status=all", headers=auth_headers).json()["items"]]
    assert s2 not in all_ids

    arch_items = client.get("/api/sessions?status=archived", headers=auth_headers).json()["items"]
    arch_by_id = {x["session_id"]: x for x in arch_items}
    assert s2 in arch_by_id
    assert arch_by_id[s2]["archived"] is True

    # Unarchive historical Beta after archiving it
    assert client.patch("/api/sessions/hist-old-2", headers=auth_headers, json={"archived": True}).status_code == 200
    assert "hist-old-2" not in [
        x["session_id"] for x in client.get("/api/sessions?status=all", headers=auth_headers).json()["items"]
    ]
    assert client.patch("/api/sessions/hist-old-2", headers=auth_headers, json={"archived": False}).status_code == 200
    assert "hist-old-2" in [
        x["session_id"] for x in client.get("/api/sessions?status=all", headers=auth_headers).json()["items"]
    ]

    # Permanent delete works on both live (s1) and historical (hist-old-1) sessions
    del_live = client.delete(f"/api/sessions/{s1}?permanent=true", headers=auth_headers)
    assert del_live.status_code == 200
    assert del_live.json() == {"session_id": s1, "status": "deleted", "deleted": True}
    assert client.get(f"/api/sessions/{s1}", headers=auth_headers).status_code == 404

    del_hist = client.delete("/api/sessions/hist-old-1?permanent=true", headers=auth_headers)
    assert del_hist.status_code == 200
    assert del_hist.json()["deleted"] is True
    assert client.get("/api/sessions/hist-old-1", headers=auth_headers).status_code == 404

    # Deleted sessions must never appear in list_sessions or storage search
    all_after = [x["session_id"] for x in client.get("/api/sessions?status=all", headers=auth_headers).json()["items"]]
    arch_after = [x["session_id"] for x in client.get("/api/sessions?status=archived", headers=auth_headers).json()["items"]]
    storage_after = [x["conversation_id"] for x in client.get("/api/storage/conversations", headers=auth_headers).json()["items"]]
    assert s1 not in all_after and s1 not in arch_after
    assert "hist-old-1" not in all_after and "hist-old-1" not in arch_after and "hist-old-1" not in storage_after


def test_mcp_oauth_cimd_pkce_refresh_and_sign_out(
    sandbox: SandboxEnv,
    auth_headers: dict[str, str],
    tmp_path: Path,
) -> None:
    _install_ecosystem_agy_shim(sandbox, tmp_path)

    token_calls: list[dict[str, str]] = []

    def _mock_oauth_handler(request: httpx.Request) -> httpx.Response:
        url = str(request.url)
        if url == "https://mcp.example.test/api/mcp":
            return httpx.Response(
                401,
                headers={
                    "WWW-Authenticate": 'Bearer resource_metadata="https://mcp.example.test/.well-known/oauth-protected-resource"'
                },
            )
        if url == "https://mcp.example.test/.well-known/oauth-protected-resource":
            return httpx.Response(
                200,
                json={
                    "resource": "https://mcp.example.test/api/mcp",
                    "authorization_servers": ["https://auth.example.test"],
                    "scopes_supported": ["mcp:read", "mcp:write"],
                },
            )
        if url == "https://auth.example.test/.well-known/oauth-authorization-server":
            return httpx.Response(
                200,
                json={
                    "issuer": "https://auth.example.test",
                    "authorization_endpoint": "https://auth.example.test/authorize",
                    "token_endpoint": "https://auth.example.test/token",
                    "client_id_metadata_document_supported": True,
                    "code_challenge_methods_supported": ["S256"],
                },
            )
        if url == "https://auth.example.test/token":
            parsed = {k: v[0] for k, v in parse_qs(request.content.decode("utf-8")).items()}
            token_calls.append(parsed)
            if parsed.get("grant_type") == "authorization_code":
                return httpx.Response(
                    200,
                    json={
                        "access_token": "mcp_secret_access_tok_111",
                        "refresh_token": "mcp_secret_refresh_tok_222",
                        "token_type": "Bearer",
                        "expires_in": 3600,
                        "scope": "mcp:read mcp:write",
                    },
                )
            if parsed.get("grant_type") == "refresh_token":
                return httpx.Response(
                    200,
                    json={
                        "access_token": "mcp_secret_access_tok_333",
                        "refresh_token": "mcp_secret_refresh_tok_444",
                        "token_type": "Bearer",
                        "expires_in": 7200,
                    },
                )
        return httpx.Response(404)

    transport = httpx.MockTransport(_mock_oauth_handler)
    app = create_app(config=GatewayConfig.from_env())

    with TestClient(app) as tc:
        app.state.ecosystem.oauth._http_client_factory = lambda: httpx.AsyncClient(transport=transport)

        # 1. Add remote HTTP MCP server
        add_r = tc.post(
            "/api/ecosystem/mcp",
            headers=auth_headers,
            json={
                "name": "linear-mcp",
                "type": "http",
                "target": "https://mcp.example.test/api/mcp",
            },
        )
        assert add_r.status_code == 201
        assert add_r.json()["oauth_status"]["authenticated"] is False

        # 2. Start OAuth -> discovers CIMD & builds PKCE authorization URL
        start_r = tc.post("/api/ecosystem/mcp/linear-mcp/oauth/start", headers=auth_headers, json={})
        assert start_r.status_code == 200
        start_data = start_r.json()
        flow_id = start_data["flow_id"]
        assert start_data["registration_method"] == "cimd"
        auth_url = urlparse(start_data["authorization_url"])
        q = parse_qs(auth_url.query)
        assert q["client_id"] == ["https://antigravity.google/oauth/client-metadata.json"]
        assert q["redirect_uri"] == ["https://antigravity.google/oauth-callback"]
        assert q["code_challenge_method"] == ["S256"]
        assert q["resource"] == ["https://mcp.example.test/api/mcp"]
        state = q["state"][0]

        # 3. Wrong state MUST fail 400
        bad_state = tc.post(
            "/api/ecosystem/mcp/linear-mcp/oauth/complete",
            headers=auth_headers,
            json={"flow_id": flow_id, "code": "https://antigravity.google/oauth-callback?code=abc&state=wrong"},
        )
        assert bad_state.status_code == 400

        # 4. Complete OAuth with full callback URL -> stores Bearer header & never leaks token
        comp_r = tc.post(
            "/api/ecosystem/mcp/linear-mcp/oauth/complete",
            headers=auth_headers,
            json={
                "flow_id": flow_id,
                "code": f"https://antigravity.google/oauth-callback?code=auth_code_xyz&state={state}",
            },
        )
        assert comp_r.status_code == 200
        comp_data = comp_r.json()
        assert comp_data["authenticated"] is True
        assert comp_data["has_refresh_token"] is True
        assert "mcp_secret_access_tok_111" not in comp_r.text
        assert "mcp_secret_refresh_tok_222" not in comp_r.text

        # Verify mcp_config.json has Bearer token on disk, while GET masks it!
        on_disk = json.loads((sandbox.gemini_dir / "config" / "mcp_config.json").read_text(encoding="utf-8"))
        assert on_disk["mcpServers"]["linear-mcp"]["headers"]["Authorization"] == "Bearer mcp_secret_access_tok_111"

        listed = tc.get("/api/ecosystem/mcp", headers=auth_headers)
        assert listed.status_code == 200
        assert "mcp_secret_access_tok_111" not in listed.text
        srv = next(x for x in listed.json()["items"] if x["name"] == "linear-mcp")
        assert srv["headers"]["Authorization"] == "[REDACTED]"
        assert srv["oauth_status"]["authenticated"] is True
        assert srv["oauth_status"]["has_refresh_token"] is True

        # 5. Refresh OAuth token -> rotates Bearer header
        ref_r = tc.post("/api/ecosystem/mcp/linear-mcp/oauth/refresh", headers=auth_headers)
        assert ref_r.status_code == 200
        assert "mcp_secret_access_tok_333" not in ref_r.text
        on_disk2 = json.loads((sandbox.gemini_dir / "config" / "mcp_config.json").read_text(encoding="utf-8"))
        assert on_disk2["mcpServers"]["linear-mcp"]["headers"]["Authorization"] == "Bearer mcp_secret_access_tok_333"

        # 6. Sign out removes token & header
        so_r = tc.delete("/api/ecosystem/mcp/linear-mcp/oauth", headers=auth_headers)
        assert so_r.status_code == 200
        assert so_r.json()["authenticated"] is False
        on_disk3 = json.loads((sandbox.gemini_dir / "config" / "mcp_config.json").read_text(encoding="utf-8"))
        assert "Authorization" not in on_disk3["mcpServers"]["linear-mcp"].get("headers", {})
