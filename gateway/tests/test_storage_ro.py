"""Tests for `?mode=ro` SQLite FTS5 search, brain/ & @file symlink containment, and magic-byte uploads."""

from __future__ import annotations

import json
from pathlib import Path
import sqlite3

import pytest
from starlette.testclient import TestClient

from conftest import SandboxEnv, seed_summaries_db

PNG_1X1 = (
    b"\x89PNG\r\n\x1a\n"
    b"\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08\x06\x00\x00\x00\x1f\x15\xc4\x89"
    b"\x00\x00\x00\nIDATx\x9cc\x00\x01\x00\x00\x05\x00\x01\r\n-\xb4"
    b"\x00\x00\x00\x00IEND\xaeB`\x82"
)


def test_storage_fts5_search_is_read_only_and_merges_title_overrides(
    client: TestClient,
    auth_headers: dict[str, str],
    sandbox: SandboxEnv,
) -> None:
    db_path = sandbox.agy_cli_dir / "conversation_summaries.db"
    seed_summaries_db(
        db_path,
        [
            {
                "conversation_id": "conv-alpha-01",
                "title": "Auth Token Architecture",
                "preview": "Implemented hmac.compare_digest in gateway/auth.py",
                "user_body": "Check gateway/auth.py constant-time bearer verification",
                "agent_body": "Verified 30s single-use WS tickets",
                "step_count": 14,
                "last_modified_time": "2026-11-23T11:00:00Z",
                "workspace_uris": json.dumps([f"file://{sandbox.workspace.resolve()}"]),
            },
            {
                "conversation_id": "conv-beta-02",
                "title": "Compose Vernier Theme",
                "preview": "Typography and color tokens",
                "user_body": "Design Android Compose dark palette",
                "agent_body": "Created Vernier Rig tokens",
                "step_count": 6,
                "last_modified_time": "2026-11-22T09:00:00Z",
                "workspace_uris": json.dumps([f"file://{sandbox.extra_dir.resolve()}"]),
            },
        ],
    )

    # Make DB read-only on filesystem (0444) to prove ?mode=ro never attempts write locks
    db_path.chmod(0o444)
    try:
        # FTS5 query with slash & hyphen special chars must not fail syntax parsing
        r1 = client.get(
            "/api/storage/conversations",
            params={"q": "gateway/auth.py"},
            headers=auth_headers,
        )
        assert r1.status_code == 200
        data1 = r1.json()
        assert data1["total"] == 1
        assert data1["items"][0]["conversation_id"] == "conv-alpha-01"
        assert sandbox.workspace.resolve().as_posix() in data1["items"][0]["workspace_paths"]

        # Workspace cwd filter
        r2 = client.get(
            "/api/storage/conversations",
            params={"cwd": str(sandbox.extra_dir)},
            headers=auth_headers,
        )
        assert r2.status_code == 200
        assert [x["conversation_id"] for x in r2.json()["items"]] == ["conv-beta-02"]

        # Custom gateway title override merges cleanly into storage results
        ren = client.patch(
            "/api/sessions/conv-beta-02",
            headers=auth_headers,
            json={"title": "Custom Vernier Override"},
        )
        assert ren.status_code == 200
        r3 = client.get(
            "/api/storage/conversations",
            params={"q": "Vernier Override"},
            headers=auth_headers,
        )
        assert r3.status_code == 200
        assert r3.json()["items"][0]["title"] == "Custom Vernier Override"

        # Verify query_only enforcement directly on StorageManager connection helper
        ro_conn = client.app.state.storage._open_summaries_ro()  # type: ignore[attr-defined]
        assert ro_conn is not None
        try:
            with pytest.raises(sqlite3.OperationalError):
                ro_conn.execute("DELETE FROM conversation_summaries")
        finally:
            ro_conn.close()
    finally:
        db_path.chmod(0o644)


def test_workspace_file_search_skips_ignored_dirs_and_blocks_symlink_escape(
    client: TestClient,
    auth_headers: dict[str, str],
    sandbox: SandboxEnv,
    tmp_path: Path,
) -> None:
    (sandbox.workspace / "src").mkdir(parents=True, exist_ok=True)
    (sandbox.workspace / "src" / "router.py").write_text("print('ok')\n", encoding="utf-8")
    (sandbox.workspace / ".git").mkdir(exist_ok=True)
    (sandbox.workspace / ".git" / "config").write_text("[core]\n", encoding="utf-8")
    (sandbox.workspace / "node_modules" / "pkg").mkdir(parents=True, exist_ok=True)
    (sandbox.workspace / "node_modules" / "pkg" / "index.js").write_text("", encoding="utf-8")

    secret_outside = tmp_path / "outside_secret.txt"
    secret_outside.write_text("TOP_SECRET", encoding="utf-8")
    (sandbox.workspace / "src" / "evil_escape.py").symlink_to(secret_outside)

    sid = client.post(
        "/api/sessions",
        headers=auth_headers,
        json={"cwd": str(sandbox.workspace)},
    ).json()["session_id"]

    res = client.get(f"/api/sessions/{sid}/files?q=", headers=auth_headers)
    assert res.status_code == 200
    paths = [item["path"] for item in res.json()["items"]]
    assert "src/router.py" in paths
    assert "src/evil_escape.py" not in paths
    assert not any(p.startswith(".git") or p.startswith("node_modules") for p in paths)


def test_brain_artifacts_symlink_containment_traversal_block_and_secret_redaction(
    client: TestClient,
    auth_headers: dict[str, str],
    sandbox: SandboxEnv,
    tmp_path: Path,
) -> None:
    sid = client.post(
        "/api/sessions",
        headers=auth_headers,
        json={"cwd": str(sandbox.workspace)},
    ).json()["session_id"]

    brain_dir = sandbox.agy_cli_dir / "brain" / sid
    logs_dir = brain_dir / ".system_generated" / "logs"
    logs_dir.mkdir(parents=True, exist_ok=True)

    # Plant secret tokens in log artifact to test mandatory redaction (even with ?raw=1)
    fake_bearer = "supersecret" + "bearer123456"
    fake_ai_key = "AIza" + "SyB1234567890abcdefghijklmnopqr"
    fake_oauth = "ya29." + "a0AfH6SMBx1234567890secretvalue"
    fake_rt = "rt_val_" + "999888"
    rt_field = "refresh_" + "token"
    log_file = logs_dir / "transcript.jsonl"
    log_file.write_text(
        json.dumps(
            {
                "header": "Authorization: Bearer " + fake_bearer,
                "gkey": fake_ai_key,
                "oauth": fake_oauth,
                rt_field: fake_rt,
            }
        )
        + "\n",
        encoding="utf-8",
    )

    img_file = brain_dir / "chart.png"
    img_file.write_bytes(PNG_1X1)

    outside_file = tmp_path / "host_passwd.txt"
    outside_file.write_text("root:x:0:0", encoding="utf-8")
    (brain_dir / "escape_link.txt").symlink_to(outside_file)

    # Listing omits escaped symlinks
    list_r = client.get(f"/api/sessions/{sid}/artifacts", headers=auth_headers)
    assert list_r.status_code == 200
    listed_paths = [x["path"] for x in list_r.json()["items"]]
    assert ".system_generated/logs/transcript.jsonl" in listed_paths
    assert "chart.png" in listed_paths
    assert "escape_link.txt" not in listed_paths

    # Serving log artifact strips secrets even when ?raw=1 is requested
    art_r = client.get(
        f"/api/sessions/{sid}/artifacts/.system_generated/logs/transcript.jsonl?raw=1",
        headers=auth_headers,
    )
    assert art_r.status_code == 200
    assert art_r.headers.get("x-content-type-options") == "nosniff"
    body_text = art_r.text
    assert fake_bearer not in body_text
    assert fake_ai_key not in body_text
    assert fake_oauth not in body_text
    assert fake_rt not in body_text
    assert "[REDACTED]" in body_text

    # Serving PNG artifact returns exact binary bytes
    png_r = client.get(f"/api/sessions/{sid}/artifacts/chart.png?raw=1", headers=auth_headers)
    assert png_r.status_code == 200
    assert png_r.content == PNG_1X1

    # Symlink escape and path traversal attempts MUST 403 forbidden
    esc_r = client.get(f"/api/sessions/{sid}/artifacts/escape_link.txt", headers=auth_headers)
    assert esc_r.status_code == 403
    assert esc_r.json()["error"]["code"] == "forbidden"

    # Raw ASGI path with `..` segments must also 403
    trav_r = client.get(
        f"/api/sessions/{sid}/artifacts/.system_generated/%2e%2e/%2e%2e/outside",
        headers=auth_headers,
    )
    assert trav_r.status_code == 403
    assert trav_r.json()["error"]["code"] == "forbidden"


def test_brain_artifacts_redact_yaml_toml_xml_sql_and_unknown_utf8_while_preserving_binaries(
    client: TestClient,
    auth_headers: dict[str, str],
    sandbox: SandboxEnv,
) -> None:
    sid = client.post(
        "/api/sessions",
        headers=auth_headers,
        json={"cwd": str(sandbox.workspace)},
    ).json()["session_id"]

    brain_dir = sandbox.agy_cli_dir / "brain" / sid
    brain_dir.mkdir(parents=True, exist_ok=True)

    secret_ai = "AIza" + "SyC9876543210abcdefghijklmnopqr"
    secret_oauth = "ya29." + "a0AfH6SMBxArtifactSecretToken123"
    secret_env = "raw_artifact_secret_val_555"

    text_Payloads = {
        "config.yaml": f"api_key: {secret_ai}\noauth: {secret_oauth}\n",
        "settings.toml": f'SERVICE_SECRET = "{secret_env}"\ntoken = "{secret_ai}"\n',
        "manifest.xml": f"<config><header>Authorization: Bearer {secret_oauth}</header></config>\n",
        "dump.sql": f"INSERT INTO creds VALUES ('{secret_ai}', 'API_TOKEN={secret_env}');\n",
        "NOTES": f"Unextensioned UTF-8 note with Bearer {secret_oauth} and {secret_ai}\n",
        "trace.customext": f"CUSTOM_SECRET={secret_env}\n",
    }
    for rel_name, content in text_Payloads.items():
        (brain_dir / rel_name).write_text(content, encoding="utf-8")

    pdf_bytes = b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n1 0 obj\n<< /Type /Catalog >>\nendobj\n%%EOF\n"
    (brain_dir / "report.pdf").write_bytes(pdf_bytes)
    (brain_dir / "pixel.png").write_bytes(PNG_1X1)

    for rel_name in text_Payloads:
        for suffix in ("", "?raw=1"):
            r = client.get(f"/api/sessions/{sid}/artifacts/{rel_name}{suffix}", headers=auth_headers)
            assert r.status_code == 200, f"Failed serving {rel_name}{suffix}"
            assert r.headers.get("x-content-type-options") == "nosniff"
            assert secret_ai not in r.text, f"Leaked key in {rel_name}{suffix}"
            assert secret_oauth not in r.text, f"Leaked oauth in {rel_name}{suffix}"
            assert secret_env not in r.text, f"Leaked env secret in {rel_name}{suffix}"
            assert "[REDACTED]" in r.text, f"Missing redaction marker in {rel_name}{suffix}"

    r_pdf = client.get(f"/api/sessions/{sid}/artifacts/report.pdf?raw=1", headers=auth_headers)
    assert r_pdf.status_code == 200
    assert r_pdf.content == pdf_bytes

    r_png = client.get(f"/api/sessions/{sid}/artifacts/pixel.png?raw=1", headers=auth_headers)
    assert r_png.status_code == 200
    assert r_png.content == PNG_1X1


def test_attachments_magic_bytes_size_cap_and_prompt_forwarding(
    client: TestClient,
    auth_headers: dict[str, str],
    sandbox: SandboxEnv,
) -> None:
    sid = client.post(
        "/api/sessions",
        headers=auth_headers,
        json={"cwd": str(sandbox.workspace)},
    ).json()["session_id"]

    # 1. Valid PNG upload -> 201 Created
    up_png = client.post(
        f"/api/sessions/{sid}/attachments",
        headers=auth_headers,
        files={"file": ("screen.png", PNG_1X1, "image/png")},
    )
    assert up_png.status_code == 201
    att = up_png.json()
    assert att["mime_type"] == "image/png"
    assert att["size_bytes"] == len(PNG_1X1)
    att_id = att["attachment_id"]

    # 2. Valid UTF-8 code file -> 201 Created
    up_py = client.post(
        f"/api/sessions/{sid}/attachments",
        headers=auth_headers,
        files={"file": ("snippet.py", b"def add(a, b):\n    return a + b\n", "text/x-python")},
    )
    assert up_py.status_code == 201
    assert up_py.json()["mime_type"] == "text/x-python"

    # 3. Magic-byte mismatch (plain text disguised as .png) -> 415 unsupported_media_type
    bad_png = client.post(
        f"/api/sessions/{sid}/attachments",
        headers=auth_headers,
        files={"file": ("spoof.png", b"this is not png magic bytes", "image/png")},
    )
    assert bad_png.status_code == 415
    assert bad_png.json()["error"]["code"] == "unsupported_media_type"

    # 4. ELF binary executable -> 415 unsupported_media_type
    bad_elf = client.post(
        f"/api/sessions/{sid}/attachments",
        headers=auth_headers,
        files={"file": ("payload.bin", b"\x7fELF\x02\x01\x01\x00binary", "application/octet-stream")},
    )
    assert bad_elf.status_code == 415
    assert bad_elf.json()["error"]["code"] == "unsupported_media_type"

    # 5. Oversized > 10 MiB upload -> 413 payload_too_large
    oversized = b"a" * (10 * 1024 * 1024 + 64)
    too_big = client.post(
        f"/api/sessions/{sid}/attachments",
        headers=auth_headers,
        files={"file": ("huge.txt", oversized, "text/plain")},
    )
    assert too_big.status_code == 413
    assert too_big.json()["error"]["code"] == "payload_too_large"

    # 6. Submitting prompt with attachment_ids forwards ACP image block to FakeAgy
    pr = client.post(
        f"/api/sessions/{sid}/prompt",
        headers=auth_headers,
        json={"text": "Inspect screenshot", "attachment_ids": [att_id]},
    )
    assert pr.status_code == 202

    # Wait for turn completion and confirm FakeAgy saw image block (`[WITH_IMAGE]`)
    import time

    deadline = time.monotonic() + 5.0
    while time.monotonic() < deadline:
        if client.get(f"/api/sessions/{sid}", headers=auth_headers).json()["status"] == "idle":
            break
        time.sleep(0.03)

    evs = client.get(f"/api/sessions/{sid}/events?after=0", headers=auth_headers).json()["events"]
    msgs = [e["payload"]["text"] for e in evs if e["type"] == "agent_message_chunk"]
    assert any("[WITH_IMAGE]" in m for m in msgs)
