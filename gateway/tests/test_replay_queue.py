"""Tests for FIFO follow-up queue, clean end_turn drain, cancel pause, send-now, and WS/HTTP replay."""

from __future__ import annotations

from pathlib import Path
import time

import pytest
from starlette.testclient import TestClient

from gravity_well_gateway.config import GatewayConfig
from gravity_well_gateway.main import create_app
from conftest import SandboxEnv


def _wait_idle(client: TestClient, headers: dict[str, str], sid: str, timeout: float = 5.0) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        r = client.get(f"/api/sessions/{sid}", headers=headers).json()
        if r["status"] in ("idle", "error"):
            return
        time.sleep(0.03)
    raise AssertionError(f"Session {sid} did not settle to idle/error")


def test_fifo_queue_auto_drains_on_clean_end_turn(
    client: TestClient,
    auth_headers: dict[str, str],
    sandbox: SandboxEnv,
) -> None:
    sid = client.post(
        "/api/sessions",
        headers=auth_headers,
        json={"cwd": str(sandbox.workspace)},
    ).json()["session_id"]

    # Hold turn 1 open with a file gate
    p1 = client.post(
        f"/api/sessions/{sid}/prompt",
        headers=auth_headers,
        json={"text": "[HOLD:gate1] First turn"},
    )
    assert p1.status_code == 202
    assert p1.json()["status"] == "started"

    # enqueue_if_busy=False while busy MUST 409 session_busy
    conflict = client.post(
        f"/api/sessions/{sid}/prompt",
        headers=auth_headers,
        json={"text": "Should conflict", "enqueue_if_busy": False},
    )
    assert conflict.status_code == 409
    assert conflict.json()["error"]["code"] == "session_busy"

    # Enqueue 2 items while busy + edit 2nd item
    q1 = client.post(
        f"/api/sessions/{sid}/prompt",
        headers=auth_headers,
        json={"text": "Second queued", "enqueue_if_busy": True},
    )
    assert q1.status_code == 202
    assert q1.json()["status"] == "queued"

    q2 = client.post(
        f"/api/sessions/{sid}/queue",
        headers=auth_headers,
        json={"text": "Third draft"},
    )
    assert q2.status_code == 201
    q2_id = q2.json()["item_id"]

    edited = client.patch(
        f"/api/sessions/{sid}/queue/{q2_id}",
        headers=auth_headers,
        json={"text": "Third edited"},
    )
    assert edited.status_code == 200
    assert edited.json()["text"] == "Third edited"

    # Release gate1 -> turn 1 ends cleanly -> Second & Third edited auto-drain in FIFO order!
    (sandbox.home / ".release_gate1").write_text("go", encoding="utf-8")
    _wait_idle(client, auth_headers, sid)

    q_after = client.get(f"/api/sessions/{sid}/queue", headers=auth_headers).json()
    assert q_after["items"] == []
    assert q_after["paused"] is False

    evs = client.get(f"/api/sessions/{sid}/events?after=0", headers=auth_headers).json()["events"]
    user_msgs = [e["payload"]["text"] for e in evs if e["type"] == "user_message_chunk"]
    assert user_msgs == ["[HOLD:gate1] First turn", "Second queued", "Third edited"]


def test_cancel_and_crash_do_not_auto_drain_queue(
    client: TestClient,
    auth_headers: dict[str, str],
    sandbox: SandboxEnv,
) -> None:
    sid = client.post(
        "/api/sessions",
        headers=auth_headers,
        json={"cwd": str(sandbox.workspace)},
    ).json()["session_id"]

    client.post(
        f"/api/sessions/{sid}/prompt",
        headers=auth_headers,
        json={"text": "[HOLD:cancel_gate] Long running"},
    )
    q_item = client.post(
        f"/api/sessions/{sid}/queue",
        headers=auth_headers,
        json={"text": "Must NOT run after cancel"},
    ).json()

    cancel_r = client.post(f"/api/sessions/{sid}/cancel", headers=auth_headers)
    assert cancel_r.status_code == 202
    assert cancel_r.json()["status"] == "cancelling"

    _wait_idle(client, auth_headers, sid)

    # Queue MUST remain intact and paused after cancel
    q_state = client.get(f"/api/sessions/{sid}/queue", headers=auth_headers).json()
    assert q_state["paused"] is True
    assert len(q_state["items"]) == 1
    assert q_state["items"][0]["item_id"] == q_item["item_id"]

    evs = client.get(f"/api/sessions/{sid}/events?after=0", headers=auth_headers).json()["events"]
    user_msgs = [e["payload"]["text"] for e in evs if e["type"] == "user_message_chunk"]
    assert "Must NOT run after cancel" not in user_msgs

    # Delete queued item cleanly
    del_q = client.delete(f"/api/sessions/{sid}/queue/{q_item['item_id']}", headers=auth_headers)
    assert del_q.status_code == 200
    assert del_q.json()["deleted"] is True

    # Crash mid-turn also pauses queue and preserves queued items
    sid2 = client.post(
        "/api/sessions",
        headers=auth_headers,
        json={"cwd": str(sandbox.workspace)},
    ).json()["session_id"]
    client.post(f"/api/sessions/{sid2}/queue", headers=auth_headers, json={"text": "Keep on crash"})
    client.post(f"/api/sessions/{sid2}/prompt", headers=auth_headers, json={"text": "[CRASH]"})
    _wait_idle(client, auth_headers, sid2)

    snap2 = client.get(f"/api/sessions/{sid2}", headers=auth_headers).json()
    assert snap2["status"] == "error"
    assert snap2["is_live"] is False
    q2 = client.get(f"/api/sessions/{sid2}/queue", headers=auth_headers).json()
    assert q2["paused"] is True
    assert [i["text"] for i in q2["items"]] == ["Keep on crash"]

    evs2 = client.get(f"/api/sessions/{sid2}/events?after=0", headers=auth_headers).json()["events"]
    user_msgs2 = [e["payload"]["text"] for e in evs2 if e["type"] == "user_message_chunk"]
    assert "Keep on crash" not in user_msgs2


def test_send_now_interrupts_active_turn_and_runs_target_item_first(
    client: TestClient,
    auth_headers: dict[str, str],
    sandbox: SandboxEnv,
) -> None:
    sid = client.post(
        "/api/sessions",
        headers=auth_headers,
        json={"cwd": str(sandbox.workspace)},
    ).json()["session_id"]

    client.post(
        f"/api/sessions/{sid}/prompt",
        headers=auth_headers,
        json={"text": "[HOLD:sendnow_gate] Busy turn"},
    )
    item_a = client.post(
        f"/api/sessions/{sid}/queue",
        headers=auth_headers,
        json={"text": "Later item A"},
    ).json()
    item_b = client.post(
        f"/api/sessions/{sid}/queue",
        headers=auth_headers,
        json={"text": "Urgent item B"},
    ).json()

    # Trigger send-now with interrupt=True on item_b (originally at position 1)
    sn = client.post(
        f"/api/sessions/{sid}/queue/{item_b['item_id']}/send-now",
        headers=auth_headers,
        json={"interrupt": True},
    )
    assert sn.status_code == 202
    assert sn.json()["status"] == "interrupting"

    _wait_idle(client, auth_headers, sid)

    evs = client.get(f"/api/sessions/{sid}/events?after=0", headers=auth_headers).json()["events"]
    user_msgs = [e["payload"]["text"] for e in evs if e["type"] == "user_message_chunk"]
    # Busy turn was cancelled -> Urgent item B ran immediately -> clean end_turn drained Later item A
    assert user_msgs == ["[HOLD:sendnow_gate] Busy turn", "Urgent item B", "Later item A"]
    assert item_a["item_id"] != item_b["item_id"]


def test_monotonic_cursor_seq_ws_replay_and_ring_buffer_truncation(
    sandbox: SandboxEnv,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # Configure small session ring buffer (maxlen=5) to test truncation & resync_required deterministically
    monkeypatch.setenv("GRAVITY_WELL_SESSION_RING_MAXLEN", "5")
    monkeypatch.setenv("GRAVITY_WELL_GLOBAL_RING_MAXLEN", "6")

    cfg = GatewayConfig.from_env()
    app = create_app(config=cfg)
    headers = {"Authorization": f"Bearer {sandbox.token}"}

    with TestClient(app) as tc:
        sid = tc.post(
            "/api/sessions",
            headers=headers,
            json={"cwd": str(sandbox.workspace)},
        ).json()["session_id"]

        # Run two prompts to generate > 6 events, evicting early cursors from ring buffer
        for idx in range(2):
            tc.post(
                f"/api/sessions/{sid}/prompt",
                headers=headers,
                json={"text": f"Turn {idx}"},
            )
            _wait_idle(tc, headers, sid)

        # HTTP catch-up with stale cursor (after=1) reports truncated=True
        stale_http = tc.get(f"/api/sessions/{sid}/events?after=1", headers=headers).json()
        assert stale_http["truncated"] is True
        assert stale_http["min_cursor_retained"] > 2

        min_ret = stale_http["min_cursor_retained"]
        # Catch-up from (min_ret - 1) is NOT truncated and returns strictly monotonic cursors & seqs
        exact_http = tc.get(
            f"/api/sessions/{sid}/events?after={min_ret - 1}",
            headers=headers,
        ).json()
        assert exact_http["truncated"] is False
        replayed_evs = exact_http["events"]
        assert len(replayed_evs) == 5
        cursors = [e["cursor"] for e in replayed_evs]
        seqs = [e["seq"] for e in replayed_evs]
        assert cursors == sorted(cursors) and len(set(cursors)) == len(cursors)
        assert seqs == sorted(seqs) and seqs[-1] - seqs[0] == len(seqs) - 1

        # WS single-session stream emits hello + resync_required when after=1
        ticket1 = tc.post("/api/ws-ticket", headers=headers).json()["ticket"]
        with tc.websocket_connect(f"/api/sessions/{sid}/ws?after=1&ticket={ticket1}") as ws1:
            hello1 = ws1.receive_json()
            assert hello1["frame"] == "hello"
            resync = ws1.receive_json()
            assert resync["frame"] == "resync_required"
            assert resync["after"] == 1
            assert resync["min_cursor_retained"] == min_ret

        # WS multiplexed stream replays events > (min_ret) then emits replay_complete + live events
        ticket2 = tc.post("/api/ws-ticket", headers=headers).json()["ticket"]
        with tc.websocket_connect(f"/api/ws?after={min_ret}&ticket={ticket2}") as ws2:
            hello2 = ws2.receive_json()
            assert hello2["frame"] == "hello"
            seen_frames = []
            while True:
                fr = ws2.receive_json()
                seen_frames.append(fr)
                if fr["frame"] == "replay_complete":
                    break
            ev_frames = [f for f in seen_frames if f["frame"] == "event"]
            assert seen_frames[-1]["replayed_count"] == len(ev_frames)
            assert all(f["cursor"] > min_ret for f in ev_frames)

            # Subscribe filter test
            ws2.send_json({"frame": "subscribe", "session_ids": [sid]})
            tc.patch(f"/api/sessions/{sid}", headers=headers, json={"title": "Live WS Title"})
            live_ev = ws2.receive_json()
            assert live_ev["frame"] == "event"
            assert live_ev["type"] == "session_info_update"
            assert live_ev["payload"]["title"] == "Live WS Title"
