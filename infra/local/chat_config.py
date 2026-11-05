"""Add the Chat PostgreSQL certificate and password to an existing local installation without rotating others."""
import os
import re
import runpy
import secrets
from pathlib import Path

certificate = runpy.run_path(str(Path(__file__).with_name("notifications_config.py")))["certificate"]


def initialize(env, state, run):
    key = "CHAT_DB_PASSWORD"
    env.setdefault(key, secrets.token_urlsafe(36))
    if not re.fullmatch(r"[A-Za-z0-9_-]{32,128}", env[key]):
        raise RuntimeError(key + " must be a private URL-safe token of at least 32 characters")
    certificate(state, run, "chat-pg", ())
    path = state / "environment.env"
    values = dict(line.split("=", 1) for line in path.read_text().splitlines())
    values[key] = env[key]
    temporary = path.with_suffix(".chat.tmp")
    temporary.write_text("".join(f"{name}={value}\n" for name, value in sorted(values.items())))
    os.chmod(temporary, 0o600)
    os.replace(temporary, path)
    return env
