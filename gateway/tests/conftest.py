"""Hermetic pytest fixtures isolating HOME, .gemini, and gateway config inside tmp_path."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import sqlite3
import stat
from typing import Any, Iterator

import pytest
from starlette.testclient import TestClient

from gravity_well_gateway.config import GatewayConfig, TOKEN_VAR_NAME
from gravity_well_gateway.main import create_app


@dataclass
class SandboxEnv:
    home: Path
    workspace: Path
    extra_dir: Path
    config_dir: Path
    gemini_dir: Path
    agy_cli_dir: Path
    fake_agy_bin: Path
    token: str


def seed_summaries_db(db_path: Path, rows: list[dict[str, Any]]) -> None:
    """Create a realistic conversation_summaries.db with FTS5 matching R9."""
    db_path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(str(db_path))
    try:
        conn.execute("PRAGMA journal_mode=WAL;")
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS conversation_summaries (
                conversation_id TEXT PRIMARY KEY,
                title TEXT,
                preview TEXT,
                step_count INTEGER,
                last_modified_time TEXT,
                workspace_uris TEXT,
                status INTEGER DEFAULT 2,
                source INTEGER DEFAULT 17,
                project_id TEXT DEFAULT 'default-cli-project',
                agent_name TEXT DEFAULT '',
                app_data_dir TEXT DEFAULT 'antigravity-cli'
            );
            """
        )
        conn.execute(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS conversations_fts USING fts5(
                conversation_id UNINDEXED,
                chunk_index UNINDEXED,
                last_modified_time UNINDEXED,
                step_count UNINDEXED,
                archived UNINDEXED,
                title,
                workspace,
                project,
                user_body,
                agent_body,
                tokenize='unicode61 remove_diacritics 2',
                prefix='1 2 3'
            );
            """
        )
        for row in rows:
            cid = str(row["conversation_id"])
            title = str(row.get("title") or "Archived Session")
            preview = str(row.get("preview") or "")
            steps = int(row.get("step_count") or 1)
            mtime = str(row.get("last_modified_time") or "2026-11-23T10:00:00Z")
            ws_uris = str(row.get("workspace_uris") or "[]")
            user_body = str(row.get("user_body") or preview)
            agent_body = str(row.get("agent_body") or "")
            ws_text = str(row.get("workspace") or "")

            conn.execute(
                """
                INSERT OR REPLACE INTO conversation_summaries
                    (conversation_id, title, preview, step_count, last_modified_time, workspace_uris)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                (cid, title, preview, steps, mtime, ws_uris),
            )
            conn.execute(
                """
                INSERT INTO conversations_fts
                    (conversation_id, chunk_index, last_modified_time, step_count, archived,
                     title, workspace, project, user_body, agent_body)
                VALUES (?, 0, ?, ?, 1, ?, ?, 'default', ?, ?)
                """,
                (cid, mtime, steps, title, ws_text, user_body, agent_body),
            )
        conn.commit()
    finally:
        conn.close()


@pytest.fixture
def sandbox(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> SandboxEnv:
    """Create a completely isolated HOME sandbox with executable FakeAgy shim."""
    home = tmp_path / "home"
    workspace = home / "projects" / "demo-app"
    extra_dir = home / "projects" / "shared-lib"
    config_dir = home / ".config" / "gravity-well"
    gemini_dir = home / ".gemini"
    agy_cli_dir = gemini_dir / "antigravity-cli"

    for d in (
        home,
        workspace,
        extra_dir,
        config_dir,
        agy_cli_dir / "brain",
        agy_cli_dir / "presence",
        home / ".hermes",
        home / ".ssh",
        home / ".gnupg",
        home / ".local" / "share" / "keyrings",
    ):
        d.mkdir(parents=True, exist_ok=True)

    fake_src = Path(__file__).parent / "fake_agy.py"
    fake_bin = tmp_path / "bin" / "fake_agy"
    fake_bin.parent.mkdir(parents=True, exist_ok=True)
    fake_bin.write_text(fake_src.read_text(encoding="utf-8"), encoding="utf-8")
    fake_bin.chmod(fake_bin.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)

    tok = "test-bearer-tok-0123456789abcdef"
    monkeypatch.setenv("HOME", str(home))
    monkeypatch.setenv("GRAVITY_WELL_CONFIG_DIR", str(config_dir))
    monkeypatch.setenv("GRAVITY_WELL_GEMINI_DIR", str(gemini_dir))
    monkeypatch.setenv("GRAVITY_WELL_AGY_CLI_DIR", str(agy_cli_dir))
    monkeypatch.setenv("GRAVITY_WELL_AGY_BIN", str(fake_bin))
    monkeypatch.setenv("GRAVITY_WELL_DEFAULT_CWD", str(workspace))
    monkeypatch.setenv("GRAVITY_WELL_ALLOWED_ROOTS", str(home))
    monkeypatch.setenv(TOKEN_VAR_NAME, tok)

    # Inject canary host secrets into parent env to prove child env scrub strips them
    monkeypatch.setenv("OPENAI_API_" + "KEY", "canary-openai-secret")
    monkeypatch.setenv("AWS_SECRET_ACCESS_" + "KEY", "canary-aws-secret")
    monkeypatch.setenv("GITHUB_" + "TOKEN", "canary-gh-secret")
    monkeypatch.setenv("MY_CUSTOM_" + "SECRET", "canary-custom-secret")
    monkeypatch.setenv("PYTHONPATH", "/tmp/poisoned-pythonpath")

    return SandboxEnv(
        home=home,
        workspace=workspace,
        extra_dir=extra_dir,
        config_dir=config_dir,
        gemini_dir=gemini_dir,
        agy_cli_dir=agy_cli_dir,
        fake_agy_bin=fake_bin,
        token=tok,
    )


@pytest.fixture
def client(sandbox: SandboxEnv) -> Iterator[TestClient]:
    cfg = GatewayConfig.from_env()
    app = create_app(config=cfg)
    with TestClient(app) as tc:
        yield tc


@pytest.fixture
def auth_headers(sandbox: SandboxEnv) -> dict[str, str]:
    return {"Authorization": f"Bearer {sandbox.token}"}
