"""Tests for ACP process supervision, child env scrub, path policy, permissions, and elicitation."""

from __future__ import annotations

import json
import os
from pathlib import Path
import time

import pytest
from starlette.testclient import TestClient

from gravity_well_gateway.config import ALLOWED_CHILD_ENV_KEYS, TOKEN_VAR_NAME
from conftest import SandboxEnv, seed_summaries_db


def _wait_for_session_status(
    client: TestClient,
    headers: dict[str, str],
    session_id: str,
    expected: set[str],
    timeout_s: float = 5.0,
) -> dict[str, object]:
    deadline = time.monotonic() + timeout_s
    last: dict[str, object] = {}
    while time.monotonic() < deadline:
        r = client.get(f"/api/sessions/{session_id}", headers=headers)
        assert r.status_code == 200
        last = r.json()
        if last.get("status") in expected:
            return last
        time.sleep(0.03)
    raise AssertionError(f"Session {session_id} stayed in {last.get('status')}, expected {expected}")


def test_child_env_scrub_and_spawn_flags(
    client: TestClient,
    auth_headers: dict[str, str],
    sandbox: SandboxEnv,
) -> None:
    resp = client.post(
        "/api/sessions",
        headers=auth_headers,
        json={
            "cwd": str(sandbox.workspace),
            "add_dirs": [str(sandbox.extra_dir)],
            "title": "Env Scrub Check",
            "model": "gemini-3.8-flash",
            "thought_level": "medium",
            "mode": "plan",
            "sandbox": True,
        },
    )
    assert resp.status_code == 201
    snap = resp.json()
    sid = snap["session_id"]
    assert snap["title"] == "Env Scrub Check"
    assert snap["title_source"] == "gateway"
    assert snap["cwd"] == str(sandbox.workspace.resolve())
    assert snap["add_dirs"] == [str(sandbox.extra_dir.resolve())]
    assert snap["sandbox"] is True

    by_id = {opt["id"]: opt["currentValue"] for opt in snap["config_options"]}
    assert by_id["model"] == "gemini-3.8-flash"
    assert by_id["thought_level"] == "medium"
    assert by_id["mode"] == "plan"

    spawn_log = sandbox.home / ".fake_agy_spawns.jsonl"
    assert spawn_log.is_file()
    records = [json.loads(line) for line in spawn_log.read_text(encoding="utf-8").splitlines() if line]
    acp_spawns = [r for r in records if r["argv"][:2] == ["acp", "--stdio"]]
    assert len(acp_spawns) == 1
    rec = acp_spawns[0]
    assert "--add-dir" in rec["argv"]
    assert str(sandbox.extra_dir.resolve()) in rec["argv"]
    assert "--sandbox" in rec["argv"]
    assert rec["cwd"] == str(sandbox.workspace.resolve())

    child_env: dict[str, str] = rec["env"]
    assert child_env.get("ANTIGRAVITY_ENV_STRICT") == "1"
    assert child_env.get("HOME") == str(sandbox.home.resolve())

    # Verify zero host secrets or PYTHONPATH leaked into child env
    allowed_set = set(ALLOWED_CHILD_ENV_KEYS) | {"ANTIGRAVITY_ENV_STRICT", "LC_CTYPE"}
    # Python runtime may set __PYVENV_LAUNCHER__ on macOS only; check secret suffixes strictly
    for k in child_env:
        assert not k.endswith(("_KEY", "_TOKEN", "_SECRET")), f"Secret env key leaked: {k}"
        assert k != "PYTHONPATH"
        assert k != TOKEN_VAR_NAME
        assert not k.startswith("GRAVITY_WELL_")
    assert set(child_env.keys()).issubset(allowed_set)

    # Clean teardown reaps child PID
    child_pid = int(rec["pid"])
    del_resp = client.delete(f"/api/sessions/{sid}", headers=auth_headers)
    assert del_resp.status_code == 200
    assert del_resp.json() == {"session_id": sid, "status": "closed"}

    with pytest.raises(ProcessLookupError):
        os.kill(child_pid, 0)


@pytest.mark.parametrize(
    "bad_rel",
    [
        ".hermes",
        ".gemini",
        ".ssh",
        ".gnupg",
        ".config/gravity-well",
        ".local/share/keyrings",
    ],
)
def test_workspace_path_policy_denies_sensitive_dotdirs_and_outside_roots(
    client: TestClient,
    auth_headers: dict[str, str],
    sandbox: SandboxEnv,
    tmp_path: Path,
    bad_rel: str,
) -> None:
    denied_target = sandbox.home / bad_rel
    denied_target.mkdir(parents=True, exist_ok=True)

    r1 = client.post("/api/sessions", headers=auth_headers, json={"cwd": str(denied_target)})
    assert r1.status_code == 403
    assert r1.json()["error"]["code"] == "forbidden"

    # Symlink inside workspace pointing to sensitive dotdir MUST also 403 via realpath
    sneaky_link = sandbox.workspace / f"link_{bad_rel.replace('/', '_')}"
    if not sneaky_link.exists():
        sneaky_link.symlink_to(denied_target)
    r2 = client.post(
        "/api/sessions",
        headers=auth_headers,
        json={"cwd": str(sandbox.workspace), "add_dirs": [str(sneaky_link)]},
    )
    assert r2.status_code == 403
    assert r2.json()["error"]["code"] == "forbidden"

    outside_dir = tmp_path / "outside_allowed_roots"
    outside_dir.mkdir(parents=True, exist_ok=True)
    r3 = client.post("/api/sessions", headers=auth_headers, json={"cwd": str(outside_dir)})
    assert r3.status_code == 403
    assert r3.json()["error"]["code"] == "forbidden"


def test_session_lifecycle_resume_load_fork_rename_and_config(
    client: TestClient,
    auth_headers: dict[str, str],
    sandbox: SandboxEnv,
) -> None:
    create_r = client.post("/api/sessions", headers=auth_headers, json={"cwd": str(sandbox.workspace)})
    assert create_r.status_code == 201
    sid = create_r.json()["session_id"]

    # Run a prompt turn
    pr = client.post(
        f"/api/sessions/{sid}/prompt",
        headers=auth_headers,
        json={"text": "Hello Gravity Well"},
    )
    assert pr.status_code == 202
    assert pr.json()["status"] == "started"
    _wait_for_session_status(client, auth_headers, sid, {"idle"})

    # Fork returns 501 acp_method_unsupported (D4)
    fork_r = client.post(f"/api/sessions/{sid}/fork", headers=auth_headers, json={})
    assert fork_r.status_code == 501
    fork_err = fork_r.json()["error"]
    assert fork_err["code"] == "acp_method_unsupported"
    assert fork_err["details"]["acp_method"] == "session/fork"
    assert fork_err["details"]["acp_code"] == -32601

    # Rename persists custom title and overrides ACP auto-title
    ren_r = client.patch(
        f"/api/sessions/{sid}",
        headers=auth_headers,
        json={"title": "  Pinned Custom Title  "},
    )
    assert ren_r.status_code == 200
    assert ren_r.json()["title"] == "Pinned Custom Title"
    assert ren_r.json()["title_source"] == "gateway"

    # Live config update + 422 on invalid option
    cfg_ok = client.patch(
        f"/api/sessions/{sid}/config",
        headers=auth_headers,
        json={"model": "claude-sonnet-5-5", "thought_level": "low", "mode": "accept-edits"},
    )
    assert cfg_ok.status_code == 200
    opts_by_id = {o["id"]: o["currentValue"] for o in cfg_ok.json()["config_options"]}
    assert opts_by_id == {
        "model": "claude-sonnet-5-5",
        "thought_level": "low",
        "mode": "accept-edits",
    }

    cfg_bad = client.patch(
        f"/api/sessions/{sid}/config",
        headers=auth_headers,
        json={"model": "nonexistent-model-xyz"},
    )
    assert cfg_bad.status_code == 422
    assert cfg_bad.json()["error"]["code"] == "validation_error"

    # Close live session, then resume with load_history=True
    assert client.delete(f"/api/sessions/{sid}", headers=auth_headers).status_code == 200

    res_r = client.post(
        f"/api/sessions/{sid}/resume",
        headers=auth_headers,
        json={"cwd": str(sandbox.workspace), "load_history": True},
    )
    assert res_r.status_code == 200
    resumed = res_r.json()
    assert resumed["is_live"] is True
    assert resumed["title"] == "Pinned Custom Title"

    events_r = client.get(f"/api/sessions/{sid}/events?after=0", headers=auth_headers)
    assert events_r.status_code == 200
    ev_types = [e["type"] for e in events_r.json()["events"]]
    assert "user_message_chunk" in ev_types
    assert "agent_message_chunk" in ev_types

    # Resume unknown session maps ACP -32002 to 404
    missing_r = client.post(
        "/api/sessions/00000000-0000-0000-0000-000000000000/resume",
        headers=auth_headers,
        json={"cwd": str(sandbox.workspace)},
    )
    assert missing_r.status_code == 404
    assert missing_r.json()["error"]["code"] == "not_found"


def test_permission_broker_allow_once_allow_rule_revocation_and_deny(
    client: TestClient,
    auth_headers: dict[str, str],
    sandbox: SandboxEnv,
) -> None:
    sid = client.post(
        "/api/sessions",
        headers=auth_headers,
        json={"cwd": str(sandbox.workspace)},
    ).json()["session_id"]

    # 1. Trigger permission request and resolve with allow_rule
    client.post(
        f"/api/sessions/{sid}/prompt",
        headers=auth_headers,
        json={"text": "[ASK_PERMISSION:pytest -q]"},
    )
    snap = _wait_for_session_status(client, auth_headers, sid, {"awaiting_approval"})
    perms = snap["pending_permissions"]
    assert len(perms) == 1  # type: ignore[arg-type]
    req_id = perms[0]["request_id"]  # type: ignore[index]

    res_perm = client.post(
        f"/api/sessions/{sid}/permissions/{req_id}",
        headers=auth_headers,
        json={"decision": "allow_rule"},
    )
    assert res_perm.status_code == 200
    perm_body = res_perm.json()
    assert perm_body["resolved"] is True
    assert perm_body["option_id"] == "allow-always"
    rule_id = perm_body["rule_id"]
    assert isinstance(rule_id, str) and rule_id.startswith("rule_")

    _wait_for_session_status(client, auth_headers, sid, {"idle"})

    # 2. Matching command in same workspace auto-approves without suspending!
    client.post(
        f"/api/sessions/{sid}/prompt",
        headers=auth_headers,
        json={"text": "[ASK_PERMISSION:pytest -v tests/]"},
    )
    _wait_for_session_status(client, auth_headers, sid, {"idle"})
    evs = client.get(f"/api/sessions/{sid}/events?after=0", headers=auth_headers).json()["events"]
    assert any(e["type"] == "permission_auto_approved" for e in evs)

    # 3. Verify rule listing and revocation path
    rules_r = client.get(
        f"/api/permissions/rules?workspace={sandbox.workspace}",
        headers=auth_headers,
    )
    assert rules_r.status_code == 200
    assert any(r["rule_id"] == rule_id for r in rules_r.json()["items"])

    rev_r = client.delete(f"/api/permissions/rules/{rule_id}", headers=auth_headers)
    assert rev_r.status_code == 200
    assert rev_r.json()["revoked"] is True

    # 4. After revoking rule, command requires interactive approval again; test deny
    client.post(
        f"/api/sessions/{sid}/prompt",
        headers=auth_headers,
        json={"text": "[ASK_PERMISSION:pytest -v]"},
    )
    snap2 = _wait_for_session_status(client, auth_headers, sid, {"awaiting_approval"})
    req_id2 = snap2["pending_permissions"][0]["request_id"]  # type: ignore[index]

    deny_r = client.post(
        f"/api/sessions/{sid}/permissions/{req_id2}",
        headers=auth_headers,
        json={"decision": "deny"},
    )
    assert deny_r.status_code == 200
    assert deny_r.json()["option_id"] == "reject-once"
    _wait_for_session_status(client, auth_headers, sid, {"idle"})


def test_elicitation_question_broker_preserves_schema_keys_and_validates(
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
        json={"text": "[ASK_QUESTION]"},
    )
    snap = _wait_for_session_status(client, auth_headers, sid, {"awaiting_question"})
    questions = snap["pending_questions"]
    assert len(questions) == 1  # type: ignore[arg-type]
    q_id = questions[0]["request_id"]  # type: ignore[index]

    # Wrong answer key ("choice" instead of "q_0") MUST return 422 validation_error
    bad_key = client.post(
        f"/api/sessions/{sid}/questions/{q_id}",
        headers=auth_headers,
        json={"action": "accept", "answers": {"choice": "Blue"}},
    )
    assert bad_key.status_code == 422
    assert bad_key.json()["error"]["code"] == "validation_error"

    # Missing required key MUST return 422
    missing_key = client.post(
        f"/api/sessions/{sid}/questions/{q_id}",
        headers=auth_headers,
        json={"action": "accept", "answers": {}},
    )
    assert missing_key.status_code == 422

    # Valid q_0 answer resolves cleanly and passes exact key to ACP
    good = client.post(
        f"/api/sessions/{sid}/questions/{q_id}",
        headers=auth_headers,
        json={"action": "accept", "answers": {"q_0": "Blue"}},
    )
    assert good.status_code == 200
    assert good.json() == {"request_id": q_id, "resolved": True, "action": "accept"}

    _wait_for_session_status(client, auth_headers, sid, {"idle"})
    evs = client.get(f"/api/sessions/{sid}/events?after=0", headers=auth_headers).json()["events"]
    msg_texts = [e["payload"]["text"] for e in evs if e["type"] == "agent_message_chunk"]
    assert any('"q_0": "Blue"' in t for t in msg_texts)


def test_large_frame_and_sessions_merge_with_archived(
    client: TestClient,
    auth_headers: dict[str, str],
    sandbox: SandboxEnv,
) -> None:
    seed_summaries_db(
        sandbox.agy_cli_dir / "conversation_summaries.db",
        [
            {
                "conversation_id": "arch-uuid-1111",
                "title": "Archived Refactor",
                "preview": "Old summary snippet",
                "step_count": 7,
                "last_modified_time": "2026-11-20T12:00:00Z",
                "workspace_uris": json.dumps([f"file://{sandbox.workspace.resolve()}"]),
            }
        ],
    )

    sid = client.post(
        "/api/sessions",
        headers=auth_headers,
        json={"cwd": str(sandbox.workspace), "title": "Live Large Frame"},
    ).json()["session_id"]

    client.post(
        f"/api/sessions/{sid}/prompt",
        headers=auth_headers,
        json={"text": "[LARGE_FRAME]"},
    )
    _wait_for_session_status(client, auth_headers, sid, {"idle"})

    evs = client.get(f"/api/sessions/{sid}/events?after=0", headers=auth_headers).json()["events"]
    tool_updates = [e for e in evs if e["type"] == "tool_call_update"]
    assert len(tool_updates) == 1
    assert len(tool_updates[0]["payload"]["diff"]) > 130_000

    listed = client.get("/api/sessions?status=all", headers=auth_headers).json()
    ids = [item["session_id"] for item in listed["items"]]
    assert ids[0] == sid  # live sorted before archived
    assert "arch-uuid-1111" in ids
