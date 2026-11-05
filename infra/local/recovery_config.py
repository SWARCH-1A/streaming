"""Initialize Cuentas recovery keys; preserve existing account data and installation secrets."""
import base64
import os
import re
import secrets


def initialize(env, state, run):
    defaults = {
        "CORE_RECOVERY_ENABLED": "true",
        "CORE_RECOVERY_ENCRYPTION_KEY": base64.b64encode(secrets.token_bytes(32)).decode(),
        "CORE_RECOVERY_SERVICE_TOKEN": secrets.token_urlsafe(36),
    }
    for key, value in defaults.items():
        env.setdefault(key, value)
    if env["CORE_RECOVERY_ENABLED"] not in ("true", "false"):
        raise RuntimeError("CORE_RECOVERY_ENABLED must be true or false")
    try:
        if len(base64.b64decode(env["CORE_RECOVERY_ENCRYPTION_KEY"], validate=True)) != 32:
            raise ValueError()
    except ValueError:
        raise RuntimeError("Recovery encryption key must be base64 encoding 32 bytes") from None
    if not re.fullmatch(r"[A-Za-z0-9_-]{32,128}", env["CORE_RECOVERY_SERVICE_TOKEN"]):
        raise RuntimeError("CORE_RECOVERY_SERVICE_TOKEN must be a private URL-safe token of at least 32 characters")
    path = state / "environment.env"
    values = dict(line.split("=", 1) for line in path.read_text().splitlines())
    values.update({key: env[key] for key in defaults})
    temporary = path.with_suffix(".recovery.tmp")
    temporary.write_text("".join(f"{key}={value}\n" for key, value in sorted(values.items())))
    os.chmod(temporary, 0o600)
    os.replace(temporary, path)
    return env
