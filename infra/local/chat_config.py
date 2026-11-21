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
    Chat runs its Lua scripts with EVALSHA/EVAL and uses no Redis Functions, so SCRIPT FLUSH and every
    FUNCTION subcommand are denied as well.
    """
    digest = hashlib.sha256(password.encode()).hexdigest()
    return ("user default off\n"
            f"user chat on #{digest} ~chat:* resetchannels +@all -@dangerous -script|flush -function\n")


def initialize(env, state, run):
    for key in KEYS:
        env.setdefault(key, secrets.token_urlsafe(36))
        # URL-safe alphabet: the Redis password travels inside CHAT_REDIS_URL without escaping.
        if not re.fullmatch(r"[A-Za-z0-9_-]{32,128}", env[key]):
            raise RuntimeError(key + " must be a private URL-safe token of at least 32 characters")
    certificate(state, run, "chat-pg", ())
    acl = state / "chat-redis.acl"
    if acl.is_dir():
        # Docker creates an empty directory when it bind-mounts a missing file; rmdir refuses non-empty ones.
        acl.rmdir()
    content = redis_acl(env["CHAT_REDIS_PASSWORD"])
    if not acl.is_file() or acl.read_text() != content:
        # Rewrite in place, never replace: Docker bind-mounts this single file, and a new file would leave
        # the existing chat-db container unable to restart until it is recreated.
        with open(acl, "w") as handle:
            handle.write(content)
    # Mounted read-only for the redis user of the container; it contains only the password hash.
    os.chmod(acl, 0o644)
    path = state / "environment.env"
    values = dict(line.split("=", 1) for line in path.read_text().splitlines())
    for key in KEYS:
        values[key] = env[key]
    temporary = path.with_suffix(".chat.tmp")
    temporary.write_text("".join(f"{name}={value}\n" for name, value in sorted(values.items())))
    os.chmod(temporary, 0o600)
    os.replace(temporary, path)
    return env
