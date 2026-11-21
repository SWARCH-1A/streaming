"""Initialize independent audit credentials without rotating existing installations."""
import os
from pathlib import Path
import re
import runpy
import secrets


def initialize(env, state, run):
    keys = ("AUDIT_OWNER_DB_PASSWORD", "AUDIT_WRITER_DB_PASSWORD", "AUDIT_READER_DB_PASSWORD", "AUDIT_QUERY_TOKEN")
    for key in keys:
        env.setdefault(key, secrets.token_urlsafe(36))
        if not re.fullmatch(r"[A-Za-z0-9_-]{32,128}", env[key]):
            raise RuntimeError(key + " requires a private URL-safe value")
    certificate = runpy.run_path(str(Path(__file__).with_name("notifications_config.py")))["certificate"]
    for name in ("audit", "audit-db"):
        certificate(state, run, name, ())
    path = state / "environment.env"
    values = dict(line.split("=", 1) for line in path.read_text().splitlines())
    values.update({key: env[key] for key in keys})
    temporary = path.with_suffix(".audit.tmp")
    temporary.write_text("".join(f"{key}={value}\n" for key, value in sorted(values.items())))
    os.chmod(temporary, 0o600)
    os.replace(temporary, path)
    return env
