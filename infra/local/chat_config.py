"""Add the Chat PostgreSQL certificate, the Chat credentials and the Redis ACL to an existing local installation
without rotating others."""
import hashlib
import os
import re
import runpy
import secrets
from pathlib import Path

certificate = runpy.run_path(str(Path(__file__).with_name("notifications_config.py")))["certificate"]

KEYS = ("CHAT_DB_PASSWORD", "CHAT_REDIS_PASSWORD")


def redis_acl(password):
    """Zero Trust for the live Chat store: no anonymous user, one least-privilege identity for Chat.

    Only the SHA-256 of the password is written, so the file holds no secret. Chat keys all start with
    "chat:"; Chat does not use Pub/Sub, so no channel is granted, and administrative commands are denied.
    """
    digest = hashlib.sha256(password.encode()).hexdigest()
    return ("user default off\n"
            f"user chat on #{digest} ~chat:* resetchannels +@all -@dangerous\n")


def initialize(env, state, run):
    for key in KEYS:
        env.setdefault(key, secrets.token_urlsafe(36))
        # URL-safe alphabet: the Redis password travels inside CHAT_REDIS_URL without escaping.
        if not re.fullmatch(r"[A-Za-z0-9_-]{32,128}", env[key]):
            raise RuntimeError(key + " must be a private URL-safe token of at least 32 characters")
    certificate(state, run, "chat-pg", ())
    acl = state / "chat-redis.acl"
    temporary = acl.with_suffix(".tmp")
    temporary.write_text(redis_acl(env["CHAT_REDIS_PASSWORD"]))
    # Mounted read-only for the redis user of the container; it contains only the password hash.
    os.chmod(temporary, 0o644)
    os.replace(temporary, acl)
    path = state / "environment.env"
    values = dict(line.split("=", 1) for line in path.read_text().splitlines())
    for key in KEYS:
        values[key] = env[key]
    temporary = path.with_suffix(".chat.tmp")
    temporary.write_text("".join(f"{name}={value}\n" for name, value in sorted(values.items())))
    os.chmod(temporary, 0o600)
    os.replace(temporary, path)
    return env
