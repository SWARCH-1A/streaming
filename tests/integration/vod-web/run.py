"""Isolated HTTPS Web/Core/Gateway/VOD/PostgreSQL/S3/AMQP browser fixture.

No fake authority or direct VOD SQL. Capture input is deterministic FFmpeg media,
submitted through the private capture API and a real RabbitMQ closure event.
Only uniquely named test containers are removed. No local/demo volumes are used.
"""
from pathlib import Path
import argparse
import base64
import hashlib
import http.cookiejar
import importlib.util
import json
import os
import secrets
import shutil
import ssl
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[3]


def run(*args, env=None, check=True, data=None):
    p = subprocess.run(args, cwd=ROOT, env=env, input=data, capture_output=True, text=True)
    if check and p.returncode:
        # Do not echo arguments/environment: they may contain fixture credentials.
        raise RuntimeError(f"Command failed: {args[0]} {args[1]} (exit {p.returncode})\n{p.stderr[-1800:]}")
    return (p.stdout + (p.stderr if args[:2] == ("docker", "logs") else "")).strip()


def wait(check, label, seconds=120):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            if check():
                return
        except (OSError, ValueError, RuntimeError):
            pass
        time.sleep(1)
    raise RuntimeError(f"Timed out: {label}")


class Client:
    def __init__(self, ca):
        self.cookies = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(self.cookies),
            urllib.request.HTTPSHandler(context=ssl.create_default_context(cafile=str(ca))))

    def call(self, url, method="GET", body=None, headers=None):
        h = dict(headers or {})
        if body is not None and not isinstance(body, bytes):
            body = json.dumps(body).encode()
            h.setdefault("Content-Type", "application/json")
        req = urllib.request.Request(url, data=body, method=method, headers=h)
        try:
            with self.opener.open(req, timeout=15) as response:
                raw = response.read()
                return json.loads(raw) if "application/json" in response.headers.get("Content-Type", "") else raw
        except urllib.error.HTTPError as e:
            detail = e.read().decode(errors="replace")[:600]
            raise RuntimeError(f"HTTP {e.code} {method} {urllib.parse.urlparse(url).path}: {detail}") from None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--browser", required=True, help="Browser entry relative to this folder (subtitles.mjs or administration.mjs, when present)")
    parser.add_argument("--skip-build", action="store_true", help="Use images already built from this exact checkout")
    args = parser.parse_args()
    browser_script = Path(__file__).with_name(args.browser).resolve()
    if not browser_script.is_file():
        raise RuntimeError("Browser test entry not found")
    openssl = shutil.which("openssl")
    if not openssl and os.name == "nt":
        candidate = Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "Git/usr/bin/openssl.exe"
        if candidate.is_file():
            openssl = str(candidate)
    if not openssl:
        raise RuntimeError("OpenSSL is required (also included with Git for Windows)")
    images = {"core": "streaming-spec22-core", "vod": "streaming-spec22-vod", "web": "streaming-vod-web-test", "gateway": "streaming-vod-gateway-test", "minio": "streaming-vod-minio"}
    if not args.skip_build:
        for service, dockerfile, context in [
            ("core", "services/core/Dockerfile", "services/core"),
            ("vod", "services/vod/Dockerfile", "services/vod"),
            ("gateway", "services/gateway/Dockerfile", "."),
            ("web", "apps/web/Dockerfile", "."),
            ("minio", "infra/media/Dockerfile.minio", "."),
        ]:
            print(f"Building {service} from current checkout", flush=True)
            run("docker", "build", "-t", images[service], "-f", dockerfile, context)
    prefix = "streaming-vod-web-" + uuid.uuid4().hex[:10]
    names = []
    with tempfile.TemporaryDirectory(prefix=prefix) as directory:
        temp = Path(directory)
        cert = temp / "ca.crt"
        (temp / "openssl.cnf").write_text("[req]\ndistinguished_name=dn\n[dn]\n", encoding="utf-8")
        run(openssl, "req", "-config", str(temp / "openssl.cnf"), "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2", "-keyout", str(temp / "key.pem"), "-out", str(cert),
            "-subj", "/CN=Vault disposable test", "-addext", "subjectAltName=DNS:localhost,DNS:media,DNS:broker,IP:127.0.0.1", "-addext", "basicConstraints=critical,CA:TRUE")
        mount = f"type=bind,source={temp},target=/fixture"
        run("docker", "run", "--rm", "--mount", mount, "eclipse-temurin:25-jdk", "keytool", "-importcert", "-noprompt", "-alias", "fixture", "-file", "/fixture/ca.crt", "-keystore", "/fixture/trust.p12", "-storepass", "changeit")
        secret = lambda: secrets.token_urlsafe(36)
        passwords = {name: secret() for name in ["operator", "core", "vod", "streaming"]}
        spec = importlib.util.spec_from_file_location("topology", ROOT / "infra/broker/topology.py")
        topology = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(topology)
        (temp / "definitions.json").write_text(json.dumps(topology.definitions(passwords)), encoding="utf-8")
        (temp / "rabbitmq.conf").write_text("listeners.tcp = none\nlisteners.ssl.default = 5671\nssl_options.cacertfile = /fixture/ca.crt\nssl_options.certfile = /fixture/ca.crt\nssl_options.keyfile = /fixture/key.pem\nssl_options.verify = verify_none\nssl_options.fail_if_no_peer_cert = false\nmanagement.load_definitions = /fixture/definitions.json\n", encoding="utf-8")
        dbpass, s3pass, coretoken, capture, delivery, gatewaytoken = [secret() for _ in range(6)]
        run("docker", "network", "create", prefix)

        def start(role, image, environment=None, extra=(), command=()):
            name = prefix + "-" + role
            names.append(name)
            params = ["docker", "run", "-d", "--name", name, "--network", prefix, "--network-alias", role, "--mount", mount]
            env = {**os.environ, **(environment or {})}
            for key in environment or {}:
                params += ["-e", key]
            return run(*params, *extra, image, *command, env=env)

        def port(role, internal):
            return int(run("docker", "port", prefix + "-" + role, str(internal) + "/tcp").rsplit(":", 1)[1])

        client = Client(cert)
        try:
            for role in ["core-db", "vod-db"]:
                start(role, "postgres:18-alpine", {"POSTGRES_USER": "fixture", "POSTGRES_DB": "core" if role == "core-db" else "vod_test", "POSTGRES_PASSWORD": dbpass}, extra=("--tmpfs", "/var/lib/postgresql"))
                wait(lambda: "accepting connections" in run("docker", "exec", prefix + "-" + role, "pg_isready", "-U", "fixture", check=False), role)
            start("minio", images["minio"], {"MINIO_ROOT_USER": "fixture", "MINIO_ROOT_PASSWORD": s3pass}, command=("server", "/data"))
            minio_env = {**os.environ, "MC_HOST_fixture": f"http://fixture:{s3pass}@minio:9000"}
            wait(lambda: "created" in run("docker", "run", "--rm", "--network", prefix, "-e", "MC_HOST_fixture", "--entrypoint", "mc", images["minio"], "mb", "--ignore-existing", "fixture/vod-test", env=minio_env, check=False), "MinIO")
            start("broker", "rabbitmq:4.1-management", extra=("-p", "127.0.0.1::15672", "--mount", f"type=bind,source={temp / 'rabbitmq.conf'},target=/etc/rabbitmq/rabbitmq.conf,readonly"))
            broker_url = f"http://127.0.0.1:{port('broker', 15672)}"
            broker_auth = {"Authorization": "Basic " + base64.b64encode(("operator:" + passwords["operator"]).encode()).decode()}
            wait(lambda: bool(client.call(broker_url + "/api/overview", headers=broker_auth)), "RabbitMQ")
            caddy = (ROOT / "infra/reverse-proxy/Caddyfile").read_text(encoding="utf-8").replace(":3000 {", ":3443 {\n  tls /fixture/ca.crt /fixture/key.pem")
            (temp / "Caddyfile").write_text(caddy, encoding="utf-8")
            start("proxy", "caddy:2-alpine", {"CORE_UPSTREAM": "http://core:8081", "VOD_UPSTREAM": "http://vod:8098", "GATEWAY_UPSTREAM": "http://gateway:8097", "WEB_UPSTREAM": "http://web:3000"}, extra=("-p", "127.0.0.1::3443", "--mount", f"type=bind,source={temp / 'Caddyfile'},target=/etc/caddy/Caddyfile,readonly"))
            origin = f"https://127.0.0.1:{port('proxy', 3443)}"
            start("core", images["core"], {
                "CORE_DB_URL": "jdbc:postgresql://core-db:5432/core", "CORE_DB_USER": "fixture", "CORE_DB_PASSWORD": dbpass,
                "CORE_RATE_LIMIT_HMAC_SECRET": secret(), "CORE_IMAGE_STORAGE_PROVIDER": "filesystem", "PROFILE_AVATAR_STORAGE": "/tmp/avatars", "CHANNELS_BANNER_STORAGE": "/tmp/banners",
                "CORE_INTERNAL_ENABLED": "true", "CORE_INTERNAL_DEVELOPMENT_HTTP": "true", "CORE_STREAMING_SERVICE_TOKEN": secret(), "CORE_VOD_SERVICE_TOKEN": coretoken,
                "WEB_ORIGIN": origin, "CORE_SECURE_COOKIE": "true", "CORE_EVENTS_TRANSPORT": "amqp",
                "SPRING_RABBITMQ_HOST": "broker", "SPRING_RABBITMQ_PORT": "5671", "SPRING_RABBITMQ_USERNAME": "core", "SPRING_RABBITMQ_PASSWORD": passwords["core"],
                "JAVA_TOOL_OPTIONS": "-Djavax.net.ssl.trustStore=/fixture/trust.p12 -Djavax.net.ssl.trustStorePassword=changeit",
            })
            wait(lambda: bool(client.call(origin + "/api/taxonomy")), "Core through HTTPS")
            s3 = {"ENDPOINT": "http://minio:9000", "BUCKET": "vod-test", "PREFIX": "vod", "REGION": "us-east-1", "ACCESS_KEY": "fixture", "SECRET_KEY": s3pass}
            start("media", images["vod"], {
                **{"MEDIA_S3_" + key: value for key, value in s3.items()},
                "MEDIA_VOD_DELIVERY_TOKEN": delivery, "MEDIA_TLS_CERT_FILE": "/fixture/ca.crt", "MEDIA_TLS_KEY_FILE": "/fixture/key.pem", "SSL_CERT_FILE": "/fixture/ca.crt",
                "STREAMING_MEDIA_ADAPTER_SERVICE_TOKEN": secret(), "VOD_CAPTURE_TOKEN": capture,
                "RECORDER_BROKER_URL": f"amqps://streaming:{passwords['streaming']}@broker:5671/streaming-p2",
                "RECORDER_SPOOL": "/tmp/recordings", "RECORDER_NATIVE_DIR": "/tmp/native",
            }, extra=("--entrypoint", "media-recorder"))
            start("vod", images["vod"], {
                **{"VOD_S3_" + key: value for key, value in s3.items()},
                "VOD_DATABASE_URL": f"postgres://fixture:{dbpass}@vod-db:5432/vod_test?sslmode=disable", "VOD_CORE_URL": "http://core:8082", "VOD_CORE_TOKEN": coretoken,
                "VOD_SIGNING_SECRET": secret(), "VOD_PUBLIC_ORIGIN": origin, "VOD_MEDIA_URL": "https://media:8890", "MEDIA_VOD_DELIVERY_TOKEN": delivery,
                "VOD_BROKER_URL": f"amqps://vod:{passwords['vod']}@broker:5671/streaming-p2", "VOD_BROKER_CA_FILE": "/fixture/ca.crt", "SSL_CERT_FILE": "/fixture/ca.crt",
                "VOD_CAPTURE_TOKEN": capture, "VOD_CHAT_TOKEN": secret(), "VOD_GATEWAY_TOKEN": gatewaytoken, "VOD_DEVELOPMENT_HTTP": "true", "VOD_HEALTH_URL": "http://localhost:8098/readyz",
            }, extra=("-p", "127.0.0.1::8099"))
            start("gateway", images["gateway"], {"GATEWAY_CORE_URL": "http://core:8081", "GATEWAY_VOD_URL": "http://vod:8098", "GATEWAY_VOD_INTERNAL_URL": "http://vod:8099", "GATEWAY_VOD_TOKEN": gatewaytoken, "GATEWAY_PUBLIC_ORIGIN": origin, "GATEWAY_DEVELOPMENT_HTTP": "true"})
            start("web", images["web"], {"PORT": "3000", "WEB_API_UPSTREAM": "http://core:8081", "WEB_GATEWAY_UPSTREAM": "http://gateway:8097", "WEB_VOD_UPSTREAM": "http://vod:8098"})
            wait(lambda: client.call(origin + "/api/vods") is not None, "VOD through HTTPS")
            handle = "owner_" + uuid.uuid4().hex[:8]
            password = secret()

            def mutate(path, data):
                csrf = client.call(origin + "/api/identity/csrf")
                return client.call(origin + path, "POST", data, {csrf["headerName"]: csrf["token"], "Origin": origin, "Idempotency-Key": str(uuid.uuid4())})

            account = mutate("/api/identity/registrations", {"handle": handle, "email": handle + "@example.test", "password": password})
            mutate("/api/identity/sessions", {"login": handle, "password": password})
            channel = client.call(origin + "/api/channels/by-owner/" + account["userId"])["channel"]
            catalogue = client.call(origin + "/api/taxonomy")
            private = f"http://127.0.0.1:{port('vod', 8099)}"
            capture_headers = {"X-Service-Name": "media-capture", "X-Service-Token": capture}
            # Four real MPEG-TS segments, each 16 seconds, make a continuous 64-second VOD.
            run("docker", "run", "--rm", "--user", "0", "--mount", mount, "--entrypoint", "ffmpeg", images["vod"],
                "-nostdin", "-v", "error", "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=25", "-t", "64", "-c:v", "libx264", "-threads", "1", "-preset", "ultrafast", "-g", "50", "-pix_fmt", "yuv420p", "-f", "segment", "-segment_time", "16", "-segment_format", "mpegts", "/fixture/segment-%d.ts")
            recordings = []
            for number in range(2):
                session = "ses_" + uuid.uuid4().hex
                path = private + "/internal/vod/recordings/" + session
                start_body = {"sessionId": session, "streamId": "str_" + uuid.uuid4().hex, "channelId": channel["channelId"], "streamGeneration": 1, "title": "Prueba de subtítulos " + str(number + 1), "description": "Grabación sintética de 64 segundos", "categoryId": catalogue["categories"][0]["id"], "tagIds": [], "autopublish": True, "preferenceVersion": 0}
                recording = client.call(path, "PUT", start_body, {**capture_headers, "Idempotency-Key": str(uuid.uuid4())})
                segments = []
                for n in range(4):
                    segment = {"sessionId": session, "renditionId": "rend_fixture", "sourceGeneration": 1, "sequence": n, "sessionStartMs": n * 16000, "durationMs": 16000, "discontinuity": False}
                    raw = (temp / f"segment-{n}.ts").read_bytes()
                    ref = client.call(path + f"/objects/segment:rend_fixture:1:{n}", "PUT", raw, {**capture_headers, "Idempotency-Key": str(uuid.uuid4()), "Content-Type": "video/mp2t", "X-Object-SHA256": hashlib.sha256(raw).hexdigest(), "X-Segment-Metadata": base64.urlsafe_b64encode(json.dumps(segment).encode()).decode().rstrip("=")})
                    segments.append({**segment, "object": ref})
                index = {"sessionId": session, "streamGeneration": 1, "renditions": [{"renditionId": "rend_fixture", "width": 320, "height": 180, "bitrateBps": 250000, "videoCodec": "avc1.42c00d", "audioCodec": None}], "segments": segments}
                raw = json.dumps(index).encode()
                ref = client.call(path + "/objects/index", "PUT", raw, {**capture_headers, "Idempotency-Key": str(uuid.uuid4()), "Content-Type": "application/json", "X-Object-SHA256": hashlib.sha256(raw).hexdigest()})
                event = {"eventId": str(uuid.uuid4()), "eventType": "recording.closed.v1", "schemaVersion": 1, "producer": "streaming", "aggregateId": "recording:" + session, "sequence": 1, "occurredAtUtc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "correlationId": "req_" + uuid.uuid4().hex, "payload": {"sessionId": session, "streamGeneration": 1, "index": ref}}
                response = client.call(broker_url + "/api/exchanges/streaming-p2/p2.events/publish", "POST", {"properties": {"content_type": "application/json", "delivery_mode": 2}, "routing_key": "recording.closed.v1", "payload": json.dumps(event), "payload_encoding": "string"}, broker_auth)
                if not response.get("routed"):
                    raise RuntimeError("Closure event was not routed")
                wait(lambda: client.call(origin + "/api/vods/" + recording["vodId"])["processingState"] == "READY", "recording READY")
                recordings.append(client.call(origin + "/api/vods/" + recording["vodId"]))
            state = {"origin": origin, "prefix": prefix, "owner": account, "handle": handle, "password": password, "vods": recordings, "cookies": [{"name": c.name, "value": c.value, "domain": "127.0.0.1", "path": c.path, "secure": c.secure, "httpOnly": c.has_nonstandard_attr("HttpOnly"), "sameSite": "Lax"} for c in client.cookies]}
            state_file = temp / "browser.json"
            state_file.write_text(json.dumps(state), encoding="utf-8")
            print("Real fixture ready: HTTPS, Core, VOD, PostgreSQL, S3, RabbitMQ, Media and 2 x 64s recordings", flush=True)
            subprocess.run(["node", str(browser_script)], cwd=ROOT, env={**os.environ, "VOD_WEB_FIXTURE": str(state_file)}, check=True)
        except Exception:
            for role in ("core", "vod", "broker", "proxy", "web"):
                log = run("docker", "logs", "--tail", "12", prefix + "-" + role, check=False)
                for value in (dbpass, s3pass, coretoken, capture, delivery, gatewaytoken, *passwords.values()):
                    log = log.replace(value, "[fixture secret]")
                print(role + ": " + log[-2200:], flush=True)
            raise
        finally:
            for name in reversed(names):
                run("docker", "rm", "-fv", name, check=False)
            run("docker", "network", "rm", prefix, check=False)


if __name__ == "__main__":
    main()
