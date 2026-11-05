"""Initialize independent VOD/S3 credentials without rotating existing installations."""
import os
from pathlib import Path
import re
import runpy
import secrets


def initialize(env, state, run):
    keys = ("VOD_DB_PASSWORD", "VOD_SIGNING_SECRET", "CORE_VOD_SERVICE_TOKEN", "VOD_CAPTURE_SERVICE_TOKEN",
            "VOD_CHAT_SERVICE_TOKEN", "VOD_GATEWAY_SERVICE_TOKEN", "MINIO_ROOT_USER", "MINIO_ROOT_PASSWORD",
            "VOD_S3_PASSWORD", "MEDIA_S3_PASSWORD", "MEDIA_VOD_DELIVERY_TOKEN")
    for key in keys:
        env.setdefault(key, secrets.token_urlsafe(36))
        if not re.fullmatch(r"[A-Za-z0-9_-]{32,128}", env[key]):
            raise RuntimeError(key + " requires a private URL-safe value")
    certificate = runpy.run_path(str(Path(__file__).with_name("notifications_config.py")))["certificate"]
    for name in ("vod", "vod-db", "minio", "gateway", "media-recorder"):
        certificate(state, run, name, ())
    path = state / "environment.env"
    values = dict(line.split("=", 1) for line in path.read_text().splitlines())
    values.update({key: env[key] for key in keys})
    temporary = path.with_suffix(".vod.tmp")
    temporary.write_text("".join(f"{key}={value}\n" for key, value in sorted(values.items())))
    os.chmod(temporary, 0o600)
    os.replace(temporary, path)
    return env
