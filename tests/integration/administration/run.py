"""Disposable Core/PostgreSQL/Caddy + real Web/Chromium administration check.

Build services/core/target/core.jar first. Web SSR is built from the current checkout by this runner.
HTTP is confined to a private fixture network and a random loopback port. No demo volumes.
"""
from pathlib import Path
import os
import base64
import secrets
import subprocess
import time
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[3]


def run(*args, env=None, check=True):
    result = subprocess.run(args, cwd=ROOT, env=env, capture_output=True, text=True)
    if check and result.returncode:
        raise RuntimeError(f"Fixture command failed: {args[0]} {args[1]}; exit {result.returncode}")
    return result.stdout.strip()


def main():
    jar = ROOT / "services/core/target/core.jar"
    for output, sources in ((jar, ROOT / "services/core/src/main"),):
        if not output.exists() or any(p.stat().st_mtime > output.stat().st_mtime for p in sources.rglob("*") if p.is_file()):
            raise RuntimeError(f"Build the current checkout first: {output.relative_to(ROOT)}")
    prefix = "streaming-admin-test-" + uuid.uuid4().hex[:10]
    names = [prefix + suffix for suffix in ("-db", "-core", "-web", "-proxy")]
    db, core, web, proxy = names
    env = {**os.environ, "POSTGRES_PASSWORD": secrets.token_urlsafe(32),
           "CORE_RATE_LIMIT_HMAC_SECRET": secrets.token_urlsafe(48)}
    env.update(CORE_DB_PASSWORD=env["POSTGRES_PASSWORD"], CORE_DB_USER="fixture",
               CORE_RECOVERY_ENABLED="true", CORE_RECOVERY_ENCRYPTION_KEY=base64.b64encode(secrets.token_bytes(32)).decode(),
               CORE_DB_URL="jdbc:postgresql://" + db + ":5432/core", CORE_IMAGE_STORAGE_PROVIDER="filesystem",
               PROFILE_AVATAR_STORAGE="/tmp/avatars", CHANNELS_BANNER_STORAGE="/tmp/banners")
    run("docker", "network", "create", prefix)
    try:
        run("docker", "build", "-t", web, "-f", "apps/web/Dockerfile", ".")
        run("docker", "run", "--rm", "-d", "--name", db, "--network", prefix, "--tmpfs", "/var/lib/postgresql",
            "-e", "POSTGRES_USER=fixture", "-e", "POSTGRES_DB=core", "-e", "POSTGRES_PASSWORD", "postgres:18-alpine", env=env)
        run("docker", "run", "--rm", "-d", "--name", web, "--network", prefix,
            "-e", "PORT=3000", "-e", "WEB_API_UPSTREAM=http://" + core + ":8081", web)
        run("docker", "run", "--rm", "-d", "--name", proxy, "--network", prefix,
            "-p", "127.0.0.1::3000", "-e", "CORE_UPSTREAM=http://" + core + ":8081",
            "-e", "WEB_UPSTREAM=http://" + web + ":3000",
            "--mount", f"type=bind,source={ROOT / 'infra/reverse-proxy/Caddyfile'},target=/etc/caddy/Caddyfile,readonly",
            "caddy:2-alpine")
        port = run("docker", "port", proxy, "3000/tcp").rsplit(":", 1)[1]
        origin = "http://127.0.0.1:" + port
        env["WEB_ORIGIN"] = origin
        args = ["docker", "run", "--rm", "-d", "--name", core, "--network", prefix]
        for key in ("CORE_DB_URL", "CORE_DB_USER", "CORE_DB_PASSWORD", "CORE_RATE_LIMIT_HMAC_SECRET",
                    "CORE_RECOVERY_ENABLED", "CORE_RECOVERY_ENCRYPTION_KEY",
                    "CORE_IMAGE_STORAGE_PROVIDER", "PROFILE_AVATAR_STORAGE", "CHANNELS_BANNER_STORAGE", "WEB_ORIGIN"):
            args.extend(["-e", key])
        args.extend(["--mount", f"type=bind,source={jar},target=/app/core.jar,readonly", "eclipse-temurin:25-jdk", "java", "-jar", "/app/core.jar"])
        run(*args, env=env)
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        deadline = time.monotonic() + 120
        while True:
            try:
                with opener.open(origin + "/api/taxonomy", timeout=3) as response:
                    if response.status == 200:
                        break
            except (OSError, TimeoutError):
                pass
            if time.monotonic() > deadline:
                raise RuntimeError("Core did not become ready through Caddy in 120 seconds")
            time.sleep(1)
        env.update(ADMIN_TEST_ORIGIN=origin, ADMIN_TEST_DB=db)
        subprocess.run(["node", str(Path(__file__).with_name("browser.mjs"))], cwd=ROOT, env=env, check=True)
    finally:
        for name in reversed(names):
            run("docker", "rm", "-fv", name, check=False)
        run("docker", "network", "rm", prefix, check=False)
        run("docker", "image", "rm", web, check=False)


if __name__ == "__main__":
    main()
