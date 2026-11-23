"""Secret redaction utilities for logs, crash dumps, CLI output, and artifacts."""

from __future__ import annotations

import re
from typing import Iterable

REDACTED = "[REDACTED]"

_REDACT_PATTERNS: tuple[tuple[re.Pattern[str], str], ...] = (
    # HTTP Authorization headers (Bearer / Basic / Token)
    (
        re.compile(r"(?i)(authorization\s*:\s*(?:bearer|basic|token)\s+)[^\s\"',;]+"),
        rf"\1{REDACTED}",
    ),
    # Standalone Bearer tokens
    (
        re.compile(r"(?i)\bbearer\s+[A-Za-z0-9._~+/=-]{8,}"),
        f"Bearer {REDACTED}",
    ),
    # Google API keys, Google OAuth access/refresh tokens, OpenAI/Anthropic sk-*, GitHub, WS tickets
    (re.compile(r"\bAIza[0-9A-Za-z_-]{20,}\b"), REDACTED),
    (re.compile(r"\bya29\.[0-9A-Za-z._-]+\b"), REDACTED),
    (re.compile(r"(?<![A-Za-z0-9:/])1//[0-9A-Za-z_-]{20,}\b"), REDACTED),
    (re.compile(r"\bsk-(?:ant-|proj-)?[0-9A-Za-z_-]{16,}\b"), REDACTED),
    (re.compile(r"\bgh[pous]_[0-9A-Za-z_]{16,}\b"), REDACTED),
    (re.compile(r"\bgithub_pat_[0-9A-Za-z_]{16,}\b"), REDACTED),
    (re.compile(r"\bgwt_[0-9A-Za-z_-]{16,}\b"), REDACTED),
    # Query-string sensitive parameters in logged URLs
    (
        re.compile(
            r"(?i)([?&](?:token|bearer|auth|access_token|api_key|client_secret|refresh_token|ticket)=)([^&\s\"']+)"
        ),
        rf"\1{REDACTED}",
    ),
    # JSON or repr dict fields holding tokens or credentials
    (
        re.compile(
            r"""(?i)(["'](?:client_secret|refresh_token|access_token|id_token|api_key|bearer_token|private_key|password|authorization)["']\s*:\s*["'])[^"']*(["'])"""
        ),
        rf"\1{REDACTED}\2",
    ),
    # Quoted KEY=VALUE / TOKEN=VALUE / SECRET=VALUE / PASSWORD=VALUE
    (
        re.compile(
            r'(?i)\b([A-Za-z0-9_]*(?:KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL)[A-Za-z0-9_]*)\s*=\s*"[^"]*"'
        ),
        rf'\1="{REDACTED}"',
    ),
    (
        re.compile(
            r"(?i)\b([A-Za-z0-9_]*(?:KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL)[A-Za-z0-9_]*)\s*=\s*'[^']*'"
        ),
        rf"\1='{REDACTED}'",
    ),
    # Unquoted KEY=VALUE / TOKEN=VALUE / SECRET=VALUE / PASSWORD=VALUE
    (
        re.compile(
            r"(?i)\b([A-Za-z0-9_]*(?:KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL)[A-Za-z0-9_]*)\s*=\s*([^\s\"',;]+)"
        ),
        rf"\1={REDACTED}",
    ),
)


def redact_text(text: str) -> str:
    """Strip credentials, OAuth tokens, API keys, and secret env values from text."""
    if not text:
        return text
    out = text
    for pattern, repl in _REDACT_PATTERNS:
        out = pattern.sub(repl, out)
    return out


def redact_lines(lines: Iterable[str]) -> list[str]:
    """Redact each line in an iterable of log lines."""
    return [redact_text(line) for line in lines]
