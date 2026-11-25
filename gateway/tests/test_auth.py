"""Tests for bearer auth, 0600 auto-minting, 30s single-use WS tickets, and query-token rejection."""

from __future__ import annotations

import os
from pathlib import Path
import stat

import pytest
from starlette.testclient import TestClient, WebSocketDenialResponse

from gravity_well_gateway.auth import AuthManager
from gravity_well_gateway.config import GATEWAY_VERSION, GatewayConfig, TOKEN_VAR_NAME
from gravity_well_gateway.main import create_app
from conftest import SandboxEnv


def test_health_is_unauthenticated_and_leaks_no_paths(client: TestClient, sandbox: SandboxEnv) -> None:
    resp = client.get("/api/health")
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "ok"
    assert body["service"] == "gravity-well-gateway"
    assert body["gateway_version"] == GATEWAY_VERSION
    assert body["agy_version"] == "1.5.15"
    assert body["protocol_version"] == 1
    assert body["auth_required"] is True
    assert isinstance(body["uptime_seconds"], int)
    serialized = resp.text
    assert str(sandbox.home) not in serialized
    assert sandbox.token not in serialized


def test_info_requires_bearer_token_and_returns_capabilities(
    client: TestClient,
    auth_headers: dict[str, str],
    sandbox: SandboxEnv,
) -> None:
    unauth = client.get("/api/info")
    assert unauth.status_code == 401
    err = unauth.json()["error"]
    assert err["code"] == "unauthorized"
    assert err["status"] == 401

    wrong = client.get("/api/info", headers={"Authorization": "Bearer wrong-token"})
    assert wrong.status_code == 401
    assert wrong.json()["error"]["code"] == "unauthorized"

    ok = client.get("/api/info", headers=auth_headers)
    assert ok.status_code == 200
    data = ok.json()
    assert data["gateway_version"] == GATEWAY_VERSION
    assert data["agy_version"] == "1.5.15"
    assert data["acp_protocol_version"] == 1
    assert data["default_cwd"] == str(sandbox.workspace.resolve())
    caps = data["capabilities"]
    assert caps["load_session"] is True
    assert caps["resume_session"] is True
    assert caps["fork"] is False
    assert caps["acp_rename"] is False
    assert caps["gateway_title_override"] is True
    assert caps["spawn_add_dir"] is True
    assert caps["elicitation"] is True


def test_auto_mints_0600_gateway_env_when_missing(
    sandbox: SandboxEnv,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv(TOKEN_VAR_NAME, raising=False)
    env_file = sandbox.config_dir / "gateway.env"
    if env_file.exists():
        env_file.unlink()

    cfg = GatewayConfig.from_env()
    auth1 = AuthManager(cfg, env={})
    assert env_file.is_file()
    mode = stat.S_IMODE(os.stat(env_file).st_mode)
    assert mode == 0o600

    raw_text = env_file.read_text(encoding="utf-8")
    assert raw_text.startswith(f"{TOKEN_VAR_NAME}=")
    minted_token = raw_text.split("=", 1)[1].strip()
    assert len(minted_token) >= 32
    assert auth1.verify_bearer(minted_token) is True

    # Reloading reuses the same persisted token
    auth2 = AuthManager(cfg, env={})
    assert auth2.verify_bearer(minted_token) is True


@pytest.mark.parametrize("param_name", ["token", "bearer", "auth", "TOKEN", "ticket"])
def test_http_query_string_tokens_rejected_with_401(
    client: TestClient,
    auth_headers: dict[str, str],
    sandbox: SandboxEnv,
    param_name: str,
) -> None:
    resp = client.get(f"/api/info?{param_name}={sandbox.token}", headers=auth_headers)
    assert resp.status_code == 401
    assert resp.json()["error"]["code"] == "unauthorized"

    health_resp = client.get(f"/api/health?{param_name}={sandbox.token}")
    assert health_resp.status_code == 401
    assert health_resp.json()["error"]["code"] == "unauthorized"


def test_ws_ticket_single_use_ttl_and_bound(
    sandbox: SandboxEnv,
) -> None:
    cfg = GatewayConfig.from_env()
    app = create_app(config=cfg)
    headers = {"Authorization": f"Bearer {sandbox.token}"}

    with TestClient(app) as tc:
        mint_resp = tc.post("/api/ws-ticket", headers=headers)
        assert mint_resp.status_code == 201
        ticket_data = mint_resp.json()
        ticket = ticket_data["ticket"]
        assert ticket.startswith("gwt_")
        assert ticket_data["ttl_seconds"] == 30
        assert ticket_data["expires_at"].endswith("Z")

        # First WS upgrade succeeds and receives hello frame
        with tc.websocket_connect(f"/api/ws?ticket={ticket}") as ws:
            hello = ws.receive_json()
            assert hello["frame"] == "hello"
            assert hello["gateway_version"] == GATEWAY_VERSION
            ws.send_json({"frame": "ping"})
            pong = ws.receive_json()
            assert pong["frame"] == "pong"

        # Reusing the same ticket MUST fail with HTTP 401 before WS upgrade
        with pytest.raises(WebSocketDenialResponse) as exc_info:
            with tc.websocket_connect(f"/api/ws?ticket={ticket}"):
                pass
        assert exc_info.value.status_code == 401
        assert exc_info.value.json()["error"]["code"] == "unauthorized"

        # Passing permanent token in ?ticket= or ?token= MUST fail with HTTP 401
        for bad_qs in (
            f"ticket={sandbox.token}",
            f"token={sandbox.token}",
            f"bearer={sandbox.token}",
            f"auth={sandbox.token}",
            "",
        ):
            url = f"/api/ws?{bad_qs}" if bad_qs else "/api/ws"
            with pytest.raises(WebSocketDenialResponse) as bad_exc:
                with tc.websocket_connect(url):
                    pass
            assert bad_exc.value.status_code == 401
            assert bad_exc.value.json()["error"]["code"] == "unauthorized"

        # Valid Authorization: Bearer header on WS upgrade succeeds directly
        with tc.websocket_connect("/api/ws", headers=headers) as ws_hdr:
            hello_hdr = ws_hdr.receive_json()
            assert hello_hdr["frame"] == "hello"


def test_ws_ticket_expiration_and_max_store_cap(sandbox: SandboxEnv) -> None:
    cfg = GatewayConfig.from_env()
    short_auth = AuthManager(cfg, ticket_ttl_seconds=0, max_tickets=4)
    expired_t = short_auth.mint_ws_ticket()["ticket"]
    assert short_auth.consume_ws_ticket(expired_t) is False

    capped_auth = AuthManager(cfg, ticket_ttl_seconds=30, max_tickets=3)
    minted = [capped_auth.mint_ws_ticket()["ticket"] for _ in range(5)]
    # Oldest two evicted when store capped at 3
    assert capped_auth.consume_ws_ticket(minted[0]) is False
    assert capped_auth.consume_ws_ticket(minted[1]) is False
    assert capped_auth.consume_ws_ticket(minted[2]) is True
    assert capped_auth.consume_ws_ticket(minted[3]) is True
    assert capped_auth.consume_ws_ticket(minted[4]) is True


def test_malformed_json_returns_400_bad_request(
    client: TestClient,
    auth_headers: dict[str, str],
) -> None:
    resp = client.post(
        "/api/sessions",
        content=b"{not valid json",
        headers={**auth_headers, "Content-Type": "application/json"},
    )
    assert resp.status_code == 400
    assert resp.json()["error"]["code"] == "bad_request"


async def test_live_tcp_websocket_upgrade_401_and_single_use_ticket(
    sandbox: SandboxEnv,
) -> None:
    """Verify real Uvicorn + websockets TCP wire behavior (HTTP 401 before WS upgrade)."""
    import asyncio
    import json
    import socket
    import httpx
    import uvicorn
    import websockets
    from websockets.exceptions import InvalidStatus

    cfg = GatewayConfig.from_env()
    asgi_app = create_app(config=cfg)

    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.bind(("127.0.0.1", 0))
    sock.listen(16)
    port = sock.getsockname()[1]

    u_cfg = uvicorn.Config(asgi_app, host="127.0.0.1", port=port, log_level="error")
    server = uvicorn.Server(u_cfg)
    srv_task = asyncio.create_task(server.serve(sockets=[sock]))
    while not server.started:
        await asyncio.sleep(0.01)

    try:
        async with httpx.AsyncClient(base_url=f"http://127.0.0.1:{port}") as hc:
            t_resp = await hc.post(
                "/api/ws-ticket",
                headers={"Authorization": f"Bearer {sandbox.token}"},
            )
            assert t_resp.status_code == 201
            ticket = t_resp.json()["ticket"]

        # 1. Valid single-use ticket connects and receives hello frame
        async with websockets.connect(f"ws://127.0.0.1:{port}/api/ws?ticket={ticket}") as ws:
            hello = json.loads(await ws.recv())
            assert hello["frame"] == "hello"

        # 2. Reusing single-use ticket fails with genuine HTTP 401 over TCP
        with pytest.raises(InvalidStatus) as reuse_exc:
            async with websockets.connect(f"ws://127.0.0.1:{port}/api/ws?ticket={ticket}"):
                pass
        assert reuse_exc.value.response.status_code == 401

        # 3. Permanent token in query string fails with genuine HTTP 401 over TCP
        with pytest.raises(InvalidStatus) as perm_exc:
            async with websockets.connect(f"ws://127.0.0.1:{port}/api/ws?token={sandbox.token}"):
                pass
        assert perm_exc.value.response.status_code == 401
    finally:
        server.should_exit = True
        await srv_task
        sock.close()
