"""Read-only SQLite FTS5 search, symlink-contained brain/ & @file browsing, and magic-byte uploads (D9)."""

from __future__ import annotations

import base64
from datetime import datetime, timezone
import hashlib
import json
import mimetypes
import os
from pathlib import Path, PurePosixPath
import re
import sqlite3
import tempfile
from typing import Any, Sequence
from urllib.parse import quote, unquote, urlparse
import secrets

from .config import (
    GatewayConfig,
    GatewayError,
    atomic_write_json,
    is_subpath,
    read_json_file,
    utc_now_iso,
    validate_session_id,
    validate_workspace_dir,
)
from .redaction import redact_text

MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024  # 10 MiB

SKIP_WORKSPACE_DIRS: frozenset[str] = frozenset(
    {
        ".git",
        ".venv",
        "node_modules",
        "build",
        "__pycache__",
        ".pytest_cache",
        ".mypy_cache",
        ".ruff_cache",
        ".gradle",
        ".kotlin",
    }
)

ALWAYS_REDACTED_ARTIFACT_EXTS: frozenset[str] = frozenset(
    {".log", ".txt", ".jsonl", ".json", ".ndjson"}
)

DISALLOWED_ATTACHMENT_EXTS: frozenset[str] = frozenset(
    {
        ".exe",
        ".dll",
        ".so",
        ".dylib",
        ".elf",
        ".bin",
        ".com",
        ".msi",
        ".apk",
        ".jar",
        ".class",
        ".pyc",
        ".pyo",
        ".wasm",
        ".zip",
        ".tar",
        ".gz",
        ".7z",
    }
)

BINARY_EXT_TO_MIME: dict[str, str] = {
    ".png": "image/png",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".gif": "image/gif",
    ".webp": "image/webp",
    ".pdf": "application/pdf",
}

TEXT_EXT_TO_MIME: dict[str, str] = {
    ".txt": "text/plain",
    ".md": "text/markdown",
    ".json": "application/json",
    ".jsonl": "application/x-ndjson",
    ".py": "text/x-python",
    ".kt": "text/x-kotlin",
    ".kts": "text/x-kotlin",
    ".js": "text/javascript",
    ".ts": "text/typescript",
    ".tsx": "text/typescript",
    ".html": "text/html",
    ".css": "text/css",
    ".xml": "application/xml",
    ".yaml": "application/yaml",
    ".yml": "application/yaml",
    ".toml": "application/toml",
    ".sh": "text/x-shellscript",
    ".sql": "application/sql",
    ".csv": "text/csv",
    ".log": "text/plain",
}


def _sniff_binary_magic(data: bytes) -> str | None:
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if data.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    if data.startswith((b"GIF87a", b"GIF89a")):
        return "image/gif"
    if len(data) >= 12 and data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "image/webp"
    if data.startswith(b"%PDF-"):
        return "application/pdf"
    return None


def _has_disallowed_binary_signature(data: bytes) -> bool:
    return data.startswith(
        (
            b"\x7fELF",
            b"MZ",
            b"\xfe\xed\xfa\xce",
            b"\xfe\xed\xfa\xcf",
            b"\xcf\xfa\xed\xfe",
            b"\xca\xfe\xba\xbe",
            b"PK\x03\x04",
            b"\x00asm",
            b"SQLite format 3\x00",
            b"\x1f\x8b",
        )
    )


def sniff_and_validate_attachment(
    data: bytes,
    filename: str,
    declared_content_type: str | None = None,
) -> str:
    """Verify magic bytes and UTF-8 validity per D9; raise 415 on mismatch or binary executable."""
    if not data:
        raise GatewayError(400, "bad_request", "Attachment file must not be empty.")
    if len(data) > MAX_ATTACHMENT_BYTES:
        raise GatewayError(413, "payload_too_large", "Attachment exceeds 10 MiB limit.")

    ext = Path(filename).suffix.lower()
    if ext in DISALLOWED_ATTACHMENT_EXTS or _has_disallowed_binary_signature(data):
        raise GatewayError(
            415,
            "unsupported_media_type",
            "Executable and archive binary attachments are not supported.",
        )

    declared_base = ""
    if declared_content_type:
        declared_base = declared_content_type.split(";", 1)[0].strip().lower()
        if declared_base == "image/jpg":
            declared_base = "image/jpeg"

    expected_binary_mime = BINARY_EXT_TO_MIME.get(ext)
    if expected_binary_mime is None and declared_base in BINARY_EXT_TO_MIME.values():
        expected_binary_mime = declared_base

    sniffed_binary = _sniff_binary_magic(data)

    if expected_binary_mime is not None:
        if sniffed_binary != expected_binary_mime:
            raise GatewayError(
                415,
                "unsupported_media_type",
                f"Attachment magic bytes do not match expected {expected_binary_mime}.",
            )
        if (
            declared_base
            and declared_base not in ("application/octet-stream", "multipart/form-data")
            and declared_base != sniffed_binary
        ):
            raise GatewayError(
                415,
                "unsupported_media_type",
                f"Declared Content-Type {declared_base} mismatches magic bytes {sniffed_binary}.",
            )
        assert sniffed_binary is not None
        return sniffed_binary

    if sniffed_binary is not None:
        if ext in TEXT_EXT_TO_MIME or (declared_base and declared_base.startswith("text/")):
            raise GatewayError(
                415,
                "unsupported_media_type",
                "Binary media content mismatches declared text file type.",
            )
        return sniffed_binary

    if b"\x00" in data:
        raise GatewayError(
            415,
            "unsupported_media_type",
            "Unsupported binary file format.",
        )
    try:
        data.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise GatewayError(
            415,
            "unsupported_media_type",
            "Text attachment must be valid UTF-8.",
        ) from exc

    return TEXT_EXT_TO_MIME.get(ext, "text/plain")


def build_fts5_match_query(raw_query: str) -> str:
    """Convert user search string into safe FTS5 prefix phrase tokens."""
    cleaned = raw_query.replace("\x00", " ").replace('"', " ")
    tokens = [tok.strip() for tok in cleaned.split() if tok.strip()]
    if not tokens:
        return ""
    return " AND ".join(f'"{tok}"*' for tok in tokens)


def _parse_workspace_uris(raw_uris: str | None) -> list[str]:
    if not raw_uris:
        return []
    try:
        arr = json.loads(raw_uris)
    except json.JSONDecodeError:
        return []
    if not isinstance(arr, list):
        return []
    paths: list[str] = []
    for item in arr:
        if not isinstance(item, str) or not item:
            continue
        if item.startswith("file://"):
            parsed = urlparse(item)
            p = unquote(parsed.path)
            if p:
                paths.append(p)
        else:
            paths.append(item)
    return paths


class StorageManager:
    """Handles `?mode=ro` SQLite FTS5 reads, brain/ artifacts, @file search, and attachments."""

    def __init__(self, config: GatewayConfig) -> None:
        self._config = config
        self._attachments_index: dict[tuple[str, str], dict[str, Any]] = {}

    @property
    def session_meta_path(self) -> Path:
        return self._config.config_dir / "session_meta.json"

    @property
    def summaries_db_path(self) -> Path:
        return self._config.agy_cli_dir / "conversation_summaries.db"

    def get_title_overrides(self) -> dict[str, dict[str, Any]]:
        data = read_json_file(self.session_meta_path, {})
        return data if isinstance(data, dict) else {}

    def get_session_meta(self, session_id: str) -> dict[str, Any] | None:
        overrides = self.get_title_overrides()
        entry = overrides.get(session_id)
        return entry if isinstance(entry, dict) else None

    def set_title_override(
        self,
        session_id: str,
        title: str,
        cwd: str | None = None,
    ) -> dict[str, Any]:
        validate_session_id(session_id)
        overrides = self.get_title_overrides()
        existing = overrides.get(session_id)
        entry: dict[str, Any] = dict(existing) if isinstance(existing, dict) else {}
        entry["title"] = title
        entry["updated_at"] = utc_now_iso()
        if cwd:
            entry["cwd"] = cwd
        overrides[session_id] = entry
        atomic_write_json(self.session_meta_path, overrides, mode=0o600)
        return entry

    def _open_summaries_ro(self) -> sqlite3.Connection | None:
        db_path = self.summaries_db_path
        if not db_path.is_file():
            return None
        uri = f"file:{quote(str(db_path))}?mode=ro"
        conn = sqlite3.connect(uri, uri=True, check_same_thread=False)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA query_only = ON;")
        return conn

    def get_archived_conversation(self, session_id: str) -> dict[str, Any] | None:
        """Fetch a single conversation summary row strictly read-only."""
        validate_session_id(session_id)
        overrides = self.get_title_overrides()
        meta = overrides.get(session_id) if isinstance(overrides.get(session_id), dict) else None

        conn = self._open_summaries_ro()
        if conn is None:
            return None
        try:
            tables = {
                r["name"]
                for r in conn.execute(
                    "SELECT name FROM sqlite_master WHERE type='table'"
                ).fetchall()
            }
            if "conversation_summaries" not in tables:
                return None
            row = conn.execute(
                """
                SELECT conversation_id, title, preview, step_count, last_modified_time, workspace_uris
                FROM conversation_summaries
                WHERE conversation_id = ?
                """,
                (session_id,),
            ).fetchone()
            if row is None:
                return None
            ws_paths = _parse_workspace_uris(row["workspace_uris"])
            custom_title = meta.get("title") if meta else None
            return {
                "conversation_id": row["conversation_id"],
                "title": custom_title or row["title"] or "Untitled Conversation",
                "title_source": "gateway" if custom_title else "acp",
                "preview": row["preview"] or "",
                "workspace_paths": ws_paths,
                "cwd": (meta.get("cwd") if meta and meta.get("cwd") else (ws_paths[0] if ws_paths else str(self._config.default_cwd))),
                "step_count": int(row["step_count"] or 0),
                "last_modified_time": row["last_modified_time"] or utc_now_iso(),
            }
        finally:
            conn.close()

    def search_conversations(
        self,
        q: str | None = None,
        cwd: str | None = None,
        limit: int = 50,
        offset: int = 0,
    ) -> dict[str, Any]:
        """Search conversation_summaries.db strictly read-only via FTS5 (`?mode=ro`)."""
        clamped_limit = max(1, min(limit, 200))
        clamped_offset = max(0, offset)
        cwd_real = os.path.realpath(os.path.expanduser(cwd)) if cwd else None

        conn = self._open_summaries_ro()
        if conn is None:
            return {"items": [], "total": 0, "limit": clamped_limit, "offset": clamped_offset}

        overrides = self.get_title_overrides()
        try:
            tables = {
                r["name"]
                for r in conn.execute(
                    "SELECT name FROM sqlite_master WHERE type='table'"
                ).fetchall()
            }
            if "conversation_summaries" not in tables:
                return {"items": [], "total": 0, "limit": clamped_limit, "offset": clamped_offset}

            has_fts = "conversations_fts" in tables
            fts_expr = build_fts5_match_query(q) if q and q.strip() else ""
            raw_rows: list[dict[str, Any]] = []
            seen_ids: set[str] = set()

            if fts_expr and has_fts:
                fts_hits = conn.execute(
                    """
                    SELECT
                        conversation_id,
                        CAST(archived AS INTEGER) AS archived_flag,
                        snippet(conversations_fts, -1, '[', ']', '...', 16) AS match_snippet,
                        bm25(conversations_fts) AS score
                    FROM conversations_fts
                    WHERE conversations_fts MATCH ?
                    ORDER BY score ASC
                    """,
                    (fts_expr,),
                ).fetchall()

                best_by_cid: dict[str, sqlite3.Row] = {}
                ordered_cids: list[str] = []
                for hit in fts_hits:
                    cid = str(hit["conversation_id"])
                    if cid not in best_by_cid:
                        best_by_cid[cid] = hit
                        ordered_cids.append(cid)

                if ordered_cids:
                    placeholders = ",".join("?" for _ in ordered_cids)
                    cs_rows = {
                        str(r["conversation_id"]): r
                        for r in conn.execute(
                            f"""
                            SELECT conversation_id, title, preview, step_count, last_modified_time, workspace_uris
                            FROM conversation_summaries
                            WHERE conversation_id IN ({placeholders})
                            """,
                            ordered_cids,
                        ).fetchall()
                    }
                    for cid in ordered_cids:
                        cs = cs_rows.get(cid)
                        if cs is None:
                            continue
                        hit = best_by_cid[cid]
                        seen_ids.add(cid)
                        raw_rows.append(
                            {
                                "conversation_id": cid,
                                "title": cs["title"],
                                "preview": cs["preview"],
                                "snippet": hit["match_snippet"] or cs["preview"] or "",
                                "workspace_uris": cs["workspace_uris"],
                                "step_count": cs["step_count"],
                                "last_modified_time": cs["last_modified_time"],
                                "archived": True if hit["archived_flag"] is None else bool(hit["archived_flag"]),
                            }
                        )

                # Also match gateway custom title overrides that may not appear in FTS5
                q_lower = q.strip().lower() if q else ""
                matching_override_ids = [
                    cid
                    for cid, meta in overrides.items()
                    if cid not in seen_ids
                    and isinstance(meta, dict)
                    and q_lower in str(meta.get("title", "")).lower()
                ]
                if matching_override_ids:
                    placeholders = ",".join("?" for _ in matching_override_ids)
                    extra_rows = conn.execute(
                        f"""
                        SELECT conversation_id, title, preview, step_count, last_modified_time, workspace_uris
                        FROM conversation_summaries
                        WHERE conversation_id IN ({placeholders})
                        ORDER BY last_modified_time DESC
                        """,
                        matching_override_ids,
                    ).fetchall()
                    for r in extra_rows:
                        raw_rows.append(
                            {
                                "conversation_id": r["conversation_id"],
                                "title": r["title"],
                                "preview": r["preview"],
                                "snippet": r["preview"] or "",
                                "workspace_uris": r["workspace_uris"],
                                "step_count": r["step_count"],
                                "last_modified_time": r["last_modified_time"],
                                "archived": True,
                            }
                        )
            elif q and q.strip():
                like_pat = f"%{q.strip()}%"
                rows = conn.execute(
                    """
                    SELECT conversation_id, title, preview, step_count, last_modified_time, workspace_uris
                    FROM conversation_summaries
                    WHERE title LIKE ? OR preview LIKE ?
                    ORDER BY last_modified_time DESC
                    """,
                    (like_pat, like_pat),
                ).fetchall()
                for r in rows:
                    raw_rows.append(
                        {
                            "conversation_id": r["conversation_id"],
                            "title": r["title"],
                            "preview": r["preview"],
                            "snippet": r["preview"] or "",
                            "workspace_uris": r["workspace_uris"],
                            "step_count": r["step_count"],
                            "last_modified_time": r["last_modified_time"],
                            "archived": True,
                        }
                    )
            else:
                rows = conn.execute(
                    """
                    SELECT conversation_id, title, preview, step_count, last_modified_time, workspace_uris
                    FROM conversation_summaries
                    ORDER BY last_modified_time DESC
                    """
                ).fetchall()
                for r in rows:
                    raw_rows.append(
                        {
                            "conversation_id": r["conversation_id"],
                            "title": r["title"],
                            "preview": r["preview"],
                            "snippet": r["preview"] or "",
                            "workspace_uris": r["workspace_uris"],
                            "step_count": r["step_count"],
                            "last_modified_time": r["last_modified_time"],
                            "archived": True,
                        }
                    )

            items: list[dict[str, Any]] = []
            for r in raw_rows:
                cid = r["conversation_id"]
                ws_paths = _parse_workspace_uris(r["workspace_uris"])
                if cwd_real is not None:
                    norm_ws = [os.path.realpath(p) for p in ws_paths]
                    if cwd_real not in norm_ws:
                        continue
                meta = overrides.get(cid)
                custom_title = meta.get("title") if isinstance(meta, dict) else None
                items.append(
                    {
                        "conversation_id": cid,
                        "title": custom_title or r["title"] or "Untitled Conversation",
                        "title_source": "gateway" if custom_title else "acp",
                        "preview": r["preview"] or "",
                        "snippet": r["snippet"] or "",
                        "workspace_paths": ws_paths,
                        "step_count": int(r["step_count"] or 0),
                        "last_modified_time": r["last_modified_time"] or "",
                        "archived": bool(r["archived"]),
                    }
                )

            total = len(items)
            paged = items[clamped_offset : clamped_offset + clamped_limit]
            return {
                "items": paged,
                "total": total,
                "limit": clamped_limit,
                "offset": clamped_offset,
            }
        finally:
            conn.close()

    def search_workspace_files(
        self,
        roots: Sequence[str],
        q: str = "",
        limit: int = 30,
    ) -> list[dict[str, Any]]:
        """Autocomplete workspace files across validated roots, blocking symlink escapes."""
        clamped_limit = max(1, min(limit, 100))
        q_lower = q.strip().lower()
        validated_roots = [validate_workspace_dir(r, self._config) for r in roots]

        candidates: list[tuple[int, str, dict[str, Any]]] = []
        seen_real: set[str] = set()

        for root_real in validated_roots:
            for dirpath, dirnames, filenames in os.walk(root_real, followlinks=False):
                kept_dirs: list[str] = []
                for d in sorted(dirnames):
                    if d in SKIP_WORKSPACE_DIRS:
                        continue
                    full_dir = os.path.join(dirpath, d)
                    if os.path.islink(full_dir):
                        continue
                    real_dir = os.path.realpath(full_dir)
                    if not is_subpath(real_dir, root_real):
                        continue
                    if any(is_subpath(real_dir, denied) for denied in self._config.denied_dirs):
                        continue
                    kept_dirs.append(d)
                dirnames[:] = kept_dirs

                for fname in sorted(filenames):
                    full_file = os.path.join(dirpath, fname)
                    real_file = os.path.realpath(full_file)
                    if not is_subpath(real_file, root_real):
                        continue
                    if any(is_subpath(real_file, denied) for denied in self._config.denied_dirs):
                        continue
                    if not os.path.isfile(real_file) or real_file in seen_real:
                        continue

                    rel_path = os.path.relpath(full_file, root_real).replace(os.sep, "/")
                    rel_lower = rel_path.lower()
                    fname_lower = fname.lower()
                    if q_lower and q_lower not in rel_lower:
                        continue

                    if not q_lower:
                        rank = 2
                    elif fname_lower == q_lower or rel_lower == q_lower:
                        rank = 0
                    elif fname_lower.startswith(q_lower) or rel_lower.startswith(q_lower):
                        rank = 1
                    else:
                        rank = 2

                    try:
                        st = os.stat(real_file)
                    except OSError:
                        continue

                    seen_real.add(real_file)
                    candidates.append(
                        (
                            rank,
                            rel_path,
                            {
                                "path": rel_path,
                                "root": root_real,
                                "size_bytes": st.st_size,
                            },
                        )
                    )
                    if not q_lower and len(candidates) >= clamped_limit:
                        break
                if not q_lower and len(candidates) >= clamped_limit:
                    break

        candidates.sort(key=lambda item: (item[0], len(item[1]), item[1]))
        return [c[2] for c in candidates[:clamped_limit]]

    def _resolve_session_brain_dir(self, session_id: str) -> tuple[str, str]:
        validate_session_id(session_id)
        brain_base_path = self._config.agy_cli_dir / "brain"
        real_brain_base = os.path.realpath(brain_base_path)
        candidate_dir = brain_base_path / session_id
        if os.path.islink(candidate_dir):
            raise GatewayError(
                403,
                "forbidden",
                "Symlinked session brain directory is forbidden.",
            )
        real_session_dir = os.path.realpath(candidate_dir)
        if not is_subpath(real_session_dir, real_brain_base) or real_session_dir == real_brain_base:
            raise GatewayError(
                403,
                "forbidden",
                "Session brain directory escapes brain root.",
            )
        return real_brain_base, real_session_dir

    def list_brain_artifacts(self, session_id: str) -> list[dict[str, Any]] | None:
        """List files inside `~/.gemini/antigravity-cli/brain/{session_id}/` with symlink containment."""
        _, real_session_dir = self._resolve_session_brain_dir(session_id)
        if not os.path.isdir(real_session_dir):
            return None

        items: list[dict[str, Any]] = []
        for dirpath, dirnames, filenames in os.walk(real_session_dir, followlinks=False):
            safe_dirs: list[str] = []
            for d in sorted(dirnames):
                full_d = os.path.join(dirpath, d)
                if os.path.islink(full_d):
                    continue
                real_d = os.path.realpath(full_d)
                if is_subpath(real_d, real_session_dir):
                    safe_dirs.append(d)
            dirnames[:] = safe_dirs

            for fname in sorted(filenames):
                full_f = os.path.join(dirpath, fname)
                real_f = os.path.realpath(full_f)
                if not is_subpath(real_f, real_session_dir) or real_f == real_session_dir:
                    continue
                if not os.path.isfile(real_f):
                    continue
                try:
                    st = os.stat(real_f)
                except OSError:
                    continue
                rel_path = os.path.relpath(full_f, real_session_dir).replace(os.sep, "/")
                ext = Path(fname).suffix.lower()
                mime = (
                    TEXT_EXT_TO_MIME.get(ext)
                    or BINARY_EXT_TO_MIME.get(ext)
                    or mimetypes.guess_type(fname)[0]
                    or "application/octet-stream"
                )
                mod_iso = datetime.fromtimestamp(st.st_mtime, tz=timezone.utc).strftime(
                    "%Y-%m-%dT%H:%M:%SZ"
                )
                items.append(
                    {
                        "path": rel_path,
                        "size_bytes": st.st_size,
                        "mime_type": mime,
                        "modified_at": mod_iso,
                    }
                )
        items.sort(key=lambda x: x["path"])
        return items

    def read_brain_artifact(
        self,
        session_id: str,
        artifact_path: str,
        *,
        raw: bool = False,
    ) -> tuple[bytes, str]:
        """Serve a single file from brain/{session_id}/, enforcing realpath containment & secret redaction."""
        _, real_session_dir = self._resolve_session_brain_dir(session_id)

        if (
            not artifact_path
            or "\x00" in artifact_path
            or "\\" in artifact_path
            or artifact_path.startswith("/")
        ):
            raise GatewayError(403, "forbidden", "Invalid artifact path.")

        posix_parts = PurePosixPath(artifact_path).parts
        if not posix_parts or any(part in ("..", ".") for part in posix_parts):
            raise GatewayError(403, "forbidden", "Path traversal in artifact_path is forbidden.")

        candidate_target = os.path.join(real_session_dir, *posix_parts)
        real_target = os.path.realpath(candidate_target)
        if not is_subpath(real_target, real_session_dir) or real_target == real_session_dir:
            raise GatewayError(
                403,
                "forbidden",
                "Artifact path escapes session brain directory.",
            )

        if not os.path.isdir(real_session_dir) or not os.path.isfile(real_target):
            raise GatewayError(404, "not_found", "Artifact not found.")

        data = Path(real_target).read_bytes()
        ext = Path(real_target).suffix.lower()
        sniffed_binary = _sniff_binary_magic(data)
        if (
            sniffed_binary is not None
            and ext not in ALWAYS_REDACTED_ARTIFACT_EXTS
            and ext not in TEXT_EXT_TO_MIME
        ):
            return data, sniffed_binary

        mime = (
            TEXT_EXT_TO_MIME.get(ext)
            or BINARY_EXT_TO_MIME.get(ext)
            or mimetypes.guess_type(real_target)[0]
            or "application/octet-stream"
        )

        is_known_text = (
            ext in ALWAYS_REDACTED_ARTIFACT_EXTS
            or ext in TEXT_EXT_TO_MIME
            or mime.startswith("text/")
            or mime in (
                "application/json",
                "application/x-ndjson",
                "application/yaml",
                "application/x-yaml",
                "application/toml",
                "application/xml",
                "application/sql",
            )
        )
        if is_known_text:
            text = data.decode("utf-8", errors="replace")
            redacted = redact_text(text)
            out_mime = f"{mime}; charset=utf-8" if "charset=" not in mime else mime
            return redacted.encode("utf-8"), out_mime

        if b"\x00" not in data:
            try:
                decoded = data.decode("utf-8")
            except UnicodeDecodeError:
                pass
            else:
                redacted = redact_text(decoded)
                base_mime = (
                    "text/plain"
                    if mime in ("application/octet-stream", *BINARY_EXT_TO_MIME.values())
                    else mime
                )
                out_mime = f"{base_mime}; charset=utf-8" if "charset=" not in base_mime else base_mime
                return redacted.encode("utf-8"), out_mime

        if raw:
            return data, mime
        return data, mime

    def save_attachment(
        self,
        session_id: str,
        filename: str,
        declared_content_type: str | None,
        data: bytes,
    ) -> dict[str, Any]:
        """Validate magic bytes + size, store under uploads/{session_id}/, and return metadata."""
        validate_session_id(session_id)
        sniffed_mime = sniff_and_validate_attachment(data, filename, declared_content_type)

        base_name = os.path.basename(filename or "attachment")
        sanitized_name = re.sub(r"[^A-Za-z0-9._-]", "_", base_name).strip("._")[:80] or "attachment"
        attachment_id = f"att_{secrets.token_hex(8)}"
        sha256_hex = hashlib.sha256(data).hexdigest()

        session_upload_dir = self._config.config_dir / "uploads" / session_id
        session_upload_dir.mkdir(parents=True, exist_ok=True)
        try:
            os.chmod(session_upload_dir, 0o700)
        except OSError:
            pass

        dest_path = session_upload_dir / f"{attachment_id}_{sanitized_name}"
        fd, tmp_path = tempfile.mkstemp(prefix=f".{attachment_id}.", dir=str(session_upload_dir))
        try:
            os.chmod(fd, 0o600)
            os.write(fd, data)
            os.fsync(fd)
            os.close(fd)
            fd = -1
            os.replace(tmp_path, dest_path)
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

        meta = {
            "attachment_id": attachment_id,
            "session_id": session_id,
            "filename": sanitized_name,
            "mime_type": sniffed_mime,
            "size_bytes": len(data),
            "sha256": sha256_hex,
            "file_path": str(dest_path),
            "created_at": utc_now_iso(),
        }
        atomic_write_json(session_upload_dir / f"{attachment_id}.meta.json", meta, mode=0o600)
        self._attachments_index[(session_id, attachment_id)] = meta
        return {
            "attachment_id": attachment_id,
            "filename": sanitized_name,
            "mime_type": sniffed_mime,
            "size_bytes": len(data),
            "sha256": sha256_hex,
        }

    def resolve_attachments(
        self,
        session_id: str,
        attachment_ids: Sequence[str],
    ) -> list[dict[str, Any]]:
        """Resolve uploaded attachment_ids into ACP prompt content blocks."""
        validate_session_id(session_id)
        blocks: list[dict[str, Any]] = []
        session_upload_dir = self._config.config_dir / "uploads" / session_id

        for att_id in attachment_ids:
            if not re.match(r"^att_[0-9a-f]{16}$", att_id):
                raise GatewayError(404, "not_found", f"Unknown attachment: {att_id}")
            meta = self._attachments_index.get((session_id, att_id))
            if meta is None:
                meta_file = session_upload_dir / f"{att_id}.meta.json"
                loaded = read_json_file(meta_file, None)
                if isinstance(loaded, dict) and loaded.get("session_id") == session_id:
                    meta = loaded
                    self._attachments_index[(session_id, att_id)] = meta
            if meta is None:
                raise GatewayError(404, "not_found", f"Unknown attachment: {att_id}")

            fpath = Path(str(meta["file_path"]))
            if not fpath.is_file():
                raise GatewayError(404, "not_found", f"Attachment payload missing: {att_id}")
            raw_bytes = fpath.read_bytes()
            mime = str(meta["mime_type"])
            if mime.startswith("image/"):
                blocks.append(
                    {
                        "type": "image",
                        "mimeType": mime,
                        "data": base64.b64encode(raw_bytes).decode("ascii"),
                    }
                )
            elif mime == "application/pdf":
                blocks.append(
                    {
                        "type": "resource",
                        "resource": {
                            "uri": f"file://{fpath}",
                            "mimeType": mime,
                            "blob": base64.b64encode(raw_bytes).decode("ascii"),
                        },
                    }
                )
            else:
                blocks.append(
                    {
                        "type": "resource",
                        "resource": {
                            "uri": f"file://{fpath}",
                            "mimeType": mime,
                            "text": raw_bytes.decode("utf-8", errors="replace"),
                        },
                    }
                )
        return blocks
