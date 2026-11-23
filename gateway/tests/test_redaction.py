"""Tests for secret redaction across logs, headers, tokens, JSON fields, and env values."""

from __future__ import annotations

from gravity_well_gateway.config import redact_secrets
from gravity_well_gateway.redaction import REDACTED, redact_lines, redact_text


def test_redact_text_strips_tokens_headers_keys_and_env_secrets() -> None:
    fake_bearer = "bearer_tok_" + "xyz1234567890"
    fake_aiza = "AIza" + "SyD1234567890abcdefghijklmnopqr"
    fake_ya29 = "ya29." + "a0AfH6SMBx9999999999secret"
    fake_refresh = "1//" + "04abcdefGHIJK1234567890xyz"
    fake_sk = "sk-ant-" + "api03-1234567890abcdefghij"
    fake_ghp = "ghp_" + "1234567890abcdefghijklmnop"
    fake_gwt = "gwt_" + "0123456789abcdef0123"
    fake_aws = "aws_sec_" + "9876543210"
    fake_cs = "cs_val_" + "abcdef"

    sample = "\n".join(
        [
            f"Authorization: Bearer {fake_bearer}",
            f"x-extra: bearer {fake_bearer}",
            f"google_key={fake_aiza}",
            f"oauth={fake_ya29}",
            f"rt={fake_refresh}",
            f"anthropic={fake_sk}",
            f"github={fake_ghp}",
            f"ws=wss://host/api/ws?ticket={fake_gwt}&token={fake_bearer}",
            f'{{"client_secret": "{fake_cs}", "refresh_token": "{fake_refresh}", "ok": "hello"}}',
            f'AWS_SECRET_ACCESS_KEY="{fake_aws}"',
            "normal log line: started session conv-123 in /home/jason/projects/app",
        ]
    )

    out = redact_text(sample)
    for secret in (
        fake_bearer,
        fake_aiza,
        fake_ya29,
        fake_refresh,
        fake_sk,
        fake_ghp,
        fake_gwt,
        fake_aws,
        fake_cs,
    ):
        assert secret not in out

    assert f"Authorization: Bearer {REDACTED}" in out
    assert f'"client_secret": "{REDACTED}"' in out
    assert f'"refresh_token": "{REDACTED}"' in out
    assert '"ok": "hello"' in out
    assert f'AWS_SECRET_ACCESS_KEY="{REDACTED}"' in out
    assert "normal log line: started session conv-123 in /home/jason/projects/app" in out


def test_config_redact_secrets_delegates_to_redaction_module() -> None:
    fake_key = "AIza" + "SyZ0987654321zyxwvutsrqponmlkji"
    assert redact_secrets(f"key={fake_key}") == f"key={REDACTED}"
    assert redact_text("") == ""
    assert redact_lines(["clean", f"Bearer {'abc12345678'}"]) == ["clean", f"Bearer {REDACTED}"]
